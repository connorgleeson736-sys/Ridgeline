//! Lower render resolution while the camera moves (dynamic resolution).
//! The scene is drawn into a smaller offscreen target with the same camera,
//! then stretched onto the surface. Geometry, routes and labels are placed in
//! normalised device coordinates, so only sharpness changes, never layout.
//! Still frames always render at full resolution.

pub(crate) struct Offscreen {
    pub texture: wgpu::Texture,
    pub bind: wgpu::BindGroup,
}

/// A depth pair (and offscreen colour) for one render scale, kept so the
/// switch at the start and end of each gesture allocates nothing.
pub(crate) struct Parked {
    pub scale: f32,
    pub size: (u32, u32),
    pub depth: wgpu::Texture,
    pub depth_previous: wgpu::Texture,
    pub offscreen: Option<Offscreen>,
}

pub(crate) struct Blit {
    pipeline: wgpu::RenderPipeline,
    layout: wgpu::BindGroupLayout,
    sampler: wgpu::Sampler,
    format: wgpu::TextureFormat,
}

const SHADER: &str = "
@group(0) @binding(0) var source:texture_2d<f32>;
@group(0) @binding(1) var source_sampler:sampler;
struct Out{@builtin(position) position:vec4<f32>,@location(0) uv:vec2<f32>};
@vertex fn vs(@builtin(vertex_index) v:u32)->Out{
 let p=vec2<f32>(f32((v<<1u)&2u),f32(v&2u));
 var o:Out;o.position=vec4<f32>(p*2.-1.,0.,1.);o.uv=vec2<f32>(p.x,1.-p.y);return o;
}
@fragment fn fs(i:Out)->@location(0) vec4<f32>{return textureSampleLevel(source,source_sampler,i.uv,0.);}
";

/// Scaled size, at least one pixel.
pub(crate) fn scaled(width: u32, height: u32, scale: f32) -> (u32, u32) {
    if scale >= 1. {
        return (width.max(1), height.max(1));
    }
    (
        ((width as f32 * scale).round() as u32).max(1),
        ((height as f32 * scale).round() as u32).max(1),
    )
}

/// Render scales are quantised so tiny changes never reallocate.
pub(crate) fn quantise(scale: f32) -> f32 {
    if !scale.is_finite() {
        return 1.;
    }
    ((scale.clamp(0.5, 1.) * 20.).round() / 20.).clamp(0.5, 1.)
}

impl Blit {
    pub fn new(device: &wgpu::Device, format: wgpu::TextureFormat) -> Self {
        let module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("resolution blit"),
            source: wgpu::ShaderSource::Wgsl(SHADER.into()),
        });
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("resolution blit"),
            entries: &[
                wgpu::BindGroupLayoutEntry {
                    binding: 0,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Texture {
                        sample_type: wgpu::TextureSampleType::Float { filterable: true },
                        view_dimension: wgpu::TextureViewDimension::D2,
                        multisampled: false,
                    },
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 1,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                    count: None,
                },
            ],
        });
        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("resolution blit"),
            bind_group_layouts: &[Some(&layout)],
            immediate_size: 0,
        });
        let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("resolution blit"),
            layout: Some(&pipeline_layout),
            vertex: wgpu::VertexState {
                module: &module,
                entry_point: Some("vs"),
                compilation_options: Default::default(),
                buffers: &[],
            },
            fragment: Some(wgpu::FragmentState {
                module: &module,
                entry_point: Some("fs"),
                compilation_options: Default::default(),
                targets: &[Some(wgpu::ColorTargetState {
                    format,
                    blend: None,
                    write_mask: wgpu::ColorWrites::ALL,
                })],
            }),
            primitive: Default::default(),
            depth_stencil: None,
            multisample: Default::default(),
            multiview_mask: None,
            cache: None,
        });
        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            ..Default::default()
        });
        Self {
            pipeline,
            layout,
            sampler,
            format,
        }
    }

    pub fn offscreen(&self, device: &wgpu::Device, size: (u32, u32)) -> Offscreen {
        let texture = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("reduced resolution scene"),
            size: wgpu::Extent3d {
                width: size.0,
                height: size.1,
                depth_or_array_layers: 1,
            },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: self.format,
            usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::TEXTURE_BINDING,
            view_formats: &[],
        });
        let bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("resolution blit"),
            layout: &self.layout,
            entries: &[
                wgpu::BindGroupEntry {
                    binding: 0,
                    resource: wgpu::BindingResource::TextureView(
                        &texture.create_view(&Default::default()),
                    ),
                },
                wgpu::BindGroupEntry {
                    binding: 1,
                    resource: wgpu::BindingResource::Sampler(&self.sampler),
                },
            ],
        });
        Offscreen { texture, bind }
    }

    /// Stretch the reduced scene over the whole surface.
    pub fn draw(&self, encoder: &mut wgpu::CommandEncoder, source: &Offscreen, target: &wgpu::TextureView) {
        let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
            label: Some("resolution blit"),
            color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                view: target,
                depth_slice: None,
                resolve_target: None,
                ops: wgpu::Operations {
                    load: wgpu::LoadOp::Clear(wgpu::Color::BLACK),
                    store: wgpu::StoreOp::Store,
                },
            })],
            depth_stencil_attachment: None,
            timestamp_writes: None,
            occlusion_query_set: None,
            multiview_mask: None,
        });
        pass.set_pipeline(&self.pipeline);
        pass.set_bind_group(0, &source.bind, &[]);
        pass.draw(0..3, 0..1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn scales_are_quantised_and_bounded() {
        assert_eq!(quantise(0.74), 0.75);
        assert_eq!(quantise(0.2), 0.5);
        assert_eq!(quantise(f32::NAN), 1.);
        assert_eq!(quantise(1.3), 1.);
        assert_eq!(scaled(720, 1560, 0.75), (540, 1170));
        assert_eq!(scaled(720, 1560, 1.), (720, 1560));
    }
}
