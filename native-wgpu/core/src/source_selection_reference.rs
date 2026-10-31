use super::*;
pub(super) fn demanded_sources(available: &[TileId], ideal: &[TileId]) -> Vec<TileId> {
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
pub(super) fn render_sources(available: &[TileId], ideal: &[TileId]) -> Vec<TileId> {
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
