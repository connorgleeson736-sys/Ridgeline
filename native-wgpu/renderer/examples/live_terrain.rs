//! Genuine LINZ inputs from tools/fetch-live-proof.py; host GPU evidence only.
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
    let dir = std::path::PathBuf::from(std::env::args().nth(1).expect("fixture directory"));
    // Fixed documented Aoraki tile; the fetcher emits this same coordinate.
    let parent = TileId {
        z: 11,
        x: 1991,
        y: 1300,
    };
    let mut descriptor = wgpu::InstanceDescriptor::new_without_display_handle();
    if std::env::args().any(|v| v == "--gles") {
        descriptor.backends = wgpu::Backends::GL;
    }
    let instance = wgpu::Instance::new(descriptor);
    let mut r = Renderer::new_with_mipmap_mode(
        &instance,
        None,
        Some(wgpu::TextureFormat::Rgba8Unorm),
        256,
        256,
        if std::env::args().any(|v| v == "--cpu-mips") {
            ridgeline_renderer::MipmapMode::Cpu
        } else if std::env::args().any(|v| v == "--render-mips") {
            ridgeline_renderer::MipmapMode::Render
        } else {
            ridgeline_renderer::MipmapMode::Auto
        },
    )
    .await
    .expect("GPU adapter");
    let output = r.device.create_texture(&wgpu::TextureDescriptor {
        label: Some("live-terrain-proof"),
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
    let mut fields = Vec::new();
    for i in 0..2 {
        let bytes = std::fs::read(dir.join(format!("tile{i}.heights"))).unwrap();
        assert_eq!(bytes.len(), 129 * 129 * 4);
        let heights: Vec<f32> = bytes
            .chunks_exact(4)
            .map(|v| f32::from_le_bytes(v.try_into().unwrap()))
            .collect();
        assert!(heights.iter().all(|v| v.is_finite()));
        let image = std::fs::read(dir.join(format!("tile{i}.rgba"))).unwrap();
        assert_eq!(image.len(), 256 * 256 * 4);
        r.upload(
            TileId {
                x: parent.x + i,
                ..parent
            },
            &heights,
            &image,
        )
        .unwrap();
        fields.push(heights);
    }
    if std::env::args().any(|v| v == "--independent-images") {
        for (i, id) in parent.children().iter().enumerate() {
            let image = std::fs::read(dir.join(format!("child{i}.rgba"))).unwrap();
            assert_eq!(image.len(), 256 * 256 * 4);
            r.upload_image(*id, &image).unwrap();
        }
    }
    for j in 0..129 {
        assert_eq!(fields[0][j * 129 + 128], fields[1][j * 129]);
    }
    let bounds = parent.bounds();
    let center = [(bounds[0] + bounds[2]) / 2., (bounds[1] + bounds[3]) / 2.];
    let elevation = fields[0][64 * 129 + 64] as f64;
    r.render(
        &output.create_view(&Default::default()),
        center,
        12.,
        0.,
        0.,
        elevation as f32,
    );
    let pixels = read(&r, &output);
    let hit = r.pick_at(128., 128.).expect("live terrain pick");
    assert!(
        (hit[2] - elevation).abs() < 0.02,
        "center must hit genuine mesh height"
    );
    assert!(pixels.chunks_exact(4).all(|p| p[3] == 255));
    assert!(
        pixels.chunks_exact(4).any(|p| p != &pixels[..4]),
        "nonuniform real image"
    );
    // Save a lossless unencoded RGB capture; convert to PNG with Pillow for review.
    let mut ppm = b"P6\n256 256\n255\n".to_vec();
    for p in pixels.chunks_exact(4) {
        ppm.extend_from_slice(&p[..3]);
    }
    std::fs::write(dir.join("native-live.ppm"), ppm).unwrap();
    r.render(
        &output.create_view(&Default::default()),
        center,
        12.,
        -24.,
        58.,
        elevation as f32,
    );
    let oblique = read(&r, &output);
    let args: Vec<_> = std::env::args().collect();
    if let Some(i) = args.iter().position(|v| v == "--pick") {
        let x: f64 = args.get(i + 1).expect("pick x").parse().unwrap();
        let y: f64 = args.get(i + 2).expect("pick y").parse().unwrap();
        if let Some(hit) = r.pick_at(x, y) {
            let point = ridgeline_renderer::mercator(hit[0], hit[1]);
            println!(
                "Diagnostic ground uv=({:.10},{:.10}) height={:.3}",
                (point[0] - bounds[0]) / (bounds[2] - bounds[0]),
                (point[1] - bounds[1]) / (bounds[3] - bounds[1]),
                hit[2]
            );
        } else {
            println!("Diagnostic pick: no actual terrain hit");
        }
    }
    println!(
        "Visible triangles: surface={} skirts={}",
        r.drawn_surface_triangles,
        r.drawn_triangles - r.drawn_surface_triangles
    );
    assert_ne!(
        oblique, pixels,
        "pitch must change the real terrain projection"
    );
    let mut capture = b"P6\n256 256\n255\n".to_vec();
    for pixel in oblique.chunks_exact(4) {
        capture.extend_from_slice(&pixel[..3]);
    }
    std::fs::write(dir.join("native-oblique.ppm"), capture).unwrap();
    println!(
        "Live LINZ GPU draw, 129 exact shared edge samples, full 256px imagery, center pick height={:.3}m expected={:.3}m: PASS. Not visual parity or Android execution.",
        hit[2], elevation
    );
}
