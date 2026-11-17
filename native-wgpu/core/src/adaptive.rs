//! Ridgeline0.18's convex residual bound, applied to immutable129x129heights.
//! Metadata is computed on source workers; the render thread only chooses indices.
use crate::surface::GridRect;
pub fn profile(heights: &[f32]) -> Option<[f64; 2]> {
    if heights.len() != 129 * 129 || heights.iter().any(|h| !h.is_finite()) {
        return None;
    }
    let maximum = heights.iter().fold(0f64, |a, h| a.max((*h as f64).abs()));
    let margin = 0.01f64.max(maximum * 2e-6);
    let mut errors = [0f64; 2];
    for (slot, step) in [4usize, 2].into_iter().enumerate() {
        for y in (0..128).step_by(step) {
            for x in (0..128).step_by(step) {
                let origin = heights[y * 129 + x] as f64;
                let dx = (heights[y * 129 + x + step] as f64 - origin) / step as f64;
                let dy = (heights[(y + step) * 129 + x] as f64 - origin) / step as f64;
                let mut lo = f64::INFINITY;
                let mut hi = f64::NEG_INFINITY;
                for j in 0..=step {
                    for i in 0..=step {
                        let residual = heights[(y + j) * 129 + x + i] as f64
                            - (origin + dx * i as f64 + dy * j as f64);
                        lo = lo.min(residual);
                        hi = hi.max(residual);
                    }
                }
                errors[slot] = errors[slot].max(hi - lo);
            }
        }
        errors[slot] += margin;
    }
    Some(errors)
}
/// Blocks per side of a tile's 128x128 cells (each 16x16 cells), for local
/// detail and culling: off-screen blocks are not drawn, and each block takes the
/// coarsest step its own error allows (a tile's worst cell used to decide for the
/// whole tile). Block boundaries are always drawn at full resolution, as tile
/// boundaries are, so blocks of different steps meet without cracks.
pub const BLOCKS: usize = 8;
pub const BLOCK_CELLS: u16 = 16;
/// Bytes of block metadata after a tile's heights and profile: per block, its
/// error at steps 4 and 2 (as [profile]) and its lowest and highest height (f32).
pub const BLOCK_BYTES: usize = BLOCKS * BLOCKS * 16;
/// Per block, row by row: [error at step 4, error at step 2, lowest, highest].
pub fn block_profile(heights: &[f32]) -> Option<Vec<[f32; 4]>> {
    if heights.len() != 129 * 129 || heights.iter().any(|h| !h.is_finite()) {
        return None;
    }
    let maximum = heights.iter().fold(0f64, |a, h| a.max((*h as f64).abs()));
    let margin = 0.01f64.max(maximum * 2e-6);
    let cells = BLOCK_CELLS as usize;
    let mut out = Vec::with_capacity(BLOCKS * BLOCKS);
    for by in 0..BLOCKS {
        for bx in 0..BLOCKS {
            let (x0, y0) = (bx * cells, by * cells);
            let mut low = f32::INFINITY;
            let mut high = f32::NEG_INFINITY;
            for y in y0..=y0 + cells {
                for x in x0..=x0 + cells {
                    low = low.min(heights[y * 129 + x]);
                    high = high.max(heights[y * 129 + x]);
                }
            }
            let mut errors = [0f64; 2];
            for (slot, step) in [4usize, 2].into_iter().enumerate() {
                for y in (y0..y0 + cells).step_by(step) {
                    for x in (x0..x0 + cells).step_by(step) {
                        let origin = heights[y * 129 + x] as f64;
                        let dx = (heights[y * 129 + x + step] as f64 - origin) / step as f64;
                        let dy = (heights[(y + step) * 129 + x] as f64 - origin) / step as f64;
                        let mut lo = f64::INFINITY;
                        let mut hi = f64::NEG_INFINITY;
                        for j in 0..=step {
                            for i in 0..=step {
                                let residual = heights[(y + j) * 129 + x + i] as f64
                                    - (origin + dx * i as f64 + dy * j as f64);
                                lo = lo.min(residual);
                                hi = hi.max(residual);
                            }
                        }
                        errors[slot] = errors[slot].max(hi - lo);
                    }
                }
                errors[slot] += margin;
            }
            // Rounded up to f32: the bound stays conservative.
            let up = |v: f64| {
                let f = v as f32;
                if (f as f64) < v { f32::from_bits(f.to_bits() + 1) } else { f }
            };
            out.push([up(errors[0]), up(errors[1]), low, high]);
        }
    }
    Some(out)
}
/// A block's step from a plan's two bits per block (0: 1, 1: 2, 2: 4).
pub fn block_step(steps: u128, block: usize) -> u8 {
    match (steps >> (2 * block)) & 3 {
        0 => 1,
        1 => 2,
        _ => 4,
    }
}
/// A plan with every block at [step].
pub fn uniform_steps(step: u8) -> u128 {
    let code: u128 = match step {
        2 => 1,
        4 => 2,
        _ => 0,
    };
    (0..BLOCKS * BLOCKS).fold(0, |a, b| a | code << (2 * b))
}
impl GridRect {
    /// The part of this rectangle in block [b], if any.
    pub fn block_part(self, b: usize) -> Option<GridRect> {
        let (bx, by) = ((b % BLOCKS) as u16 * BLOCK_CELLS, (b / BLOCKS) as u16 * BLOCK_CELLS);
        let part = GridRect {
            x0: self.x0.max(bx),
            y0: self.y0.max(by),
            x1: self.x1.min(bx + BLOCK_CELLS),
            y1: self.y1.min(by + BLOCK_CELLS),
        };
        (part.x0 < part.x1 && part.y0 < part.y1).then_some(part)
    }
    /// Whether any block of this rectangle is visible ([visible]: a bit per block).
    pub fn any_block_visible(self, visible: u64) -> bool {
        (0..BLOCKS * BLOCKS).any(|b| visible >> b & 1 != 0 && self.block_part(b).is_some())
    }
    /// Surface triangles of the visible blocks at their steps.
    pub fn block_surface_triangles(self, steps: u128, visible: u64) -> u32 {
        (0..BLOCKS * BLOCKS)
            .filter(|b| visible >> b & 1 != 0)
            .filter_map(|b| self.block_part(b).map(|p| p.adaptive_surface_triangles(block_step(steps, b))))
            .sum()
    }
    /// Indices of the visible blocks, each at its own step with full-resolution
    /// boundaries, and the skirts ([mask]) along this rectangle's edges under them.
    pub fn block_indices(self, steps: u128, visible: u64, mask: u8) -> Vec<u32> {
        let mut ids = Vec::new();
        for b in 0..BLOCKS * BLOCKS {
            if visible >> b & 1 == 0 {
                continue;
            }
            let Some(part) = self.block_part(b) else { continue };
            let surface = part.adaptive_indices(block_step(steps, b), 0);
            ids.extend(surface);
            let edges = (if part.x0 == self.x0 { 1 } else { 0 })
                | (if part.x1 == self.x1 { 2 } else { 0 })
                | (if part.y0 == self.y0 { 4 } else { 0 })
                | (if part.y1 == self.y1 { 8 } else { 0 });
            part.append_skirts(&mut ids, mask & edges);
        }
        ids
    }
}
impl GridRect {
    pub fn adaptive_step(self, step: u8) -> u8 {
        if ![2, 4].contains(&step)
            || (self.x1 - self.x0) % step as u16 != 0
            || (self.y1 - self.y0) % step as u16 != 0
        {
            1
        } else {
            step
        }
    }
    pub fn adaptive_surface_triangles(self, step: u8) -> u32 {
        let step = self.adaptive_step(step) as u32;
        let w = (self.x1 - self.x0) as u32;
        let h = (self.y1 - self.y0) as u32;
        if step == 1 {
            return w * h * 2;
        }
        let nx = w / step;
        let ny = h / step;
        let inside = nx.saturating_sub(2) * ny.saturating_sub(2);
        2 * nx * ny + 2 * (nx * ny - inside) + (step - 1) * (2 * nx + 2 * ny)
    }
    pub fn adaptive_index_count(self, step: u8, mask: u8) -> u32 {
        self.adaptive_surface_triangles(step) * 3 + self.index_count(mask)
            - (self.x1 - self.x0) as u32 * (self.y1 - self.y0) as u32 * 6
    }
    pub fn adaptive_indices(self, step: u8, mask: u8) -> Vec<u32> {
        let step = self.adaptive_step(step) as u16;
        if step == 1 {
            return self.indices_with_skirt_edges(mask);
        }
        let mut ids = Vec::with_capacity(self.adaptive_index_count(step as u8, mask) as usize);
        let index = |x: u16, y: u16| y as u32 * 129 + x as u32;
        for y in (self.y0..self.y1).step_by(step as usize) {
            for x in (self.x0..self.x1).step_by(step as usize) {
                if x > self.x0 && y > self.y0 && x + step < self.x1 && y + step < self.y1 {
                    ids.extend([
                        index(x, y),
                        index(x, y + step),
                        index(x + step, y),
                        index(x + step, y),
                        index(x, y + step),
                        index(x + step, y + step),
                    ]);
                    continue;
                }
                let mut ring = [0u32; 16];
                let mut count = 0;
                let mut edge = |ax: u16, ay: u16, bx: u16, by: u16, fine: bool| {
                    let n = if fine { step } else { 1 };
                    for i in 0..n {
                        ring[count] = index(
                            (ax as i32 + (bx as i32 - ax as i32) * i as i32 / n as i32) as u16,
                            (ay as i32 + (by as i32 - ay as i32) * i as i32 / n as i32) as u16,
                        );
                        count += 1;
                    }
                };
                edge(x, y, x, y + step, x == self.x0);
                edge(x, y + step, x + step, y + step, y + step == self.y1);
                edge(x + step, y + step, x + step, y, x + step == self.x1);
                edge(x + step, y, x, y, y == self.y0);
                let center = index(x + step / 2, y + step / 2);
                for i in 0..count {
                    ids.extend([center, ring[i], ring[(i + 1) % count]]);
                }
            }
        }
        self.append_skirts(&mut ids, mask);
        ids
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn adaptive_boundaries_keep_every_edge_and_exact_coverage() {
        for rect in [
            GridRect {
                x0: 0,
                y0: 0,
                x1: 128,
                y1: 128,
            },
            GridRect {
                x0: 32,
                y0: 64,
                x1: 64,
                y1: 96,
            },
        ] {
            for step in [1, 2, 4] {
                let ids = rect.adaptive_indices(step, 15);
                assert_eq!(ids.len(), rect.adaptive_index_count(step, 15) as usize);
                let surface = rect.adaptive_surface_triangles(step) as usize * 3;
                let mut area = 0i64;
                let mut boundary = std::collections::HashSet::new();
                for t in ids[..surface].chunks_exact(3) {
                    let p: Vec<_> = t
                        .iter()
                        .map(|i| ((i % 129) as i64, (i / 129) as i64))
                        .collect();
                    let cross = (p[1].0 - p[0].0) * (p[2].1 - p[0].1)
                        - (p[1].1 - p[0].1) * (p[2].0 - p[0].0);
                    assert!(cross < 0);
                    area -= cross;
                    for &i in t {
                        let x = (i % 129) as u16;
                        let y = (i / 129) as u16;
                        if x == rect.x0 || x == rect.x1 || y == rect.y0 || y == rect.y1 {
                            boundary.insert(i);
                        }
                    }
                }
                assert_eq!(
                    area,
                    2 * (rect.x1 - rect.x0) as i64 * (rect.y1 - rect.y0) as i64
                );
                for x in rect.x0..=rect.x1 {
                    assert!(boundary.contains(&(rect.y0 as u32 * 129 + x as u32)));
                    assert!(boundary.contains(&(rect.y1 as u32 * 129 + x as u32)));
                }
                for y in rect.y0..=rect.y1 {
                    assert!(boundary.contains(&(y as u32 * 129 + rect.x0 as u32)));
                    assert!(boundary.contains(&(y as u32 * 129 + rect.x1 as u32)));
                }
                let fine = rect.indices_with_skirt_edges(15);
                let fine_surface = (rect.x1 - rect.x0) as usize * (rect.y1 - rect.y0) as usize * 6;
                assert_eq!(&ids[surface..], &fine[fine_surface..]);
            }
        }
        let r = GridRect {
            x0: 0,
            y0: 0,
            x1: 128,
            y1: 128,
        };
        assert_eq!(r.adaptive_surface_triangles(4), 2680);
        assert_eq!(r.adaptive_surface_triangles(2), 8952);
    }
    #[test]
    fn residual_bound_preserves_planes_and_detects_small_sharp_features() {
        let mut h: Vec<_> = (0..129 * 129)
            .map(|i| 1000. + (i % 129) as f32 * 2. + (i / 129) as f32 * 3.)
            .collect();
        let plane = profile(&h).unwrap();
        assert!(plane[0] < 0.011 && plane[1] < 0.011);
        h[65 * 129 + 65] += 100.;
        let spike = profile(&h).unwrap();
        assert!(spike[0] >= 100. && spike[1] >= 100.);
    }

    #[test]
    fn blocks_cover_the_rect_once_and_hide_what_is_off_screen() {
        let rect = GridRect { x0: 0, y0: 0, x1: 128, y1: 128 };
        let all = u64::MAX;
        // All visible at full detail: the same cells as the whole-tile mesh.
        let full = rect.block_indices(uniform_steps(1), all, 15);
        assert_eq!(full.len(), rect.indices_with_skirts().len());
        let area = |ids: &[u32]| {
            let pos = |i: u32| ((i % 129) as f64, (i / 129) as f64);
            ids.chunks_exact(3).filter(|t| t.iter().all(|&i| i < 129 * 129)).map(|t| {
                let (a, b, c) = (pos(t[0]), pos(t[1]), pos(t[2]));
                ((b.0 - a.0) * (c.1 - a.1) - (c.0 - a.0) * (b.1 - a.1)).abs() / 2.
            }).sum::<f64>()
        };
        // Any mix of steps covers exactly the visible blocks' area.
        let mixed: u128 = (0..64).fold(0, |a, b| a | ((b % 3) as u128) << (2 * b));
        assert!((area(&rect.block_indices(mixed, all, 0)) - 128. * 128.).abs() < 1e-6);
        let half: u64 = 0x0000_0000_FFFF_FFFF; // the top four rows of blocks
        assert!((area(&rect.block_indices(mixed, half, 0)) - 128. * 64.).abs() < 1e-6);
        assert!(rect.block_indices(mixed, 0, 15).is_empty());
        assert!(!rect.any_block_visible(0));
        // Coarse blocks draw fewer triangles than full ones.
        assert!(rect.block_surface_triangles(uniform_steps(4), all) < rect.block_surface_triangles(uniform_steps(1), all) / 3);
    }
    #[test]
    fn neighbouring_blocks_share_every_edge_vertex_whatever_their_steps() {
        // Each block boundary is drawn at full resolution on both sides: every
        // vertex on a shared edge is used by both blocks (no T-junction cracks).
        let rect = GridRect { x0: 0, y0: 0, x1: 128, y1: 128 };
        let steps: u128 = 2 | 0 << 2; // block 0 at step 4, block 1 at step 1
        let left = GridRect { x0: 0, y0: 0, x1: 16, y1: 16 }.adaptive_indices(block_step(steps, 0), 0);
        let right = GridRect { x0: 16, y0: 0, x1: 32, y1: 16 }.adaptive_indices(block_step(steps, 1), 0);
        for y in 0..=16u32 {
            let v = y * 129 + 16;
            assert!(left.contains(&v) && right.contains(&v), "edge vertex {y}");
        }
        let _ = rect;
    }
    #[test]
    fn block_profiles_are_flat_on_planes_and_bound_bumps() {
        let plane: Vec<f32> = (0..129 * 129).map(|i| ((i % 129) as f32) * 3. + ((i / 129) as f32) * 2.).collect();
        let blocks = block_profile(&plane).unwrap();
        assert_eq!(blocks.len(), 64);
        assert!(blocks.iter().all(|b| b[0] < 0.05 && b[1] < 0.05));
        let mut bump = vec![100f32; 129 * 129];
        bump[70 * 129 + 70] = 160.; // in block (4, 4)
        let blocks = block_profile(&bump).unwrap();
        assert!(blocks[4 * 8 + 4][0] >= 60. && blocks[4 * 8 + 4][3] == 160.);
        assert!(blocks[0][0] < 1. && blocks[0][2] == 100.);
        assert!(block_profile(&bump[1..]).is_none());
    }
}
