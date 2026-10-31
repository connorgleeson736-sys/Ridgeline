//! Exact CPU fallback filtering, including the former float normalization and
//! ties-to-even rounding. The original full-resolution pixels are never changed.
const NORMALIZED: [f32; 256] = {
    let mut values = [0.; 256];
    let mut i = 0;
    while i < 256 {
        values[i] = i as f32 / 255.;
        i += 1;
    }
    values
};

pub fn downsample_rgba(source: &[u8], width: usize, output: &mut [u8]) {
    assert!(width >= 2 && width.is_power_of_two());
    assert_eq!(source.len(), width * width * 4);
    let next = width / 2;
    assert_eq!(output.len(), next * next * 4);
    for (y, row) in output.chunks_exact_mut(next * 4).enumerate() {
        let upper = &source[y * 2 * width * 4..(y * 2 + 1) * width * 4];
        let lower = &source[(y * 2 + 1) * width * 4..(y * 2 + 2) * width * 4];
        for ((pixel, a), b) in row
            .chunks_exact_mut(4)
            .zip(upper.chunks_exact(8))
            .zip(lower.chunks_exact(8))
        {
            for c in 0..4 {
                let sum = NORMALIZED[a[c] as usize]
                    + NORMALIZED[a[c + 4] as usize]
                    + NORMALIZED[b[c] as usize]
                    + NORMALIZED[b[c + 4] as usize];
                pixel[c] = ((sum * 0.25) * 255.).round_ties_even().clamp(0., 255.) as u8;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    // Frozen CPU arithmetic from 3cc6f903, deliberately not integer averaging.
    fn reference(source: &[u8], width: usize, output: &mut [u8]) {
        let n = width / 2;
        for y in 0..n {
            for x in 0..n {
                for c in 0..4 {
                    let a = source[(y * 2 * width + x * 2) * 4 + c] as f32 / 255.;
                    let b = source[(y * 2 * width + x * 2 + 1) * 4 + c] as f32 / 255.;
                    let d = source[((y * 2 + 1) * width + x * 2) * 4 + c] as f32 / 255.;
                    let e = source[((y * 2 + 1) * width + x * 2 + 1) * 4 + c] as f32 / 255.;
                    output[(y * n + x) * 4 + c] = (((a + b + d + e) * 0.25) * 255.)
                        .round_ties_even()
                        .clamp(0., 255.) as u8;
                }
            }
        }
    }
    fn chain(source: &[u8], fast: bool) -> Vec<u8> {
        let mut source = source.to_vec();
        let mut width = 256;
        let mut result = Vec::new();
        while width > 1 {
            let mut next = vec![0; width * width];
            if fast {
                downsample_rgba(&source, width, &mut next);
            } else {
                reference(&source, width, &mut next);
            }
            result.extend_from_slice(&next);
            source = next;
            width /= 2;
        }
        result
    }
    #[test]
    fn all_levels_preserve_normalization_rounding_alpha_and_extremes() {
        for i in 0..256 {
            assert_eq!(NORMALIZED[i].to_bits(), (i as f32 / 255.).to_bits());
        }
        let mut random = 1u32;
        for seed in 0..32 {
            let data: Vec<u8> = (0..256 * 256 * 4)
                .map(|i| {
                    random = random.wrapping_mul(1664525).wrapping_add(1013904223);
                    match seed {
                        0 => 0,
                        1 => 255,
                        2 => (i % 256) as u8,
                        _ => (random >> 24) as u8,
                    }
                })
                .collect();
            assert_eq!(chain(&data, false), chain(&data, true));
        }
    }
    #[test]
    #[ignore = "explicit real-pixel CPU benchmark"]
    fn benchmark_genuine_chain() {
        let data = std::fs::read(std::env::var("RIDGELINE_MIP_FIXTURE").unwrap()).unwrap();
        assert_eq!(data.len(), 256 * 256 * 4);
        assert_eq!(chain(&data, false), chain(&data, true));
        let (mut before, mut after) = (Vec::new(), Vec::new());
        for _ in 0..40 {
            std::hint::black_box(chain(&data, false));
            std::hint::black_box(chain(&data, true));
        }
        for trial in 0..100 {
            for fast in if trial % 2 == 0 {
                [false, true]
            } else {
                [true, false]
            } {
                let start = std::time::Instant::now();
                for _ in 0..10 {
                    std::hint::black_box(chain(std::hint::black_box(&data), fast));
                }
                let nanos = start.elapsed().as_nanos() / 10;
                if fast {
                    after.push(nanos);
                } else {
                    before.push(nanos);
                }
            }
        }
        let mut a = before.clone();
        let mut b = after.clone();
        a.sort_unstable();
        b.sort_unstable();
        let report = format!(
            "{{\"scope\":\"Release host CPU: genuine 256px LINZ RGBA to complete eight-level mip chain; same allocations in both arms, no GPU upload or decode\",\"beforeMedianNs\":{},\"afterMedianNs\":{},\"beforeNs\":{:?},\"afterNs\":{:?},\"allMipBytesIdentical\":true}}",
            a[50], b[50], before, after
        );
        println!("{report}");
        if let Ok(path) = std::env::var("RIDGELINE_MIP_BENCH_OUTPUT") {
            std::fs::write(path, report).unwrap();
        }
    }
}
