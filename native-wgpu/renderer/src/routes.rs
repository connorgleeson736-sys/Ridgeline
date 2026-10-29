use wgpu::util::DeviceExt;
#[derive(Clone, PartialEq)]
pub struct RoutePath {
    pub points: Vec<[f64; 2]>,
    pub color: [f32; 4],
    pub selected: Option<usize>,
}
#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
pub(crate) struct Vertex {
    pub start: [f32; 3],
    pub end: [f32; 3],
    pub corner: [f32; 3],
    pub color: [f32; 4],
}
#[repr(C)]
#[derive(Clone, Copy, PartialEq, bytemuck::Pod, bytemuck::Zeroable)]
struct Scene {
    matrix: [f32; 16],
    anchor: [f32; 4],
    size: [f32; 4],
}
pub(crate) struct RouteGpu {
    pub uploads: u64,
    pipeline: wgpu::RenderPipeline,
    uniform: wgpu::Buffer,
    bind: wgpu::BindGroup,
    vertices: wgpu::Buffer,
    count: u32,
    last: Option<Scene>,
}
impl RouteGpu {
    pub fn new(device: &wgpu::Device, format: wgpu::TextureFormat) -> Self {
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("native terrain routes"),
            source: wgpu::ShaderSource::Wgsl(include_str!("routes.wgsl").into()),
        });
        let pipeline=device.create_render_pipeline(&wgpu::RenderPipelineDescriptor{
   label:Some("depth-tested native route and waypoint overlay"),layout:None,
   vertex:wgpu::VertexState{module:&shader,entry_point:Some("vs"),buffers:&[Some(wgpu::VertexBufferLayout{array_stride:52,step_mode:wgpu::VertexStepMode::Vertex,attributes:&wgpu::vertex_attr_array![0=>Float32x3,1=>Float32x3,2=>Float32x3,3=>Float32x4]})],compilation_options:Default::default()},
   fragment:Some(wgpu::FragmentState{module:&shader,entry_point:Some("fs"),targets:&[Some(wgpu::ColorTargetState{format,blend:Some(wgpu::BlendState::PREMULTIPLIED_ALPHA_BLENDING),write_mask:wgpu::ColorWrites::ALL})],compilation_options:Default::default()}),
   primitive:Default::default(),depth_stencil:Some(wgpu::DepthStencilState{format:wgpu::TextureFormat::Depth32Float,depth_write_enabled:Some(false),depth_compare:Some(wgpu::CompareFunction::LessEqual),stencil:Default::default(),bias:wgpu::DepthBiasState{constant:-4,slope_scale:-1.,clamp:0.}}),multisample:Default::default(),multiview_mask:None,cache:None});
        let uniform = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("route camera"),
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
        let vertices = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("empty routes"),
            size: 52,
            usage: wgpu::BufferUsages::VERTEX,
            mapped_at_creation: false,
        });
        Self {
            uploads: 0,
            pipeline,
            uniform,
            bind,
            vertices,
            count: 0,
            last: None,
        }
    }
    pub fn upload(&mut self, device: &wgpu::Device, vertices: &[Vertex]) {
        self.uploads += 1;
        self.count = vertices.len() as u32;
        if !vertices.is_empty() {
            self.vertices = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some("cached draped routes"),
                contents: bytemuck::cast_slice(vertices),
                usage: wgpu::BufferUsages::VERTEX,
            });
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
    ) {
        if self.count == 0 {
            return;
        }
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
            self.last = Some(scene);
        }
    }
    pub fn draw(&self, pass: &mut wgpu::RenderPass<'_>) {
        if self.count > 0 {
            pass.set_pipeline(&self.pipeline);
            pass.set_bind_group(0, &self.bind, &[]);
            pass.set_vertex_buffer(0, self.vertices.slice(..));
            pass.draw(0..self.count, 0..1);
        }
    }
}
impl crate::Renderer {
    pub fn route_uploads(&self) -> u64 {
        self.route_gpu.uploads
    }
    pub fn set_routes(&mut self, paths: Vec<RoutePath>) {
        if self.route_paths != paths {
            self.route_paths = paths;
            self.route_dirty = true;
        }
    }
    pub fn terrain_height(&self, point: [f64; 2]) -> Option<f64> {
        for row in &self.cover {
            let clip = row.region.bounds();
            if point[0] >= clip[0]
                && point[0] <= clip[2]
                && point[1] >= clip[1]
                && point[1] <= clip[3]
            {
                let b = row.source.bounds();
                let size = b[2] - b[0];
                if let Some(h) = self.tiles[&row.source]
                    .field
                    .sample((point[0] - b[0]) / size, (point[1] - b[1]) / size)
                {
                    return Some(h);
                }
            }
        }
        None
    }
    pub(crate) fn rebuild_routes(&mut self) {
        self.route_anchor = self
            .route_paths
            .iter()
            .flat_map(|r| r.points.iter())
            .next()
            .copied()
            .unwrap_or([0.5, 0.5]);
        let anchor = self.route_anchor;
        let mut vertices = Vec::new();
        self.route_waypoints.clear();
        let local = |p: [f64; 3]| {
            [
                (p[0] - anchor[0]) as f32,
                (p[1] - anchor[1]) as f32,
                p[2] as f32,
            ]
        };
        let mut quad = |a: [f64; 3], b: [f64; 3], radius: f32, color: [f32; 4]| {
            for (t, side) in [
                (0., -1.),
                (1., -1.),
                (0., 1.),
                (0., 1.),
                (1., -1.),
                (1., 1.),
            ] {
                vertices.push(Vertex {
                    start: local(a),
                    end: local(b),
                    corner: [t, side, radius],
                    color,
                });
            }
        };
        for (index, path) in self.route_paths.iter().enumerate() {
            for segment in path.points.windows(2) {
                for row in &self.cover {
                    let bounds = row.source.bounds();
                    let span = bounds[2] - bounds[0];
                    let c = row.region.bounds();
                    let convert =
                        |p: [f64; 2]| [(p[0] - bounds[0]) / span, (p[1] - bounds[1]) / span];
                    let clip = [
                        (c[0] - bounds[0]) / span,
                        (c[1] - bounds[1]) / span,
                        (c[2] - bounds[0]) / span,
                        (c[3] - bounds[1]) / span,
                    ];
                    let values = self.tiles[&row.source].field.drape_segment(
                        convert(segment[0]),
                        convert(segment[1]),
                        clip,
                    );
                    for pair in values.windows(2) {
                        let world =
                            |p: [f64; 3]| [bounds[0] + p[0] * span, bounds[1] + p[1] * span, p[2]];
                        quad(world(pair[0]), world(pair[1]), 0., path.color);
                    }
                }
            }
            if index == 0 {
                for (i, &p) in path.points.iter().enumerate() {
                    let point = self.terrain_height(p).map(|h| [p[0], p[1], h]);
                    self.route_waypoints.push(point);
                    if let Some(p) = point {
                        quad(
                            p,
                            p,
                            if path.selected == Some(i) { 9. } else { 5. },
                            if path.selected == Some(i) {
                                [1., 0.62, 0.12, 1.]
                            } else {
                                path.color
                            },
                        );
                    }
                }
            }
        }
        self.route_gpu.upload(&self.device, &vertices);
        self.route_dirty = false;
    }
    pub fn project_waypoints(&self) -> Vec<[f32; 3]> {
        let Some(view) = self.last_view else {
            return vec![];
        };
        let (m, ppm) = crate::camera_matrix(
            view.width,
            view.height,
            view.center,
            view.zoom,
            view.bearing,
            view.pitch,
            view.elevation,
        );
        let world = 512. * 2f64.powf(view.zoom);
        self.route_waypoints
            .iter()
            .map(|p| {
                if let Some(p) = p {
                    let c = m * glam::Vec4::new(
                        ((p[0] - view.center[0]) * world) as f32,
                        ((view.center[1] - p[1]) * world) as f32,
                        (p[2] as f32 + 1. - view.elevation) * ppm,
                        1.,
                    );
                    if c.w > 0. && c.z >= 0. {
                        return [
                            (c.x / c.w + 1.) * 0.5 * view.width as f32,
                            (1. - c.y / c.w) * 0.5 * view.height as f32,
                            1.,
                        ];
                    }
                }
                [0., 0., 0.]
            })
            .collect()
    }
}
