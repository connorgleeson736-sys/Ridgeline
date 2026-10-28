# Ridgeline Android downloads

## Diagnostic build: 0.16.0 — WebView CPU profiling

**[Download 0.16.0 Profiling Base APK — 26.9 MB](Ridgeline-3D-0.16.0-Profiling-Base.apk)**

Choose **Download raw file**, then install over your current 0.16.0 preview. Same application ID, version code and signing certificate; keep the app installed to preserve its data. This is a diagnostic alternative to the normal Base build below.

The only changed APK entry is `assets/capacitor.config.json`, adding `android.webContentsDebuggingEnabled: true`. All 1,654 other file entries, including renderer JavaScript, native code, manifest, imagery and terrain settings, are byte-identical to the original 0.16.0 Base APK. It keeps the same release-mode build configuration.

To record: enable USB debugging on your Android phone, connect and authorise your laptop, open desktop Chrome at `chrome://inspect/#devices`, enable Discover USB devices, open Ridgeline and click Inspect beneath its WebView. In Performance, record 20–30 seconds of panning/zooming, stop, and save the trace. Keep CPU throttling off and JavaScript samples enabled; screenshots can be disabled to reduce overhead. The normal Base APK below restores the original inspection setting when installed again.

Verified: offline preview build, APK signature, matching signer, unchanged manifest and complete APK-entry comparison. No emulator/device profiling run was performed.

[Checksum](Ridgeline-3D-0.16.0-Profiling-Base.apk.sha256) · [Validation](Ridgeline-3D-0.16.0-Profiling-validation.json)

## Latest: 0.16.0 — elevation-cache and invalidation work reductions

**[Download 0.16.0 Base APK — 26.9 MB](Ridgeline-3D-0.16.0-Base.apk)**

Choose **Download raw file**, then install over your current app. Same preview signer. Base only.

- Map/profile elevation now uses shared native HTTP persistence directly rather than duplicate automatic WebView disk caching. Decoded-height memory reuse, saved regions and explicitly saved overviews remain supported.
- Arrivals inspect terrain texture sizes only for geographically related patches and avoid intermediate warm/merged tile arrays. Source-membership filters and invalidation outcomes are preserved.

One affected patch among 1,000 requires one RTT inspection instead of 1,000. A synthetic 500-arrival CPU fixture improved from about 34 ms to 14 ms; an empty-texture case also improved. These are controlled work measurements, not phone FPS.

Passed: **85 Android/JVM tests, 55 JavaScript tests**, lint, instrumentation-test compilation, browser workflow (25 actual overview reads, no page/console errors), worker transport, signing and APK comparisons. Eleven graphics/quality modules are identical to 0.15.0; profile terrain decoding differs only in the default cache setting.

[Findings and scope](https://github.com/connorgleeson736-sys/Ridgeline/blob/main/capacitor/CACHE-INVALIDATION-OPTIMISATIONS.md) · [Source commit](https://github.com/connorgleeson736-sys/Ridgeline/commit/c0b3941) · [Checksum](Ridgeline-3D-0.16.0-Base.apk.sha256) · [Validation](Ridgeline-3D-0.16.0-validation.json)

## Previous: 0.15.0 — terrain-adapter CPU and allocation reductions

**[Download 0.15.0 Base APK — 26.9 MB](Ridgeline-3D-0.15.0-Base.apk)**

Choose **Download raw file**, then install over your current app. Same preview signer. Base only.

- Reuses geographic terrain-coordinate indexes; native matrices still calculate freshly and changed membership/coordinates invalidate the index.
- Caches temporary release functions per tile rather than allocating them every update, restoring original methods after each update and on exceptions.
- Skips transition maps/sets when membership is unchanged and no transition is held. Native updates and texture invalidation still run.

Controlled results: coordinate lookup about 121 ms → 11 ms over 40 preparation passes; release-function identities 101 → 2 over 100 updates; stable-frame Map/Set allocations 1,000 → 200 over 100 updates. These are CPU/allocation fixtures, not phone FPS.

Passed: **85 Android/JVM tests, 52 JavaScript tests**, real pinned-engine texture lifecycle checks, lint, instrumentation-test compilation, full browser workflow and worker transport checks, APK signature and module comparisons. Original + Direct, imagery/terrain detail, Topo zoom 18 and movement drawing resolution remain unchanged.

[Implementation and measurements](https://github.com/connorgleeson736-sys/Ridgeline/blob/main/capacitor/MAIN-THREAD-OPTIMISATIONS.md) · [Source commit](https://github.com/connorgleeson736-sys/Ridgeline/commit/04bf5f0) · [Checksum](Ridgeline-3D-0.15.0-Base.apk.sha256) · [Validation](Ridgeline-3D-0.15.0-validation.json)

## Previous: 0.14.1 — follow-up render-path audit

**[Download 0.14.1 Base APK — 26.9 MB](Ridgeline-3D-0.14.1-Base.apk)**

Choose **Download raw file**, then install over the existing app. Same preview signer; Base only.

- Reuses exact imagery coverage when the ready coordinates are unchanged. The fixture builds one index across 101 checks; holes and changed coordinates still invalidate it.
- Removes an explicit shared-response buffer copy while keeping consumers isolated.
- Avoids allocating an empty passive-route source and hides empty waypoint rendering.
- Diagnostic reports now include opt-in main-thread component timings and native loading counters, with Android's JSON save picker.

Passed: 85 native tests, 46 JavaScript tests, lint, instrumentation-test compilation, browser route/offline/Topo workflow, real component capture and mocked native diagnostic export, APK signing and renderer/quality parity checks. Eleven graphics/quality modules match 0.14.0 exactly. No physical-phone speed claim is made.

[Broader audit, reviewed areas and remaining gaps](https://github.com/connorgleeson736-sys/Ridgeline/blob/main/capacitor/PERFORMANCE-AUDIT.md) · [Source commit](https://github.com/connorgleeson736-sys/Ridgeline/commit/d448d64) · [Checksum](Ridgeline-3D-0.14.1-Base.apk.sha256) · [Validation](Ridgeline-3D-0.14.1-validation.json)

## Previous: 0.14.0 — cache, eviction and main-thread efficiency

**[Download 0.14.0 Base APK — 26.9 MB](Ridgeline-3D-0.14.0-Base.apk)**

Choose **Download raw file**, then install over your current app. Uses the previous preview signer. No Full APK was built.

- Removes expired compressed entries before cache-admission and prefetch decisions.
- Reduces repeated eviction bookkeeping: 5,206 to 366 coordinate checks in a 100-tile fixture, with identical surviving tiles.
- Eliminates redundant native request-begin calls and coalesces priority/completion/cancellation controls; 40 completions use one batch in the unit fixture.
- Removes per-image hashing from production worker decoding and memoises repeated native offline lookup misses with commit-safe invalidation.
- Defers view persistence until idle and avoids unchanged region-list rebuilding/idle polling.

Original + Direct, imagery/terrain quality and maximum Topo detail are preserved. Eleven renderer/quality modules are byte-identical to 0.13.2. Source website files are unchanged.

Passed: **85 native tests, 42 JavaScript tests**, lint, instrumentation-test compilation, real-worker batch/legacy transport checks, full browser workflow (25 actual precomputed imagery reads, no page/console errors), APK signature and packaging comparisons. These are correctness and operation-count results, not physical-phone FPS measurements.

[Findings and limitations](https://github.com/connorgleeson736-sys/Ridgeline/blob/main/capacitor/LOADING-OPTIMISATIONS.md) · [Source commit](https://github.com/connorgleeson736-sys/Ridgeline/commit/f53616f) · [Checksum](Ridgeline-3D-0.14.0-Base.apk.sha256) · [Validation](Ridgeline-3D-0.14.0-validation.json)

## Previous: 0.13.2 — stable resolution during movement

**[Download 0.13.2 Base APK — 26.9 MB](Ridgeline-3D-0.13.2-Base.apk)**

Choose **Download raw file**, then install over your current app. Removes automatic frame-rate-triggered rendering-resolution reductions during movement, preventing that drawing-buffer resize. Other detail settings, Original + Direct and maximum Topo detail are preserved. Website source is unchanged.

Base only. Tests and optional validation were skipped at the user's request; no device benchmark is claimed.

[Source commit](https://github.com/connorgleeson736-sys/Ridgeline/commit/7abccfc)

## Previous: 0.13.1 — foreground loading without the extra native cap

**[Download 0.13.1 Base APK — 26.9 MB](Ridgeline-3D-0.13.1-Base.apk)**

Choose **Download raw file** on the APK page. Install over the existing app; the signer matches previous previews. No Full APK was built for this update. Existing imported imagery and saved regions remain supported.

- Removes the fixed eight-transfer native foreground limit; the renderer's existing loading controls govern image demand.
- Fresh native HTTP-cache hits no longer queue behind unrelated network downloads.
- Background work retains its separate two-transfer limit. Cancellation, cache freshness/validation, Original + Direct and maximum Topo detail remain unchanged.

Passed: 82 native tests, 36 JavaScript tests, Android lint, Base preview build and APK signature verification. The new real-HTTP regression test holds 16 transfers open while a fresh cache hit and another new transfer complete independently. Eight graphics modules are byte-identical to 0.13.0 Base. No phone performance benchmark is claimed.

[Checksum](Ridgeline-3D-0.13.1-Base.apk.sha256) · [Validation](Ridgeline-3D-0.13.1-validation.json) · [Source commit](https://github.com/connorgleeson736-sys/Ridgeline/commit/547783b)

## Previous: 0.13.0 — native scheduling, streaming and shared offline storage

| Build | Download | Size |
| --- | --- | --- |
| Base | [0.13.0 Base APK](Ridgeline-3D-0.13.0-Base.apk) | 26.9 MB |
| Full imagery | [0.13.0 Full APK](Ridgeline-3D-0.13.0-Full.apk) | 391.7 MB |

Choose **Download raw file** on the APK page, then install over your existing app. Full uses Git LFS and retains the verified 364.8 MB extra imagery pack. Base supports the existing six-ZIP import workflow. Both use the previous preview signer.

**Original + Direct and maximum Topo detail are preserved.** The graphics engine, Direct sampling, detail/terrain policy, blending math and retained high-density zoom-18 Topo policy match 0.12.0. Cook reference substitution remains excluded.

- Existing renderer cancellation and priority now reach Android, including workers and shared-source priority promotion. Cancelling one consumer preserves transfers still needed by others.
- Online tile headers/bytes and trusted overview ranges stream earlier; validation still gates successful completion. Saved-ancestor recovery remains available to whole-tile readers.
- The imagery worker reuses exact decoded inputs in a bounded 2 MiB retained pool. Obsolete predictive warmups cancel, with foreground capacity protected.
- Offline maps use indexed content-addressed SQLite storage with independent region ownership. Identical bytes share storage; different source versions stay separate. Downloads use bounded parallel production, atomic batches and retry/backoff.
- Cache hits retain the remaining freshness lifetime, including streamed responses and the elevation disk cache. The removable native HTTP cache stays at 256 MB; downloaded-only mode bypasses it.

Existing region files migrate in small durable batches and remain readable during migration. Routes and imported imagery are retained. **Migrated saved maps require 0.13.0 or later.**

**Passed:** 81 native JVM tests, 36 JavaScript tests per variant, lint, instrumentation-test compilation, real-worker transport checks, eight pixel-exact composition comparisons, full WebGL app workflow, APK signatures, Base/Full code equality and all six full-pack hashes. In the composition fixture, two decodes served 16 reuse hits. No emulator or physical-phone speed benchmark is claimed.

[Research, implementation and safeguards](https://github.com/connorgleeson736-sys/Ridgeline/blob/main/capacitor/LOADING-OPTIMISATIONS.md) · [Source commit](https://github.com/connorgleeson736-sys/Ridgeline/commit/ece2f78a340774e7e268deeabe95ffddd24ddba5)

[Base checksum](Ridgeline-3D-0.13.0-Base.apk.sha256) · [Full checksum](Ridgeline-3D-0.13.0-Full.apk.sha256) · [Validation](Ridgeline-3D-0.13.0-validation.json)

Previous [0.12.0 Base](Ridgeline-3D-0.12.0-Base.apk) and [0.12.0 Full](Ridgeline-3D-0.12.0-Full.apk) remain available.

## Earlier full installer: Ridgeline 3D 0.5.0 — full imagery included

**[Download Ridgeline-3D-0.5.0-Full.apk — 391 MB](Ridgeline-3D-0.5.0-Full.apk)**

Sign in to GitHub if required, open the APK file page and choose **Download raw file** (↓). The large installer is stored using Git LFS; GitHub's download serves the complete APK.

Install this single APK. **All supplied precomputed South Island/Rakiura overview imagery at tile zooms 0–12 is already inside it. No separate ZIP downloads or imports are needed.** The imagery is read directly from uncompressed APK assets; it is not copied out at first launch.

The APK uses the same signer as 0.4.0, so it installs as an update to Ridgeline 3D. Requires Android 8.0+ with up-to-date Android System WebView/Chrome. The original Direct 3D renderer, centred GPS SVG and Topo clarity changes are retained. Separate Cook-reference corrections remain excluded.

Detailed imagery beyond zoom 12 and elevation for offline 3D terrain/profiles still use user-selected region downloads. Current offline imagery limit: zoom 17. Online sources extend through zoom 19 where finer data exists. Image-tile zooms can differ from camera zooms.

[Validation details](Ridgeline-3D-VALIDATION.md) · [APK checksum](Ridgeline-3D-0.5.0-Full.apk.sha256) · [Versioned validation record](Ridgeline-3D-0.5.0-validation.json)

This update adds **＋ Create new** below Import GPX in the logo menu, route/elevation and offline-region bottom panels with a 5/8-height map, coordinates under Advanced, and rectangle preview/cancellation improvements. Build, lint, 7 packaging tests and browser workflow checks passed. All six bundled pack hashes were verified inside this APK; the UI files match the synced build. The imagery and native reader retain the prior 0.4.0 verification. No emulator validation was requested or run. Phone runtime checks remain appropriate for this development preview.

## Website patch and earlier builds

The [website Topo patch](Ridgeline-Website-Topo-Fix-0.3.0.zip) still needs applying through the original Site deployment session; publishing the Android APK does not deploy that Site.

Earlier APKs and [the old six import ZIPs](overview-packs/) remain available for comparison or reproducing a build. Version 0.5.0 does not require those imports. The offline panel can remove old imported copies to reclaim duplicate storage while retaining bundled imagery.

Source: [main/capacitor](https://github.com/connorgleeson736-sys/Ridgeline/tree/main/capacitor).
