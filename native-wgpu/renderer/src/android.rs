use super::*;
use ndk::native_window::NativeWindow;
use raw_window_handle::{DisplayHandle, HandleError, HasDisplayHandle, HasWindowHandle};
use std::{
    ffi::c_void,
    sync::{
        Mutex, OnceLock,
        atomic::{AtomicU64, Ordering},
    },
};
#[link(name = "log")]
unsafe extern "C" {
    fn __android_log_write(
        priority: i32,
        tag: *const std::ffi::c_char,
        text: *const std::ffi::c_char,
    ) -> i32;
}
fn log(message: &str) {
    if let Ok(text) = std::ffi::CString::new(message) {
        unsafe {
            __android_log_write(4, c"RidgelineNative".as_ptr(), text.as_ptr());
        }
    }
}
#[derive(Debug)]
struct AndroidDisplay;
impl HasDisplayHandle for AndroidDisplay {
    fn display_handle(&self) -> Result<DisplayHandle<'_>, HandleError> {
        // SAFETY: Android's display token is stateless; ANativeWindow ownership
        // remains with AndroidState, beyond the lifetime of its surface.
        Ok(unsafe {
            DisplayHandle::borrow_raw(raw_window_handle::AndroidDisplayHandle::new().into())
        })
    }
}
fn configure_surface(
    surface: &wgpu::Surface<'_>,
    renderer: &Renderer,
    config: &wgpu::SurfaceConfiguration,
) -> Result<(), String> {
    let scope = renderer
        .device
        .push_error_scope(wgpu::ErrorFilter::Validation);
    surface.configure(&renderer.device, config);
    match pollster::block_on(scope.pop()) {
        Some(error) => Err(error.to_string()),
        None => Ok(()),
    }
}
struct AndroidState {
    pending_frame: Option<wgpu::SurfaceTexture>,
    presentation: crate::presentation::Presentation,
    acquire_ns: u64,
    latch_ns: u64,
    submitted_revision: u64,
    surface: Option<wgpu::Surface<'static>>,
    instance: wgpu::Instance,
    device_lost: std::sync::Arc<std::sync::atomic::AtomicBool>,
    renderer: Renderer,
    config: wgpu::SurfaceConfiguration,
    _window: NativeWindow,
}
static STATES: OnceLock<Mutex<HashMap<u64, AndroidState>>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
static PICKING: OnceLock<
    Mutex<HashMap<u64, std::sync::Arc<crate::picking_snapshot::PickingSnapshot>>>,
> = OnceLock::new();
fn picking()
-> &'static Mutex<HashMap<u64, std::sync::Arc<crate::picking_snapshot::PickingSnapshot>>> {
    PICKING.get_or_init(|| Mutex::new(HashMap::new()))
}

fn states() -> &'static Mutex<HashMap<u64, AndroidState>> {
    STATES.get_or_init(|| Mutex::new(HashMap::new()))
}
fn recover_poisoned_state() {
    if !states().is_poisoned() {
        return;
    }
    if let Err(poisoned) = states().lock() {
        let mut guard = poisoned.into_inner();
        guard.clear();
        states().clear_poison();
        log("Discarded interrupted GPU state; native renderer can be recreated");
    }
}
fn protect<T: Default>(f: impl FnOnce() -> T) -> T {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_default()
}
static ARRAY_MODE: AtomicU64 = AtomicU64::new(0);
static PREFERRED_BACKEND: AtomicU64 = AtomicU64::new(0);
fn state_for_backend(
    window: &NativeWindow,
    w: i32,
    h: i32,
    backend: wgpu::Backends,
) -> Result<AndroidState, String> {
    let mut descriptor =
        wgpu::InstanceDescriptor::new_with_display_handle(Box::new(AndroidDisplay));
    descriptor.backends = backend;
    // Older Vulkan drivers (including emulator SwiftShader) reject the
    // newly standardized WGSL OpSource debug-language enum. Source debug
    // metadata is optional; retain API validation without emitting it.
    descriptor.flags.remove(wgpu::InstanceFlags::DEBUG);
    let instance = wgpu::Instance::new(descriptor);
    // SAFETY: AndroidState owns ANativeWindow until after Surface drops.
    let target = wgpu::SurfaceTargetUnsafe::RawHandle {
        raw_display_handle: Some(raw_window_handle::AndroidDisplayHandle::new().into()),
        raw_window_handle: window.window_handle().map_err(|e| e.to_string())?.as_raw(),
    };
    let surface = unsafe { instance.create_surface_unsafe(target) }.map_err(|e| e.to_string())?;
    let selected = match ARRAY_MODE.load(Ordering::Relaxed) {
        1 => TextureBackend::Separate,
        2 => TextureBackend::Arrays,
        _ => TextureBackend::Auto,
    };
    let build = |backend| {
        pollster::block_on(Renderer::new_with_texture_backend(
            &instance,
            Some(&surface),
            None,
            w.max(1) as u32,
            h.max(1) as u32,
            MipmapMode::Auto,
            backend,
        ))
    };
    let renderer = match build(selected) {
        Ok(renderer) => renderer,
        Err(_) if selected == TextureBackend::Arrays => {
            log("Array texture startup unavailable; using compatibility textures");
            build(TextureBackend::Separate)?
        }
        Err(error) => return Err(error),
    };
    // Vulkan benefits from removing CPU submission stalls. GLES software-driver
    // timings varied/regressed between trials, so preserve its validated path.
    renderer.set_upload_batching(backend == wgpu::Backends::VULKAN);
    let config = wgpu::SurfaceConfiguration {
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT,
        format: renderer.format,
        color_space: wgpu::SurfaceColorSpace::Auto,
        view_formats: vec![],
        alpha_mode: wgpu::CompositeAlphaMode::Auto,
        width: w.max(1) as u32,
        height: h.max(1) as u32,
        desired_maximum_frame_latency: if backend == wgpu::Backends::VULKAN {
            1
        } else {
            2
        },
        present_mode: wgpu::PresentMode::AutoVsync,
    };
    configure_surface(&surface, &renderer, &config)?;
    let device_lost = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let flag = device_lost.clone();
    renderer
        .device
        .set_device_lost_callback(move |reason, _message| {
            log(&format!("GPU device lost: {reason:?}"));
            flag.store(true, Ordering::Release);
        });
    let presentation = crate::presentation::Presentation::new(&renderer.device);
    Ok(AndroidState {
        pending_frame: None,
        presentation,
        acquire_ns: 0,
        latch_ns: 0,
        submitted_revision: 0,
        surface: Some(surface),
        instance,
        device_lost,
        renderer,
        config,
        _window: window.clone(),
    })
}
fn restore_surface(state: &mut AndroidState) -> Result<(), String> {
    state.surface.take();
    let target = wgpu::SurfaceTargetUnsafe::RawHandle {
        raw_display_handle: Some(raw_window_handle::AndroidDisplayHandle::new().into()),
        raw_window_handle: state
            ._window
            .window_handle()
            .map_err(|e| e.to_string())?
            .as_raw(),
    };
    let surface =
        unsafe { state.instance.create_surface_unsafe(target) }.map_err(|e| e.to_string())?;
    configure_surface(&surface, &state.renderer, &state.config)?;
    state.surface = Some(surface);
    Ok(())
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_create(
    env: *mut c_void,
    _class: *mut c_void,
    surface: *mut c_void,
    w: i32,
    h: i32,
) -> i64 {
    protect(|| {
        recover_poisoned_state();
        let result = (|| -> Result<i64, String> {
            let window = unsafe { NativeWindow::from_surface(env.cast(), surface.cast()) }
                .ok_or("No native window")?;
            // A surface must be owned by one graphics API. Probing both at once
            // can connect the same Android buffer queue twice on emulated Vulkan.
            let backends = if PREFERRED_BACKEND.load(Ordering::Relaxed) == 2 {
                [wgpu::Backends::GL, wgpu::Backends::VULKAN]
            } else {
                [wgpu::Backends::VULKAN, wgpu::Backends::GL]
            };
            let mut selected = None;
            let mut errors = Vec::new();
            for backend in backends {
                match state_for_backend(&window, w, h, backend) {
                    Ok(state) => {
                        PREFERRED_BACKEND.store(
                            if backend == wgpu::Backends::GL { 2 } else { 1 },
                            Ordering::Relaxed,
                        );
                        log(&format!(
                            "Native surface ready: {backend:?}; cpu_mips={}",
                            state.renderer.uses_cpu_mips()
                        ));
                        selected = Some(state);
                        break;
                    }
                    Err(error) => errors.push(format!("{backend:?}: {error}")),
                }
            }
            let state = selected.ok_or_else(|| errors.join("; "))?;
            let id = NEXT.fetch_add(1, Ordering::Relaxed);
            states()
                .lock()
                .map_err(|_| "Native state poisoned")?
                .insert(id, state);
            Ok(id as i64)
        })();
        match result {
            Ok(id) => id,
            Err(e) => {
                log(&format!("Initialization failed: {e}"));
                0
            }
        }
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_destroy(
    _env: *mut c_void,
    _class: *mut c_void,
    handle: i64,
) {
    protect(|| {
        if let Ok(mut s) = states().lock() {
            if let Some(state) = s.get(&(handle as u64)) {
                state.renderer.completion_wake.set(None);
            }
            s.remove(&(handle as u64));
            let retired = picking()
                .lock()
                .ok()
                .and_then(|mut snapshots| snapshots.remove(&(handle as u64)));
            drop(retired);
        }
    });
}
fn acquire_state(s: &mut AndroidState, w: i32, h: i32) -> i32 {
    s.pending_frame.take();
    if s.device_lost.load(Ordering::Acquire) {
        return -6;
    }
    if s.surface.is_none() && restore_surface(s).is_err() {
        return -4;
    }
    if w < 1 || h < 1 {
        return 0;
    }
    if s.config.width != w as u32 || s.config.height != h as u32 {
        s.config.width = w as u32;
        s.config.height = h as u32;
        s.renderer.resize(w as u32, h as u32);
        if let Err(error) = configure_surface(s.surface.as_ref().unwrap(), &s.renderer, &s.config) {
            log(&format!("Surface configuration failed: {error}"));
            return 0;
        }
    }
    if !s.presentation.poll(s.surface.as_ref().unwrap()) {
        return -7;
    }
    let started = std::time::Instant::now();
    let acquired = s.surface.as_ref().unwrap().get_current_texture();
    s.acquire_ns = started.elapsed().as_nanos() as u64;
    let frame = match acquired {
        wgpu::CurrentSurfaceTexture::Success(frame)
        | wgpu::CurrentSurfaceTexture::Suboptimal(frame) => frame,
        wgpu::CurrentSurfaceTexture::Timeout => return -1,
        wgpu::CurrentSurfaceTexture::Occluded => return -2,
        wgpu::CurrentSurfaceTexture::Outdated => {
            if let Err(error) =
                configure_surface(s.surface.as_ref().unwrap(), &s.renderer, &s.config)
            {
                log(&format!("Surface reconfigure failed: {error}"));
                return -4;
            }
            return -3;
        }
        wgpu::CurrentSurfaceTexture::Lost => {
            if let Err(error) = restore_surface(s) {
                log(&format!("Surface recreation failed: {error}"));
            }
            return -4;
        }
        wgpu::CurrentSurfaceTexture::Validation => return -5,
    };
    s.pending_frame = Some(frame);
    1
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_acquire(
    _env: *mut c_void,
    _class: *mut c_void,
    handle: i64,
    w: i32,
    h: i32,
) -> i32 {
    protect(|| {
        recover_poisoned_state();
        let Ok(mut map) = states().lock() else {
            return 0;
        };
        let Some(s) = map.get_mut(&(handle as u64)) else {
            return 0;
        };
        acquire_state(s, w, h)
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_prepareFrame(
    _env: *mut c_void,
    _class: *mut c_void,
    handle: i64,
    nanos: i64,
    idle: u8,
) {
    protect(|| {
        if let Ok(mut map) = states().lock() {
            if let Some(s) = map.get_mut(&(handle as u64)) {
                if !s.device_lost.load(Ordering::Acquire) {
                    s.renderer
                        .prepare_frame_work(nanos.max(0) as u64, idle != 0);
                }
            }
        }
    });
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_frameWake(
    raw: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    callback: jni::sys::jobject,
) {
    let mut env = unsafe { jni::EnvUnowned::from_raw(raw) };
    let _ = env
        .with_env(|env| -> Result<(), jni::errors::Error> {
            let callback = unsafe { jni::objects::JObject::from_raw(env, callback) };
            let global = env.new_global_ref(callback)?;
            let vm = env.get_java_vm()?;
            let wake = Arc::new(move || {
                let _ = vm.attach_current_thread(|env| -> Result<(), jni::errors::Error> {
                    env.call_method(&global, jni::jni_str!("run"), jni::jni_sig!("()V"), &[])?;
                    Ok(())
                });
            });
            if let Ok(mut map) = states().lock() {
                if let Some(s) = map.get_mut(&(handle as u64)) {
                    s.renderer.completion_wake.set(Some(wake));
                }
            }
            Ok(())
        })
        .into_outcome();
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_render(
    _env: *mut c_void,
    _class: *mut c_void,
    handle: i64,
    w: i32,
    h: i32,
    lon: f64,
    lat: f64,
    zoom: f64,
    logical_zoom: f64,
    bearing: f32,
    pitch: f32,
    elevation: f32,
    camera_revision: i64,
    camera_input_ns: i64,
    camera_latch_ns: i64,
) -> i32 {
    protect(|| {
        recover_poisoned_state();
        let Ok(mut states) = states().lock() else {
            return 0;
        };
        let Some(s) = states.get_mut(&(handle as u64)) else {
            return 0;
        };
        let Some(frame) = s.pending_frame.take() else {
            return -3;
        };
        if s.config.width != w as u32 || s.config.height != h as u32 {
            return -3;
        }
        s.renderer.logical_zoom = Some(logical_zoom);
        s.renderer.render(
            &frame.texture.create_view(&Default::default()),
            mercator(lon, lat),
            zoom,
            bearing,
            pitch,
            elevation,
        );
        if let Some(snapshot) = s.renderer.picking_snapshot() {
            let retired = picking()
                .lock()
                .ok()
                .and_then(|mut snapshots| snapshots.insert(handle as u64, snapshot));
            // A retired scene may own the last references to large heightfields.
            // Release them after the input worker can acquire its snapshot.
            drop(retired);
        }
        s.latch_ns = camera_latch_ns.max(0) as u64;
        s.submitted_revision = camera_revision.max(0) as u64;
        s.presentation.submit(
            s.surface.as_ref().unwrap(),
            s.submitted_revision,
            camera_input_ns.max(0) as u64,
        );
        s.renderer.queue.present(frame);
        s.renderer
            .next_adaptive_frame()
            .map_or(1, |delay| 2 + delay.min(10000) as i32)
    })
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_upload(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    z: i32,
    x: i32,
    y: i32,
    heights: jni::sys::jobject,
    rgba: jni::sys::jobject,
) -> u8 {
    let mut env = unsafe { jni::EnvUnowned::from_raw(env) };
    match env
        .with_env(|env| -> Result<u8, jni::errors::Error> {
            if !(0..=22).contains(&z) || x < 0 || y < 0 || x >= 1i32 << z || y >= 1i32 << z {
                return Ok(0);
            }
            let heights = unsafe { jni::objects::JByteBuffer::from_raw(env, heights) };
            let rgba = unsafe { jni::objects::JByteBuffer::from_raw(env, rgba) };
            if env.get_direct_buffer_capacity(&heights)? != 129 * 129 * 4
                || env.get_direct_buffer_capacity(&rgba)? != 256 * 256 * 4
            {
                return Ok(0);
            }
            let Ok(mut states) = states().lock() else {
                return Ok(0);
            };
            let Some(s) = states.get_mut(&(handle as u64)) else {
                return Ok(0);
            };
            let id = TileId {
                z: z as u8,
                x: x as u32,
                y: y as u32,
            };
            if s.renderer.touch(id) {
                return Ok(1);
            }
            let bytes = unsafe {
                std::slice::from_raw_parts(env.get_direct_buffer_address(&heights)?, 129 * 129 * 4)
            };
            let heights: Vec<f32> = bytes
                .chunks_exact(4)
                .map(|b| f32::from_le_bytes(b.try_into().unwrap()))
                .collect();
            let rgba = unsafe {
                std::slice::from_raw_parts(env.get_direct_buffer_address(&rgba)?, 256 * 256 * 4)
            };
            Ok(s.renderer.upload(id, &heights, rgba).is_ok() as u8)
        })
        .into_outcome()
    {
        jni::Outcome::Ok(result) => result,
        _ => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_tileBudget(
    _env: *mut c_void,
    _class: *mut c_void,
    handle: i64,
    tiles: i32,
) {
    protect(|| {
        if let Ok(mut states) = states().lock() {
            if let Some(s) = states.get_mut(&(handle as u64)) {
                s.renderer.set_coordinated_retention(tiles.max(0) as usize);
            }
        }
    });
}

/// Dynamic resolution: render at `scale` now, keep `prepare`'s targets ready.
#[unsafe(no_mangle)]
pub extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_renderScale(
    _env: *mut c_void,
    _class: *mut c_void,
    handle: i64,
    scale: f32,
    prepare: f32,
) {
    protect(|| {
        if let Ok(mut states) = states().lock() {
            if let Some(s) = states.get_mut(&(handle as u64)) {
                s.renderer.set_render_scales(scale, prepare);
            }
        }
    });
}
/// Overlay texture pool size (1 MiB each), chosen from device RAM.
#[unsafe(no_mangle)]
pub extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_overlayBudget(
    _env: *mut c_void,
    _class: *mut c_void,
    handle: i64,
    layers: i32,
) {
    protect(|| {
        if let Ok(mut states) = states().lock() {
            if let Some(s) = states.get_mut(&(handle as u64)) {
                s.renderer.set_overlay_layers(layers.max(0) as usize);
            }
        }
    });
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_plan(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    w: i32,
    h: i32,
    lon: f64,
    lat: f64,
    zoom: f64,
    bearing: f32,
    pitch: f32,
    elevation: f32,
    budget: i32,
    target: f64,
    max_zoom: i32,
    margin: f64,
    output: jni::sys::jobject,
) -> i32 {
    unsafe {
        plan_into(
            env, _class, w, h, lon, lat, zoom, bearing, pitch, elevation, budget, target, max_zoom,
            margin, output, None,
        )
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_planFocused(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    w: i32,
    h: i32,
    lon: f64,
    lat: f64,
    zoom: f64,
    bearing: f32,
    pitch: f32,
    elevation: f32,
    budget: i32,
    target: f64,
    max_zoom: i32,
    margin: f64,
    focus_lon: f64,
    focus_lat: f64,
    focus_range: f64,
    output: jni::sys::jobject,
) -> i32 {
    let focus = if focus_lon.is_finite()
        && focus_lat.is_finite()
        && (-180.0..=180.0).contains(&focus_lon)
        && (-85.0..=85.0).contains(&focus_lat)
    {
        Some(crate::selection::SurfaceFocus {
            center: mercator(focus_lon, focus_lat),
            range: focus_range,
        })
    } else {
        None
    };
    unsafe {
        plan_into(
            env, _class, w, h, lon, lat, zoom, bearing, pitch, elevation, budget, target, max_zoom,
            margin, output, focus,
        )
    }
}

unsafe fn plan_into(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    w: i32,
    h: i32,
    lon: f64,
    lat: f64,
    zoom: f64,
    bearing: f32,
    pitch: f32,
    elevation: f32,
    budget: i32,
    target: f64,
    max_zoom: i32,
    margin: f64,
    output: jni::sys::jobject,
    focus: Option<crate::selection::SurfaceFocus>,
) -> i32 {
    let mut env = unsafe { jni::EnvUnowned::from_raw(env) };
    match env
        .with_env(|env| -> Result<i32, jni::errors::Error> {
            if w < 1
                || h < 1
                || ![
                    lon,
                    lat,
                    zoom,
                    bearing as f64,
                    pitch as f64,
                    elevation as f64,
                ]
                .iter()
                .all(|v| v.is_finite())
            {
                return Ok(0);
            }
            let output = unsafe { jni::objects::JByteBuffer::from_raw(env, output) };
            let capacity = env.get_direct_buffer_capacity(&output)?;
            if !target.is_finite() || !margin.is_finite() {
                return Ok(0);
            }
            let ids = crate::selection::plan_with_surface_focus(
                &crate::selection::View {
                    width: w as u32,
                    height: h as u32,
                    center: mercator(lon, lat),
                    zoom,
                    bearing,
                    pitch,
                    elevation,
                },
                budget.max(16) as usize,
                target,
                max_zoom.clamp(4, 22) as u8,
                margin,
                focus,
            );
            if capacity < ids.len() * 12 {
                return Ok(0);
            }
            let bytes = unsafe {
                std::slice::from_raw_parts_mut(
                    env.get_direct_buffer_address(&output)?,
                    ids.len() * 12,
                )
            };
            for (row, id) in bytes.chunks_exact_mut(12).zip(&ids) {
                for (slot, n) in row.chunks_exact_mut(4).zip([id.z as u32, id.x, id.y]) {
                    slot.copy_from_slice(&n.to_le_bytes());
                }
            }
            Ok(ids.len() as i32)
        })
        .into_outcome()
    {
        jni::Outcome::Ok(result) => result,
        _ => 0,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_stats(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    output: jni::sys::jobject,
) -> i32 {
    let mut env = unsafe { jni::EnvUnowned::from_raw(env) };
    match env
        .with_env(|env| -> Result<i32, jni::errors::Error> {
            let output = unsafe { jni::objects::JByteBuffer::from_raw(env, output) };
            if env.get_direct_buffer_capacity(&output)? < 12 {
                return Ok(0);
            }
            let Ok(states) = states().lock() else {
                return Ok(0);
            };
            let bytes = unsafe {
                std::slice::from_raw_parts_mut(env.get_direct_buffer_address(&output)?, 12)
            };
            let values = [
                states.len() as u32,
                states
                    .values()
                    .map(|s| s.renderer.frames)
                    .sum::<u64>()
                    .min(u32::MAX as u64) as u32,
                states
                    .values()
                    .map(|s| s.renderer.resident_count() as u32)
                    .sum(),
            ];
            for (slot, value) in bytes.chunks_exact_mut(4).zip(values) {
                slot.copy_from_slice(&value.to_le_bytes());
            }
            Ok(1)
        })
        .into_outcome()
    {
        jni::Outcome::Ok(result) => result,
        _ => 0,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_pick(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    x: f64,
    y: f64,
    output: jni::sys::jobject,
) -> u8 {
    let mut env = unsafe { jni::EnvUnowned::from_raw(env) };
    match env
        .with_env(|env| -> Result<u8, jni::errors::Error> {
            let output = unsafe { jni::objects::JByteBuffer::from_raw(env, output) };
            if env.get_direct_buffer_capacity(&output)? < 24 {
                return Ok(0);
            }
            let Ok(mut states) = states().lock() else {
                return Ok(0);
            };
            let Some(state) = states.get_mut(&(handle as u64)) else {
                return Ok(0);
            };
            let Some(point) = state.renderer.pick_at(x, y) else {
                return Ok(0);
            };
            let bytes = unsafe {
                std::slice::from_raw_parts_mut(env.get_direct_buffer_address(&output)?, 24)
            };
            for (slot, value) in bytes.chunks_exact_mut(8).zip(point) {
                slot.copy_from_slice(&value.to_le_bytes());
            }
            Ok(1)
        })
        .into_outcome()
    {
        jni::Outcome::Ok(result) => result,
        _ => 0,
    }
}

unsafe fn upload_source(
    env: *mut jni::sys::JNIEnv,
    handle: i64,
    z: i32,
    x: i32,
    y: i32,
    data: jni::sys::jobject,
    geometry: bool,
) -> i32 {
    let mut env = unsafe { jni::EnvUnowned::from_raw(env) };
    match env
        .with_env(|env| -> Result<i32, jni::errors::Error> {
            if !(0..=22).contains(&z) || x < 0 || y < 0 || x >= 1i32 << z || y >= 1i32 << z {
                return Ok(-1);
            }
            let data = unsafe { jni::objects::JByteBuffer::from_raw(env, data) };
            let size = if geometry {
                129 * 129 * 4
            } else {
                256 * 256 * 4
            };
            let capacity = env.get_direct_buffer_capacity(&data)?;
            if capacity != size && (!geometry || capacity != size + 16) {
                return Ok(-1);
            }
            let Ok(mut states) = states().lock() else {
                return Ok(-2);
            };
            let Some(state) = states.get_mut(&(handle as u64)) else {
                return Ok(-2);
            };
            if state.device_lost.load(Ordering::Acquire) {
                return Ok(-2);
            }
            let id = TileId {
                z: z as u8,
                x: x as u32,
                y: y as u32,
            };
            if if geometry {
                state.renderer.touch_geometry(id)
            } else {
                state.renderer.touch_image(id)
            } {
                return Ok(1);
            }
            let bytes = unsafe {
                std::slice::from_raw_parts(env.get_direct_buffer_address(&data)?, capacity)
            };
            let result = if geometry {
                let heights: Vec<f32> = bytes[..size]
                    .chunks_exact(4)
                    .map(|b| f32::from_le_bytes(b.try_into().unwrap()))
                    .collect();
                let result = state.renderer.upload_geometry_owned(id, heights);
                if result.is_ok() && capacity == size + 16 {
                    state.renderer.set_geometry_profile(
                        id,
                        [
                            f64::from_le_bytes(bytes[size..size + 8].try_into().unwrap()),
                            f64::from_le_bytes(bytes[size + 8..size + 16].try_into().unwrap()),
                        ],
                    );
                }
                result
            } else {
                state.renderer.upload_image(id, bytes)
            };
            Ok(match result {
                Ok(()) => 1,
                Err(UploadError::Capacity) => 0,
                Err(UploadError::InvalidGeometry | UploadError::InvalidImagery) => -1,
            })
        })
        .into_outcome()
    {
        jni::Outcome::Ok(result) => result,
        _ => -1,
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_uploadGeometry(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    z: i32,
    x: i32,
    y: i32,
    data: jni::sys::jobject,
) -> u8 {
    (unsafe { upload_source(env, handle, z, x, y, data, true) } == 1) as u8
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_uploadImage(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    z: i32,
    x: i32,
    y: i32,
    data: jni::sys::jobject,
) -> u8 {
    (unsafe { upload_source(env, handle, z, x, y, data, false) } == 1) as u8
}
// Upload acknowledgement: 1 accepted/resident, 0 retryable capacity,
// -1 invalid data, -2 unavailable/lost GPU. Legacy Boolean exports stay compatible.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_uploadGeometryResult(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    z: i32,
    x: i32,
    y: i32,
    data: jni::sys::jobject,
) -> i32 {
    unsafe { upload_source(env, handle, z, x, y, data, true) }
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_uploadImageResult(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    z: i32,
    x: i32,
    y: i32,
    data: jni::sys::jobject,
) -> i32 {
    unsafe { upload_source(env, handle, z, x, y, data, false) }
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_uploadResult(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    z: i32,
    x: i32,
    y: i32,
    heights: jni::sys::jobject,
    rgba: jni::sys::jobject,
) -> i32 {
    let geometry = unsafe { upload_source(env, handle, z, x, y, heights, true) };
    if geometry != 1 {
        return geometry;
    }
    unsafe { upload_source(env, handle, z, x, y, rgba, false) }
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_residency(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    output: jni::sys::jobject,
) -> u8 {
    let mut env = unsafe { jni::EnvUnowned::from_raw(env) };
    match env
        .with_env(|env| -> Result<u8, jni::errors::Error> {
            let output = unsafe { jni::objects::JByteBuffer::from_raw(env, output) };
            let Ok(states) = states().lock() else {
                return Ok(0);
            };
            let Some(state) = states.get(&(handle as u64)) else {
                return Ok(0);
            };
            let g = state
                .renderer
                .tiles
                .values()
                .filter(|t| t.field.complete())
                .count();
            let images = state.renderer.images.len();
            let size = 8 + (g + images) * 8;
            if env.get_direct_buffer_capacity(&output)? < size {
                return Ok(0);
            }
            let bytes = unsafe {
                std::slice::from_raw_parts_mut(env.get_direct_buffer_address(&output)?, size)
            };
            bytes[..4].copy_from_slice(&(g as u32).to_le_bytes());
            bytes[4..8].copy_from_slice(&(images as u32).to_le_bytes());
            for (slot, id) in bytes[8..].chunks_exact_mut(8).zip(
                state
                    .renderer
                    .tiles
                    .iter()
                    .filter(|(_, t)| t.field.complete())
                    .map(|(id, _)| id)
                    .chain(state.renderer.images.keys()),
            ) {
                let key = ((id.z as u64) << 44) | ((id.x as u64) << 22) | id.y as u64;
                slot.copy_from_slice(&key.to_le_bytes());
            }
            Ok(1)
        })
        .into_outcome()
    {
        jni::Outcome::Ok(result) => result,
        _ => 0,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_mipmapProof(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    output: jni::sys::jobject,
) -> u8 {
    let mut env = unsafe { jni::EnvUnowned::from_raw(env) };
    match env
        .with_env(|env| -> Result<u8, jni::errors::Error> {
            let output = unsafe { jni::objects::JByteBuffer::from_raw(env, output) };
            if env.get_direct_buffer_capacity(&output)? < 108 {
                return Ok(0);
            }
            let Ok(states) = states().lock() else {
                return Ok(0);
            };
            let Some(state) = states.get(&(handle as u64)) else {
                return Ok(0);
            };
            let report = match state.renderer.mipmap_diagnostics() {
                Ok(v) => v,
                Err(e) => {
                    log(&format!("Mipmap diagnostic failed: {e}"));
                    return Ok(0);
                }
            };
            let bytes = unsafe {
                std::slice::from_raw_parts_mut(env.get_direct_buffer_address(&output)?, 108)
            };
            for (slot, value) in bytes.chunks_exact_mut(4).zip(report.into_iter().flatten()) {
                slot.copy_from_slice(&value.to_le_bytes());
            }
            Ok(1)
        })
        .into_outcome()
    {
        jni::Outcome::Ok(v) => v,
        _ => 0,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_routes(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    input: jni::sys::jobject,
) -> u8 {
    let mut env = unsafe { jni::EnvUnowned::from_raw(env) };
    match env
        .with_env(|env| -> Result<u8, jni::errors::Error> {
            let input = unsafe { jni::objects::JByteBuffer::from_raw(env, input) };
            let size = env.get_direct_buffer_capacity(&input)?;
            if !(4..=1_601_000).contains(&size) {
                return Ok(0);
            }
            let bytes =
                unsafe { std::slice::from_raw_parts(env.get_direct_buffer_address(&input)?, size) };
            let mut offset = 4;
            let count = u32::from_le_bytes(bytes[..4].try_into().unwrap()) as usize;
            if count > 50 {
                return Ok(0);
            }
            let mut paths = Vec::with_capacity(count);
            let mut total = 0;
            for _ in 0..count {
                if offset + 12 > size {
                    return Ok(0);
                }
                let count =
                    u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap()) as usize;
                let color = u32::from_le_bytes(bytes[offset + 4..offset + 8].try_into().unwrap());
                let selected =
                    i32::from_le_bytes(bytes[offset + 8..offset + 12].try_into().unwrap());
                offset += 12;
                total += count;
                if count > 2000 || total > 100000 || offset + count * 16 > size {
                    return Ok(0);
                }
                let mut points = Vec::with_capacity(count);
                for pair in bytes[offset..offset + count * 16].chunks_exact(16) {
                    let lon = f64::from_le_bytes(pair[..8].try_into().unwrap());
                    let lat = f64::from_le_bytes(pair[8..].try_into().unwrap());
                    if !lon.is_finite()
                        || !lat.is_finite()
                        || !(165.0..=180.0).contains(&lon)
                        || !(-49.0..=-33.0).contains(&lat)
                    {
                        return Ok(0);
                    }
                    points.push(mercator(lon, lat));
                }
                offset += count * 16;
                paths.push(RoutePath {
                    points,
                    color: [
                        ((color >> 16) & 255) as f32 / 255.,
                        ((color >> 8) & 255) as f32 / 255.,
                        (color & 255) as f32 / 255.,
                        ((color >> 24) & 255) as f32 / 255.,
                    ],
                    selected: if selected >= 0 {
                        Some(selected as usize)
                    } else {
                        None
                    },
                });
            }
            if offset != size {
                return Ok(0);
            }
            let Ok(mut states) = states().lock() else {
                return Ok(0);
            };
            let Some(state) = states.get_mut(&(handle as u64)) else {
                return Ok(0);
            };
            state.renderer.set_routes(paths);
            Ok(1)
        })
        .into_outcome()
    {
        jni::Outcome::Ok(v) => v,
        _ => 0,
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_waypoints(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    output: jni::sys::jobject,
) -> i32 {
    let mut env = unsafe { jni::EnvUnowned::from_raw(env) };
    match env
        .with_env(|env| -> Result<i32, jni::errors::Error> {
            let output = unsafe { jni::objects::JByteBuffer::from_raw(env, output) };
            let size = env.get_direct_buffer_capacity(&output)?;
            let Ok(states) = states().lock() else {
                return Ok(0);
            };
            let Some(state) = states.get(&(handle as u64)) else {
                return Ok(0);
            };
            let points = state.renderer.project_waypoints();
            if points.len() * 12 > size {
                return Ok(0);
            }
            let bytes = unsafe {
                std::slice::from_raw_parts_mut(env.get_direct_buffer_address(&output)?, size)
            };
            for (slot, value) in bytes.chunks_exact_mut(4).zip(points.iter().flatten()) {
                slot.copy_from_slice(&value.to_le_bytes());
            }
            Ok(points.len() as i32)
        })
        .into_outcome()
    {
        jni::Outcome::Ok(v) => v,
        _ => 0,
    }
}

/// Immutable bounded source demand, applied by the GPU owner before uploads.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_demand(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    input: jni::sys::jobject,
) -> u8 {
    let mut env = unsafe { jni::EnvUnowned::from_raw(env) };
    match env
        .with_env(|env| -> Result<u8, jni::errors::Error> {
            let input = unsafe { jni::objects::JByteBuffer::from_raw(env, input) };
            let size = env.get_direct_buffer_capacity(&input)?;
            if !(8..=4616).contains(&size) {
                return Ok(0);
            }
            let bytes =
                unsafe { std::slice::from_raw_parts(env.get_direct_buffer_address(&input)?, size) };
            let read = |i: usize| u32::from_le_bytes(bytes[i..i + 4].try_into().unwrap());
            let g = read(0) as usize;
            let n = read(4) as usize;
            if g > 192 || n > 192 || size != 8 + (g + n) * 12 {
                return Ok(0);
            }
            let mut ids = Vec::with_capacity(g + n);
            for i in 0..g + n {
                let pos = 8 + i * 12;
                let z = read(pos);
                let x = read(pos + 4);
                let y = read(pos + 8);
                if z > 22 || x >= 1u32 << z || y >= 1u32 << z {
                    return Ok(0);
                }
                ids.push(TileId { z: z as u8, x, y });
            }
            let Ok(mut states) = states().lock() else {
                return Ok(0);
            };
            let Some(state) = states.get_mut(&(handle as u64)) else {
                return Ok(0);
            };
            state
                .renderer
                .set_demand(ids[..g].to_vec(), ids[g..].to_vec());
            Ok(1)
        })
        .into_outcome()
    {
        jni::Outcome::Ok(v) => v,
        _ => 0,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_cameraSafety(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    w: i32,
    h: i32,
    lon: f64,
    lat: f64,
    zoom: f64,
    bearing: f32,
    pitch: f32,
    elevation: f32,
    output: jni::sys::jobject,
) -> u8 {
    let mut env = unsafe { jni::EnvUnowned::from_raw(env) };
    match env
        .with_env(|env| -> Result<u8, jni::errors::Error> {
            if w <= 0
                || h <= 0
                || ![
                    lon,
                    lat,
                    zoom,
                    bearing as f64,
                    pitch as f64,
                    elevation as f64,
                ]
                .iter()
                .all(|x| x.is_finite())
            {
                return Ok(0);
            }
            let output = unsafe { jni::objects::JByteBuffer::from_raw(env, output) };
            if env.get_direct_buffer_capacity(&output)? < 12 {
                return Ok(0);
            }
            let Ok(mut states) = states().lock() else {
                return Ok(0);
            };
            let Some(state) = states.get_mut(&(handle as u64)) else {
                return Ok(0);
            };
            let values = state.renderer.camera_safety(&crate::selection::View {
                width: w as u32,
                height: h as u32,
                center: mercator(lon, lat),
                zoom,
                bearing,
                pitch,
                elevation,
            });
            let bytes = unsafe {
                std::slice::from_raw_parts_mut(env.get_direct_buffer_address(&output)?, 12)
            };
            for (slot, value) in bytes.chunks_exact_mut(4).zip(values) {
                slot.copy_from_slice(&value.to_le_bytes());
            }
            Ok(1)
        })
        .into_outcome()
    {
        jni::Outcome::Ok(result) => result,
        _ => 0,
    }
}

/// Background source-worker metadata; never analyze terrain in the frame loop.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_terrainProfile(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    data: jni::sys::jobject,
) -> u8 {
    let mut env = unsafe { jni::EnvUnowned::from_raw(env) };
    match env
        .with_env(|env| -> Result<u8, jni::errors::Error> {
            let data = unsafe { jni::objects::JByteBuffer::from_raw(env, data) };
            let size = 129 * 129 * 4;
            if env.get_direct_buffer_capacity(&data)? != size + 16 {
                return Ok(0);
            }
            let bytes = unsafe {
                std::slice::from_raw_parts_mut(env.get_direct_buffer_address(&data)?, size + 16)
            };
            let heights: Vec<_> = bytes[..size]
                .chunks_exact(4)
                .map(|b| f32::from_le_bytes(b.try_into().unwrap()))
                .collect();
            let Some(errors) = ridgeline_core::adaptive::profile(&heights) else {
                return Ok(0);
            };
            for (slot, error) in bytes[size..].chunks_exact_mut(8).zip(errors) {
                slot.copy_from_slice(&error.to_le_bytes());
            }
            Ok(1)
        })
        .into_outcome()
    {
        jni::Outcome::Ok(result) => result,
        _ => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_releaseUnused(
    _env: *mut c_void,
    _class: *mut c_void,
    handle: i64,
) {
    protect(|| {
        if let Ok(mut states) = states().lock() {
            if let Some(state) = states.get_mut(&(handle as u64)) {
                state.renderer.release_unused();
            }
        }
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_gpuBacklog(
    _env: *mut c_void,
    _class: *mut c_void,
    handle: i64,
) -> i32 {
    protect(|| {
        states()
            .lock()
            .ok()
            .and_then(|s| {
                s.get(&(handle as u64)).map(|state| {
                    if state.device_lost.load(Ordering::Acquire) {
                        0
                    } else {
                        state.renderer.gpu_backlog() as i32
                    }
                })
            })
            .unwrap_or(0)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_textureBackend(
    _env: *mut c_void,
    _class: *mut c_void,
    mode: i32,
) {
    ARRAY_MODE.store(mode.clamp(0, 2) as u64, Ordering::Relaxed);
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_configure(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    input: jni::sys::jobject,
) -> u8 {
    let mut env = unsafe { jni::EnvUnowned::from_raw(env) };
    match env
        .with_env(|env| -> Result<u8, jni::errors::Error> {
            let buffer = unsafe { jni::objects::JByteBuffer::from_raw(env, input) };
            if env.get_direct_buffer_capacity(&buffer)? != 24 {
                return Ok(0);
            }
            let bytes =
                unsafe { std::slice::from_raw_parts(env.get_direct_buffer_address(&buffer)?, 24) };
            let Some(style) = crate::MapStyle::from_packet(bytes) else {
                return Ok(0);
            };
            let Ok(mut all) = states().lock() else {
                return Ok(0);
            };
            let Some(state) = all.get_mut(&(handle as u64)) else {
                return Ok(0);
            };
            state.renderer.set_map_style(style);
            Ok(1)
        })
        .into_outcome()
    {
        jni::Outcome::Ok(v) => v,
        _ => 0,
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_clearImages(
    _env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
) {
    if let Ok(mut all) = states().lock() {
        if let Some(state) = all.get_mut(&(handle as u64)) {
            state.renderer.clear_images();
        }
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_cartography(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    input: jni::sys::jobject,
) -> u8 {
    let mut env = unsafe { jni::EnvUnowned::from_raw(env) };
    match env
        .with_env(|env| -> Result<u8, jni::errors::Error> {
            let buffer = unsafe { jni::objects::JByteBuffer::from_raw(env, input) };
            let size = env.get_direct_buffer_capacity(&buffer)?;
            if !(4..=24_000_000).contains(&size) {
                return Ok(0);
            }
            let bytes = unsafe {
                std::slice::from_raw_parts(env.get_direct_buffer_address(&buffer)?, size)
            };
            // Lines grouped by source vector tile, parsed before the renderer
            // lock: [groups] then per group z,x,y, clip[4] (f64), line count,
            // and per line n, ARGB, width, dash, gap, order, n x (f64 x, f64 y).
            let int = |at: usize| u32::from_le_bytes(bytes[at..at + 4].try_into().unwrap());
            let float = |at: usize| f32::from_le_bytes(bytes[at..at + 4].try_into().unwrap());
            let double = |at: usize| f64::from_le_bytes(bytes[at..at + 8].try_into().unwrap());
            let count = int(0) as usize;
            if count > 4096 {
                return Ok(0);
            }
            let mut at = 4;
            let mut total = 0usize;
            let mut groups = Vec::with_capacity(count);
            for _ in 0..count {
                if at + 48 > size {
                    return Ok(0);
                }
                let group_start = at;
                let (z, x, y) = (int(at), int(at + 4), int(at + 8));
                let clip = [double(at + 12), double(at + 20), double(at + 28), double(at + 36)];
                let lines_count = int(at + 44) as usize;
                at += 48;
                if lines_count > 65536 || clip.iter().any(|v| !v.is_finite()) {
                    return Ok(0);
                }
                let mut lines = Vec::with_capacity(lines_count);
                for _ in 0..lines_count {
                    if at + 24 > size {
                        return Ok(0);
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
                        || at + n * 16 > size
                        || !width.is_finite()
                        || width <= 0.
                        || width > 40.
                        || dash.iter().any(|v| !v.is_finite() || *v < 0. || *v > 400.)
                    {
                        return Ok(0);
                    }
                    let mut points = Vec::with_capacity(n);
                    for row in bytes[at..at + n * 16].chunks_exact(16) {
                        let px = f64::from_le_bytes(row[..8].try_into().unwrap());
                        let py = f64::from_le_bytes(row[8..].try_into().unwrap());
                        if !px.is_finite() || !py.is_finite() || !(0. ..=1.).contains(&px) || !(0. ..=1.).contains(&py) {
                            return Ok(0);
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
                    lines.extend(crate::overlay::Line::new(points, rgba, width, dash, order));
                }
                use std::hash::Hasher;
                let mut hasher = std::collections::hash_map::DefaultHasher::new();
                hasher.write(&bytes[group_start..at]);
                let key = crate::overlay::GroupKey { z, x, y, clip: clip.map(f64::to_bits) };
                if !lines.is_empty() {
                    groups.push(crate::overlay::Group::new(key, hasher.finish(), lines));
                }
            }
            if at != size {
                return Ok(0);
            }
            let Ok(mut all) = states().lock() else {
                return Ok(0);
            };
            let Some(state) = all.get_mut(&(handle as u64)) else {
                return Ok(0);
            };
            state.renderer.set_overlay_groups(groups);
            Ok(1)
        })
        .into_outcome()
    {
        jni::Outcome::Ok(v) => v,
        _ => 0,
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_project(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    input: jni::sys::jobject,
    output: jni::sys::jobject,
) -> i32 {
    let mut env = unsafe { jni::EnvUnowned::from_raw(env) };
    match env
        .with_env(|env| -> Result<i32, jni::errors::Error> {
            let input = unsafe { jni::objects::JByteBuffer::from_raw(env, input) };
            let output = unsafe { jni::objects::JByteBuffer::from_raw(env, output) };
            let size = env.get_direct_buffer_capacity(&input)?;
            if size % 16 != 0
                || size > 512 * 16
                || env.get_direct_buffer_capacity(&output)? < size / 16 * 12
            {
                return Ok(0);
            }
            let bytes =
                unsafe { std::slice::from_raw_parts(env.get_direct_buffer_address(&input)?, size) };
            let points: Vec<_> = bytes
                .chunks_exact(16)
                .map(|r| {
                    [
                        f64::from_le_bytes(r[..8].try_into().unwrap()),
                        f64::from_le_bytes(r[8..].try_into().unwrap()),
                    ]
                })
                .collect();
            if points
                .iter()
                .flatten()
                .any(|v| !v.is_finite() || !(0. ..=1.).contains(v))
            {
                return Ok(0);
            }
            let Ok(all) = states().lock() else {
                return Ok(0);
            };
            let Some(state) = all.get(&(handle as u64)) else {
                return Ok(0);
            };
            let projected = state.renderer.project_points(&points);
            let out = unsafe {
                std::slice::from_raw_parts_mut(
                    env.get_direct_buffer_address(&output)?,
                    projected.len() * 12,
                )
            };
            for (slot, value) in out.chunks_exact_mut(4).zip(projected.iter().flatten()) {
                slot.copy_from_slice(&value.to_le_bytes());
            }
            Ok(projected.len() as i32)
        })
        .into_outcome()
    {
        jni::Outcome::Ok(v) => v,
        _ => 0,
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_labelAtlas(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    width: i32,
    height: i32,
    rgba: jni::sys::jobject,
    metadata: jni::sys::jobject,
) -> u8 {
    let mut env = unsafe { jni::EnvUnowned::from_raw(env) };
    match env
        .with_env(|env| -> Result<u8, jni::errors::Error> {
            if !(1..=2048).contains(&width) || !(1..=2048).contains(&height) {
                return Ok(0);
            }
            let rgba = unsafe { jni::objects::JByteBuffer::from_raw(env, rgba) };
            let metadata = unsafe { jni::objects::JByteBuffer::from_raw(env, metadata) };
            let size = env.get_direct_buffer_capacity(&metadata)?;
            if !(4..=4 + 512 * 40).contains(&size)
                || env.get_direct_buffer_capacity(&rgba)? != (width * height * 4) as usize
            {
                return Ok(0);
            }
            let bytes = unsafe {
                std::slice::from_raw_parts(env.get_direct_buffer_address(&metadata)?, size)
            };
            let count = u32::from_le_bytes(bytes[..4].try_into().unwrap()) as usize;
            if count > 512 || size != 4 + count * 40 {
                return Ok(0);
            }
            let mut sprites = Vec::with_capacity(count);
            for row in bytes[4..].chunks_exact(40) {
                let point = [
                    f64::from_le_bytes(row[..8].try_into().unwrap()),
                    f64::from_le_bytes(row[8..16].try_into().unwrap()),
                ];
                let f = |i| f32::from_le_bytes(row[i..i + 4].try_into().unwrap());
                let uv = [f(16), f(20), f(24), f(28)];
                let size = [f(32), f(36)];
                if point
                    .iter()
                    .any(|v| !v.is_finite() || !(0. ..=1.).contains(v))
                    || uv.iter().any(|v| !v.is_finite() || !(0. ..=1.).contains(v))
                    || size
                        .iter()
                        .any(|v| !v.is_finite() || *v <= 0. || *v > 2048.)
                {
                    return Ok(0);
                }
                sprites.push(crate::LabelSprite { point, uv, size });
            }
            let pixels = unsafe {
                std::slice::from_raw_parts(
                    env.get_direct_buffer_address(&rgba)?,
                    (width * height * 4) as usize,
                )
            };
            let Ok(mut all) = states().lock() else {
                return Ok(0);
            };
            let Some(state) = all.get_mut(&(handle as u64)) else {
                return Ok(0);
            };
            state
                .renderer
                .set_label_atlas(width as u32, height as u32, pixels, sprites);
            Ok(1)
        })
        .into_outcome()
    {
        jni::Outcome::Ok(v) => v,
        _ => 0,
    }
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_placeLabels(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    input: jni::sys::jobject,
) -> u8 {
    let mut env = unsafe { jni::EnvUnowned::from_raw(env) };
    match env
        .with_env(|env| -> Result<u8, jni::errors::Error> {
            let buffer = unsafe { jni::objects::JByteBuffer::from_raw(env, input) };
            let size = env.get_direct_buffer_capacity(&buffer)?;
            if !(4..=4 + 512 * 12).contains(&size) {
                return Ok(0);
            }
            let bytes = unsafe {
                std::slice::from_raw_parts(env.get_direct_buffer_address(&buffer)?, size)
            };
            let count = u32::from_le_bytes(bytes[..4].try_into().unwrap()) as usize;
            if count > 512 || size != 4 + count * 12 {
                return Ok(0);
            }
            let mut selection = Vec::with_capacity(count);
            for row in bytes[4..].chunks_exact(12) {
                let index = u32::from_le_bytes(row[..4].try_into().unwrap()) as usize;
                let offset = [
                    f32::from_le_bytes(row[4..8].try_into().unwrap()),
                    f32::from_le_bytes(row[8..].try_into().unwrap()),
                ];
                if index >= 512 || offset.iter().any(|v| !v.is_finite() || v.abs() > 2048.) {
                    return Ok(0);
                }
                selection.push((index, offset));
            }
            let Ok(mut all) = states().lock() else {
                return Ok(0);
            };
            let Some(state) = all.get_mut(&(handle as u64)) else {
                return Ok(0);
            };
            state.renderer.select_labels(selection);
            Ok(1)
        })
        .into_outcome()
    {
        jni::Outcome::Ok(v) => v,
        _ => 0,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_inspect(
    _env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    longitude: f64,
    latitude: f64,
    enabled: u8,
) -> u8 {
    let point = if enabled != 0 {
        if !longitude.is_finite()
            || !latitude.is_finite()
            || !(165. ..=180.).contains(&longitude)
            || !(-49. ..=-33.).contains(&latitude)
        {
            return 0;
        };
        Some(mercator(longitude, latitude))
    } else {
        None
    };
    let Ok(mut all) = states().lock() else {
        return 0;
    };
    let Some(state) = all.get_mut(&(handle as u64)) else {
        return 0;
    };
    state.renderer.inspect_point(point);
    1
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_location(
    _env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    longitude: f64,
    latitude: f64,
    enabled: u8,
) -> u8 {
    let point = if enabled != 0 {
        if !longitude.is_finite()
            || !latitude.is_finite()
            || !(165. ..=180.).contains(&longitude)
            || !(-49. ..=-33.).contains(&latitude)
        {
            return 0;
        };
        Some(mercator(longitude, latitude))
    } else {
        None
    };
    let Ok(mut all) = states().lock() else {
        return 0;
    };
    let Some(state) = all.get_mut(&(handle as u64)) else {
        return 0;
    };
    state.renderer.location_point(point);
    1
}
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_clearGeometry(
    _env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
) {
    if let Ok(mut all) = states().lock() {
        if let Some(state) = all.get_mut(&(handle as u64)) {
            state.renderer.clear_geometry();
        }
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_pickSnapshot(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    x: f64,
    y: f64,
    width: i32,
    height: i32,
    lon: f64,
    lat: f64,
    zoom: f64,
    bearing: f32,
    pitch: f32,
    elevation: f32,
    three_d: u8,
    relief: f32,
    output: jni::sys::jobject,
) -> u8 {
    let snapshot = picking()
        .lock()
        .ok()
        .and_then(|s| s.get(&(handle as u64)).cloned());
    let Some(snapshot) = snapshot else {
        return 0;
    };
    let v = &snapshot.view;
    if !snapshot.matches_style(three_d != 0, relief)
        || v.width != width as u32
        || v.height != height as u32
        || v.center != mercator(lon, lat)
        || v.zoom != zoom
        || v.bearing != bearing
        || v.pitch != pitch
        || v.elevation != elevation
    {
        return 0;
    }
    let Some(point) = snapshot.pick(x, y) else {
        return 0;
    };
    let mut env = unsafe { jni::EnvUnowned::from_raw(env) };
    match env
        .with_env(|env| -> Result<u8, jni::errors::Error> {
            let buffer = unsafe { jni::objects::JByteBuffer::from_raw(env, output) };
            if env.get_direct_buffer_capacity(&buffer)? < 24 {
                return Ok(0);
            }
            let bytes = unsafe {
                std::slice::from_raw_parts_mut(env.get_direct_buffer_address(&buffer)?, 24)
            };
            for (slot, value) in bytes.chunks_exact_mut(8).zip(point) {
                slot.copy_from_slice(&value.to_le_bytes());
            }
            Ok(1)
        })
        .into_outcome()
    {
        jni::Outcome::Ok(v) => v,
        _ => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_uploadPlaneResult(
    _env: *mut c_void,
    _class: *mut c_void,
    handle: i64,
    z: i32,
    x: i32,
    y: i32,
) -> i32 {
    if !(0..=22).contains(&z) || x < 0 || y < 0 || x >= 1i32 << z || y >= 1i32 << z {
        return -1;
    }
    let Ok(mut all) = states().lock() else {
        return -2;
    };
    let Some(s) = all.get_mut(&(handle as u64)) else {
        return -2;
    };
    if s.device_lost.load(Ordering::Acquire) {
        return -2;
    }
    match s.renderer.upload_plane(TileId {
        z: z as u8,
        x: x as u32,
        y: y as u32,
    }) {
        Ok(()) => 1,
        Err(UploadError::Capacity) => 0,
        Err(_) => -1,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_labelMetadata(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    input: jni::sys::jobject,
) -> u8 {
    let mut env = unsafe { jni::EnvUnowned::from_raw(env) };
    match env
        .with_env(|env| -> Result<u8, jni::errors::Error> {
            let input = unsafe { jni::objects::JByteBuffer::from_raw(env, input) };
            let size = env.get_direct_buffer_capacity(&input)?;
            if !(4..=4 + 512 * 40).contains(&size) {
                return Ok(0);
            }
            let bytes =
                unsafe { std::slice::from_raw_parts(env.get_direct_buffer_address(&input)?, size) };
            let count = u32::from_le_bytes(bytes[..4].try_into().unwrap()) as usize;
            if count > 512 || size != 4 + count * 40 {
                return Ok(0);
            }
            let mut sprites = Vec::with_capacity(count);
            for row in bytes[4..].chunks_exact(40) {
                let point = [
                    f64::from_le_bytes(row[..8].try_into().unwrap()),
                    f64::from_le_bytes(row[8..16].try_into().unwrap()),
                ];
                let f = |i| f32::from_le_bytes(row[i..i + 4].try_into().unwrap());
                let uv = [f(16), f(20), f(24), f(28)];
                let size = [f(32), f(36)];
                if point
                    .iter()
                    .any(|v| !v.is_finite() || !(0. ..=1.).contains(v))
                    || uv.iter().any(|v| !v.is_finite() || !(0. ..=1.).contains(v))
                    || size
                        .iter()
                        .any(|v| !v.is_finite() || *v <= 0. || *v > 2048.)
                {
                    return Ok(0);
                }
                sprites.push(crate::LabelSprite { point, uv, size });
            }
            let Ok(mut all) = states().lock() else {
                return Ok(0);
            };
            let Some(state) = all.get_mut(&(handle as u64)) else {
                return Ok(0);
            };
            state.renderer.set_label_metadata(sprites);
            Ok(1)
        })
        .into_outcome()
    {
        jni::Outcome::Ok(v) => v,
        _ => 0,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_uploadInterior(
    env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    z: i32,
    x: i32,
    y: i32,
    token: i64,
    input: jni::sys::jobject,
) -> u8 {
    if !(1..=19).contains(&z) || x < 0 || y < 0 || x >= 1i32 << z || y >= 1i32 << z {
        return 0;
    }
    let mut env = unsafe { jni::EnvUnowned::from_raw(env) };
    match env
        .with_env(|env| -> Result<u8, jni::errors::Error> {
            let input = unsafe { jni::objects::JByteBuffer::from_raw(env, input) };
            let size = 16 + 129 * 129 * 4;
            if env.get_direct_buffer_capacity(&input)? != size {
                return Ok(0);
            }
            let bytes =
                unsafe { std::slice::from_raw_parts(env.get_direct_buffer_address(&input)?, size) };
            let mut rect = [0; 4];
            for (i, row) in bytes[..16].chunks_exact(4).enumerate() {
                rect[i] = u32::from_le_bytes(row.try_into().unwrap()) as usize;
            }
            let heights = bytes[16..]
                .chunks_exact(4)
                .map(|row| f32::from_le_bytes(row.try_into().unwrap()))
                .collect();
            let Ok(mut all) = states().lock() else {
                return Ok(0);
            };
            let Some(state) = all.get_mut(&(handle as u64)) else {
                return Ok(0);
            };
            if state.device_lost.load(Ordering::Acquire) {
                return Ok(0);
            }
            Ok(state
                .renderer
                .upload_interior(
                    TileId {
                        z: z as u8,
                        x: x as u32,
                        y: y as u32,
                    },
                    token as u64,
                    heights,
                    rect,
                )
                .is_ok() as u8)
        })
        .into_outcome()
    {
        jni::Outcome::Ok(v) => v,
        _ => 0,
    }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_clearInterior(
    _env: *mut c_void,
    _class: *mut c_void,
    handle: i64,
    z: i32,
    x: i32,
    y: i32,
    token: i64,
) {
    if !(1..=19).contains(&z) || x < 0 || y < 0 || x >= 1i32 << z || y >= 1i32 << z {
        return;
    }
    if let Ok(mut all) = states().lock() {
        if let Some(state) = all.get_mut(&(handle as u64)) {
            state.renderer.clear_interior(
                TileId {
                    z: z as u8,
                    x: x as u32,
                    y: y as u32,
                },
                token as u64,
            );
        }
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_frameStats(
    raw: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    handle: i64,
    buffer: jni::sys::jobject,
) {
    let mut env = unsafe { jni::EnvUnowned::from_raw(raw) };
    let _ = env
        .with_env(|env| -> Result<(), jni::errors::Error> {
            let output = unsafe { jni::objects::JByteBuffer::from_raw(env, buffer) };
            if env.get_direct_buffer_capacity(&output)? < 112 {
                return Ok(());
            }
            let bytes = unsafe {
                std::slice::from_raw_parts_mut(env.get_direct_buffer_address(&output)?, 112)
            };
            if let Ok(map) = states().lock() {
                if let Some(s) = map.get(&(handle as u64)) {
                    let values = [
                        s.acquire_ns,
                        s.latch_ns,
                        s.submitted_revision,
                        s.presentation.actual,
                        s.presentation.input_to_display,
                        s.presentation.revision,
                        s.presentation.pending(),
                        s.presentation.refresh,
                        s.presentation.available() as u64,
                        s.renderer.gpu_backlog() as u64,
                        s.renderer.route_gpu.uploaded_bytes,
                        s.renderer.overlay.texture_bytes(),
                        s.renderer.route_gpu.uploads,
                        s.renderer.overlay.renders,
                    ];
                    for (chunk, value) in bytes.chunks_exact_mut(8).zip(values) {
                        chunk.copy_from_slice(&value.to_le_bytes());
                    }
                }
            }
            Ok(())
        })
        .into_outcome();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_nz_ridgeline_nativeapp_NativeBridge_discardFrame(
    _env: *mut c_void,
    _class: *mut c_void,
    handle: i64,
) {
    protect(|| {
        if let Ok(mut map) = states().lock() {
            if let Some(s) = map.get_mut(&(handle as u64)) {
                s.pending_frame.take();
            }
        }
    });
}
