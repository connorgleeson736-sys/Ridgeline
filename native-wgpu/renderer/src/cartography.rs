//! Cached depth-tested topographic geometry. No per-frame GeoJSON/tessellation.
use crate::{Renderer, routes::Vertex};
use ridgeline_core::heightfield::Heightfield;
use std::sync::{
    Arc, Condvar, Mutex,
    atomic::{AtomicBool, AtomicU64, Ordering},
};

struct Patch {
    bounds: [f64; 4],
    clip: [f64; 4],
    field: Arc<Heightfield>,
}
struct Job {
    paths: Arc<[CartographicPath]>,
    patches: Vec<Patch>,
    relief: f32,
    flat: bool,
    revision: u64,
}
struct Output {
    anchor: [f64; 2],
    vertices: Vec<Vertex>,
    overflow: bool,
    revision: u64,
}
struct Shared {
    revision: AtomicU64,
    stopped: AtomicBool,
    input: Mutex<Option<Job>>,
    wake: Condvar,
    output: Mutex<Option<Output>>,
}
/// One running job and one replaceable latest snapshot; no unbounded geometry queue.
/// No Android JNI or GPU object is accessed by this worker.
pub(crate) struct Worker {
    shared: Arc<Shared>,
}
impl Worker {
    fn new() -> Option<Self> {
        let shared = Arc::new(Shared {
            revision: AtomicU64::new(0),
            stopped: AtomicBool::new(false),
            input: Mutex::new(None),
            wake: Condvar::new(),
            output: Mutex::new(None),
        });
        let state = shared.clone();
        std::thread::Builder::new()
            .name("RidgelineDrape".into())
            .spawn(move || {
                #[cfg(target_os = "android")]
                unsafe {
                    unsafe extern "C" {
                        fn setpriority(which: i32, who: u32, priority: i32) -> i32;
                    }
                    // Linux PRIO_PROCESS/0 applies to the calling thread, below foreground work.
                    setpriority(0, 0, 10);
                }
                loop {
                    let job = {
                        let mut slot = state.input.lock().unwrap();
                        while slot.is_none() && !state.stopped.load(Ordering::Acquire) {
                            slot = state.wake.wait(slot).unwrap();
                        }
                        if state.stopped.load(Ordering::Acquire) {
                            return;
                        }
                        slot.take().unwrap()
                    };
                    let valid = || {
                        !state.stopped.load(Ordering::Relaxed)
                            && state.revision.load(Ordering::Acquire) == job.revision
                    };
                    if let Some(output) = build(&job, valid) {
                        if valid() {
                            *state.output.lock().unwrap() = Some(output);
                        }
                    }
                }
            })
            .ok()?;
        Some(Self { shared })
    }
    fn submit(&self, mut job: Job) {
        job.revision = self.shared.revision.fetch_add(1, Ordering::AcqRel) + 1;
        self.shared.output.lock().unwrap().take();
        *self.shared.input.lock().unwrap() = Some(job);
        self.shared.wake.notify_one();
    }
    fn take(&self) -> Option<Output> {
        self.shared
            .output
            .lock()
            .unwrap()
            .take()
            .filter(|output| output.revision == self.shared.revision.load(Ordering::Acquire))
    }
    fn cancel(&self) {
        self.shared.revision.fetch_add(1, Ordering::AcqRel);
        self.shared.input.lock().unwrap().take();
        self.shared.output.lock().unwrap().take();
    }
}
impl Drop for Worker {
    fn drop(&mut self) {
        // Do not join a CPU worker while Android holds the renderer-state mutex.
        let mut input = self.shared.input.lock().unwrap();
        self.shared.stopped.store(true, Ordering::Release);
        input.take();
        self.shared.wake.notify_one();
    }
}
/// Exact former synchronous arithmetic, now executed only by a cancelable worker.
fn build(job: &Job, valid: impl Fn() -> bool) -> Option<Output> {
    let anchor = job
        .paths
        .iter()
        .find_map(|p| p.points.first())
        .copied()
        .unwrap_or([0.5, 0.5]);
    let mut vertices = Vec::new();
    let mut overflow = false;
    'paths: for path in job.paths.iter() {
        let mut distance = 0.;
        for segment in path.points.windows(2) {
            if !valid() {
                return None;
            }
            let a = segment[0];
            let b = segment[1];
            let lo = [a[0].min(b[0]), a[1].min(b[1])];
            let hi = [a[0].max(b[0]), a[1].max(b[1])];
            for patch in &job.patches {
                let clip = patch.clip;
                if hi[0] < clip[0] || lo[0] > clip[2] || hi[1] < clip[1] || lo[1] > clip[3] {
                    continue;
                }
                let bounds = patch.bounds;
                let span = bounds[2] - bounds[0];
                let point = |p: [f64; 2]| [(p[0] - bounds[0]) / span, (p[1] - bounds[1]) / span];
                let draped = drape(
                    &patch.field,
                    point(a),
                    point(b),
                    [
                        (clip[0] - bounds[0]) / span,
                        (clip[1] - bounds[1]) / span,
                        (clip[2] - bounds[0]) / span,
                        (clip[3] - bounds[1]) / span,
                    ],
                    job.flat,
                );
                for pair in draped.windows(2) {
                    if vertices.len() + 1 > 333333 {
                        overflow = true;
                        break 'paths;
                    }
                    let world = |p: [f64; 3]| {
                        [
                            (bounds[0] + p[0] * span - anchor[0]) as f32,
                            (bounds[1] + p[1] * span - anchor[1]) as f32,
                            (p[2] * job.relief as f64) as f32,
                        ]
                    };
                    let start_world =
                        [bounds[0] + pair[0][0] * span, bounds[1] + pair[0][1] * span];
                    let phase = distance + (start_world[0] - a[0]).hypot(start_world[1] - a[1]);
                    vertices.push(Vertex {
                        start: world(pair[0]),
                        end: world(pair[1]),
                        corner: [0., 0., -path.width * 0.5],
                        color: path.color,
                        style: [path.dash[0], path.dash[1], phase as f32],
                    });
                }
            }
            distance += (b[0] - a[0]).hypot(b[1] - a[1]);
        }
    }
    Some(Output {
        anchor,
        vertices,
        overflow,
        revision: job.revision,
    })
}

#[derive(Clone, PartialEq)]
pub struct CartographicPath {
    pub points: Vec<[f64; 2]>,
    pub color: [f32; 4],
    pub width: f32,
    pub dash: [f32; 2],
}
impl Renderer {
    pub fn set_cartography(&mut self, paths: Vec<CartographicPath>) {
        if self.cartographic_paths.as_ref() != paths.as_slice() {
            self.cartographic_paths = paths.into();
            self.cartographic_dirty = true;
        }
    }
    /// Only snapshots immutable CPU inputs. Terrain draping never runs on the frame thread.
    pub(crate) fn rebuild_cartography(&mut self) {
        self.cartographic_dirty = false;
        if self.cartographic_paths.is_empty() || self.cover.is_empty() {
            if let Some(worker) = &self.cartographic_worker {
                worker.cancel();
            }
            self.cartographic_pending = false;
            self.cartographic_vertices = 0;
            self.cartographic_gpu.upload(&self.device, &self.queue, &[]);
            return;
        }
        if self.cartographic_worker.is_none() {
            self.cartographic_worker = Worker::new();
        }
        let Some(worker) = &self.cartographic_worker else {
            return;
        };
        let patches = self
            .cover
            .iter()
            .filter_map(|row| {
                let tile = self.tiles.get(&row.source)?;
                Some(Patch {
                    bounds: row.source.bounds(),
                    clip: row.region.bounds(),
                    field: tile.field.clone(),
                })
            })
            .collect();
        worker.submit(Job {
            paths: self.cartographic_paths.clone(),
            patches,
            relief: self.map_style.height_scale(),
            flat: !self.map_style.three_d,
            revision: 0,
        });
        self.cartographic_pending = true;
    }
    pub(crate) fn poll_cartography(&mut self) {
        let Some(output) = self.cartographic_worker.as_ref().and_then(Worker::take) else {
            return;
        };
        self.cartographic_anchor = output.anchor;
        self.cartographic_vertices = output.vertices.len() as u32 * 6;
        self.cartographic_overflow = output.overflow;
        self.cartographic_gpu
            .upload(&self.device, &self.queue, &output.vertices);
        self.cartographic_pending = false;
    }
    pub fn cartography_pending(&self) -> bool {
        self.cartographic_pending || self.cartographic_dirty
    }
    pub fn cartography_jobs(&self) -> u64 {
        self.cartographic_worker
            .as_ref()
            .map_or(0, |worker| worker.shared.revision.load(Ordering::Acquire))
    }
    pub(crate) fn terrain_overlays_changed(&mut self) {
        self.route_dirty = true;
        self.cartographic_dirty = true;
        self.labels_dirty = true;
        self.inspection_dirty = true;
    }
    pub(crate) fn clear_cartography_geometry(&mut self) {
        if let Some(worker) = &self.cartographic_worker {
            worker.cancel();
        }
        self.cartographic_pending = false;
        self.cartographic_gpu.upload(&self.device, &self.queue, &[]);
        self.cartographic_vertices = 0;
    }
    /// Projection only, no GPU readback or per-label raycast. Text quads use terrain depth.
    pub fn project_points(&self, points: &[[f64; 2]]) -> Vec<[f32; 3]> {
        let Some(view) = self.last_view else {
            return vec![[0.; 3]; points.len()];
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
        points
            .iter()
            .map(|p| {
                let Some(height) = self.terrain_height(*p) else {
                    return [0.; 3];
                };
                let c = m * glam::Vec4::new(
                    ((p[0] - view.center[0]) * world) as f32,
                    ((view.center[1] - p[1]) * world) as f32,
                    ((height * self.map_style.height_scale() as f64 + 1. - view.elevation as f64)
                        * ppm as f64) as f32,
                    1.,
                );
                if c.w <= 0. || c.z < 0. || c.z > c.w {
                    return [0.; 3];
                };
                [
                    (c.x / c.w + 1.) * 0.5 * view.width as f32,
                    (1. - c.y / c.w) * 0.5 * view.height as f32,
                    1.,
                ]
            })
            .collect()
    }
}

/// In explicit 2D, clip to the cartographic plane without walking DEM grid triangles.
/// In 3D, retain exact real-height-field draping.
pub(crate) fn drape(
    field: &ridgeline_core::heightfield::Heightfield,
    a: [f64; 2],
    b: [f64; 2],
    clip: [f64; 4],
    flat: bool,
) -> Vec<[f64; 3]> {
    if !flat {
        return field.drape_segment(a, b, clip);
    }
    let delta = [b[0] - a[0], b[1] - a[1]];
    let mut lo = 0f64;
    let mut hi = 1f64;
    for (p, q) in [
        (-delta[0], a[0] - clip[0]),
        (delta[0], clip[2] - a[0]),
        (-delta[1], a[1] - clip[1]),
        (delta[1], clip[3] - a[1]),
    ] {
        if p == 0. {
            if q < 0. {
                return vec![];
            }
        } else {
            let t = q / p;
            if p < 0. {
                lo = lo.max(t)
            } else {
                hi = hi.min(t)
            };
            if lo > hi {
                return vec![];
            }
        }
    }
    vec![
        [a[0] + delta[0] * lo, a[1] + delta[1] * lo, 0.],
        [a[0] + delta[0] * hi, a[1] + delta[1] * hi, 0.],
    ]
}
