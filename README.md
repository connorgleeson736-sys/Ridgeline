# Ridgeline Android downloads

## Latest: 0.12.0 — native loading and caching improvements

| Build | Download | Size |
| --- | --- | --- |
| Base | [0.12.0 Base APK](Ridgeline-3D-0.12.0-Base.apk) | 26.9 MB |
| Full imagery | [0.12.0 Full APK](Ridgeline-3D-0.12.0-Full.apk) | 391.7 MB |

Choose **Download raw file** on the APK page, then install over your existing app. Full uses Git LFS and includes the same verified 364.8 MB extra imagery pack. Base retains the six-ZIP import workflow. Both use the previous preview signer.

**Original + Direct and maximum Topo detail are preserved.** All 65 packaged JavaScript files are byte-for-byte identical to 0.11.0. High-density zoom-18 Topo, zoom-18 offline Topo downloads, lossless PDFs, imagery pixels and rendering preferences remain unchanged.

The native layer now shares HTTP connections and supports HTTP/2 where the provider negotiates it. A removable **256 MB native HTTP disk cache** honours server freshness/revalidation rules and can reuse completed estimation samples. Concurrent map/download requests for the same URL share one transfer. Saved-tile locations and misses are memoised, independent offline/overview reads can proceed together, and long exports/imports/estimates no longer queue ahead of status/settings/Pause. Temporary enlarged ancestor tiles are not retained in response/byte caches as full-detail tiles.

Downloaded-only mode bypasses the online cache. Saved regions and imagery packs remain persistent. Phone speed gains have not been benchmarked; new-area loading still depends on provider latency and connectivity. Cancellation/priority across native workers and concurrent region downloads are documented next steps.

**Passed:** 51 native JVM tests (including real local HTTP/cache/concurrency tests), 18 JavaScript tests for each variant, lint, the isolated real-WebGL browser workflow, APK signatures, six pack hashes, Base/Full code equality and unchanged renderer JavaScript. A browser run concurrent with the Android build reported worker timeouts; the isolated rerun completed with no page or console errors. No emulator run.

[Research and full findings](https://github.com/connorgleeson736-sys/Ridgeline/blob/main/capacitor/LOADING-OPTIMISATIONS.md) · [Source commit](https://github.com/connorgleeson736-sys/Ridgeline/commit/1a25406ec5f8220c0eba93910067309fc6153288)

[Base checksum](Ridgeline-3D-0.12.0-Base.apk.sha256) · [Full checksum](Ridgeline-3D-0.12.0-Full.apk.sha256) · [Validation](Ridgeline-3D-0.12.0-validation.json)

Previous [0.11.0 Base](Ridgeline-3D-0.11.0-Base.apk) and [0.11.0 Full](Ridgeline-3D-0.11.0-Full.apk) remain available.

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
