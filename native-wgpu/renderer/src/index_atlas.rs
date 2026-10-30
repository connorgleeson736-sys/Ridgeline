//! Stable suballocations. Only off-frame patterns may be evicted; current
//! frame topology stays valid even when its working set exceeds the atlas.
use ridgeline_core::surface::GridRect;
use std::collections::{BTreeMap, HashMap, HashSet};
pub(crate) type Key = (GridRect, u8, u8);
#[derive(Clone, Copy)]
struct Entry {
    start: u32,
    end: u32,
    used: u64,
}
pub(crate) struct IndexAtlas {
    capacity: u32,
    ranges: HashMap<Key, Entry>,
    free: BTreeMap<u32, u32>,
    clock: u64,
    pub allocations: u64,
    pub evictions: u64,
    pub overflow: u64,
    pub uploaded_bytes: u64,
}
impl IndexAtlas {
    pub fn new(capacity: u32) -> Self {
        Self {
            capacity,
            ranges: HashMap::new(),
            free: BTreeMap::from([(0, capacity)]),
            clock: 0,
            allocations: 0,
            evictions: 0,
            overflow: 0,
            uploaded_bytes: 0,
        }
    }
    pub fn begin_frame(&mut self, visible: &HashSet<Key>) {
        self.clock += 1;
        for key in visible {
            if let Some(row) = self.ranges.get_mut(key) {
                row.used = self.clock;
            }
        }
    }
    pub fn get(&self, key: &Key) -> Option<(u32, u32)> {
        self.ranges.get(key).map(|e| (e.start, e.end))
    }
    fn release(&mut self, mut start: u32, mut size: u32) {
        if let Some((&before, &length)) = self.free.range(..start).next_back() {
            if before + length == start {
                self.free.remove(&before);
                start = before;
                size += length;
            }
        }
        if let Some((&after, &length)) = self.free.range(start..).next() {
            if start + size == after {
                self.free.remove(&after);
                size += length;
            }
        }
        self.free.insert(start, size);
    }
    pub fn allocate(&mut self, key: Key, count: u32, visible: &HashSet<Key>) -> Option<(u32, u32)> {
        if let Some(row) = self.get(&key) {
            return Some(row);
        }
        if count > self.capacity {
            self.overflow += 1;
            return None;
        }
        loop {
            if let Some((start, size)) = self
                .free
                .iter()
                .find(|(_, size)| **size >= count)
                .map(|(a, b)| (*a, *b))
            {
                self.free.remove(&start);
                if size > count {
                    self.free.insert(start + count, size - count);
                }
                self.ranges.insert(
                    key,
                    Entry {
                        start,
                        end: start + count,
                        used: self.clock,
                    },
                );
                self.allocations += 1;
                self.uploaded_bytes += count as u64 * 2;
                return Some((start, start + count));
            }
            let victim = self
                .ranges
                .iter()
                .filter(|(key, _)| !visible.contains(key))
                .min_by_key(|(k, e)| (e.used, k.0.x0, k.0.y0, k.0.x1, k.0.y1, k.1, k.2))
                .map(|(k, e)| (*k, *e));
            let Some((id, entry)) = victim else {
                self.overflow += 1;
                return None;
            };
            self.ranges.remove(&id);
            self.release(entry.start, entry.end - entry.start);
            self.evictions += 1;
        }
    }
    pub fn stats(&self) -> [u64; 5] {
        [
            self.allocations,
            self.evictions,
            self.overflow,
            self.uploaded_bytes,
            self.ranges.len() as u64,
        ]
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn key(x: u16) -> Key {
        (
            GridRect {
                x0: x,
                y0: 0,
                x1: x + 1,
                y1: 1,
            },
            0,
            1,
        )
    }
    #[test]
    fn coalesces_adjacent_holes_without_moving_visible_ranges() {
        let mut atlas = IndexAtlas::new(24);
        for i in 0..4 {
            atlas.allocate(key(i), 6, &HashSet::new()).unwrap();
        }
        let visible = HashSet::from([key(0), key(3), key(4), key(5)]);
        atlas.begin_frame(&visible);
        assert_eq!(atlas.allocate(key(4), 12, &visible), Some((6, 18)));
        assert_eq!(atlas.get(&key(0)), Some((0, 6)));
        assert_eq!(atlas.get(&key(3)), Some((18, 24)));
        assert_eq!(atlas.allocate(key(5), 18, &visible), None);
        assert_eq!(atlas.get(&key(4)), Some((6, 18)));
    }
    #[test]
    fn churn_preserves_hot_topology_and_reduces_uploads() {
        let mut atlas = IndexAtlas::new(36);
        let hot = key(0);
        let mut old = HashSet::new();
        let mut next = 0;
        let mut old_uploads = 0;
        let mut old_hot = 0;
        for i in 1..=30 {
            let current = key(i);
            let visible = HashSet::from([hot, current]);
            let missing = visible.iter().filter(|k| !old.contains(*k)).count() as u32 * 12;
            if next + missing > 36 {
                old.clear();
                next = 0;
            }
            for k in [hot, current] {
                if old.insert(k) {
                    old_uploads += 1;
                    next += 12;
                    if k == hot {
                        old_hot += 1;
                    }
                }
            }
            atlas.begin_frame(&visible);
            for k in [hot, current] {
                atlas.allocate(k, 12, &visible).unwrap();
            }
            assert!(atlas.get(&hot).is_some());
        }
        println!(
            "INDEX_CHURN: previous {old_uploads} pattern uploads ({old_hot} hot), stable {} (1 hot); uploaded bytes {} -> {}",
            atlas.allocations,
            old_uploads * 24,
            atlas.uploaded_bytes
        );
        assert_eq!(atlas.allocations, 31);
        assert!(atlas.allocations < old_uploads as u64);
        assert_eq!(atlas.get(&hot), Some((0, 12)));
    }
}
