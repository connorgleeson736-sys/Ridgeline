//! One cached native-font atlas and a depth-tested batch, with CPU grid collision selection.
//! Terrain occlusion is decided per label on the GPU and smoothed from frame to
//! frame (label_fade.wgsl), and labels dropped by placement fade out, so names
//! never blink while a tilted map moves.
use std::collections::{HashMap, HashSet};
/// Grid cells (~10 m each) within which a label's earlier height and first-shown time are its own.
const NEAR_CELLS: i64 = 4;
/// Label state slots on the GPU (candidates are at most 512, plus labels still
/// on screen from the previous candidates).
pub(crate) const SLOTS: usize = 1024;
/// Seconds a label dropped by placement takes to fade out (labels.wgsl).
const FADE_OUT: f32 = 0.25;
/// Seconds frames keep coming after the camera stops, for the smoothed
/// visibility and its fades to settle (label_fade.wgsl).
const SETTLE: f32 = 0.5;
/// "Never": the fade-out time of a label still placed.
const LIVE: f32 = 1e9;
/// A label's identity across candidate batches: its place (grid cell), sprite
/// size and which of the same is meant (a curve's letters share a place).
pub(crate) type Ident = (i64, i64, u64, u32);
/// A label drawn by the last rebuild: enough to draw it again while it fades out.
#[derive(Clone, Copy)]
pub(crate) struct Drawn {
    ident: Ident,
    height: f32,
    offset: [f32; 2],
    angle: f32,
    born: f32,
    dies: f32,
}
/// The value at [k] or, failing that, at the nearest cell within NEAR_CELLS.
fn near<V: Copy>(map: &HashMap<(i64, i64), V>, k: (i64, i64)) -> Option<V> {
    map.get(&k).copied().or_else(|| {
        (1..=NEAR_CELLS).find_map(|r| {
            (-r..=r)
                .flat_map(|dx| (-r..=r).map(move |dy| (k.0 + dx, k.1 + dy)))
                .filter(|n| (n.0 - k.0).abs() == r || (n.1 - k.1).abs() == r)
                .find_map(|n| map.get(&n).copied())
        })
    })
}
/// Label continuity grid: 2^22 cells across the world (~10 m at the equator).
const LABEL_GRID: f64 = 4_194_304.0;
pub(crate) fn grid_key(p: [f64; 2]) -> (i64, i64) {
    (
        (p[0] * LABEL_GRID).round() as i64,
        (p[1] * LABEL_GRID).round() as i64,
    )
}
#[derive(Clone, Copy)]
pub struct LabelSprite {
    pub point: [f64; 2],
    pub uv: [f32; 4],
    pub size: [f32; 2],
}
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
#[repr(C)]
struct Vertex {
    anchor: [f32; 3],
    offset: [f32; 2],
    uv: [f32; 2],
    /// Renderer time (s) the label first appeared, for its fade-in.
    born: f32,
    /// Its terrain state slot (label_fade.wgsl).
    slot: f32,
    /// Renderer time (s) its fade-out ends, once dropped by placement (LIVE while placed).
    dies: f32,
}
/// One label slot's update in the fade pass.
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
#[repr(C)]
pub(crate) struct FadePoint {
    pub anchor: [f32; 3],
    pub slot: f32,
    /// 1: a slot newly given to this label; its old state is someone else's.
    pub fresh: f32,
}
#[derive(Clone, Copy, PartialEq, bytemuck::Pod, bytemuck::Zeroable)]
#[repr(C)]
struct Scene {
    matrix: [f32; 16],
    /// Current-frame local coordinates to the previous frame's clip space.
    previous: [f32; 16],
    anchor: [f32; 4],
    size: [f32; 4],
    /// x: renderer time (s); y: 1 when the previous frame's depth is valid;
    /// z: seconds since the previous frame (fades and filtering).
    time: [f32; 4],
}
#[derive(Clone, Copy)]
struct Frame {
    matrix: glam::Mat4,
    center: [f64; 2],
    world: f64,
    ppm: f64,
    elevation: f64,
}
pub(crate) struct LabelGpu {
    width: u32,
    height: u32,
    pixels: Vec<u8>,
    uploaded_bytes: u64,
    pipeline: wgpu::RenderPipeline,
    pub(crate) uniform: wgpu::Buffer,
    bind: wgpu::BindGroup,
    atlas_bind: wgpu::BindGroup,
    /// The atlas the placed batch was built against. A resized atlas does not
    /// replace it until the next placement is built, so names stay on screen
    /// (they all vanished for a round trip whenever new names grew the atlas).
    draw_bind: wgpu::BindGroup,
    _atlas: wgpu::Texture,
    vertices: wgpu::Buffer,
    count: u32,
    last: Option<Scene>,
    read_slot: usize,
    previous_valid: bool,
    previous: Option<Frame>,
    /// The per-label terrain state: two SLOTS x 1 textures written in turn (the
    /// fade pass reads one and writes the other); labels and the probe read the
    /// one written last, [state_slot].
    states: Vec<wgpu::Texture>,
    state_views: Vec<wgpu::TextureView>,
    label_state_binds: Vec<wgpu::BindGroup>,
    fade_pipeline: wgpu::RenderPipeline,
    fade_scene: wgpu::BindGroup,
    fade_state_binds: Vec<wgpu::BindGroup>,
    /// One bind per depth slot; the fade pass reads the slot the previous frame wrote.
    fade_depth_binds: Vec<wgpu::BindGroup>,
    fade_points: wgpu::Buffer,
    fade_count: u32,
    pub(crate) state_slot: usize,
    last_time: Option<f32>,
    last_matrix: Option<[f32; 16]>,
    /// Renderer time until which frames keep coming for the state to settle.
    pub(crate) settle_until: f32,
    /// This frame's fade pass ran with the previous frame's depth.
    known: bool,
}
impl LabelGpu {
    pub fn new(device: &wgpu::Device, queue: &wgpu::Queue, format: wgpu::TextureFormat) -> Self {
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("native map names"),
            source: wgpu::ShaderSource::Wgsl(include_str!("labels.wgsl").into()),
        });
        let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("map labels over terrain"),
            layout: None,
            vertex: wgpu::VertexState {
                module: &shader,
                entry_point: Some("vs"),
                buffers: &[Some(wgpu::VertexBufferLayout {
                    array_stride: 40,
                    step_mode: wgpu::VertexStepMode::Vertex,
                    attributes: &wgpu::vertex_attr_array![0=>Float32x3,1=>Float32x2,2=>Float32x2,3=>Float32,4=>Float32,5=>Float32],
                })],
                compilation_options: Default::default(),
            },
            fragment: Some(wgpu::FragmentState {
                module: &shader,
                entry_point: Some("fs"),
                targets: &[Some(wgpu::ColorTargetState {
                    format,
                    blend: Some(wgpu::BlendState::PREMULTIPLIED_ALPHA_BLENDING),
                    write_mask: wgpu::ColorWrites::ALL,
                })],
                compilation_options: Default::default(),
            }),
            primitive: Default::default(),
            // Like MapLibre symbols: screen-space text drawn whole on top of the
            // terrain, inside the main pass (no depth test). Occlusion is decided
            // on the GPU from the previous frame's terrain depth at each label's
            // anchor and smoothed over time (label_fade.wgsl), never per pixel.
            depth_stencil: Some(wgpu::DepthStencilState {
                format: wgpu::TextureFormat::Depth32Float,
                depth_write_enabled: Some(false),
                depth_compare: Some(wgpu::CompareFunction::Always),
                stencil: Default::default(),
                bias: Default::default(),
            }),
            multisample: Default::default(),
            multiview_mask: None,
            cache: None,
        });
        let uniform = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("label camera"),
            size: 176,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: None,
            layout: &pipeline.get_bind_group_layout(0),
            entries: &[wgpu::BindGroupEntry {
                binding: 0,
                resource: uniform.as_entire_binding(),
            }],
        });
        let (atlas, atlas_bind) = Self::atlas(device, queue, &pipeline, 1, 1, &[0; 4]);
        let vertices = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("empty labels"),
            size: 40,
            usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let fade_shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("label terrain state"),
            source: wgpu::ShaderSource::Wgsl(include_str!("label_fade.wgsl").into()),
        });
        let fade_pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("label terrain state"),
            layout: None,
            vertex: wgpu::VertexState {
                module: &fade_shader,
                entry_point: Some("vs"),
                buffers: &[Some(wgpu::VertexBufferLayout {
                    array_stride: 20,
                    step_mode: wgpu::VertexStepMode::Vertex,
                    attributes: &wgpu::vertex_attr_array![0=>Float32x3,1=>Float32,2=>Float32],
                })],
                compilation_options: Default::default(),
            },
            fragment: Some(wgpu::FragmentState {
                module: &fade_shader,
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
        let fade_scene = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("label state camera"),
            layout: &fade_pipeline.get_bind_group_layout(0),
            entries: &[wgpu::BindGroupEntry {
                binding: 0,
                resource: uniform.as_entire_binding(),
            }],
        });
        let states: Vec<wgpu::Texture> = (0..2)
            .map(|_| {
                device.create_texture(&wgpu::TextureDescriptor {
                    label: Some("label terrain state"),
                    size: wgpu::Extent3d { width: SLOTS as u32, height: 1, depth_or_array_layers: 1 },
                    mip_level_count: 1,
                    sample_count: 1,
                    dimension: wgpu::TextureDimension::D2,
                    format: wgpu::TextureFormat::Rgba8Unorm,
                    usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::TEXTURE_BINDING,
                    view_formats: &[],
                })
            })
            .collect();
        let state_views: Vec<wgpu::TextureView> = states.iter().map(|t| t.create_view(&Default::default())).collect();
        let state_bind = |layout: &wgpu::BindGroupLayout, view: &wgpu::TextureView| {
            device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("label terrain state"),
                layout,
                entries: &[wgpu::BindGroupEntry { binding: 0, resource: wgpu::BindingResource::TextureView(view) }],
            })
        };
        let label_state_binds = state_views.iter().map(|v| state_bind(&pipeline.get_bind_group_layout(2), v)).collect();
        let fade_state_binds = state_views.iter().map(|v| state_bind(&fade_pipeline.get_bind_group_layout(2), v)).collect();
        let fade_points = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("label state anchors"),
            size: (SLOTS * std::mem::size_of::<FadePoint>()) as u64,
            usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        Self {
            width: 1,
            height: 1,
            pixels: vec![0; 4],
            uploaded_bytes: 4,
            pipeline,
            uniform,
            bind,
            draw_bind: atlas_bind.clone(),
            atlas_bind,
            _atlas: atlas,
            vertices,
            count: 0,
            last: None,
            read_slot: 0,
            previous_valid: false,
            previous: None,
            states,
            state_views,
            label_state_binds,
            fade_pipeline,
            fade_scene,
            fade_state_binds,
            fade_depth_binds: Vec::new(),
            fade_points,
            fade_count: 0,
            state_slot: 0,
            last_time: None,
            last_matrix: None,
            settle_until: 0.,
            known: false,
        }
    }
    /// Whether this frame's fade pass updated every label from terrain depth
    /// (a probe of a frame without it could read new labels as hidden).
    pub fn state_known(&self) -> bool {
        self.known
    }
    /// The two label state textures (the probe reads them too).
    pub fn states(&self) -> &[wgpu::Texture] {
        &self.states
    }
    pub fn set_fade_points(&mut self, queue: &wgpu::Queue, points: &[FadePoint]) {
        let n = points.len().min(SLOTS);
        if n > 0 {
            queue.write_buffer(&self.fade_points, 0, bytemuck::cast_slice(&points[..n]));
        }
        self.fade_count = n as u32;
    }
    /// Update every label slot's terrain state from the previous frame's depth
    /// (slot [read_slot]), before the main pass draws the labels with it.
    pub fn encode_fade(&mut self, encoder: &mut wgpu::CommandEncoder, read_slot: usize) -> bool {
        self.known = false;
        let (Some(depth), Some(state)) = (self.fade_depth_binds.get(read_slot), self.fade_state_binds.get(self.state_slot)) else {
            return false;
        };
        if self.fade_count == 0 {
            return false;
        }
        let next = 1 - self.state_slot;
        {
            let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("label terrain state"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: &self.state_views[next],
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
            pass.set_pipeline(&self.fade_pipeline);
            pass.set_bind_group(0, &self.fade_scene, &[]);
            pass.set_bind_group(1, depth, &[]);
            pass.set_bind_group(2, state, &[]);
            pass.set_vertex_buffer(0, self.fade_points.slice(..));
            pass.draw(0..self.fade_count, 0..1);
        }
        self.state_slot = next;
        self.known = self.previous_valid;
        true
    }
    /// The renderer's two alternating depth textures.
    pub fn set_depths(&mut self, device: &wgpu::Device, depths: &[wgpu::Texture]) {
        self.fade_depth_binds = depths.iter().map(|d| self.depth_bind(device, d)).collect();
        self.previous_valid = false;
    }
    /// Before a frame: labels read [read_slot]; [valid] says whether the
    /// previous frame stored its depth there.
    pub fn begin_frame(&mut self, read_slot: usize, valid: bool) {
        self.read_slot = read_slot;
        self.previous_valid = valid;
    }
    fn update_rows(&mut self, queue: &wgpu::Queue, width: u32, height: u32, y0: u32, rows: &[u8]) -> bool {
        let stride = width as usize * 4;
        if width != self.width
            || height != self.height
            || rows.is_empty()
            || rows.len() % stride != 0
            || y0 as usize + rows.len() / stride > height as usize
        {
            return false;
        }
        let n = (rows.len() / stride) as u32;
        queue.write_texture(
            wgpu::TexelCopyTextureInfo {
                texture: &self._atlas,
                mip_level: 0,
                origin: wgpu::Origin3d { x: 0, y: y0, z: 0 },
                aspect: wgpu::TextureAspect::All,
            },
            rows,
            wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(width * 4),
                rows_per_image: Some(n),
            },
            wgpu::Extent3d { width, height: n, depth_or_array_layers: 1 },
        );
        let start = y0 as usize * stride;
        if self.pixels.len() == height as usize * stride {
            self.pixels[start..start + rows.len()].copy_from_slice(rows);
        }
        self.uploaded_bytes += rows.len() as u64;
        true
    }
    fn depth_bind(&self, device: &wgpu::Device, depth: &wgpu::Texture) -> wgpu::BindGroup {
        // Passes (1.0) when the reference depth is <= the terrain depth, i.e.
        // nothing nearer than the raised anchor covers it. Linear filtering:
        // the 2 x 2 texels around the anchor's exact place, weighted, so the
        // result changes smoothly as the view moves (not by whole pixels).
        let compare = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("label occlusion"),
            compare: Some(wgpu::CompareFunction::LessEqual),
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            ..Default::default()
        });
        device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("label terrain depth"),
            layout: &self.fade_pipeline.get_bind_group_layout(1),
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: wgpu::BindingResource::TextureView(&depth.create_view(&Default::default())),
                },
                wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::Sampler(&compare) },
            ],
        })
    }
    pub fn visible(&self) -> bool {
        self.count > 0 && !self.fade_depth_binds.is_empty()
    }
    fn atlas(
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        pipeline: &wgpu::RenderPipeline,
        width: u32,
        height: u32,
        rgba: &[u8],
    ) -> (wgpu::Texture, wgpu::BindGroup) {
        let texture = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("bounded native label atlas"),
            size: wgpu::Extent3d {
                width,
                height,
                depth_or_array_layers: 1,
            },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::Rgba8Unorm,
            usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
            view_formats: &[],
        });
        queue.write_texture(
            wgpu::TexelCopyTextureInfo {
                texture: &texture,
                mip_level: 0,
                origin: wgpu::Origin3d::ZERO,
                aspect: wgpu::TextureAspect::All,
            },
            rgba,
            wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(width * 4),
                rows_per_image: Some(height),
            },
            wgpu::Extent3d {
                width,
                height,
                depth_or_array_layers: 1,
            },
        );
        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            ..Default::default()
        });
        let bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: None,
            layout: &pipeline.get_bind_group_layout(1),
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: wgpu::BindingResource::TextureView(
                        &texture.create_view(&Default::default()),
                    ),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: wgpu::BindingResource::Sampler(&sampler),
                },
            ],
        });
        (texture, bind)
    }
    pub fn set_atlas(
        &mut self,
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        width: u32,
        height: u32,
        rgba: &[u8],
    ) {
        if self.width != width || self.height != height {
            // Atlas UVs change with its size: the placed batch keeps drawing
            // from the old atlas (draw_bind) until the next placement.
            let (texture, bind) = Self::atlas(device, queue, &self.pipeline, width, height, rgba);
            self._atlas = texture;
            self.atlas_bind = bind;
            self.width = width;
            self.height = height;
            self.uploaded_bytes += rgba.len() as u64;
        } else {
            let stride = width as usize * 4;
            let mut row = 0usize;
            while row < height as usize {
                if self.pixels[row * stride..(row + 1) * stride]
                    == rgba[row * stride..(row + 1) * stride]
                {
                    row += 1;
                    continue;
                }
                let first = row;
                row += 1;
                while row < height as usize
                    && self.pixels[row * stride..(row + 1) * stride]
                        != rgba[row * stride..(row + 1) * stride]
                {
                    row += 1;
                }
                let bytes = &rgba[first * stride..row * stride];
                queue.write_texture(
                    wgpu::TexelCopyTextureInfo {
                        texture: &self._atlas,
                        mip_level: 0,
                        origin: wgpu::Origin3d {
                            x: 0,
                            y: first as u32,
                            z: 0,
                        },
                        aspect: wgpu::TextureAspect::All,
                    },
                    bytes,
                    wgpu::TexelCopyBufferLayout {
                        offset: 0,
                        bytes_per_row: Some(width * 4),
                        rows_per_image: Some((row - first) as u32),
                    },
                    wgpu::Extent3d {
                        width,
                        height: (row - first) as u32,
                        depth_or_array_layers: 1,
                    },
                );
                self.uploaded_bytes += bytes.len() as u64;
            }
        }
        self.pixels.clear();
        self.pixels.extend_from_slice(rgba);
        if self.pixels.len() < self.pixels.capacity() / 4 {
            self.pixels.shrink_to_fit();
        }
    }
    pub fn camera(
        &mut self,
        queue: &wgpu::Queue,
        matrix: glam::Mat4,
        anchor: [f64; 2],
        view: &crate::selection::View,
        ppm: f32,
        scale: f32,
        time: f32,
        candidates: bool,
    ) {
        let world = 512. * 2f64.powf(view.zoom);
        let frame = Frame {
            matrix,
            center: view.center,
            world,
            ppm: ppm as f64,
            elevation: view.elevation as f64,
        };
        // Current local coordinates -> previous local coordinates -> previous clip.
        let previous = self.previous.replace(frame).unwrap_or(frame);
        let s = previous.world / world;
        let a = glam::DMat4::from_cols(
            glam::DVec4::new(s, 0., 0., 0.),
            glam::DVec4::new(0., s, 0., 0.),
            glam::DVec4::new(0., 0., previous.ppm / frame.ppm, 0.),
            glam::DVec4::new(
                (view.center[0] - previous.center[0]) * previous.world,
                (previous.center[1] - view.center[1]) * previous.world,
                (frame.elevation - previous.elevation) * previous.ppm,
                1.,
            ),
        );
        let previous_matrix = previous.matrix * a.as_mat4();
        // Up to a quarter second: a slow phone's few frames still settle in time.
        let dt = self.last_time.map_or(0., |t| (time - t).clamp(0., 0.25));
        self.last_time = Some(time);
        let bits = matrix.to_cols_array();
        if self.last_matrix != Some(bits) {
            self.last_matrix = Some(bits);
            self.settle_until = time + SETTLE;
        }
        // Also kept current for unplaced candidates: the visibility probe uses it.
        if self.count == 0 && !candidates {
            return;
        };
        let scene = Scene {
            matrix: matrix.to_cols_array(),
            previous: previous_matrix.to_cols_array(),
            anchor: [
                ((anchor[0] - view.center[0]) * world) as f32,
                ((view.center[1] - anchor[1]) * world) as f32,
                world as f32,
                ppm,
            ],
            size: [view.elevation, view.width as f32, view.height as f32, scale],
            time: [time, if self.previous_valid { 1. } else { 0. }, dt, 0.],
        };
        if self.last != Some(scene) {
            queue.write_buffer(&self.uniform, 0, bytemuck::bytes_of(&scene));
            self.last = Some(scene)
        }
    }
    pub fn draw(&self, pass: &mut wgpu::RenderPass<'_>) {
        if let (true, Some(state)) = (self.count > 0, self.label_state_binds.get(self.state_slot)) {
            pass.set_pipeline(&self.pipeline);
            pass.set_bind_group(0, &self.bind, &[]);
            pass.set_bind_group(1, &self.draw_bind, &[]);
            pass.set_bind_group(2, state, &[]);
            pass.set_vertex_buffer(0, self.vertices.slice(..));
            pass.draw(0..self.count, 0..1);
        }
    }
}
impl crate::Renderer {
    /// Only rows [y0, y0 + n) of an atlas of unchanged size changed (newly drawn
    /// names). False, changing nothing, if the size differs: send the whole atlas.
    pub fn set_label_atlas_rows(
        &mut self,
        width: u32,
        height: u32,
        y0: u32,
        rows: &[u8],
        sprites: Vec<LabelSprite>,
    ) -> bool {
        if !self.labels_gpu.update_rows(&self.queue, width, height, y0, rows) {
            return false;
        }
        self.set_label_metadata(sprites);
        true
    }
    pub fn set_label_atlas(
        &mut self,
        width: u32,
        height: u32,
        rgba: &[u8],
        sprites: Vec<LabelSprite>,
    ) {
        self.labels_gpu
            .set_atlas(&self.device, &self.queue, width, height, rgba);
        self.set_label_metadata(sprites);
    }
    /// New candidates. The labels on screen stay until the matching placement
    /// arrives (select_labels), instead of all vanishing for a round trip.
    pub fn set_label_metadata(&mut self, sprites: Vec<LabelSprite>) {
        // No candidates (zoomed out past every name layer, or names turned
        // off): no placement will follow, so clear now. Keeping the batch
        // "until the next placement" left names on screen when zoomed far out.
        let empty = sprites.is_empty();
        let mut counts: HashMap<((i64, i64), u64), u32> = HashMap::new();
        let idents: Vec<Ident> = sprites
            .iter()
            .map(|s| {
                let k = grid_key(s.point);
                let size = ((s.size[0].to_bits() as u64) << 32) | s.size[1].to_bits() as u64;
                let n = counts.entry((k, size)).or_insert(0);
                let ident = (k.0, k.1, size, *n);
                *n += 1;
                ident
            })
            .collect();
        self.assign_label_slots(&idents);
        self.label_by_ident = idents.iter().enumerate().map(|(i, id)| (*id, i)).collect();
        self.label_idents = idents;
        self.label_sprites = sprites;
        self.label_generation += 1;
        self.label_selection.clear();
        self.labels_stale = !empty;
        if empty {
            self.labels_dirty = true;
        }
    }
    /// Gives each candidate its terrain state slot: the slot it had in the
    /// previous candidates (the same label from a finer or coarser tile, within
    /// ~40 m), so its smoothed visibility carries over; labels still drawn from
    /// the previous candidates keep theirs; others get a free slot, started afresh.
    fn assign_label_slots(&mut self, idents: &[Ident]) {
        let old = std::mem::take(&mut self.label_slots);
        let mut cells: HashMap<(i64, i64), Vec<(Ident, u16)>> = HashMap::new();
        for (id, slot) in &old {
            cells.entry((id.0, id.1)).or_default().push((*id, *slot));
        }
        let mut used = HashSet::new();
        let mut next = HashMap::with_capacity(idents.len());
        let mut slots = vec![-1i32; idents.len()];
        for (i, id) in idents.iter().enumerate() {
            let found = old.get(id).copied().or_else(|| {
                (1..=NEAR_CELLS).find_map(|r| {
                    (-r..=r)
                        .flat_map(|dx| (-r..=r).map(move |dy| (id.0 + dx, id.1 + dy)))
                        .filter(|n| (n.0 - id.0).abs() == r || (n.1 - id.1).abs() == r)
                        .find_map(|n| {
                            cells.get(&n)?.iter().find(|(o, s)| o.2 == id.2 && o.3 == id.3 && !used.contains(s)).map(|(_, s)| *s)
                        })
                })
            });
            if let Some(slot) = found {
                if used.insert(slot) {
                    next.insert(*id, slot);
                    slots[i] = slot as i32;
                }
            }
        }
        for d in &self.label_drawn {
            if !next.contains_key(&d.ident) {
                if let Some(&slot) = old.get(&d.ident) {
                    if used.insert(slot) {
                        next.insert(d.ident, slot);
                    }
                }
            }
        }
        let mut free = (0..SLOTS as u16).filter(|s| !used.contains(s));
        for (i, id) in idents.iter().enumerate() {
            if slots[i] < 0 {
                if let Some(slot) = free.next() {
                    next.insert(*id, slot);
                    slots[i] = slot as i32;
                    self.label_fresh.insert(slot);
                }
            }
        }
        self.label_slots = next;
        self.label_sprite_slots = slots;
        self.fade_points_key = None;
    }
    /// The fade pass's points: every candidate with terrain under it, and the
    /// labels still drawn from earlier candidates. Rebuilt when the candidates or
    /// the drawn labels change, and at most ten times a second as terrain arrives.
    pub(crate) fn update_fade_points(&mut self) {
        let now = self.label_clock.elapsed().as_secs_f32();
        let anchor = self.label_anchor;
        let key = (
            self.label_generation,
            self.label_drawn_revision,
            [anchor[0].to_bits(), anchor[1].to_bits()],
            self.cover_revision,
        );
        if let Some(k) = self.fade_points_key {
            if k == key || (k.0, k.1, k.2) == (key.0, key.1, key.2) && now - self.fade_points_at < 0.1 {
                return;
            }
        }
        let scale = self.map_style.height_scale();
        let mut points = Vec::with_capacity(self.label_sprites.len() + self.label_drawn.len());
        let point = |p: [f64; 2], height: f32, slot: u16, fresh: &HashSet<u16>| FadePoint {
            anchor: [(p[0] - anchor[0]) as f32, (p[1] - anchor[1]) as f32, height * scale],
            slot: slot as f32,
            fresh: if fresh.contains(&slot) { 1. } else { 0. },
        };
        for (i, sprite) in self.label_sprites.iter().enumerate() {
            let Some(&slot) = self.label_sprite_slots.get(i) else { continue };
            if slot < 0 {
                continue;
            }
            let Some(height) = self
                .terrain_height(sprite.point)
                .map(|h| h as f32)
                .or_else(|| near(&self.label_heights, grid_key(sprite.point)))
            else {
                continue;
            };
            points.push(point(sprite.point, height, slot as u16, &self.label_fresh));
        }
        for d in &self.label_drawn {
            if self.label_by_ident.contains_key(&d.ident) {
                continue;
            }
            if let Some(&slot) = self.label_slots.get(&d.ident) {
                let p = [
                    (d.ident.0 as f64) / LABEL_GRID,
                    (d.ident.1 as f64) / LABEL_GRID,
                ];
                points.push(point(p, d.height, slot, &self.label_fresh));
            }
        }
        self.labels_gpu.set_fade_points(&self.queue, &points);
        // New labels or terrain under them: frames keep coming while they settle.
        self.labels_gpu.settle_until = self.labels_gpu.settle_until.max(now + SETTLE);
        self.fade_points_key = Some(key);
        self.fade_points_at = now;
    }
    /// After a fade pass: slots given out afresh now hold their own state.
    pub(crate) fn faded(&mut self) {
        if !self.label_fresh.is_empty() {
            self.label_fresh.clear();
            self.fade_points_key = None;
        }
    }
    pub fn label_texture_upload_bytes(&self) -> u64 {
        self.labels_gpu.uploaded_bytes
    }
    pub fn select_labels(&mut self, selection: Vec<(usize, [f32; 2], f32)>) {
        if self.label_selection != selection || self.labels_stale {
            self.label_selection = selection;
            self.labels_stale = false;
            self.labels_dirty = true;
        }
    }
    pub(crate) fn rebuild_labels(&mut self) {
        if self.labels_stale {
            // Keep the current batch until the new placement arrives.
            self.labels_dirty = false;
            return;
        }
        let now = self.label_clock.elapsed().as_secs_f32();
        // The same name from a finer or coarser source tile has a slightly
        // different point (tile quantization, at most a few metres). Keyed by
        // exact bits it faded in again, so names visibly reloaded while zooming.
        // Points within ~40 m (four ~10 m grid cells) match: a peak from a zoom 8 tile
        // is up to ~40 m from the same peak in a zoom 12 tile (tile precision), and
        // matching only neighbouring cells faded it in again whenever the zoom
        // crossed a tile level, so mountain names flickered while zooming.
        let key = grid_key;
        self.label_anchor = self
            .label_sprites
            .first()
            .map(|s| s.point)
            .unwrap_or([0.5, 0.5]);
        let anchor = self.label_anchor;
        let scale = self.map_style.height_scale();
        let previous: HashMap<Ident, Drawn> = std::mem::take(&mut self.label_drawn).into_iter().map(|d| (d.ident, d)).collect();
        // By place: labels placed last time (their first-shown time) and labels
        // fading out (their fade).
        let mut shown_at: HashMap<(i64, i64), f32> = HashMap::new();
        let mut fading: HashMap<(i64, i64), (f32, f32)> = HashMap::new();
        for d in previous.values() {
            if d.dies >= LIVE {
                shown_at.insert((d.ident.0, d.ident.1), d.born);
            } else if d.dies > now {
                fading.insert((d.ident.0, d.ident.1), (d.born, d.dies));
            }
        }
        let mut drawn = Vec::with_capacity(self.label_selection.len());
        let mut vertices = Vec::with_capacity(self.label_selection.len() * 6);
        let sprites = &self.label_sprites;
        let push = |vertices: &mut Vec<Vertex>, sprite: &LabelSprite, d: &Drawn, slot: i32| {
            let a = [
                (sprite.point[0] - anchor[0]) as f32,
                (sprite.point[1] - anchor[1]) as f32,
                d.height * scale,
            ];
            // A letter of a name along a curve is turned about its centre (screen
            // offsets, y down); every other sprite has no turn.
            let (sin, cos) = d.angle.sin_cos();
            let centre = [d.offset[0] + sprite.size[0] * 0.5, d.offset[1] + sprite.size[1] * 0.5];
            for (x, y) in [(0., 0.), (1., 0.), (0., 1.), (0., 1.), (1., 0.), (1., 1.)] {
                let local = [sprite.size[0] * (x - 0.5), sprite.size[1] * (y - 0.5)];
                vertices.push(Vertex {
                    anchor: a,
                    offset: [centre[0] + local[0] * cos - local[1] * sin, centre[1] + local[0] * sin + local[1] * cos],
                    uv: [sprite.uv[0] + sprite.uv[2] * x, sprite.uv[1] + sprite.uv[3] * y],
                    born: d.born,
                    slot: slot as f32,
                    dies: d.dies,
                });
            }
        };
        let mut placed = HashSet::with_capacity(self.label_selection.len());
        for &(index, offset, angle) in &self.label_selection {
            let Some(sprite) = sprites.get(index) else {
                continue;
            };
            // While terrain under a label is being replaced, keep its last height
            // rather than dropping the label for a frame.
            let Some(height) = self
                .terrain_height(sprite.point)
                .map(|h| h as f32)
                .or_else(|| near(&self.label_heights, key(sprite.point)))
            else {
                continue;
            };
            self.label_heights.insert(key(sprite.point), height);
            let ident = self.label_idents[index];
            // Still on screen (the same name from a finer or coarser tile is within
            // ~40 m): it keeps its first-shown time, so it does not fade in again.
            // Placed again while fading out: back up from the opacity it has now.
            // Otherwise it fades in anew (it popped back when it kept an old time).
            let first = near(&shown_at, key(sprite.point))
                .or_else(|| {
                    near(&fading, key(sprite.point)).map(|(born, dies)| {
                        let shown = ((now - born) / 0.2).clamp(0., 1.).min((dies - now) / FADE_OUT);
                        now - shown * 0.2
                    })
                })
                .unwrap_or(now);
            placed.insert(ident);
            let d = Drawn { ident, height, offset, angle, born: first, dies: LIVE };
            push(&mut vertices, sprite, &d, self.label_sprite_slots[index]);
            drawn.push(d);
        }
        // Dropped by this placement (collisions change as the view moves): fade
        // out instead of vanishing, as MapLibre symbols do. Only labels still among
        // the candidates (their sprite, in the current atlas, draws them).
        for (ident, d) in previous {
            if placed.contains(&ident) {
                continue;
            }
            let dies = if d.dies >= LIVE { now + FADE_OUT } else { d.dies };
            if dies <= now {
                continue;
            }
            let Some(&index) = self.label_by_ident.get(&ident) else {
                continue;
            };
            let d = Drawn { dies, ..d };
            push(&mut vertices, &sprites[index], &d, self.label_sprite_slots[index]);
            drawn.push(d);
        }
        // Labels shown now keep their first-shown time; others fade in anew.
        self.label_fade_until = drawn
            .iter()
            .map(|d| if d.dies < LIVE { d.dies } else { d.born + 0.2 })
            .fold(0., f32::max);
        self.label_drawn = drawn;
        self.label_drawn_revision += 1;
        if self.label_heights.len() > 4096 {
            self.label_heights.clear();
        }
        self.labels_gpu.count = vertices.len() as u32;
        self.labels_gpu.draw_bind = self.labels_gpu.atlas_bind.clone();
        if !vertices.is_empty() {
            let bytes = bytemuck::cast_slice(&vertices);
            if self.labels_gpu.vertices.size() < bytes.len() as u64 {
                self.labels_gpu.vertices = self.device.create_buffer(&wgpu::BufferDescriptor {
                    label: Some("placed label batch capacity"),
                    size: (bytes.len() as u64).next_power_of_two(),
                    usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::COPY_DST,
                    mapped_at_creation: false,
                });
            }
            self.queue.write_buffer(&self.labels_gpu.vertices, 0, bytes);
        }
        self.labels_dirty = false;
    }
}
