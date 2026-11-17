//! Letters of a name along a curve are turned about their centres (the Te Araroa
//! name), on actual GPU execution: a wide sprite placed unturned covers a wide
//! box; turned a quarter it covers a tall one about the same centre.
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
            layout: wgpu::TexelCopyBufferLayout { offset: 0, bytes_per_row: Some(1024), rows_per_image: Some(256) },
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
/// The box (left, top, right, bottom) of magenta pixels for a 40x10 sprite turned by [angle].
async fn drawn(instance: &wgpu::Instance, angle: f32) -> Option<[usize; 4]> {
    let mut r = Renderer::new(instance, None, Some(wgpu::TextureFormat::Rgba8Unorm), 256, 256).await.unwrap();
    let id = TileId { z: 11, x: 1991, y: 1300 };
    let b = id.bounds();
    let center = [(b[0] + b[2]) / 2., (b[1] + b[3]) / 2.];
    r.upload(id, &vec![100f32; 129 * 129], &[160u8, 160, 160, 255].repeat(256 * 256)).unwrap();
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
    r.set_label_atlas(4, 4, &[255, 0, 255, 255].repeat(16), vec![LabelSprite { point: center, uv: [0., 0., 1., 1.], size: [40., 10.] }]);
    // Centred on its anchor: offset is minus half its size.
    r.select_labels(vec![(0, [-20., -5.], angle)]);
    for _ in 0..3 {
        r.render(&target, center, 11., 0., 0., 100.);
    }
    std::thread::sleep(std::time::Duration::from_millis(260));
    r.render(&target, center, 11., 0., 0., 100.);
    let px = read(&r, &output);
    let mut found: Option<[usize; 4]> = None;
    for y in 0..256 {
        for x in 0..256 {
            let p = &px[(y * 256 + x) * 4..][..4];
            if p[0] > 200 && p[1] < 80 && p[2] > 200 {
                found = Some(match found {
                    None => [x, y, x, y],
                    Some([l, t, rr, bb]) => [l.min(x), t.min(y), rr.max(x), bb.max(y)],
                });
            }
        }
    }
    found
}
async fn run() {
    let instance = wgpu::Instance::default();
    let flat = drawn(&instance, 0.).await.expect("the unturned sprite is drawn");
    let turned = drawn(&instance, std::f32::consts::FRAC_PI_2).await.expect("the turned sprite is drawn");
    let size = |b: [usize; 4]| (b[2] - b[0] + 1, b[3] - b[1] + 1);
    let centre = |b: [usize; 4]| ((b[0] + b[2]) as f32 / 2., (b[1] + b[3]) as f32 / 2.);
    let (fw, fh) = size(flat);
    let (tw, th) = size(turned);
    assert!(fw > fh * 2, "unturned: wide ({fw}x{fh})");
    assert!(th > tw * 2, "turned a quarter: tall ({tw}x{th})");
    assert!((fw as i32 - th as i32).abs() <= 2 && (fh as i32 - tw as i32).abs() <= 2, "same size turned ({fw}x{fh} vs {tw}x{th})");
    let (a, c) = (centre(flat), centre(turned));
    assert!((a.0 - c.0).abs() <= 1.5 && (a.1 - c.1).abs() <= 1.5, "turned about its centre ({a:?} vs {c:?})");
    println!("PASS label turn: unturned {fw}x{fh} px, turned a quarter {tw}x{th} px about the same centre {a:?}");
}
