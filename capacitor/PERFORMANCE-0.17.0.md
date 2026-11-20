> **Superseded by [0.18.0](PERFORMANCE-0.18.0.md).** The user reports no noticeable improvement in 0.17.0. Its conditional 10–30% estimate below was not a measured device result and should not be treated as an expected gain.

# Ridgeline 3D Performance — 0.17.0

> A **Full imagery APK** is now available with the same application code, package and signer. See [Full packaging and verification](PERFORMANCE-0.17.0-FULL.md). Earlier Base-specific checks below describe the initial release.


Built 28 October 2026 (Melbourne), against the normal and Profiling 0.16.0 previews on `android-preview-download`, with source baseline `c0b394125bc35f6236c645c68a4a3bb94d88ce55`. Includes all reconciled fixes and the additional work below. Source changes are additive to the 0.15/0.16 app-specific optimisations.

## Installation and data

This is the user-selected **side-by-side** comparison app: **Ridgeline 3D Performance**, package **`nz.ridgeline.mobile.performance`**, version **0.17.0-performance**, code **20**. It has a new development signer because the previous preview's private signing key was unavailable. It cannot replace the normal installed app and does not access or delete its private data. Its own downloads, routes, settings, provider key and HTTP cache start separately. Enter your provider key and match the original app's settings before comparing. Export/import routes if required; the existing app's saved regions remain with that app.

Base APK only, matching the 0.16.0 Base distribution. Existing packaged coarse imagery is unchanged; larger overview packs can be imported in this new app as before. No large overview packs were fabricated or substituted. WebView inspection is enabled to permit a new device Performance trace. Release-mode rendering/build settings are retained.

## All included changes

- Indexed native fallback-child lookup instead of comparing every loaded tile with every missing tile. Native ancestry, wrap/overscale rules and complete-cover decisions are retained.
- Shared missing-parent/overview probes within each raster retention update, invalidated by residency changes, and lazy loaded-coverage construction.
- Visibility indexes only for sources whose requests are ranked; a bounded address memo avoids reparsing URLs on every movement event.
- Deduplicated imagery readiness by visible source, complementing 0.16's ready-coverage reuse; paint classification shared only during a synchronous preparation.
- Prompt cleanup of cancelled/failed/late decoded bitmaps and reference-safe worker-mask eviction.
- Dispatcher scheduling over queued jobs only; cancellation, body-reader callbacks and continuations outside its monitor; lock-free terminal cancellation checks. Single-byte stream reads no longer allocate an array each time.
- Independent delivery/decode timing, cancellable decode admission and an optional independent decode cap. Defaults and total image-job limits remain unchanged.
- Existing 0.16 removal of a redundant response copy and duplicate elevation disk caching is retained; these are not claimed as new Android fixes.

### Further findings from this pass

1. **Saturated image queues:** priority invalidations previously rebuilt the whole queue even when every image slot was occupied. They now remain dirty until capacity returns, with stale-heap compaction still allowed to bound memory. A regression performs 1,000 blocked pumps without a rebuild and then verifies that the next admitted tile uses the latest priorities.
2. **Fallback-child allocation:** the native selector used four filter/map arrays to find the nearest usable child level. Two loops now produce just the final selected-ID list. Tests compare retained IDs and native completeness outcomes over 300 randomized cases, including maximum-overzoom and overscaled tiles.
3. **Connectivity binder calls:** resource interception used `getActiveNetwork` and `getNetworkCapabilities` per online-eligible request, including possible HTTP-cache hits. One application-lifetime default-network callback now maintains the same INTERNET-capability predicate. Normal tile checks read a volatile state without binder queries. Late callbacks from replaced networks are ignored. If monitoring cannot be established, the original direct-query path remains available. State-transition tests cover availability, capability changes, loss, duplicate initial notifications and stale old-network events. Actual Android network transitions still need device testing.
4. **Completed-transfer freshness race:** the full HTTP tests exposed a small window where a new request could join an already completed producer before its worker removed the job. A stale/no-store response could therefore bypass a fresh HTTP-cache decision. Completed jobs are no longer shareable; existing readers keep their body. A latch-controlled regression forces this window, and expired/new-byte, cache-control and revalidation tests pass.

The trace's main-thread saturation, large LINZ selection scope, slow image preparation and substantial WebView I/O work motivate these changes. Its 19.9 seconds of kernel reclaim also argues against increasing caches/concurrency. Binder time in that recording is not uniquely attributed to connectivity; removing those per-tile queries is a verified code-path reduction, not a claim that they explain all recorded I/O CPU.

## How much faster?

**There is no physical-phone before/after measurement yet.** A reasonable *conditional planning estimate* is **about 10–30% faster CPU-bound, selection-heavy panning/refinement**, with larger reductions in individual loading stalls. This assumes the changed paths occupy roughly 20–40% of elapsed frame work and that their combined cost halves. Amdahl's law then gives approximately 11–25% more throughput; 10–30% is a rounded planning range, not a statistical confidence interval or a measured app result. If GPU work, bandwidth or OS reclaim dominates, the overall gain can be small or zero. The original ~9.9 fps snapshot cannot responsibly be turned into a promised new frame rate.

The reproducible CPU benchmark below uses native fallback-child lookup and retention from the **actual shipped 0.16.0 APK** and this build, with matching results, warmed and alternated for seven rounds of 40 iterations. It covers only that subsystem on this cloud CPU:

| Loaded tiles | Missing tiles | 0.16.0 median/call | 0.17.0 median/call | Local speed ratio |
| --- | --- | --- | --- | --- |
| 32 | 16 | 0.0178 ms | 0.0151 ms | 1.18× |
| 256 | 128 | 0.2554 ms | 0.0839 ms | 3.04× |
| 1024 | 512 | 3.1539 ms | 0.3395 ms | 9.29× |
| 512 | 0 | 0.0078 ms | 0.0001 ms | 95.87× |

The empty-missing case saves a tiny absolute amount; its large ratio is not an end-to-end speedup. The earlier operation-count fixtures still show 524,288 → 1,024 ancestry checks and 6,144 → 521 repeated ancestor probes. Do not add or multiply subsystem speed ratios to estimate app FPS.

Reproduce with `node checks/preview-cpu-benchmark.mjs PATH_TO_0160_BASE_APK output.json`. The complete samples are in `PERFORMANCE-0.17.0-benchmark.json`.

## Verification

- **90 native/JVM tests pass**, including real local HTTP/cache/revalidation, stream validation/cancellation, SQLite storage, dispatcher contention and the new connectivity/freshness regressions.
- **55 mobile JavaScript tests pass**; **17 focused tests pass against source**, and **17 against the final generated Capacitor modules**.
- Android performance APK assembled; lint passes with **zero errors** (22 warnings); Android instrumentation tests compile. No emulator/device tests were executed.
- Chromium workflow passes with software WebGL and controlled provider fixtures: 313 fixture requests, no page/console errors. No large overview packs were available in this base build, so this run made zero real pack reads.
- Real-worker browser transport, promotion, cancellation, response clones and reference composition checks pass with a mocked Android control interface.
- APK v2 signature and ZIP alignment verified. Manifest verified: side-by-side package, version code/name, correct activity and visible Performance label.
- All **1,217 packaged web assets** match the synced build. All **1,133 image/binary imagery assets** are byte-identical to the shipped 0.16.0 APK. Direct renderer, terrain/detail policy, Topo resolution, filtering/colour math, native terrain data path and MapLibre worker modules are byte-identical to 0.16.0. Regional blend arithmetic is retained; its changes concern resource ownership.
- Web fingerprint: `ace5c9b660f3cc5c830daffc4c5f6f2881c01f965ef5985cd98cb5fc8d9f67f2`.
- APK SHA256: `009c736e9e8f86e73ec1b41be09d75e80ecccc94695e659b1f556463bcc85869`.

Full imagery resolution, terrain detail, renderer type, drawing resolution and default concurrency/cache budgets are preserved. First-launch comparisons will be affected by the new app's cold caches and separate settings. Warm both apps over the same route, match settings, and record movement, visible completion, source-selection timing, decode/admission timing and native memory pressure to replace the estimate with a device result.

## Build

Use JDK 21, SDK platform 36, Android build-tools 35.0.0 and Gradle 8.13:

```sh
RIDGELINE_BUNDLE_OVERVIEWS=0 npm run sync --prefix capacitor
# Set JAVA_HOME, ANDROID_HOME and writable Android/Gradle user homes as needed.
cd capacitor/android
./gradlew :app:assemblePerformance :app:testPerformanceUnitTest :app:lintPerformance :app:assemblePerformanceAndroidTest
```

The development keystore remains outside source control. Preserve it for future updates to this comparison app. The normal preview signer and package were not replaced.
