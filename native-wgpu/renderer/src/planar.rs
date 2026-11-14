//! Identical existing grid rasterisation, backed by one shared explicit zero plane.
use crate::*;
pub(crate) struct Plane {
    bind: Arc<wgpu::BindGroup>,
    texture: wgpu::Texture,
    _lease: Option<texture_arrays::LayerLease>,
    field: Arc<ridgeline_core::heightfield::Heightfield>,
    layer: u32,
}
impl Renderer {
    pub fn unique_height_resources(&self) -> usize {
        self.tiles
            .values()
            .map(|t| (Arc::as_ptr(&t.height_bind) as usize, t.height_layer))
            .collect::<std::collections::HashSet<_>>()
            .len()
    }

    pub fn upload_plane(&mut self, id: TileId) -> Result<(), UploadError> {
        if self.map_style.three_d || (self.map_style.hillshade && self.map_style.basemap != 2) {
            return Err(UploadError::InvalidGeometry);
        }
        if self.touch_geometry(id) {
            return Ok(());
        }
        if self.plane.is_none() {
            self.upload_geometry_owned(id, vec![0.; 129 * 129])?;
            let tile = self.tiles.get_mut(&id).unwrap();
            self.plane = Some(Plane {
                bind: tile.height_bind.clone(),
                texture: tile._heights.clone(),
                _lease: tile.array.take(),
                field: tile.field.clone(),
                layer: tile.height_layer,
            });
            return Ok(());
        }
        if self.tiles.len() >= 192 {
            self.trim_geometry();
            if self.tiles.len() >= 192 {
                return Err(UploadError::Capacity);
            }
        }
        let shared = self.plane.as_ref().unwrap();
        self.clock += 1;
        self.tiles.insert(
            id,
            Tile {
                height_bind: shared.bind.clone(),
                array: None,
                _heights: shared.texture.clone(),
                field: shared.field.clone(),
                height_layer: shared.layer,
                used: self.clock,
                generation: self.clock,
                adaptive_profile: None,
                adaptive_state: std::cell::Cell::new(adaptive::State::new()),
            },
        );
        self.cover_dirty = true;
        self.seams_dirty = true;
        self.trim_geometry();
        self.trim_joint();
        Ok(())
    }
}
