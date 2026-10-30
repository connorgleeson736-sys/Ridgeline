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
    if std::env::args().any(|v| v == "--gles") {
        descriptor.backends = wgpu::Backends::GL;
    }
    let instance = wgpu::Instance::new(descriptor);
    let mut r = Renderer::new_with_texture_backend(
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
        if std::env::args().any(|v| v == "--arrays") {
            ridgeline_renderer::TextureBackend::Arrays
        } else {
            ridgeline_renderer::TextureBackend::Auto
        },
    )
    .await
    .expect("adapter/device");
    let mips = r.mipmap_diagnostics().expect("mip readback");
    assert!(
        mips.iter().all(|row| row[1] == 0 && row[2] == 255),
        "every source/mip pixel must retain exact colour and opacity: {mips:?}"
    );
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
    let counts = (r.scene_uploads, r.instance_uploads);
    r.render(
        &output.create_view(&Default::default()),
        center,
        12.,
        0.,
        0.,
        1000.,
    );
    assert_eq!(
        read(&r, &output),
        bytes,
        "unchanged frame must remain pixel identical"
    );
    assert_eq!(
        (r.scene_uploads, r.instance_uploads),
        counts,
        "unchanged GPU buffers must not be resent"
    );
    let point = r.pick_at(128., 128.).expect("native terrain ray hit");
    assert!((point[0] - (center[0] * 360. - 180.)).abs() < 1e-6);
    assert!((point[2] - 1000.).abs() < 1e-6);
    // Metadata comes from source workers in production. A plane must simplify
    // without changing a pixel, while preserving the source buffer and skirts.
    r.set_geometry_profile(parent, ridgeline_core::adaptive::profile(&heights).unwrap());
    r.render(
        &output.create_view(&Default::default()),
        center,
        12.,
        0.,
        0.,
        1000.,
    );
    assert!(
        r.next_adaptive_frame().is_some(),
        "coarsening waits for stable eligibility"
    );
    std::thread::sleep(std::time::Duration::from_millis(360));
    r.render(
        &output.create_view(&Default::default()),
        center,
        12.,
        0.,
        0.,
        1000.,
    );
    assert!(
        r.drawn_surface_triangles < 32768,
        "eligible plane reduces GPU triangle work"
    );
    assert_eq!(
        read(&r, &output),
        bytes,
        "planar simplification must preserve every output pixel"
    );
    println!(
        "Adaptive plane: {} surface triangles, full boundaries, selective skirts, identical pixels",
        r.drawn_surface_triangles
    );
    r.set_routes(vec![ridgeline_renderer::RoutePath {
        points: vec![
            [center[0] - (bounds[2] - bounds[0]) * 0.1, center[1]],
            [center[0] + (bounds[2] - bounds[0]) * 0.1, center[1]],
        ],
        color: [1., 0.2, 0.1, 1.],
        selected: Some(0),
    }]);
    r.render(
        &output.create_view(&Default::default()),
        center,
        12.,
        0.,
        0.,
        1000.,
    );
    assert_eq!(
        r.drawn_surface_triangles, 32768,
        "route-bearing terrain retains full geometry for exact depth/occlusion"
    );
    r.set_routes(Vec::new());
    r.set_geometry_profile(parent, [1e12, 1e12]); // Force full detail for the existing ownership fixtures.
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
    assert_eq!(
        r.skirt_edge_count(),
        8,
        "continuous shared parent/child boundaries omit internal skirts"
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
    assert_eq!(
        r.scene_uploads, counts.0,
        "new tile data does not change camera uniforms"
    );
    assert!(
        r.instance_uploads > counts.1,
        "refined source cover must update instances"
    );
    // Demand can lag the moving camera. A ready fine tile must not suppress
    // its cached parent while neighbouring detail is not in the new plan yet.
    let fine = parent.children()[0];
    r.set_demand(vec![fine], vec![fine]);
    r.render(
        &output.create_view(&Default::default()),
        center,
        12.,
        0.,
        0.,
        1000.,
    );
    let backdrop = read(&r, &output);
    assert_eq!(
        backdrop, bytes,
        "cached parent must keep every sibling pixel covered after fine arrival"
    );
    println!("Cached parent backdrop: fine-only demand preserves every pixel of the complete cached frame");
    r.clear_demand();
    // Independent photographic detail must not duplicate/refine the terrain grid.
    let detailed = TileId {
        z: 12,
        x: 4000,
        y: 2600,
    };
    let d = detailed.bounds();
    let old_geometry = r.geometry_count();
    r.upload_geometry(detailed, &heights).unwrap();
    let stripe: Vec<u8> = (0..256 * 256)
        .flat_map(|i| {
            if i % 256 < 128 {
                [200, 70, 30, 255]
            } else {
                [40, 120, 80, 255]
            }
        })
        .collect();
    let expected_red = pixel(64, 64).to_vec();
    for child in detailed.children() {
        r.upload_image(child, &stripe).unwrap();
    }
    assert_eq!(r.geometry_count(), old_geometry + 1);
    r.render(
        &output.create_view(&Default::default()),
        [(d[0] + d[2]) / 2., (d[1] + d[3]) / 2.],
        12.,
        0.,
        0.,
        1000.,
    );
    let bytes = read(&r, &output);
    let at = |x: usize, y: usize| &bytes[(y * 256 + x) * 4..][..4];
    assert_eq!(at(64, 64), &[36, 113, 74, 255]);
    assert_eq!(at(192, 64), expected_red.as_slice());
    assert_eq!(at(64, 192), &[36, 113, 74, 255]);
    assert_eq!(at(192, 192), expected_red.as_slice());
    assert_eq!(
        r.drawn_surface_triangles,
        128 * 128 * 2,
        "four full-resolution child photos share exactly one native grid"
    );
    assert_eq!(
        r.drawn_triangles - r.drawn_surface_triangles,
        512 * 2,
        "imagery subdivision keeps only the original terrain perimeter skirts"
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
    // An intentionally discontinuous parent/child height fixture exposes a
    // vertical LOD gap. Skirts must close pixels, not merely increase indices.
    let mut seam = Renderer::new_with_texture_backend(
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
        if std::env::args().any(|v| v == "--arrays") {
            ridgeline_renderer::TextureBackend::Arrays
        } else {
            ridgeline_renderer::TextureBackend::Auto
        },
    )
    .await
    .unwrap();
    let output = seam.device.create_texture(&wgpu::TextureDescriptor {
        label: Some("LOD seam readback"),
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
    seam.upload(parent, &heights, &green).unwrap();
    let high = parent.children()[1];
    seam.upload(high, &vec![2000.; 129 * 129], &red).unwrap();
    let pb = parent.bounds();
    let focus = [(pb[0] + pb[2]) / 2., pb[1] + (pb[3] - pb[1]) / 4.];
    seam.logical_zoom = Some(40.); // Negligible depth: a diagnostic control, not an app setting.
    seam.render(
        &output.create_view(&Default::default()),
        focus,
        13.5,
        90.,
        75.,
        1500.,
    );
    let open = read(&seam, &output);
    seam.logical_zoom = Some(13.5);
    seam.render(
        &output.create_view(&Default::default()),
        focus,
        13.5,
        90.,
        75.,
        1500.,
    );
    let closed = read(&seam, &output);
    let sky = |p: &[u8]| p == [189, 203, 208, 255];
    let repaired = open
        .chunks_exact(4)
        .zip(closed.chunks_exact(4))
        .filter(|(a, b)| sky(a) && !sky(b))
        .count();
    println!("Mixed-LOD discontinuity: skirts close {repaired} exposed background pixels");
    assert!(
        repaired > 1000,
        "skirts must visibly close the discontinuous LOD gap"
    );
    assert!(seam.drawn_triangles > seam.drawn_surface_triangles);
    let triangles = seam.drawn_triangles;
    seam.set_index_cache_budget(24); // Deliberately force exact overflow buffers.
    seam.render(
        &output.create_view(&Default::default()),
        focus,
        13.5,
        90.,
        75.,
        1500.,
    );
    assert_eq!(
        read(&seam, &output),
        closed,
        "index-cache pressure must not change a pixel or omit skirts"
    );
    assert_eq!(
        seam.drawn_triangles, triangles,
        "index-cache pressure must not change geometry"
    );
    // Cached children must not dictate a new coarser view. Replay both directions
    // with identical residency: pixel output must depend on demand, not history.
    seam.set_demand(vec![parent], vec![parent]);
    seam.render(
        &output.create_view(&Default::default()),
        focus,
        13.5,
        90.,
        75.,
        1500.,
    );
    let coarse = read(&seam, &output);
    assert_ne!(
        coarse, closed,
        "coarse demand must retire cached fine ownership"
    );
    let desired: Vec<_> = std::iter::once(parent).chain(parent.children()).collect();
    seam.set_demand(desired.clone(), desired);
    seam.render(
        &output.create_view(&Default::default()),
        focus,
        13.5,
        90.,
        75.,
        1500.,
    );
    let fine = read(&seam, &output);
    assert_eq!(
        fine, closed,
        "unready siblings must retain their loaded parent"
    );
    assert_ne!(
        coarse, fine,
        "ready requested fine detail must immediately display"
    );
    seam.set_demand(vec![parent], vec![parent]);
    seam.render(
        &output.create_view(&Default::default()),
        focus,
        13.5,
        90.,
        75.,
        1500.,
    );
    assert_eq!(
        coarse,
        read(&seam, &output),
        "same demand must yield same pixels after reversing LOD"
    );
    let uploads = (
        seam.scene_uploads,
        seam.instance_uploads,
        seam.route_uploads(),
    );
    seam.render(
        &output.create_view(&Default::default()),
        focus,
        13.5,
        90.,
        75.,
        1500.,
    );
    assert_eq!(
        uploads,
        (
            seam.scene_uploads,
            seam.instance_uploads,
            seam.route_uploads()
        ),
        "settled frame must not transfer unchanged state"
    );
    let hb = high.bounds();
    let hc = [(hb[0] + hb[2]) / 2., (hb[1] + hb[3]) / 2.];
    seam.set_demand(vec![high], vec![high]);
    let safety = seam.camera_safety(&ridgeline_renderer::selection::View {
        width: 256,
        height: 256,
        center: hc,
        zoom: 19.,
        bearing: 0.,
        pitch: 75.,
        elevation: 0.,
    });
    assert!(
        safety[0] > 1900.,
        "eye and near-plane clearance must lift a buried alpine camera"
    );
    assert_eq!(safety[1], 2000.);
    assert_eq!(safety[2], high.z as f32);
    assert_eq!(
        seam.terrain_gpu_payload_bytes(),
        266256 + seam.geometry_count() as u64 * 66564,
        "shared grid keeps every exact f32 height without per-tile duplicated vertices"
    );
    seam.set_geometry_retention(48);
    for x in 10..14 {
        seam.upload_geometry(TileId { z: 12, x, y: 2500 }, &heights)
            .unwrap();
    }
    assert!(
        seam.touch_geometry(high),
        "recent requested terrain remains GPU resident across unrelated uploads"
    );
    seam.release_unused();
    assert_eq!(seam.geometry_count(), 2, "keep requested terrain and its nearest parent backdrop");
    assert_eq!(seam.image_count(), 2, "keep requested imagery and its nearest parent backdrop");
    assert!(seam.touch_geometry(parent) && seam.touch_image(parent));
    assert!(
        seam.touch_geometry(high),
        "memory pressure preserves actual demanded terrain"
    );
    // The hard budget holds during a burst of unrelated new uploads.
    r.set_tile_budget(24);
    for x in 0..80 {
        r.upload(TileId { z: 12, x, y: 2500 }, &heights, &green)
            .unwrap();
        assert!(r.resident_count() <= 24);
    }
    println!(
        "PASS GPU: exact grade, fine-child/coarse-parent ownership, no fallback holes, independent image/geometry LOD and exact crop UV, trilinear mipmap average, bounded 24-tile residency. Synthetic geometry; no phone benchmark or real-terrain parity claim."
    );
}
