//! Conservative0.18screen-error selection. Original samples stay GPU resident.
use crate::{Tile, TileId, selection::View};
use glam::{Mat4, Vec4};
use std::time::Instant;
#[derive(Clone, Copy)]
pub(crate) struct State {
    initialized: bool,
    step: u8,
    target: u8,
    since: Instant,
}
impl State {
    pub fn new() -> Self {
        Self {
            initialized: false,
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
    relief: f32,
) -> (u8, Option<u64>) {
    if relief == 0. && !exact {
        return (4, None);
    }
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
                                (h * relief - view.elevation) * ppm,
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
                nx.hypot(ny) / (min_w * min_w) * ppm as f64 * relief as f64,
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
    if old.initialized && old.step > target && level(0.9, 13.) >= old.step {
        target = old.step;
    }
    let state = advance(old, target, now, exact || metrics.is_some());
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

/// Each block of a tile's grid this frame: whether it is in view (its own box,
/// its own lowest and highest heights when known, against the frustum) and its step
/// (the coarsest whose error and cell size stay within the tile rule's 0.6 px and
/// 10 px, kept while within 0.9 px and 13 px). Without block metadata, or for an
/// exact tile, every block takes [tile_step]; a partly measured tile keeps every
/// block in view. Blocks out of view carry step code 0 (one canonical plan).
pub(crate) fn plan_blocks(
    tile: &Tile,
    id: TileId,
    matrix: Mat4,
    view: &View,
    ppm: f32,
    relief: f32,
    exact: bool,
    tile_step: u8,
    skirt: f32,
) -> (u128, u64) {
    use ridgeline_core::adaptive::{BLOCKS, BLOCK_CELLS, block_step};
    let b = id.bounds();
    let world = 512. * 2f64.powf(view.zoom);
    let complete = tile.field.complete();
    let blocks = tile.blocks.get().and_then(|o| o.as_deref()).filter(|_| complete);
    let previous = tile.block_steps.get();
    let code = |step: u8| -> u128 { match step { 2 => 1, 4 => 2, _ => 0 } };
    let (mut steps, mut visible) = (0u128, 0u64);
    for k in 0..BLOCKS * BLOCKS {
        let (bx, by) = ((k % BLOCKS) as f64, (k / BLOCKS) as f64);
        let n = BLOCKS as f64;
        let (x0, x1) = (b[0] + (b[2] - b[0]) * bx / n, b[0] + (b[2] - b[0]) * (bx + 1.) / n);
        let (y0, y1) = (b[1] + (b[3] - b[1]) * by / n, b[1] + (b[3] - b[1]) * (by + 1.) / n);
        let (low, high) = blocks.map_or((tile.field.min, tile.field.max), |bl| (bl[k][2], bl[k][3]));
        let seen = !complete
            || crate::selection::visible_box(
                matrix,
                // Skirts hang [skirt] below the edges: a block whose skirt shows is drawn.
                [((x0 - view.center[0]) * world) as f32, ((view.center[1] - y1) * world) as f32, ((low - skirt) * relief - view.elevation) * ppm],
                [((x1 - view.center[0]) * world) as f32, ((view.center[1] - y0) * world) as f32, (high * relief - view.elevation) * ppm],
            );
        if !seen {
            continue;
        }
        visible |= 1 << k;
        let step = match blocks {
            Some(bl) if !exact && relief != 0. => {
                // The tile rule's screen-error metric, on the block's own corners.
                let mut metric = Some((0f64, 0f64, f64::INFINITY, [f64::INFINITY, f64::NEG_INFINITY, f64::INFINITY, f64::NEG_INFINITY]));
                'corners: for x in [x0, x1] {
                    for y in [y0, y1] {
                        for h in [low, high] {
                            let clip = matrix * Vec4::new(((x - view.center[0]) * world) as f32, ((view.center[1] - y) * world) as f32, (h * relief - view.elevation) * ppm, 1.);
                            if !clip.is_finite() || clip.w <= view.height as f32 / 50. || clip.z < 0. {
                                metric = None;
                                break 'corners;
                            }
                            let (nx, ny, min_w, mut r) = metric.unwrap();
                            let w = clip.w as f64;
                            let px = clip.x as f64 / w * view.width as f64 * 0.5;
                            let py = clip.y as f64 / w * view.height as f64 * 0.5;
                            r = [r[0].min(px), r[1].max(px), r[2].min(py), r[3].max(py)];
                            metric = Some((
                                nx.max(((matrix.z_axis.x as f64 * w - matrix.z_axis.w as f64 * clip.x as f64) * view.width as f64 * 0.5).abs()),
                                ny.max(((matrix.z_axis.y as f64 * w - matrix.z_axis.w as f64 * clip.y as f64) * view.height as f64 * 0.5).abs()),
                                min_w.min(w),
                                r,
                            ));
                        }
                    }
                }
                let level = |limit: f64, cell: f64| -> u8 {
                    if let Some((nx, ny, min_w, r)) = metric {
                        let pixels = nx.hypot(ny) / (min_w * min_w) * ppm as f64 * relief as f64;
                        let span = (r[1] - r[0]).max(r[3] - r[2]);
                        for (step, error) in [(4u8, bl[k][0] as f64), (2, bl[k][1] as f64)] {
                            if error * pixels <= limit && span / (BLOCK_CELLS / step as u16) as f64 <= cell {
                                return step;
                            }
                        }
                    }
                    1
                };
                let mut target = level(0.6, 10.);
                let before = block_step(previous, k);
                if before > target && level(0.9, 13.) >= before {
                    target = before;
                }
                target
            }
            _ => tile_step,
        };
        steps |= code(step) << (2 * k);
    }
    tile.block_steps.set(steps);
    (steps, visible)
}

fn advance(old: State, target: u8, now: Instant, qualified: bool) -> State {
    if !old.initialized || target <= old.step {
        State {
            initialized: old.initialized || qualified,
            step: target,
            target,
            since: now,
        }
    } else if target != old.target {
        State {
            initialized: true,
            step: old.step,
            target,
            since: now,
        }
    } else if now.duration_since(old.since).as_millis() >= 350 {
        State {
            initialized: true,
            step: target,
            target,
            since: now,
        }
    } else {
        old
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn additional_adaptive_cold_start_and_hysteresis() {
        let t = Instant::now();
        let cold = advance(State::new(), 4, t, true);
        assert_eq!(cold.step, 4);
        assert_eq!(cold.target, 4);
        let exact = advance(State::new(), 1, t, true);
        let waiting = advance(exact, 4, t, true);
        assert_eq!(waiting.step, 1);
        assert_eq!(
            advance(waiting, 4, t + std::time::Duration::from_millis(349), true).step,
            1
        );
        assert_eq!(
            advance(waiting, 4, t + std::time::Duration::from_millis(350), true).step,
            4
        );
        assert_eq!(advance(cold, 1, t, true).step, 1);
        assert!(!advance(State::new(), 1, t, false).initialized);
        assert!(advance(cold, 1, t, false).initialized);
    }
}
