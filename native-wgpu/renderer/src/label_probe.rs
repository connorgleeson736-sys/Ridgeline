//! Asynchronous per-label terrain visibility for CPU collision placement.
//! A tiny pass after the main pass tests every candidate label's anchor against
//! this frame's depth (label_probe.wgsl); the 512 x 1 result is copied to a
//! mappable buffer and read back without stalling, a few frames later.
use std::sync::Arc;
use std::sync::atomic::{AtomicU8, Ordering};

pub(crate) const MAX: usize = 512;
const IDLE: u8 = 0;
const IN_FLIGHT: u8 = 1;
const READY: u8 = 2;

#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
#[repr(C)]
pub(crate) struct Point {
    pub anchor: [f32; 3],
    pub index: f32,
}

pub(crate) struct LabelProbe {
    pipeline: wgpu::RenderPipeline,
    scene: wgpu::BindGroup,
    depth_binds: Vec<wgpu::BindGroup>,
    target: wgpu::Texture,
    readback: wgpu::Buffer,
    points: wgpu::Buffer,
    state: Arc<AtomicU8>,
    /// Label generation and point count of the probe in flight.
    pending: (u64, usize),
    /// Last completed result: generation and one visibility byte per label
    /// (0 hidden or off screen, 255 visible).
    result: Option<(u64, Vec<u8>)>,
    last: Option<std::time::Instant>,
}

impl LabelProbe {
    pub fn new(device: &wgpu::Device, uniform: &wgpu::Buffer) -> Self {
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("label visibility probe"),
            source: wgpu::ShaderSource::Wgsl(include_str!("label_probe.wgsl").into()),
        });
        let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("label visibility probe"),
            layout: None,
            vertex: wgpu::VertexState {
                module: &shader,
                entry_point: Some("vs"),
                buffers: &[Some(wgpu::VertexBufferLayout {
                    array_stride: 16,
                    step_mode: wgpu::VertexStepMode::Vertex,
                    attributes: &wgpu::vertex_attr_array![0=>Float32x3,1=>Float32],
                })],
                compilation_options: Default::default(),
            },
            fragment: Some(wgpu::FragmentState {
                module: &shader,
                entry_point: Some("fs"),
                targets: &[Some(wgpu::ColorTargetState {
                    format: wgpu::TextureFormat::Rgba8Unorm,
                    blend: None,
                    write_mask: wgpu::ColorWrites::ALL,
                })],
                compilation_options: Default::default(),
            }),
            primitive: wgpu::PrimitiveState {
                topology: wgpu::PrimitiveTopology::PointList,
                ..Default::default()
            },
            depth_stencil: None,
            multisample: Default::default(),
            multiview_mask: None,
            cache: None,
        });
        let scene = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("label probe camera"),
            layout: &pipeline.get_bind_group_layout(0),
            entries: &[wgpu::BindGroupEntry {
                binding: 0,
                resource: uniform.as_entire_binding(),
            }],
        });
        let target = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("label visibility"),
            size: wgpu::Extent3d {
                width: MAX as u32,
                height: 1,
                depth_or_array_layers: 1,
            },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::Rgba8Unorm,
            usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
            view_formats: &[],
        });
        let readback = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("label visibility readback"),
            size: (MAX * 4) as u64,
            usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
            mapped_at_creation: false,
        });
        let points = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("label probe anchors"),
            size: (MAX * 16) as u64,
            usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        Self {
            pipeline,
            scene,
            depth_binds: Vec::new(),
            target,
            readback,
            points,
            state: Arc::new(AtomicU8::new(IDLE)),
            pending: (0, 0),
            result: None,
            last: None,
        }
    }

    pub fn set_depths(&mut self, device: &wgpu::Device, depths: &[wgpu::Texture]) {
        let compare = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("label probe occlusion"),
            compare: Some(wgpu::CompareFunction::LessEqual),
            ..Default::default()
        });
        self.depth_binds = depths
            .iter()
            .map(|depth| {
                device.create_bind_group(&wgpu::BindGroupDescriptor {
                    label: Some("label probe depth"),
                    layout: &self.pipeline.get_bind_group_layout(1),
                    entries: &[
                        wgpu::BindGroupEntry {
                            binding: 0,
                            resource: wgpu::BindingResource::TextureView(
                                &depth.create_view(&Default::default()),
                            ),
                        },
                        wgpu::BindGroupEntry {
                            binding: 1,
                            resource: wgpu::BindingResource::Sampler(&compare),
                        },
                    ],
                })
            })
            .collect();
    }

    /// Collect a finished readback, if any. Never waits.
    pub fn collect(&mut self) {
        if self.state.load(Ordering::Acquire) != READY {
            return;
        }
        let (generation, count) = self.pending;
        let bytes = {
            let data = self.readback.slice(..).get_mapped_range().unwrap();
            (0..count)
                .map(|i| {
                    let probed = data[i * 4 + 1] > 127;
                    if probed && data[i * 4] >= 128 { 255 } else { 0 }
                })
                .collect::<Vec<u8>>()
        };
        self.readback.unmap();
        self.result = Some((generation, bytes));
        self.state.store(IDLE, Ordering::Release);
    }

    /// Whether a new probe may start this frame (none in flight, ~15 per second).
    pub fn due(&self) -> bool {
        self.state.load(Ordering::Acquire) == IDLE
            && !self.depth_binds.is_empty()
            && self
                .last
                .is_none_or(|t| t.elapsed() >= std::time::Duration::from_millis(66))
    }

    /// Encode a probe of [points] against depth slot [slot] (written this frame).
    pub fn encode(
        &mut self,
        queue: &wgpu::Queue,
        encoder: &mut wgpu::CommandEncoder,
        points: &[Point],
        slot: usize,
        generation: u64,
    ) -> bool {
        let count = points.len().min(MAX);
        let Some(depth) = self.depth_binds.get(slot) else {
            return false;
        };
        if count == 0 {
            return false;
        }
        queue.write_buffer(&self.points, 0, bytemuck::cast_slice(&points[..count]));
        {
            let view = self.target.create_view(&Default::default());
            let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("label visibility probe"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: &view,
                    depth_slice: None,
                    resolve_target: None,
                    ops: wgpu::Operations {
                        load: wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT),
                        store: wgpu::StoreOp::Store,
                    },
                })],
                depth_stencil_attachment: None,
                timestamp_writes: None,
                occlusion_query_set: None,
                multiview_mask: None,
            });
            pass.set_pipeline(&self.pipeline);
            pass.set_bind_group(0, &self.scene, &[]);
            pass.set_bind_group(1, depth, &[]);
            pass.set_vertex_buffer(0, self.points.slice(..));
            pass.draw(0..count as u32, 0..1);
        }
        encoder.copy_texture_to_buffer(
            self.target.as_image_copy(),
            wgpu::TexelCopyBufferInfo {
                buffer: &self.readback,
                layout: wgpu::TexelCopyBufferLayout {
                    offset: 0,
                    bytes_per_row: Some((MAX * 4) as u32),
                    rows_per_image: Some(1),
                },
            },
            wgpu::Extent3d {
                width: MAX as u32,
                height: 1,
                depth_or_array_layers: 1,
            },
        );
        self.pending = (generation, count);
        self.state.store(IN_FLIGHT, Ordering::Release);
        self.last = Some(std::time::Instant::now());
        true
    }

    /// After the encoder holding [encode] is submitted.
    pub fn map(&self) {
        if self.state.load(Ordering::Acquire) != IN_FLIGHT {
            return;
        }
        let state = self.state.clone();
        self.readback
            .slice(..)
            .map_async(wgpu::MapMode::Read, move |result| {
                state.store(if result.is_ok() { READY } else { IDLE }, Ordering::Release);
            });
    }

    /// A readback is in flight or ready to collect: draw again to collect it.
    pub fn waiting(&self) -> bool {
        self.state.load(Ordering::Acquire) != IDLE
    }

    pub fn result(&self) -> Option<&(u64, Vec<u8>)> {
        self.result.as_ref()
    }
}
