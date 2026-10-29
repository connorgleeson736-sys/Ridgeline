//! Controlled synthetic geometry, actual GPU execution. This is not real-terrain parity.
use ridgeline_renderer::{Renderer, TileId};
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
async fn run() {
    let mut descriptor = wgpu::InstanceDescriptor::new_without_display_handle();
    descriptor.backends = if std::env::args().any(|s|s=="--gles"){wgpu::Backends::GL}else{wgpu::Backends::VULKAN};
    let instance = wgpu::Instance::new(descriptor);
    let mut r = Renderer::new(
        &instance,
        None,
        Some(wgpu::TextureFormat::Rgba8Unorm),
        256,
        256,
    )
    .await
    .unwrap();
    let id = TileId {
        z: 11,
        x: 1991,
        y: 1300,
    };
    let bounds = id.bounds();
    let center = [(bounds[0] + bounds[2]) / 2., (bounds[1] + bounds[3]) / 2.];
    let span = bounds[2] - bounds[0];
    r.upload(
        id,
        &vec![500.; 129 * 129],
        &[160, 160, 160, 255].repeat(256 * 256),
    )
    .unwrap();
    let output = r.device.create_texture(&wgpu::TextureDescriptor {
        label: Some("route fixture"),
        size: wgpu::Extent3d {
            width: 256,
            height: 256,
            depth_or_array_layers: 1,
        },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
        view_formats: &[],
    });
    let target = output.create_view(&Default::default());
    r.render(&target, center, 11., 0., 0., 500.);
    let before = read(&r, &output);
    r.set_routes(vec![ridgeline_renderer::RoutePath {
        points: vec![
            [bounds[0] + span * 0.2, center[1]],
            [bounds[0] + span * 0.8, center[1]],
        ],
        color: [1., 0.1, 0.1, 1.],
        selected: Some(0),
    }]);
    r.render(&target, center, 11., 0., 0., 500.);
    let after = read(&r, &output);
    let changed = before
        .chunks_exact(4)
        .zip(after.chunks_exact(4))
        .filter(|(a, b)| a != b)
        .count();
    assert!(changed > 100, "native route is visible");
    let pixel = &after[(128 * 256 + 128) * 4..][..4];
    assert!(pixel[0] > pixel[1] + 40, "route crosses the actual center");
    let uploads = r.route_uploads();
    r.render(&target, center, 11., 0., 0., 500.);
    assert_eq!(uploads, r.route_uploads());
    r.render(
        &target,
        [center[0] + span * 0.01, center[1]],
        11.,
        0.,
        0.,
        500.,
    );
    assert_eq!(
        uploads,
        r.route_uploads(),
        "camera movement must not retessellate routes"
    );
    println!(
        "PASS native route GPU: {changed} affected pixels, visible draped line/waypoints, unchanged geometry uploads on idle/pan. Synthetic height fixture; not phone performance."
    );
}
