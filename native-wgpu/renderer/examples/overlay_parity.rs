//! Capacitor/MapLibre overlay parity on actual GPU execution (synthetic terrain):
//! dash patterns stay fixed to the ground while zooming. Label occlusion is
//! covered by label_occlusion.rs.
use ridgeline_renderer::{Renderer, TileId};
fn main() {
    pollster::block_on(run());
}
fn render(r: &mut Renderer, target: &wgpu::TextureView, center: [f64; 2], zoom: f64, pitch: f32) {
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
    loop {
        r.render(target, center, zoom, 0., pitch, 500.);
        if !r.cartography_pending() {
            break;
        }
        assert!(std::time::Instant::now() < deadline, "cartography completes");
        std::thread::sleep(std::time::Duration::from_millis(1));
    }
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
        wgpu::Extent3d { width: 256, height: 256, depth_or_array_layers: 1 },
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
/// Screen x positions where the dashed line's coverage crosses 50% on row 128.
/// Red is 160 on the grey ground and 0 on the green line.
fn transitions(pixels: &[u8]) -> Vec<f64> {
    let red = |x: usize| pixels[(128 * 256 + x) * 4] as f64;
    let mut out = Vec::new();
    for x in 1..256 {
        let (a, b) = (red(x - 1) - 80., red(x) - 80.);
        if (a < 0.) != (b < 0.) {
            out.push(x as f64 - 1. + a.abs() / (a.abs() + b.abs()));
        }
    }
    out
}
async fn run() {
    let gles = std::env::args().any(|s| s == "--gles");
    let mut descriptor = wgpu::InstanceDescriptor::new_without_display_handle();
    descriptor.backends = if gles { wgpu::Backends::GL } else { wgpu::Backends::VULKAN };
    let instance = wgpu::Instance::new(descriptor);
    let mut r = Renderer::new(&instance, None, Some(wgpu::TextureFormat::Rgba8Unorm), 256, 256)
        .await
        .unwrap();
    let id = TileId { z: 11, x: 1991, y: 1300 };
    let b = id.bounds();
    let span = b[2] - b[0];
    let center = [(b[0] + b[2]) / 2., (b[1] + b[3]) / 2.];
    let output = r.device.create_texture(&wgpu::TextureDescriptor {
        label: Some("overlay parity"),
        size: wgpu::Extent3d { width: 256, height: 256, depth_or_array_layers: 1 },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
        view_formats: &[],
    });
    let target = output.create_view(&Default::default());
    let grey = [160u8, 160, 160, 255].repeat(256 * 256);
    r.upload(id, &vec![500.; 129 * 129], &grey).unwrap();

    // --- Dashes: fixed to the ground while zooming (flat renderer) --------
    // The line starts far west so the screen centre is ~250 px along it.
    r.set_cartography(vec![ridgeline_renderer::CartographicPath {
        points: vec![[b[0] + span * 0.01, center[1]], [b[2] - span * 0.01, center[1]]],
        color: [0., 1., 0., 1.],
        width: 4.,
        dash: [6., 6.],
    }]);
    render(&mut r, &target, center, 11., 0.);
    let at = transitions(&read(&r, &output));
    render(&mut r, &target, center, 11.02, 0.);
    let zoomed = transitions(&read(&r, &output));
    assert!(at.len() > 10, "dashed line is visible: {at:?}");
    // Around the screen centre a ground-fixed pattern scales about the centre:
    // a 0.02 zoom step moves a transition 20 px from centre by ~0.3 px. The
    // previous screen-space phase slid every transition ~3.5 px here.
    let mut worst: f64 = 0.;
    for &x in at.iter().filter(|x| (**x - 128.).abs() < 20.) {
        let expected = 128. + (x - 128.) * 2f64.powf(0.02);
        let nearest = zoomed.iter().map(|z| (z - expected).abs()).fold(f64::MAX, f64::min);
        worst = worst.max(nearest);
    }
    assert!(worst < 1., "dash transitions stay fixed to the ground while zooming (worst {worst:.2} px)");
    println!("PASS ground-fixed dashes: worst transition drift {worst:.2} px over a 0.02 zoom step ({})", if gles { "GLES" } else { "Vulkan" });
}
