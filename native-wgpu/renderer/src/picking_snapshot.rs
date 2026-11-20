//! Immutable, submitted-scene CPU picker. Input never waits on the renderer-state mutex.
use crate::{Renderer, camera_matrix, selection::View};
use ridgeline_core::heightfield::{Heightfield, Ray};
use std::sync::Arc;
struct Patch {
    bounds: [f64; 4],
    clip: [f64; 4],
    field: Arc<Heightfield>,
}
pub struct PickingSnapshot {
    pub view: View,
    pub generation: u64,
    relief: f64,
    flat: bool,
    patches: Vec<Patch>,
}
impl Renderer {
    pub fn picking_snapshot(&self) -> Option<Arc<PickingSnapshot>> {
        self.submitted_pick.clone()
    }
    pub(crate) fn build_picking_snapshot(&self) -> Option<Arc<PickingSnapshot>> {
        Some(Arc::new(PickingSnapshot {
            view: self.last_view?,
            generation: self.clock,
            relief: self.map_style.relief as f64,
            flat: !self.map_style.three_d,
            patches: self
                .draws
                .iter()
                .filter_map(|row| {
                    Some(Patch {
                        bounds: row.geometry.bounds(),
                        clip: row.bounds(),
                        field: self.tiles.get(&row.geometry)?.field.clone(),
                    })
                })
                .collect(),
        }))
    }
}
impl PickingSnapshot {
    pub(crate) fn owns_rectangle(&self, id: crate::TileId, field: &Arc<Heightfield>) -> bool {
        let b = id.bounds();
        let mut area = 0.;
        for patch in &self.patches {
            if Arc::ptr_eq(&patch.field, field) {
                let width = b[2].min(patch.clip[2]) - b[0].max(patch.clip[0]);
                let height = b[3].min(patch.clip[3]) - b[1].max(patch.clip[1]);
                if width > 0. && height > 0. {
                    area += width * height;
                }
            }
        }
        area == (b[2] - b[0]) * (b[3] - b[1])
    }
    pub(crate) fn matches_style(&self, three_d: bool, relief: f32) -> bool {
        self.flat == !three_d && self.relief == relief as f64
    }
    pub fn pick(&self, x: f64, y: f64) -> Option<[f64; 3]> {
        let view = &self.view;
        if !x.is_finite() || !y.is_finite() || view.width == 0 || view.height == 0 {
            return None;
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
        let ndc = glam::Vec4::new(
            (x / view.width as f64 * 2. - 1.) as f32,
            (1. - y / view.height as f64 * 2.) as f32,
            0.,
            1.,
        );
        let a = inverse * ndc;
        let b = inverse * glam::Vec4::new(ndc.x, ndc.y, 1., 1.);
        let origin = a.truncate() / a.w;
        let far = b.truncate() / b.w;
        let direction = (far - origin).normalize();
        let world = 512. * 2f64.powf(view.zoom);
        let max_t = (far - origin).length() as f64;
        let geographic = |mx: f64, my: f64, h: f64| {
            [
                mx * 360. - 180.,
                (std::f64::consts::PI * (1. - 2. * my))
                    .sinh()
                    .atan()
                    .to_degrees(),
                h,
            ]
        };
        if self.flat {
            let dz = direction.z as f64 / ppm as f64;
            let z = view.elevation as f64 + origin.z as f64 / ppm as f64;
            if dz.abs() < 1e-20 {
                return None;
            }
            let t = -z / dz;
            if t < 0. || t > max_t {
                return None;
            }
            let mx = view.center[0] + (origin.x as f64 + direction.x as f64 * t) / world;
            let my = view.center[1] - (origin.y as f64 + direction.y as f64 * t) / world;
            if !self
                .patches
                .iter()
                .any(|p| mx >= p.clip[0] && mx <= p.clip[2] && my >= p.clip[1] && my <= p.clip[3])
            {
                return None;
            }
            return Some(geographic(mx, my, 0.));
        }
        let mut best: Option<(f64, [f64; 3])> = None;
        for patch in &self.patches {
            let bounds = patch.bounds;
            let clip = patch.clip;
            let span = bounds[2] - bounds[0];
            let ray = Ray {
                origin: [
                    (view.center[0] + origin.x as f64 / world - bounds[0]) / span,
                    (view.center[1] - origin.y as f64 / world - bounds[1]) / span,
                    (view.elevation as f64 + origin.z as f64 / ppm as f64) / self.relief,
                ],
                direction: [
                    direction.x as f64 / world / span,
                    -direction.y as f64 / world / span,
                    direction.z as f64 / ppm as f64 / self.relief,
                ],
            };
            if let Some(hit) = patch.field.intersect_region(
                ray,
                [
                    (clip[0] - bounds[0]) / span,
                    (clip[1] - bounds[1]) / span,
                    (clip[2] - bounds[0]) / span,
                    (clip[3] - bounds[1]) / span,
                ],
            ) {
                if hit.t <= max_t && best.as_ref().is_none_or(|(t, _)| hit.t < *t) {
                    best = Some((
                        hit.t,
                        geographic(
                            bounds[0] + hit.point[0] * span,
                            bounds[1] + hit.point[1] * span,
                            hit.point[2] * self.relief,
                        ),
                    ));
                }
            }
        }
        best.map(|(_, p)| p)
    }
}
