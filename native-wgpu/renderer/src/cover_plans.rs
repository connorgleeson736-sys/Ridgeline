//! Geographic mapping is camera-independent. Cache exact source memberships,
//! not textures or camera matrices; reloaded IDs retain the same UV mapping.
use super::*;
#[derive(Default)]
pub(crate) struct CoverPlans {
    rows: std::collections::VecDeque<(
        Vec<TileId>,
        Vec<TileId>,
        Vec<ridgeline_core::surface::SurfacePatch>,
    )>,
}
impl CoverPlans {
    pub fn get(
        &mut self,
        geometry: &[TileId],
        images: &[TileId],
    ) -> Vec<ridgeline_core::surface::SurfacePatch> {
        if let Some(index) = self
            .rows
            .iter()
            .position(|(g, i, _)| g == geometry && i == images)
        {
            let row = self.rows.remove(index).unwrap();
            let result = row.2.clone();
            self.rows.push_back(row);
            return result;
        }
        let result = ridgeline_core::surface::surface_cover(geometry, images);
        self.rows
            .push_back((geometry.to_vec(), images.to_vec(), result.clone()));
        if self.rows.len() > 16 {
            self.rows.pop_front();
        }
        result
    }
}
