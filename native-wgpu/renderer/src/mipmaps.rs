//! Full-resolution level0 is untouched. All paths average the same four texels;
//! startup-validated CPU generation supports drivers with broken GPU mip writes.
const SOURCE: &str = r#"
@group(0) @binding(0) var source:texture_2d<f32>;
fn mean(p:vec2<i32>)->vec4<f32>{
 return (textureLoad(source,p,0)+textureLoad(source,p+vec2<i32>(1,0),0)+textureLoad(source,p+vec2<i32>(0,1),0)+textureLoad(source,p+vec2<i32>(1,1),0))*.25;
}
"#;
use std::cell::{Cell, RefCell};
struct Scratch {
    key: (u32, u32, u32),
    textures: Vec<wgpu::Texture>,
    views: Vec<wgpu::TextureView>,
    binds: Vec<wgpu::BindGroup>,
    base: Option<(wgpu::Texture, wgpu::BindGroup)>,
}
#[derive(Clone, Copy, Debug, Default)]
pub struct MipmapStats {
    pub scratch_textures: u64,
    pub bind_groups: u64,
    pub submissions: u64,
    pub pooled_bytes: u64,
}
enum Pipeline {
    Cpu,
    Compute(wgpu::ComputePipeline),
    Render(wgpu::RenderPipeline),
}
pub struct Mipmaps {
    pipeline: Pipeline,
    layout: Option<wgpu::BindGroupLayout>,
    separate_levels: bool,
    reuse: Cell<bool>,
    scratch: RefCell<Option<Scratch>>,
    stats: Cell<MipmapStats>,
}
impl Mipmaps {
    pub fn cpu() -> Self {
        Self {
            pipeline: Pipeline::Cpu,
            layout: None,
            separate_levels: false,
            reuse: Cell::new(true),
            scratch: RefCell::new(None),
            stats: Cell::new(MipmapStats::default()),
        }
    }
    pub fn is_cpu(&self) -> bool {
        matches!(self.pipeline, Pipeline::Cpu)
    }
    fn generate_cpu(queue: &wgpu::Queue, texture: &wgpu::Texture, rgba: &[u8], layer: u32) {
        let mut previous = std::borrow::Cow::Borrowed(rgba);
        let mut width = texture.width() as usize;
        for level in 1..texture.mip_level_count() {
            let next_width = (width / 2).max(1);
            let mut next = vec![0u8; next_width * next_width * 4];
            for y in 0..next_width {
                for x in 0..next_width {
                    for c in 0..4 {
                        let a = previous[(y * 2 * width + x * 2) * 4 + c] as f32 / 255.;
                        let b = previous[(y * 2 * width + x * 2 + 1) * 4 + c] as f32 / 255.;
                        let d = previous[((y * 2 + 1) * width + x * 2) * 4 + c] as f32 / 255.;
                        let e = previous[((y * 2 + 1) * width + x * 2 + 1) * 4 + c] as f32 / 255.;
                        next[(y * next_width + x) * 4 + c] = (((a + b + d + e) * 0.25) * 255.)
                            .round_ties_even()
                            .clamp(0., 255.)
                            as u8;
                    }
                }
            }
            queue.write_texture(
                wgpu::TexelCopyTextureInfo {
                    texture,
                    mip_level: level,
                    origin: wgpu::Origin3d {
                        x: 0,
                        y: 0,
                        z: layer,
                    },
                    aspect: wgpu::TextureAspect::All,
                },
                &next,
                wgpu::TexelCopyBufferLayout {
                    offset: 0,
                    bytes_per_row: Some(next_width as u32 * 4),
                    rows_per_image: Some(next_width as u32),
                },
                wgpu::Extent3d {
                    width: next_width as u32,
                    height: next_width as u32,
                    depth_or_array_layers: 1,
                },
            );
            previous = std::borrow::Cow::Owned(next);
            width = next_width;
        }
    }
    pub fn new(device: &wgpu::Device, force_render: bool, separate_levels: bool) -> Self {
        let l = device.limits();
        let compute = !force_render
            && l.max_compute_workgroups_per_dimension >= 16
            && l.max_compute_workgroup_size_x >= 8
            && l.max_compute_workgroup_size_y >= 8
            && l.max_compute_invocations_per_workgroup >= 64
            && l.max_storage_textures_per_shader_stage >= 1;
        let code = if compute {
            r#"
@group(0) @binding(1) var destination:texture_storage_2d<rgba8unorm,write>;
@compute @workgroup_size(8,8) fn main(@builtin(global_invocation_id) id:vec3<u32>){
 if(any(id.xy>=textureDimensions(destination))){return;}
 textureStore(destination,vec2<i32>(id.xy),mean(vec2<i32>(id.xy*2u)));
}
"#
        } else {
            r#"
@vertex fn vs(@builtin(vertex_index) i:u32)->@builtin(position) vec4<f32>{
 let p=array<vec2<f32>,3>(vec2<f32>(-1.,-1.),vec2<f32>(3.,-1.),vec2<f32>(-1.,3.));
 return vec4<f32>(p[i],0.,1.);
}
@fragment fn fs(@builtin(position) p:vec4<f32>)->@location(0) vec4<f32>{return mean(vec2<i32>(p.xy)*2);}
"#
        };
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("exact imagery mipmaps"),
            source: wgpu::ShaderSource::Wgsl(format!("{SOURCE}{code}").into()),
        });
        let (pipeline, layout) = if compute {
            let p = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                label: Some("compute imagery downsample"),
                layout: None,
                module: &shader,
                entry_point: Some("main"),
                compilation_options: Default::default(),
                cache: None,
            });
            let layout = p.get_bind_group_layout(0);
            (Pipeline::Compute(p), layout)
        } else {
            let p = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
                label: Some("render imagery downsample"),
                layout: None,
                vertex: wgpu::VertexState {
                    module: &shader,
                    entry_point: Some("vs"),
                    buffers: &[],
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
                primitive: Default::default(),
                depth_stencil: None,
                multisample: Default::default(),
                multiview_mask: None,
                cache: None,
            });
            let layout = p.get_bind_group_layout(0);
            (Pipeline::Render(p), layout)
        };
        Self {
            pipeline,
            layout: Some(layout),
            separate_levels,
            reuse: Cell::new(true),
            scratch: RefCell::new(None),
            stats: Cell::new(MipmapStats::default()),
        }
    }
    pub fn destination_usage(&self) -> wgpu::TextureUsages {
        if self.separate_levels {
            wgpu::TextureUsages::empty()
        } else {
            self.target_usage()
        }
    }
    fn target_usage(&self) -> wgpu::TextureUsages {
        match self.pipeline {
            Pipeline::Cpu => wgpu::TextureUsages::empty(),
            Pipeline::Compute(_) => wgpu::TextureUsages::STORAGE_BINDING,
            Pipeline::Render(_) => wgpu::TextureUsages::RENDER_ATTACHMENT,
        }
    }
    pub fn stats(&self) -> MipmapStats {
        self.stats.get()
    }
    pub fn set_reuse(&self, reuse: bool) {
        self.reuse.set(reuse);
        if !reuse {
            self.scratch.borrow_mut().take();
            let mut stats = self.stats.get();
            stats.pooled_bytes = 0;
            self.stats.set(stats);
        }
    }
    fn bind(
        &self,
        device: &wgpu::Device,
        source: &wgpu::TextureView,
        destination: &wgpu::TextureView,
    ) -> wgpu::BindGroup {
        let mut entries = vec![wgpu::BindGroupEntry {
            binding: 0,
            resource: wgpu::BindingResource::TextureView(source),
        }];
        if matches!(self.pipeline, Pipeline::Compute(_)) {
            entries.push(wgpu::BindGroupEntry {
                binding: 1,
                resource: wgpu::BindingResource::TextureView(destination),
            });
        }
        let mut stats = self.stats.get();
        stats.bind_groups += 1;
        self.stats.set(stats);
        device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("reusable mip bind"),
            layout: self.layout.as_ref().unwrap(),
            entries: &entries,
        })
    }
    fn make_scratch(&self, device: &wgpu::Device, texture: &wgpu::Texture) -> Scratch {
        let mut textures = Vec::new();
        let mut views = Vec::new();
        let mut bytes = 0;
        for level in 1..texture.mip_level_count() {
            let width = (texture.width() >> level).max(1);
            let height = (texture.height() >> level).max(1);
            bytes += width as u64 * height as u64 * 4;
            let target = device.create_texture(&wgpu::TextureDescriptor {
                label: Some("pooled non-aliasing mip"),
                size: wgpu::Extent3d {
                    width,
                    height,
                    depth_or_array_layers: 1,
                },
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format: wgpu::TextureFormat::Rgba8Unorm,
                usage: wgpu::TextureUsages::COPY_SRC
                    | wgpu::TextureUsages::TEXTURE_BINDING
                    | self.target_usage(),
                view_formats: &[],
            });
            views.push(target.create_view(&Default::default()));
            textures.push(target);
        }
        let binds = (1..views.len())
            .map(|i| self.bind(device, &views[i - 1], &views[i]))
            .collect();
        let mut stats = self.stats.get();
        stats.scratch_textures += textures.len() as u64;
        stats.pooled_bytes = bytes;
        self.stats.set(stats);
        Scratch {
            key: (texture.width(), texture.height(), texture.mip_level_count()),
            textures,
            views,
            binds,
            base: None,
        }
    }
    pub fn generate(
        &self,
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        texture: &wgpu::Texture,
        rgba: &[u8],
    ) {
        self.generate_layer(device, queue, texture, rgba, 0)
    }
    pub fn generate_layer(
        &self,
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        texture: &wgpu::Texture,
        rgba: &[u8],
        layer: u32,
    ) {
        if self.separate_levels && texture.size().depth_or_array_layers > 1 && !self.reuse.get() {
            Self::generate_cpu(queue, texture, rgba, layer);
            return;
        }
        if !self.separate_levels || !self.reuse.get() || self.is_cpu() {
            self.generate_fresh(device, queue, texture, rgba, layer);
            return;
        }
        let mut pool = self.scratch.borrow_mut();
        let key = (texture.width(), texture.height(), texture.mip_level_count());
        if pool.as_ref().is_none_or(|s| s.key != key) {
            *pool = Some(self.make_scratch(device, texture));
        }
        let array = texture.size().depth_or_array_layers > 1;
        let scratch = pool.as_mut().unwrap();
        if scratch.views.is_empty() {
            return;
        }
        if array && scratch.base.is_none() {
            let base = device.create_texture(&wgpu::TextureDescriptor {
                label: Some("array mip base staging"),
                size: wgpu::Extent3d {
                    width: texture.width(),
                    height: texture.height(),
                    depth_or_array_layers: 1,
                },
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format: wgpu::TextureFormat::Rgba8Unorm,
                usage: wgpu::TextureUsages::COPY_DST | wgpu::TextureUsages::TEXTURE_BINDING,
                view_formats: &[],
            });
            let bind = self.bind(
                device,
                &base.create_view(&Default::default()),
                &scratch.views[0],
            );
            let mut stats = self.stats.get();
            stats.scratch_textures += 1;
            stats.pooled_bytes += texture.width() as u64 * texture.height() as u64 * 4;
            self.stats.set(stats);
            scratch.base = Some((base, bind));
        }
        let first_bind = if array {
            None
        } else {
            let source = texture.create_view(&wgpu::TextureViewDescriptor {
                base_mip_level: 0,
                mip_level_count: Some(1),
                dimension: Some(wgpu::TextureViewDimension::D2),
                ..Default::default()
            });
            Some(self.bind(device, &source, &scratch.views[0]))
        };
        let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor {
            label: Some("pooled exact mip chain"),
        });
        if array {
            encoder.copy_texture_to_texture(
                wgpu::TexelCopyTextureInfo {
                    texture,
                    mip_level: 0,
                    origin: wgpu::Origin3d {
                        x: 0,
                        y: 0,
                        z: layer,
                    },
                    aspect: wgpu::TextureAspect::All,
                },
                scratch.base.as_ref().unwrap().0.as_image_copy(),
                scratch.base.as_ref().unwrap().0.size(),
            );
        }
        for (i, target) in scratch.textures.iter().enumerate() {
            let bind = if i == 0 {
                first_bind
                    .as_ref()
                    .unwrap_or_else(|| &scratch.base.as_ref().unwrap().1)
            } else {
                &scratch.binds[i - 1]
            };
            match &self.pipeline {
                Pipeline::Cpu => unreachable!(),
                Pipeline::Compute(pipeline) => {
                    let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                        label: Some("pooled mip compute"),
                        timestamp_writes: None,
                    });
                    pass.set_pipeline(pipeline);
                    pass.set_bind_group(0, bind, &[]);
                    pass.dispatch_workgroups(
                        target.width().div_ceil(8),
                        target.height().div_ceil(8),
                        1,
                    );
                }
                Pipeline::Render(pipeline) => {
                    let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                        label: Some("pooled mip render"),
                        color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                            view: &scratch.views[i],
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
                    pass.set_pipeline(pipeline);
                    pass.set_bind_group(0, bind, &[]);
                    pass.draw(0..3, 0..1);
                }
            }
            encoder.copy_texture_to_texture(
                target.as_image_copy(),
                wgpu::TexelCopyTextureInfo {
                    texture,
                    mip_level: i as u32 + 1,
                    origin: wgpu::Origin3d {
                        x: 0,
                        y: 0,
                        z: layer,
                    },
                    aspect: wgpu::TextureAspect::All,
                },
                target.size(),
            );
        }
        // All uses share one queue: copies consuming a scratch level precede
        // any subsequent image's writes. No CPU wait or fence readback is needed.
        let mut stats = self.stats.get();
        stats.submissions += 1;
        self.stats.set(stats);
        queue.submit([encoder.finish()]);
    }
    fn generate_fresh(
        &self,
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        texture: &wgpu::Texture,
        rgba: &[u8],
        layer: u32,
    ) {
        if self.is_cpu() {
            Self::generate_cpu(queue, texture, rgba, layer);
            return;
        }
        let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor {
            label: Some("bounded imagery mip upload"),
        });
        let mut levels: Vec<wgpu::Texture> = Vec::new();
        for level in 1..texture.mip_level_count() {
            let view = |mip| {
                texture.create_view(&wgpu::TextureViewDescriptor {
                    base_mip_level: mip,
                    base_array_layer: layer,
                    array_layer_count: Some(1),
                    dimension: Some(wgpu::TextureViewDimension::D2),
                    mip_level_count: Some(1),
                    ..Default::default()
                })
            };
            // GLES texture views can alias base/max mip state. Single-level
            // intermediates keep textureLoad(0) exact and avoid framebuffer feedback.
            // Vulkan keeps the original direct-to-mip path with no extra copies.
            let intermediate = self.separate_levels.then(|| {
                let mut stats = self.stats.get();
                stats.scratch_textures += 1;
                self.stats.set(stats);
                device.create_texture(&wgpu::TextureDescriptor {
                    label: Some("non-aliasing imagery mip"),
                    size: wgpu::Extent3d {
                        width: (texture.width() >> level).max(1),
                        height: (texture.height() >> level).max(1),
                        depth_or_array_layers: 1,
                    },
                    mip_level_count: 1,
                    sample_count: 1,
                    dimension: wgpu::TextureDimension::D2,
                    format: wgpu::TextureFormat::Rgba8Unorm,
                    usage: wgpu::TextureUsages::COPY_SRC
                        | wgpu::TextureUsages::TEXTURE_BINDING
                        | self.target_usage(),
                    view_formats: &[],
                })
            });
            let source = if let Some(previous) = levels.last() {
                previous.create_view(&Default::default())
            } else {
                view(level - 1)
            };
            let destination = if let Some(target) = &intermediate {
                target.create_view(&Default::default())
            } else {
                view(level)
            };
            let mut entries = vec![wgpu::BindGroupEntry {
                binding: 0,
                resource: wgpu::BindingResource::TextureView(&source),
            }];
            if matches!(self.pipeline, Pipeline::Compute(_)) {
                entries.push(wgpu::BindGroupEntry {
                    binding: 1,
                    resource: wgpu::BindingResource::TextureView(&destination),
                });
            }
            let mut stats = self.stats.get();
            stats.bind_groups += 1;
            self.stats.set(stats);
            let bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: None,
                layout: self.layout.as_ref().expect("GPU mip layout"),
                entries: &entries,
            });
            match &self.pipeline {
                Pipeline::Cpu => unreachable!(),
                Pipeline::Compute(pipeline) => {
                    let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                        label: Some("imagery mip level"),
                        timestamp_writes: None,
                    });
                    pass.set_pipeline(pipeline);
                    pass.set_bind_group(0, &bind, &[]);
                    let size = (texture.width() >> level).max(1);
                    pass.dispatch_workgroups(size.div_ceil(8), size.div_ceil(8), 1);
                }
                Pipeline::Render(pipeline) => {
                    let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                        label: Some("imagery render mip level"),
                        color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                            view: &destination,
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
                    pass.set_pipeline(pipeline);
                    pass.set_bind_group(0, &bind, &[]);
                    pass.draw(0..3, 0..1);
                }
            }
            if let Some(target) = intermediate {
                encoder.copy_texture_to_texture(
                    target.as_image_copy(),
                    wgpu::TexelCopyTextureInfo {
                        texture,
                        mip_level: level,
                        origin: wgpu::Origin3d {
                            x: 0,
                            y: 0,
                            z: layer,
                        },
                        aspect: wgpu::TextureAspect::All,
                    },
                    target.size(),
                );
                levels.push(target);
            }
        }
        let mut stats = self.stats.get();
        stats.submissions += 1;
        self.stats.set(stats);
        queue.submit([encoder.finish()]);
    }
}
