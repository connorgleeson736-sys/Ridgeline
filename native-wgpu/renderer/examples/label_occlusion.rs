//! Labels hidden whole by terrain in front of their anchor, decided on the GPU
//! from the terrain depth (as MapLibre does), on actual GPU execution with
//! synthetic terrain. A label is never cut per pixel by the slope it sits on.
use ridgeline_renderer::{LabelSprite, Renderer, TileId};
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
/// Magenta label pixels around the anchor, after the fade-in has finished.
async fn label_pixels(instance: &wgpu::Instance, heights: &[f32], anchor_at: f64) -> usize {
    label_pixels_moving(instance, heights, anchor_at, false).await
}
/// With [moving], every frame pans and turns the camera, so occlusion must be
/// read through the previous frame's camera to stay correct.
async fn label_pixels_moving(instance: &wgpu::Instance, heights: &[f32], anchor_at: f64, moving: bool) -> usize {
    label_pixels_scaled(instance, heights, anchor_at, moving, false, false).await
}
/// With [scaled], the camera moves at the reduced moving resolution and then
/// stops: the still frame switches back to full resolution, and the renderer
/// must draw the labels again without any further input.
async fn label_pixels_scaled(instance: &wgpu::Instance, heights: &[f32], anchor_at: f64, moving: bool, scaled: bool, slow_still: bool) -> usize {
    let mut r = Renderer::new(instance, None, Some(wgpu::TextureFormat::Rgba8Unorm), 256, 256)
        .await
        .unwrap();
    let id = TileId { z: 11, x: 1991, y: 1300 };
    let b = id.bounds();
    let center = [(b[0] + b[2]) / 2., (b[1] + b[3]) / 2.];
    r.upload(id, heights, &[160u8, 160, 160, 255].repeat(256 * 256)).unwrap();
    let output = r.device.create_texture(&wgpu::TextureDescriptor {
        label: Some("labels"),
        size: wgpu::Extent3d { width: 256, height: 256, depth_or_array_layers: 1 },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
        view_formats: &[],
    });
    let target = output.create_view(&Default::default());
    let point = [center[0], b[1] + (b[3] - b[1]) * anchor_at];
    r.set_label_atlas(4, 4, &[255, 0, 255, 255].repeat(16), vec![LabelSprite { point, uv: [0., 0., 1., 1.], size: [24., 12.] }]);
    r.select_labels(vec![(0, [-12., -6.])]);
    if slow_still {
        // A still map on a slow phone (about 4 fps, as while tiles load): the only frames drawn are
        // the first and those the renderer asks for. The labels must end shown.
        r.render(&target, center, 11., 0., 60., 500.);
        let mut frames = 0;
        while r.next_adaptive_frame().is_some() && frames < 50 {
            std::thread::sleep(std::time::Duration::from_millis(250));
            r.render(&target, center, 11., 0., 60., 500.);
            frames += 1;
        }
    } else {
        for _ in 0..3 {
            r.render(&target, center, 11., 0., 60., 500.);
        }
        std::thread::sleep(std::time::Duration::from_millis(260));
        r.render(&target, center, 11., 0., 60., 500.);
    }
    if moving {
        for step in 1..=8 {
            let c = [center[0] + (b[2] - b[0]) * 0.006 * step as f64, center[1] - (b[3] - b[1]) * 0.004 * step as f64];
            r.render(&target, c, 11. + 0.01 * step as f64, 1.5 * step as f32, 60., 500.);
        }
    }
    if scaled {
        r.set_render_scales(0.75, 1.);
        for step in 1..=4 {
            let c = [center[0] + (b[2] - b[0]) * 0.004 * step as f64, center[1]];
            r.render(&target, c, 11., 0., 60., 500.);
        }
        let still = [center[0] + (b[2] - b[0]) * 0.016, center[1]];
        r.set_render_scales(1., 0.75);
        r.render(&target, still, 11., 0., 60., 500.);
        // Only the frames the renderer asks for (the app draws nothing else when still).
        let mut extra = 0;
        while r.next_adaptive_frame().is_some() && extra < 10 {
            r.render(&target, still, 11., 0., 60., 500.);
            extra += 1;
        }
    }
    let pixels = read(&r, &output);
    pixels.chunks_exact(4).filter(|p| p[0] > 200 && p[1] < 80 && p[2] > 200).count()
}
/// Visibility of every candidate (placed or not) read back for placement.
async fn probe(instance: &wgpu::Instance, heights: &[f32]) -> Vec<u8> {
    let mut r = Renderer::new(instance, None, Some(wgpu::TextureFormat::Rgba8Unorm), 256, 256)
        .await
        .unwrap();
    let id = TileId { z: 11, x: 1991, y: 1300 };
    let b = id.bounds();
    let center = [(b[0] + b[2]) / 2., (b[1] + b[3]) / 2.];
    r.upload(id, heights, &[160u8, 160, 160, 255].repeat(256 * 256)).unwrap();
    let output = r.device.create_texture(&wgpu::TextureDescriptor {
        label: Some("probe"),
        size: wgpu::Extent3d { width: 256, height: 256, depth_or_array_layers: 1 },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
        view_formats: &[],
    });
    let target = output.create_view(&Default::default());
    let at = |f: f64| [center[0], b[1] + (b[3] - b[1]) * f];
    let sprite = |point| LabelSprite { point, uv: [0., 0., 1., 1.], size: [24., 12.] };
    // North (behind a ridge for this camera) and south (in front); neither placed.
    r.set_label_atlas(4, 4, &[255, 0, 255, 255].repeat(16), vec![sprite(at(0.30)), sprite(at(0.58))]);
    r.select_labels(vec![]);
    for _ in 0..40 {
        r.render(&target, center, 11., 0., 60., 500.);
        if let Some((_, v)) = r.label_visibility() {
            let v = v.to_vec();
            // A still map probes once: the renderer stops asking for frames.
            let mut extra = 0;
            while r.next_adaptive_frame().is_some() {
                assert!(extra < 30, "a still map stops drawing after its visibility probe");
                std::thread::sleep(std::time::Duration::from_millis(20));
                r.device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
                r.render(&target, center, 11., 0., 60., 500.);
                extra += 1;
            }
            return v;
        }
        r.device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(80));
    }
    panic!("label visibility read back");
}
fn ridge(rows: std::ops::RangeInclusive<usize>) -> Vec<f32> {
    let mut h = vec![500f32; 129 * 129];
    for row in rows {
        for column in 0..129 {
            h[row * 129 + column] = 3000.;
        }
    }
    h
}
async fn run() {
    let gles = std::env::args().any(|s| s == "--gles");
    let mut descriptor = wgpu::InstanceDescriptor::new_without_display_handle();
    descriptor.backends = if gles { wgpu::Backends::GL } else { wgpu::Backends::VULKAN };
    let instance = wgpu::Instance::new(descriptor);
    // The camera looks north from the south. North anchor at 30%, south at 58%.
    let flat = vec![500f32; 129 * 129];
    let north = label_pixels(&instance, &flat, 0.30).await;
    let south = label_pixels(&instance, &flat, 0.58).await;
    assert!(north >= 200 && south >= 200, "labels drawn whole on flat ground ({north}, {south} px of 288)");
    // A 3,000 m ridge across the middle hides the northern label only.
    let behind = label_pixels(&instance, &ridge(56..=60), 0.30).await;
    let front = label_pixels(&instance, &ridge(56..=60), 0.58).await;
    assert_eq!(behind, 0, "a label behind the ridge is hidden whole");
    assert!(front >= 200, "a label in front of the ridge stays whole ({front} px)");
    // A subsidiary ridge just in front of the anchor and a single-row ridge.
    assert_eq!(label_pixels(&instance, &ridge(39..=40), 0.30).await, 0, "near-summit ridge hides the label");
    assert_eq!(label_pixels(&instance, &ridge(50..=50), 0.30).await, 0, "single-row ridge hides the label");
    // While the camera moves every frame, the same decisions hold.
    let moving_flat = label_pixels_moving(&instance, &flat, 0.30, true).await;
    let moving_behind = label_pixels_moving(&instance, &ridge(56..=60), 0.30, true).await;
    assert!(moving_flat >= 200, "moving: label whole on flat ground ({moving_flat} px)");
    assert_eq!(moving_behind, 0, "moving: label behind the ridge stays hidden");
    // Visibility of unplaced candidates is read back for collision placement.
    assert_eq!(probe(&instance, &flat).await, vec![255, 255], "both candidates visible on flat ground");
    assert_eq!(probe(&instance, &ridge(56..=60)).await, vec![0, 255], "the candidate behind the ridge reads hidden");
    // The first label on a still map, on a slow phone: shown without input.
    let lone = label_pixels_scaled(&instance, &flat, 0.30, false, false, true).await;
    assert!(lone >= 200, "a lone label appearing on a still map is shown ({lone} px)");
    // Moving at reduced resolution, then stopping: labels come back on their own.
    let after_scale = label_pixels_scaled(&instance, &flat, 0.30, false, true, false).await;
    let after_scale_behind = label_pixels_scaled(&instance, &ridge(56..=60), 0.30, false, true, false).await;
    assert!(after_scale >= 200, "labels return after a moving-resolution gesture ({after_scale} px)");
    assert_eq!(after_scale_behind, 0, "and stay hidden behind ridges");
    // On a slope rising towards the camera the label is not cut (whole or hidden, never partial).
    let slope: Vec<f32> = (0..129 * 129).map(|i| 500. + ((i / 129) as f32) * 4.).collect();
    let on_slope = label_pixels(&instance, &slope, 0.30).await;
    assert!(on_slope == 0 || on_slope >= 200, "never a partial label ({on_slope} px)");
    println!(
        "PASS GPU label occlusion: whole on flat ground ({north}/{south} px), hidden behind ridges, near-summit and thin ridges, slope {on_slope} px; same while moving, after reduced-resolution movement and for a lone label on a still slow frame loop; candidate visibility read back ({})",
        if gles { "GLES" } else { "Vulkan" }
    );
}
