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
    let instance = wgpu::Instance::new(wgpu::InstanceDescriptor::new_without_display_handle());
    let mut r = Renderer::new(
        &instance,
        None,
        Some(wgpu::TextureFormat::Rgba8Unorm),
        256,
        256,
    )
    .await
    .expect("adapter/device");
    let output = r.device.create_texture(&wgpu::TextureDescriptor {
        label: Some("proof"),
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
    let parent = TileId {
        z: 12,
        x: 3980,
        y: 2600,
    };
    let bounds = parent.bounds();
    let center = [(bounds[0] + bounds[2]) / 2., (bounds[1] + bounds[3]) / 2.];
    let heights = vec![1000.; 129 * 129];
    let green = [40, 120, 80, 255].repeat(256 * 256);
    let red = [200, 70, 30, 255].repeat(256 * 256);
    r.upload(parent, &heights, &green).unwrap();
    r.render(
        &output.create_view(&Default::default()),
        center,
        12.,
        0.,
        0.,
        1000.,
    );
    let bytes = read(&r, &output);
    assert_eq!(
        &bytes[(128 * 256 + 128) * 4..][..4],
        &[36, 113, 74, 255],
        "exact reference affine grade"
    );
    // One detailed child must replace precisely its quadrant, leaving the other
    // three supplied by the parent. Render order is irrelevant to ownership.
    r.upload(
        TileId {
            z: 13,
            x: parent.x * 2,
            y: parent.y * 2,
        },
        &heights,
        &red,
    )
    .unwrap();
    r.render(
        &output.create_view(&Default::default()),
        center,
        12.,
        0.,
        0.,
        1000.,
    );
    let bytes = read(&r, &output);
    let pixel = |x: usize, y: usize| &bytes[(y * 256 + x) * 4..][..4];
    assert_ne!(pixel(64, 64), &[36, 113, 74, 255]);
    assert_eq!(pixel(192, 64), &[36, 113, 74, 255]);
    assert_eq!(pixel(64, 192), &[36, 113, 74, 255]);
    assert_eq!(pixel(192, 192), &[36, 113, 74, 255]);
    assert!(
        bytes.chunks_exact(4).all(|p| p != [189, 203, 208, 255]),
        "no uncovered fallback pixels"
    );
    // A minified 1px black/white checker must sample the generated pyramid,
    // not uninitialized levels or shimmer between original individual pixels.
    let checker: Vec<u8> = (0..256 * 256)
        .flat_map(|i| {
            let c = if (i % 256 + i / 256) % 2 == 0 { 0 } else { 255 };
            [c, c, c, 255]
        })
        .collect();
    let check_id = TileId {
        z: 12,
        x: parent.x + 1,
        y: parent.y,
    };
    let b = check_id.bounds();
    r.upload(check_id, &heights, &checker).unwrap();
    r.render(
        &output.create_view(&Default::default()),
        [(b[0] + b[2]) / 2., (b[1] + b[3]) / 2.],
        8.,
        0.,
        0.,
        1000.,
    );
    let bytes = read(&r, &output);
    assert_eq!(
        &bytes[(128 * 256 + 128) * 4..][..4],
        &[136, 136, 136, 255],
        "minified checker mip average and reference grade"
    );
    // The hard budget holds during a burst of unrelated new uploads.
    r.set_tile_budget(24);
    for x in 0..80 {
        r.upload(TileId { z: 12, x, y: 2500 }, &heights, &green)
            .unwrap();
        assert!(r.resident_count() <= 24);
    }
    println!(
        "PASS GPU: exact grade, fine-child/coarse-parent ownership, no fallback holes, trilinear mipmap average, bounded 24-tile residency. Synthetic geometry; no phone benchmark or real-terrain parity claim."
    );
}
