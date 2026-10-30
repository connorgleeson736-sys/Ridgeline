//! Conservative0.18screen-error selection. Original samples stay GPU resident.
use crate::{Tile, TileId, selection::View};
use glam::{Mat4, Vec4};
use std::time::Instant;
#[derive(Clone, Copy)]
pub(crate) struct State {
    step: u8,
    target: u8,
    since: Instant,
}
impl State {
    pub fn new() -> Self {
        Self {
            step: 1,
            target: 1,
            since: Instant::now(),
        }
    }
}
pub(crate) fn choose(
    tile: &Tile,
    id: TileId,
    matrix: Mat4,
    view: &View,
    ppm: f32,
    now: Instant,
    exact: bool,
) -> (u8, Option<u64>) {
    let old = tile.adaptive_state.get();
    let metrics = if exact {
        None
    } else {
        tile.adaptive_profile.and_then(|errors| {
            let b = id.bounds();
            let world = 512. * 2f64.powf(view.zoom);
            let mut min_w = f64::INFINITY;
            let mut nx = 0f64;
            let mut ny = 0f64;
            let mut x0 = f64::INFINITY;
            let mut x1 = f64::NEG_INFINITY;
            let mut y0 = f64::INFINITY;
            let mut y1 = f64::NEG_INFINITY;
            for x in [b[0], b[2]] {
                for y in [b[1], b[3]] {
                    for h in [tile.field.min, tile.field.max] {
                        let clip = matrix
                            * Vec4::new(
                                ((x - view.center[0]) * world) as f32,
                                ((view.center[1] - y) * world) as f32,
                                (h - view.elevation) * ppm,
                                1.,
                            );
                        if !clip.is_finite() || clip.w <= view.height as f32 / 50. || clip.z < 0. {
                            return None;
                        }
                        let w = clip.w as f64;
                        min_w = min_w.min(w);
                        nx = nx.max(
                            ((matrix.z_axis.x as f64 * w - matrix.z_axis.w as f64 * clip.x as f64)
                                * view.width as f64
                                * 0.5)
                                .abs(),
                        );
                        ny = ny.max(
                            ((matrix.z_axis.y as f64 * w - matrix.z_axis.w as f64 * clip.y as f64)
                                * view.height as f64
                                * 0.5)
                                .abs(),
                        );
                        let px = clip.x as f64 / w * view.width as f64 * 0.5;
                        let py = clip.y as f64 / w * view.height as f64 * 0.5;
                        x0 = x0.min(px);
                        x1 = x1.max(px);
                        y0 = y0.min(py);
                        y1 = y1.max(py);
                    }
                }
            }
            Some((
                errors,
                nx.hypot(ny) / (min_w * min_w) * ppm as f64,
                (x1 - x0).max(y1 - y0),
            ))
        })
    };
    let level = |limit: f64, cell: f64| -> u8 {
        if let Some((errors, pixels, span)) = metrics {
            for (step, error) in [(4, errors[0]), (2, errors[1])] {
                if error * pixels <= limit && span / (128 / step) as f64 <= cell {
                    return step;
                }
            }
        }
        1
    };
    let mut target = level(0.6, 10.);
    if old.step > target && level(0.9, 13.) >= old.step {
        target = old.step;
    }
    let state = if target <= old.step {
        State {
            step: target,
            target,
            since: now,
        }
    } else if target != old.target {
        State {
            step: old.step,
            target,
            since: now,
        }
    } else if now.duration_since(old.since).as_millis() >= 350 {
        State {
            step: target,
            target,
            since: now,
        }
    } else {
        old
    };
    tile.adaptive_state.set(state);
    let wake = if state.step != state.target {
        Some(
            350u64
                .saturating_sub(now.duration_since(state.since).as_millis() as u64)
                .max(1),
        )
    } else {
        None
    };
    (state.step, wake)
}
