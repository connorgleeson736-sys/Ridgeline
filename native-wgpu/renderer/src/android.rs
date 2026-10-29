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
    surface: wgpu::Surface<'static>,
    renderer: Renderer,
    config: wgpu::SurfaceConfiguration,
    _window: NativeWindow,
}
static STATES: OnceLock<Mutex<HashMap<u64, AndroidState>>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
fn states() -> &'static Mutex<HashMap<u64, AndroidState>> {
    STATES.get_or_init(|| Mutex::new(HashMap::new()))
}
fn protect<T: Default>(f: impl FnOnce() -> T) -> T {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_default()
}
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
    let renderer = pollster::block_on(Renderer::new(
        &instance,
        Some(&surface),
        None,
        w.max(1) as u32,
        h.max(1) as u32,
    ))?;
    let config = wgpu::SurfaceConfiguration {
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT,
        format: renderer.format,
        color_space: wgpu::SurfaceColorSpace::Auto,
        view_formats: vec![],
        alpha_mode: wgpu::CompositeAlphaMode::Auto,
        width: w.max(1) as u32,
        height: h.max(1) as u32,
        desired_maximum_frame_latency: 2,
        present_mode: wgpu::PresentMode::AutoVsync,
    };
    configure_surface(&surface, &renderer, &config)?;
    Ok(AndroidState {
        surface,
        renderer,
        config,
        _window: window.clone(),
    })
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
            s.remove(&(handle as u64));
        }
    });
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
) -> u8 {
    protect(|| {
        let Ok(mut states) = states().lock() else {
            return 0;
        };
        let Some(s) = states.get_mut(&(handle as u64)) else {
            return 0;
        };
        if w < 1 || h < 1 {
            return 0;
        }
        if s.config.width != w as u32 || s.config.height != h as u32 {
            s.config.width = w as u32;
            s.config.height = h as u32;
            s.renderer.resize(w as u32, h as u32);
            if let Err(error) = configure_surface(&s.surface, &s.renderer, &s.config) {
                log(&format!("Surface configuration failed: {error}"));
                return 0;
            }
        }
        let frame = match s.surface.get_current_texture() {
            wgpu::CurrentSurfaceTexture::Success(t)
            | wgpu::CurrentSurfaceTexture::Suboptimal(t) => t,
            wgpu::CurrentSurfaceTexture::Outdated | wgpu::CurrentSurfaceTexture::Lost => {
                if let Err(error) = configure_surface(&s.surface, &s.renderer, &s.config) {
                    log(&format!("Surface configuration failed: {error}"));
                    return 0;
                }
                return 0;
            }
            _ => return 0,
        };
        s.renderer.logical_zoom = Some(logical_zoom);
        s.renderer.render(
            &frame.texture.create_view(&Default::default()),
            mercator(lon, lat),
            zoom,
            bearing,
            pitch,
            elevation,
        );
        s.renderer.queue.present(frame);
        1
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
                s.renderer.set_tile_budget(tiles.max(0) as usize);
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
    output: jni::sys::jobject,
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
            if !target.is_finite() {
                return Ok(0);
            }
            let ids = crate::selection::plan_with_target(
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
) -> u8 {
    let mut env = unsafe { jni::EnvUnowned::from_raw(env) };
    match env
        .with_env(|env| -> Result<u8, jni::errors::Error> {
            if !(0..=22).contains(&z) || x < 0 || y < 0 || x >= 1i32 << z || y >= 1i32 << z {
                return Ok(0);
            }
            let data = unsafe { jni::objects::JByteBuffer::from_raw(env, data) };
            let size = if geometry {
                129 * 129 * 4
            } else {
                256 * 256 * 4
            };
            if env.get_direct_buffer_capacity(&data)? != size {
                return Ok(0);
            }
            let Ok(mut states) = states().lock() else {
                return Ok(0);
            };
            let Some(state) = states.get_mut(&(handle as u64)) else {
                return Ok(0);
            };
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
            let bytes =
                unsafe { std::slice::from_raw_parts(env.get_direct_buffer_address(&data)?, size) };
            Ok(if geometry {
                let heights: Vec<f32> = bytes
                    .chunks_exact(4)
                    .map(|b| f32::from_le_bytes(b.try_into().unwrap()))
                    .collect();
                state.renderer.upload_geometry(id, &heights)
            } else {
                state.renderer.upload_image(id, bytes)
            }
            .is_ok() as u8)
        })
        .into_outcome()
    {
        jni::Outcome::Ok(result) => result,
        _ => 0,
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
    unsafe { upload_source(env, handle, z, x, y, data, true) }
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
    unsafe { upload_source(env, handle, z, x, y, data, false) }
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
            let g = state.renderer.tiles.len();
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
                    .keys()
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
