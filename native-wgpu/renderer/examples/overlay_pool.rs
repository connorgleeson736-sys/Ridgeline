//! Many visible tiles with lines and a small overlay memory budget (as on a
//! 3 GB phone): every tile still gets its lines (two-tier pool), on actual GPU
//! execution with synthetic terrain.
use ridgeline_renderer::{CartographicPath, Renderer, TileId};
fn main() {
    pollster::block_on(run());
}
fn read(r: &Renderer, output: &wgpu::Texture) -> Vec<u8> {
    let buffer = r.device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("readback"),
        size: 512 * 512 * 4,
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
                bytes_per_row: Some(2048),
                rows_per_image: Some(512),
            },
        },
        wgpu::Extent3d {
            width: 512,
            height: 512,
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
    let gles = std::env::args().any(|s| s == "--gles");
    let mut descriptor = wgpu::InstanceDescriptor::new_without_display_handle();
    descriptor.backends = if gles { wgpu::Backends::GL } else { wgpu::Backends::VULKAN };
    let instance = wgpu::Instance::new(descriptor);
    let mut r = Renderer::new(&instance, None, Some(wgpu::TextureFormat::Rgba8Unorm), 512, 512).await.unwrap();
    r.set_overlay_layers(12);
    let parent = TileId { z: 11, x: 1991, y: 1300 };
    let pb = parent.bounds();
    let mut tiles = Vec::new();
    for y in 0..5u32 {
        for x in 0..5u32 {
            let id = TileId { z: 14, x: parent.x * 8 + 1 + x, y: parent.y * 8 + 1 + y };
            r.upload(id, &vec![500.; 129 * 129], &[160u8, 160, 160, 255].repeat(256 * 256)).unwrap();
            tiles.push(id);
        }
    }
    let b0 = tiles[0].bounds();
    let b1 = tiles[24].bounds();
    let center = [(b0[0] + b1[2]) / 2., (b0[1] + b1[3]) / 2.];
    // One horizontal line through the middle of each row of tiles.
    let lines = (0..5)
        .map(|row| {
            let b = tiles[row * 5].bounds();
            let y = (b[1] + b[3]) / 2.;
            CartographicPath { points: vec![[b0[0] - (pb[2] - pb[0]) * 0.01, y], [b1[2] + (pb[2] - pb[0]) * 0.01, y]], color: [0., 1., 0., 1.], width: 4., dash: [0., 0.] }
        })
        .collect();
    r.set_cartography(lines);
    let output = r.device.create_texture(&wgpu::TextureDescriptor {
        label: Some("overlay pool"),
        size: wgpu::Extent3d { width: 512, height: 512, depth_or_array_layers: 1 },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
        view_formats: &[],
    });
    let target = output.create_view(&Default::default());
    for _ in 0..20 {
        r.render(&target, center, 11.05, 0., 0., 500.);
        if !r.overlays_pending() {
            break;
        }
    }
    r.render(&target, center, 11.05, 0., 0., 500.);
    let (shown, missing) = r.overlay_coverage();
    let pixels = read(&r, &output);
    // Green rows: each of the five lines should cross the whole view.
    let green_rows = (0..512)
        .filter(|y| (0..512).filter(|x| { let p = &pixels[(y * 512 + x) * 4..][..4]; p[1] as u16 > p[0] as u16 + 60 }).count() > 280)
        .count();
    assert_eq!(missing, 0, "no visible tile without its lines ({shown} shown)");
    assert!(shown >= 25, "all 25 tiles show their lines ({shown})");
    assert!(green_rows >= 5, "five full-width lines ({green_rows} rows)");
    println!("PASS overlay pool: {shown} tiles with lines from 12 MiB (single tier held 12), none missing ({})", if gles { "GLES" } else { "Vulkan" });
}
