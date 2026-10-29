//! Native terrain GPU proof. Camera/lighting/feature parity is an open gate.
use glam::{Mat4, Vec3};
pub use ridgeline_core::{TileId, mercator, terrain_rgb};
use std::collections::HashMap;
use wgpu::util::DeviceExt;
#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct Scene {
    matrix: [f32; 16],
    grade: [f32; 4],
    metrics: [f32; 4],
}
#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct Vertex {
    position: [f32; 3],
}
struct Tile {
    vertices: wgpu::Buffer,
    bind: wgpu::BindGroup,
    _texture: wgpu::Texture,
    used: u64,
}
pub struct Renderer {
    pub device: wgpu::Device,
    pub queue: wgpu::Queue,
    pub format: wgpu::TextureFormat,
    pipeline: wgpu::RenderPipeline,
    scene: wgpu::Buffer,
    scene_bind: wgpu::BindGroup,
    texture_layout: wgpu::BindGroupLayout,
    sampler: wgpu::Sampler,
    indices: wgpu::Buffer,
    index_count: u32,
    instances: wgpu::Buffer,
    tiles: HashMap<TileId, Tile>,
    depth: wgpu::Texture,
    width: u32,
    height: u32,
    pub frames: u64,
    cover: Vec<ridgeline_core::CoverRegion>,
    instance_capacity: usize,
    budget: usize,
    clock: u64,
    center: [f64; 2],
}
impl Renderer {
    pub async fn new(
        instance: &wgpu::Instance,
        surface: Option<&wgpu::Surface<'_>>,
        requested_format: Option<wgpu::TextureFormat>,
        width: u32,
        height: u32,
    ) -> Result<Self, String> {
        let adapter = instance
            .request_adapter(&wgpu::RequestAdapterOptions {
                power_preference: wgpu::PowerPreference::HighPerformance,
                compatible_surface: surface,
                force_fallback_adapter: false,
                ..Default::default()
            })
            .await
            .map_err(|e| e.to_string())?;
        let format = requested_format.unwrap_or_else(|| {
            let formats = surface
                .expect("surface required without explicit format")
                .get_capabilities(&adapter)
                .formats;
            formats
                .iter()
                .copied()
                .find(|f| !f.is_srgb())
                .unwrap_or(formats[0])
        });
        let (device, queue) = adapter
            .request_device(&wgpu::DeviceDescriptor::default())
            .await
            .map_err(|e| e.to_string())?;
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("terrain proof"),
            source: wgpu::ShaderSource::Wgsl(include_str!("terrain.wgsl").into()),
        });
        let scene = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("scene"),
            contents: bytemuck::bytes_of(&Scene {
                matrix: Mat4::IDENTITY.to_cols_array(),
                grade: [0.058, 1., 0.25, 1. / 0.74],
                metrics: [1., 0., 0., 0.],
            }),
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
        });
        let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("terrain"),
            layout: None,
            vertex: wgpu::VertexState {
                module: &shader,
                entry_point: Some("vs"),
                compilation_options: Default::default(),
                buffers: &[
                    Some(wgpu::VertexBufferLayout {
                        array_stride: 12,
                        step_mode: wgpu::VertexStepMode::Vertex,
                        attributes: &wgpu::vertex_attr_array![0=>Float32x3],
                    }),
                    Some(wgpu::VertexBufferLayout {
                        array_stride: 32,
                        step_mode: wgpu::VertexStepMode::Instance,
                        attributes: &wgpu::vertex_attr_array![1=>Float32x4,2=>Float32x4],
                    }),
                ],
            },
            primitive: wgpu::PrimitiveState {
                cull_mode: None,
                ..Default::default()
            },
            depth_stencil: Some(wgpu::DepthStencilState {
                format: wgpu::TextureFormat::Depth32Float,
                depth_write_enabled: Some(true),
                depth_compare: Some(wgpu::CompareFunction::LessEqual),
                stencil: Default::default(),
                bias: Default::default(),
            }),
            multisample: Default::default(),
            fragment: Some(wgpu::FragmentState {
                module: &shader,
                entry_point: Some("fs"),
                compilation_options: Default::default(),
                targets: &[Some(wgpu::ColorTargetState {
                    format,
                    blend: Some(wgpu::BlendState::PREMULTIPLIED_ALPHA_BLENDING),
                    write_mask: wgpu::ColorWrites::ALL,
                })],
            }),
            multiview_mask: None,
            cache: None,
        });
        let scene_bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: None,
            layout: &pipeline.get_bind_group_layout(0),
            entries: &[wgpu::BindGroupEntry {
                binding: 0,
                resource: scene.as_entire_binding(),
            }],
        });
        let texture_layout = pipeline.get_bind_group_layout(1);
        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            mipmap_filter: wgpu::MipmapFilterMode::Linear,
            ..Default::default()
        });
        let mut idx = Vec::<u32>::new();
        for y in 0..128 {
            for x in 0..128 {
                let a = y * 129 + x;
                idx.extend([a, a + 129, a + 1, a + 1, a + 129, a + 130]);
            }
        }
        let indices = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("shared grid"),
            contents: bytemuck::cast_slice(&idx),
            usage: wgpu::BufferUsages::INDEX,
        });
        let instances = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("tile instances"),
            size: 256 * 32,
            usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let depth = Self::depth(&device, width, height);
        Ok(Self {
            device,
            queue,
            format,
            pipeline,
            scene,
            scene_bind,
            texture_layout,
            sampler,
            indices,
            index_count: idx.len() as u32,
            instances,
            tiles: HashMap::new(),
            depth,
            width,
            height,
            frames: 0,
            cover: Vec::new(),
            instance_capacity: 256,
            budget: 96,
            clock: 0,
            center: [0.5, 0.5],
        })
    }
    fn depth(d: &wgpu::Device, w: u32, h: u32) -> wgpu::Texture {
        d.create_texture(&wgpu::TextureDescriptor {
            label: Some("depth"),
            size: wgpu::Extent3d {
                width: w.max(1),
                height: h.max(1),
                depth_or_array_layers: 1,
            },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::Depth32Float,
            usage: wgpu::TextureUsages::RENDER_ATTACHMENT,
            view_formats: &[],
        })
    }
    pub fn resize(&mut self, w: u32, h: u32) {
        self.width = w.max(1);
        self.height = h.max(1);
        self.depth = Self::depth(&self.device, self.width, self.height);
    }
    /// Proof input: height values sampled on the native 129x129 mesh, unmodified metres.
    pub fn upload(&mut self, id: TileId, heights: &[f32], rgba: &[u8]) -> Result<(), String> {
        if heights.len() != 129 * 129
            || rgba.len() != 256 * 256 * 4
            || heights.iter().any(|h| !h.is_finite())
        {
            return Err("Invalid terrain/image payload".into());
        }
        self.clock += 1;
        if let Some(tile) = self.tiles.get_mut(&id) {
            tile.used = self.clock;
            return Ok(());
        }
        let v: Vec<Vertex> = (0..129 * 129)
            .map(|i| Vertex {
                position: [(i % 129) as f32 / 128., (i / 129) as f32 / 128., heights[i]],
            })
            .collect();
        let vertices = self
            .device
            .create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some("tile vertices"),
                contents: bytemuck::cast_slice(&v),
                usage: wgpu::BufferUsages::VERTEX,
            });
        let texture = self.device.create_texture(&wgpu::TextureDescriptor {
            label: Some("actual imagery"),
            size: wgpu::Extent3d {
                width: 256,
                height: 256,
                depth_or_array_layers: 1,
            },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::Rgba8Unorm,
            usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
            view_formats: &[],
        });
        self.queue.write_texture(
            texture.as_image_copy(),
            rgba,
            wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(1024),
                rows_per_image: Some(256),
            },
            wgpu::Extent3d {
                width: 256,
                height: 256,
                depth_or_array_layers: 1,
            },
        );
        let view = texture.create_view(&Default::default());
        let bind = self.device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: None,
            layout: &self.texture_layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: wgpu::BindingResource::TextureView(&view),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: wgpu::BindingResource::Sampler(&self.sampler),
                },
            ],
        });
        self.tiles.insert(
            id,
            Tile {
                vertices,
                bind,
                _texture: texture,
                used: self.clock,
            },
        );
        self.trim();
        self.rebuild_cover();
        Ok(())
    }
    /// Budget includes geometry + RGBA image storage (~462KiB/tile), not depth/surface.
    pub fn set_tile_budget(&mut self, tiles: usize) {
        self.budget = tiles.clamp(24, 192);
        self.trim();
        self.rebuild_cover();
    }
    pub fn resident_count(&self) -> usize {
        self.tiles.len()
    }
    fn rebuild_cover(&mut self) {
        self.cover =
            ridgeline_core::resident_cover(&self.tiles.keys().copied().collect::<Vec<_>>());
    }
    fn trim(&mut self) {
        while self.tiles.len() > self.budget {
            // Keep nearby useful ancestors and recently used off-screen data. Prefer
            // old distant tiles for eviction; distance is measured in each tile's units.
            let victim = self
                .tiles
                .iter()
                .max_by(|(a, ta), (b, tb)| {
                    let score = |id: &TileId, t: &Tile| {
                        let bounds = id.bounds();
                        let size = bounds[2] - bounds[0];
                        let dx = ((bounds[0] + bounds[2]) / 2. - self.center[0]) / size;
                        let dy = ((bounds[1] + bounds[3]) / 2. - self.center[1]) / size;
                        (dx * dx + dy * dy).sqrt().ln_1p() + (self.clock - t.used) as f64 / 32.
                    };
                    score(a, ta).total_cmp(&score(b, tb))
                })
                .map(|(id, _)| *id)
                .unwrap();
            self.tiles.remove(&victim);
        }
    }
    pub fn render(
        &mut self,
        target: &wgpu::TextureView,
        center: [f64; 2],
        zoom: f64,
        bearing: f32,
        pitch: f32,
        elevation: f32,
    ) {
        let world = 512. * 2f64.powf(zoom);
        let (matrix, px_meter) = camera_matrix(
            self.width,
            self.height,
            center,
            zoom,
            bearing,
            pitch,
            elevation,
        );
        self.queue.write_buffer(
            &self.scene,
            0,
            bytemuck::bytes_of(&Scene {
                matrix: matrix.to_cols_array(),
                grade: [0.058, 1., 0.25, 1. / 0.74],
                metrics: [px_meter, elevation, 0., 0.],
            }),
        );
        self.center = center;
        // The cached cover partitions coarse fallback and fine terrain; each ground
        // location has exactly one owner. No overlapping parent/child depth fighting.
        let rows = &self.cover;
        let data: Vec<[f32; 8]> = rows
            .iter()
            .map(|row| {
                let b = row.source.bounds();
                let region = row.region.bounds();
                let size = b[2] - b[0];
                [
                    ((b[0] - center[0]) * world) as f32,
                    ((center[1] - b[1]) * world) as f32,
                    (size * world) as f32,
                    0.,
                    ((region[0] - b[0]) / size) as f32,
                    ((region[1] - b[1]) / size) as f32,
                    ((region[2] - b[0]) / size) as f32,
                    ((region[3] - b[1]) / size) as f32,
                ]
            })
            .collect();
        if data.len() > self.instance_capacity {
            self.instance_capacity = data.len().next_power_of_two();
            self.instances = self.device.create_buffer(&wgpu::BufferDescriptor {
                label: Some("tile instances"),
                size: (self.instance_capacity * 32) as u64,
                usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::COPY_DST,
                mapped_at_creation: false,
            });
        }
        if !data.is_empty() {
            self.queue
                .write_buffer(&self.instances, 0, bytemuck::cast_slice(&data));
        }
        let mut encoder = self.device.create_command_encoder(&Default::default());
        let depth = self.depth.create_view(&Default::default());
        {
            let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("terrain"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: target,
                    depth_slice: None,
                    resolve_target: None,
                    ops: wgpu::Operations {
                        load: wgpu::LoadOp::Clear(wgpu::Color {
                            r: 189. / 255.,
                            g: 203. / 255.,
                            b: 208. / 255.,
                            a: 1.,
                        }),
                        store: wgpu::StoreOp::Store,
                    },
                })],
                depth_stencil_attachment: Some(wgpu::RenderPassDepthStencilAttachment {
                    view: &depth,
                    depth_ops: Some(wgpu::Operations {
                        load: wgpu::LoadOp::Clear(1.),
                        store: wgpu::StoreOp::Discard,
                    }),
                    stencil_ops: None,
                }),
                timestamp_writes: None,
                occlusion_query_set: None,
                multiview_mask: None,
            });
            pass.set_pipeline(&self.pipeline);
            pass.set_bind_group(0, &self.scene_bind, &[]);
            pass.set_index_buffer(self.indices.slice(..), wgpu::IndexFormat::Uint32);
            for (i, row) in rows.iter().enumerate() {
                let tile = &self.tiles[&row.source];
                pass.set_bind_group(1, &tile.bind, &[]);
                pass.set_vertex_buffer(0, tile.vertices.slice(..));
                pass.set_vertex_buffer(
                    1,
                    self.instances.slice((i * 32) as u64..((i + 1) * 32) as u64),
                );
                pass.draw_indexed(0..self.index_count, 0, 0..1);
            }
        }
        self.queue.submit([encoder.finish()]);
        self.frames += 1;
    }
}

/// MapLibre Mercator camera rebased around the center before Float32 conversion.
/// Unpadded viewport, no roll. Padding/terrain collision/gesture parity remain open.
pub fn camera_matrix(
    width: u32,
    height: u32,
    center: [f64; 2],
    zoom: f64,
    bearing: f32,
    pitch: f32,
    elevation: f32,
) -> (Mat4, f32) {
    let world = 512. * 2f64.powf(zoom);
    let px_meter = (world / ridgeline_core::ALTITUDE_CIRCUMFERENCE
        * (2. * std::f64::consts::PI * (center[1] - 0.5)).cosh()) as f32;
    let distance = height as f32 * 1.5;
    let b = bearing.to_radians();
    let p = pitch.to_radians();
    let at = Vec3::ZERO;
    let eye = at + Vec3::new(-b.sin() * p.sin(), -b.cos() * p.sin(), p.cos()) * distance;
    let up = Vec3::new(b.sin() * p.cos(), b.cos() * p.cos(), p.sin());
    (
        perspective(
            36.8698976f32.to_radians(),
            width as f32 / height as f32,
            height as f32 / 50.,
            camera_depth_range(height, px_meter as f64, pitch as f64, elevation as f64, 0.).1
                as f32,
        ) * look_at(eye, at, up),
        px_meter,
    )
}

/// Official MapLibre unpadded/zero-roll near/far and horizon policy, including
/// altitude and a conservative minimum terrain elevation. Pixel units.
pub fn camera_depth_range(
    height: u32,
    px_meter: f64,
    pitch: f64,
    elevation: f64,
    min_elevation: f64,
) -> (f64, f64) {
    let distance = height as f64 * 1.5;
    let p = pitch.to_radians();
    let cos = p.cos();
    let camera_alt = elevation + distance * cos / px_meter;
    let minimum = elevation.min(min_elevation).min(camera_alt - 100.);
    let far_distance = (distance + elevation * px_meter / cos).max(distance / 2.);
    let a = if minimum < 0. {
        far_distance - minimum * px_meter / cos
    } else {
        far_distance
    };
    let half_fov = 36.86989764584402f64.to_radians() / 2.;
    let length = |angle: f64| {
        angle.sin() * a
            / (std::f64::consts::FRAC_PI_2 - p - angle)
                .clamp(0.01, std::f64::consts::PI - 0.01)
                .sin()
    };
    let horizon = (std::f64::consts::FRAC_PI_2 - p).tan() * 0.85;
    let horizon = horizon.min((89.25f64.to_radians() - p).tan());
    let horizon_angle = horizon.atan().max(0.75f64.to_radians());
    (
        height as f64 / 50.,
        (p.sin() * length(half_fov).min(length(horizon_angle)) + a) * 1.01,
    )
}

fn perspective(fov: f32, aspect: f32, near: f32, far: f32) -> Mat4 {
    let f = 1. / (fov / 2.).tan();
    Mat4::from_cols_array(&[
        f / aspect,
        0.,
        0.,
        0.,
        0.,
        f,
        0.,
        0.,
        0.,
        0.,
        far / (near - far),
        -1.,
        0.,
        0.,
        near * far / (near - far),
        0.,
    ])
}
fn look_at(eye: Vec3, at: Vec3, up: Vec3) -> Mat4 {
    let f = (at - eye).normalize();
    let s = f.cross(up).normalize();
    let u = s.cross(f);
    Mat4::from_cols_array(&[
        s.x,
        u.x,
        -f.x,
        0.,
        s.y,
        u.y,
        -f.y,
        0.,
        s.z,
        u.z,
        -f.z,
        0.,
        -s.dot(eye),
        -u.dot(eye),
        f.dot(eye),
        1.,
    ])
}

#[cfg(target_os = "android")]
mod android;
