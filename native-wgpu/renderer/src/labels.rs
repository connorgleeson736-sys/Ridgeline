//! One cached native-font atlas and a depth-tested batch, with CPU grid collision selection.
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
}
#[derive(Clone, Copy, PartialEq, bytemuck::Pod, bytemuck::Zeroable)]
#[repr(C)]
struct Scene {
    matrix: [f32; 16],
    anchor: [f32; 4],
    size: [f32; 4],
}
pub(crate) struct LabelGpu {
    width: u32,
    height: u32,
    pixels: Vec<u8>,
    uploaded_bytes: u64,
    pipeline: wgpu::RenderPipeline,
    uniform: wgpu::Buffer,
    bind: wgpu::BindGroup,
    atlas_bind: wgpu::BindGroup,
    _atlas: wgpu::Texture,
    vertices: wgpu::Buffer,
    count: u32,
    last: Option<Scene>,
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
                    array_stride: 28,
                    step_mode: wgpu::VertexStepMode::Vertex,
                    attributes: &wgpu::vertex_attr_array![0=>Float32x3,1=>Float32x2,2=>Float32x2],
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
            // terrain. Terrain occlusion is decided per label from its anchor's
            // line of sight (cartography::project_points), never per pixel, so
            // slopes nearer the camera no longer cut text into the mountain.
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
            size: 96,
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
            size: 28,
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
            atlas_bind,
            _atlas: atlas,
            vertices,
            count: 0,
            last: None,
        }
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
        self.count = 0;
    }
    pub fn camera(
        &mut self,
        queue: &wgpu::Queue,
        matrix: glam::Mat4,
        anchor: [f64; 2],
        view: &crate::selection::View,
        ppm: f32,
        scale: f32,
    ) {
        if self.count == 0 {
            return;
        };
        let world = 512. * 2f64.powf(view.zoom);
        let scene = Scene {
            matrix: matrix.to_cols_array(),
            anchor: [
                ((anchor[0] - view.center[0]) * world) as f32,
                ((view.center[1] - anchor[1]) * world) as f32,
                world as f32,
                ppm,
            ],
            size: [view.elevation, view.width as f32, view.height as f32, scale],
        };
        if self.last != Some(scene) {
            queue.write_buffer(&self.uniform, 0, bytemuck::bytes_of(&scene));
            self.last = Some(scene)
        }
    }
    pub fn draw(&self, pass: &mut wgpu::RenderPass<'_>) {
        if self.count > 0 {
            pass.set_pipeline(&self.pipeline);
            pass.set_bind_group(0, &self.bind, &[]);
            pass.set_bind_group(1, &self.atlas_bind, &[]);
            pass.set_vertex_buffer(0, self.vertices.slice(..));
            pass.draw(0..self.count, 0..1);
        }
    }
}
impl crate::Renderer {
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
    pub fn set_label_metadata(&mut self, sprites: Vec<LabelSprite>) {
        self.label_sprites = sprites;
        self.label_selection.clear();
        self.labels_gpu.count = 0;
        self.labels_dirty = true;
    }
    pub fn label_texture_upload_bytes(&self) -> u64 {
        self.labels_gpu.uploaded_bytes
    }
    pub fn select_labels(&mut self, selection: Vec<(usize, [f32; 2])>) {
        if self.label_selection != selection {
            self.label_selection = selection;
            self.labels_dirty = true;
        }
    }
    pub(crate) fn rebuild_labels(&mut self) {
        self.label_anchor = self
            .label_sprites
            .first()
            .map(|s| s.point)
            .unwrap_or([0.5, 0.5]);
        let anchor = self.label_anchor;
        let mut vertices = Vec::with_capacity(self.label_selection.len() * 6);
        for &(index, offset) in &self.label_selection {
            let Some(sprite) = self.label_sprites.get(index) else {
                continue;
            };
            let Some(height) = self.terrain_height(sprite.point) else {
                continue;
            };
            let a = [
                (sprite.point[0] - anchor[0]) as f32,
                (sprite.point[1] - anchor[1]) as f32,
                (height * self.map_style.height_scale() as f64) as f32,
            ];
            for (x, y) in [(0., 0.), (1., 0.), (0., 1.), (0., 1.), (1., 0.), (1., 1.)] {
                vertices.push(Vertex {
                    anchor: a,
                    offset: [
                        offset[0] + sprite.size[0] * x,
                        offset[1] + sprite.size[1] * y,
                    ],
                    uv: [
                        sprite.uv[0] + sprite.uv[2] * x,
                        sprite.uv[1] + sprite.uv[3] * y,
                    ],
                });
            }
        }
        self.labels_gpu.count = vertices.len() as u32;
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
