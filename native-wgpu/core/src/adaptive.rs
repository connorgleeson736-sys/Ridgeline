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
}
