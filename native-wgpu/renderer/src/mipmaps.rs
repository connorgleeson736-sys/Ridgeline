//! Full-resolution level0 is untouched. All paths average the same four texels;
//! startup-validated CPU generation supports drivers with broken GPU mip writes.
const SOURCE: &str = r#"
@group(0) @binding(0) var source:texture_2d<f32>;
fn mean(p:vec2<i32>)->vec4<f32>{
 return (textureLoad(source,p,0)+textureLoad(source,p+vec2<i32>(1,0),0)+textureLoad(source,p+vec2<i32>(0,1),0)+textureLoad(source,p+vec2<i32>(1,1),0))*.25;
}
"#;
enum Pipeline {
    Cpu,
    Compute(wgpu::ComputePipeline),
    Render(wgpu::RenderPipeline),
}
pub struct Mipmaps {
    pipeline: Pipeline,
    layout: Option<wgpu::BindGroupLayout>,
    separate_levels: bool,
}
impl Mipmaps {
    pub fn cpu() -> Self {
        Self {
            pipeline: Pipeline::Cpu,
            layout: None,
            separate_levels: false,
        }
    }
    pub fn is_cpu(&self) -> bool {
        matches!(self.pipeline, Pipeline::Cpu)
    }
    fn generate_cpu(queue: &wgpu::Queue, texture: &wgpu::Texture, rgba: &[u8]) {
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
                    origin: wgpu::Origin3d::ZERO,
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
    pub fn generate(
        &self,
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        texture: &wgpu::Texture,
        rgba: &[u8],
    ) {
        if self.is_cpu() {
            Self::generate_cpu(queue, texture, rgba);
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
                    mip_level_count: Some(1),
                    ..Default::default()
                })
            };
            // GLES texture views can alias base/max mip state. Single-level
            // intermediates keep textureLoad(0) exact and avoid framebuffer feedback.
            // Vulkan keeps the original direct-to-mip path with no extra copies.
            let intermediate = self.separate_levels.then(|| {
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
                        origin: wgpu::Origin3d::ZERO,
                        aspect: wgpu::TextureAspect::All,
                    },
                    target.size(),
                );
                levels.push(target);
            }
        }
        queue.submit([encoder.finish()]);
    }
}
