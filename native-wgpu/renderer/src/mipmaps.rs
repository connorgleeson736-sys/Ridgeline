//! Generate the reference's trilinear imagery pyramid on the GPU. The complete
//! original 256px level remains unchanged; encoded elevations never enter here.
pub struct Mipmaps {
    pipeline: wgpu::ComputePipeline,
    layout: wgpu::BindGroupLayout,
}
impl Mipmaps {
    pub fn new(device: &wgpu::Device) -> Self {
        let shader=device.create_shader_module(wgpu::ShaderModuleDescriptor{label:Some("imagery mipmaps"),source:wgpu::ShaderSource::Wgsl(r#"
@group(0) @binding(0) var source:texture_2d<f32>;
@group(0) @binding(1) var destination:texture_storage_2d<rgba8unorm,write>;
@compute @workgroup_size(8,8) fn main(@builtin(global_invocation_id) id:vec3<u32>){
 let size=textureDimensions(destination);
 if(any(id.xy>=size)){return;}
 let p=vec2<i32>(id.xy*2u);
 let color=(textureLoad(source,p,0)+textureLoad(source,p+vec2<i32>(1,0),0)+textureLoad(source,p+vec2<i32>(0,1),0)+textureLoad(source,p+vec2<i32>(1,1),0))*.25;
 textureStore(destination,vec2<i32>(id.xy),color);
}
"#.into())});
        let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("imagery downsample"),
            layout: None,
            module: &shader,
            entry_point: Some("main"),
            compilation_options: Default::default(),
            cache: None,
        });
        let layout = pipeline.get_bind_group_layout(0);
        Self { pipeline, layout }
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
            let bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: None,
                layout: &self.layout,
                entries: &[
                    wgpu::BindGroupEntry {
                        binding: 0,
                        resource: wgpu::BindingResource::TextureView(&source),
                    },
                    wgpu::BindGroupEntry {
                        binding: 1,
                        resource: wgpu::BindingResource::TextureView(&destination),
                    },
                ],
            });
            let mut pass = encoder.begin_compute_pass(&wgpu::ComputePassDescriptor {
                label: Some("imagery mip level"),
                timestamp_writes: None,
            });
            pass.set_pipeline(&self.pipeline);
            pass.set_bind_group(0, &bind, &[]);
            let size = (texture.width() >> level).max(1);
            pass.dispatch_workgroups(size.div_ceil(8), size.div_ceil(8), 1);
        }
        queue.submit([encoder.finish()]);
    }
}
