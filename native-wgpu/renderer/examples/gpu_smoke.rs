//! Synthetic fixture only: validates native GPU draw/readback, never a terrain parity claim.
use ridgeline_renderer::{Renderer, TileId};
fn main() {
    pollster::block_on(run());
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
        label: Some("smoke output"),
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
    let rgba = [40u8, 120, 80, 255].repeat(256 * 256);
    r.upload(
        TileId {
            z: 12,
            x: 3980,
            y: 2600,
        },
        &vec![1000.; 129 * 129],
        &rgba,
    )
    .unwrap();
    let b = TileId {
        z: 12,
        x: 3980,
        y: 2600,
    }
    .bounds();
    r.render(
        &output.create_view(&Default::default()),
        [(b[0] + b[2]) / 2., (b[1] + b[3]) / 2.],
        12.,
        0.,
        0.,
        1000.,
    );
    let buffer = r.device.create_buffer(&wgpu::BufferDescriptor {
        label: None,
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
    let bytes = buffer.slice(..).get_mapped_range().unwrap();
    let center = &bytes[(128 * 256 + 128) * 4..(128 * 256 + 128) * 4 + 4];
    assert_ne!(
        center,
        &[189, 203, 208, 255],
        "terrain must actually cover center"
    );
    assert_eq!(center[3], 255);
    println!(
        "Native wgpu terrain draw + readback passed; center={center:?}; synthetic heights, not visual parity"
    );
}
