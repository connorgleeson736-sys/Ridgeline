//! Several provisional terrain interiors at once, on actual GPU execution
//! (synthetic terrain). Each keeps its measured parent rim; promotion to the
//! complete meshes is pixel-identical to complete-only rendering.
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
        wgpu::Extent3d { width: 256, height: 256, depth_or_array_layers: 1 },
    );
    r.queue.submit([encoder.finish()]);
    let (tx, rx) = std::sync::mpsc::channel();
    buffer.slice(..).map_async(wgpu::MapMode::Read, move |result| {
        tx.send(result).unwrap();
    });
    r.device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
    rx.recv().unwrap().unwrap();
    let result = buffer.slice(..).get_mapped_range().unwrap().to_vec();
    buffer.unmap();
    result
}
fn rect(child: TileId) -> [usize; 4] {
    [
        if child.x % 2 == 0 { 1 } else { 0 },
        if child.y % 2 == 0 { 1 } else { 0 },
        if child.x % 2 == 0 { 128 } else { 127 },
        if child.y % 2 == 0 { 128 } else { 127 },
    ]
}
fn masked(child: TileId, height: f32) -> Vec<f32> {
    let r = rect(child);
    let mut heights = vec![0.; 129 * 129];
    for y in r[1]..=r[3] {
        for x in r[0]..=r[2] {
            heights[y * 129 + x] = height;
        }
    }
    heights
}
async fn run() {
    let gles = std::env::args().any(|s| s == "--gles");
    let mut descriptor = wgpu::InstanceDescriptor::new_without_display_handle();
    descriptor.backends = if gles { wgpu::Backends::GL } else { wgpu::Backends::VULKAN };
    let instance = wgpu::Instance::new(descriptor);
    let mut r = Renderer::new(&instance, None, Some(wgpu::TextureFormat::Rgba8Unorm), 256, 256)
        .await
        .unwrap();
    let output = r.device.create_texture(&wgpu::TextureDescriptor {
        label: Some("progressive multi"),
        size: wgpu::Extent3d { width: 256, height: 256, depth_or_array_layers: 1 },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
        view_formats: &[],
    });
    let target = output.create_view(&Default::default());
    r.set_map_style(MapStyle {
        basemap: 0,
        natural: false,
        hillshade: false,
        relief: 1.,
        sky: 0xff17242f,
        three_d: true,
    });
    let id = TileId { z: 11, x: 1991, y: 1300 };
    let children = id.children();
    let b = id.bounds();
    let center = [(b[0] + b[2]) / 2., (b[1] + b[3]) / 2.];
    let photo = [150u8, 160, 170, 255].repeat(256 * 256);
    // Children rise 200-500 m above the parent (inside the skirt) so ownership is visible.
    let heights = [1200f32, 1300., 1400., 1500.];
    let mut demand = vec![id];
    demand.extend(children);
    let view = |r: &mut Renderer| r.render(&target, center, 11., -24., 35., 1400.);

    // Reference: complete parent and complete children only.
    r.upload(id, &vec![1000.; 129 * 129], &photo).unwrap();
    for (child, h) in children.iter().zip(heights) {
        r.upload_geometry(*child, &vec![h; 129 * 129]).unwrap();
    }
    r.set_demand(demand.clone(), vec![id]);
    view(&mut r);
    let expected = read(&r, &output);

    // Progressive: complete parent, then all four children staged at once.
    r.clear_geometry();
    r.upload_geometry(id, &vec![1000.; 129 * 129]).unwrap();
    view(&mut r);
    for (token, (child, h)) in children.iter().zip(heights).enumerate() {
        r.upload_interior(*child, 100 + token as u64, masked(*child, h), rect(*child))
            .unwrap_or_else(|e| panic!("interior {token} staged alongside the others: {e:?}"));
    }
    view(&mut r);
    // An overlapping stage is refused: interiors stay disjoint.
    let grandchild = children[0].children()[3];
    assert!(
        r.upload_interior(grandchild, 200, masked(grandchild, 1700.), rect(grandchild)).is_err(),
        "an interior inside another staged interior is refused"
    );
    for (child, h) in children.iter().zip(heights) {
        let cb = child.bounds();
        let cs = cb[2] - cb[0];
        let cc = [(cb[0] + cb[2]) / 2., (cb[1] + cb[3]) / 2.];
        assert!(!r.has_geometry(*child), "partial readiness never acknowledges a full patch");
        assert_eq!(r.terrain_height(cc), Some(h as f64), "each interior owns its measured centre");
        let rimx = if child.x % 2 == 0 { 0.5 / 128. } else { 127.5 / 128. };
        assert_eq!(
            r.terrain_height([cb[0] + rimx * cs, cc[1]]),
            Some(1000.),
            "each missing rim retains the measured parent"
        );
    }
    println!("Progressive terrain calls with 4 interiors: {}", r.last_frame_draw_calls);
    assert!(r.last_frame_draw_calls <= 4 * 8, "rectangular clips per interior");

    // Promote one at a time; late clears of promoted patches are harmless.
    for (token, (child, h)) in children.iter().zip(heights).enumerate() {
        r.upload_geometry(*child, &vec![h; 129 * 129]).unwrap();
        view(&mut r);
        assert!(r.has_geometry(*child));
        r.clear_interior(*child, 100 + token as u64);
        assert!(r.has_geometry(*child), "late cancellation cannot delete a promoted patch");
    }
    view(&mut r);
    assert!(
        expected == read(&r, &output),
        "after promotion the frame is pixel-identical to complete-only rendering"
    );
    println!(
        "PASS multi-interior progressive terrain: 4 concurrent owners, measured rims, overlap refused, pixel-identical promotion ({})",
        if gles { "GLES" } else { "Vulkan" }
    );
}
