//! Native terrain GPU proof. Camera/lighting/feature parity is an open gate.
use glam::{Mat4, Vec3};
pub use ridgeline_core::{TileId, mercator, terrain_rgb};
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use wgpu::util::DeviceExt;
mod adaptive;
mod camera_safety;
mod cover_plans;
mod diagnostics;
mod index_atlas;
mod mipmaps;
mod residency;
mod routes;
mod seams;
mod texture_arrays;
pub use mipmaps::MipmapStats;
pub use routes::RoutePath;
pub mod selection;
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum MipmapMode {
    Auto,
    Render,
    Cpu,
}
#[repr(C)]
#[derive(Clone, Copy, PartialEq, bytemuck::Pod, bytemuck::Zeroable)]
struct Scene {
    matrix: [f32; 16],
    grade: [f32; 4],
    metrics: [f32; 4],
}
#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct Vertex {
    position: [f32; 2],
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TextureBackend {
    Auto,
    Separate,
    Arrays,
}
struct Tile {
    height_bind: Arc<wgpu::BindGroup>,
    array: Option<texture_arrays::LayerLease>,
    _heights: wgpu::Texture,
    used: u64,
    generation: u64,
    field: ridgeline_core::heightfield::Heightfield,
    adaptive_profile: Option<[f64; 2]>,
    adaptive_state: std::cell::Cell<adaptive::State>,
}
struct ImageTile {
    bind: Arc<wgpu::BindGroup>,
    array: Option<texture_arrays::LayerLease>,
    _texture: wgpu::Texture,
    used: u64,
}
pub struct Renderer {
    pub device: wgpu::Device,
    pub queue: wgpu::Queue,
    pub format: wgpu::TextureFormat,
    pipeline: wgpu::RenderPipeline,
    unclipped_pipeline: wgpu::RenderPipeline,
    scene: wgpu::Buffer,
    last_scene: Option<Scene>,
    last_instances: Vec<[f32; 14]>,
    pub scene_uploads: u64,
    pub instance_uploads: u64,
    scene_bind: wgpu::BindGroup,
    texture_layout: wgpu::BindGroupLayout,
    height_layout: wgpu::BindGroupLayout,
    image_arrays: Option<texture_arrays::TextureArrays>,
    height_arrays: Option<texture_arrays::TextureArrays>,
    pub texture_backend: TextureBackend,
    pub texture_creations: u64,
    pub last_frame_draw_calls: u64,
    pub last_frame_texture_binds: u64,
    grid: wgpu::Buffer,
    sampler: wgpu::Sampler,
    mipmaps: mipmaps::Mipmaps,
    indices: wgpu::Buffer,
    instances: wgpu::Buffer,
    tiles: HashMap<TileId, Tile>,
    images: HashMap<TileId, ImageTile>,
    draws: Vec<ridgeline_core::surface::SurfacePatch>,
    index_atlas: index_atlas::IndexAtlas,
    geometry_budget: usize,
    residency_byte_budget: Option<u64>,
    pending_frames: std::sync::Arc<std::sync::atomic::AtomicU32>,
    depth: wgpu::Texture,
    width: u32,
    height: u32,
    route_gpu: routes::RouteGpu,
    route_paths: Vec<RoutePath>,
    route_dirty: bool,
    exact_route_sources: HashSet<TileId>,
    next_adaptive_frame: Option<u64>,
    adaptive_levels: HashMap<TileId, u8>,
    instance_scratch: Vec<[f32; 14]>,
    visible_scratch: Vec<ridgeline_core::surface::SurfacePatch>,
    route_anchor: [f64; 2],
    route_waypoints: Vec<Option<[f64; 3]>>,
    pub frames: u64,
    pub drawn_triangles: u32,
    pub drawn_surface_triangles: u32,
    /// Android camera projection uses physical pixels; skirts use logical zoom.
    pub logical_zoom: Option<f64>,
    cover: Vec<ridgeline_core::CoverRegion>,
    cover_dirty: bool,
    seams_dirty: bool,
    edge_masks: HashMap<TileId, u8>,
    seam_proofs: seams::Proofs,
    cover_plans: cover_plans::CoverPlans,
    demand: Option<(Vec<TileId>, Vec<TileId>)>,
    ideal_demand: Option<(Vec<TileId>, Vec<TileId>)>,
    instance_capacity: usize,
    budget: usize,
    clock: u64,
    center: [f64; 2],
    last_view: Option<selection::View>,
}
impl Renderer {
    pub async fn new(
        instance: &wgpu::Instance,
        surface: Option<&wgpu::Surface<'_>>,
        requested_format: Option<wgpu::TextureFormat>,
        width: u32,
        height: u32,
    ) -> Result<Self, String> {
        Self::new_with_mipmap_mode(
            instance,
            surface,
            requested_format,
            width,
            height,
            MipmapMode::Auto,
        )
        .await
    }
    /// The render path is also exposed for exact backend comparison on the host.
    pub async fn new_with_mipmap_mode(
        instance: &wgpu::Instance,
        surface: Option<&wgpu::Surface<'_>>,
        requested_format: Option<wgpu::TextureFormat>,
        width: u32,
        height: u32,
        mipmap_mode: MipmapMode,
    ) -> Result<Self, String> {
        Self::new_with_texture_backend(
            instance,
            surface,
            requested_format,
            width,
            height,
            mipmap_mode,
            TextureBackend::Auto,
        )
        .await
    }
    pub async fn new_with_texture_backend(
        instance: &wgpu::Instance,
        surface: Option<&wgpu::Surface<'_>>,
        requested_format: Option<wgpu::TextureFormat>,
        width: u32,
        height: u32,
        mipmap_mode: MipmapMode,
        backend: TextureBackend,
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
            .request_device(&wgpu::DeviceDescriptor {
                required_limits: adapter.limits(),
                ..Default::default()
            })
            .await
            .map_err(|e| e.to_string())?;
        let supported = device.limits().max_texture_array_layers >= 8;
        if backend == TextureBackend::Arrays && !supported {
            return Err("Adapter does not support bounded texture arrays".into());
        }
        let arrays = supported
            && (backend == TextureBackend::Arrays
                || (backend == TextureBackend::Auto
                    && adapter.get_info().backend != wgpu::Backend::Gl));
        let source = if arrays {
            include_str!("terrain.wgsl").replace("var photo: texture_2d<f32>","var photo: texture_2d_array<f32>").replace("var heights: texture_2d<f32>","var heights: texture_2d_array<f32>")
            .replace("@location(2) @interpolate(flat) image: vec4<f32>","@location(2) @interpolate(flat) image: vec4<f32>, @location(3) @interpolate(flat) layer:f32")
            .replace("@location(3) image: vec4<f32>)","@location(3) image: vec4<f32>, @location(4) layers:vec2<f32>)")
            .replace("round(vertex*128.)),0)","round(vertex*128.)),i32(layers.y),0)")
            .replace("o.image=image;return","o.image=image;o.layer=layers.x;return")
            .replace("vec2<f32>(1.)),dx,dy)","vec2<f32>(1.)),i32(i.layer),dx,dy)")
        } else {
            include_str!("terrain.wgsl").to_owned()
        };
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("terrain proof"),
            source: wgpu::ShaderSource::Wgsl(source.into()),
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
        let create_pipeline = |entry: &str, layout: Option<&wgpu::PipelineLayout>| {
            device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("terrain"),
            layout,
            vertex: wgpu::VertexState {
                module: &shader,
                entry_point: Some("vs"),
                compilation_options: Default::default(),
                buffers: &[
                    Some(wgpu::VertexBufferLayout {
                        array_stride: 8,
                        step_mode: wgpu::VertexStepMode::Vertex,
                        attributes: &wgpu::vertex_attr_array![0=>Float32x2],
                    }),
                    Some(wgpu::VertexBufferLayout {
                        array_stride: 56,
                        step_mode: wgpu::VertexStepMode::Instance,
                        attributes: &wgpu::vertex_attr_array![1=>Float32x4,2=>Float32x4,3=>Float32x4,4=>Float32x2],
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
                entry_point: Some(entry),
                compilation_options: Default::default(),
                targets: &[Some(wgpu::ColorTargetState {
                    format,
                    blend: Some(wgpu::BlendState::PREMULTIPLIED_ALPHA_BLENDING),
                    write_mask: wgpu::ColorWrites::ALL,
                })],
            }),
            multiview_mask: None,
            cache: None,
        })
        };
        let scene_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("terrain scene"),
            entries: &[wgpu::BindGroupLayoutEntry {
                binding: 0,
                visibility: wgpu::ShaderStages::VERTEX_FRAGMENT,
                ty: wgpu::BindingType::Buffer {
                    ty: wgpu::BufferBindingType::Uniform,
                    has_dynamic_offset: false,
                    min_binding_size: wgpu::BufferSize::new(std::mem::size_of::<Scene>() as u64),
                },
                count: None,
            }],
        });
        let texture_entry = |visibility, filterable| wgpu::BindGroupLayoutEntry {
            binding: 0,
            visibility,
            count: None,
            ty: wgpu::BindingType::Texture {
                sample_type: wgpu::TextureSampleType::Float { filterable },
                view_dimension: if arrays {
                    wgpu::TextureViewDimension::D2Array
                } else {
                    wgpu::TextureViewDimension::D2
                },
                multisampled: false,
            },
        };
        let photo_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("terrain photos"),
            entries: &[
                texture_entry(wgpu::ShaderStages::FRAGMENT, true),
                wgpu::BindGroupLayoutEntry {
                    binding: 1,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                    count: None,
                },
            ],
        });
        let height_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("terrain heights"),
            entries: &[texture_entry(wgpu::ShaderStages::VERTEX, false)],
        });
        // Both programs use explicit compatible layouts, including array pages.
        let shared_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("terrain shared layout"),
            bind_group_layouts: &[
                Some(&scene_layout),
                Some(&photo_layout),
                Some(&height_layout),
            ],
            immediate_size: 0,
        });
        let pipeline = create_pipeline("fs", Some(&shared_layout));
        let unclipped_pipeline = create_pipeline("fs_unclipped", Some(&shared_layout));
        let scene_bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: None,
            layout: &pipeline.get_bind_group_layout(0),
            entries: &[wgpu::BindGroupEntry {
                binding: 0,
                resource: scene.as_entire_binding(),
            }],
        });
        let texture_layout = pipeline.get_bind_group_layout(1);
        let height_layout = pipeline.get_bind_group_layout(2);
        let mut vertices: Vec<Vertex> = (0..129 * 129)
            .map(|i| Vertex {
                position: [(i % 129) as f32 / 128., (i / 129) as f32 / 128.],
            })
            .collect();
        vertices.extend_from_within(..); // Lowered boundary indices share the exact original UVs.
        let grid = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("shared full-detail terrain grid"),
            contents: bytemuck::cast_slice(&vertices),
            usage: wgpu::BufferUsages::VERTEX,
        });
        let anisotropy = if adapter
            .get_downlevel_capabilities()
            .flags
            .contains(wgpu::DownlevelFlags::ANISOTROPIC_FILTERING)
        {
            16
        } else {
            1
        };
        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            address_mode_u: wgpu::AddressMode::ClampToEdge,
            address_mode_v: wgpu::AddressMode::ClampToEdge,
            address_mode_w: wgpu::AddressMode::ClampToEdge,
            anisotropy_clamp: anisotropy,
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            mipmap_filter: wgpu::MipmapFilterMode::Linear,
            ..Default::default()
        });
        let mipmaps = if mipmap_mode == MipmapMode::Cpu {
            mipmaps::Mipmaps::cpu()
        } else {
            mipmaps::Mipmaps::new(
                &device,
                mipmap_mode == MipmapMode::Render,
                adapter.get_info().backend == wgpu::Backend::Gl,
            )
        };
        let image_arrays = arrays.then(|| {
            texture_arrays::TextureArrays::new(
                256,
                256,
                9,
                8,
                192,
                wgpu::TextureFormat::Rgba8Unorm,
                wgpu::TextureUsages::TEXTURE_BINDING
                    | wgpu::TextureUsages::COPY_DST
                    | wgpu::TextureUsages::COPY_SRC
                    | mipmaps.destination_usage(),
            )
        });
        let height_arrays = arrays.then(|| {
            texture_arrays::TextureArrays::new(
                129,
                129,
                1,
                8,
                192,
                wgpu::TextureFormat::R32Float,
                wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
            )
        });
        let idx = ridgeline_core::surface::GridRect {
            x0: 0,
            y0: 0,
            x1: 128,
            y1: 128,
        }
        .indices()
        .into_iter()
        .map(|i| i as u16)
        .collect::<Vec<_>>();
        let indices = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("shared terrain subrect index atlas"),
            size: (9 * 128 * 128 * 6 * 2) as u64,
            usage: wgpu::BufferUsages::INDEX | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        queue.write_buffer(&indices, 0, bytemuck::cast_slice(&idx));
        let mut index_atlas = index_atlas::IndexAtlas::new((indices.size() / 2) as u32);
        index_atlas
            .allocate(
                (
                    ridgeline_core::surface::GridRect {
                        x0: 0,
                        y0: 0,
                        x1: 128,
                        y1: 128,
                    },
                    0,
                    1,
                ),
                idx.len() as u32,
                &HashSet::new(),
            )
            .unwrap();
        let instances = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("tile instances"),
            size: 256 * 56,
            usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let depth = Self::depth(&device, width, height);
        let route_gpu = routes::RouteGpu::new(&device, format);
        let mut renderer = Self {
            device,
            queue,
            format,
            pipeline,
            unclipped_pipeline,
            scene,
            last_scene: None,
            last_instances: Vec::new(),
            scene_uploads: 0,
            instance_uploads: 0,
            scene_bind,
            texture_layout,
            height_layout,
            image_arrays,
            height_arrays,
            texture_backend: if arrays {
                TextureBackend::Arrays
            } else {
                TextureBackend::Separate
            },
            texture_creations: 0,
            last_frame_draw_calls: 0,
            last_frame_texture_binds: 0,
            grid,
            sampler,
            mipmaps,
            indices,
            instances,
            tiles: HashMap::new(),
            images: HashMap::new(),
            draws: Vec::new(),
            index_atlas,
            geometry_budget: 48,
            depth,
            width,
            height,
            route_gpu,
            route_paths: vec![],
            route_dirty: true,
            exact_route_sources: HashSet::new(),
            next_adaptive_frame: None,
            adaptive_levels: HashMap::new(),
            instance_scratch: Vec::new(),
            visible_scratch: Vec::new(),
            route_anchor: [0.5, 0.5],
            route_waypoints: vec![],
            frames: 0,
            drawn_triangles: 0,
            drawn_surface_triangles: 0,
            logical_zoom: None,
            cover: Vec::new(),
            cover_dirty: true,
            seams_dirty: true,
            edge_masks: HashMap::new(),
            seam_proofs: Default::default(),
            cover_plans: Default::default(),
            demand: None,
            ideal_demand: None,
            instance_capacity: 256,
            budget: 96,
            residency_byte_budget: None,
            pending_frames: Default::default(),
            clock: 0,
            center: [0.5, 0.5],
            last_view: None,
        };
        if adapter.get_info().backend == wgpu::Backend::Gl && mipmap_mode == MipmapMode::Auto {
            let gpu = renderer.mipmap_diagnostics()?;
            if gpu.iter().any(|v| v[1] != 0 || v[2] != 255) {
                renderer.mipmaps = mipmaps::Mipmaps::cpu();
                let cpu = renderer.mipmap_diagnostics()?;
                if cpu.iter().any(|v| v[1] != 0 || v[2] != 255) {
                    return Err("No exact full-quality mip backend passed device readback".into());
                }
            }
        }
        if arrays && mipmap_mode == MipmapMode::Auto {
            let failed = renderer
                .array_mipmap_diagnostics()
                .map_or(true, |report| report.iter().any(|v| v[2] != 0));
            if failed {
                renderer.mipmaps = mipmaps::Mipmaps::cpu();
                if renderer
                    .array_mipmap_diagnostics()
                    .map_or(true, |report| report.iter().any(|v| v[2] != 0))
                {
                    if backend == TextureBackend::Auto {
                        drop(renderer);
                        return Box::pin(Self::new_with_texture_backend(
                            instance,
                            surface,
                            requested_format,
                            width,
                            height,
                            mipmap_mode,
                            TextureBackend::Separate,
                        ))
                        .await;
                    }
                    return Err("Array layer/mipmap startup probe failed".into());
                }
            }
        }
        Ok(renderer)
    }
    pub fn uses_cpu_mips(&self) -> bool {
        self.mipmaps.is_cpu()
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
    /// Bounded index memory; pressure uses exact per-frame indices, never a coarser mesh.
    pub fn set_index_cache_budget(&mut self, bytes: u64) {
        let bytes = bytes.clamp(24, 9 * 128 * 128 * 6 * 2) / 4 * 4;
        self.indices = self.device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("bounded terrain index atlas"),
            size: bytes,
            usage: wgpu::BufferUsages::INDEX | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        self.index_atlas = index_atlas::IndexAtlas::new((bytes / 2) as u32);
    }
    pub fn resize(&mut self, w: u32, h: u32) {
        self.width = w.max(1);
        self.height = h.max(1);
        self.depth = Self::depth(&self.device, self.width, self.height);
    }
    pub fn touch_geometry(&mut self, id: TileId) -> bool {
        if let Some(tile) = self.tiles.get_mut(&id) {
            self.clock += 1;
            tile.used = self.clock;
            true
        } else {
            false
        }
    }
    pub fn touch_image(&mut self, id: TileId) -> bool {
        if let Some(tile) = self.images.get_mut(&id) {
            self.clock += 1;
            tile.used = self.clock;
            true
        } else {
            false
        }
    }
    pub fn touch(&mut self, id: TileId) -> bool {
        let g = self.touch_geometry(id);
        let i = self.touch_image(id);
        g && i
    }
    /// Compatibility path for controlled fixtures; production loads sources independently.
    pub fn upload(&mut self, id: TileId, heights: &[f32], rgba: &[u8]) -> Result<(), String> {
        if rgba.len() != 256 * 256 * 4 {
            return Err("Invalid imagery payload".into());
        }
        self.upload_geometry(id, heights)?;
        self.upload_image(id, rgba)
    }
    pub fn upload_geometry(&mut self, id: TileId, heights: &[f32]) -> Result<(), String> {
        self.upload_geometry_owned(id, heights.to_vec())
    }
    pub fn upload_geometry_owned(&mut self, id: TileId, heights: Vec<f32>) -> Result<(), String> {
        if heights.len() != 129 * 129 || heights.iter().any(|h| !h.is_finite()) {
            return Err("Invalid geometry payload".into());
        }
        if self.touch_geometry(id) {
            return Ok(());
        }
        if self.tiles.len() >= 192 {
            self.trim_geometry();
            // A related tile makes safe replacement possible after upload. An
            // unrelated arrival must wait for capacity rather than grow the
            // compatibility backend indefinitely or discard sole coverage.
            if self.tiles.len() >= 192
                && !self
                    .tiles
                    .keys()
                    .any(|old| id.contains(*old) || old.contains(id))
            {
                return Err("Terrain residency full; waiting for fallback capacity".into());
            }
        }
        self.clock += 1;
        // Preserve each f32 height exactly; only the invariant grid is shared.
        let allocation = if let Some(pool) = &mut self.height_arrays {
            Some(pool.allocate(&self.device, &self.height_layout, None)?)
        } else {
            None
        };
        let layer = allocation.as_ref().map_or(0, |slot| slot.layer);
        let heights_texture = if let Some(slot) = &allocation {
            slot.texture()
        } else {
            self.texture_creations += 1;
            self.device.create_texture(&wgpu::TextureDescriptor {
                label: Some("exact 129 terrain heights"),
                size: wgpu::Extent3d {
                    width: 129,
                    height: 129,
                    depth_or_array_layers: 1,
                },
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format: wgpu::TextureFormat::R32Float,
                usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
                view_formats: &[],
            })
        };
        self.queue.write_texture(
            wgpu::TexelCopyTextureInfo {
                texture: &heights_texture,
                mip_level: 0,
                origin: wgpu::Origin3d {
                    x: 0,
                    y: 0,
                    z: layer,
                },
                aspect: wgpu::TextureAspect::All,
            },
            bytemuck::cast_slice(&heights),
            wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(129 * 4),
                rows_per_image: Some(129),
            },
            wgpu::Extent3d {
                width: 129,
                height: 129,
                depth_or_array_layers: 1,
            },
        );
        let height_bind = if let Some(slot) = &allocation {
            slot.bind()
        } else {
            Arc::new(self.device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("terrain height field"),
                layout: &self.height_layout,
                entries: &[wgpu::BindGroupEntry {
                    binding: 0,
                    resource: wgpu::BindingResource::TextureView(
                        &heights_texture.create_view(&Default::default()),
                    ),
                }],
            }))
        };
        self.tiles.insert(
            id,
            Tile {
                height_bind,
                array: allocation,
                _heights: heights_texture,
                adaptive_profile: None,
                adaptive_state: std::cell::Cell::new(adaptive::State::new()),
                used: self.clock,
                generation: self.clock,
                field: ridgeline_core::heightfield::Heightfield::new(
                    128,
                    heights.into_boxed_slice(),
                )
                .map_err(str::to_owned)?,
            },
        );
        self.trim_geometry();
        self.trim_joint();
        self.seams_dirty = true;
        if self.cover.iter().any(|row| row.source == id) {
            self.route_dirty = true;
        }
        self.cover_dirty = true;
        Ok(())
    }
    pub fn upload_image(&mut self, id: TileId, rgba: &[u8]) -> Result<(), String> {
        if rgba.len() != 256 * 256 * 4 {
            return Err("Invalid imagery payload".into());
        }
        if self.touch_image(id) {
            return Ok(());
        }
        if self.images.len() >= 192 {
            self.trim_images();
            if self.images.len() >= 192
                && !self
                    .images
                    .keys()
                    .any(|old| id.contains(*old) || old.contains(id))
            {
                return Err("Imagery residency full; waiting for fallback capacity".into());
            }
        }
        self.clock += 1;
        let allocation = if let Some(pool) = &mut self.image_arrays {
            Some(pool.allocate(&self.device, &self.texture_layout, Some(&self.sampler))?)
        } else {
            None
        };
        let layer = allocation.as_ref().map_or(0, |slot| slot.layer);
        let texture = if let Some(slot) = &allocation {
            slot.texture()
        } else {
            self.texture_creations += 1;
            self.device.create_texture(&wgpu::TextureDescriptor {
                label: Some("actual imagery"),
                size: wgpu::Extent3d {
                    width: 256,
                    height: 256,
                    depth_or_array_layers: 1,
                },
                mip_level_count: 9,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format: wgpu::TextureFormat::Rgba8Unorm,
                usage: wgpu::TextureUsages::TEXTURE_BINDING
                    | wgpu::TextureUsages::COPY_DST
                    | self.mipmaps.destination_usage(),
                view_formats: &[],
            })
        };
        self.queue.write_texture(
            wgpu::TexelCopyTextureInfo {
                texture: &texture,
                mip_level: 0,
                origin: wgpu::Origin3d {
                    x: 0,
                    y: 0,
                    z: layer,
                },
                aspect: wgpu::TextureAspect::All,
            },
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
        self.mipmaps
            .generate_layer(&self.device, &self.queue, &texture, rgba, layer);
        let bind = if let Some(slot) = &allocation {
            slot.bind()
        } else {
            let view = texture.create_view(&Default::default());
            Arc::new(self.device.create_bind_group(&wgpu::BindGroupDescriptor {
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
            }))
        };
        self.images.insert(
            id,
            ImageTile {
                bind,
                array: allocation,
                _texture: texture,
                used: self.clock,
            },
        );
        self.trim_images();
        self.trim_joint();
        self.cover_dirty = true;
        Ok(())
    }
    /// Bounded geometry height fields and original RGBA mip pyramids; excludes surface/depth.
    pub fn set_tile_budget(&mut self, tiles: usize) {
        self.residency_byte_budget = None;
        self.budget = tiles.clamp(24, 192);
        self.geometry_budget = (self.budget / 2).clamp(16, 96);
        self.trim_geometry();
        self.trim_images();
        self.cover_dirty = true;
    }
    /// Shared height textures make retaining three times the previous terrain
    /// source count cheaper than the old duplicated-vertex representation.
    pub fn set_geometry_profile(&mut self, id: TileId, errors: [f64; 2]) {
        if errors.iter().all(|x| x.is_finite() && *x >= 0.) {
            if let Some(tile) = self.tiles.get_mut(&id) {
                tile.adaptive_profile = Some(errors);
            }
        }
    }
    pub fn next_adaptive_frame(&self) -> Option<u64> {
        self.next_adaptive_frame
    }
    /// Memory pressure releases only cache entries outside current demand/cover.
    pub fn release_unused(&mut self) {
        let mut ground: HashSet<_> = self.active_sources(true).into_iter().collect();
        let mut photos: HashSet<_> = self.active_sources(false).into_iter().collect();
        if let Some((g, i)) = &self.demand {
            ground.extend(g.iter().copied());
            photos.extend(i.iter().copied());
        }
        self.tiles.retain(|id, _| ground.contains(id));
        self.images.retain(|id, _| photos.contains(id));
        if let Some(pool) = &mut self.image_arrays {
            pool.trim_empty();
        }
        if let Some(pool) = &mut self.height_arrays {
            pool.trim_empty();
        }
        self.cover_dirty = true;
    }
    pub fn set_geometry_retention(&mut self, tiles: usize) {
        self.geometry_budget = tiles.clamp(16, 192);
        self.trim_geometry();
        self.cover_dirty = true;
    }
    pub fn outstanding_frames(&self) -> u32 {
        self.pending_frames
            .load(std::sync::atomic::Ordering::Acquire)
    }
    pub fn gpu_backlog(&self) -> u32 {
        if self.device.poll(wgpu::PollType::Poll).is_err() {
            return 0;
        }
        self.pending_frames
            .load(std::sync::atomic::Ordering::Acquire)
    }
    pub fn resident_count(&self) -> usize {
        self.tiles.len().max(self.images.len())
    }
    pub fn array_stats(&self) -> [u64; 4] {
        let pools = [self.image_arrays.as_ref(), self.height_arrays.as_ref()];
        [
            pools.iter().flatten().map(|p| p.creations).sum::<u64>() + self.texture_creations,
            pools.iter().flatten().map(|p| p.reuses).sum(),
            pools.iter().flatten().map(|p| p.bytes()).sum(),
            pools.iter().flatten().map(|p| p.page_count() as u64).sum(),
        ]
    }
    pub fn mipmap_stats(&self) -> MipmapStats {
        self.mipmaps.stats()
    }
    pub fn set_mip_scratch_reuse(&self, reuse: bool) {
        self.mipmaps.set_reuse(reuse)
    }
    pub fn index_cache_stats(&self) -> [u64; 5] {
        self.index_atlas.stats()
    }
    pub fn skirt_edge_count(&self) -> u32 {
        self.edge_masks.values().map(|mask| mask.count_ones()).sum()
    }
    pub fn terrain_gpu_payload_bytes(&self) -> u64 {
        self.grid.size() + self.tiles.len() as u64 * 129 * 129 * 4
    }
    pub fn geometry_count(&self) -> usize {
        self.tiles.len()
    }
    pub fn image_count(&self) -> usize {
        self.images.len()
    }
    pub fn set_demand(&mut self, mut geometry: Vec<TileId>, mut images: Vec<TileId>) {
        // Priorities can reorder an unchanged view. Geographic ownership depends
        // only on membership, so that change must not rebuild covers/seams.
        for ids in [&mut geometry, &mut images] {
            ids.sort_unstable_by_key(|id| (id.z, id.x, id.y));
            ids.dedup();
        }
        let next = Some((geometry, images));
        if self.demand != next {
            let (g, i) = next.as_ref().unwrap();
            self.ideal_demand = Some((
                ridgeline_core::finest_sources(g),
                ridgeline_core::finest_sources(i),
            ));
            self.demand = next;
            self.cover_dirty = true;
        }
    }
    /// Controlled render clients may use all resident sources without a plan.
    pub fn clear_demand(&mut self) {
        if self.demand.take().is_some() {
            self.ideal_demand = None;
            self.cover_dirty = true;
        }
    }
    fn active_sources(&self, geometry: bool) -> Vec<TileId> {
        let available: Vec<_> = if geometry {
            self.tiles.keys().copied().collect()
        } else {
            self.images.keys().copied().collect()
        };
        if let Some((g, i)) = &self.ideal_demand {
            ridgeline_core::render_sources(&available, if geometry { g } else { i })
        } else {
            available
        }
    }
    fn rebuild_cover(&mut self) {
        let geometry = self.active_sources(true);
        let images = self.active_sources(false);
        let cover = ridgeline_core::resident_cover(&geometry);
        if self.cover != cover || self.seams_dirty {
            self.edge_masks = seams::masks(&cover, &self.tiles, &mut self.seam_proofs);
            self.seams_dirty = false;
        }
        if self.cover != cover {
            self.route_dirty = true;
            self.cover = cover;
        }
        self.draws = self.cover_plans.get(&geometry, &images);
    }
    fn trim_geometry(&mut self) {
        let mut coarse = self.tiles.keys().filter(|id| id.z <= 12).count();
        while self.tiles.len() > self.geometry_budget {
            // Protect a visible source if removing it would expose an uncovered
            // region. Fine tiles may fall back to an existing real ancestor;
            // a partial parent's unfilled siblings must never disappear.
            let owners: HashSet<_> = if self.demand.is_some() {
                self.active_sources(true).into_iter().collect()
            } else {
                HashSet::new()
            };
            let mut protected = owners.clone();
            if let Some(view) = self.last_view {
                let (matrix, ppm) = camera_matrix(
                    view.width,
                    view.height,
                    view.center,
                    view.zoom,
                    view.bearing,
                    view.pitch,
                    view.elevation,
                );
                let world = 512. * 2f64.powf(view.zoom);
                for row in
                    ridgeline_core::resident_cover(&self.tiles.keys().copied().collect::<Vec<_>>())
                {
                    let mut ancestor = row.source.parent();
                    let mut fallback = false;
                    while let Some(id) = ancestor {
                        if self.tiles.contains_key(&id) {
                            fallback = true;
                            break;
                        }
                        ancestor = id.parent();
                    }
                    if !fallback
                        && Self::visible(
                            &row,
                            &self.tiles[&row.source],
                            matrix,
                            view.center,
                            world,
                            ppm,
                            view.elevation,
                        )
                    {
                        protected.insert(row.source);
                    }
                }
            }
            if let Some((wanted, _)) = &self.demand {
                protected.extend(
                    wanted
                        .iter()
                        .filter(|id| self.tiles.contains_key(id))
                        .copied(),
                );
            }
            let reserve = coarse <= self.geometry_budget / 5
                && self
                    .tiles
                    .keys()
                    .any(|id| id.z > 12 && !protected.contains(id));
            let Some(victim) = self
                .tiles
                .iter()
                .filter(|(id, _)| !protected.contains(id) && !(reserve && id.z <= 12))
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
                .or_else(|| {
                    self.tiles
                        .iter()
                        .filter(|(id, _)| !owners.contains(id))
                        .min_by_key(|(_, tile)| tile.used)
                        .map(|(id, _)| *id)
                })
                .or_else(|| {
                    // Only sacrifice detail if a real resident ancestor can fill it.
                    self.tiles
                        .iter()
                        .filter(|(id, _)| {
                            let mut parent = id.parent();
                            while let Some(p) = parent {
                                if self.tiles.contains_key(&p) {
                                    return true;
                                }
                                parent = p.parent();
                            }
                            false
                        })
                        .min_by_key(|(_, tile)| tile.used)
                        .map(|(id, _)| *id)
                })
            else {
                // Coverage debt is preferable to deleting the last source.
                // Array pools and upload backpressure still bound admission.
                break;
            };
            if victim.z <= 12 {
                coarse = coarse.saturating_sub(1);
            }
            self.tiles.remove(&victim);
            if self.exact_route_sources.contains(&victim) {
                self.route_dirty = true;
            }
        }
    }
    fn trim_images(&mut self) {
        let mut coarse = self.images.keys().filter(|id| id.z <= 12).count();
        while self.images.len() > self.budget {
            let owners: HashSet<_> = if self.demand.is_some() {
                self.active_sources(false).into_iter().collect()
            } else {
                HashSet::new()
            };
            let mut protected = owners.clone();
            if let Some(view) = self.last_view {
                let (matrix, ppm) = camera_matrix(
                    view.width,
                    view.height,
                    view.center,
                    view.zoom,
                    view.bearing,
                    view.pitch,
                    view.elevation,
                );
                let world = 512. * 2f64.powf(view.zoom);
                for row in
                    ridgeline_core::resident_cover(&self.images.keys().copied().collect::<Vec<_>>())
                {
                    let mut ancestor = row.source.parent();
                    let mut fallback = false;
                    while let Some(id) = ancestor {
                        if self.images.contains_key(&id) {
                            fallback = true;
                            break;
                        }
                        ancestor = id.parent();
                    }
                    let b = row.region.bounds();
                    if !fallback
                        && selection::visible_box(
                            matrix,
                            [
                                ((b[0] - view.center[0]) * world) as f32,
                                ((view.center[1] - b[3]) * world) as f32,
                                (-100. - view.elevation) * ppm,
                            ],
                            [
                                ((b[2] - view.center[0]) * world) as f32,
                                ((view.center[1] - b[1]) * world) as f32,
                                (4000. - view.elevation) * ppm,
                            ],
                        )
                    {
                        protected.insert(row.source);
                    }
                }
            }
            if let Some((_, wanted)) = &self.demand {
                protected.extend(
                    wanted
                        .iter()
                        .filter(|id| self.images.contains_key(id))
                        .copied(),
                );
            }
            let reserve = coarse <= self.budget / 5
                && self
                    .images
                    .keys()
                    .any(|id| id.z > 12 && !protected.contains(id));
            let Some(victim) = self
                .images
                .iter()
                .filter(|(id, _)| !protected.contains(id) && !(reserve && id.z <= 12))
                .max_by(|(a, ta), (b, tb)| {
                    let score = |id: &TileId, used: u64| {
                        let q = id.bounds();
                        let size = q[2] - q[0];
                        let dx = ((q[0] + q[2]) / 2. - self.center[0]) / size;
                        let dy = ((q[1] + q[3]) / 2. - self.center[1]) / size;
                        (dx * dx + dy * dy).sqrt().ln_1p() + (self.clock - used) as f64 / 32.
                    };
                    score(a, ta.used).total_cmp(&score(b, tb.used))
                })
                .map(|(id, _)| *id)
                .or_else(|| {
                    self.images
                        .iter()
                        .filter(|(id, _)| !owners.contains(id))
                        .min_by_key(|(_, tile)| tile.used)
                        .map(|(id, _)| *id)
                })
                .or_else(|| {
                    // Only sacrifice detail if a real resident ancestor can fill it.
                    self.images
                        .iter()
                        .filter(|(id, _)| {
                            let mut parent = id.parent();
                            while let Some(p) = parent {
                                if self.images.contains_key(&p) {
                                    return true;
                                }
                                parent = p.parent();
                            }
                            false
                        })
                        .min_by_key(|(_, tile)| tile.used)
                        .map(|(id, _)| *id)
                })
            else {
                // Coverage debt is preferable to deleting the last source.
                // Array pools and upload backpressure still bound admission.
                break;
            };
            if victim.z <= 12 {
                coarse = coarse.saturating_sub(1);
            }
            self.images.remove(&victim);
        }
    }
    fn visible(
        row: &ridgeline_core::CoverRegion,
        tile: &Tile,
        matrix: Mat4,
        center: [f64; 2],
        world: f64,
        ppm: f32,
        elevation: f32,
    ) -> bool {
        let b = row.region.bounds();
        selection::visible_box(
            matrix,
            [
                ((b[0] - center[0]) * world) as f32,
                ((center[1] - b[3]) * world) as f32,
                (tile.field.min - elevation) * ppm,
            ],
            [
                ((b[2] - center[0]) * world) as f32,
                ((center[1] - b[1]) * world) as f32,
                (tile.field.max - elevation) * ppm,
            ],
        )
    }
    pub fn pick_at(&mut self, x: f64, y: f64) -> Option<[f64; 3]> {
        let view = self.last_view?;
        self.pick(&view, x, y)
    }
    /// Closest actual rendered terrain intersection; never reads GPU depth or
    /// invents a plane for missing tiles. Uses the same triangles and ownership.
    pub fn pick(&mut self, view: &selection::View, x: f64, y: f64) -> Option<[f64; 3]> {
        if !x.is_finite() || !y.is_finite() || view.width == 0 || view.height == 0 {
            return None;
        }
        if self.cover_dirty {
            self.rebuild_cover();
            self.cover_dirty = false;
        }
        let (matrix, ppm) = camera_matrix(
            view.width,
            view.height,
            view.center,
            view.zoom,
            view.bearing,
            view.pitch,
            view.elevation,
        );
        let inverse = matrix.inverse();
        let ndc = glam::Vec4::new(
            (x / view.width as f64 * 2. - 1.) as f32,
            (1. - y / view.height as f64 * 2.) as f32,
            0.,
            1.,
        );
        let a = inverse * ndc;
        let b = inverse * glam::Vec4::new(ndc.x, ndc.y, 1., 1.);
        let origin = a.truncate() / a.w;
        let far = b.truncate() / b.w;
        let direction = (far - origin).normalize();
        let world = 512. * 2f64.powf(view.zoom);
        let max_t = (far - origin).length() as f64;
        let mut best: Option<(f64, [f64; 3])> = None;
        for row in &self.draws {
            let bounds = row.geometry.bounds();
            let span = bounds[2] - bounds[0];
            let clip = row.region.bounds();
            let ray = ridgeline_core::heightfield::Ray {
                origin: [
                    (view.center[0] + origin.x as f64 / world - bounds[0]) / span,
                    (view.center[1] - origin.y as f64 / world - bounds[1]) / span,
                    view.elevation as f64 + origin.z as f64 / ppm as f64,
                ],
                direction: [
                    direction.x as f64 / world / span,
                    -direction.y as f64 / world / span,
                    direction.z as f64 / ppm as f64,
                ],
            };
            if let Some(hit) = self.tiles[&row.geometry].field.intersect_region(
                ray,
                [
                    (clip[0] - bounds[0]) / span,
                    (clip[1] - bounds[1]) / span,
                    (clip[2] - bounds[0]) / span,
                    (clip[3] - bounds[1]) / span,
                ],
            ) {
                if hit.t <= max_t && best.as_ref().is_none_or(|(t, _)| hit.t < *t) {
                    let mx = bounds[0] + hit.point[0] * span;
                    let my = bounds[1] + hit.point[1] * span;
                    best = Some((
                        hit.t,
                        [
                            mx * 360. - 180.,
                            (std::f64::consts::PI * (1. - 2. * my))
                                .sinh()
                                .atan()
                                .to_degrees(),
                            hit.point[2],
                        ],
                    ));
                }
            }
        }
        best.map(|(_, point)| point)
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
        if self.cover_dirty {
            self.rebuild_cover();
            self.cover_dirty = false;
        }
        if self.route_dirty {
            self.rebuild_routes();
        }
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
        let route_view = selection::View {
            width: self.width,
            height: self.height,
            center,
            zoom,
            bearing,
            pitch,
            elevation,
        };
        let route_scale = 2f64.powf(zoom - self.logical_zoom.unwrap_or(zoom)) as f32;
        self.route_gpu.camera(
            &self.queue,
            matrix,
            self.route_anchor,
            &route_view,
            px_meter,
            route_scale,
        );
        let scene = Scene {
            matrix: matrix.to_cols_array(),
            grade: [0.058, 1., 0.25, 1. / 0.74],
            metrics: [
                px_meter,
                elevation,
                ridgeline_core::surface::skirt_length(self.logical_zoom.unwrap_or(zoom)) as f32,
                0.,
            ],
        };
        if self.last_scene != Some(scene) {
            self.queue
                .write_buffer(&self.scene, 0, bytemuck::bytes_of(&scene));
            self.last_scene = Some(scene);
            self.scene_uploads += 1;
        }
        self.center = center;
        // The cached cover partitions coarse fallback and fine terrain; each ground
        // location has exactly one owner. No overlapping parent/child depth fighting.
        self.last_view = Some(selection::View {
            width: self.width,
            height: self.height,
            center,
            zoom,
            bearing,
            pitch,
            elevation,
        });
        let mut rows = std::mem::take(&mut self.visible_scratch);
        rows.clear();
        rows.extend(self.draws.iter().copied().filter(|row| {
            Self::visible(
                &ridgeline_core::CoverRegion {
                    source: row.geometry,
                    region: row.region,
                },
                &self.tiles[&row.geometry],
                matrix,
                center,
                world,
                px_meter,
                elevation,
            )
        }));
        let now = std::time::Instant::now();
        self.adaptive_levels.clear();
        self.next_adaptive_frame = None;
        for id in rows.iter().map(|r| r.geometry) {
            if self.adaptive_levels.contains_key(&id) {
                continue;
            }
            let (step, wake) = adaptive::choose(
                &self.tiles[&id],
                id,
                matrix,
                &route_view,
                px_meter,
                now,
                self.exact_route_sources.contains(&id),
            );
            self.adaptive_levels.insert(id, step);
            if let Some(delay) = wake {
                self.next_adaptive_frame =
                    Some(self.next_adaptive_frame.map_or(delay, |old| old.min(delay)));
            }
        }
        // Group all compatible instances, rather than only accidental
        // neighbours in quadtree order. Surface ownership remains disjoint.
        if self.image_arrays.is_some() && self.height_arrays.is_some() {
            rows.sort_by_cached_key(|row| {
                let rect = ridgeline_core::surface::GridRect::for_region(row.geometry, row.region);
                let mask = ridgeline_core::surface::skirt_edges(row.geometry_region, row.region)
                    & self
                        .edge_masks
                        .get(&row.geometry_region)
                        .copied()
                        .unwrap_or(15);
                (
                    row.region.z - row.geometry.z > 7,
                    Arc::as_ptr(&self.images[&row.image].bind) as usize,
                    Arc::as_ptr(&self.tiles[&row.geometry].height_bind) as usize,
                    rect.x0,
                    rect.y0,
                    rect.x1,
                    rect.y1,
                    mask,
                    rect.adaptive_step(self.adaptive_levels[&row.geometry]),
                )
            });
        }
        let mut data = std::mem::take(&mut self.instance_scratch);
        data.clear();
        data.extend(rows.iter().map(|row| {
            let b = row.geometry.bounds();
            let region = row.region.bounds();
            let size = b[2] - b[0];
            let image = row.image.bounds();
            let image_size = image[2] - image[0];
            [
                ((b[0] - center[0]) * world) as f32,
                ((center[1] - b[1]) * world) as f32,
                (size * world) as f32,
                0.,
                ((region[0] - b[0]) / size) as f32,
                ((region[1] - b[1]) / size) as f32,
                ((region[2] - b[0]) / size) as f32,
                ((region[3] - b[1]) / size) as f32,
                (size / image_size) as f32,
                (size / image_size) as f32,
                ((b[0] - image[0]) / image_size) as f32,
                ((b[1] - image[1]) / image_size) as f32,
                self.images[&row.image]
                    .array
                    .as_ref()
                    .map_or(0., |a| a.layer as f32),
                self.tiles[&row.geometry]
                    .array
                    .as_ref()
                    .map_or(0., |a| a.layer as f32),
            ]
        }));
        if data.len() > self.instance_capacity {
            self.instance_capacity = data.len().next_power_of_two();
            self.instances = self.device.create_buffer(&wgpu::BufferDescriptor {
                label: Some("tile instances"),
                size: (self.instance_capacity * 56) as u64,
                usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::COPY_DST,
                mapped_at_creation: false,
            });
        }
        if data != self.last_instances {
            if !data.is_empty() {
                self.queue
                    .write_buffer(&self.instances, 0, bytemuck::cast_slice(&data));
                self.instance_uploads += 1;
            }
            std::mem::swap(&mut self.last_instances, &mut data);
        }
        self.instance_scratch = data;
        let rects: Vec<_> = rows
            .iter()
            .map(|row| {
                (
                    ridgeline_core::surface::GridRect::for_region(row.geometry, row.region),
                    ridgeline_core::surface::skirt_edges(row.geometry_region, row.region)
                        & self
                            .edge_masks
                            .get(&row.geometry_region)
                            .copied()
                            .unwrap_or(15),
                    ridgeline_core::surface::GridRect::for_region(row.geometry, row.region)
                        .adaptive_step(self.adaptive_levels[&row.geometry]),
                )
            })
            .collect();
        let unique: HashSet<_> = rects.iter().copied().collect();
        self.index_atlas.begin_frame(&unique);
        let mut overflow = HashMap::new();
        for rect in &rects {
            if self.index_atlas.get(rect).is_none() && !overflow.contains_key(rect) {
                let ids: Vec<u16> = rect
                    .0
                    .adaptive_indices(rect.2, rect.1)
                    .into_iter()
                    .map(|i| i as u16)
                    .collect();
                if let Some((start, _end)) =
                    self.index_atlas.allocate(*rect, ids.len() as u32, &unique)
                {
                    // Queue writes execute with the next submission, after prior
                    // submitted draws. No current-frame range can be evicted.
                    self.queue.write_buffer(
                        &self.indices,
                        start as u64 * 2,
                        bytemuck::cast_slice(&ids),
                    );
                } else {
                    // A single frame may exceed the atlas: retain its exact indices.
                    // Buffers live through this submission and are bounded by visible cover.
                    let buffer =
                        self.device
                            .create_buffer_init(&wgpu::util::BufferInitDescriptor {
                                label: Some("exact visible index overflow"),
                                contents: bytemuck::cast_slice(&ids),
                                usage: wgpu::BufferUsages::INDEX,
                            });
                    overflow.insert(*rect, buffer);
                }
            }
        }
        self.drawn_surface_triangles = rects
            .iter()
            .map(|(r, _, s)| r.adaptive_surface_triangles(*s))
            .sum();
        self.drawn_triangles = rects
            .iter()
            .map(|rect| {
                self.index_atlas
                    .get(rect)
                    .map_or_else(|| (overflow[rect].size() / 2) as u32, |(a, b)| b - a)
                    / 3
            })
            .sum();
        self.last_frame_draw_calls = 0;
        self.last_frame_texture_binds = 0;
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
            let mut clipped_pipeline = None;
            pass.set_bind_group(0, &self.scene_bind, &[]);
            pass.set_index_buffer(self.indices.slice(..), wgpu::IndexFormat::Uint16);
            let mut atlas_bound = true;
            pass.set_vertex_buffer(0, self.grid.slice(..));
            pass.set_vertex_buffer(1, self.instances.slice(..));
            let mut last_geometry = None;
            let mut last_image = None;
            let mut i = 0;
            while i < rows.len() {
                let row = rows[i];
                let clipped = row.region.z - row.geometry.z > 7;
                if clipped_pipeline != Some(clipped) {
                    pass.set_pipeline(if clipped {
                        &self.pipeline
                    } else {
                        &self.unclipped_pipeline
                    });
                    clipped_pipeline = Some(clipped);
                }
                let image_binding = Arc::as_ptr(&self.images[&row.image].bind);
                let geometry_binding = Arc::as_ptr(&self.tiles[&row.geometry].height_bind);
                if last_image != Some(image_binding) {
                    pass.set_bind_group(1, self.images[&row.image].bind.as_ref(), &[]);
                    last_image = Some(image_binding);
                    self.last_frame_texture_binds += 1;
                }
                if last_geometry != Some(geometry_binding) {
                    pass.set_bind_group(2, self.tiles[&row.geometry].height_bind.as_ref(), &[]);
                    last_geometry = Some(geometry_binding);
                    self.last_frame_texture_binds += 1;
                }
                let mut end_instance = i + 1;
                while end_instance < rows.len()
                    && (rows[end_instance].region.z - rows[end_instance].geometry.z > 7) == clipped
                    && Arc::ptr_eq(
                        &self.tiles[&rows[end_instance].geometry].height_bind,
                        &self.tiles[&row.geometry].height_bind,
                    )
                    && Arc::ptr_eq(
                        &self.images[&rows[end_instance].image].bind,
                        &self.images[&row.image].bind,
                    )
                    && rects[end_instance] == rects[i]
                {
                    end_instance += 1;
                }
                if let Some((start, end)) = self.index_atlas.get(&rects[i]) {
                    if !atlas_bound {
                        pass.set_index_buffer(self.indices.slice(..), wgpu::IndexFormat::Uint16);
                        atlas_bound = true;
                    }
                    pass.draw_indexed(start..end, 0, i as u32..end_instance as u32);
                } else {
                    let buffer = &overflow[&rects[i]];
                    pass.set_index_buffer(buffer.slice(..), wgpu::IndexFormat::Uint16);
                    atlas_bound = false;
                    pass.draw_indexed(
                        0..(buffer.size() / 2) as u32,
                        0,
                        i as u32..end_instance as u32,
                    );
                }
                self.last_frame_draw_calls += 1;
                i = end_instance;
            }
            self.route_gpu.draw(&mut pass);
        }
        self.pending_frames
            .fetch_add(1, std::sync::atomic::Ordering::Release);
        self.queue.submit([encoder.finish()]);
        let pending = self.pending_frames.clone();
        self.queue.on_submitted_work_done(move || {
            pending.fetch_sub(1, std::sync::atomic::Ordering::Release);
        });
        // Cache age follows actual use, including panning back to already-loaded sources.
        self.clock += 1;
        for row in &rows {
            if let Some(tile) = self.tiles.get_mut(&row.geometry) {
                tile.used = self.clock;
            }
            if let Some(image) = self.images.get_mut(&row.image) {
                image.used = self.clock;
            }
        }
        self.visible_scratch = rows;
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
