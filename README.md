# Ridgeline Android downloads

## Latest: 0.11.0 — Original + Direct retained

| Build | Download | Size |
| --- | --- | --- |
| Base | [0.11.0 Base APK](Ridgeline-3D-0.11.0-Base.apk) | 26.5 MB |
| Full imagery | [0.11.0 Full APK](Ridgeline-3D-0.11.0-Full.apk) | 391.2 MB |

Choose **Download raw file** on the APK page, then install over your existing app. Full is stored with Git LFS and includes the verified 364.8 MB extra imagery pack. Base supports the existing six-ZIP import workflow. Both variants use the same code and signer.

**Original + Direct stays enabled. The app has not switched to Original.** Renderer modules and shared resolution policy match the repository website source. The high-density zoom-18 Topo enhancement is intentionally retained, along with zoom-18 offline Topo downloads and lossless PDF export.

This update resets saved rendering/detail/loading/filtering/sky settings to website defaults once, before rendering scripts load. Routes, downloaded regions, imported imagery, saved map view and LINZ label preferences are preserved. Later rendering adjustments remain saved. All page-size selection, PDF grid/north/scale options and route controls from 0.10.0 remain available.

The live Site could not be fetched from this environment. Source-code parity is verified; exact browser/WebView pixels and loading speeds on a physical phone are not claimed. Native offline tile access and the previously excluded Cook reference replacement remain unchanged.

18 JavaScript tests, 29 Android unit tests, lint, browser workflow checks, APK signatures, all six full-pack hashes and matching Base/Full renderer code passed. No emulator run.

[Base checksum](Ridgeline-3D-0.11.0-Base.apk.sha256) · [Full checksum](Ridgeline-3D-0.11.0-Full.apk.sha256) · [Validation](Ridgeline-3D-0.11.0-validation.json) · [Source](https://github.com/connorgleeson736-sys/Ridgeline/commit/54c0890)

Previous [0.10.0 Base](Ridgeline-3D-0.10.0-Base.apk) and [0.10.0 Full](Ridgeline-3D-0.10.0-Full.apk) remain available.

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
