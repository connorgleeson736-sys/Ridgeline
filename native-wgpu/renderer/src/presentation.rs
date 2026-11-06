//! Real Vulkan display feedback, when the active swapchain supports it.
//! GPU completion and queue.present() return are never reported as display time.
use ash::{google::display_timing, vk};
use std::{
    collections::VecDeque,
    time::{Duration, Instant},
};
#[derive(Default)]
pub(crate) struct Presentation {
    api: Option<display_timing::Device>,
    swapchain: Option<vk::SwapchainKHR>,
    pending: VecDeque<(u32, u64, u64)>,
    next: u32,
    feedback_at: Option<Instant>,
    pub actual: u64,
    pub input_to_display: u64,
    pub revision: u64,
    pub refresh: u64,
}
impl Presentation {
    pub fn new(device: &wgpu::Device) -> Self {
        let mut s = Self::default();
        if device
            .features()
            .contains(wgpu::Features::VULKAN_GOOGLE_DISPLAY_TIMING)
        {
            // Read-only HAL access; ownership and presentation stay with wgpu.
            if let Some(raw) = unsafe { device.as_hal::<wgpu::hal::api::Vulkan>() } {
                s.api = Some(display_timing::Device::new(
                    raw.shared_instance().raw_instance(),
                    raw.raw_device(),
                ));
            }
        }
        s
    }
    pub fn poll(&mut self, surface: &wgpu::Surface<'_>) -> bool {
        let Some(api) = &self.api else {
            return true;
        };
        let Some(raw) = (unsafe { surface.as_hal::<wgpu::hal::api::Vulkan>() }) else {
            return true;
        };
        let Some(swapchain) = raw.raw_native_swapchain() else {
            return true;
        };
        if self.swapchain != Some(swapchain) {
            self.swapchain = Some(swapchain);
            self.pending.clear();
            self.feedback_at = Some(Instant::now());
            self.actual = 0;
            self.input_to_display = 0;
            self.revision = 0;
        }
        match unsafe { api.get_past_presentation_timing(swapchain) } {
            Ok(rows) => {
                for row in rows {
                    if let Some(index) = self.pending.iter().position(|p| p.0 == row.present_id) {
                        let (_, revision, input) = self.pending[index];
                        for _ in 0..=index {
                            self.pending.pop_front();
                        }
                        let changed = self.actual == 0 || self.revision != revision;
                        self.actual = row.actual_present_time;
                        self.revision = revision;
                        // Android nanoTime and VK timing use the monotonic clock.
                        // Reject incompatible/invalid driver clock observations.
                        if changed {
                            self.input_to_display = if row.actual_present_time >= input
                                && row.actual_present_time - input < 10_000_000_000
                            {
                                row.actual_present_time - input
                            } else {
                                0
                            };
                        }
                        self.feedback_at = Some(Instant::now());
                    }
                }
            }
            Err(_) => {
                self.api = None;
                self.pending.clear();
                return true;
            }
        }
        if let Ok(cycle) = unsafe { api.get_refresh_cycle_duration(swapchain) } {
            self.refresh = cycle.refresh_duration;
        }
        // Broken/stale feedback must never freeze camera presentation. Fall
        // back to swapchain/vsync pacing, explicitly marked unavailable.
        if self.pending.len() >= 2
            && self
                .feedback_at
                .is_some_and(|at| at.elapsed() > Duration::from_millis(100))
        {
            self.api = None;
            self.pending.clear();
            return true;
        }
        self.pending.len() < 2
    }
    pub fn submit(&mut self, surface: &wgpu::Surface<'_>, revision: u64, input: u64) {
        if self.api.is_none() {
            return;
        }
        if let Some(raw) = unsafe { surface.as_hal::<wgpu::hal::api::Vulkan>() } {
            self.next = self.next.wrapping_add(1).max(1);
            raw.set_next_present_time(vk::PresentTimeGOOGLE {
                present_id: self.next,
                desired_present_time: 0,
            });
            self.pending.push_back((self.next, revision, input));
        }
    }
    pub fn pending(&self) -> u64 {
        self.pending.len() as u64
    }
    pub fn available(&self) -> bool {
        self.api.is_some()
    }
}
