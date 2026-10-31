//! Synthetic skirt/batching A/B fixture; host wall time is not A20 GPU time.
use ridgeline_renderer::{MipmapMode, Renderer, TextureBackend, TileId};
use std::time::Instant;
fn read(r: &Renderer, texture: &wgpu::Texture) -> Vec<u8> {
    let size = 512 * 512 * 4;
    let buffer = r.device.create_buffer(&wgpu::BufferDescriptor {
        label: None,
        size,
        usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
        mapped_at_creation: false,
    });
    let mut encoder = r.device.create_command_encoder(&Default::default());
    encoder.copy_texture_to_buffer(
        texture.as_image_copy(),
        wgpu::TexelCopyBufferInfo {
            buffer: &buffer,
            layout: wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(2048),
                rows_per_image: Some(512),
            },
        },
        texture.size(),
    );
    r.queue.submit([encoder.finish()]);
    let (tx, rx) = std::sync::mpsc::channel();
    buffer
        .slice(..)
        .map_async(wgpu::MapMode::Read, move |v| tx.send(v).unwrap());
    r.device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
    rx.recv().unwrap().unwrap();
    let result = buffer.slice(..).get_mapped_range().unwrap().to_vec();
    buffer.unmap();
    result
}
fn main() {
    pollster::block_on(run());
}
async fn run() {
    let args: Vec<_> = std::env::args().collect();
    let mut desc = wgpu::InstanceDescriptor::new_without_display_handle();
    desc.backends = if args.iter().any(|v| v == "--gles") {
        wgpu::Backends::GL
    } else {
        wgpu::Backends::VULKAN
    };
    let instance = wgpu::Instance::new(desc);
    let backend = if args.iter().any(|v| v == "--arrays") {
        TextureBackend::Arrays
    } else {
        TextureBackend::Separate
    };
    let mut r = Renderer::new_with_texture_backend(
        &instance,
        None,
        Some(wgpu::TextureFormat::Rgba8Unorm),
        512,
        512,
        MipmapMode::Auto,
        backend,
    )
    .await
    .unwrap();
    let ids: Vec<_> = (0..16)
        .map(|i| TileId {
            z: 12,
            x: 3980 + i % 4,
            y: 2600 + i / 4,
        })
        .collect();
    r.set_demand(ids.clone(), ids.clone());
    for index in 0..16 {
        let i = (index * 5) % 16;
        let id = &ids[i];
        let heights: Vec<_> = (0..129 * 129)
            .map(|p| {
                let x = (id.x * 128 + p % 129) as f64;
                let y = (id.y * 128 + p / 129) as f64;
                (1600. + 600. * (x * 0.014).sin() * (y * 0.019).cos()) as f32
            })
            .collect();
        let rgba: Vec<u8> = (0..256 * 256)
            .flat_map(|p| [(40 + i * 7) as u8, (100 + p % 100) as u8, 70, 255])
            .collect();
        r.upload(*id, &heights, &rgba).unwrap();
    }
    let output = r.device.create_texture(&wgpu::TextureDescriptor {
        label: Some("skirt benchmark"),
        size: wgpu::Extent3d {
            width: 512,
            height: 512,
            depth_or_array_layers: 1,
        },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: r.format,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
        view_formats: &[],
    });
    let view = output.create_view(&Default::default());
    let b = ids[0].bounds();
    let d = b[2] - b[0];
    let center = [b[0] + 2. * d, b[1] + 2. * d];
    for pitch in [0., 60., 75.] {
        r.render(&view, center, 10.5, 25., pitch, 1600.);
        r.device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
        let start = Instant::now();
        for _ in 0..8 {
            r.render(&view, center, 10.5, 25., pitch, 1600.);
            r.device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
        }
        let us = start.elapsed().as_micros();
        let pixels = read(&r, &output);
        let hash = pixels.iter().fold(0xcbf29ce484222325u64, |h, b| {
            (h ^ *b as u64).wrapping_mul(0x100000001b3)
        });
        println!(
            "pitch={pitch} elapsedUs8Frames={us} draws={} binds={} triangles={} hash={hash:016x}",
            r.last_frame_draw_calls, r.last_frame_texture_binds, r.drawn_triangles
        );
    }
}
