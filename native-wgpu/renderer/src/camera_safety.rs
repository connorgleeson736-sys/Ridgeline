//! Terrain camera clearance following MapLibre 6.14's eye + 3x3 near-plane checks.
//! Queries the actual resident triangulated height fields, never GPU depth.
use crate::{Renderer, camera_matrix, selection::View};
use glam::Vec4;
impl Renderer {
    /// [minimum safe center elevation, drawn center height, center geometry zoom].
    /// Missing terrain stays unknown rather than becoming an invented zero plane.
    pub fn camera_safety(&mut self, view: &View) -> [f32; 3] {
        if self.cover_dirty {
            self.rebuild_cover();
            self.cover_dirty = false;
        }
        let (matrix, ppm) = camera_matrix(
            view.width,
            view.height,
            view.center,
            view.zoom,
            view.bearing,
            view.pitch,
            view.elevation,
        );
        let inverse = matrix.inverse();
        let world = 512. * 2f64.powf(view.zoom);
        let mut floor = f64::NEG_INFINITY;
        let mut sample = |point: glam::Vec3| {
            let coordinate = [
                view.center[0] + point.x as f64 / world,
                view.center[1] - point.y as f64 / world,
            ];
            if let Some(height) = self.terrain_height(coordinate) {
                floor = floor.max(height - point.z as f64 / ppm as f64 + 0.05);
            }
        };
        let b = view.bearing.to_radians();
        let p = view.pitch.to_radians();
        let distance = view.height as f32 * 1.5;
        sample(glam::Vec3::new(-b.sin() * p.sin(), -b.cos() * p.sin(), p.cos()) * distance);
        for row in 0..=2 {
            for column in 0..=2 {
                let point = inverse * Vec4::new(column as f32 - 1., row as f32 - 1., 0., 1.);
                sample(point.truncate() / point.w);
            }
        }
        let ground = self
            .terrain_height(view.center)
            .map(|h| h as f32)
            .unwrap_or(f32::NAN);
        let z = self
            .cover
            .iter()
            .filter(|row| {
                let q = row.region.bounds();
                view.center[0] >= q[0]
                    && view.center[0] <= q[2]
                    && view.center[1] >= q[1]
                    && view.center[1] <= q[3]
            })
            .map(|row| row.source.z)
            .max()
            .map(|z| z as f32)
            .unwrap_or(-1.);
        [floor as f32, ground, z]
    }
}
