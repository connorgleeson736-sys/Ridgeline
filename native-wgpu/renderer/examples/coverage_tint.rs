//! Advanced "stored map detail" view on actual GPU execution: the terrain is
//! tinted by a coverage grid placed by Mercator bounds, and hiding it restores
//! the exact original picture.
use ridgeline_renderer::{Renderer, TileId, COVERAGE_SIZE};
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
fn texture(r: &Renderer) -> wgpu::Texture {
    r.device.create_texture(&wgpu::TextureDescriptor {
        label: Some("topo filter"),
        size: wgpu::Extent3d { width: 256, height: 256, depth_or_array_layers: 1 },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
        view_formats: &[],
    })
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
    r.upload(id, &vec![500.; 129 * 129], &[128u8, 128, 128, 255].repeat(256 * 256)).unwrap();
    let b = id.bounds();
    let center = [(b[0] + b[2]) / 2., (b[1] + b[3]) / 2.];
    let output = texture(&r);
    let target = output.create_view(&Default::default());
    let frame = |r: &mut Renderer| {
        for _ in 0..3 {
            r.render(&target, center, 11.6, 0., 0., 0.);
        }
        read(r, &output)
    };
    let plain = frame(&mut r);
    // West half red, east half green, over the tile.
    let n = COVERAGE_SIZE as usize;
    let mut grid = vec![0u8; n * n * 4];
    for row in 0..n {
        for col in 0..n {
            let i = (row * n + col) * 4;
            grid[i..i + 4].copy_from_slice(if col < n / 2 { &[235, 40, 30, 140] } else { &[40, 220, 60, 140] });
        }
    }
    r.set_coverage(Some((&grid, [b[0], b[1], b[2], b[3]])));
    let tinted = frame(&mut r);
    let px = |p: &[u8], x: usize, y: usize| { let i = (y * 256 + x) * 4; [p[i] as i32, p[i + 1] as i32, p[i + 2] as i32] };
    let west = px(&tinted, 80, 128);
    let east = px(&tinted, 176, 128);
    assert!(west[0] > west[1] + 40, "west tinted red: {west:?}");
    assert!(east[1] > east[0] + 40, "east tinted green: {east:?}");
    r.set_coverage(None);
    let back = frame(&mut r);
    assert!(plain == back, "hiding the view restores the exact picture");
    println!(
        "PASS stored map detail tint: west {west:?} red, east {east:?} green, exact when hidden ({})",
        if gles { "GLES" } else { "Vulkan" }
    );
}
