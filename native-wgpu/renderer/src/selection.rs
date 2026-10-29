//! Conservative native viewport selection. Real DEM extrema and measured LOD
//! transition/seam acceptance are still required before production parity.
use crate::{TileId, camera_matrix};
use glam::{Mat4, Vec4};
use std::{cmp::Ordering, collections::BinaryHeap};

#[derive(Clone, Copy)]
pub struct View {
    pub width: u32,
    pub height: u32,
    pub center: [f64; 2],
    pub zoom: f64,
    pub bearing: f32,
    pub pitch: f32,
    pub elevation: f32,
}
struct Candidate {
    id: TileId,
    score: f64,
}
impl PartialEq for Candidate {
    fn eq(&self, other: &Self) -> bool {
        self.id == other.id && self.score.to_bits() == other.score.to_bits()
    }
}
impl Eq for Candidate {}
impl PartialOrd for Candidate {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}
impl Ord for Candidate {
    fn cmp(&self, other: &Self) -> Ordering {
        self.score.total_cmp(&other.score).then_with(|| {
            (self.id.z, self.id.x, self.id.y).cmp(&(other.id.z, other.id.x, other.id.y))
        })
    }
}
/// A non-overlapping quadtree cover within NZ. Budget limits requests, never
/// removes an existing parent in favour of an unready child.
pub fn plan(view: &View, budget: usize) -> Vec<TileId> {
    plan_with_target(view, budget, 256., 19)
}
pub fn plan_with_target(view: &View, budget: usize, target: f64, max_zoom: u8) -> Vec<TileId> {
    let target = target.clamp(32., 1024.);
    let max_zoom = max_zoom.clamp(4, 22);
    let (matrix, ppm) = camera_matrix(
        view.width,
        view.height,
        view.center,
        view.zoom,
        view.bearing,
        view.pitch,
        view.elevation,
    );
    let world = 512. * 2f64.powf(view.zoom);
    let nz0 = crate::mercator(165., -33.);
    let nz1 = crate::mercator(180., -49.);
    let candidate = |id: TileId| -> Option<Candidate> {
        let b = id.bounds();
        if b[2] < nz0[0] || b[0] > nz1[0] || b[3] < nz0[1] || b[1] > nz1[1] {
            return None;
        }
        let x0 = ((b[0] - view.center[0]) * world) as f32;
        let x1 = ((b[2] - view.center[0]) * world) as f32;
        let y0 = ((view.center[1] - b[1]) * world) as f32;
        let y1 = ((view.center[1] - b[3]) * world) as f32;
        // Conservative NZ elevations, not a fabricated height field. Used only for
        // visibility until resident real DEM extrema are available.
        if !visible_box(
            matrix,
            [x0, y1, (-100. - view.elevation) * ppm],
            [x1, y0, (4000. - view.elevation) * ppm],
        ) {
            return None;
        }
        let middle = matrix * Vec4::new((x0 + x1) / 2., (y0 + y1) / 2., 0., 1.);
        let size = (b[2] - b[0]) * world;
        let distance = (middle.w as f64 - size * 0.71).max(view.height as f64 / 50.);
        let score = size * (view.height as f64 * 1.5) / distance;
        Some(Candidate { id, score })
    };
    let Some(root) = candidate(TileId { z: 0, x: 0, y: 0 }) else {
        return Vec::new();
    };
    let budget = budget.clamp(16, 512);
    let mut pending = BinaryHeap::with_capacity(budget);
    let mut leaves = Vec::with_capacity(budget);
    pending.push(root);
    while let Some(next) = pending.pop() {
        if next.id.z >= max_zoom || (next.score <= target && next.id.z >= 4) {
            leaves.push(next);
            continue;
        }
        let children = next.id.children().map(&candidate);
        let count = children.iter().flatten().count();
        if pending.len() + leaves.len() + count > budget {
            leaves.push(next);
            continue;
        }
        pending.extend(children.into_iter().flatten());
    }
    leaves.sort_by(|a, b| {
        let rank = |c: &Candidate| {
            let b = c.id.bounds();
            let dx = (b[0] + b[2]) / 2. - view.center[0];
            let dy = (b[1] + b[3]) / 2. - view.center[1];
            dx * dx + dy * dy
        };
        rank(a).total_cmp(&rank(b))
    });
    leaves.into_iter().map(|c| c.id).collect()
}
pub(crate) fn visible_box(matrix: Mat4, lo: [f32; 3], hi: [f32; 3]) -> bool {
    let points: [Vec4; 8] = std::array::from_fn(|i| {
        matrix
            * Vec4::new(
                if i & 1 == 0 { lo[0] } else { hi[0] },
                if i & 2 == 0 { lo[1] } else { hi[1] },
                if i & 4 == 0 { lo[2] } else { hi[2] },
                1.,
            )
    });
    !(0..6).any(|plane| {
        points.iter().all(|p| match plane {
            0 => p.x < -p.w,
            1 => p.x > p.w,
            2 => p.y < -p.w,
            3 => p.y > p.w,
            4 => p.z < 0.,
            _ => p.z > p.w,
        })
    })
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn covers_center_without_overlap_or_unbounded_requests() {
        for pitch in [0., 58., 85.] {
            for zoom in [5., 11.9, 17., 20.] {
                let center = crate::mercator(170.14219572, -43.5955671);
                let view = View {
                    width: 1080,
                    height: 1920,
                    center,
                    zoom,
                    bearing: -24.,
                    pitch,
                    elevation: 3724.,
                };
                let ids = plan(&view, 96);
                assert!(!ids.is_empty());
                assert!(ids.len() <= 96);
                let target = TileId {
                    z: 22,
                    x: (center[0] * (1u32 << 22) as f64) as u32,
                    y: (center[1] * (1u32 << 22) as f64) as u32,
                };
                assert!(
                    ids.iter().any(|id| id.contains(target)),
                    "center missing at zoom {zoom} pitch {pitch}"
                );
                for (a, id) in ids.iter().enumerate() {
                    assert!(id.z <= 19);
                    for (b, other) in ids.iter().enumerate() {
                        if a != b {
                            assert!(!id.contains(*other));
                        }
                    }
                }
            }
        }
    }
}
