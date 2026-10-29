//! CPU queries against the same triangulated grid as the native GPU, without
//! reading GPU depth back or scanning every triangle for each touch.
#[derive(Clone, Copy, Debug)]
pub struct Ray {
    pub origin: [f64; 3],
    pub direction: [f64; 3],
}
#[derive(Clone, Copy, Debug)]
pub struct Hit {
    pub t: f64,
    pub point: [f64; 3],
}
pub struct Heightfield {
    cells: usize,
    heights: Box<[f32]>,
    pub min: f32,
    pub max: f32,
}
impl Heightfield {
    pub fn new(cells: usize, heights: Box<[f32]>) -> Result<Self, &'static str> {
        if cells == 0
            || cells > 256
            || heights.len() != (cells + 1) * (cells + 1)
            || heights.iter().any(|h| !h.is_finite())
        {
            return Err("Invalid height field");
        }
        let min = heights.iter().copied().fold(f32::INFINITY, f32::min);
        let max = heights.iter().copied().fold(f32::NEG_INFINITY, f32::max);
        Ok(Self {
            cells,
            heights,
            min,
            max,
        })
    }
    fn height(&self, x: usize, y: usize) -> f64 {
        self.heights[y * (self.cells + 1) + x] as f64
    }
    fn triangles(&self, x: usize, y: usize) -> [[[f64; 3]; 3]; 2] {
        let n = self.cells as f64;
        let a = [x as f64 / n, y as f64 / n, self.height(x, y)];
        let b = [(x + 1) as f64 / n, y as f64 / n, self.height(x + 1, y)];
        let c = [x as f64 / n, (y + 1) as f64 / n, self.height(x, y + 1)];
        let d = [
            (x + 1) as f64 / n,
            (y + 1) as f64 / n,
            self.height(x + 1, y + 1),
        ];
        [[a, c, b], [b, c, d]]
    }
    /// Triangle interpolation, matching the rendered diagonals rather than a
    /// different bilinear surface. UV outside this actual tile remains missing.
    pub fn sample(&self, u: f64, v: f64) -> Option<f64> {
        if !u.is_finite()
            || !v.is_finite()
            || !(0.0..=1.0).contains(&u)
            || !(0.0..=1.0).contains(&v)
        {
            return None;
        }
        let x = ((u * self.cells as f64).floor() as usize).min(self.cells - 1);
        let y = ((v * self.cells as f64).floor() as usize).min(self.cells - 1);
        let fx = u * self.cells as f64 - x as f64;
        let fy = v * self.cells as f64 - y as f64;
        Some(if fx + fy <= 1. {
            let h = self.height(x, y);
            h + fx * (self.height(x + 1, y) - h) + fy * (self.height(x, y + 1) - h)
        } else {
            let h = self.height(x + 1, y + 1);
            h + (1. - fx) * (self.height(x, y + 1) - h) + (1. - fy) * (self.height(x + 1, y) - h)
        })
    }
    pub fn intersect(&self, ray: Ray) -> Option<Hit> {
        self.intersect_region(ray, [0., 0., 1., 1.])
    }
    /// Clip to the exact visible parent/fine ownership region before walking.
    pub fn intersect_region(&self, ray: Ray, clip: [f64; 4]) -> Option<Hit> {
        if !clip
            .iter()
            .all(|v| v.is_finite() && (0.0..=1.0).contains(v))
            || clip[0] >= clip[2]
            || clip[1] >= clip[3]
        {
            return None;
        }
        if !ray
            .origin
            .iter()
            .chain(ray.direction.iter())
            .all(|v| v.is_finite())
            || ray.direction.iter().all(|v| *v == 0.)
        {
            return None;
        }
        let (enter, exit) = slab(
            ray,
            [clip[0], clip[1], self.min as f64],
            [clip[2], clip[3], self.max as f64],
        )?;
        let p = add(ray.origin, mul(ray.direction, enter));
        let n = self.cells as f64;
        let mut x = (p[0] * n).floor().clamp(0., n - 1.) as i32;
        let mut y = (p[1] * n).floor().clamp(0., n - 1.) as i32;
        let step = |d: f64| {
            if d > 0. {
                1
            } else if d < 0. {
                -1
            } else {
                0
            }
        };
        let sx = step(ray.direction[0]);
        let sy = step(ray.direction[1]);
        let next = |cell: i32, axis: usize, s: i32| {
            if s == 0 {
                f64::INFINITY
            } else {
                ((cell + if s > 0 { 1 } else { 0 }) as f64 / n - ray.origin[axis])
                    / ray.direction[axis]
            }
        };
        let mut tx = next(x, 0, sx);
        let mut ty = next(y, 1, sy);
        let dx = if sx == 0 {
            f64::INFINITY
        } else {
            (1. / n / ray.direction[0]).abs()
        };
        let dy = if sy == 0 {
            f64::INFINITY
        } else {
            (1. / n / ray.direction[1]).abs()
        };
        for _ in 0..self.cells * 2 + 2 {
            if x < 0 || y < 0 || x >= self.cells as i32 || y >= self.cells as i32 {
                break;
            }
            let hit = self
                .triangles(x as usize, y as usize)
                .into_iter()
                .filter_map(|tri| triangle(ray, tri))
                .filter(|t| *t >= enter - 1e-9 && *t <= exit + 1e-9)
                .min_by(f64::total_cmp);
            if let Some(t) = hit {
                return Some(Hit {
                    t,
                    point: add(ray.origin, mul(ray.direction, t)),
                });
            }
            let end = tx.min(ty);
            if end > exit || !end.is_finite() {
                break;
            }
            // Advance both axes at a corner, avoiding infinite loops and duplicate cells.
            if tx <= end {
                x += sx;
                tx += dx;
            }
            if ty <= end {
                y += sy;
                ty += dy;
            }
        }
        None
    }
}
fn slab(ray: Ray, lo: [f64; 3], hi: [f64; 3]) -> Option<(f64, f64)> {
    let mut enter = 0f64;
    let mut exit = f64::INFINITY;
    for axis in 0..3 {
        let d = ray.direction[axis];
        let o = ray.origin[axis];
        if d == 0. {
            if o < lo[axis] || o > hi[axis] {
                return None;
            }
        } else {
            let a = (lo[axis] - o) / d;
            let b = (hi[axis] - o) / d;
            enter = enter.max(a.min(b));
            exit = exit.min(a.max(b));
        }
        if exit < enter {
            return None;
        }
    }
    Some((enter, exit))
}
fn add(a: [f64; 3], b: [f64; 3]) -> [f64; 3] {
    std::array::from_fn(|i| a[i] + b[i])
}
fn sub(a: [f64; 3], b: [f64; 3]) -> [f64; 3] {
    std::array::from_fn(|i| a[i] - b[i])
}
fn mul(a: [f64; 3], s: f64) -> [f64; 3] {
    a.map(|v| v * s)
}
fn dot(a: [f64; 3], b: [f64; 3]) -> f64 {
    a.iter().zip(b).map(|(x, y)| x * y).sum()
}
fn cross(a: [f64; 3], b: [f64; 3]) -> [f64; 3] {
    [
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    ]
}
fn triangle(ray: Ray, v: [[f64; 3]; 3]) -> Option<f64> {
    let e1 = sub(v[1], v[0]);
    let e2 = sub(v[2], v[0]);
    let p = cross(ray.direction, e2);
    let det = dot(e1, p);
    if det.abs() < 1e-14 {
        return None;
    }
    let inv = 1. / det;
    let q = sub(ray.origin, v[0]);
    let u = dot(q, p) * inv;
    if !(-1e-10..=1. + 1e-10).contains(&u) {
        return None;
    }
    let qv = cross(q, e1);
    let w = dot(ray.direction, qv) * inv;
    if w < -1e-10 || u + w > 1. + 1e-10 {
        return None;
    }
    let t = dot(e2, qv) * inv;
    (t >= 0.).then_some(t)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn exact_flat_edges_and_missing_data() {
        let field = Heightfield::new(128, vec![1000.; 129 * 129].into_boxed_slice()).unwrap();
        for (x, y) in [(0., 0.), (0.5, 0.5), (1., 1.), (0., 1.), (1., 0.)] {
            let hit = field
                .intersect(Ray {
                    origin: [x, y, 2000.],
                    direction: [0., 0., -1.],
                })
                .unwrap();
            assert!((hit.t - 1000.).abs() < 1e-8);
            assert_eq!(hit.point, [x, y, 1000.]);
        }
        assert!(field.sample(-0.01, 0.5).is_none());
        assert!(
            field
                .intersect(Ray {
                    origin: [2., 2., 2000.],
                    direction: [0., 0., -1.]
                })
                .is_none()
        );
        assert!(Heightfield::new(1, vec![f32::NAN; 4].into_boxed_slice()).is_err());
    }
    #[test]
    fn sampling_matches_triangles_not_bilinear() {
        let field = Heightfield::new(1, vec![0., 0., 0., 100.].into_boxed_slice()).unwrap();
        assert_eq!(field.sample(0.25, 0.25), Some(0.));
        assert_eq!(field.sample(0.75, 0.75), Some(50.));
        assert_eq!(
            field
                .intersect(Ray {
                    origin: [0.75, 0.75, 200.],
                    direction: [0., 0., -1.]
                })
                .unwrap()
                .t,
            150.
        );
        assert!(
            field
                .intersect_region(
                    Ray {
                        origin: [0.25, 0.25, 200.],
                        direction: [0., 0., -1.]
                    },
                    [0.5, 0.5, 1., 1.]
                )
                .is_none()
        );
    }
    #[test]
    fn traversal_matches_exhaustive_triangle_oracle() {
        let n = 16;
        let data = (0..(n + 1) * (n + 1))
            .map(|i| (((i * 719) % 523) as f32) / 10.)
            .collect::<Vec<_>>();
        let field = Heightfield::new(n, data.into_boxed_slice()).unwrap();
        for i in 0..600 {
            let ray = Ray {
                origin: [
                    (i % 29) as f64 / 20. - 0.2,
                    (i % 31) as f64 / 23. - 0.1,
                    100.,
                ],
                direction: [
                    ((i % 7) as f64 - 3.) * 0.006,
                    ((i % 11) as f64 - 5.) * 0.005,
                    -1.,
                ],
            };
            let expected = (0..n)
                .flat_map(|y| (0..n).flat_map(move |x| [(x, y, 0), (x, y, 1)]))
                .filter_map(|(x, y, t)| triangle(ray, field.triangles(x, y)[t]))
                .min_by(f64::total_cmp);
            let actual = field.intersect(ray).map(|h| h.t);
            match (actual, expected) {
                (Some(a), Some(b)) => assert!((a - b).abs() < 1e-7, "ray {i}: {a} vs {b}"),
                (None, None) => {}
                _ => panic!("ray {i}: {actual:?} vs {expected:?}"),
            }
        }
    }
}
