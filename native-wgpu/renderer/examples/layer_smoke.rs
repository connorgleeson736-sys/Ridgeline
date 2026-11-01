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
    descriptor.backends = if std::env::args().any(|s| s == "--gles") {
        wgpu::Backends::GL
    } else {
        wgpu::Backends::VULKAN
    };
    let instance = wgpu::Instance::new(descriptor);
    let mut r = Renderer::new(
        &instance,
        None,
        Some(wgpu::TextureFormat::Rgba8Unorm),
        256,
        256,
    )
    .await
    .unwrap();
    let id = TileId {
        z: 11,
        x: 1991,
        y: 1300,
    };
    let bounds = id.bounds();
    let center = [(bounds[0] + bounds[2]) / 2., (bounds[1] + bounds[3]) / 2.];
    let span = bounds[2] - bounds[0];
    r.upload(
        id,
        &vec![500.; 129 * 129],
        &[160, 160, 160, 255].repeat(256 * 256),
    )
    .unwrap();
    let output = r.device.create_texture(&wgpu::TextureDescriptor {
        label: Some("route fixture"),
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
    let target = output.create_view(&Default::default());
    r.render(&target, center, 11., 0., 0., 500.);
    let before = read(&r, &output);
    r.set_routes(vec![ridgeline_renderer::RoutePath {
        points: vec![
            [bounds[0] + span * 0.2, center[1]],
            [bounds[0] + span * 0.8, center[1]],
        ],
        color: [1., 0.1, 0.1, 1.],
        selected: Some(0),
    }]);
    r.render(&target, center, 11., 0., 0., 500.);
    let after = read(&r, &output);
    let changed = before
        .chunks_exact(4)
        .zip(after.chunks_exact(4))
        .filter(|(a, b)| a != b)
        .count();
    assert!(changed > 100, "native route is visible");
    let pixel = &after[(128 * 256 + 128) * 4..][..4];
    assert!(pixel[0] > pixel[1] + 40, "route crosses the actual center");
    let uploads = r.route_uploads();
    r.render(&target, center, 11., 0., 0., 500.);
    assert_eq!(uploads, r.route_uploads());
    r.render(
        &target,
        [center[0] + span * 0.01, center[1]],
        11.,
        0.,
        0.,
        500.,
    );
    assert_eq!(
        uploads,
        r.route_uploads(),
        "camera movement must not retessellate routes"
    );
    println!(
        "PASS native route GPU: {changed} affected pixels, visible draped line/waypoints, unchanged geometry uploads on idle/pan. Synthetic height fixture; not phone performance."
    );
    r.set_routes(vec![]);
    r.set_cartography(vec![ridgeline_renderer::CartographicPath {
        points: vec![
            [bounds[0] + span * 0.2, center[1]],
            [bounds[0] + span * 0.8, center[1]],
        ],
        color: [0., 1., 0., 1.],
        width: 3.,
        dash: [0., 0.],
    }]);
    r.render(&target, center, 11., 0., 0., 500.);
    let cartography = read(&r, &output);
    let pixel = &cartography[(128 * 256 + 128) * 4..][..4];
    assert!(
        pixel[1] > pixel[0] + 40,
        "real cartographic batch crosses terrain"
    );
    let instanced_bytes = r.cartographic_upload_bytes();
    r.expanded_overlay_validation(true);
    r.render(&target, center, 11., 0., 0., 500.);
    let expanded = read(&r, &output);
    let expanded_bytes = r.cartographic_upload_bytes();
    assert!(
        cartography == expanded,
        "instanced line quads retain every frame pixel"
    );
    assert_eq!(expanded_bytes, instanced_bytes * 6);
    println!(
        "Line batch bytes: expanded {expanded_bytes}, instanced {instanced_bytes}; identical pixels"
    );
    r.expanded_overlay_validation(false);

    r.set_label_atlas(
        16,
        8,
        &[0, 0, 255, 255].repeat(16 * 8),
        vec![ridgeline_renderer::LabelSprite {
            point: center,
            uv: [0., 0., 1., 1.],
            size: [30., 12.],
        }],
    );
    r.select_labels(vec![(0, [10., -6.])]);
    r.render(&target, center, 11., 0., 0., 500.);
    let labels = read(&r, &output);
    let pixel = &labels[(128 * 256 + 145) * 4..][..4];
    assert!(
        pixel[2] > pixel[1] + 40,
        "native atlas quad is visible with correct pixel offset"
    );
    let positions = r.project_points(&[center]);
    assert!((positions[0][0] - 128.).abs() < 1.);
    assert!((positions[0][1] - 128.).abs() < 1.);
    r.set_cartography(vec![]);
    r.select_labels(vec![]);
    let style = ridgeline_renderer::MapStyle {
        basemap: 2,
        natural: true,
        hillshade: false,
        relief: 1.,
        sky: 0xff17242f,
        three_d: true,
    };
    r.set_map_style(style);
    r.render(&target, center, 11., 0., 0., 500.);
    let topo = read(&r, &output);
    assert_eq!(
        &topo[(128 * 256 + 128) * 4..][..4],
        &[160, 160, 160, 255],
        "Topo bypasses aerial grade and uses original pixels"
    );
    r.set_map_style(ridgeline_renderer::MapStyle {
        relief: 1.5,
        ..style
    });
    r.render(&target, center, 11., 0., 0., 750.);
    let hit = r.pick_at(128., 128.).unwrap();
    assert!(
        (hit[2] - 750.).abs() < 0.01,
        "rendered picking follows relief"
    );
    let floor = r.camera_safety(&ridgeline_renderer::selection::View {
        width: 256,
        height: 256,
        center,
        zoom: 11.,
        bearing: 0.,
        pitch: 0.,
        elevation: 750.,
    });
    assert!((floor[1] - 750.).abs() < 0.01);
    r.set_map_style(ridgeline_renderer::MapStyle {
        three_d: false,
        ..style
    });
    r.render(&target, center, 11., 0., 0., 0.);
    let flat = r.pick_at(128., 128.).unwrap();
    assert!(
        flat[2].abs() < 0.001,
        "explicit 2D plane ignores resident real DEM heights"
    );
    r.inspect_point(Some(center));
    r.location_point(Some([center[0] + span * 0.1, center[1]]));
    r.render(&target, center, 11., 0., 0., 0.);
    let marker = read(&r, &output);
    assert!(
        marker[(128 * 256 + 128) * 4 + 1] > marker[(128 * 256 + 128) * 4],
        "linked profile cursor is visible"
    );
    r.inspect_point(None);
    r.location_point(None);
    r.clear_images();
    let checker: Vec<u8> = (0..256 * 256)
        .flat_map(|i| {
            [
                (i % 256) as u8,
                (i / 256) as u8,
                ((i % 256) ^ (i / 256)) as u8,
                255,
            ]
        })
        .collect();
    r.upload_image(id, &checker).unwrap();
    r.set_map_style(style);
    r.render(&target, center, 11., 0., 0., 500.);
    let planar_fine = read(&r, &output);
    r.set_map_style(ridgeline_renderer::MapStyle {
        three_d: false,
        ..style
    });
    r.render(&target, center, 11., 0., 0., 0.);
    assert!(
        planar_fine == read(&r, &output),
        "2D plane subdivision retains all original raster pixels"
    );
    r.clear_geometry();
    let hills: Vec<f32> = (0..129 * 129)
        .map(|i| 500. + (i % 129) as f32 * 10.)
        .collect();
    r.upload_geometry(id, &hills).unwrap();
    r.set_map_style(ridgeline_renderer::MapStyle {
        three_d: false,
        basemap: 0,
        natural: false,
        hillshade: false,
        ..style
    });
    r.render(&target, center, 11., 0., 0., 0.);
    let unshaded = read(&r, &output);
    r.set_map_style(ridgeline_renderer::MapStyle {
        three_d: false,
        basemap: 0,
        natural: false,
        hillshade: true,
        ..style
    });
    r.render(&target, center, 11., 0., 0., 0.);
    let shaded = read(&r, &output);
    assert!(
        unshaded != shaded,
        "Igor shade changes actual pixels using real resident heights while geometry stays 2D"
    );
    r.render(&target, [0.6, 0.6], 11., 0., 0., 0.);
    let sky = read(&r, &output);
    assert_eq!(&sky[..4], &[23, 36, 47, 255]);
    println!(
        "PASS native layers: cartographic line, depth-tested native-font atlas, projection, ungraded nearest Topo, relief picking/clearance and sky on actual GPU. Synthetic source fixture, not device/visual acceptance."
    );
}
