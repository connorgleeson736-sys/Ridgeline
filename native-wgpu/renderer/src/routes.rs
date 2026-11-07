use crate::TileId;
use std::sync::Arc;

#[cfg(test)]
mod additional_tests {
    use super::*;
    fn read(
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        source: &wgpu::Buffer,
        bytes: u64,
    ) -> Vec<u8> {
        let out = device.create_buffer(&wgpu::BufferDescriptor {
            label: None,
            size: bytes,
            usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
            mapped_at_creation: false,
        });
        let mut encoder = device.create_command_encoder(&Default::default());
        encoder.copy_buffer_to_buffer(source, 0, &out, 0, bytes);
        queue.submit([encoder.finish()]);
        let (tx, rx) = std::sync::mpsc::channel();
        out.slice(..).map_async(wgpu::MapMode::Read, move |r| {
            tx.send(r).unwrap();
        });
        device.poll(wgpu::PollType::wait_indefinitely()).unwrap();
        rx.recv().unwrap().unwrap();
        let data = out.slice(..).get_mapped_range().unwrap().to_vec();
        out.unmap();
        data
    }
    #[test]
    fn additional_coherent_incremental_overlay_gpu() {
        for backend in [wgpu::Backends::VULKAN, wgpu::Backends::GL] {
            let mut desc = wgpu::InstanceDescriptor::new_without_display_handle();
            desc.backends = backend;
            desc.flags.remove(wgpu::InstanceFlags::DEBUG);
            let instance = wgpu::Instance::new(desc);
            let renderer = pollster::block_on(crate::Renderer::new(
                &instance,
                None,
                Some(wgpu::TextureFormat::Rgba8Unorm),
                64,
                64,
            ))
            .unwrap();
            let mut gpu = RouteGpu::new(&renderer.device, renderer.format);
            let vertex = Vertex {
                start: [0.; 3],
                end: [1.; 3],
                corner: [0., 0., -1.5],
                color: [1.; 4],
                style: [0.; 3],
            };
            let parts: Vec<Arc<[Vertex]>> = (0..640)
                .map(|i| {
                    let mut v = vertex;
                    v.start[0] = i as f32;
                    vec![v; 500].into()
                })
                .collect();
            gpu.stage_fragments(parts.clone());
            assert!(!gpu.advance_fragments(
                &renderer.device,
                &renderer.queue,
                &crate::frame_work::Budget::new(0)
            ));
            assert_eq!(gpu.count, 0);
            assert_eq!(gpu.uploaded_bytes, 0);
            let mut complete = false;
            for _ in 0..1000 {
                if gpu.advance_fragments(
                    &renderer.device,
                    &renderer.queue,
                    &crate::frame_work::Budget::with_idle(4_000_000, true),
                ) {
                    complete = true;
                    break;
                }
                assert_eq!(gpu.count, 0, "partial new generation must not draw");
                std::thread::sleep(std::time::Duration::from_millis(1));
            }
            assert!(complete);
            let mut next = parts.clone();
            for path in 0..20 {
                let mut v = vertex;
                v.start[0] = 10000. + path as f32;
                next[path * 32 + 3] = vec![v; 500].into();
            }
            let before = gpu.uploaded_bytes;
            gpu.stage_fragments(next.clone());
            let mut done = false;
            for _ in 0..1000 {
                if gpu.advance_fragments(
                    &renderer.device,
                    &renderer.queue,
                    &crate::frame_work::Budget::with_idle(4_000_000, true),
                ) {
                    done = true;
                    break;
                }
                std::thread::sleep(std::time::Duration::from_millis(1));
            }
            assert!(done);
            assert_eq!(
                gpu.uploaded_bytes - before,
                640000,
                "only20 changed fragment ranges cross the GPU boundary"
            );
            let expected: Vec<_> = next.iter().flat_map(|p| p.iter().copied()).collect();
            assert_eq!(
                read(
                    &renderer.device,
                    &renderer.queue,
                    &gpu.vertices,
                    expected.len() as u64 * 64
                ),
                bytemuck::cast_slice::<_, u8>(&expected)
            );
            // A larger equal-layout delta exceeds the atomic host-write cap.
            // Copy the acknowledged front on-device, then stage only dirty ranges.
            let mut large = next.clone();
            for path in 0..20 {
                for patch in [4, 5] {
                    let mut v = vertex;
                    v.start[0] = 20000. + (path * 32 + patch) as f32;
                    large[path * 32 + patch] = vec![v; 500].into();
                }
            }
            let before = gpu.uploaded_bytes;
            let copied = gpu.copied_bytes;
            gpu.stage_fragments(large.clone());
            let mut done = false;
            for _ in 0..1000 {
                if gpu.advance_fragments(
                    &renderer.device,
                    &renderer.queue,
                    &crate::frame_work::Budget::with_idle(4_000_000, true),
                ) {
                    done = true;
                    break;
                }
                assert_eq!(gpu.count, 320000);
                std::thread::sleep(std::time::Duration::from_millis(1));
            }
            assert!(done);
            assert_eq!(gpu.uploaded_bytes - before, 1_280_000);
            assert_eq!(gpu.copied_bytes - copied, 20_480_000);
            let expected: Vec<_> = large.iter().flat_map(|p| p.iter().copied()).collect();
            assert_eq!(
                read(
                    &renderer.device,
                    &renderer.queue,
                    &gpu.vertices,
                    expected.len() as u64 * 64
                ),
                bytemuck::cast_slice::<_, u8>(&expected)
            );
            next = large;
            // Start a reshaped replacement, then supersede it. The next delta
            // must still compare against the last committed GPU generation.
            gpu.stage_fragments(vec![vec![vertex; 320001].into()]);
            assert!(!gpu.advance_fragments(
                &renderer.device,
                &renderer.queue,
                &crate::frame_work::Budget::new(300000)
            ));
            assert_eq!(gpu.count, 320000);
            gpu.stage_fragments(next.clone());
            let before = gpu.uploaded_bytes;
            assert!(gpu.advance_fragments(
                &renderer.device,
                &renderer.queue,
                &crate::frame_work::Budget::new(4_000_000)
            ));
            assert_eq!(gpu.uploaded_bytes, before);
            println!(
                "PASS {backend:?}:20,480,000 ->640,000 host/GPU bytes; exact ordered GPU stream; zero-budget/partial/canceled generations safe"
            );
        }
    }
}
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
    pub style: [f32; 3],
}
#[repr(C)]
#[derive(Clone, Copy, PartialEq, bytemuck::Pod, bytemuck::Zeroable)]
struct Scene {
    matrix: [f32; 16],
    anchor: [f32; 4],
    size: [f32; 4],
}
struct PendingFragments {
    parts: Vec<Arc<[Vertex]>>,
    target: Option<wgpu::Buffer>,
    part: usize,
    vertex: usize,
    delta: bool,
    since: std::time::Instant,
}
pub(crate) struct RouteGpu {
    fragments: Vec<Arc<[Vertex]>>,
    pending_fragments: Option<PendingFragments>,
    upload_ns_per_byte: f64,
    allocation_ns_per_byte: f64,
    spare: Option<wgpu::Buffer>,
    pub maintenance_allocations: u64,
    pub uploads: u64,
    pipeline: wgpu::RenderPipeline,
    uniform: wgpu::Buffer,
    bind: wgpu::BindGroup,
    vertices: wgpu::Buffer,
    count: u32,
    last: Option<Scene>,
    expanded: bool,
    pub uploaded_bytes: u64,
    pub copied_bytes: u64,
}
impl RouteGpu {
    /// Snapshot references, not a flattened replacement. Comparison is against
    /// the acknowledged GPU layout, never an unapplied worker revision.
    pub(crate) fn stage_fragments(&mut self, parts: Vec<Arc<[Vertex]>>) {
        self.cancel_staging();
        self.pending_fragments = Some(PendingFragments {
            parts,
            target: None,
            part: 0,
            vertex: 0,
            delta: false,
            since: std::time::Instant::now(),
        });
    }
    pub(crate) fn cancel_staging(&mut self) {
        if let Some(target) = self.pending_fragments.take().and_then(|p| p.target) {
            if self
                .spare
                .as_ref()
                .is_none_or(|old| old.size() < target.size())
            {
                self.spare = Some(target);
            }
        }
    }
    fn write_part(&self, queue: &wgpu::Queue, target: &wgpu::Buffer, offset: u64, part: &[Vertex]) {
        if self.expanded {
            let expanded: Vec<_> = part
                .iter()
                .flat_map(|v| {
                    [
                        (0., -1.),
                        (1., -1.),
                        (0., 1.),
                        (0., 1.),
                        (1., -1.),
                        (1., 1.),
                    ]
                    .map(|(x, y)| {
                        let mut v = *v;
                        v.corner[0] = x;
                        v.corner[1] = y;
                        v
                    })
                })
                .collect();
            queue.write_buffer(target, offset, bytemuck::cast_slice(&expanded));
        } else {
            queue.write_buffer(target, offset, bytemuck::cast_slice(part));
        }
    }
    /// Small equal-layout deltas update in one atomic frame. Larger/reshaped
    /// results use bounded writes into an inactive buffer, publishing only when
    /// complete. wgpu retires the previous buffer after its submitted uses.
    pub(crate) fn advance_fragments(
        &mut self,
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        budget: &crate::frame_work::Budget,
    ) -> bool {
        let Some(mut p) = self.pending_fragments.take() else {
            return false;
        };
        let stride = 64 * if self.expanded { 6 } else { 1 };
        let count: usize = p.parts.iter().map(|v| v.len()).sum();
        if count == 0 {
            self.upload(device, queue, &[]);
            return true;
        }
        let same_layout = self.fragments.len() == p.parts.len()
            && self
                .fragments
                .iter()
                .zip(&p.parts)
                .all(|(a, b)| a.len() == b.len());
        let same = p.target.is_none() && same_layout;
        let dirty = if same {
            self.fragments
                .iter()
                .zip(&p.parts)
                .filter(|(a, b)| !Arc::ptr_eq(a, b))
                .map(|(_, b)| b.len() * stride)
                .sum::<usize>()
        } else {
            usize::MAX
        };
        if same
            && dirty <= if self.expanded { 65536 } else { 650 * 1024 }
            && budget.allows(100_000 + (dirty as f64 * self.upload_ns_per_byte) as u64)
        {
            let began = std::time::Instant::now();
            let mut offset = 0;
            for (a, b) in self.fragments.iter().zip(&p.parts) {
                if !Arc::ptr_eq(a, b) && !b.is_empty() {
                    self.write_part(queue, &self.vertices, offset, b);
                }
                offset += (b.len() * stride) as u64;
            }
            if dirty > 0 {
                self.upload_ns_per_byte = (self.upload_ns_per_byte * 0.8
                    + began.elapsed().as_nanos() as f64 / dirty as f64 * 0.2)
                    .clamp(1., 100.);
            }
            self.uploaded_bytes += dirty as u64;
            self.fragments = p.parts;
            self.uploads += 1;
            return true;
        }
        let total = (count * stride) as u64;
        let delta = same_layout && dirty <= (count * stride) / 2;
        let copy_cost = if delta {
            (total as f64 * 0.1) as u64
        } else {
            0
        };
        let has_spare = self.spare.as_ref().is_some_and(|b| b.size() >= total);
        let allocation = if has_spare {
            0
        } else {
            (total as f64 * self.allocation_ns_per_byte) as u64
        };
        let predicted = 200_000 + copy_cost + allocation;
        let maintenance = p.since.elapsed().as_millis() >= 250 && budget.maintenance();
        if p.target.is_none()
            && total <= device.limits().max_buffer_size
            && (budget.allows(predicted) || maintenance)
        {
            if !budget.allows(predicted) {
                self.maintenance_allocations += 1;
            }
            p.target = if has_spare {
                self.spare.take()
            } else {
                let began = std::time::Instant::now();
                let target = device.create_buffer(&wgpu::BufferDescriptor {
                    label: Some("inactive coherent overlay update"),
                    size: total
                        .next_power_of_two()
                        .max(64)
                        .min(device.limits().max_buffer_size),
                    usage: wgpu::BufferUsages::VERTEX
                        | wgpu::BufferUsages::COPY_DST
                        | wgpu::BufferUsages::COPY_SRC,
                    mapped_at_creation: false,
                });
                self.allocation_ns_per_byte = (self.allocation_ns_per_byte * 0.5
                    + began.elapsed().as_nanos() as f64 / total as f64 * 0.5)
                    .clamp(0.01, 100.);
                Some(target)
            };
            if delta {
                let mut encoder = device.create_command_encoder(&Default::default());
                encoder.copy_buffer_to_buffer(
                    &self.vertices,
                    0,
                    p.target.as_ref().unwrap(),
                    0,
                    total,
                );
                queue.submit([encoder.finish()]);
                self.copied_bytes += total;
                p.delta = true;
                budget.charge(copy_cost);
            }
        }
        if let Some(target) = &p.target {
            let mut offset = p
                .parts
                .iter()
                .take(p.part)
                .map(|v| v.len() * stride)
                .sum::<usize>()
                + p.vertex * stride;
            while p.part < p.parts.len() {
                if p.delta && Arc::ptr_eq(&self.fragments[p.part], &p.parts[p.part]) {
                    offset += (p.parts[p.part].len() - p.vertex) * stride;
                    p.part += 1;
                    p.vertex = 0;
                    continue;
                }
                let available = budget.bytes(self.upload_ns_per_byte, stride) / stride;
                if available == 0 {
                    break;
                }
                let part = &p.parts[p.part];
                let n = available.min(part.len() - p.vertex);
                if n > 0 {
                    let began = std::time::Instant::now();
                    self.write_part(queue, target, offset as u64, &part[p.vertex..p.vertex + n]);
                    let bytes = n * stride;
                    self.uploaded_bytes += bytes as u64;
                    self.upload_ns_per_byte = (self.upload_ns_per_byte * 0.8
                        + began.elapsed().as_nanos() as f64 / bytes as f64 * 0.2)
                        .clamp(1., 100.);
                    p.vertex += n;
                    offset += bytes;
                }
                if p.vertex == part.len() {
                    p.part += 1;
                    p.vertex = 0;
                }
            }
            if p.part == p.parts.len() {
                self.spare = Some(std::mem::replace(
                    &mut self.vertices,
                    p.target.take().unwrap(),
                ));
                self.count = (count * if self.expanded { 6 } else { 1 }) as u32;
                self.fragments = p.parts;
                self.uploads += 1;
                return true;
            }
        }
        self.pending_fragments = Some(p);
        false
    }
    pub fn new(device: &wgpu::Device, format: wgpu::TextureFormat) -> Self {
        Self::with_layout(device, format, false)
    }
    pub fn with_layout(device: &wgpu::Device, format: wgpu::TextureFormat, expanded: bool) -> Self {
        let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("native terrain routes"),
            source: wgpu::ShaderSource::Wgsl(include_str!("routes.wgsl").into()),
        });
        let pipeline=device.create_render_pipeline(&wgpu::RenderPipelineDescriptor{
   label:Some("depth-tested native route and waypoint overlay"),layout:None,
   vertex:wgpu::VertexState{module:&shader,entry_point:Some(if expanded{"vs"}else{"vs_instanced"}),buffers:&[Some(wgpu::VertexBufferLayout{array_stride:64,step_mode:if expanded{wgpu::VertexStepMode::Vertex}else{wgpu::VertexStepMode::Instance},attributes:&wgpu::vertex_attr_array![0=>Float32x3,1=>Float32x3,2=>Float32x3,3=>Float32x4,4=>Float32x3]})],compilation_options:Default::default()},
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
            size: 64,
            usage: wgpu::BufferUsages::VERTEX
                | wgpu::BufferUsages::COPY_DST
                | wgpu::BufferUsages::COPY_SRC,
            mapped_at_creation: false,
        });
        Self {
            fragments: Vec::new(),
            pending_fragments: None,
            upload_ns_per_byte: 4.,
            allocation_ns_per_byte: 0.05,
            spare: None,
            maintenance_allocations: 0,
            uploads: 0,
            pipeline,
            uniform,
            bind,
            vertices,
            count: 0,
            last: None,
            expanded,
            uploaded_bytes: 0,
            copied_bytes: 0,
        }
    }
    pub fn fork(&self, device: &wgpu::Device) -> Self {
        let uniform = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("overlay camera"),
            size: 96,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        let bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: None,
            layout: &self.pipeline.get_bind_group_layout(0),
            entries: &[wgpu::BindGroupEntry {
                binding: 0,
                resource: uniform.as_entire_binding(),
            }],
        });
        let vertices = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("empty overlay batch"),
            size: 64,
            usage: wgpu::BufferUsages::VERTEX
                | wgpu::BufferUsages::COPY_DST
                | wgpu::BufferUsages::COPY_SRC,
            mapped_at_creation: false,
        });
        Self {
            fragments: Vec::new(),
            pending_fragments: None,
            upload_ns_per_byte: 4.,
            allocation_ns_per_byte: 0.05,
            spare: None,
            maintenance_allocations: 0,
            uploads: 0,
            pipeline: self.pipeline.clone(),
            uniform,
            bind,
            vertices,
            count: 0,
            last: None,
            expanded: self.expanded,
            uploaded_bytes: 0,
            copied_bytes: 0,
        }
    }
    pub fn upload(&mut self, device: &wgpu::Device, queue: &wgpu::Queue, vertices: &[Vertex]) {
        self.cancel_staging();
        if vertices.is_empty() {
            self.spare = None;
        }
        self.fragments.clear();
        self.uploads += 1;
        let expanded;
        let vertices = if self.expanded {
            expanded = vertices
                .iter()
                .flat_map(|v| {
                    [
                        (0., -1.),
                        (1., -1.),
                        (0., 1.),
                        (0., 1.),
                        (1., -1.),
                        (1., 1.),
                    ]
                    .map(|(t, side)| {
                        let mut v = *v;
                        v.corner[0] = t;
                        v.corner[1] = side;
                        v
                    })
                })
                .collect::<Vec<_>>();
            expanded.as_slice()
        } else {
            vertices
        };
        self.count = vertices.len() as u32;
        self.uploaded_bytes += std::mem::size_of_val(vertices) as u64;
        if vertices.is_empty() && self.vertices.size() > 65536 {
            self.vertices = device.create_buffer(&wgpu::BufferDescriptor {
                label: Some("released empty overlay"),
                size: 64,
                usage: wgpu::BufferUsages::VERTEX
                    | wgpu::BufferUsages::COPY_DST
                    | wgpu::BufferUsages::COPY_SRC,
                mapped_at_creation: false,
            });
        }
        if !vertices.is_empty() {
            let bytes = bytemuck::cast_slice(vertices);
            if self.vertices.size() < bytes.len() as u64 {
                self.vertices = device.create_buffer(&wgpu::BufferDescriptor {
                    label: Some("cached draped overlay capacity"),
                    size: (bytes.len() as u64).next_power_of_two(),
                    usage: wgpu::BufferUsages::VERTEX
                        | wgpu::BufferUsages::COPY_DST
                        | wgpu::BufferUsages::COPY_SRC,
                    mapped_at_creation: false,
                });
            }
            queue.write_buffer(&self.vertices, 0, bytes);
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
            pass.set_vertex_buffer(0, self.vertices.slice(..self.count as u64 * 64));
            if self.expanded {
                pass.draw(0..self.count, 0..1)
            } else {
                pass.draw(0..6, 0..self.count)
            }
        }
    }
}
pub(crate) struct RoutePatch {
    source: TileId,
    bounds: [f64; 4],
    clip: [f64; 4],
    field: Arc<ridgeline_core::heightfield::Heightfield>,
}
pub(crate) struct RouteJob {
    paths: Arc<[RoutePath]>,
    patches: Vec<RoutePatch>,
    relief: f32,
    flat: bool,
}
pub(crate) struct RouteOutput {
    anchor: [f64; 2],
    vertices: Arc<[Vertex]>,
    waypoints: Vec<Option<[f64; 3]>>,
    exact_sources: std::collections::HashSet<TileId>,
}
fn build_routes(job: RouteJob, valid: &dyn Fn() -> bool) -> Option<RouteOutput> {
    let anchor = job
        .paths
        .iter()
        .flat_map(|p| p.points.iter())
        .next()
        .copied()
        .unwrap_or([0.5, 0.5]);
    let mut exact_sources = std::collections::HashSet::new();
    let sample = |point: [f64; 2]| -> Option<f64> {
        for patch in &job.patches {
            let c = patch.clip;
            if point[0] >= c[0] && point[0] <= c[2] && point[1] >= c[1] && point[1] <= c[3] {
                let b = patch.bounds;
                let span = b[2] - b[0];
                if let Some(h) = patch
                    .field
                    .sample((point[0] - b[0]) / span, (point[1] - b[1]) / span)
                {
                    return Some(h);
                }
            }
        }
        None
    };
    let mut vertices = Vec::new();
    let mut halos = Vec::new();
    let mut active = Vec::new();
    let mut waypoints = Vec::new();
    let local = |p: [f64; 3]| {
        [
            (p[0] - anchor[0]) as f32,
            (p[1] - anchor[1]) as f32,
            (p[2] * job.relief as f64) as f32,
        ]
    };
    let mut quad = |a: [f64; 3], b: [f64; 3], radius: f32, color: [f32; 4], layer: u8| {
        let target = if layer == 1 {
            &mut halos
        } else if layer == 2 {
            &mut active
        } else {
            &mut vertices
        };
        target.push(Vertex {
            start: local(a),
            end: local(b),
            corner: [0., 0., radius],
            color,
            style: [0.; 3],
        });
    };
    for (index, path) in job.paths.iter().enumerate() {
        for segment in path.points.windows(2) {
            if !valid() {
                return None;
            }
            let reach = [
                segment[0][0].min(segment[1][0]),
                segment[0][1].min(segment[1][1]),
                segment[0][0].max(segment[1][0]),
                segment[0][1].max(segment[1][1]),
            ];
            for row in &job.patches {
                let c = row.clip;
                // Only patches the segment can touch are draped.
                if c[0] > reach[2] || c[2] < reach[0] || c[1] > reach[3] || c[3] < reach[1] {
                    continue;
                }
                let bounds = row.bounds;
                let span = bounds[2] - bounds[0];
                let convert = |p: [f64; 2]| [(p[0] - bounds[0]) / span, (p[1] - bounds[1]) / span];
                let clip = [
                    (c[0] - bounds[0]) / span,
                    (c[1] - bounds[1]) / span,
                    (c[2] - bounds[0]) / span,
                    (c[3] - bounds[1]) / span,
                ];
                let values = crate::cartography::drape(
                    &row.field,
                    convert(segment[0]),
                    convert(segment[1]),
                    clip,
                    job.flat,
                );
                if !values.is_empty() {
                    exact_sources.insert(row.source);
                }
                for pair in values.windows(2) {
                    let world =
                        |p: [f64; 3]| [bounds[0] + p[0] * span, bounds[1] + p[1] * span, p[2]];
                    if index == 0 {
                        quad(world(pair[0]), world(pair[1]), -3., [1., 1., 1., 0.85], 1);
                    }
                    quad(
                        world(pair[0]),
                        world(pair[1]),
                        -1.5,
                        path.color,
                        if index == 0 { 2 } else { 0 },
                    );
                }
            }
        }
        if index == 0 {
            for (i, &p) in path.points.iter().enumerate() {
                if !valid() {
                    return None;
                }
                let point = sample(p).map(|h| [p[0], p[1], h]);
                waypoints.push(point);
                if let Some(p) = point {
                    for row in &job.patches {
                        let b = row.clip;
                        if p[0] >= b[0] && p[0] <= b[2] && p[1] >= b[1] && p[1] <= b[3] {
                            exact_sources.insert(row.source);
                        }
                    }
                    quad(
                        p,
                        p,
                        if path.selected == Some(i) { 8.5 } else { 5. },
                        if path.selected == Some(i) {
                            [216. / 255., 117. / 255., 73. / 255., 1.]
                        } else {
                            [216. / 255., 117. / 255., 73. / 255., 1.]
                        },
                        2,
                    );
                    quad(
                        p,
                        p,
                        if path.selected == Some(i) { 6.5 } else { 4. },
                        if path.selected == Some(i) {
                            [216. / 255., 117. / 255., 73. / 255., 1.]
                        } else {
                            [250. / 255., 250. / 255., 245. / 255., 1.]
                        },
                        2,
                    );
                }
            }
        }
    }
    vertices.reserve(halos.len() + active.len());
    vertices.extend(halos);
    vertices.extend(active);
    Some(RouteOutput {
        anchor,
        vertices: vertices.into(),
        waypoints,
        exact_sources,
    })
}
impl crate::Renderer {
    pub fn expanded_overlay_validation(&mut self, expanded: bool) {
        self.route_gpu = RouteGpu::with_layout(&self.device, self.format, expanded);
        self.inspection_gpu = self.route_gpu.fork(&self.device);
        self.route_dirty = true;
        self.inspection_dirty = true;
    }
    /// Overlay segments painted into textures so far (the old draped-geometry
    /// upload counter's replacement).
    pub fn cartographic_upload_bytes(&self) -> u64 {
        self.overlay.painted_segments * 64
    }
    pub fn overlays_pending(&self) -> bool {
        self.route_pending || self.route_dirty || self.cartography_pending()
    }
    pub fn route_uploads(&self) -> u64 {
        self.route_gpu.uploads
    }
    pub fn set_routes(&mut self, paths: Vec<RoutePath>) {
        if self.route_paths.as_ref() != paths.as_slice() {
            self.route_paths = paths.into();
            self.route_terrain = None;
            self.route_dirty = true;
        }
    }
    pub fn terrain_height(&self, point: [f64; 2]) -> Option<f64> {
        if !self.map_style.three_d {
            return self
                .cover
                .iter()
                .any(|r| {
                    let b = r.region.bounds();
                    point[0] >= b[0] && point[0] <= b[2] && point[1] >= b[1] && point[1] <= b[3]
                })
                .then_some(0.);
        }
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
    /// [terrain_height] for spatially coherent samples (a line of sight): the
    /// cover row that answered the previous sample is checked first, so a
    /// march costs about one bounds test per sample instead of a full scan.
    /// Rows are otherwise visited in the same order as terrain_height.
    pub(crate) fn rebuild_routes(&mut self) {
        self.route_upload = None;
        self.route_gpu.cancel_staging();
        if self.route_paths.is_empty() || self.cover.is_empty() {
            if let Some(worker) = &self.route_worker {
                worker.cancel();
            }
            self.route_gpu.upload(&self.device, &self.queue, &[]);
            self.route_waypoints.clear();
            self.exact_route_sources.clear();
            self.route_dirty = false;
            self.route_pending = false;
            return;
        }
        if self.route_worker.is_none() {
            self.route_worker = crate::latest_worker::LatestWorker::new(
                "RidgelineRoutes",
                self.completion_wake.clone(),
                build_routes,
            );
        }
        let Some(worker) = &self.route_worker else {
            return;
        };
        // Only terrain under the routes matters. If it is unchanged since the
        // last rebuild (the cover changed elsewhere, as it does constantly while
        // moving or loading), keep the current route geometry.
        let mut area = [f64::INFINITY, f64::INFINITY, f64::NEG_INFINITY, f64::NEG_INFINITY];
        for p in self.route_paths.iter().flat_map(|p| p.points.iter()) {
            area = [area[0].min(p[0]), area[1].min(p[1]), area[2].max(p[0]), area[3].max(p[1])];
        }
        let patches: Vec<RoutePatch> = self
            .cover
            .iter()
            .filter(|row| {
                let c = row.region.bounds();
                c[0] <= area[2] && c[2] >= area[0] && c[1] <= area[3] && c[3] >= area[1]
            })
            .filter_map(|row| {
                Some(RoutePatch {
                    source: row.source,
                    bounds: row.source.bounds(),
                    clip: row.region.bounds(),
                    field: self.tiles.get(&row.source)?.field.clone(),
                })
            })
            .collect();
        let terrain = {
            use std::hash::{Hash, Hasher};
            let mut h = std::collections::hash_map::DefaultHasher::new();
            (self.map_style.height_scale().to_bits(), self.map_style.three_d).hash(&mut h);
            for p in &patches {
                (p.source, p.clip.map(f64::to_bits), Arc::as_ptr(&p.field) as usize).hash(&mut h);
            }
            h.finish()
        };
        if self.route_terrain == Some(terrain) && !self.route_pending {
            self.route_dirty = false;
            return;
        }
        self.route_terrain = Some(terrain);
        worker.submit(RouteJob {
            paths: self.route_paths.clone(),
            patches,
            relief: self.map_style.height_scale(),
            flat: !self.map_style.three_d,
        });
        self.route_dirty = false;
        self.route_pending = true;
    }
    pub(crate) fn poll_routes(&mut self, budget: &crate::frame_work::Budget) {
        if let Some(output) = self.route_worker.as_ref().and_then(|w| w.take()) {
            self.route_gpu
                .stage_fragments(vec![output.vertices.clone()]);
            self.route_upload = Some(output);
        }
        if self.route_upload.is_some()
            && self
                .route_gpu
                .advance_fragments(&self.device, &self.queue, budget)
        {
            let o = self.route_upload.take().unwrap();
            self.route_anchor = o.anchor;
            self.route_waypoints = o.waypoints;
            self.exact_route_sources = o.exact_sources;
            self.route_pending = false;
        }
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
                        (p[2] as f32 * self.map_style.height_scale() + 1. - view.elevation) * ppm,
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
