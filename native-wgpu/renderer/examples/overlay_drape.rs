//! Topographic overlays draped like MapLibre's terrain render-to-texture, on
//! actual GPU execution (synthetic terrain): lines sit on the ground, terrain in
//! front hides them, and nothing is repainted by camera movement, by unrelated
//! line data, or while overlays are off.
use ridgeline_renderer::overlay::{Group, GroupKey, Line};
use ridgeline_renderer::{CartographicPath, Renderer, TileId};
fn main() {
    pollster::block_on(run());
}
fn read(r: &Renderer, output: &wgpu::Texture) -> Vec<u8> {
    let buffer = r.device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("readback"),
        size: 256 * 256 * 4,
        usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
        mapped_at_creation: false,
    });
    let mut encoder = r.device.create_command_encoder(&Default::default());
    encoder.copy_texture_to_buffer(
        output.as_image_copy(),
        wgpu::TexelCopyBufferInfo {
            buffer: &buffer,
            layout: wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(1024),
                rows_per_image: Some(256),
            },
        },
        wgpu::Extent3d {
            width: 256,
            height: 256,
            depth_or_array_layers: 1,
        },
    );
    r.queue.submit([encoder.finish()]);
    let (tx, rx) = std::sync::mpsc::channel();
    buffer
        .slice(..)
        .map_async(wgpu::MapMode::Read, move |result| {
            tx.send(result).unwrap();
        });
    r.device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
    rx.recv().unwrap().unwrap();
    let result = buffer.slice(..).get_mapped_range().unwrap().to_vec();
    buffer.unmap();
    result
}
fn settle(r: &mut Renderer, target: &wgpu::TextureView, center: [f64; 2], pitch: f32) {
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
    loop {
        r.render(target, center, 11., 0., pitch, 500.);
        if !r.overlays_pending() {
            break;
        }
        assert!(std::time::Instant::now() < deadline, "overlays complete");
    }
}
fn green(pixels: &[u8]) -> usize {
    pixels.chunks_exact(4).filter(|p| p[1] > p[0] + 60 && p[1] > p[2] + 60).count()
}
async fn scene(instance: &wgpu::Instance, heights: &[f32]) -> (Renderer, wgpu::Texture, [f64; 4]) {
    let mut r = Renderer::new(instance, None, Some(wgpu::TextureFormat::Rgba8Unorm), 256, 256)
        .await
        .unwrap();
    let id = TileId { z: 11, x: 1991, y: 1300 };
    r.upload(id, heights, &[160u8, 160, 160, 255].repeat(256 * 256)).unwrap();
    let output = r.device.create_texture(&wgpu::TextureDescriptor {
        label: Some("overlay drape"),
        size: wgpu::Extent3d { width: 256, height: 256, depth_or_array_layers: 1 },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
        view_formats: &[],
    });
    (r, output, id.bounds())
}
async fn run() {
    let gles = std::env::args().any(|s| s == "--gles");
    let mut descriptor = wgpu::InstanceDescriptor::new_without_display_handle();
    descriptor.backends = if gles { wgpu::Backends::GL } else { wgpu::Backends::VULKAN };
    let instance = wgpu::Instance::new(descriptor);
    // East-west line north of the centre (behind it for a camera looking north).
    let line = |b: [f64; 4], at: f64| CartographicPath {
        points: vec![[b[0] + (b[2] - b[0]) * 0.1, b[1] + (b[3] - b[1]) * at], [b[0] + (b[2] - b[0]) * 0.9, b[1] + (b[3] - b[1]) * at]],
        color: [0., 1., 0., 1.],
        width: 4.,
        dash: [0., 0.],
    };

    // 1. Flat ground, pitched view: the line is drawn on the ground.
    let (mut r, output, b) = scene(&instance, &vec![500.; 129 * 129]).await;
    let target = output.create_view(&Default::default());
    let center = [(b[0] + b[2]) / 2., (b[1] + b[3]) / 2.];
    settle(&mut r, &target, center, 0.);
    assert_eq!(r.cartography_jobs(), 0, "no overlay data: no overlay work");
    assert_eq!(green(&read(&r, &output)), 0);
    r.set_cartography(vec![line(b, 0.35)]);
    settle(&mut r, &target, center, 60.);
    let flat = green(&read(&r, &output));
    assert!(flat > 100, "line visible on the ground: {flat} px");
    let painted = r.cartography_jobs();
    assert!(painted >= 1);

    // 2. Camera movement within a zoom band repaints nothing.
    for step in 1..=5 {
        let moved = [center[0] + (b[2] - b[0]) * 0.01 * step as f64, center[1]];
        r.render(&target, moved, 11., 3. * step as f32, 60., 500.);
    }
    assert_eq!(r.cartography_jobs(), painted, "camera movement never repaints overlays");

    // 3. Line data far from every visible tile repaints nothing.
    let far = |g: u32| Group::new(
        GroupKey { z: 14, x: g, y: 0, clip: [0; 4] },
        g as u64,
        vec![Line::new(vec![[0.001 * g as f64, 0.001], [0.001 * g as f64 + 0.0005, 0.002]], [1., 0., 0., 1.], 2., [0., 0.], 0).unwrap()],
    );
    let near = |at: f64, hash: u64| {
        let p = line(b, at);
        Group::new(GroupKey { z: 14, x: 0, y: 1, clip: [0; 4] }, hash, vec![Line::new(p.points, p.color, p.width, p.dash, 1).unwrap()])
    };
    r.set_overlay_groups(vec![near(0.35, 7)]);
    settle(&mut r, &target, center, 60.);
    let base = r.cartography_jobs();
    r.set_overlay_groups(vec![near(0.35, 7), far(3)]);
    settle(&mut r, &target, center, 60.);
    assert_eq!(r.cartography_jobs(), base, "unrelated tiles' data does not repaint visible overlays");
    r.set_overlay_groups(vec![near(0.30, 8), far(3)]);
    settle(&mut r, &target, center, 60.);
    assert!(r.cartography_jobs() > base, "changed data repaints the tiles it touches");

    // 4. Overlays off: nothing drawn, nothing painted afterwards.
    r.set_overlay_groups(vec![]);
    settle(&mut r, &target, center, 60.);
    let off = r.cartography_jobs();
    for _ in 0..3 {
        r.render(&target, center, 11., 0., 60., 500.);
    }
    assert_eq!(green(&read(&r, &output)), 0, "overlays off: no lines");
    assert_eq!(r.cartography_jobs(), off, "overlays off: no overlay work");

    // 5. A 3,000 m ridge between the camera and the line hides it, as terrain does.
    let mut ridge = vec![500f32; 129 * 129];
    for row in 56..=60 {
        for column in 0..129 {
            ridge[row * 129 + column] = 3000.;
        }
    }
    let (mut hidden, output, b) = scene(&instance, &ridge).await;
    let target = output.create_view(&Default::default());
    hidden.set_cartography(vec![line(b, 0.35)]);
    settle(&mut hidden, &target, center, 60.);
    let behind = green(&read(&hidden, &output));
    assert!(behind * 4 < flat, "terrain in front hides the line ({behind} px vs {flat} px on flat ground)");
    println!(
        "PASS overlay drape: {flat} px on the ground, {behind} px behind a ridge; no repaint on camera moves or unrelated data; no work when off ({})",
        if gles { "GLES" } else { "Vulkan" }
    );
}
