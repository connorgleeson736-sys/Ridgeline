use glam::Vec4;
use ridgeline_renderer::{camera_depth_range, camera_matrix};
#[test]
fn authoritative_reference_screen_positions() {
    let mut count = 0;
    let mut worst = 0f32;
    for line in include_str!("camera-goldens.txt")
        .lines()
        .filter(|line| !line.starts_with('#'))
    {
        let v: Vec<f64> = line
            .split_whitespace()
            .map(|x| x.parse().unwrap())
            .collect();
        let (m, scale) = camera_matrix(
            v[0] as u32,
            v[1] as u32,
            [v[2], v[3]],
            v[4],
            v[5] as f32,
            v[6] as f32,
            v[7] as f32,
        );
        let p = m * Vec4::new(v[8] as f32, v[9] as f32, (v[10] - v[7]) as f32 * scale, 1.);
        let x = (p.x / p.w + 1.) * v[0] as f32 / 2.;
        let y = (1. - p.y / p.w) * v[1] as f32 / 2.;
        let error = (x - v[11] as f32).abs().max((y - v[12] as f32).abs());
        worst = worst.max(error);
        let (near, far) = camera_depth_range(v[1] as u32, v[15], v[6], v[7], 0.);
        assert!((near - v[13]).abs() < 1e-6);
        assert!(
            (far - v[14]).abs() / v[14] < 1e-8,
            "far mismatch {far} vs {}: {line}",
            v[14]
        );
        assert!(
            error < 0.05,
            "projection differs by {error}px: {line}; got {x} {y}"
        );
        count += 1;
    }
    assert!(count > 1000);
    println!("{count} reference projections; worst Float32 screen deviation {worst}px");
}
