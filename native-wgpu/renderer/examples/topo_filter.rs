//! NZ Topo filtering and reduced resolution while moving, on actual GPU
//! execution. Shrunk printed-map tiles are bilinear (MapLibre's
//! LINEAR_MIPMAP_NEAREST), not point-sampled; enlarged tiles keep crisp texel
//! edges; and a reduced render scale changes sharpness only, then returns to
//! the exact full-resolution frame.
use ridgeline_renderer::{MapStyle, Renderer, TileId};
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
/// Share of pixels in the central 64x64 square that are mid grey / pure.
fn tones(pixels: &[u8]) -> (f64, f64) {
    let (mut grey, mut pure, mut n) = (0, 0, 0);
    for y in 96..160 {
        for x in 96..160 {
            let v = pixels[(y * 256 + x) * 4] as i32;
            n += 1;
            if (40..=215).contains(&v) {
                grey += 1;
            }
            if v < 20 || v > 235 {
                pure += 1;
            }
        }
    }
    (grey as f64 / n as f64, pure as f64 / n as f64)
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
    // One-texel black and white columns: the worst case for resampling.
    let mut rgba = Vec::with_capacity(256 * 256 * 4);
    for _ in 0..256 {
        for x in 0..256 {
            let v = if x % 2 == 0 { 0 } else { 255 };
            rgba.extend_from_slice(&[v, v, v, 255]);
        }
    }
    r.upload(id, &vec![0.; 129 * 129], &rgba).unwrap();
    let b = id.bounds();
    let center = [(b[0] + b[2]) / 2., (b[1] + b[3]) / 2.];
    r.set_map_style(MapStyle { basemap: 2, three_d: false, natural: false, ..Default::default() });
    let output = texture(&r);
    let target = output.create_view(&Default::default());
    let shot = |r: &mut Renderer, texels_per_pixel: f64| {
        // A z11 tile spans 512 px at zoom 11 and has 256 texels.
        let zoom = 11. + (0.5 / texels_per_pixel).log2();
        for _ in 0..3 {
            r.render(&target, center, zoom, 0., 0., 0.);
        }
        read(r, &output)
    };
    // Shrunk 1.39x (a rounded tile zoom about 0.47 below the camera).
    let (grey, pure) = tones(&shot(&mut r, 1.39));
    assert!(grey > 0.5, "shrunk tiles are filtered, not point-sampled: {:.0}% grey, {:.0}% pure", grey * 100., pure * 100.);
    // Enlarged 1.8x: crisp texel edges with a one-pixel antialiased edge.
    let (grey_up, pure_up) = tones(&shot(&mut r, 1. / 1.8));
    assert!(pure_up > 0.45 && grey_up > 0.05, "enlarged tiles stay crisp: {:.0}% pure, {:.0}% edge", pure_up * 100., grey_up * 100.);

    // Reduced resolution while moving: same picture, softer; exact on return.
    let mut r = Renderer::new(&instance, None, Some(wgpu::TextureFormat::Rgba8Unorm), 256, 256)
        .await
        .unwrap();
    let output = texture(&r);
    let target = output.create_view(&Default::default());
    let mut photo = Vec::with_capacity(256 * 256 * 4);
    for y in 0..256u32 {
        for x in 0..256u32 {
            photo.extend_from_slice(&[x as u8, y as u8, 90, 255]);
        }
    }
    r.upload(id, &vec![500.; 129 * 129], &photo).unwrap();
    let frame = |r: &mut Renderer| {
        for _ in 0..3 {
            r.render(&target, center, 11.3, 20., 50., 2500.);
        }
        read(r, &output)
    };
    r.set_render_scales(1., 0.75);
    let full = frame(&mut r);
    r.set_render_scales(0.75, 1.);
    assert_eq!(r.render_scale(), 0.75);
    let reduced = frame(&mut r);
    if std::env::var("TOPO_DUMP").is_ok() {
        std::fs::write(std::env::var("TOPO_DUMP").unwrap() + "/full.rgba", &full).unwrap();
        std::fs::write(std::env::var("TOPO_DUMP").unwrap() + "/reduced.rgba", &reduced).unwrap();
    }
    let mean_error = full.iter().zip(&reduced).map(|(a, b)| (*a as i32 - *b as i32).abs() as f64).sum::<f64>() / full.len() as f64;
    assert!(mean_error < 6., "reduced scale keeps the picture: mean error {mean_error:.2}");
    r.set_render_scales(1., 0.75);
    let back = frame(&mut r);
    assert!(full == back, "full resolution returns exactly");
    println!(
        "PASS NZ Topo filtering: shrunk {:.0}% filtered grey (was point-sampled), enlarged {:.0}% crisp; 0.75x moving scale mean error {mean_error:.2}, exact on return ({})",
        grey * 100., pure_up * 100., if gles { "GLES" } else { "Vulkan" }
    );
}
