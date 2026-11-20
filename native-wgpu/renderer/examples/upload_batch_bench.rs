//! Upload-batch A/B: genuine LINZ image/height payloads with tiny diagnostic colour
//! tags to distinguish tiles. This is not a phone FPS or geographic parity test.
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
    for trial in 0..3 {
        let mut expected: Option<Vec<u8>> = None;
        for mode in if trial % 2 == 0 { [1, 2, 3] } else { [3, 2, 1] } {
            let backend = if args.iter().any(|v| v == "--arrays") {
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
            r.set_mip_scratch_reuse(true);
            r.set_upload_batching(mode != 1);
            assert!(
                r.mipmap_diagnostics()
                    .unwrap()
                    .iter()
                    .all(|v| v[1] == 0 && v[2] == 255)
            );
            assert!(
                r.array_mipmap_diagnostics()
                    .unwrap()
                    .iter()
                    .all(|v| v[2] == 0)
            );
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
            let tiles: Vec<_> = (0..4)
                .map(|i| TileId {
                    z: 14,
                    x: 15928 + i % 2,
                    y: 10400 + i / 2,
                })
                .collect();
            r.set_demand(tiles.clone(), tiles.clone());
            let root = tiles[0].bounds();
            let d = root[2] - root[0];
            let center = [root[0] + d, root[1] + d];
            // Warm the same draw/mip pipelines, then evict the warm sources so
            // the measured path still performs four genuine new uploads.
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
            r.device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
            r.set_demand(vec![], vec![]);
            r.release_unused();
            assert_eq!(r.image_count(), 0);
            assert_eq!(r.geometry_count(), 0);
            assert_eq!(
                r.upload_image(tiles[0], &[]),
                Err(ridgeline_renderer::UploadError::InvalidImagery)
            );
            assert_eq!(
                r.upload_geometry(tiles[0], &[]),
                Err(ridgeline_renderer::UploadError::InvalidGeometry)
            );
            r.set_demand(tiles.clone(), tiles.clone());
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
            if mode == 2 {
                r.set_upload_batching(false);
                r.set_upload_batching(true);
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
                assert_eq!(
                    &actual, reference,
                    "batched upload changed final frame pixels"
                );
            } else {
                expected = Some(actual.clone());
            }
            let mip = r.mipmap_stats();
            let array = r.array_stats();
            let mip_submits = mip.submissions - before_mip.submissions;
            let batched = mip.batched_chains - before_mip.batched_chains;
            if !r.uses_cpu_mips() {
                assert_eq!(mip_submits, if mode != 1 { 0 } else { 4 });
                assert_eq!(batched, if mode != 1 { 4 } else { 0 });
            }
            println!(
                "{{\"trial\":{trial},\"mode\":{mode},\"mipStandaloneSubmits\":{mip_submits},\"batchedChains\":{batched},\"cpuMips\":{}}}",
                r.uses_cpu_mips()
            );
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
        }
    }
}
