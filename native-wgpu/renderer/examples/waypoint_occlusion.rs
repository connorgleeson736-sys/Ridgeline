//! Waypoint dots on terrain, actual GPU execution (synthetic terrain): a dot is
//! drawn whole on the ground it marks (not cut in half by the slope of the
//! ground just in front of it), but a real ridge in front still hides it.
use ridgeline_renderer::{Renderer, RoutePath, TileId};
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
fn settle(r: &mut Renderer, target: &wgpu::TextureView, center: [f64; 2]) {
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
    loop {
        r.render(target, center, 13., 0., 60., 500.);
        if !r.overlays_pending() {
            break;
        }
        assert!(std::time::Instant::now() < deadline, "routes complete");
        std::thread::sleep(std::time::Duration::from_millis(1));
    }
    r.render(target, center, 13., 0., 60., 500.);
}
async fn scene(instance: &wgpu::Instance, heights: &[f32]) -> (Vec<u8>, [f32; 3]) {
    let mut r = Renderer::new(instance, None, Some(wgpu::TextureFormat::Rgba8Unorm), 256, 256)
        .await
        .unwrap();
    let id = TileId { z: 11, x: 1991, y: 1300 };
    let b = id.bounds();
    let span = b[2] - b[0];
    let center = [(b[0] + b[2]) / 2., (b[1] + b[3]) / 2.];
    r.upload(id, heights, &[160u8, 160, 160, 255].repeat(256 * 256)).unwrap();
    let output = r.device.create_texture(&wgpu::TextureDescriptor {
        label: Some("waypoint fixture"),
        size: wgpu::Extent3d { width: 256, height: 256, depth_or_array_layers: 1 },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
        view_formats: &[],
    });
    let target = output.create_view(&Default::default());
    // The route leaves the waypoint northwards (up the screen), so the screen
    // area below the dot is covered by the dot alone.
    r.set_routes(vec![RoutePath {
        points: vec![center, [center[0], center[1] - span * 0.2]],
        color: [232. / 255., 121. / 255., 68. / 255., 1.],
        selected: None,
    }]);
    settle(&mut r, &target, center);
    let dot = r.project_waypoints()[0];
    (read(&r, &output), dot)
}
async fn run() {
    let gles = std::env::args().any(|s| s == "--gles");
    let mut descriptor = wgpu::InstanceDescriptor::new_without_display_handle();
    descriptor.backends = if gles { wgpu::Backends::GL } else { wgpu::Backends::VULKAN };
    let instance = wgpu::Instance::new(descriptor);
    let fill = |pixels: &[u8], x: f32, y: f32| {
        let p = &pixels[((y.round() as usize) * 256 + x.round() as usize) * 4..][..3];
        p[0] > 235 && p[1] > 235 && p[2] > 225
    };
    // Flat ground at 60 degrees pitch: the ground just below the dot on screen
    // is nearer the camera than the dot's anchor.
    let (flat, dot) = scene(&instance, &vec![500.; 129 * 129]).await;
    assert_eq!(dot[2], 1., "waypoint projected: {dot:?}");
    // White fill rows in the dot's column: radius 4 px gives about 8 rows,
    // centred on the projected point. A half dot gives about 4.
    let rows: Vec<usize> = (0..256).filter(|y| fill(&flat, dot[0], *y as f32) && (*y as f32 - dot[1]).abs() < 10.).collect();
    let (top, bottom) = (*rows.first().unwrap() as f32, *rows.last().unwrap() as f32);
    let middle = (top + bottom + 1.) / 2.;
    assert!(bottom - top + 1. >= 7. && (middle - dot[1]).abs() <= 1., "the whole dot is drawn: rows {top}..{bottom}, point at {:.1}", dot[1]);
    // A 3,000 m ridge between the camera and the waypoint still hides it.
    let mut ridge = vec![500f32; 129 * 129];
    for row in 70..=74 {
        for column in 0..129 {
            ridge[row * 129 + column] = 3000.;
        }
    }
    let (blocked, dot) = scene(&instance, &ridge).await;
    let hidden = dot[2] == 0. || (-3..=3).all(|dy| !fill(&blocked, dot[0], dot[1] + dy as f32));
    assert!(hidden, "a ridge in front still hides the waypoint");
    println!("PASS waypoint dots drawn whole on the ground and hidden behind ridges ({})", if gles { "GLES" } else { "Vulkan" });
}
