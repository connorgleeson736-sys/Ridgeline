//! Cached depth-tested topographic geometry. No per-frame GeoJSON/tessellation.
use crate::{Renderer, routes::Vertex};
#[derive(Clone, PartialEq)]
pub struct CartographicPath {
    pub points: Vec<[f64; 2]>,
    pub color: [f32; 4],
    pub width: f32,
    pub dash: [f32; 2],
}
impl Renderer {
    pub fn set_cartography(&mut self, paths: Vec<CartographicPath>) {
        if self.cartographic_paths != paths {
            self.cartographic_paths = paths;
            self.cartographic_dirty = true;
        }
    }
    pub(crate) fn rebuild_cartography(&mut self) {
        self.cartographic_anchor = self
            .cartographic_paths
            .iter()
            .find_map(|p| p.points.first())
            .copied()
            .unwrap_or([0.5, 0.5]);
        let anchor = self.cartographic_anchor;
        let relief = self.map_style.height_scale() as f64;
        let mut vertices = Vec::new();
        'paths: for path in &self.cartographic_paths {
            let mut distance = 0.;
            for segment in path.points.windows(2) {
                let a = segment[0];
                let b = segment[1];
                let lo = [a[0].min(b[0]), a[1].min(b[1])];
                let hi = [a[0].max(b[0]), a[1].max(b[1])];
                for row in &self.cover {
                    let clip = row.region.bounds();
                    if hi[0] < clip[0] || lo[0] > clip[2] || hi[1] < clip[1] || lo[1] > clip[3] {
                        continue;
                    }
                    let bounds = row.source.bounds();
                    let span = bounds[2] - bounds[0];
                    let point =
                        |p: [f64; 2]| [(p[0] - bounds[0]) / span, (p[1] - bounds[1]) / span];
                    let draped = drape(
                        &self.tiles[&row.source].field,
                        point(a),
                        point(b),
                        [
                            (clip[0] - bounds[0]) / span,
                            (clip[1] - bounds[1]) / span,
                            (clip[2] - bounds[0]) / span,
                            (clip[3] - bounds[1]) / span,
                        ],
                        !self.map_style.three_d,
                    );
                    for pair in draped.windows(2) {
                        if vertices.len() + 1 > 333333 {
                            self.cartographic_overflow = true;
                            break 'paths;
                        }
                        let world = |p: [f64; 3]| {
                            [
                                (bounds[0] + p[0] * span - anchor[0]) as f32,
                                (bounds[1] + p[1] * span - anchor[1]) as f32,
                                (p[2] * relief) as f32,
                            ]
                        };
                        let start_world =
                            [bounds[0] + pair[0][0] * span, bounds[1] + pair[0][1] * span];
                        let phase = distance + (start_world[0] - a[0]).hypot(start_world[1] - a[1]);
                        vertices.push(Vertex {
                            start: world(pair[0]),
                            end: world(pair[1]),
                            corner: [0., 0., -path.width * 0.5],
                            color: path.color,
                            style: [path.dash[0], path.dash[1], phase as f32],
                        });
                    }
                }
                distance += (b[0] - a[0]).hypot(b[1] - a[1]);
            }
        }
        self.cartographic_vertices = vertices.len() as u32 * 6;
        self.cartographic_gpu
            .upload(&self.device, &self.queue, &vertices);
        self.cartographic_dirty = false;
    }
    /// Projection only, no GPU readback or per-label raycast. Text quads use terrain depth.
    pub fn project_points(&self, points: &[[f64; 2]]) -> Vec<[f32; 3]> {
        let Some(view) = self.last_view else {
            return vec![[0.; 3]; points.len()];
        };
        let (m, ppm) = crate::camera_matrix(
            view.width,
            view.height,
            view.center,
            view.zoom,
            view.bearing,
            view.pitch,
            view.elevation,
        );
        let world = 512. * 2f64.powf(view.zoom);
        points
            .iter()
            .map(|p| {
                let Some(height) = self.terrain_height(*p) else {
                    return [0.; 3];
                };
                let c = m * glam::Vec4::new(
                    ((p[0] - view.center[0]) * world) as f32,
                    ((view.center[1] - p[1]) * world) as f32,
                    ((height * self.map_style.height_scale() as f64 + 1. - view.elevation as f64)
                        * ppm as f64) as f32,
                    1.,
                );
                if c.w <= 0. || c.z < 0. || c.z > c.w {
                    return [0.; 3];
                };
                [
                    (c.x / c.w + 1.) * 0.5 * view.width as f32,
                    (1. - c.y / c.w) * 0.5 * view.height as f32,
                    1.,
                ]
            })
            .collect()
    }
}

/// In explicit 2D, clip to the cartographic plane without walking DEM grid triangles.
/// In 3D, retain exact real-height-field draping.
pub(crate) fn drape(
    field: &ridgeline_core::heightfield::Heightfield,
    a: [f64; 2],
    b: [f64; 2],
    clip: [f64; 4],
    flat: bool,
) -> Vec<[f64; 3]> {
    if !flat {
        return field.drape_segment(a, b, clip);
    }
    let delta = [b[0] - a[0], b[1] - a[1]];
    let mut lo = 0f64;
    let mut hi = 1f64;
    for (p, q) in [
        (-delta[0], a[0] - clip[0]),
        (delta[0], clip[2] - a[0]),
        (-delta[1], a[1] - clip[1]),
        (delta[1], clip[3] - a[1]),
    ] {
        if p == 0. {
            if q < 0. {
                return vec![];
            }
        } else {
            let t = q / p;
            if p < 0. {
                lo = lo.max(t)
            } else {
                hi = hi.min(t)
            };
            if lo > hi {
                return vec![];
            }
        }
    }
    vec![
        [a[0] + delta[0] * lo, a[1] + delta[1] * lo, 0.],
        [a[0] + delta[0] * hi, a[1] + delta[1] * hi, 0.],
    ]
}
