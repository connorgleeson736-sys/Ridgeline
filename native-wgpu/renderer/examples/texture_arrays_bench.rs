//! Controlled A/B: genuine LINZ image/height payloads with tiny diagnostic colour
//! tags to distinguish layers. This is not a phone FPS or geographic parity test.
use ridgeline_renderer::{MipmapMode, Renderer, TextureBackend, TileId};
use std::{path::Path, time::Instant};
fn read(r: &Renderer, texture: &wgpu::Texture) -> Vec<u8> {
    let size = 512 * 512 * 4;
    let buffer = r.device.create_buffer(&wgpu::BufferDescriptor {
        label: None,
        size,
        usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
        mapped_at_creation: false,
    });
    let mut encoder = r.device.create_command_encoder(&Default::default());
    encoder.copy_texture_to_buffer(
        texture.as_image_copy(),
        wgpu::TexelCopyBufferInfo {
            buffer: &buffer,
            layout: wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(2048),
                rows_per_image: Some(512),
            },
        },
        texture.size(),
    );
    r.queue.submit([encoder.finish()]);
    let (tx, rx) = std::sync::mpsc::channel();
    buffer
        .slice(..)
        .map_async(wgpu::MapMode::Read, move |v| tx.send(v).unwrap());
    r.device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
    rx.recv().unwrap().unwrap();
    let result = buffer.slice(..).get_mapped_range().unwrap().to_vec();
    buffer.unmap();
    result
}
fn main() {
    pollster::block_on(run());
}
async fn run() {
    let args: Vec<_> = std::env::args().collect();
    let path = Path::new(args.get(1).expect("fixture directory"));
    let rgba = std::fs::read(path.join("tile0.rgba")).unwrap();
    let heights: Vec<f32> = std::fs::read(path.join("tile0.heights"))
        .unwrap()
        .chunks_exact(4)
        .map(|b| f32::from_le_bytes(b.try_into().unwrap()))
        .collect();
    let mut descriptor = wgpu::InstanceDescriptor::new_without_display_handle();
    descriptor.backends = if args.iter().any(|v| v == "--gles") {
        wgpu::Backends::GL
    } else {
        wgpu::Backends::VULKAN
    };
    let instance = wgpu::Instance::new(descriptor);
    for trial in 0..5 {
        let mut expected: Option<Vec<u8>> = None;
        let mut expected_recycle: Option<(Vec<u8>, Vec<u8>)> = None;
        for mode in if trial % 2 == 0 { [0, 1, 2] } else { [2, 1, 0] } {
            let backend = if mode == 2 {
                TextureBackend::Arrays
            } else {
                TextureBackend::Separate
            };
            let mut r = Renderer::new_with_texture_backend(
                &instance,
                None,
                Some(wgpu::TextureFormat::Rgba8Unorm),
                512,
                512,
                MipmapMode::Auto,
                backend,
            )
            .await
            .unwrap();
            r.set_mip_scratch_reuse(mode != 0);
            r.set_coordinated_retention(32);
            let output = r.device.create_texture(&wgpu::TextureDescriptor {
                label: Some("array backend comparison"),
                size: wgpu::Extent3d {
                    width: 512,
                    height: 512,
                    depth_or_array_layers: 1,
                },
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format: r.format,
                usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
                view_formats: &[],
            });
            let tiles: Vec<_> = (0..16)
                .map(|i| TileId {
                    z: 14,
                    x: 15928 + i % 4,
                    y: 10400 + i / 4,
                })
                .collect();
            r.set_demand(tiles.clone(), tiles.clone());
            let root = tiles[0].bounds();
            let d = root[2] - root[0];
            let center = [root[0] + d * 2., root[1] + d * 2.];
            let before_mip = r.mipmap_stats();
            let before_array = r.array_stats();
            let start = Instant::now();
            for (i, id) in tiles.iter().enumerate() {
                let mut pixels = rgba.clone();
                for y in 0..16 {
                    for x in 0..16 {
                        let at = (y * 256 + x) * 4;
                        pixels[at..at + 4].copy_from_slice(&[
                            (i * 13) as u8,
                            (i * 7) as u8,
                            (255 - i * 11) as u8,
                            255,
                        ]);
                    }
                }
                let field: Vec<_> = heights.iter().map(|v| v + i as f32 * 7.).collect();
                r.upload(*id, &field, &pixels).unwrap();
            }
            let uploaded_cpu = start.elapsed().as_micros();
            let render_start = Instant::now();
            r.render(
                &output.create_view(&Default::default()),
                center,
                11.5,
                -24.,
                45.,
                1800.,
            );
            let render_cpu = render_start.elapsed().as_micros();
            r.device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
            let elapsed = start.elapsed().as_micros();
            let actual = read(&r, &output);
            if let Some(reference) = &expected {
                assert_eq!(&actual, reference, "array/separate pixel mismatch");
            } else {
                expected = Some(actual.clone());
            }
            let mip = r.mipmap_stats();
            let array = r.array_stats();
            let hash = actual.iter().fold(0xcbf29ce484222325u64, |h, b| {
                (h ^ *b as u64).wrapping_mul(0x100000001b3)
            });
            println!(
                "{{\"trial\":{trial},\"mode\":{mode},\"backend\":\"{:?}\",\"elapsedUsIncludingGpuDrain\":{elapsed},\"uploadSubmitCpuUs\":{uploaded_cpu},\"renderSubmitCpuUs\":{render_cpu},\"imageHeightTextureCreations\":{},\"mipTemporaryCreations\":{},\"mipBindCreations\":{},\"drawCalls\":{},\"textureBinds\":{},\"arrayAllocatedBytes\":{},\"mipScratchBytes\":{},\"pixelHash\":\"{hash:016x}\"}}",
                r.texture_backend,
                array[0] - before_array[0],
                mip.scratch_textures - before_mip.scratch_textures,
                mip.bind_groups - before_mip.bind_groups,
                r.last_frame_draw_calls,
                r.last_frame_texture_binds,
                array[2],
                mip.pooled_bytes
            );
            // Queue a first new view, then overwrite recycled layers repeatedly without
            // waiting for that frame. Its output must retain its original layer contents.
            let first_view = r.device.create_texture(&wgpu::TextureDescriptor {
                label: Some("pre-recycle frame"),
                size: output.size(),
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format: r.format,
                usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
                view_formats: &[],
            });
            for i in 0..80 {
                let id = TileId {
                    z: 14,
                    x: 15940 + i % 16,
                    y: 10400 + i / 16,
                };
                r.set_demand(vec![id], vec![id]);
                let mut pixels = rgba.clone();
                for p in pixels.chunks_exact_mut(4).take(256) {
                    p.copy_from_slice(&[(i * 3) as u8, 91, 173, 255]);
                }
                r.upload(id, &heights, &pixels).unwrap();
                if i == 0 {
                    let b = id.bounds();
                    let c = [(b[0] + b[2]) * 0.5, (b[1] + b[3]) * 0.5];
                    r.render(
                        &first_view.create_view(&Default::default()),
                        c,
                        14.,
                        -24.,
                        45.,
                        1800.,
                    );
                }
            }
            r.set_demand(vec![], vec![]);
            r.release_unused();
            r.set_demand(tiles.clone(), tiles.clone());
            for id in &tiles {
                r.upload(*id, &heights, &rgba).unwrap();
            }
            r.render(
                &output.create_view(&Default::default()),
                center,
                11.5,
                -24.,
                45.,
                1800.,
            );
            let first = read(&r, &first_view);
            let returned = read(&r, &output);
            if let Some((a, b)) = &expected_recycle {
                assert_eq!(&first, a, "in-flight layer reuse changed an older frame");
                assert_eq!(&returned, b, "cache pressure/recreation pixel mismatch");
            } else {
                expected_recycle = Some((first, returned));
            }
            assert!(r.array_stats()[3] <= 50); // two pools, each bounded to25eight-layer pages.
        }
    }
}
