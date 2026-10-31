//! Omit curtains only when resident terrain proves the entire adjoining edge
//! continuous. Unknown, missing and discontinuous edges retain their skirts.
use crate::{Tile, TileId};
use ridgeline_core::CoverRegion;
use std::collections::HashMap;
#[derive(Clone, Copy)]
struct Edge {
    region: TileId,
    source: TileId,
    axis: u8,
    line: u32,
    lo: u32,
    hi: u32,
    bit: u8,
    side: bool,
}
// Proofs never retain GPU resources. Immutable source generations, exact join
// interval and axis are all part of the key; IDs alone would survive a reload.
#[derive(Clone, Copy, Debug, Hash, PartialEq, Eq)]
struct ProofKey {
    sources: [(u8, u32, u32, u64); 2],
    axis: u8,
    line: u32,
    lo: u32,
    hi: u32,
}
impl ProofKey {
    fn new(a: Edge, b: Edge, lo: u32, hi: u32, ga: u64, gb: u64) -> Self {
        let mut sources = [
            (a.source.z, a.source.x, a.source.y, ga),
            (b.source.z, b.source.x, b.source.y, gb),
        ];
        sources.sort_unstable();
        Self {
            sources,
            axis: a.axis,
            line: a.line,
            lo,
            hi,
        }
    }
}
#[derive(Default)]
pub(crate) struct Proofs {
    rows: HashMap<ProofKey, bool>,
}
impl Proofs {
    fn get(&mut self, key: ProofKey, test: impl FnOnce() -> bool) -> bool {
        let result = *self.rows.entry(key).or_insert_with(test);
        if self.rows.len() > 8192 {
            self.rows.clear();
        }
        result
    }
    fn begin(&mut self) {
        // Bound retained CPU metadata even through long journeys. Recomputing a
        // proof affects work only; it cannot change which edges keep skirts.
        if self.rows.len() > 8192 {
            self.rows.clear();
        }
    }
}
fn continuous(a: Edge, b: Edge, lo: u32, hi: u32, tiles: &HashMap<TileId, Tile>) -> bool {
    if a.source == b.source {
        return true;
    }
    let fixed = (a.line as u64) << 7;
    for id in [a.source, b.source] {
        if fixed % (1u64 << (22 - id.z)) != 0 {
            return false;
        }
    }
    let step = 1u64 << (22 - a.source.z.max(b.source.z));
    let sample = |edge: Edge, p: u64| {
        let (x, y) = if edge.axis == 0 {
            (fixed, p)
        } else {
            (p, fixed)
        };
        let bounds = edge.source.bounds();
        let size = bounds[2] - bounds[0];
        tiles[&edge.source].field.sample(
            (x as f64 / (1u64 << 29) as f64 - bounds[0]) / size,
            (y as f64 / (1u64 << 29) as f64 - bounds[1]) / size,
        )
    };
    let mut p = (lo as u64) << 7;
    let end = (hi as u64) << 7;
    loop {
        match (sample(a, p), sample(b, p)) {
            (Some(x), Some(y)) if x == y => {}
            _ => return false,
        }
        if p == end {
            return true;
        }
        p = (p + step).min(end);
    }
}
pub(crate) fn masks(
    cover: &[CoverRegion],
    tiles: &HashMap<TileId, Tile>,
    proofs: &mut Proofs,
) -> HashMap<TileId, u8> {
    proofs.begin();
    let mut lines: HashMap<(u8, u32), Vec<Edge>> = HashMap::new();
    let mut masks = HashMap::new();
    for row in cover {
        let id = row.region;
        let shift = 22 - id.z;
        let x0 = id.x << shift;
        let x1 = (id.x + 1) << shift;
        let y0 = id.y << shift;
        let y1 = (id.y + 1) << shift;
        masks.insert(id, 15);
        for (axis, line, lo, hi, bit, side) in [
            (0, x0, y0, y1, 1, false),
            (0, x1, y0, y1, 2, true),
            (1, y0, x0, x1, 4, false),
            (1, y1, x0, x1, 8, true),
        ] {
            lines.entry((axis, line)).or_default().push(Edge {
                region: id,
                source: row.source,
                axis,
                line,
                lo,
                hi,
                bit,
                side,
            });
        }
    }
    for edges in lines.values_mut() {
        join_line(edges, &mut masks, |a, b, lo, hi| {
            if a.source == b.source {
                return true;
            }
            let key = ProofKey::new(
                a,
                b,
                lo,
                hi,
                tiles[&a.source].generation,
                tiles[&b.source].generation,
            );
            proofs.get(key, || continuous(a, b, lo, hi, tiles))
        });
    }
    masks
}

// A resident cover is a disjoint partition: intervals on either side of a
// line never overlap. Visit each intersection once, in order, and advance
// both edges' contiguous coverage rather than allocating/sorting per edge.
fn join_line(
    edges: &mut [Edge],
    masks: &mut HashMap<TileId, u8>,
    mut test: impl FnMut(Edge, Edge, u32, u32) -> bool,
) {
    edges.sort_unstable_by_key(|e| (e.side, e.lo, e.hi));
    let split = edges.partition_point(|e| !e.side);
    let mut covered: Vec<u32> = edges.iter().map(|e| e.lo).collect();
    let (mut a, mut b) = (0, split);
    while a < split && b < edges.len() {
        let (left, right) = (edges[a], edges[b]);
        let lo = left.lo.max(right.lo);
        let hi = left.hi.min(right.hi);
        if lo < hi && test(left, right, lo, hi) {
            if lo == covered[a] {
                covered[a] = hi;
            }
            if lo == covered[b] {
                covered[b] = hi;
            }
        }
        if left.hi <= right.hi {
            a += 1;
        }
        if right.hi <= left.hi {
            b += 1;
        }
    }
    for (edge, end) in edges.iter().zip(covered) {
        if end == edge.hi {
            *masks.get_mut(&edge.region).unwrap() &= !edge.bit;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn edge(x: u32, lo: u32, hi: u32, side: bool) -> Edge {
        let region = TileId { z: 10, x, y: 0 };
        Edge {
            region,
            source: region,
            axis: 0,
            line: 1,
            lo,
            hi,
            bit: if side { 2 } else { 1 },
            side,
        }
    }
    fn reference(
        edges: &[Edge],
        test: impl Fn(Edge, Edge, u32, u32) -> bool,
    ) -> HashMap<TileId, u8> {
        let mut masks: HashMap<_, _> = edges.iter().map(|e| (e.region, 15)).collect();
        for &a in edges {
            let mut intervals = Vec::new();
            for &b in edges {
                let (lo, hi) = (a.lo.max(b.lo), a.hi.min(b.hi));
                if a.side != b.side && lo < hi && test(a, b, lo, hi) {
                    intervals.push((lo, hi));
                }
            }
            intervals.sort_unstable();
            let mut end = a.lo;
            for (lo, hi) in intervals {
                if lo > end {
                    break;
                }
                end = end.max(hi);
            }
            if end == a.hi {
                *masks.get_mut(&a.region).unwrap() &= !a.bit;
            }
        }
        masks
    }
    #[test]
    fn continuity_proofs_require_identical_source_generations_and_join() {
        let a = edge(1, 0, 16, false);
        let b = edge(2, 0, 16, true);
        let key = ProofKey::new(a, b, 0, 16, 7, 9);
        let mut proofs = Proofs::default();
        assert!(!proofs.get(key, || false));
        assert!(!proofs.get(ProofKey::new(b, a, 0, 16, 9, 7), || panic!(
            "retested immutable join"
        )));
        assert!(proofs.get(ProofKey::new(a, b, 0, 16, 8, 9), || true));
        assert!(proofs.get(ProofKey::new(a, b, 1, 16, 7, 9), || true));
        assert!(proofs.get(ProofKey { axis: 1, ..key }, || true));
        assert!(proofs.get(ProofKey { line: 2, ..key }, || true));
        for line in 0..9000 {
            proofs.get(ProofKey { line, ..key }, || true);
        }
        assert!(proofs.rows.len() <= 8192);
    }
    #[test]
    #[ignore = "explicit release host CPU benchmark"]
    fn benchmark_reused_continuity() {
        use ridgeline_core::heightfield::Heightfield;
        let heights = |offset: f32| {
            (0..129 * 129)
                .map(|i| offset + (i % 129) as f32 * 0.25 + (i / 129) as f32 * 0.5)
                .collect::<Vec<_>>()
                .into_boxed_slice()
        };
        let left = Heightfield::new(128, heights(0.)).unwrap();
        let right = Heightfield::new(128, heights(32.)).unwrap();
        let test = || {
            (0..=128).all(|y| left.sample(1., y as f64 / 128.) == right.sample(0., y as f64 / 128.))
        };
        let key = ProofKey::new(edge(1, 0, 16, false), edge(2, 0, 16, true), 0, 16, 7, 9);
        let mut proofs = Proofs::default();
        let mut rows: [Vec<u128>; 2] = std::array::from_fn(|_| Vec::new());
        for trial in 0..100 {
            for arm in if trial % 2 == 0 { [0, 1] } else { [1, 0] } {
                let start = std::time::Instant::now();
                for _ in 0..100 {
                    std::hint::black_box(if arm == 0 {
                        test()
                    } else {
                        proofs.get(key, test)
                    });
                }
                if trial >= 20 {
                    rows[arm].push(start.elapsed().as_nanos() / 100);
                }
            }
        }
        let mut sorted = rows.clone();
        sorted.iter_mut().for_each(|r| r.sort_unstable());
        let report = format!(
            "{{\"scope\":\"Release host CPU, repeated continuity of an immutable 129-sample shared edge; no whole-frame, I/O or device claim\",\"beforeMedianNs\":{},\"afterMedianNs\":{},\"beforeNs\":{:?},\"afterNs\":{:?}}}",
            sorted[0][40], sorted[1][40], rows[0], rows[1]
        );
        println!("{report}");
        if let Ok(path) = std::env::var("RIDGELINE_CONTINUITY_OUTPUT") {
            std::fs::write(path, report).unwrap();
        }
    }
    #[test]
    fn sweep_matches_pairwise_with_mixed_lod_holes_and_discontinuities() {
        for hole in 0..17 {
            let mut edges = vec![edge(0, 0, 16, false), edge(1, 16, 32, false)];
            for i in 0..16 {
                if i != hole {
                    edges.push(edge(i + 2, i * 2, i * 2 + 2, true));
                }
            }
            for failure in 0..17 {
                let test = |a: Edge, b: Edge, _: u32, _: u32| {
                    a.region.x != failure + 2 && b.region.x != failure + 2
                };
                let expected = reference(&edges, test);
                let mut actual = edges.iter().map(|e| (e.region, 15)).collect();
                let mut calls = 0;
                join_line(&mut edges, &mut actual, |a, b, lo, hi| {
                    calls += 1;
                    test(a, b, lo, hi)
                });
                assert_eq!(actual, expected);
                assert!(calls <= 16, "shared joins tested once");
            }
        }
    }
    #[test]
    #[ignore = "explicit host CPU benchmark"]
    fn benchmark_sweep() {
        let edges: Vec<_> = (0..256)
            .flat_map(|i| {
                [
                    edge(i, i * 2, i * 2 + 2, false),
                    edge(i + 256, i * 2, i * 2 + 2, true),
                ]
            })
            .collect();
        let mut before = Vec::new();
        let mut after = Vec::new();
        for iteration in 0..40 {
            let mut run = |fast: bool| {
                let start = std::time::Instant::now();
                if fast {
                    let mut input = edges.clone();
                    let mut masks = edges.iter().map(|e| (e.region, 15)).collect();
                    join_line(&mut input, &mut masks, |_, _, _, _| true);
                    std::hint::black_box(masks);
                    after.push(start.elapsed().as_nanos());
                } else {
                    std::hint::black_box(reference(&edges, |_, _, _, _| true));
                    before.push(start.elapsed().as_nanos());
                }
            };
            if iteration % 2 == 0 {
                run(false);
                run(true);
            } else {
                run(true);
                run(false);
            }
        }
        before.sort_unstable();
        after.sort_unstable();
        let report = format!(
            "{{\"beforeMedianNs\":{},\"afterMedianNs\":{},\"beforeSamples\":{:?},\"afterSamples\":{:?},\"edges\":512,\"oldContinuityCalls\":512,\"newContinuityCalls\":256}}",
            before[20], after[20], before, after
        );
        println!("{report}");
        if let Ok(path) = std::env::var("RIDGELINE_SEAM_BENCH_OUTPUT") {
            std::fs::write(path, report).unwrap();
        }
    }
}
