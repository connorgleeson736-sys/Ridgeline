//! Labels over rough terrain while a steeply tilted camera pans slowly, as when
//! someone drags a pitched map. A name may go behind a ridge or come out from it
//! once as the view moves, but must not blink on and off frame to frame.
//! Each label is drawn in its own colour on black ground; its opacity per frame
//! is read back, and quick reversals (shown, hidden, shown again within a few
//! frames, or the opposite) are counted.
use ridgeline_renderer::{LabelSprite, Renderer, TileId};
const SIZE: u32 = 320;
fn main() {
    pollster::block_on(run());
}
fn read(r: &Renderer, output: &wgpu::Texture) -> Vec<u8> {
    let buffer = r.device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("readback"),
        size: (SIZE * SIZE * 4) as u64,
        usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
        mapped_at_creation: false,
    });
    let mut encoder = r.device.create_command_encoder(&Default::default());
    encoder.copy_texture_to_buffer(
        output.as_image_copy(),
        wgpu::TexelCopyBufferInfo {
            buffer: &buffer,
            layout: wgpu::TexelCopyBufferLayout { offset: 0, bytes_per_row: Some(SIZE * 4), rows_per_image: Some(SIZE) },
        },
        wgpu::Extent3d { width: SIZE, height: SIZE, depth_or_array_layers: 1 },
    );
    r.queue.submit([encoder.finish()]);
    let (tx, rx) = std::sync::mpsc::channel();
    buffer.slice(..).map_async(wgpu::MapMode::Read, move |result| {
        tx.send(result).unwrap();
    });
    r.device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
    rx.recv().unwrap().unwrap();
    let result = buffer.slice(..).get_mapped_range().unwrap().to_vec();
    buffer.unmap();
    result
}
/// Rough mountains: a few octaves of smooth value noise, 300 m to about 2,600 m.
fn terrain(seed: u32) -> Vec<f32> {
    let hash = |x: i32, y: i32, o: u32| {
        let mut h = (x as u32).wrapping_mul(374761393) ^ (y as u32).wrapping_mul(668265263) ^ seed.wrapping_mul(2246822519) ^ o.wrapping_mul(3266489917);
        h = (h ^ (h >> 13)).wrapping_mul(1274126177);
        (h ^ (h >> 16)) as f32 / u32::MAX as f32
    };
    let noise = |x: f32, y: f32, o: u32| {
        let (x0, y0) = (x.floor(), y.floor());
        let (fx, fy) = (x - x0, y - y0);
        let s = |t: f32| t * t * (3. - 2. * t);
        let (sx, sy) = (s(fx), s(fy));
        let (x0, y0) = (x0 as i32, y0 as i32);
        let a = hash(x0, y0, o) + (hash(x0 + 1, y0, o) - hash(x0, y0, o)) * sx;
        let b = hash(x0, y0 + 1, o) + (hash(x0 + 1, y0 + 1, o) - hash(x0, y0 + 1, o)) * sx;
        a + (b - a) * sy
    };
    (0..129 * 129)
        .map(|i| {
            let (x, y) = ((i % 129) as f32, (i / 129) as f32);
            let mut h = 0.;
            let mut amplitude = 1.;
            let mut frequency = 1. / 24.;
            for o in 0..5 {
                h += noise(x * frequency, y * frequency, o) * amplitude;
                amplitude *= 0.5;
                frequency *= 2.;
            }
            300. + h * 1200.
        })
        .collect()
}
/// Per label, its opacity in each frame (0..1).
/// Per label, its opacity in each frame (0..1), and whether it was at the image's edge.
async fn pan(instance: &wgpu::Instance, seed: u32, pitch: f32, bearing: f32) -> (Vec<Vec<f32>>, Vec<Vec<bool>>) {
    let mut r = Renderer::new(instance, None, Some(wgpu::TextureFormat::Rgba8Unorm), SIZE, SIZE).await.unwrap();
    let id = TileId { z: 11, x: 1991, y: 1300 };
    let b = id.bounds();
    let (w, h) = (b[2] - b[0], b[3] - b[1]);
    // Black ground: a label's pixels are its colour times its opacity.
    r.upload(id, &terrain(seed), &[0u8, 0, 0, 255].repeat(256 * 256)).unwrap();
    let output = r.device.create_texture(&wgpu::TextureDescriptor {
        label: Some("flicker"),
        size: wgpu::Extent3d { width: SIZE, height: SIZE, depth_or_array_layers: 1 },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
        view_formats: &[],
    });
    let target = output.create_view(&Default::default());
    // Label k: red 255, green 8k, blue 255 (4 texels each, sampled at the middle two).
    const N: usize = 30;
    let mut atlas = Vec::new();
    for k in 0..N {
        for _ in 0..4 {
            atlas.extend_from_slice(&[255, (8 * k) as u8, 255, 255]);
        }
    }
    let width = (N * 4) as u32;
    let sprites: Vec<LabelSprite> = (0..N)
        .map(|k| {
            let (i, j) = (k % 6, k / 6);
            LabelSprite {
                point: [b[0] + w * (0.12 + 0.15 * i as f64), b[1] + h * (0.10 + 0.17 * j as f64)],
                uv: [(k * 4 + 1) as f32 / width as f32, 0., 2. / width as f32, 1.],
                size: [10., 6.],
            }
        })
        .collect();
    r.set_label_atlas(width, 1, &atlas, sprites.clone());
    r.select_labels((0..N).map(|k| (k, [-5., -3.], 0.)).collect());
    let start = [b[0] + w * 0.5, b[1] + h * 0.85];
    for _ in 0..3 {
        r.render(&target, start, 12.2, bearing, pitch, 1500.);
    }
    std::thread::sleep(std::time::Duration::from_millis(300));
    let mut alphas = vec![Vec::new(); N];
    let mut edges = vec![Vec::new(); N];
    // A slow drag north-east, about 2 px a frame at 60 frames a second.
    for step in 0..120 {
        let m: f64 = std::env::var("PAN").ok().and_then(|v| v.parse().ok()).unwrap_or(1.);
        let c = [start[0] + w * 0.0006 * m * step as f64, start[1] - h * 0.0011 * m * step as f64];
        // As tiles load, the app sends the candidates again (a new batch: same
        // names, new rows) and their placement a frame or two later; and placement
        // drops a name for a while (collisions change as the view moves).
        if step % 15 == 7 {
            let mut again = sprites.clone();
            again.rotate_left(step % 4);
            let n = again.len();
            let shift = step % 4;
            r.set_label_metadata(again);
            r.render(&target, c, 12.2, bearing, pitch, 1500.);
            r.select_labels((0..N).map(|k| ((k + n - shift) % n, [-5., -3.], 0.)).filter(|(i, _, _)| !(dropped(step) && (*i + shift) % n == DROP)).collect());
        } else if dropped(step) != dropped(step.wrapping_sub(1)) {
            let shift = (step / 15 * 15 + 7) % 4;
            let shift = if step < 7 { 0 } else { shift };
            let n = N;
            r.select_labels((0..N).map(|k| ((k + n - shift) % n, [-5., -3.], 0.)).filter(|(i, _, _)| !(dropped(step) && (*i + shift) % n == DROP)).collect());
        }
        r.render(&target, c, 12.2, bearing, pitch, 1500.);
        let pixels = read(&r, &output);
        let mut best = [0f32; N];
        let mut edge = [false; N];
        for (n, p) in pixels.chunks_exact(4).enumerate() {
            let (x, y) = ((n as u32 % SIZE), (n as u32 / SIZE));
            if p[0] < 40 || p[2] < 40 || (p[0] as i32 - p[2] as i32).abs() > 6 {
                continue;
            }
            let a = p[0] as f32 / 255.;
            let k = ((p[1] as f32 / a) / 8.).round() as usize;
            if k < N {
                best[k] = best[k].max(a);
                edge[k] |= x < 12 || y < 12 || x >= SIZE - 12 || y >= SIZE - 12;
            }
        }
        for k in 0..N {
            alphas[k].push(best[k]);
            edges[k].push(edge[k]);
        }
        std::thread::sleep(std::time::Duration::from_millis(16));
    }
    (alphas, edges)
}
/// Frames in which placement leaves out label DROP (then puts it back).
/// The label placement leaves out for a while (on screen in most of these views).
const DROP: usize = 26;
fn dropped(step: usize) -> bool {
    (40..52).contains(&step)
}
/// Quick reversals: shown, then hidden, then shown again (or the opposite)
/// within [window] frames, and all changes of state.
fn reversals(alpha: &[f32], window: usize) -> (usize, usize) {
    let state: Vec<bool> = alpha.iter().map(|a| *a > 0.5).collect();
    let changes: Vec<usize> = (1..state.len()).filter(|&i| state[i] != state[i - 1]).collect();
    let quick = changes.windows(2).filter(|c| c[1] - c[0] <= window).count();
    (quick, changes.len())
}
async fn run() {
    let gles = std::env::args().any(|s| s == "--gles");
    let verbose = std::env::args().any(|s| s == "--verbose");
    let mut descriptor = wgpu::InstanceDescriptor::new_without_display_handle();
    descriptor.backends = if gles { wgpu::Backends::GL } else { wgpu::Backends::VULKAN };
    let instance = wgpu::Instance::new(descriptor);
    let mut quick = 0;
    let mut changes = 0;
    let mut hidden_frames = 0;
    let mut steps = 0;
    for (seed, pitch, bearing) in [(1, 70., 0.), (2, 75., 30.), (3, 65., -20.), (4, 78., 10.)] {
        let (alphas, edges) = pan(&instance, seed, pitch, bearing).await;
        for (k, a) in alphas.iter().enumerate() {
            let (q, c) = reversals(a, 8);
            quick += q;
            changes += c;
            hidden_frames += a.iter().filter(|v| **v < 0.5).count();
            // Opacity steps larger than half between consecutive frames: a pop, not a
            // fade (except a label leaving or entering across the image's edge).
            steps += (1..a.len()).filter(|&i| (a[i] - a[i - 1]).abs() > 0.5 && !edges[k][i] && !edges[k][i - 1]).count();
            if verbose && (c > 0 || k == DROP) {
                let s: String = a.iter().map(|v| if *v > 0.75 { '#' } else if *v > 0.25 { '+' } else { '.' }).collect();
                println!("seed {seed} label {k:2}: {s}");
            }
        }
    }
    println!(
        "label flicker ({}): {quick} quick reversals, {changes} changes, {steps} pops, {hidden_frames} hidden label-frames",
        if gles { "GLES" } else { "Vulkan" }
    );
    if std::env::args().any(|s| s == "--check") {
        assert_eq!(quick, 0, "no label blinks off and on (or on and off) within a few frames");
        assert_eq!(steps, 0, "labels fade, never pop, while the view moves");
        assert!(hidden_frames > 0, "some labels go behind terrain in these views");
        println!("PASS label flicker: no blinking while panning a tilted map ({changes} smooth changes)");
    }
}
