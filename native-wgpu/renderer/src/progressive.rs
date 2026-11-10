//! Exact measured interiors: a terrain tile drawn from its own elevation before
//! the neighbouring elevation for its outer rim has arrived. Quadtree cells give
//! every consumer the same fine/parent owner, without an invented height in the
//! missing L-shaped rim, which stays with the complete parent. Several disjoint
//! interiors may be staged; each keeps its own parent and skirt checks.
use crate::*;
/// Bound on simultaneously staged provisional interiors.
pub(crate) const MAX_INTERIORS: usize = 16;
pub(crate) struct Interior {
    pub id: TileId,
    pub parent: TileId,
    parent_generation: u64,
    max_drop: f64,
    pub token: u64,
    regions: Vec<TileId>,
}
fn regions(id: TileId, rect: [usize; 4]) -> Vec<TileId> {
    fn split(tile: TileId, local: [usize; 4], target: [usize; 4], out: &mut Vec<TileId>) {
        if local[2] <= target[0]
            || local[0] >= target[2]
            || local[3] <= target[1]
            || local[1] >= target[3]
        {
            return;
        }
        if local[0] >= target[0]
            && local[1] >= target[1]
            && local[2] <= target[2]
            && local[3] <= target[3]
        {
            out.push(tile);
            return;
        }
        let mx = (local[0] + local[2]) / 2;
        let my = (local[1] + local[3]) / 2;
        for (child, r) in tile.children().into_iter().zip([
            [local[0], local[1], mx, my],
            [mx, local[1], local[2], my],
            [local[0], my, mx, local[3]],
            [mx, my, local[2], local[3]],
        ]) {
            split(child, r, target, out);
        }
    }
    let mut out = Vec::new();
    split(id, [0, 0, 128, 128], rect, &mut out);
    out
}
impl Renderer {
    pub(crate) fn physical_geometry(&self, id: TileId) -> TileId {
        if self.tiles.get(&id).is_some_and(|t| t.field.complete()) {
            return id;
        }
        self.interiors
            .values()
            .find(|p| p.regions.contains(&id))
            .map_or(id, |p| p.id)
    }
    pub(crate) fn physical_sources(&self, sources: Vec<TileId>) -> Vec<TileId> {
        let mut ids: Vec<_> = sources
            .into_iter()
            .map(|id| self.physical_geometry(id))
            .collect();
        ids.sort_unstable_by_key(|id| (id.z, id.x, id.y));
        ids.dedup();
        ids
    }
    pub(crate) fn geometry_cover_sources(&self) -> Vec<TileId> {
        let mut ids: Vec<_> = self
            .tiles
            .iter()
            .filter(|(_, t)| t.field.complete())
            .map(|(id, _)| *id)
            .collect();
        let complete = ids.len();
        for p in self.interiors.values() {
            if self.tiles.contains_key(&p.id)
                && ids[..complete]
                    .iter()
                    .any(|id| *id != p.id && id.contains(p.id))
            {
                ids.extend(p.regions.iter().copied());
            }
        }
        ids
    }
    pub fn clear_interior(&mut self, id: TileId, token: u64) {
        if self
            .interiors
            .get(&id)
            .is_some_and(|p| p.token == token)
        {
            self.interiors.remove(&id);
            if self.tiles.get(&id).is_some_and(|t| !t.field.complete()) {
                self.tiles.remove(&id);
            }
            self.cover_dirty = true;
            self.seams_dirty = true;
        }
    }
    pub fn upload_interior(
        &mut self,
        id: TileId,
        token: u64,
        heights: Vec<f32>,
        rect: [usize; 4],
    ) -> Result<(), UploadError> {
        let expected = [
            if id.x % 2 == 0 { 1 } else { 0 },
            if id.y % 2 == 0 { 1 } else { 0 },
            if id.x % 2 == 0 { 128 } else { 127 },
            if id.y % 2 == 0 { 128 } else { 127 },
        ];
        if !self.map_style.three_d
            || id.z == 0
            || id.z > 19
            || rect != expected
            || self.has_geometry(id)
        {
            return Err(UploadError::InvalidGeometry);
        }
        if !self
            .tiles
            .iter()
            .any(|(old, t)| *old != id && old.contains(id) && t.field.complete())
            || !self.images.keys().any(|old| old.contains(id))
        {
            return Err(UploadError::Capacity);
        }
        if heights.len() != 129 * 129 || heights.iter().any(|h| !h.is_finite()) {
            return Err(UploadError::InvalidGeometry);
        }
        if self
            .tiles
            .iter()
            .any(|(old, t)| *old != id && id.contains(*old) && t.field.complete())
        {
            return Err(UploadError::Capacity);
        }
        let (parent, parent_tile) = self
            .tiles
            .iter()
            .filter(|(old, t)| **old != id && old.contains(id) && t.field.complete())
            .max_by_key(|(old, _)| old.z)
            .unwrap();
        let parent = *parent;
        if self
            .submitted_pick
            .as_ref()
            .is_none_or(|snapshot| !snapshot.owns_rectangle(id, &parent_tile.field))
        {
            return Err(UploadError::Capacity);
        }
        let parent_generation = parent_tile.generation;
        let pb = parent.bounds();
        let b = id.bounds();
        let span = b[2] - b[0];
        let ps = pb[2] - pb[0];
        let mut max_drop = 0f64;
        let logical = self
            .logical_zoom
            .or_else(|| self.last_view.map(|v| v.zoom))
            .ok_or(UploadError::Capacity)?;
        let skirt = ridgeline_core::surface::skirt_length(logical);
        // Unaligned parent edges cannot provide an upward skirt. Decline those
        // cases; never invent a bridge or expose a crack to reveal detail early.
        for y in rect[1]..=rect[3] {
            for x in rect[0]..=rect[2] {
                if x != rect[0] && x != rect[2] && y != rect[1] && y != rect[3] {
                    continue;
                }
                let h = parent_tile
                    .field
                    .sample(
                        (b[0] + x as f64 / 128. * span - pb[0]) / ps,
                        (b[1] + y as f64 / 128. * span - pb[1]) / ps,
                    )
                    .ok_or(UploadError::Capacity)?;
                let drop = heights[y * 129 + x] as f64 - h;
                if drop < 0. || drop > skirt {
                    return Err(UploadError::Capacity);
                }
                max_drop = max_drop.max(drop);
            }
        }
        // Interiors stay disjoint, so each keeps an unambiguous rim owner.
        if self
            .interiors
            .values()
            .any(|p| p.id != id && (p.id.contains(id) || id.contains(p.id)))
        {
            return Err(UploadError::Capacity);
        }
        if let Some(old) = self.interiors.get(&id) {
            let token = old.token;
            self.clear_interior(id, token);
        }
        if self.interiors.len() >= MAX_INTERIORS {
            return Err(UploadError::Capacity);
        }
        self.upload_geometry_region(id, heights, rect)?;
        self.interiors.insert(id, Interior {
            id,
            parent,
            parent_generation,
            max_drop,
            token,
            regions: regions(id, rect),
        });
        self.cover_dirty = true;
        self.seams_dirty = true;
        Ok(())
    }
    pub(crate) fn validate_interior(&mut self, logical: f64) {
        let skirt = ridgeline_core::surface::skirt_length(logical);
        let invalid: Vec<_> = self
            .interiors
            .values()
            .filter(|p| {
                self.tiles
                    .get(&p.parent)
                    .is_none_or(|t| t.generation != p.parent_generation)
                    || p.max_drop > skirt
            })
            .map(|p| (p.id, p.token))
            .collect();
        for (id, token) in invalid {
            self.clear_interior(id, token);
        }
    }
    pub(crate) fn draw_mask(&self, row: &ridgeline_core::surface::SurfacePatch) -> u8 {
        row.skirt_override.unwrap_or_else(|| {
            ridgeline_core::surface::skirt_edges(row.geometry_region, row.region)
                & self
                    .edge_masks
                    .get(&row.geometry_region)
                    .copied()
                    .unwrap_or(15)
        })
    }
    pub(crate) fn draw_clipped(&self, row: &ridgeline_core::surface::SurfacePatch) -> bool {
        if row.clip.is_none() {
            return row.region.z - row.geometry.z > 7;
        }
        let r = ridgeline_core::surface::GridRect::for_bounds(row.geometry, row.bounds());
        let b = row.geometry.bounds();
        let size = b[2] - b[0];
        let clip = row.bounds();
        [
            (clip[0] - b[0]) / size,
            (clip[1] - b[1]) / size,
            (clip[2] - b[0]) / size,
            (clip[3] - b[1]) / size,
        ] != [
            r.x0 as f64 / 128.,
            r.y0 as f64 / 128.,
            r.x1 as f64 / 128.,
            r.y1 as f64 / 128.,
        ]
    }
    /// CPU ownership stays exact; rectangular GPU clips avoid hundreds of tiny
    /// quadtree draws for a one-cell-wide L-shaped parent rim.
    pub(crate) fn coalesce_interior_draws(&mut self, images: &[TileId]) {
        let mut ids: Vec<_> = self.interiors.keys().copied().collect();
        ids.sort_unstable_by_key(|id| (id.z, id.x, id.y));
        for id in ids {
            self.coalesce_one_interior(id, images);
        }
    }
    fn coalesce_one_interior(&mut self, id: TileId, images: &[TileId]) {
        use ridgeline_core::surface::SurfacePatch;
        let Some(p) = self.interiors.get(&id) else {
            return;
        };
        if !self.cover.iter().any(|row| row.source == p.id) {
            return;
        }
        let b = p.id.bounds();
        let span = b[2] - b[0];
        let Some(tile) = self.tiles.get(&p.id) else {
            return;
        };
        let r = tile.field.valid_rect();
        let fine = [
            b[0] + span * r[0] as f64 / 128.,
            b[1] + span * r[1] as f64 / 128.,
            b[0] + span * r[2] as f64 / 128.,
            b[1] + span * r[3] as f64 / 128.,
        ];
        let column = if r[0] > 0 {
            [b[0], b[1], fine[0], b[3]]
        } else {
            [fine[2], b[1], b[2], b[3]]
        };
        let horizontal = if r[1] > 0 {
            [fine[0], b[1], fine[2], fine[1]]
        } else {
            [fine[0], fine[3], fine[2], b[3]]
        };
        let domains = [(p.id, fine), (p.parent, column), (p.parent, horizontal)];
        let mut masks = [0u8; 3];
        for (index, (source, domain)) in domains.iter().enumerate() {
            for row in &self.cover {
                if row.source != *source || !p.id.contains(row.region) {
                    continue;
                }
                let q = row.region.bounds();
                let mask = self.edge_masks.get(&row.region).copied().unwrap_or(15);
                if q[0] == domain[0] {
                    masks[index] |= mask & 1;
                }
                if q[2] == domain[2] {
                    masks[index] |= mask & 2;
                }
                if q[1] == domain[1] {
                    masks[index] |= mask & 4;
                }
                if q[3] == domain[3] {
                    masks[index] |= mask & 8;
                }
            }
        }
        let photos = ridgeline_core::resident_cover(images);
        let mut next = Vec::new();
        for row in &self.draws {
            if !p.id.contains(row.region) {
                next.push(*row);
            }
        }
        for (index, (source, domain)) in domains.iter().enumerate() {
            for photo in &photos {
                let q = photo.region.bounds();
                let clip = [
                    domain[0].max(q[0]),
                    domain[1].max(q[1]),
                    domain[2].min(q[2]),
                    domain[3].min(q[3]),
                ];
                if clip[0] >= clip[2] || clip[1] >= clip[3] {
                    continue;
                }
                let mut mask = masks[index];
                if clip[0] != domain[0] {
                    mask &= !1;
                }
                if clip[2] != domain[2] {
                    mask &= !2;
                }
                if clip[1] != domain[1] {
                    mask &= !4;
                }
                if clip[3] != domain[3] {
                    mask &= !8;
                }
                next.push(SurfacePatch {
                    geometry: *source,
                    geometry_region: p.id,
                    image: photo.source,
                    region: p.id,
                    clip: Some(clip),
                    skirt_override: Some(mask),
                });
            }
        }
        self.draws = next;
    }
}
