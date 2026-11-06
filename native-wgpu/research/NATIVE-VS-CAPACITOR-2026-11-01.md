# Native versus Capacitor rendering and loading

The latest native and Capacitor apps have **no matched, same-device rendering or
loading benchmark yet**. Available measurements show substantial improvements
within native's loading pipeline, but do not establish that native now renders
or loads faster than Capacitor. This comparison uses existing measurements;
no new test suite was run.

## Versions compared

| | Native Rust/wgpu | Capacitor / MapLibre |
| --- | --- | --- |
| APK | 0.1.2-loading-checkpoint Base, code 3 | 0.18.0-performance Base, code 21 |
| Source | `d831d2f3ddc9c301e0b2282fa58912d3940dd222` | `d880143418ac46ece98adff0b3b2fbaedf044d46` |
| Publication | `a257788`, native-renderer-performance | `39431dad230945919a7b7d9d0ef2e86db8492ead`, android-preview-download |
| APK bytes | 32,040,144 | 26,920,390 |
| Coarse overview images | 67 | 67 |
| Large overview packs bundled | 0 | 0 |
| Runtime | Kotlin UI/loading, Rust geometry and wgpu rendering | Android native HTTP/storage, WebView JavaScript, patched MapLibre GL JS 6.14.0 |
| Backends | Vulkan / GLES | WebGL through Android WebView |

Native Base is 5,119,754 bytes larger (19.0%). APK size does not measure working
memory or renderer speed. Compare Base against Base: Capacitor Full includes
six large packs that could otherwise give it an unfair offline-loading advantage.
Native's base is exactly the requested `native-rust-wgpu` publication `93be86c`
(0.1.1), with subsequent loading changes applied. `150ad30` is the source inside
that original publication, rather than a different APK baseline.

## Direct performance comparison: evidence currently missing

| Metric, same device and camera replay | Native 0.1.2 | Capacitor 0.18 |
| --- | --- | --- |
| Movement frame time, p50 / p95 | Not measured | Not measured |
| Presented FPS / frames missing display deadline | Not measured | Not measured |
| Gesture to first updated presented frame | Not measured | Not measured |
| New view to first useful terrain + imagery | Not measured | Not measured |
| New view to full target detail | Not measured | Not measured |
| Warm RAM / warm disk revisit to visible tiles | Not measured | Not measured |
| Peak PSS, GPU memory, thermal/power cost | Not measured | Not measured |

No Android device is attached in the current workspace (`adb devices` returned
an empty device list). Chromium/software GPU results cannot fill these phone
measurements. Existing native host GPU timings include GPU drain and uploads;
inverting them into FPS would misrepresent real frame pacing.

## Native loading: measured improvements present in 0.1.2

These are controlled **host subsystem** results, not measurements against
Capacitor. Each row has its own workload and baseline; do not combine ratios.

| Workload | Before | Current implementation | Change |
| --- | ---: | ---: | ---: |
| Foreground cache hit behind 64 low-priority reads; controlled 2 ms service | 70.171 ms | 2.289 ms | 96.7% lower latency |
| Tile body ready while actual OkHttp cache monitor is locked for 400 ms | 404.218 ms | 43.248 ms | 89.3% lower latency |
| Complete 129×129 terrain mesh, four DEM inputs and parent fallback | 375.235 ms | 197.203 ms | 47.4% lower latency |
| Complete terrain mesh without parent fallback | 197.153 ms | 185.705 ms | 5.8% lower latency |
| Healthy disk-cache hit with encoded RAM retention disabled | 0.581 ms | 0.614 ms | 0.033 ms overhead |
| Source ownership, 161 resident sources | 28.886 µs | 15.047 µs | 47.9% less CPU time |
| Obsolete queued source admissions during camera cancellation | 64 | 0 | 64 admissions avoided |

First/source-ownership rows compare the earlier native viewport implementation
against the foreground-priority pass. Cache-stall/mesh rows compare the next
latency pass against `49d74fc`. Source arrivals, cache-lock duration and the
parent-triggering failure are controlled inputs. DEM/imagery bytes are genuine
LINZ payloads. The mesh result excludes Android BitmapFactory, JNI terrain
profiling, GPU upload and display; it is not complete tile-to-screen latency.
The mesh bytes and source-request counts match between compared paths.
The queue fixture has 16 alternating trials; cache-stall and mesh fixtures have
12; healthy-cache timing has 40 after warmup.

The final APK build also recorded one unresolved HTTP-cache fixture timeout:
`TileTransportTest.actualHttpDiskHitFinishesWhileAllForegroundNetworkSlotsAreBlocked`.
Earlier controlled fixture success does not erase this failure. Further tests
were stopped at the user's request. Treat cache behavior on the phone as pending.

## Capacitor's measured CPU improvements

These compare **Capacitor 0.17 against 0.18**, using warmed alternating cloud CPU
batches. They are different operations from the native measurements above.

| Workload | Capacitor 0.17 | Capacitor 0.18 |
| --- | ---: | ---: |
| LOD arithmetic, 4,096 candidates | 3.183 ms | 0.496 ms |
| Sparse DEM ancestor lookup, 512 queries | 0.884 ms | 0.391 ms |
| Projection matrices, 16 images / 64 terrain patches | 0.077 ms | 0.054 ms |

An older supplied phone trace (build `a21b411…`, **not 0.18**) recorded roughly
100.7 ms mean movement frames (about 9.93 FPS), 183.8 ms p95 frame time,
442.2 ms p95 decode elapsed and 3,162 ms p95 admission elapsed. It indicates
historical scheduling pressure, not current Capacitor performance. Decode elapsed
includes waiting; inclusive stage times overlap. No GPU duration samples exist
in that trace. It cannot supply a native-versus-0.18 speed ratio.

## Why the pipelines can feel different

| Stage | Capacitor 0.18 | Native 0.1.2 |
| --- | --- | --- |
| View selection | Mature MapLibre terrain-aware cover, distance LOD and fallback | Custom HLOD, near-view ranking and cached coverage; full selection/visual parity remains unaccepted |
| Local delivery | Independent native cache delivery plus fresh encoded RAM reuse | Independent ranked cache reads, fresh encoded/decoded/prepared reuse |
| Network | Shared cancellable transfers, bounded background work | Shared ranked transfers; atomic demand changes avoid promoting obsolete work |
| Stalled disk lookup | Existing native HTTP/cache path; no matched stall measurement here | Foreground online work can hedge after 40 ms using a cache-free sibling client under existing network limits |
| DEM dependencies | MapLibre source cache and worker preparation | Decode inputs as they arrive; discover/request missing parents early |
| Upload/drawing | Browser/worker image ownership, MapLibre uploads and terrain composition | Ranked native buffers/uploads; direct texture sampling, Vulkan arrays/batching where selected |
| Revisit/eviction | Sharper/ancestor residency, coarse reserve and quality holds | Cached parent/child ownership, prepared RAM reuse and protected useful fallback sources |
| Detail controls | Independent imagery and terrain settings | Independent seven-position sliders, same existing default bias |
| Diagnostics | Opt-in tile stage traces and loading counters | Host fixtures and counters; equivalent per-tile phone export remains a gap |

Removing WebView work creates an opportunity, not a speed guarantee. Native can
still lose time in selection, storage locks, decode/preparation queues, texture
upload, GPU fill or poorly chosen tile residency. Its GPU workload and tile cover
must be measured at comparable visible quality; equal slider labels alone do not
prove equivalent visible detail.

## Smallest useful matched phone capture

Use the same phone, screen resolution/refresh rate, camera position/pitch/bearing,
imagery mode, terrain exaggeration and visible quality. Attach the same original
packs to both or neither. Keep optional native idle preparation off. Record
backend/WebView version, cache budgets, network and thermal state.

Capture three alternating runs per app for each condition: cold cache into new
coverage; repeat with disk warm after process restart; return with RAM warm;
and a 20-second loaded pan/zoom replay with network demand separated from settled
rendering. Clear only automatic caches for the cold run, preserving user data.
Report medians and ranges across these few runs, not an unreliable p95 across
three launch samples. Frame and per-tile event streams have enough individual
samples to report their own p50/p95 when counts are stated.

Use identical start/end events: movement input → first presented update;
camera change → first useful coverage; camera change → final target detail.
Trace queue wait, cache lookup, HTTP headers/body, decode, geometry preparation,
GPU upload and presentation separately. Capture scheduled work and request bytes
so background saturation and redundant loading are visible. A screen recording
can establish visible timing when a matching per-tile native exporter is absent;
use Perfetto FrameTimeline for actual presented frames and `dumpsys meminfo` for
PSS. Compare captures with recording overhead held constant.

## Evidence

- [Native exact APK/build record](../validation/loading-checkpoint-2026-11-01/release.json)
- [Viewport priority measurements and raw sample links](VIEWPORT-LOADING-AUDIT-2026-10-31.md)
- [Cache/DEM latency measurements and raw sample links](LATENCY-BARRIERS-2026-11-01.md)
- [Capacitor CPU measurements](../../capacitor/PERFORMANCE-0.18.0-benchmarks.json)
- [Capacitor audit and historical trace scope](AUDIT-018.md)
- [Capacitor 0.18 Base package record](https://github.com/connorgleeson736-sys/Ridgeline/blob/39431dad230945919a7b7d9d0ef2e86db8492ead/Ridgeline-3D-0.18.0-Base-validation.json)

The earlier [cache migration audit](CACHE-018-COMPARISON.md) describes native
deficiencies before the subsequent ports; it should not be read as the current
0.1.2 implementation inventory.
