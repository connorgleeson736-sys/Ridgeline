//! The raised centre of perspective (a sheet over the map: set_view_shift), on
//! actual GPU execution: with the centre raised by half the height, the picture is
//! the unshifted one moved up by a quarter of the height, and the camera's centre
//! projects to clip y = +0.5.
use ridgeline_renderer::{Renderer, TileId, camera_matrix, set_view_shift};
fn main() {
    pollster::block_on(run());
}
async fn frame(r: &mut Renderer, output: &wgpu::Texture, center: [f64; 2]) -> Vec<u8> {
    let target = output.create_view(&Default::default());
    for _ in 0..4 {
        r.render(&target, center, 11., 20., 50., 100.);
        std::thread::sleep(std::time::Duration::from_millis(60));
    }
    let buffer = r.device.create_buffer(&wgpu::BufferDescriptor { label: None, size: 256 * 256 * 4, usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ, mapped_at_creation: false });
    let mut encoder = r.device.create_command_encoder(&Default::default());
    encoder.copy_texture_to_buffer(output.as_image_copy(), wgpu::TexelCopyBufferInfo { buffer: &buffer, layout: wgpu::TexelCopyBufferLayout { offset: 0, bytes_per_row: Some(1024), rows_per_image: Some(256) } }, wgpu::Extent3d { width: 256, height: 256, depth_or_array_layers: 1 });
    r.queue.submit([encoder.finish()]);
    buffer.slice(..).map_async(wgpu::MapMode::Read, |_| {});
    r.device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
    let data = buffer.slice(..).get_mapped_range().unwrap().to_vec();
    data
}
async fn run() {
    let (m, _) = { set_view_shift(0.5); let v = camera_matrix(256, 256, [0.5, 0.5], 11., 20., 50., 100.); set_view_shift(0.); v };
    let c = m * glam::Vec4::new(0., 0., 0., 1.);
    assert!((c.y / c.w - 0.5).abs() < 1e-4, "centre at clip y {}", c.y / c.w);
    let instance = wgpu::Instance::default();
    let mut r = Renderer::new(&instance, None, Some(wgpu::TextureFormat::Rgba8Unorm), 256, 256).await.unwrap();
    let id = TileId { z: 11, x: 1991, y: 1300 };
    let b = id.bounds();
    let center = [(b[0] + b[2]) / 2., (b[1] + b[3]) / 2.];
    // Stripes across the tile (rows of 8 texels), flat ground at 100 m.
    let image: Vec<u8> = (0..256 * 256).flat_map(|i| if (i / 256 / 8) % 2 == 0 { [30u8, 30, 30, 255] } else { [230, 230, 220, 255] }).collect();
    r.upload(id, &vec![100f32; 129 * 129], &image).unwrap();
    let output = r.device.create_texture(&wgpu::TextureDescriptor { label: None, size: wgpu::Extent3d { width: 256, height: 256, depth_or_array_layers: 1 }, mip_level_count: 1, sample_count: 1, dimension: wgpu::TextureDimension::D2, format: wgpu::TextureFormat::Rgba8Unorm, usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC, view_formats: &[] });
    let plain = frame(&mut r, &output, center).await;
    set_view_shift(0.5);
    let shifted = frame(&mut r, &output, center).await;
    set_view_shift(0.);
    // Shifted row y shows what the plain picture has at row y + 64.
    let (mut same, mut total) = (0, 0);
    for y in 0..192 {
        for x in 0..256 {
            let a = &shifted[(y * 256 + x) * 4..][..3];
            let p = &plain[((y + 64) * 256 + x) * 4..][..3];
            total += 1;
            if a.iter().zip(p).all(|(u, v)| (*u as i32 - *v as i32).abs() <= 8) {
                same += 1;
            }
        }
    }
    let share = same as f64 / total as f64;
    assert!(share > 0.98, "shifted picture matches the plain one moved up a quarter: {share:.4}");
    println!("PASS view shift: camera centre at clip y 0.5; picture moved up 64 of 256 px ({:.2}% of pixels match)", share * 100.);
}
