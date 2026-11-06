//! Native counterparts of the reference raster grade, sky, relief and Igor shading.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct MapStyle {
    pub basemap: u32,
    pub natural: bool,
    pub hillshade: bool,
    pub relief: f32,
    pub sky: u32,
    pub three_d: bool,
}
impl Default for MapStyle {
    fn default() -> Self {
        Self {
            basemap: 0,
            natural: true,
            hillshade: false,
            relief: 1.,
            sky: 0xffbdcbd0,
            three_d: true,
        }
    }
}
impl MapStyle {
    pub fn height_scale(&self) -> f32 {
        if self.three_d { self.relief } else { 0. }
    }
    pub fn grade(&self) -> [f32; 4] {
        if !self.natural || self.basemap == 2 {
            [0., 1., 0., 1.]
        } else if self.basemap == 1 {
            [0., 0.99, 0.12, 1. / 0.985]
        } else {
            [0.058, 1., 0.25, 1. / 0.74]
        }
    }
    pub fn from_packet(bytes: &[u8]) -> Option<Self> {
        if bytes.len() != 24 {
            return None;
        }
        let int = |offset| u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap());
        let three_d = int(20);
        let basemap = int(0);
        let natural = int(4);
        let hillshade = int(8);
        let relief = f32::from_le_bytes(bytes[12..16].try_into().unwrap());
        if three_d > 1
            || basemap > 2
            || natural > 1
            || hillshade > 1
            || !relief.is_finite()
            || !(1. ..=2.).contains(&relief)
        {
            return None;
        }
        Some(Self {
            basemap,
            natural: natural != 0,
            hillshade: hillshade != 0,
            relief,
            sky: int(16),
            three_d: three_d != 0,
        })
    }
}
impl crate::Renderer {
    pub fn set_map_style(&mut self, style: MapStyle) {
        if self.map_style != style {
            if self.map_style.height_scale() != style.height_scale() {
                self.terrain_overlays_changed();
                self.cover_dirty = true;
                self.adaptive_levels.clear();
            }
            self.map_style = style;
        }
    }
    pub fn clear_geometry(&mut self) {
        self.tiles.clear();
        self.interiors.clear();
        self.plane = None;
        if let Some(pool) = &mut self.height_arrays {
            pool.trim_empty();
        }
        self.seams_dirty = true;
        self.cover_dirty = true;
        self.terrain_overlays_changed();
    }
    pub fn clear_images(&mut self) {
        self.images.clear();
        if let Some(pool) = &mut self.image_arrays {
            pool.trim_empty();
        }
        self.cover_dirty = true;
    }
}
