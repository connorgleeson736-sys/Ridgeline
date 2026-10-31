//! Pure, host-testable geographic and terrain primitives. No Android or browser runtime.
pub mod mipmap;
/// MapLibre altitude uses its mean Earth radius (6371008.8m), not EPSG:3857 radius.
pub const ALTITUDE_CIRCUMFERENCE: f64 = 40030228.88407185;
pub const EARTH_CIRCUMFERENCE: f64 = 40075016.68557849;
pub fn mercator(lon: f64, lat: f64) -> [f64; 2] {
    let lat = lat.clamp(-85.0511287798066, 85.0511287798066).to_radians();
    [
        (lon + 180.) / 360.,
        (1. - (lat.tan() + 1. / lat.cos()).ln() / std::f64::consts::PI) / 2.,
    ]
}
pub fn terrain_rgb(rgb: [u8; 3]) -> f64 {
    -10000. + (rgb[0] as f64 * 65536. + rgb[1] as f64 * 256. + rgb[2] as f64) * 0.1
}
pub fn terrarium(rgb: [u8; 3]) -> f64 {
    rgb[0] as f64 * 256. + rgb[1] as f64 + rgb[2] as f64 / 256. - 32768.
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct TileId {
    pub z: u8,
    pub x: u32,
    pub y: u32,
}
impl TileId {
    pub fn parent(self) -> Option<Self> {
        (self.z > 0).then(|| Self {
            z: self.z - 1,
            x: self.x / 2,
            y: self.y / 2,
        })
    }
    pub fn bounds(self) -> [f64; 4] {
        // Every u8 zoom has an exactly representable inverse power of two.
        // Construct it directly instead of exponentiation and four divisions.
        let scale = f64::from_bits((1023 - self.z as u64) << 52);
        [
            self.x as f64 * scale,
            self.y as f64 * scale,
            (self.x as f64 + 1.) * scale,
            (self.y as f64 + 1.) * scale,
        ]
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn direct_bounds_are_bit_identical_for_all_zoom_values_and_coordinate_extremes() {
        let mut random = 43u32;
        for z in 0..=255 {
            for i in 0..1024 {
                random = random.wrapping_mul(1664525).wrapping_add(1013904223);
                let x = if i == 0 {
                    0
                } else if i == 1 {
                    u32::MAX
                } else {
                    random
                };
                random = random.wrapping_mul(1664525).wrapping_add(1013904223);
                let y = random;
                let n = 2f64.powi(z as i32);
                let before = [
                    x as f64 / n,
                    y as f64 / n,
                    (x as f64 + 1.) / n,
                    (y as f64 + 1.) / n,
                ];
                let after = TileId { z, x, y }.bounds();
                assert_eq!(before.map(f64::to_bits), after.map(f64::to_bits));
            }
        }
    }
    #[test]
    fn encoding_is_height_not_channel_interpolation() {
        assert!((terrain_rgb([1, 173, 176]) - 1000.).abs() < 1e-8);
        assert_eq!(terrarium([128, 0, 0]), 0.);
    }
    #[test]
    fn adjacent_tiles_share_exact_edges() {
        for z in 1..22 {
            let a = TileId { z, x: 0, y: 0 }.bounds();
            let b = TileId { z, x: 1, y: 0 }.bounds();
            assert_eq!(a[2], b[0]);
        }
    }
    #[test]
    fn projection_reference() {
        assert_eq!(mercator(0., 0.), [0.5, 0.5]);
        let p = mercator(170.141, -43.595);
        assert!(p[0] > 0.97 && p[1] > 0.63 && p[1] < 0.64);
    }
}

/// Bilinear DEM samples on global pixel-centre coordinates. A caller supplies
/// the halo from real adjacent DEM tiles; missing data stays missing.
pub fn sample_dem(mut pixel: impl FnMut(i64, i64) -> Option<f64>, x: f64, y: f64) -> Option<f64> {
    let ix = x.floor() as i64;
    let iy = y.floor() as i64;
    let fx = x - ix as f64;
    let fy = y - iy as f64;
    Some(
        pixel(ix, iy)? * (1. - fx) * (1. - fy)
            + pixel(ix + 1, iy)? * fx * (1. - fy)
            + pixel(ix, iy + 1)? * (1. - fx) * fy
            + pixel(ix + 1, iy + 1)? * fx * fy,
    )
}
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct CoverRegion {
    pub source: TileId,
    pub region: TileId,
}
impl TileId {
    pub fn contains(self, other: Self) -> bool {
        if self.z > other.z {
            return false;
        };
        let d = other.z - self.z;
        (other.x >> d) == self.x && (other.y >> d) == self.y
    }
    pub fn children(self) -> [Self; 4] {
        std::array::from_fn(|i| Self {
            z: self.z + 1,
            x: self.x * 2 + (i % 2) as u32,
            y: self.y * 2 + (i / 2) as u32,
        })
    }
}
/// Exact non-overlapping partition of resident terrain. An available coarse
/// tile fills siblings while sharper descendants replace only their footprint.
/// Native geometry is never fabricated for uncovered regions.
/// Output uses quadtree child (Morton) order; spatial joins rely on this order.
pub fn resident_cover(available: &[TileId]) -> Vec<CoverRegion> {
    use std::collections::HashSet;
    let exact: HashSet<_> = available.iter().copied().collect();
    let mut branches = HashSet::new();
    for id in available {
        let mut node = *id;
        while let Some(parent) = node.parent() {
            branches.insert(parent);
            node = parent;
        }
    }
    fn visit(
        id: TileId,
        source: Option<TileId>,
        exact: &HashSet<TileId>,
        branches: &HashSet<TileId>,
        out: &mut Vec<CoverRegion>,
    ) {
        let source = if exact.contains(&id) {
            Some(id)
        } else {
            source
        };
        if branches.contains(&id) {
            for child in id.children() {
                visit(child, source, exact, branches, out)
            }
        } else if let Some(source) = source {
            out.push(CoverRegion { source, region: id });
        }
    }
    let mut out = Vec::new();
    visit(
        TileId { z: 0, x: 0, y: 0 },
        None,
        &exact,
        &branches,
        &mut out,
    );
    out
}
/// Choose render owners from the current ideal cover, not every cached descendant.
/// Ready ideal tiles win immediately. Missing ideals keep real loaded children
/// and their nearest loaded ancestor until replacement is ready.
pub fn demanded_sources(available: &[TileId], ideal: &[TileId]) -> Vec<TileId> {
    use std::collections::HashSet;
    let exact: HashSet<_> = available.iter().copied().collect();
    fn sharper(target: TileId, rows: &[TileId], exact: &HashSet<TileId>) -> Option<Vec<TileId>> {
        let descendants: Vec<_> = rows
            .iter()
            .copied()
            .filter(|id| target.contains(*id) && id.z > target.z)
            .collect();
        if !descendants.is_empty() && target.z < 22 {
            let mut cover = Vec::new();
            let mut full = true;
            for child in target.children() {
                match sharper(child, &descendants, exact) {
                    Some(parts) => cover.extend(parts),
                    None => {
                        full = false;
                        break;
                    }
                }
            }
            if full {
                return Some(cover);
            }
        }
        exact.contains(&target).then(|| vec![target])
    }
    let mut selected = HashSet::new();
    for target in ideal {
        if let Some(ready) = sharper(*target, available, &exact) {
            selected.extend(ready);
            continue;
        }
        selected.extend(available.iter().copied().filter(|id| target.contains(*id)));
        let mut ancestor = target.parent();
        while let Some(id) = ancestor {
            if exact.contains(&id) {
                selected.insert(id);
                break;
            }
            ancestor = id.parent();
        }
    }
    let mut out: Vec<_> = selected.into_iter().collect();
    out.sort_by_key(|id| (id.z, id.x, id.y));
    out
}
/// Keep the nearest resident ancestor as a backdrop even after an ideal tile
/// arrives. A camera frame may precede the next demand plan during a drag;
/// dropping this background would expose cached siblings as empty space.
pub fn render_sources(available: &[TileId], ideal: &[TileId]) -> Vec<TileId> {
    use std::collections::HashSet;
    let exact: HashSet<_> = available.iter().copied().collect();
    let mut selected: HashSet<_> = demanded_sources(available, ideal).into_iter().collect();
    for target in ideal {
        let mut parent = target.parent();
        while let Some(id) = parent {
            if exact.contains(&id) {
                selected.insert(id);
                break;
            }
            parent = id.parent();
        }
    }
    let mut out: Vec<_> = selected.into_iter().collect();
    out.sort_by_key(|id| (id.z, id.x, id.y));
    out
}
#[cfg(test)]
mod terrain_tests {
    use super::*;
    #[test]
    fn halo_has_identical_samples_on_shared_edges() {
        let value = |x: i64, y: i64| Some((x * x + y * 3) as f64 / 10.);
        for y in 0..129 {
            let gy = 3000. * 256. + y as f64 * 2. - 0.5;
            let left = sample_dem(value, 4000. * 256. + 255.5, gy);
            let right = sample_dem(value, 4001. * 256. - 0.5, gy);
            assert_eq!(left, right);
        }
    }
    #[test]
    fn absent_halo_is_not_zero_height() {
        assert_eq!(sample_dem(|_, _| None, 1.5, 2.5), None);
    }
    #[test]
    fn coarse_parent_fills_three_siblings_without_overlap() {
        let p = TileId {
            z: 12,
            x: 3980,
            y: 2600,
        };
        let c = p.children()[0];
        let rows = resident_cover(&[p, c]);
        assert_eq!(rows.len(), 4);
        assert_eq!(rows.iter().filter(|r| r.source == c).count(), 1);
        assert_eq!(rows.iter().filter(|r| r.source == p).count(), 3);
        for a in &rows {
            for b in &rows {
                if a != b {
                    assert!(!a.region.contains(b.region));
                }
            }
        }
    }
    #[test]
    fn complete_children_replace_parent() {
        let p = TileId {
            z: 12,
            x: 3980,
            y: 2600,
        };
        let mut ids = vec![p];
        ids.extend(p.children());
        let rows = resident_cover(&ids);
        assert_eq!(rows.len(), 4);
        assert!(rows.iter().all(|r| r.source != p));
    }
    #[test]
    fn long_zoom_jump_preserves_exact_coverage() {
        let p = TileId { z: 4, x: 15, y: 10 };
        let f = TileId {
            z: 12,
            x: p.x * 256 + 90,
            y: p.y * 256 + 90,
        };
        let rows = resident_cover(&[p, f]);
        let area: f64 = rows.iter().map(|r| 2f64.powi(-2 * r.region.z as i32)).sum();
        assert_eq!(area, 2f64.powi(-2 * p.z as i32));
        assert!(rows.len() < 32);
    }
}

pub mod heightfield;

pub mod surface;

#[cfg(test)]
mod demand_tests {
    use super::*;
    #[test]
    fn ready_ideal_wins_over_cached_descendants_in_either_camera_direction() {
        let p = TileId {
            z: 10,
            x: 991,
            y: 650,
        };
        let c = p.children()[0];
        let fine = c.children()[0];
        assert_eq!(demanded_sources(&[p, c, fine], &[p]), vec![p]);
        assert_eq!(demanded_sources(&[p, c, fine], &[c]), vec![c]);
        let fallback = demanded_sources(&[p, fine], &[c]);
        assert!(fallback.contains(&p) && fallback.contains(&fine));
        assert_eq!(demanded_sources(&[fine, c, p], &[c]), vec![c]);
    }
    #[test]
    fn missing_ideal_keeps_real_parent_until_its_children_arrive() {
        let p = TileId {
            z: 10,
            x: 991,
            y: 650,
        };
        let desired = p.children();
        assert_eq!(demanded_sources(&[p], &desired), vec![p]);
        let mut available = vec![p, desired[0]];
        let fallback = demanded_sources(&available, &desired);
        let cover = resident_cover(&fallback);
        assert_eq!(cover.iter().filter(|r| r.source == p).count(), 3);
        available.extend_from_slice(&desired[1..]);
        assert_eq!(demanded_sources(&available, &desired).len(), 4);
        assert!(!demanded_sources(&available, &desired).contains(&p));
    }
}

pub mod adaptive;

#[cfg(test)]
mod cached_child_cover_tests {
    use super::*;
    #[test]
    fn complete_sharper_children_win_over_an_available_parent() {
        let p = TileId {
            z: 12,
            x: 3982,
            y: 2600,
        };
        let mut available = vec![p];
        available.extend(p.children());
        let selected = demanded_sources(&available, &[p]);
        assert_eq!(selected.len(), 4);
        assert!(!selected.contains(&p));
        available.pop();
        let fallback = demanded_sources(&available, &[p]);
        assert_eq!(fallback, vec![p]);
    }
}

#[cfg(test)]
mod fallback_backdrop_tests {
    use super::*;
    #[test]
    fn fine_arrival_keeps_cached_siblings_covered_before_the_next_pan_plan() {
        let p = TileId {
            z: 12,
            x: 3983,
            y: 2600,
        };
        let c = p.children()[0];
        let owners = render_sources(&[p, c], &[c]);
        let cover = resident_cover(&owners);
        assert_eq!(cover.iter().filter(|r| r.source == p).count(), 3);
        assert_eq!(cover.iter().filter(|r| r.source == c).count(), 1);
        let mut available = vec![p];
        available.extend(p.children());
        let full = resident_cover(&render_sources(&available, &p.children()));
        assert_eq!(full.len(), 4);
        assert!(full.iter().all(|r| r.source != p));
        // Zooming back out must not revive a lone stale fine child.
        assert_eq!(render_sources(&[p, c], &[p]), vec![p]);
    }
}
