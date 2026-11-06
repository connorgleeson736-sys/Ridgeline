//! Bounded latest-only CPU work, independent of renderer-state and UI locks.
use std::sync::{
    Arc, Condvar, Mutex,
    atomic::{AtomicBool, AtomicU64, Ordering},
};
struct Shared<I, O> {
    revision: AtomicU64,
    stopped: AtomicBool,
    input: Mutex<Option<(u64, I)>>,
    output: Mutex<Option<(u64, O)>>,
    ready: Condvar,
    wake: crate::frame_work::CompletionWake,
}
pub(crate) struct LatestWorker<I, O> {
    state: Arc<Shared<I, O>>,
}
impl<I: Send + 'static, O: Send + 'static> LatestWorker<I, O> {
    pub fn new(
        name: &str,
        wake: crate::frame_work::CompletionWake,
        mut build: impl FnMut(I, &dyn Fn() -> bool) -> Option<O> + Send + 'static,
    ) -> Option<Self> {
        let state = Arc::new(Shared {
            revision: AtomicU64::new(0),
            stopped: AtomicBool::new(false),
            input: Mutex::new(None),
            output: Mutex::new(None),
            ready: Condvar::new(),
            wake,
        });
        let s = state.clone();
        std::thread::Builder::new()
            .name(name.into())
            .spawn(move || {
                #[cfg(target_os = "android")]
                unsafe {
                    unsafe extern "C" {
                        fn setpriority(which: i32, who: u32, priority: i32) -> i32;
                    }
                    setpriority(0, 0, 10);
                }
                loop {
                    let (revision, job) = {
                        let mut slot = s.input.lock().unwrap();
                        while slot.is_none() && !s.stopped.load(Ordering::Acquire) {
                            slot = s.ready.wait(slot).unwrap();
                        }
                        if s.stopped.load(Ordering::Acquire) {
                            return;
                        }
                        slot.take().unwrap()
                    };
                    let valid = || {
                        !s.stopped.load(Ordering::Acquire)
                            && s.revision.load(Ordering::Acquire) == revision
                    };
                    if let Some(output) = build(job, &valid) {
                        if valid() {
                            *s.output.lock().unwrap() = Some((revision, output));
                            s.wake.notify();
                        }
                    }
                }
            })
            .ok()?;
        Some(Self { state })
    }
    pub fn submit(&self, job: I) {
        let revision = self.state.revision.fetch_add(1, Ordering::AcqRel) + 1;
        self.state.output.lock().unwrap().take();
        *self.state.input.lock().unwrap() = Some((revision, job));
        self.state.ready.notify_one();
    }
    pub fn take(&self) -> Option<O> {
        self.state
            .output
            .lock()
            .unwrap()
            .take()
            .filter(|(r, _)| *r == self.state.revision.load(Ordering::Acquire))
            .map(|(_, o)| o)
    }
    pub fn cancel(&self) {
        self.state.revision.fetch_add(1, Ordering::AcqRel);
        self.state.output.lock().unwrap().take();
        self.state.input.lock().unwrap().take();
    }
}
impl<I, O> Drop for LatestWorker<I, O> {
    fn drop(&mut self) {
        let mut input = self.state.input.lock().unwrap();
        self.state.stopped.store(true, Ordering::Release);
        input.take();
        self.state.ready.notify_one();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn additional_latest_result_wakes_without_polling() {
        let (started_tx, started) = std::sync::mpsc::channel();
        let (release_tx, release) = std::sync::mpsc::channel();
        let (wake_tx, woken) = std::sync::mpsc::channel();
        let wake = crate::frame_work::CompletionWake::default();
        wake.set(Some(Arc::new(move || {
            let _ = wake_tx.send(());
        })));
        let worker = LatestWorker::new("latest-proof", wake, move |value, valid| {
            if value == 1 {
                started_tx.send(()).unwrap();
                release.recv().unwrap();
            }
            valid().then_some(value)
        })
        .unwrap();
        worker.submit(1);
        started
            .recv_timeout(std::time::Duration::from_secs(2))
            .unwrap();
        worker.submit(2);
        worker.submit(3);
        release_tx.send(()).unwrap();
        woken
            .recv_timeout(std::time::Duration::from_secs(2))
            .unwrap();
        assert_eq!(worker.take(), Some(3));
        worker.cancel();
        assert_eq!(worker.take(), None);
    }
}
