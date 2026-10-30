//! Share the previous combined terrain/photo payload allowance. Images may use
//! idle terrain space; demanded coverage is never sacrificed to meet a cache target.
use super::*;
const HEIGHT_BYTES: u64 = 129 * 129 * 4;
const IMAGE_BYTES: u64 = 349524; // RGBA8, all nine original-resolution mip levels.
impl Renderer {
    pub fn set_coordinated_retention(&mut self, base: usize) {
        let base = base.clamp(24, 128);
        self.residency_byte_budget =
            Some(base as u64 * IMAGE_BYTES + ((base / 2) * 3).min(192) as u64 * HEIGHT_BYTES);
        self.budget = (base + base / 4).min(160);
        self.geometry_budget = ((base / 2) * 3).clamp(16, 192);
        self.trim_geometry();
        self.trim_images();
        self.trim_joint();
        self.cover_dirty = true;
    }
    pub fn resident_payload_bytes(&self) -> u64 {
        self.tiles.len() as u64 * HEIGHT_BYTES + self.images.len() as u64 * IMAGE_BYTES
    }
    pub fn resident_payload_target(&self) -> Option<u64> {
        self.residency_byte_budget
    }
    pub fn has_image(&self, id: TileId) -> bool {
        self.images.contains_key(&id)
    }
    pub fn has_geometry(&self, id: TileId) -> bool {
        self.tiles.contains_key(&id)
    }
    pub(crate) fn trim_joint(&mut self) {
        let Some(limit) = self.residency_byte_budget else {
            return;
        };
        if self.resident_payload_bytes() <= limit {
            return;
        }
        let mut ground: HashSet<_> = self.active_sources(true).into_iter().collect();
        let mut photos: HashSet<_> = self.active_sources(false).into_iter().collect();
        if let Some((g, i)) = &self.demand {
            ground.extend(g);
            photos.extend(i);
        }
        while self.resident_payload_bytes() > limit {
            // Photographs are more expensive to restore (decode/composite/mips).
            // Prefer releasing equally old prepared terrain over warm photographs.
            let g = self
                .tiles
                .iter()
                .filter(|(id, _)| !ground.contains(id))
                .max_by_key(|(_, t)| self.clock - t.used)
                .map(|(id, t)| (*id, (self.clock - t.used) as f64));
            let i = self
                .images
                .iter()
                .filter(|(id, _)| !photos.contains(id))
                .max_by_key(|(_, t)| self.clock - t.used)
                .map(|(id, t)| (*id, (self.clock - t.used) as f64 / 4.));
            match (g, i) {
                (Some((id, age)), photo) if photo.is_none_or(|(_, p)| age >= p) => {
                    self.tiles.remove(&id);
                    self.seams_dirty = true;
                }
                (_, Some((id, _))) => {
                    self.images.remove(&id);
                }
                _ => break, // Explicit count caps still bound protected-coverage debt.
            }
            self.cover_dirty = true;
        }
    }
}
