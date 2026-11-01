//! Native counterparts of the reference raster grade, sky, relief and Igor shading.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct MapStyle {
    pub basemap: u32,
    pub natural: bool,
    pub hillshade: bool,
    pub relief: f32,
    pub sky: u32,
}
impl Default for MapStyle {
    fn default() -> Self { Self { basemap: 0, natural: true, hillshade: false, relief: 1., sky: 0xffbdcbd0 } }
}
impl MapStyle {
    pub fn grade(&self) -> [f32;4] {
        if !self.natural || self.basemap == 2 { [0.,1.,0.,1.] }
        else if self.basemap == 1 { [0.,0.99,0.12,1./0.985] }
        else { [0.058,1.,0.25,1./0.74] }
    }
    pub fn from_packet(bytes: &[u8]) -> Option<Self> {
        if bytes.len()!=20 { return None; }
        let int=|offset|u32::from_le_bytes(bytes[offset..offset+4].try_into().unwrap());
        let basemap=int(0); let natural=int(4); let hillshade=int(8); let relief=f32::from_le_bytes(bytes[12..16].try_into().unwrap());
        if basemap>2 || natural>1 || hillshade>1 || !relief.is_finite() || !(1. ..=2.).contains(&relief) { return None; }
        Some(Self {basemap,natural:natural!=0,hillshade:hillshade!=0,relief,sky:int(16)})
    }
}
impl crate::Renderer {
    pub fn set_map_style(&mut self, style: MapStyle) {
        if self.map_style!=style {
            if self.map_style.relief!=style.relief {self.route_dirty=true;self.cover_dirty=true;self.adaptive_levels.clear();}
            self.map_style=style;
        }
    }
    pub fn clear_images(&mut self) {
        self.images.clear();if let Some(pool)=&mut self.image_arrays {pool.trim_empty();}
        self.cover_dirty=true;
    }
}
