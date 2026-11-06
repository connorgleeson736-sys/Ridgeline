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
    for _ in 0..3 {
        r.render(&target, center, 11., 0., 60., 500.);
    }
    std::thread::sleep(std::time::Duration::from_millis(260));
    r.render(&target, center, 11., 0., 60., 500.);
    let pixels = read(&r, &output);
    pixels.chunks_exact(4).filter(|p| p[0] > 200 && p[1] < 80 && p[2] > 200).count()
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
    // On a slope rising towards the camera the label is not cut (whole or hidden, never partial).
    let slope: Vec<f32> = (0..129 * 129).map(|i| 500. + ((i / 129) as f32) * 4.).collect();
    let on_slope = label_pixels(&instance, &slope, 0.30).await;
    assert!(on_slope == 0 || on_slope >= 200, "never a partial label ({on_slope} px)");
    println!(
        "PASS GPU label occlusion: whole on flat ground ({north}/{south} px), hidden behind ridges, near-summit and thin ridges, slope {on_slope} px ({})",
        if gles { "GLES" } else { "Vulkan" }
    );
}
