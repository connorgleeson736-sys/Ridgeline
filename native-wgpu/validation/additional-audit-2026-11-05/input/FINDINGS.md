# Ridgeline: additional native renderer latency audit

Reviewed 4 November 2026 UTC. Repository: `connorgleeson736-sys/Ridgeline`; branch: `native-renderer-performance`; exact head: `3f4d9b8dedf8e3c53ed24f1c148712def00d1692`.

**The strongest new opportunity is to make the path from current input to presentation independent of optional overlay work, then sample the camera after surface acquisition.** The current camera-first policy does not extend through the Rust renderer. There are also two substantial, quantifiable work reductions: incremental overlay uploads and immediate application of the existing adaptive rule to newly visible terrain.

This is a source audit with controlled experiments, not a measured phone speedup. No production changes were committed or APK built. The executable checks are Python ports and byte-stream experiments; they do not run the Android/JNI/wgpu application. There was no phone or emulator available. Percentages below concern specific work, not total frame time or FPS.

| Priority | Remaining issue | Concrete opportunity | Evidence status |
|---|---|---|---|
| 1 | Camera captured before surface acquisition; GPU completion treated as backlog control | Sample current input after acquisition and pace against presentation | Source-confirmed ordering; phone impact conditional on acquisition/presentation delays |
| 1 | Completed cartography still replaces the entire GPU line buffer | Transfer changed fragments; apply the frame budget inside Rust; wake on completion | Source-confirmed; 96.875% payload reduction in a stated synthetic workload |
| 2 | New terrain starts at full density and waits 350 ms to coarsen | Initialize unseen terrain with the already-qualified adaptive level | Source-confirmed; 91.82% fewer surface triangles for a full patch eligible for step four |
| 2 | First optional upload ignores its predicted cost, including at zero real budget | Enforce an actual frame deadline on the first optional operation too | Counterexample reproduced from the admission predicate |

## Source and release alignment

The branch's current published native checkpoint is **0.1.11-border-recovery**, version code 12. Its [release record](https://github.com/connorgleeson736-sys/Ridgeline/blob/3f4d9b8dedf8e3c53ed24f1c148712def00d1692/native-wgpu/validation/border-recovery-2026-11-03/release.json) identifies app source `aab3c12c38db3304ebd0f628f3c674d5e0922962` and native library build source `c0a420d90680660bbb7dd883c0b33de477774767`.

GitHub comparisons confirmed that the two commits after the app source contain documentation, validation and checkpoint files, with no application code changes. The comparison from the native build source to the app release source contains no Rust renderer/core changes. Thus the audited source is aligned with the recorded release sources. The release record reports APK SHA-256 `f871578201586c074bad30003d95eb584f454ec1ad45ef7275a60c4db2995c00`; this audit did **not** independently download or verify that APK. The binary media URL was unsupported by the available GitHub fetch tool. Nothing here establishes which version is installed on the user's phone.

The repository's `android-preview-download` reference was refreshed as required by its instructions; it describes the separate Capacitor 0.18.0 app. Native version claims above use the native checkpoint records. Source files used in the experiments have SHA-256 hashes in `results.json`; the fetched source snapshot was also checked against the repository's Git blob hashes.

I compared the earlier native audit and the current implementation. Immutable picking snapshots, route-only invalidation, boundary indexing, background cartographic draping, stable label updates and shared planar resources are already present. Those are not presented as new discoveries. The existing two-triangle planar proposal is also not repeated.

## 1. Remove stale camera input across surface acquisition

**Mechanism.** `TerrainView.drawFrame()` captures `requested=cameraPose`, applies clearance, and passes the resulting values into `NativeBridge.render`. Rust then calls `get_current_texture()`. If acquisition waits, input published during that wait cannot affect the acquired frame: the camera arguments have already been copied. The renderer subsequently performs cover updates, synchronous route rebuilds and completed-overlay uploads before encoding the frame.

Separately, `gpuBacklog` measures outstanding GPU submissions. It is decremented by `on_submitted_work_done`. A completed GPU submission can still be waiting to be displayed. `presentedPose` is assigned when JNI returns; it is not an actual presentation timestamp. Choreographer scheduling plus this counter therefore does not establish that there are no old frames in the display queue.

Sources: [camera snapshot and JNI call](https://github.com/connorgleeson736-sys/Ridgeline/blob/3f4d9b8dedf8e3c53ed24f1c148712def00d1692/native-wgpu/android/app/src/main/java/nz/ridgeline/nativeapp/TerrainView.kt#L249-L288), [surface acquisition before rendering](https://github.com/connorgleeson736-sys/Ridgeline/blob/3f4d9b8dedf8e3c53ed24f1c148712def00d1692/native-wgpu/renderer/src/android.rs#L296-L340), [completion counter](https://github.com/connorgleeson736-sys/Ridgeline/blob/3f4d9b8dedf8e3c53ed24f1c148712def00d1692/native-wgpu/renderer/src/lib.rs#L1885-L1896).

**Implementation direction.** Use a latest immutable camera packet, published without taking the long renderer-state mutex. Prepare admitted optional work, acquire the surface, then latch the latest camera and perform its clearance/culling/projection before encoding. Use the same camera revision for the terrain draw, labels, picking snapshot and submission bookkeeping. Do not make a synchronous round trip to the Android UI thread after acquisition. A split acquire/finish API is another option if surface lifetime and cancellation are controlled carefully.

Add presentation-aware pacing and separate submitted/GPU-complete/presented metrics. Android frame pacing integration would need to cooperate with wgpu's actual presentation backend; simply adding the library dependency is insufficient. An A/B trial of `desired_maximum_frame_latency=1` on Vulkan is reasonable, but is not a universal fix: wgpu says this hint is clamped by backend support, can sacrifice throughput, and is ignored by OpenGL. Reducing the swapchain while still capturing input before the blocking call can preserve the stale-input problem.

**Expected effect.** Where stale queued frames are present, removing one refresh interval means **16.67 ms at 60 Hz, 11.11 ms at 90 Hz or 8.33 ms at 120 Hz**. This is a conditional target, not a measured saving. If acquisition is already nonblocking and the display queue is shallow, this component may save essentially nothing. The supplied event example has input B arrive at 8 ms while acquisition returns at 12 ms: the current call still draws A; a post-acquisition snapshot draws B.

Primary references: [wgpu 30.0.1 surface configuration](https://docs.rs/wgpu/30.0.1/wgpu/type.SurfaceConfiguration.html), [GPU completion callback semantics](https://docs.rs/wgpu/30.0.1/wgpu/struct.Queue.html#method.on_submitted_work_done), [Android's explanation of Choreographer and buffer stuffing](https://developer.android.com/games/sdk/frame-pacing). These support the mechanism, not a claim that buffer stuffing was observed on this phone.

## 2. Finish the cartography optimization at the GPU boundary

**Mechanism.** The new worker caches draped fragments per terrain patch, which avoids recomputing unchanged geometry. But every completed job then concatenates all cached fragments into a new ordered vector. `poll_cartography()` passes that whole vector to `RouteGpu.upload()`, which writes the complete buffer at offset zero. The ordinary production path uses 64 bytes per line instance. At the current 333,333-instance cap, that is **21,333,312 bytes**, approximately 20.35 MiB, in one overlay replacement.

This happens inside `Renderer.render`, before the terrain draw. The Kotlin `cameraFirst` decision and its 650 KiB tile budget do not govern this upload. A background job started earlier can therefore insert a large upload into a frame that Kotlin intended to prioritize for camera movement. Background CPU draping has not removed this last frame-thread cost.

Sources: [whole-vector assembly](https://github.com/connorgleeson736-sys/Ridgeline/blob/3f4d9b8dedf8e3c53ed24f1c148712def00d1692/native-wgpu/renderer/src/cartography.rs#L317-L335), [result application](https://github.com/connorgleeson736-sys/Ridgeline/blob/3f4d9b8dedf8e3c53ed24f1c148712def00d1692/native-wgpu/renderer/src/cartography.rs#L392-L401), [buffer upload](https://github.com/connorgleeson736-sys/Ridgeline/blob/3f4d9b8dedf8e3c53ed24f1c148712def00d1692/native-wgpu/renderer/src/routes.rs#L113-L160), [unconditional application before drawing](https://github.com/connorgleeson736-sys/Ridgeline/blob/3f4d9b8dedf8e3c53ed24f1c148712def00d1692/native-wgpu/renderer/src/lib.rs#L1503-L1521).

**Controlled result.** The fixture has 20 paths × 32 patches × 500 instances = 320,000 instances. Updating one patch changes 10,000 records. Replacing all records transfers **20,480,000 bytes**; transferring the changed records needs **640,000 bytes**, a **96.875% reduction**. The reconstructed ordered vertex stream is byte-for-byte identical. Another 100 cases exercise reordered coverage, varying fragment lengths, insertions and removals. This is a synthetic workload, not evidence that a typical phone view contains 320,000 instances.

**Implementation direction.** First, send the frame's optional-work allowance into Rust and bound completed-overlay application too. Retain a coherent existing result until its replacement can be applied; do not publish a partially updated overlay with mixed terrain generations. Avoid dropping a pending result merely because this frame cannot admit it.

For equal-layout updates, dirty buffer ranges can preserve the existing single draw and reduce upload bytes immediately. The fixture's equal-size case requires 20 changed ranges. For variable fragment sizes, stable GPU allocations keyed by path, patch and heightfield generation avoid shifting the rest of the overlay. Preserve the current path-major then cover-major draw order, dash phase, color, anchor and alpha semantics. Account for the extra draw ranges or indirection; the payload reduction is not free if it creates thousands of draw calls. Validate Vulkan and GLES separately. A bounded allocator and retirement after GPU use are required; the experiment's append-only byte arena is a proof of ordering, not production memory management.

Delta results must identify their GPU base revision. With cancellation and replaceable jobs, a delta against a worker result that was never applied cannot be accepted. Compute against the last acknowledged GPU base or fall back to a complete replacement. Anchor, style, path-set and relief changes must invalidate dependent fragments.

**Remove the completion polling delay as well.** The worker publishes output but never wakes the renderer. While work is pending, `next_adaptive_frame()` asks for another frame after up to 32 ms, posted after the current frame returns, followed by Choreographer scheduling. In an idle scene, a finished result can sit waiting for that poll. Notify the GPU handler on completion, coalesce with any already-queued frame, and keep lifecycle/revision guards. The supplied 60 Hz schedule has a frame return at 6 ms and output ready at 7 ms: current polling reaches the result at 50 ms; completion notification reaches it at 16.67 ms, **33.33 ms earlier**. This is a scheduling example, not a device measurement. Active gestures or other frame requests can already eliminate much of that wait.

Sources: [worker output publication](https://github.com/connorgleeson736-sys/Ridgeline/blob/3f4d9b8dedf8e3c53ed24f1c148712def00d1692/native-wgpu/renderer/src/cartography.rs#L69-L80), [32 ms polling](https://github.com/connorgleeson736-sys/Ridgeline/blob/3f4d9b8dedf8e3c53ed24f1c148712def00d1692/native-wgpu/renderer/src/lib.rs#L963-L968), [Android delayed scheduling](https://github.com/connorgleeson736-sys/Ridgeline/blob/3f4d9b8dedf8e3c53ed24f1c148712def00d1692/native-wgpu/android/app/src/main/java/nz/ridgeline/nativeapp/TerrainView.kt#L330-L363).

## 3. Stop the 350 ms full-density burst on newly loaded terrain

**Mechanism.** Every new terrain source gets adaptive state `step=1, target=1`. Even when the existing error calculation immediately qualifies it for step four, the state machine starts a 350 ms coarsening timer. It can therefore render a full 128 × 128 grid throughout the first 350 ms. Loading a succession of new tiles keeps introducing these expensive initial meshes.

This is not a 350 ms delay before showing terrain. It is a period of unnecessary density for eligible tiles, with a possible effect on GPU frame time while loading and panning.

**Implementation direction.** Give adaptive state an explicit uninitialized state. On the first qualified evaluation, use the existing chosen level immediately. Keep hysteresis for previously evaluated sources, immediate refinement, the current 0.6-pixel bound and cell-span threshold. Preserve full density where the profile is absent, near-plane math is unsafe, exact route/interior constraints apply, or current hillshade rules require it. Do not lower texture resolution, discard DEM samples or increase the error threshold.

For a full patch eligible for step four, the existing topology has **2,680 surface triangles instead of 32,768**: **91.82% fewer**. Including all four existing skirts gives **3,704 instead of 33,792**, an **89.04% reduction**. The fixture verifies triangle counts, winding, total covered area and all full-resolution boundary vertices for 18 cases, and verifies the cold-start/refinement state trace. It does not verify production shader screenshots or the complete projected error bound. This changes the timing of the already-approved adaptive representation; it is not bit-identical to the initial full mesh.

The benefit is strongest for newly loaded, sufficiently smooth or distant terrain. Close rough terrain and exact-route patches can remain at step one and see no gain. A 12.2× reduction in surface triangle count is **not** a 12.2× reduction in total frame time; rasterization, image sampling, submission and display waits remain.

Sources: [new state and 350 ms transition](https://github.com/connorgleeson736-sys/Ridgeline/blob/3f4d9b8dedf8e3c53ed24f1c148712def00d1692/native-wgpu/renderer/src/adaptive.rs#L6-L138), [source initialization](https://github.com/connorgleeson736-sys/Ridgeline/blob/3f4d9b8dedf8e3c53ed24f1c148712def00d1692/native-wgpu/renderer/src/lib.rs#L802-L825), [topology and retained boundaries](https://github.com/connorgleeson736-sys/Ridgeline/blob/3f4d9b8dedf8e3c53ed24f1c148712def00d1692/native-wgpu/core/src/adaptive.rs#L34-L110), [exact-detail exceptions](https://github.com/connorgleeson736-sys/Ridgeline/blob/3f4d9b8dedf8e3c53ed24f1c148712def00d1692/native-wgpu/renderer/src/lib.rs#L1632-L1655).

## 4. The upload admission predicate is not enforcing its stated budget

**Mechanism.** When `cameraFirst` is false, `TerrainView` converts a zero allowance into at least 1 nanosecond. `UploadAdmission.allows()` admits the first upload whenever allowance is positive, without comparing its predicted cost. The cost estimate only constrains subsequent uploads. Thus even a known-expensive optional operation is admitted when the measured render cost has consumed the entire budget. `cameraFirst` also expires after 64 ms, so the exception can recur during sustained movement.

The counterexample supplies a zero real budget and a predicted optional upload cost of 20 ms. The call-site clamp produces an allowance of 1 ns, and the current predicate accepts the upload. **20 ms is a deliberately chosen test input, not an observed device upload time.** The predicate is demonstrably insensitive to that cost for the first upload.

The allowance also defaults to a fixed 16.67 ms frame interval, and the call site never supplies another value. That is inappropriate if the surface actually runs at 90 or 120 Hz. Do not assume the phone's panel maximum equals the app's active refresh rate; measure the latter.

**Implementation direction.** Check predicted cost against the real remaining deadline for the first optional upload too, using the active display interval. Keep an explicit, bounded exception for filling missing coverage and an intentional starvation policy for detail refinement. Once a non-preemptible upload is known to exceed the budget, either break the work into safe stages or schedule its unavoidable cost explicitly. Merely enforcing the budget forever would starve those tiles. Include Rust overlay replacement and synchronous route draping in the same frame-work accounting; the current 650 KiB tile cap does not cover them.

Sources: [call-site clamp and camera-first expiration](https://github.com/connorgleeson736-sys/Ridgeline/blob/3f4d9b8dedf8e3c53ed24f1c148712def00d1692/native-wgpu/android/app/src/main/java/nz/ridgeline/nativeapp/TerrainView.kt#L192-L211), [admission predicate](https://github.com/connorgleeson736-sys/Ridgeline/blob/3f4d9b8dedf8e3c53ed24f1c148712def00d1692/native-wgpu/android/app/src/main/java/nz/ridgeline/nativeapp/UploadAdmission.kt#L5-L13), [route draping still synchronous](https://github.com/connorgleeson736-sys/Ridgeline/blob/3f4d9b8dedf8e3c53ed24f1c148712def00d1692/native-wgpu/renderer/src/routes.rs#L256-L383).

## Validation that would establish a real latency breakthrough

Start with camera revision tracing and separate times for input receipt, acquisition begin/end, post-acquire camera latch, cover/route/overlay work, queue submission, GPU completion and actual presentation. Record overlay bytes, terrain triangle count, active refresh interval and frame deadlines. JNI return is not the end of input-to-display latency.

A/B each change on the same phone, route and settings. Include a warm-cache continuous pan, cold tile arrivals, dense cartography, route editing, and a stationary view waiting for a worker result. Preserve full imagery resolution and all existing terrain error thresholds. Run the app's supported Vulkan and GLES paths, and compare screenshots around terrain seams, path intersections, labels and rapid direction changes.

Report p50/p95 input-to-present latency and missed-frame rate separately from tile-ready-to-visible and cartography-ready-to-visible latency. Do not add the conditional savings in this report: some remove the same missed refresh, and some only apply to different workloads.

Recommended implementation order: close the frame-budget gaps and instrument presentation; add completion-triggered overlay wake; apply the qualified adaptive level on first visibility; then implement incremental GPU overlay storage and late camera sampling with the corresponding lifecycle tests. The architectural goal is that optional scene updates cannot repeatedly hold a current camera pose behind old work.

## Reproduce the supplied checks

Use an existing checkout at the exact head above, then run:

```bash
python3 checks.py /absolute/path/to/Ridgeline --out new-results.json
```

`results.json` contains the run from this audit. `checks.py` requires only Python's standard library. It guards the relevant source statements and emits source hashes so later branch changes are visible. The tests passed for 18 topology cases, 101 ordered byte-stream cases, the adaptive state trace, the explicit scheduling examples and the admission counterexample. They validate the stated mechanisms and work counts; they are not a replacement for the production Rust/JVM suites or on-device measurements.
