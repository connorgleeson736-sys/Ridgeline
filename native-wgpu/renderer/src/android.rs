use super::*;
use ndk::native_window::NativeWindow;
use raw_window_handle::HasWindowHandle;
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
            let mut descriptor = wgpu::InstanceDescriptor::new_without_display_handle();
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
            let surface =
                unsafe { instance.create_surface_unsafe(target) }.map_err(|e| e.to_string())?;
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
            surface.configure(&renderer.device, &config);
            let id = NEXT.fetch_add(1, Ordering::Relaxed);
            states()
                .lock()
                .map_err(|_| "Native state poisoned")?
                .insert(
                    id,
                    AndroidState {
                        surface,
                        renderer,
                        config,
                        _window: window,
                    },
                );
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
            s.surface.configure(&s.renderer.device, &s.config);
        }
        let frame = match s.surface.get_current_texture() {
            wgpu::CurrentSurfaceTexture::Success(t)
            | wgpu::CurrentSurfaceTexture::Suboptimal(t) => t,
            wgpu::CurrentSurfaceTexture::Outdated | wgpu::CurrentSurfaceTexture::Lost => {
                s.surface.configure(&s.renderer.device, &s.config);
                return 0;
            }
            _ => return 0,
        };
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
            let ids = crate::selection::plan(
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
