//! Triangle load of the app's default camera (Aoraki, zoom 11.9, pitch 58) on real
//! LINZ elevation, for the 2026-11-17 audit's N3/N4. Two steps:
//!   cargo run --example triangle_audit -- plan <width-dp> <height-dp>
//!     prints the terrain tiles the app plans (as TileLoader.sourcePlan: target
//!     431 px, budget 48, maximum zoom 22);
//!   cargo run --example triangle_audit -- render <dir> <width-px> <height-px>
//!     uploads <dir>/<z>_<x>_<y>.f32 (129x129 heights, from tools/triangle-audit.py)
//!     with plain imagery and reports triangles drawn and draw calls.
use ridgeline_renderer::{Renderer, TileId, selection};

const LON: f64 = 170.14219572;
const LAT: f64 = -43.5955671;
const ZOOM: f64 = 11.9;
const BEARING: f32 = -24.;
const PITCH: f32 = 58.;
const ELEVATION: f32 = 3724.;

fn mercator(lon: f64, lat: f64) -> [f64; 2] {
    let s = lat.to_radians().sin();
    [(lon + 180.) / 360., 0.5 - ((1. + s) / (1. - s)).ln() / (4. * std::f64::consts::PI)]
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    match args.get(1).map(String::as_str) {
        Some("plan") => {
            let (w, h): (u32, u32) = (args[2].parse().unwrap(), args[3].parse().unwrap());
            let view = selection::View { width: w, height: h, center: mercator(LON, LAT), zoom: ZOOM, bearing: BEARING, pitch: PITCH, elevation: ELEVATION };
            for id in selection::plan_with_surface_focus(&view, 48, 431., 22, 1.0, None) {
                println!("{} {} {}", id.z, id.x, id.y);
            }
        }
        Some("render") => pollster::block_on(render(&args[2], args[3].parse().unwrap(), args[4].parse().unwrap())),
        _ => eprintln!("usage: triangle_audit plan <w-dp> <h-dp> | render <dir> <w-px> <h-px>"),
    }
}

async fn render(dir: &str, width: u32, height: u32) {
    let instance = wgpu::Instance::default();
    let mut r = Renderer::new(&instance, None, Some(wgpu::TextureFormat::Rgba8Unorm), width, height).await.unwrap();
    r.set_tile_budget(192);
    // Plain imagery, or a fine checker (TRIANGLE_AUDIT_CHECKER) so that moved
    // terrain shows as moved edges when comparing renderings.
    let grey: Vec<u8> = if std::env::var_os("TRIANGLE_AUDIT_CHECKER").is_some() {
        (0..256 * 256).flat_map(|i| if (i % 256 / 8 + i / 256 / 8) % 2 == 0 { [40u8, 40, 40, 255] } else { [220, 220, 210, 255] }).collect()
    } else {
        [150u8, 150, 140, 255].repeat(256 * 256)
    };
    let mut tiles = 0;
    for entry in std::fs::read_dir(dir).unwrap() {
        let path = entry.unwrap().path();
        let name = path.file_stem().unwrap().to_string_lossy().to_string();
        if path.extension().is_none_or(|e| e != "f32") {
            continue;
        }
        let p: Vec<u32> = name.split('_').map(|v| v.parse().unwrap()).collect();
        let id = TileId { z: p[0] as u8, x: p[1], y: p[2] };
        let bytes = std::fs::read(&path).unwrap();
        let heights: Vec<f32> = bytes.chunks_exact(4).map(|b| f32::from_le_bytes(b.try_into().unwrap())).collect();
        r.upload(id, &heights, &grey).unwrap();
        if let Some(errors) = ridgeline_core::adaptive::profile(&heights) {
            r.set_geometry_profile(id, errors);
        }
        tiles += 1;
    }
    let output = r.device.create_texture(&wgpu::TextureDescriptor {
        label: Some("audit"),
        size: wgpu::Extent3d { width, height, depth_or_array_layers: 1 },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
        view_formats: &[],
    });
    let target = output.create_view(&Default::default());
    let center = mercator(LON, LAT);
    // Settle the adaptive levels (they step over 350 ms) and covers.
    for _ in 0..12 {
        r.render(&target, center, ZOOM, BEARING, PITCH, ELEVATION);
        std::thread::sleep(std::time::Duration::from_millis(80));
    }
    r.render(&target, center, ZOOM, BEARING, PITCH, ELEVATION);
    println!(
        "{width}x{height}: {tiles} terrain tiles, triangles {} (surface {}, skirts {}), draw calls {}",
        r.drawn_triangles, r.drawn_surface_triangles, r.drawn_triangles - r.drawn_surface_triangles, r.last_frame_draw_calls
    );
    // Optionally the frame as raw RGBA, to compare renderings.
    if let Ok(path) = std::env::var("TRIANGLE_AUDIT_RGBA") {
        let row = (width * 4).div_ceil(256) * 256;
        let buffer = r.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("readback"),
            size: (row * height) as u64,
            usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
            mapped_at_creation: false,
        });
        let mut encoder = r.device.create_command_encoder(&Default::default());
        encoder.copy_texture_to_buffer(
            output.as_image_copy(),
            wgpu::TexelCopyBufferInfo { buffer: &buffer, layout: wgpu::TexelCopyBufferLayout { offset: 0, bytes_per_row: Some(row), rows_per_image: Some(height) } },
            wgpu::Extent3d { width, height, depth_or_array_layers: 1 },
        );
        r.queue.submit([encoder.finish()]);
        buffer.slice(..).map_async(wgpu::MapMode::Read, |_| {});
        r.device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
        let data = buffer.slice(..).get_mapped_range().unwrap();
        let mut out = Vec::with_capacity((width * height * 4) as usize);
        for y in 0..height {
            out.extend_from_slice(&data[(y * row) as usize..][..(width * 4) as usize]);
        }
        std::fs::write(path, out).unwrap();
    }
}
