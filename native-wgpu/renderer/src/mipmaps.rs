//! Full-resolution level0 is untouched. Both backends average the same four
//! texels; render passes support adapters without compute/storage textures.
const SOURCE: &str = r#"
@group(0) @binding(0) var source:texture_2d<f32>;
fn mean(p:vec2<i32>)->vec4<f32>{
 return (textureLoad(source,p,0)+textureLoad(source,p+vec2<i32>(1,0),0)+textureLoad(source,p+vec2<i32>(0,1),0)+textureLoad(source,p+vec2<i32>(1,1),0))*.25;
}
"#;
enum Pipeline {
    Compute(wgpu::ComputePipeline),
    Render(wgpu::RenderPipeline),
}
pub struct Mipmaps {
    pipeline: Pipeline,
    layout: wgpu::BindGroupLayout,
}
impl Mipmaps {
    pub fn new(device: &wgpu::Device, force_render: bool) -> Self {
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
        Self { pipeline, layout }
    }
    pub fn destination_usage(&self) -> wgpu::TextureUsages {
        match self.pipeline {
            Pipeline::Compute(_) => wgpu::TextureUsages::STORAGE_BINDING,
            Pipeline::Render(_) => wgpu::TextureUsages::RENDER_ATTACHMENT,
        }
    }
    pub fn generate(&self, device: &wgpu::Device, queue: &wgpu::Queue, texture: &wgpu::Texture) {
        let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor {
            label: Some("bounded imagery mip upload"),
        });
        for level in 1..texture.mip_level_count() {
            let view = |mip| {
                texture.create_view(&wgpu::TextureViewDescriptor {
                    base_mip_level: mip,
                    mip_level_count: Some(1),
                    ..Default::default()
                })
            };
            let source = view(level - 1);
            let destination = view(level);
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
                layout: &self.layout,
                entries: &entries,
            });
            match &self.pipeline {
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
        }
        queue.submit([encoder.finish()]);
    }
}
