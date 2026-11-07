//! Real GPU resource work, genuine LINZ payloads, controlled locality workload.
//! No claim of geographic screenshot parity or physical phone performance.
use ridgeline_renderer::{Renderer, TileId};
use std::{path::Path, time::Instant};
fn main() {
    pollster::block_on(run());
}
async fn run() {
    let args: Vec<_> = std::env::args().collect();
    let input = Path::new(args.get(1).expect("fixture directory"));
    let rgba = std::fs::read(input.join("tile0.rgba")).unwrap();
    let heights: Vec<f32> = std::fs::read(input.join("tile0.heights"))
        .unwrap()
        .chunks_exact(4)
        .map(|b| f32::from_le_bytes(b.try_into().unwrap()))
        .collect();
    let mut descriptor = wgpu::InstanceDescriptor::new_without_display_handle();
    descriptor.backends = if args.iter().any(|s| s == "--gles") {
        wgpu::Backends::GL
    } else {
        wgpu::Backends::VULKAN
    };
    let instance = wgpu::Instance::new(descriptor);
    // 36-photo revisit, eight terrain fields: more photos than the old 32 limit,
    // yet within the same combined payload target. Every mip remains full quality.
    for trial in 0..6 {
        for coordinated in if trial % 2 == 0 {
            [false, true]
        } else {
            [true, false]
        } {
            let mut r = Renderer::new(
                &instance,
                None,
                Some(wgpu::TextureFormat::Rgba8Unorm),
                256,
                256,
            )
            .await
            .unwrap();
            if coordinated {
                r.set_coordinated_retention(32);
            } else {
                r.set_tile_budget(32);
                r.set_geometry_retention(48);
            }
            let geometry: Vec<_> = (0..8)
                .map(|i| TileId {
                    z: 11,
                    x: 1991 + i,
                    y: 1300,
                })
                .collect();
            r.set_demand(geometry.clone(), vec![]);
            for &id in &geometry {
                r.upload_geometry(id, &heights).unwrap();
            }
            let mut uploads = 0;
            let mut revisits = 0;
            let mut cpu_us = Vec::new();
            let mut cpu_kind = Vec::new();
            let start = Instant::now();
            for visit in 0..2 {
                let ids: Vec<_> = if visit == 0 {
                    (0..36).collect()
                } else {
                    (0..36).rev().collect()
                };
                for i in ids {
                    let id = TileId {
                        z: 11,
                        x: 1991 + i,
                        y: 1300,
                    };
                    r.set_demand(geometry.clone(), vec![id]);
                    if r.has_image(id) {
                        assert!(r.touch_image(id));
                        continue;
                    }
                    let t = Instant::now();
                    r.upload_image(id, &rgba).unwrap();
                    cpu_us.push(t.elapsed().as_nanos() as u64);
                    cpu_kind.push(2);
                    uploads += 1;
                    if visit > 0 {
                        revisits += 1;
                    }
                }
            }
            r.device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
            let total_us = start.elapsed().as_micros();
            assert!(geometry.iter().all(|id| r.has_geometry(*id)));
            if coordinated {
                assert_eq!(revisits, 0);
                assert!(r.resident_payload_bytes() <= r.resident_payload_target().unwrap());
            }
            println!(
                "{{\"coordinated\":{coordinated},\"trial\":{trial},\"uploads\":{uploads},\"revisitUploads\":{revisits},\"uploadedLevel0Bytes\":{},\"mipLevelsGenerated\":{},\"elapsedUsIncludingGpuDrain\":{total_us},\"residentPayloadBytes\":{},\"targetBytes\":{},\"uploadCpuNanos\":{:?},\"kind\":{:?}}}",
                uploads * rgba.len(),
                uploads * 8,
                r.resident_payload_bytes(),
                r.resident_payload_target().unwrap_or(0),
                cpu_us,
                cpu_kind
            );
            if trial == 0 {
                r.resize(768, 1536);
                let texture = r.device.create_texture(&wgpu::TextureDescriptor {
                    label: Some("queue pressure fixture"),
                    size: wgpu::Extent3d {
                        width: 768,
                        height: 1536,
                        depth_or_array_layers: 1,
                    },
                    mip_level_count: 1,
                    sample_count: 1,
                    dimension: wgpu::TextureDimension::D2,
                    format: r.format,
                    usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
                    view_formats: &[],
                });
                let target = texture.create_view(&Default::default());
                let mut peak = 0;
                let mut waits = 0;
                let start = Instant::now();
                for _ in 0..12 {
                    if coordinated && r.gpu_backlog() >= 2 {
                        r.device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
                        waits += 1;
                    }
                    r.render(
                        &target,
                        ridgeline_renderer::mercator(170.1418, -43.595),
                        12.,
                        -24.,
                        65.,
                        1900.,
                    );
                    peak = peak.max(r.outstanding_frames());
                }
                r.device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
                let elapsed = start.elapsed().as_micros();
                assert_eq!(r.outstanding_frames(), 0);
                if coordinated {
                    assert!(peak <= 2);
                }
                let buffer = r.device.create_buffer(&wgpu::BufferDescriptor {
                    label: None,
                    size: 768 * 1536 * 4,
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
                            bytes_per_row: Some(768 * 4),
                            rows_per_image: Some(1536),
                        },
                    },
                    texture.size(),
                );
                r.queue.submit([encoder.finish()]);
                let (tx, rx) = std::sync::mpsc::channel();
                buffer
                    .slice(..)
                    .map_async(wgpu::MapMode::Read, move |result| tx.send(result).unwrap());
                r.device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
                rx.recv().unwrap().unwrap();
                let pixels = buffer.slice(..).get_mapped_range().unwrap();
                let hash = pixels.iter().fold(0xcbf29ce484222325u64, |h, b| {
                    (h ^ *b as u64).wrapping_mul(0x100000001b3)
                });
                println!(
                    "{{\"queuePressure\":true,\"coordinated\":{coordinated},\"frames\":12,\"peakOutstandingSubmissions\":{peak},\"backpressureWaits\":{waits},\"elapsedUsIncludingGpuDrain\":{elapsed},\"pixelHash\":\"{hash:016x}\"}}"
                );
                drop(pixels);
                buffer.unmap();
                if coordinated {
                    let mut demanded = Vec::new();
                    for i in 0..80 {
                        let id = TileId {
                            z: 12,
                            x: 3982 + i,
                            y: 2600,
                        };
                        demanded.push(id);
                        if demanded.len() > 32 {
                            demanded.remove(0);
                        }
                        r.set_demand(geometry.clone(), demanded.clone());
                        r.upload_image(id, &rgba).unwrap();
                    }
                    r.device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
                    assert!(demanded.iter().all(|id| r.has_image(*id)));
                    assert!(geometry.iter().all(|id| r.has_geometry(*id)));
                    assert!(r.resident_payload_bytes() <= r.resident_payload_target().unwrap());
                    println!(
                        "{{\"residencyPressure\":true,\"residentImages\":{},\"protectedImages\":32,\"protectedTerrain\":8,\"residentPayloadBytes\":{},\"targetBytes\":{}}}",
                        r.image_count(),
                        r.resident_payload_bytes(),
                        r.resident_payload_target().unwrap()
                    );
                }
            }
        }
    }
}
