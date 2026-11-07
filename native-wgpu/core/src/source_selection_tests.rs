use super::source_selection_reference as reference;
use super::*;
fn fixture(count: usize, seed: u32) -> (Vec<TileId>, Vec<TileId>) {
    let root = TileId {
        z: 10,
        x: 995,
        y: 650,
    };
    let ideal: Vec<_> = root
        .children()
        .into_iter()
        .flat_map(TileId::children)
        .collect();
    let mut available = vec![root];
    let mut random = seed;
    for n in 0..count {
        random = random.wrapping_mul(1664525).wrapping_add(1013904223);
        let dz = 1 + n % 10;
        let mask = (1 << dz) - 1;
        available.push(TileId {
            z: root.z + dz as u8,
            x: (root.x << dz) + (random & mask),
            y: (root.y << dz) + ((random >> 12) & mask),
        });
    }
    (available, ideal)
}
#[test]
fn indexed_owners_preserve_partial_full_and_sharper_covers() {
    for seed in 0..500 {
        let (mut available, mut ideal) = fixture((seed % 170) as usize, seed);
        if seed % 2 == 0 {
            available.remove(0);
        }
        if seed % 3 == 0 {
            available.reverse();
            ideal.reverse();
        }
        if seed % 5 == 0 {
            available.extend(ideal.iter().copied());
        }
        assert_eq!(
            demanded_sources(&available, &ideal),
            reference::demanded_sources(&available, &ideal)
        );
        assert_eq!(
            render_sources(&available, &ideal),
            reference::render_sources(&available, &ideal)
        );
        assert_eq!(
            resident_cover(&available),
            reference::resident_cover(&available)
        );
        let expected: Vec<_> = available
            .iter()
            .copied()
            .filter(|id| {
                !available
                    .iter()
                    .any(|other| id != other && id.contains(*other))
            })
            .collect();
        assert_eq!(finest_sources(&available), expected);
    }
    let root = TileId { z: 0, x: 0, y: 0 };
    let all: Vec<_> = root
        .children()
        .into_iter()
        .flat_map(TileId::children)
        .collect();
    assert_eq!(
        demanded_sources(&all, &[root]),
        reference::demanded_sources(&all, &[root])
    );
}
#[test]
#[ignore = "explicit release host CPU benchmark"]
fn benchmark_coverage_index() {
    fn elapsed(mut run: impl FnMut()) -> u128 {
        let t = std::time::Instant::now();
        for _ in 0..20 {
            run();
        }
        t.elapsed().as_nanos() / 20
    }
    let mut rows = Vec::new();
    for count in [16, 64, 160] {
        let (available, ideal) = fixture(count, 73);
        for kind in ["owners", "partition"] {
            let run = |arm| match kind {
                "owners" => {
                    std::hint::black_box(if arm == 0 {
                        reference::render_sources(&available, &ideal)
                    } else {
                        render_sources(&available, &ideal)
                    });
                }
                "partition" => {
                    std::hint::black_box(if arm == 0 {
                        reference::resident_cover(&available)
                    } else {
                        resident_cover(&available)
                    });
                }
                _ => {
                    std::hint::black_box(if arm == 0 {
                        available
                            .iter()
                            .copied()
                            .filter(|id| {
                                !available
                                    .iter()
                                    .any(|other| id != other && id.contains(*other))
                            })
                            .collect()
                    } else {
                        finest_sources(&available)
                    });
                }
            };
            let mut samples: [Vec<u128>; 2] = std::array::from_fn(|_| Vec::new());
            for trial in 0..90 {
                for arm in if trial % 2 == 0 { [0, 1] } else { [1, 0] } {
                    let ns = elapsed(|| run(arm));
                    if trial >= 10 {
                        samples[arm].push(ns);
                    }
                }
            }
            let mut med = samples.clone();
            med.iter_mut().for_each(|r| r.sort_unstable());
            rows.push(format!("{{\"kind\":\"{kind}\",\"sources\":{},\"beforeMedianNs\":{},\"afterMedianNs\":{},\"beforeNs\":{:?},\"afterNs\":{:?}}}", available.len(), med[0][40], med[1][40], samples[0], samples[1]));
        }
    }
    let report = format!(
        "{{\"scope\":\"Release host CPU; source ownership, exact cover partition and ideal leaves, no I/O or GPU\",\"cases\":[{}]}}",
        rows.join(",")
    );
    println!("{report}");
    if let Ok(path) = std::env::var("RIDGELINE_COVERAGE_OUTPUT") {
        std::fs::write(path, report).unwrap();
    }
}
