//! The default "clear sky": a gradient sampled from the reference 3D viewer's
//! sky, drawn behind the terrain. A sky colour with zero alpha selects it.
use bytemuck::{Pod, Zeroable};
#[repr(C)]
#[derive(Clone, Copy, PartialEq, Pod, Zeroable)]
struct Uniform {
    params: [f32; 4],
    stops: [[f32; 4]; 7],
}
/// (elevation above the horizon in degrees, sRGB) from horizon to zenith side.
const STOPS: [(f32, [u8; 3]); 7] = [
    (0.0, [217, 242, 255]),
    (0.4, [207, 238, 255]),
    (1.9, [186, 224, 255]),
    (4.3, [157, 206, 255]),
    (6.7, [137, 192, 255]),
    (9.1, [122, 184, 254]),
    (12.0, [120, 181, 254]),
];
/// The horizon colour: also the clear colour below the horizon.
pub const HORIZON: [u8; 3] = STOPS[0].1;
pub fn gradient(sky: u32) -> bool {
    sky >> 24 == 0
}
pub(crate) struct SkyGpu {
    pipeline: wgpu::RenderPipeline,
    uniform: wgpu::Buffer,
    bind: wgpu::BindGroup,
    last: Option<Uniform>,
}
impl SkyGpu {
    pub fn new(device: &wgpu::Device, format: wgpu::TextureFormat) -> Self {
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("clear sky"),
            source: wgpu::ShaderSource::Wgsl(include_str!("sky.wgsl").into()),
        });
        let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("clear sky gradient"),
            layout: None,
            vertex: wgpu::VertexState { module: &shader, entry_point: Some("vs"), buffers: &[], compilation_options: Default::default() },
            fragment: Some(wgpu::FragmentState {
                module: &shader,
                entry_point: Some("fs"),
                targets: &[Some(wgpu::ColorTargetState { format, blend: None, write_mask: wgpu::ColorWrites::ALL })],
                compilation_options: Default::default(),
            }),
            primitive: Default::default(),
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
            label: Some("clear sky"),
            size: std::mem::size_of::<Uniform>() as u64,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("clear sky"),
            layout: &pipeline.get_bind_group_layout(0),
            entries: &[wgpu::BindGroupEntry { binding: 0, resource: uniform.as_entire_binding() }],
        });
        Self { pipeline, uniform, bind, last: None }
    }
    /// Whether any sky is above the screen's top edge at this [pitch] (degrees).
    pub fn visible(pitch: f32) -> bool {
        pitch - 90. + (FOV_DEGREES / 2.) > -1.
    }
    pub fn draw(&mut self, queue: &wgpu::Queue, pass: &mut wgpu::RenderPass<'_>, pitch: f32) {
        let mut stops = [[0f32; 4]; 7];
        for (i, (e, c)) in STOPS.iter().enumerate() {
            stops[i] = [c[0] as f32 / 255., c[1] as f32 / 255., c[2] as f32 / 255., *e];
        }
        let u = Uniform { params: [pitch - 90., (FOV_DEGREES / 2.).to_radians().tan(), 0., 0.], stops };
        if self.last != Some(u) {
            queue.write_buffer(&self.uniform, 0, bytemuck::bytes_of(&u));
            self.last = Some(u);
        }
        pass.set_pipeline(&self.pipeline);
        pass.set_bind_group(0, &self.bind, &[]);
        pass.draw(0..3, 0..1);
    }
}
/// The camera's vertical field of view (as `camera_matrix`).
pub const FOV_DEGREES: f32 = 36.8698976;
