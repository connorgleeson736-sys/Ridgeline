//! The default clear sky on the GPU: a pitched view with no terrain shows the
//! gradient from pale haze at the horizon to deep blue at the top. Writes a PPM.
use ridgeline_renderer::Renderer;
fn main() {
    pollster::block_on(run());
}
async fn run() {
    let (w, h) = (320u32, 200u32);
    let mut descriptor = wgpu::InstanceDescriptor::new_without_display_handle();
    if std::env::args().any(|v| v == "--gles") {
        descriptor.backends = wgpu::Backends::GL;
    }
    let instance = wgpu::Instance::new(descriptor);
    let mut r = Renderer::new_with_texture_backend(&instance, None, Some(wgpu::TextureFormat::Rgba8Unorm), w, h,
        ridgeline_renderer::MipmapMode::Auto, ridgeline_renderer::TextureBackend::Auto).await.expect("adapter/device");
    let output = r.device.create_texture(&wgpu::TextureDescriptor {
        label: Some("sky"), size: wgpu::Extent3d { width: w, height: h, depth_or_array_layers: 1 },
        mip_level_count: 1, sample_count: 1, dimension: wgpu::TextureDimension::D2, format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC, view_formats: &[],
    });
    let view = output.create_view(&Default::default());
    r.render(&view, [0.97, 0.62], 12.0, 0., 85., 1000.);
    let row = w * 4;
    let buffer = r.device.create_buffer(&wgpu::BufferDescriptor { label: None, size: (row * h) as u64, usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ, mapped_at_creation: false });
    let mut encoder = r.device.create_command_encoder(&Default::default());
    encoder.copy_texture_to_buffer(output.as_image_copy(), wgpu::TexelCopyBufferInfo { buffer: &buffer, layout: wgpu::TexelCopyBufferLayout { offset: 0, bytes_per_row: Some(row), rows_per_image: Some(h) } }, wgpu::Extent3d { width: w, height: h, depth_or_array_layers: 1 });
    r.queue.submit([encoder.finish()]);
    let (tx, rx) = std::sync::mpsc::channel();
    buffer.slice(..).map_async(wgpu::MapMode::Read, move |res| { tx.send(res).unwrap(); });
    r.device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
    rx.recv().unwrap().unwrap();
    let px = buffer.slice(..).get_mapped_range().unwrap().to_vec();
    let at = |y: u32| { let i = (y * row + (w / 2) * 4) as usize; [px[i], px[i + 1], px[i + 2]] };
    let (top, bottom) = (at(0), at(h - 1));
    println!("top {top:?} middle {:?} bottom {bottom:?}", at(h / 2));
    assert!(top[0] < 135 && top[2] > 240, "deep blue at the top");
    assert!(bottom[0] > 200 && bottom[2] > 245, "pale haze at and below the horizon");
    if let Some(path) = std::env::args().skip_while(|a| a != "--ppm").nth(1) {
        let mut out = format!("P6\n{w} {h}\n255\n").into_bytes();
        for p in px.chunks(4) { out.extend_from_slice(&p[..3]); }
        std::fs::write(path, out).unwrap();
    }
    println!("PASS clear sky gradient");
}
