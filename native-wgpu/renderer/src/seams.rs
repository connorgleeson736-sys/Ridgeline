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
pub(crate) fn masks(cover: &[CoverRegion], tiles: &HashMap<TileId, Tile>) -> HashMap<TileId, u8> {
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
    for edges in lines.values() {
        for &edge in edges {
            let mut covered = Vec::new();
            for &other in edges {
                if edge.side == other.side {
                    continue;
                }
                let lo = edge.lo.max(other.lo);
                let hi = edge.hi.min(other.hi);
                if lo < hi && continuous(edge, other, lo, hi, tiles) {
                    covered.push((lo, hi));
                }
            }
            covered.sort_unstable();
            let mut end = edge.lo;
            for (lo, hi) in covered {
                if lo > end {
                    break;
                }
                end = end.max(hi);
            }
            if end == edge.hi {
                *masks.get_mut(&edge.region).unwrap() &= !edge.bit;
            }
        }
    }
    masks
}
