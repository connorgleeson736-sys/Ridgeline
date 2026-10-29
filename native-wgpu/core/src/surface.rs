//! Independent native terrain geometry and full-resolution image ownership.
use crate::{TileId, resident_cover};
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct SurfacePatch {
    pub geometry: TileId,
    pub image: TileId,
    pub region: TileId,
}
pub fn surface_cover(geometry: &[TileId], images: &[TileId]) -> Vec<SurfacePatch> {
    let ground = resident_cover(geometry);
    let photos = resident_cover(images);
    let mut out = Vec::new();
    for g in &ground {
        for i in &photos {
            let region = if g.region.contains(i.region) {
                i.region
            } else if i.region.contains(g.region) {
                g.region
            } else {
                continue;
            };
            out.push(SurfacePatch {
                geometry: g.source,
                image: i.source,
                region,
            });
        }
    }
    out
}
#[derive(Clone, Copy, Debug, Hash, PartialEq, Eq)]
pub struct GridRect {
    pub x0: u16,
    pub y0: u16,
    pub x1: u16,
    pub y1: u16,
}
impl GridRect {
    pub fn for_region(geometry: TileId, region: TileId) -> Self {
        assert!(geometry.contains(region));
        let g = geometry.bounds();
        let r = region.bounds();
        let scale = 128. / (g[2] - g[0]);
        Self {
            x0: ((r[0] - g[0]) * scale).floor().clamp(0., 127.) as u16,
            y0: ((r[1] - g[1]) * scale).floor().clamp(0., 127.) as u16,
            x1: ((r[2] - g[0]) * scale).ceil().clamp(1., 128.) as u16,
            y1: ((r[3] - g[1]) * scale).ceil().clamp(1., 128.) as u16,
        }
    }
    pub fn indices(self) -> Vec<u32> {
        let mut ids =
            Vec::with_capacity((self.x1 - self.x0) as usize * (self.y1 - self.y0) as usize * 6);
        for y in self.y0..self.y1 {
            for x in self.x0..self.x1 {
                let a = y as u32 * 129 + x as u32;
                ids.extend([a, a + 129, a + 1, a + 1, a + 129, a + 130]);
            }
        }
        ids
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn sharper_photos_do_not_refine_geometry() {
        let p = TileId {
            z: 12,
            x: 3983,
            y: 2600,
        };
        let rows = surface_cover(&[p], &p.children());
        assert_eq!(rows.len(), 4);
        assert!(rows.iter().all(|r| r.geometry == p && r.image == r.region));
        let cells: usize = rows
            .iter()
            .map(|r| GridRect::for_region(r.geometry, r.region).indices().len())
            .sum();
        assert_eq!(cells, 128 * 128 * 6);
    }
    #[test]
    fn detail_can_be_sharper_than_a_retained_photo() {
        let p = TileId {
            z: 12,
            x: 3983,
            y: 2600,
        };
        let rows = surface_cover(&p.children(), &[p]);
        assert_eq!(rows.len(), 4);
        assert!(rows.iter().all(|r| r.image == p && r.geometry == r.region));
    }
    #[test]
    fn missing_image_is_not_invented() {
        let p = TileId {
            z: 12,
            x: 3983,
            y: 2600,
        };
        let child = p.children()[0];
        let rows = surface_cover(&[p], &[child]);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].region, child);
        assert!(surface_cover(&[p], &[]).is_empty());
    }
    #[test]
    fn tiny_image_regions_keep_the_actual_parent_triangle() {
        let p = TileId {
            z: 8,
            x: 249,
            y: 162,
        };
        let c = TileId {
            z: 19,
            x: p.x * 2048 + 123,
            y: p.y * 2048 + 456,
        };
        let r = GridRect::for_region(p, c);
        assert_eq!(r.indices().len(), 6);
    }
}
