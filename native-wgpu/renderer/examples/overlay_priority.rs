//! A full overlay pool and a camera that moves: the tiles nearest the new
//! view's centre must get their lines, taken from tiles further out, instead
//! of the tiles that happened to hold the textures keeping them while new
//! ground stayed without tracks. Actual GPU execution.
use ridgeline_renderer::{CartographicPath, Renderer, TileId};
fn main() {
    pollster::block_on(run());
}
async fn run() {
    let gles = std::env::args().any(|s| s == "--gles");
    let mut descriptor = wgpu::InstanceDescriptor::new_without_display_handle();
    descriptor.backends = if gles { wgpu::Backends::GL } else { wgpu::Backends::VULKAN };
    let instance = wgpu::Instance::new(descriptor);
    let mut r = Renderer::new(&instance, None, Some(wgpu::TextureFormat::Rgba8Unorm), 512, 512).await.unwrap();
    // 12 MiB: 32 textures. Ten columns by eight rows of zoom-14 tiles, every one with lines.
    r.set_tile_budget(256);
    r.set_overlay_layers(12);
    let (x0, y0) = (1991 * 8, 1300 * 8);
    let tile = |c: u32, row: u32| TileId { z: 14, x: x0 + c, y: y0 + row };
    for c in 0..10 {
        for row in 0..8 {
            r.upload(tile(c, row), &vec![500.; 129 * 129], &[160u8, 160, 160, 255].repeat(256 * 256)).unwrap();
        }
    }
    let (w, e) = (tile(0, 0).bounds()[0], tile(9, 0).bounds()[2]);
    let lines = (0..8)
        .map(|row| {
            let b = tile(0, row).bounds();
            let y = (b[1] + b[3]) / 2.;
            CartographicPath { points: vec![[w, y], [e, y]], color: [0., 1., 0., 1.], width: 4., dash: [0., 0.] }
        })
        .collect();
    r.set_cartography(lines);
    let output = r.device.create_texture(&wgpu::TextureDescriptor {
        label: Some("overlay priority"),
        size: wgpu::Extent3d { width: 512, height: 512, depth_or_array_layers: 1 },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
        view_formats: &[],
    });
    let target = output.create_view(&Default::default());
    // The view centred on the west edge of column [c] (about three columns
    // each side in view) and on the rows' middle.
    let centre = |c: u32| {
        let (a, b) = (tile(c, 0).bounds(), tile(c, 7).bounds());
        [a[0], (a[1] + b[3]) / 2.]
    };
    let settle = |r: &mut Renderer, centre: [f64; 2]| {
        // The drawn tiles follow the camera over a few frames.
        for i in 0..80 {
            r.render(&target, centre, 11., 0., 0., 500.);
            std::thread::sleep(std::time::Duration::from_millis(2));
            if i >= 20 && !r.overlays_pending() {
                break;
            }
        }
        r.render(&target, centre, 11., 0., 0., 500.);
    };
    // Columns 4-9 in view: the pool fills with the first 32 of them.
    settle(&mut r, centre(7));
    let (_, missing_a) = r.overlay_coverage();
    assert!(missing_a > 0, "the view needs more textures than the pool has ({missing_a} missing)");
    // Move west, centred between columns 2 and 3: columns 1-3 are new ground.
    settle(&mut r, centre(3));
    let held = r.overlay_tiles();
    // Every tile within two tiles of the new centre has its lines.
    let near: Vec<TileId> = (1..=4).flat_map(|c| (2..=5).map(move |row| tile(c, row))).collect();
    let without = near.iter().filter(|t| !held.contains(t)).count();
    let (shown, missing) = r.overlay_coverage();
    assert_eq!(without, 0, "tiles by the centre have their lines ({without} of 16 without; {shown} shown, {missing} missing)");
    println!("PASS overlay priority: new ground takes textures from later tiles ({shown} shown, {missing} missing beyond the pool) ({})", if gles { "GLES" } else { "Vulkan" });
}
