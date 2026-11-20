//! One real remaining allowance, shared by optional CPU/GPU preparation.
use std::{
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

pub(crate) type Wake = Arc<dyn Fn() + Send + Sync>;
#[derive(Clone, Default)]
pub(crate) struct CompletionWake(Arc<Mutex<Option<Wake>>>);
impl CompletionWake {
    pub fn set(&self, wake: Option<Wake>) {
        *self.0.lock().unwrap() = wake;
    }
    pub fn notify(&self) {
        let wake = self.0.lock().unwrap().clone();
        if let Some(wake) = wake {
            wake();
        }
    }
}
pub(crate) struct Budget {
    deadline: std::cell::Cell<Instant>,
    idle: bool,
    allowance: u64,
}
impl Budget {
    pub fn new(nanos: u64) -> Self {
        Self::with_idle(nanos, false)
    }
    pub fn with_idle(nanos: u64, idle: bool) -> Self {
        Self {
            deadline: std::cell::Cell::new(
                Instant::now() + Duration::from_nanos(nanos.min(4_000_000)),
            ),
            idle,
            allowance: nanos,
        }
    }
    pub fn maintenance(&self) -> bool {
        self.idle && self.allowance >= 1_000_000 && self.allows(100_000)
    }
    pub fn charge(&self, nanos: u64) {
        self.deadline.set(
            self.deadline
                .get()
                .checked_sub(Duration::from_nanos(nanos))
                .unwrap_or_else(Instant::now),
        );
    }
    pub fn allows(&self, predicted: u64) -> bool {
        self.deadline
            .get()
            .saturating_duration_since(Instant::now())
            .as_nanos()
            > predicted as u128
    }
    pub fn bytes(&self, nanos_per_byte: f64, stride: usize) -> usize {
        let remaining = self
            .deadline
            .get()
            .saturating_duration_since(Instant::now())
            .as_nanos() as f64;
        let bytes = ((remaining - 100_000.).max(0.) / nanos_per_byte.max(1.)).min(65_536.) as usize;
        bytes / stride * stride
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn zero_deadline_never_admits_first_work() {
        let b = Budget::new(0);
        assert!(!b.allows(0));
        assert_eq!(b.bytes(4., 64), 0);
    }
    #[test]
    fn wake_releases_lock_before_callback() {
        let w = CompletionWake::default();
        let c = w.clone();
        w.set(Some(Arc::new(move || c.set(None))));
        w.notify();
        w.notify();
    }
}
