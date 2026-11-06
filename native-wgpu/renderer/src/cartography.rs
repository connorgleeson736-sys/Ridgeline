//! Topographic line data for the terrain overlay drape (see overlay.rs), plus
//! label projection and line-of-sight. Lines are no longer draped on the CPU.
use crate::Renderer;
#[cfg(test)]
use ridgeline_core::heightfield::Heightfield;

#[derive(Clone, PartialEq)]
pub struct CartographicPath {
    pub points: Vec<[f64; 2]>,
    pub color: [f32; 4],
    pub width: f32,
    pub dash: [f32; 2],
}
impl Renderer {
    /// One ungrouped set of lines (tests and simple callers). Android sends
    /// lines grouped by source tile through [Renderer::set_overlay_groups].
    pub fn set_cartography(&mut self, paths: Vec<CartographicPath>) {
        use std::hash::{Hash, Hasher};
        let mut hasher = std::collections::hash_map::DefaultHasher::new();
        let mut lines = Vec::new();
        for (order, p) in paths.into_iter().enumerate() {
            for v in p.points.iter().flatten() {
                v.to_bits().hash(&mut hasher);
            }
            for v in p.color.iter().chain([p.width].iter()).chain(p.dash.iter()) {
                v.to_bits().hash(&mut hasher);
            }
            lines.extend(crate::overlay::Line::new(p.points, p.color, p.width, p.dash, order as i32));
        }
        let groups = if lines.is_empty() {
            Vec::new()
        } else {
            vec![crate::overlay::Group::new(
                crate::overlay::GroupKey { z: 0, x: 0, y: 0, clip: [0, 0, 0, 0] },
                hasher.finish(),
                lines,
            )]
        };
        self.set_overlay_groups(groups);
    }
    pub fn set_overlay_groups(&mut self, groups: Vec<crate::overlay::Group>) {
        self.overlay.set_groups(groups);
        self.overlay.pending = !self.overlay.is_empty();
    }
    /// True until every visible tile shows the current overlay.
    pub fn cartography_pending(&self) -> bool {
        self.overlay.pending
    }
    /// Overlay textures painted so far (each is one tile, not a rebuild of all lines).
    pub fn cartography_jobs(&self) -> u64 {
        self.overlay.renders
    }
    pub(crate) fn terrain_overlays_changed(&mut self) {
        // Overlay textures are in image-tile space: terrain changes never repaint them.
        self.route_dirty = true;
        self.labels_dirty = true;
        self.inspection_dirty = true;
    }
    /// Screen position of each point on the resident terrain, for label
    /// collision. Terrain occlusion is decided on the GPU per label anchor
    /// (labels.wgsl), as MapLibre does, so this does no line-of-sight work.
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
        let scale = self.map_style.height_scale() as f64;
        let local_z = |h: f64| (h * scale + 1. - view.elevation as f64) * ppm as f64;
        points
            .iter()
            .map(|p| {
                let Some(height) = self.terrain_height(*p) else {
                    return [0.; 3];
                };
                let anchor = [
                    (p[0] - view.center[0]) * world,
                    (view.center[1] - p[1]) * world,
                    local_z(height),
                ];
                let c = m * glam::Vec4::new(anchor[0] as f32, anchor[1] as f32, anchor[2] as f32, 1.);
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
        let mut points = field.drape_segment(a, b, clip);
        // Consecutive DEM-triangle intervals on this original straight path
        // with exactly equal heights are the same 3D segment. Keep every real
        // height change; remove only redundant constant-height subdivisions.
        // This also avoids repeatedly blending translucent round caps there.
        let mut n = 0;
        for i in 0..points.len() {
            let p = points[i];
            if n >= 2 && points[n - 2][2] == points[n - 1][2] && points[n - 1][2] == p[2] {
                n -= 1;
            }
            points[n] = p;
            n += 1;
        }
        points.truncate(n);
        return points;
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

#[cfg(test)]
mod audit_tests {
    use super::*;
    #[test]
    fn constant_height_drape_keeps_exact_endpoints() {
        let field = Heightfield::new(128, vec![500.; 129 * 129].into_boxed_slice()).unwrap();
        let a = [0.1, 0.2];
        let b = [0.9, 0.8];
        let clip = [0., 0., 1., 1.];
        let raw = field.drape_segment(a, b, clip);
        assert!(raw.len() > 50);
        assert_eq!(
            drape(&field, a, b, clip, false),
            vec![raw[0], *raw.last().unwrap()]
        );
        let heights: Vec<_> = (0..129 * 129).map(|i| (i % 129) as f32).collect();
        let field = Heightfield::new(128, heights.into_boxed_slice()).unwrap();
        let raw = field.drape_segment(a, b, clip);
        let compact = drape(&field, a, b, clip, false);
        for i in 1..raw.len() - 1 {
            if raw[i - 1][2] != raw[i][2] || raw[i][2] != raw[i + 1][2] {
                assert!(compact.contains(&raw[i]), "real height change removed");
            }
        }
    }
}
