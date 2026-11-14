//! Topographic lines draped like MapLibre's terrain render-to-texture: each
//! visible image tile gets an overlay texture in a fixed pool, painted on the
//! GPU from the line data and sampled by the terrain shader over the photo.
//!
//! - Line data arrives grouped by source vector tile. A tile's texture is
//!   repainted only when the groups touching it (or the zoom band) change, so
//!   camera movement and terrain uploads never rebuild anything.
//! - Lines sit exactly on whatever terrain mesh is drawn; no CPU draping.
//! - With no line data, no texture is allocated and no per-frame work is done.
use crate::TileId;
use std::collections::HashMap;
use std::hash::{Hash, Hasher};
use std::sync::Arc;

pub(crate) const SIZE: u32 = 512;
/// Second tier for the farther, coarser tiles: four times as many per MiB.
pub(crate) const SMALL: u32 = 256;
/// Line instances painted per frame; the rest follow on the next frames.
const SEGMENTS_PER_FRAME: usize = 160_000;
const RENDERS_PER_FRAME: usize = 24;

#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub struct GroupKey {
    pub z: u32,
    pub x: u32,
    pub y: u32,
    pub clip: [u64; 4],
}
pub struct Line {
    pub color: [f32; 4],
    pub width: f32,
    pub dash: [f32; 2],
    pub order: i32,
    bbox: [f64; 4],
    points: Vec<[f64; 2]>,
    /// Distance along the line at each point, in mercator units.
    along: Vec<f64>,
}
impl Line {
    /// Consecutive duplicate points are removed; a line needs two distinct points.
    pub fn new(points: Vec<[f64; 2]>, color: [f32; 4], width: f32, dash: [f32; 2], order: i32) -> Option<Self> {
        let mut clean: Vec<[f64; 2]> = Vec::with_capacity(points.len());
        for p in points {
            if clean.last() != Some(&p) {
                clean.push(p);
            }
        }
        if clean.len() < 2 {
            return None;
        }
        let mut bbox = [f64::MAX, f64::MAX, f64::MIN, f64::MIN];
        let mut along = Vec::with_capacity(clean.len());
        let mut d = 0.;
        for (i, p) in clean.iter().enumerate() {
            if i > 0 {
                let q = clean[i - 1];
                d += ((p[0] - q[0]).powi(2) + (p[1] - q[1]).powi(2)).sqrt();
            }
            along.push(d);
            bbox = [bbox[0].min(p[0]), bbox[1].min(p[1]), bbox[2].max(p[0]), bbox[3].max(p[1])];
        }
        Some(Self { color, width, dash, order, bbox, points: clean, along })
    }
}
/// A line group sent by value, or by reference to one already held.
pub enum GroupInput {
    New(Group),
    Existing(GroupKey, u64),
    /// Sent, but with no drawable lines: remembered so references stay valid.
    Empty(GroupKey, u64),
}
pub struct Group {
    pub key: GroupKey,
    /// Content hash of the group's packed bytes; equal hash, equal lines.
    pub hash: u64,
    bounds: [f64; 4],
    pub lines: Vec<Line>,
}
impl Group {
    pub fn new(key: GroupKey, hash: u64, lines: Vec<Line>) -> Self {
        let mut bounds = [f64::MAX, f64::MAX, f64::MIN, f64::MIN];
        for l in &lines {
            bounds = [
                bounds[0].min(l.bbox[0]),
                bounds[1].min(l.bbox[1]),
                bounds[2].max(l.bbox[2]),
                bounds[3].max(l.bbox[3]),
            ];
        }
        Self { key, hash, bounds, lines }
    }
}
fn intersects(a: [f64; 4], b: [f64; 4]) -> bool {
    a[0] <= b[2] && a[2] >= b[0] && a[1] <= b[3] && a[3] >= b[1]
}

#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct Segment {
    a: [f32; 2],
    b: [f32; 2],
    prev: [f32; 2],
    next: [f32; 2],
    color: [f32; 4],
    /// Half width, dash, gap (texture pixels) and dash phase at `a`.
    params: [f32; 4],
}

/// Paint the lines touching `tile` in texture pixels. `scale` converts CSS
/// pixels to texture pixels for this tile at the current zoom band.
fn segments(tile: TileId, groups: &[Arc<Group>], scale: f64, out: &mut Vec<Segment>) {
    let b = tile.bounds();
    let s = b[2] - b[0];
    let per = SIZE as f64 / s;
    let mut lines: Vec<&Line> = Vec::new();
    for g in groups {
        for line in &g.lines {
            let margin = (line.width as f64 * scale * 0.5 + 2.) / per;
            if intersects(line.bbox, [b[0] - margin, b[1] - margin, b[2] + margin, b[3] + margin]) {
                lines.push(line);
            }
        }
    }
    // Layer-major paint order across groups, as MapLibre draws layers.
    lines.sort_by_key(|l| l.order);
    let to = |p: [f64; 2]| [((p[0] - b[0]) * per) as f32, ((p[1] - b[1]) * per) as f32];
    for line in lines {
        let mut half = (line.width as f64 * scale * 0.5) as f32;
        let mut color = line.color;
        if half < 0.5 {
            // Thinner than a texel: keep one texel and fade, so coverage matches.
            color[3] *= half * 2.;
            half = 0.5;
        }
        let dash = (line.dash[0] as f64 * scale) as f32;
        let gap = (line.dash[1] as f64 * scale) as f32;
        let cycle = (dash + gap) as f64;
        let margin = (half as f64 + 2.) / per;
        let area = [b[0] - margin, b[1] - margin, b[2] + margin, b[3] + margin];
        let n = line.points.len();
        for i in 0..n - 1 {
            let (p, q) = (line.points[i], line.points[i + 1]);
            if !intersects([p[0].min(q[0]), p[1].min(q[1]), p[0].max(q[0]), p[1].max(q[1])], area) {
                continue;
            }
            let phase = if dash > 0. && gap > 0. { (line.along[i] * per) % cycle } else { 0. };
            out.push(Segment {
                a: to(p),
                b: to(q),
                prev: to(if i > 0 { line.points[i - 1] } else { p }),
                next: to(if i + 2 < n { line.points[i + 2] } else { q }),
                color,
                params: [half, dash, gap, phase as f32],
            });
        }
    }
}

struct Gpu {
    textures: [wgpu::Texture; 2],
    /// Layer views: the 512 px tier first, then the 256 px tier.
    views: Vec<wgpu::TextureView>,
    bind: wgpu::BindGroup,
    buffer: wgpu::Buffer,
    capacity: usize,
}
#[derive(Clone, Copy)]
struct Slot {
    tile: Option<TileId>,
    fingerprint: u64,
    used: u64,
}
pub(crate) struct Overlay {
    groups: Vec<Arc<Group>>,
    /// Groups sent without drawable lines, by key and id.
    empty_groups: std::collections::HashSet<(GroupKey, u64)>,
    layout: wgpu::BindGroupLayout,
    sampler: wgpu::Sampler,
    pipeline: Option<wgpu::RenderPipeline>,
    empty: wgpu::BindGroup,
    gpu: Option<Gpu>,
    /// Layers in the 512 px and 256 px tiers; slots [0, big) are 512 px.
    big: u32,
    small: u32,
    slots: Vec<Slot>,
    by_tile: HashMap<TileId, usize>,
    frame: u64,
    /// A visible tile still shows an outdated (or no) overlay.
    pub pending: bool,
    pub renders: u64,
    pub painted_segments: u64,
    pub revision: u64,
    /// Visible tiles with lines but no free texture in the last frame.
    pub missing: usize,
    /// Visible tiles showing an overlay in the last frame.
    pub shown: usize,
}
impl Overlay {
    pub fn new(device: &wgpu::Device) -> Self {
        let layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("terrain overlay"),
            entries: &[
                wgpu::BindGroupLayoutEntry {
                    binding: 0,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Texture {
                        sample_type: wgpu::TextureSampleType::Float { filterable: true },
                        view_dimension: wgpu::TextureViewDimension::D2Array,
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
                wgpu::BindGroupLayoutEntry {
                    binding: 2,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Texture {
                        sample_type: wgpu::TextureSampleType::Float { filterable: true },
                        view_dimension: wgpu::TextureViewDimension::D2Array,
                        multisampled: false,
                    },
                    count: None,
                },
            ],
        });
        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("terrain overlay"),
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            ..Default::default()
        });
        // Overlays off: a 1x1 transparent array is bound and never sampled.
        let texture = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("no terrain overlay"),
            size: wgpu::Extent3d { width: 1, height: 1, depth_or_array_layers: 2 },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::Rgba8Unorm,
            usage: wgpu::TextureUsages::TEXTURE_BINDING,
            view_formats: &[],
        });
        let empty = Self::bind(device, &layout, [&texture, &texture], &sampler);
        let mut overlay = Self {
            groups: Vec::new(),
            empty_groups: std::collections::HashSet::new(),
            layout,
            sampler,
            pipeline: None,
            empty,
            gpu: None,
            big: 0,
            small: 0,
            slots: Vec::new(),
            by_tile: HashMap::new(),
            frame: 0,
            pending: false,
            renders: 0,
            painted_segments: 0,
            revision: 0,
            missing: 0,
            shown: 0,
        };
        overlay.set_layers(48);
        overlay
    }
    fn bind(device: &wgpu::Device, layout: &wgpu::BindGroupLayout, textures: [&wgpu::Texture; 2], sampler: &wgpu::Sampler) -> wgpu::BindGroup {
        let view = |t: &wgpu::Texture| {
            t.create_view(&wgpu::TextureViewDescriptor {
                dimension: Some(wgpu::TextureViewDimension::D2Array),
                ..Default::default()
            })
        };
        let (big, small) = (view(textures[0]), view(textures[1]));
        device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("terrain overlay"),
            layout,
            entries: &[
                wgpu::BindGroupEntry { binding: 0, resource: wgpu::BindingResource::TextureView(&big) },
                wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::Sampler(sampler) },
                wgpu::BindGroupEntry { binding: 2, resource: wgpu::BindingResource::TextureView(&small) },
            ],
        })
    }
    pub fn layout(&self) -> &wgpu::BindGroupLayout {
        &self.layout
    }
    pub fn bind_group(&self) -> &wgpu::BindGroup {
        self.gpu.as_ref().map_or(&self.empty, |g| &g.bind)
    }
    /// Overlay pool from the renderer's tile budget (desktop/tests).
    pub fn set_budget(&mut self, tiles: usize) {
        self.set_layers((tiles / 2).clamp(32, 64));
    }
    /// Pool memory in MiB (from device RAM on Android): half as 512 px textures
    /// for the nearest, finest tiles, half as 256 px textures (four per MiB) for
    /// the rest, so far more visible tiles get their lines from the same memory.
    pub fn set_layers(&mut self, mib: usize) {
        // wgpu's GL backend creates 2D textures whose layer count is a multiple
        // of 6 as cube maps, which cannot be rendered per layer.
        let safe = |n: u32| if n % 6 == 0 { n + 1 } else { n };
        let mib = mib.clamp(12, 64) as u32;
        let big = safe((mib / 2).max(4));
        let small = safe((mib - mib / 2) * 4);
        if (big, small) != (self.big, self.small) {
            (self.big, self.small) = (big, small);
            self.gpu = None;
            self.slots.clear();
            self.by_tile.clear();
        }
    }
    fn encode(&self, slot: usize) -> f32 {
        if slot < self.big as usize { slot as f32 + 1. } else { -((slot - self.big as usize) as f32 + 1.) }
    }
    fn is_big(&self, slot: usize) -> bool {
        slot < self.big as usize
    }
    pub fn is_empty(&self) -> bool {
        self.groups.is_empty()
    }
    /// Replace the line data. Groups whose key and hash are unchanged keep their
    /// parsed lines, so their tiles' textures stay valid.
    /// Groups by reference or by value. Returns false, changing nothing, if a
    /// referenced group is not held (the caller then sends every group).
    pub fn set_group_inputs(&mut self, groups: Vec<GroupInput>) -> bool {
        let held: HashMap<(GroupKey, u64), Arc<Group>> =
            self.groups.iter().map(|g| ((g.key, g.hash), g.clone())).collect();
        let mut next = Vec::with_capacity(groups.len());
        let mut empty = std::collections::HashSet::new();
        for g in groups {
            match g {
                GroupInput::Existing(key, id) => match held.get(&(key, id)) {
                    Some(g) => next.push(g.clone()),
                    None if self.empty_groups.contains(&(key, id)) => {
                        empty.insert((key, id));
                    }
                    None => return false,
                },
                GroupInput::New(g) => next.push(Arc::new(g)),
                GroupInput::Empty(key, id) => {
                    empty.insert((key, id));
                }
            }
        }
        self.groups = next;
        self.empty_groups = empty;
        self.revision += 1;
        if self.groups.is_empty() {
            self.gpu = None;
            self.slots.clear();
            self.by_tile.clear();
            self.pending = false;
        }
        true
    }
    pub fn set_groups(&mut self, groups: Vec<Group>) {
        let old: HashMap<(GroupKey, u64), Arc<Group>> =
            self.groups.drain(..).map(|g| ((g.key, g.hash), g)).collect();
        self.groups = groups
            .into_iter()
            .map(|g| old.get(&(g.key, g.hash)).cloned().unwrap_or_else(|| Arc::new(g)))
            .collect();
        self.revision += 1;
        if self.groups.is_empty() {
            // Overlays off: free the pool; nothing runs per frame.
            self.gpu = None;
            self.slots.clear();
            self.by_tile.clear();
            self.pending = false;
        }
    }
    /// Choose and, within a per-frame budget, paint overlay textures for the
    /// visible image tiles (finest first). Returns slot+1 per tile with an overlay.
    pub fn prepare(
        &mut self,
        device: &wgpu::Device,
        queue: &wgpu::Queue,
        tiles: impl Iterator<Item = TileId>,
        logical_zoom: f64,
    ) -> HashMap<TileId, f32> {
        let mut result = HashMap::new();
        self.pending = false;
        if self.groups.is_empty() {
            return result;
        }
        if self.gpu.is_none() {
            self.create(device);
        }
        self.frame += 1;
        let band = (logical_zoom * 4.).floor();
        let zoom = (band + 0.5) / 4.;
        let mut order: Vec<TileId> = tiles.collect();
        order.sort_by(|a, b| b.z.cmp(&a.z).then(a.x.cmp(&b.x)).then(a.y.cmp(&b.y)));
        order.dedup();
        let mut batch: Vec<Segment> = Vec::new();
        let mut paints: Vec<(usize, std::ops::Range<u32>)> = Vec::new();
        // (tile, groups, fingerprint, current slot, rank among tiles with lines)
        let mut work: Vec<(TileId, Vec<Arc<Group>>, u64, Option<usize>, usize)> = Vec::new();
        let mut rank = 0;
        for tile in order {
            let b = tile.bounds();
            let s = b[2] - b[0];
            let pad = s * 0.05;
            let near: Vec<Arc<Group>> = self
                .groups
                .iter()
                .filter(|g| intersects(g.bounds, [b[0] - pad, b[1] - pad, b[2] + pad, b[3] + pad]))
                .cloned()
                .collect();
            if near.is_empty() {
                if let Some(slot) = self.by_tile.remove(&tile) {
                    self.slots[slot].tile = None;
                }
                continue;
            }
            rank += 1;
            let mut hasher = std::collections::hash_map::DefaultHasher::new();
            (band as i64, tile.z, tile.x, tile.y).hash(&mut hasher);
            for g in &near {
                (g.key, g.hash).hash(&mut hasher);
            }
            let fingerprint = hasher.finish();
            let existing = self.by_tile.get(&tile).copied();
            if let Some(slot) = existing {
                self.slots[slot].used = self.frame;
                if self.slots[slot].fingerprint == fingerprint {
                    result.insert(tile, self.encode(slot));
                    continue;
                }
            }
            work.push((tile, near, fingerprint, existing, rank - 1));
        }
        // Tiles with no overlay at all are painted before stale ones are
        // refreshed, so lines never go missing while others are repainted.
        work.sort_by_key(|w| (w.3.is_some(), w.4));
        self.missing = 0;
        for (tile, near, fingerprint, existing, rank) in work {
            if paints.len() >= RENDERS_PER_FRAME || batch.len() >= SEGMENTS_PER_FRAME {
                // Show the previous texture of this tile until it is repainted.
                self.pending = true;
                if let Some(slot) = existing {
                    result.insert(tile, self.encode(slot));
                }
                continue;
            }
            // The finest (nearest) tiles get 512 px textures, the rest 256 px.
            let want_big = rank < self.big as usize;
            let slot = existing
                .filter(|s| self.is_big(*s) == want_big)
                .or_else(|| self.free_slot(want_big))
                .or(existing)
                .or_else(|| self.free_slot(!want_big));
            let Some(slot) = slot else {
                // Every texture already serves a nearer, finer tile this frame.
                self.missing += 1;
                continue;
            };
            if let Some(old) = existing {
                if old != slot {
                    self.slots[old].tile = None;
                }
            }
            if let Some(old) = self.slots[slot].tile {
                if old != tile {
                    self.by_tile.remove(&old);
                }
            }
            let start = batch.len() as u32;
            // CSS px to texture px (512 px space; the 256 px tier renders the
            // same space at half resolution): the tile spans 512 * 2^(zoom - z) CSS px.
            let scale = SIZE as f64 / (512. * 2f64.powf(zoom - tile.z as f64));
            segments(tile, &near, scale, &mut batch);
            paints.push((slot, start..batch.len() as u32));
            self.slots[slot] = Slot { tile: Some(tile), fingerprint, used: self.frame };
            self.by_tile.insert(tile, slot);
            result.insert(tile, self.encode(slot));
        }
        self.shown = result.len();
        if !paints.is_empty() {
            self.paint(device, queue, &batch, &paints);
        }
        result
    }
    fn free_slot(&mut self, big: bool) -> Option<usize> {
        let range = if big { 0..self.big as usize } else { self.big as usize..(self.big + self.small) as usize };
        if self.slots.len() < (self.big + self.small) as usize {
            self.slots.resize((self.big + self.small) as usize, Slot { tile: None, fingerprint: 0, used: 0 });
        }
        if let Some(i) = range.clone().find(|&i| self.slots[i].tile.is_none()) {
            return Some(i);
        }
        // Least recently used texture that no tile of this frame is showing.
        range.filter(|&i| self.slots[i].used < self.frame).min_by_key(|&i| self.slots[i].used)
    }
    fn create(&mut self, device: &wgpu::Device) {
        let tier = |size: u32, layers: u32| {
            device.create_texture(&wgpu::TextureDescriptor {
                label: Some("terrain overlay pool"),
                size: wgpu::Extent3d { width: size, height: size, depth_or_array_layers: layers },
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format: wgpu::TextureFormat::Rgba8Unorm,
                usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::RENDER_ATTACHMENT,
                view_formats: &[],
            })
        };
        let textures = [tier(SIZE, self.big), tier(SMALL, self.small)];
        let views = textures
            .iter()
            .zip([self.big, self.small])
            .flat_map(|(texture, layers)| {
                (0..layers).map(move |layer| {
                    texture.create_view(&wgpu::TextureViewDescriptor {
                        dimension: Some(wgpu::TextureViewDimension::D2),
                        base_array_layer: layer,
                        array_layer_count: Some(1),
                        ..Default::default()
                    })
                })
            })
            .collect();
        let bind = Self::bind(device, &self.layout, [&textures[0], &textures[1]], &self.sampler);
        let buffer = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("overlay segments"),
            size: 4096 * std::mem::size_of::<Segment>() as u64,
            usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });
        if self.pipeline.is_none() {
            let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
                label: Some("terrain overlay lines"),
                source: wgpu::ShaderSource::Wgsl(include_str!("overlay.wgsl").into()),
            });
            self.pipeline = Some(device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
                label: Some("terrain overlay lines"),
                layout: None,
                vertex: wgpu::VertexState {
                    module: &shader,
                    entry_point: Some("vs"),
                    compilation_options: Default::default(),
                    buffers: &[Some(wgpu::VertexBufferLayout {
                        array_stride: std::mem::size_of::<Segment>() as u64,
                        step_mode: wgpu::VertexStepMode::Instance,
                        attributes: &wgpu::vertex_attr_array![0=>Float32x2,1=>Float32x2,2=>Float32x2,3=>Float32x2,4=>Float32x4,5=>Float32x4],
                    })],
                },
                fragment: Some(wgpu::FragmentState {
                    module: &shader,
                    entry_point: Some("fs"),
                    compilation_options: Default::default(),
                    targets: &[Some(wgpu::ColorTargetState {
                        format: wgpu::TextureFormat::Rgba8Unorm,
                        blend: Some(wgpu::BlendState::PREMULTIPLIED_ALPHA_BLENDING),
                        write_mask: wgpu::ColorWrites::ALL,
                    })],
                }),
                primitive: Default::default(),
                depth_stencil: None,
                multisample: Default::default(),
                multiview_mask: None,
                cache: None,
            }));
        }
        self.gpu = Some(Gpu { textures, views, bind, buffer, capacity: 4096 });
        self.slots.clear();
        self.by_tile.clear();
    }
    fn paint(&mut self, device: &wgpu::Device, queue: &wgpu::Queue, batch: &[Segment], paints: &[(usize, std::ops::Range<u32>)]) {
        let gpu = self.gpu.as_mut().unwrap();
        if batch.len() > gpu.capacity {
            gpu.capacity = batch.len().next_power_of_two();
            gpu.buffer = device.create_buffer(&wgpu::BufferDescriptor {
                label: Some("overlay segments"),
                size: (gpu.capacity * std::mem::size_of::<Segment>()) as u64,
                usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::COPY_DST,
                mapped_at_creation: false,
            });
        }
        if !batch.is_empty() {
            queue.write_buffer(&gpu.buffer, 0, bytemuck::cast_slice(batch));
        }
        let pipeline = self.pipeline.as_ref().unwrap();
        let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: Some("terrain overlays") });
        for (slot, range) in paints {
            let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("terrain overlay"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: &gpu.views[*slot],
                    depth_slice: None,
                    resolve_target: None,
                    ops: wgpu::Operations { load: wgpu::LoadOp::Clear(wgpu::Color::TRANSPARENT), store: wgpu::StoreOp::Store },
                })],
                depth_stencil_attachment: None,
                timestamp_writes: None,
                occlusion_query_set: None,
                multiview_mask: None,
            });
            if !range.is_empty() {
                pass.set_pipeline(pipeline);
                pass.set_vertex_buffer(0, gpu.buffer.slice(..));
                pass.draw(0..6, range.clone());
            }
        }
        queue.submit([encoder.finish()]);
        self.renders += paints.len() as u64;
        self.painted_segments += batch.len() as u64;
        let _ = &gpu.textures;
    }
    pub fn texture_bytes(&self) -> u64 {
        self.gpu.as_ref().map_or(0, |_| 4 * (SIZE as u64 * SIZE as u64 * self.big as u64 + SMALL as u64 * SMALL as u64 * self.small as u64))
    }
}

/// Body length marking a group sent by reference (the renderer holds it).
pub const REFERENCE: u32 = u32::MAX;
/// Parse a grouped line packet (see the Android cartography JNI): per group
/// z, x, y, clip[4], line count, 64-bit id, body length (REFERENCE for a
/// group sent by reference), body. None if invalid.
pub fn parse_line_packet(bytes: &[u8]) -> Option<Vec<GroupInput>> {
    let size = bytes.len();
    if size < 4 {
        return None;
    }
    // Lines grouped by source vector tile, parsed before the renderer
    // lock: [groups] then per group z,x,y, clip[4] (f64), line count,
    // a 64-bit content id, a body length and the body: per line n, ARGB,
    // width, dash, gap, order, n x (f64 x, f64 y). A body length of 0
    // refers to a group the renderer already holds (same key and id), so
    // unchanged tiles are neither re-sent nor re-parsed.
    let int = |at: usize| u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap());
    let float = |at: usize| f32::from_le_bytes(bytes[at..at + 4].try_into().unwrap());
    let double = |at: usize| f64::from_le_bytes(bytes[at..at + 8].try_into().unwrap());
    let long = |at: usize| u64::from_le_bytes(bytes[at..at + 8].try_into().unwrap());
    let count = int(0) as usize;
    if count > 4096 {
        return None;
    }
    let mut at = 4;
    let mut total = 0usize;
    let mut groups = Vec::with_capacity(count);
    for _ in 0..count {
        if at + 60 > size {
            return None;
        }
        let (z, x, y) = (int(at), int(at + 4), int(at + 8));
        let clip = [double(at + 12), double(at + 20), double(at + 28), double(at + 36)];
        let lines_count = int(at + 44) as usize;
        let id = long(at + 48);
        let body = int(at + 56) as usize;
        at += 60;
        let key = GroupKey { z, x, y, clip: clip.map(f64::to_bits) };
        // REFERENCE, not a zero length: a tile with no lines has an empty
        // body, and reading that as a reference made every packet with an
        // empty tile in view fail, so no overlays were drawn.
        if body as u32 == REFERENCE {
            groups.push(GroupInput::Existing(key, id));
            continue;
        }
        if lines_count > 65536 || clip.iter().any(|v| !v.is_finite()) || at + body > size {
            return None;
        }
        let end = at + body;
        let mut lines = Vec::with_capacity(lines_count);
        for _ in 0..lines_count {
            if at + 24 > end {
                return None;
            }
            let n = int(at) as usize;
            let color = int(at + 4);
            let width = float(at + 8);
            let dash = [float(at + 12), float(at + 16)];
            let order = int(at + 20) as i32;
            at += 24;
            total += n;
            if n > 262144
                || total > 2_000_000
                || at + n * 16 > end
                || !width.is_finite()
                || width <= 0.
                || width > 40.
                || dash.iter().any(|v| !v.is_finite() || *v < 0. || *v > 400.)
            {
                return None;
            }
            let mut points = Vec::with_capacity(n);
            for row in bytes[at..at + n * 16].chunks_exact(16) {
                let px = f64::from_le_bytes(row[..8].try_into().unwrap());
                let py = f64::from_le_bytes(row[8..].try_into().unwrap());
                if !px.is_finite() || !py.is_finite() || !(0. ..=1.).contains(&px) || !(0. ..=1.).contains(&py) {
                    return None;
                }
                points.push([px, py]);
            }
            at += n * 16;
            let rgba = [
                ((color >> 16) & 255) as f32 / 255.,
                ((color >> 8) & 255) as f32 / 255.,
                (color & 255) as f32 / 255.,
                ((color >> 24) & 255) as f32 / 255.,
            ];
            lines.extend(Line::new(points, rgba, width, dash, order));
        }
        if at != end {
            return None;
        }
        groups.push(if lines.is_empty() {
            GroupInput::Empty(key, id)
        } else {
            GroupInput::New(Group::new(key, id, lines))
        });
    }
    if at != size {
        return None;
    }
    Some(groups)
}

#[cfg(test)]
mod packet_tests {
    use super::*;
    fn group(out: &mut Vec<u8>, id: u64, lines: &[&[[f64; 2]]], reference: bool) {
        for v in [14u32, 1, 2] {
            out.extend(v.to_le_bytes());
        }
        for v in [0.1f64, 0.1, 0.2, 0.2] {
            out.extend(v.to_le_bytes());
        }
        out.extend((lines.len() as u32).to_le_bytes());
        out.extend(id.to_le_bytes());
        let mut body = Vec::new();
        for line in lines {
            body.extend((line.len() as u32).to_le_bytes());
            body.extend(0xff00ff00u32.to_le_bytes());
            for v in [2f32, 0., 0.] {
                body.extend(v.to_le_bytes());
            }
            body.extend(0i32.to_le_bytes());
            for p in line.iter() {
                body.extend(p[0].to_le_bytes());
                body.extend(p[1].to_le_bytes());
            }
        }
        out.extend((if reference { REFERENCE } else { body.len() as u32 }).to_le_bytes());
        if !reference {
            out.extend(body);
        }
    }
    #[test]
    fn an_empty_tile_is_empty_not_a_reference() {
        let mut packet = 3u32.to_le_bytes().to_vec();
        group(&mut packet, 1, &[], false);
        group(&mut packet, 2, &[&[[0.12, 0.12], [0.18, 0.18]]], false);
        group(&mut packet, 3, &[], true);
        let parsed = parse_line_packet(&packet).expect("parses");
        assert!(matches!(parsed[0], GroupInput::Empty(_, 1)), "a tile without lines is sent by value, empty");
        assert!(matches!(parsed[1], GroupInput::New(_)));
        assert!(matches!(parsed[2], GroupInput::Existing(_, 3)));
    }
}
