# Ridgeline Android downloads

## Latest: 0.7.0 Base APK — 26.5 MB

[Download Ridgeline 3D 0.7.0 Base](Ridgeline-3D-0.7.0-Base.apk). Open the file page and choose **Download raw file**, then install over your current Ridgeline 3D app.

This update has smaller, rounder sheets with borderless close buttons; a faster screen-space rectangle editor with small handles in 2D; and no peak-search box. The main Layers tab contains topographic overlays, with peak labels and terrain shading under Advanced.

Saved routes have Open/Close actions. Multiple routes can remain visible. Select their checkboxes in join order, optionally reverse direction, then choose **Combine selected routes**. The original routes stay saved; gaps become straight connecting segments.

For printable offline maps choose **Logo menu → Download offline region → Map content → NZ Topo only · maximum detail**. Complete the download, then choose **Export Topo PDF** under Saved regions. The PDF is a north-up, lossless A4 landscape atlas made entirely from downloaded zoom-16 Topo tiles. Older regions lacking those tiles need a new Topo download. Size estimates now use actual saved/sampled tile sizes when available, with a planning estimate and storage allowance.

The base APK retains zoom 0–8 overview images. Add zooms 9–12 using [all six prepared ZIPs](overview-packs/) through **Logo menu → Download offline region → Included imagery → Import overview ZIPs**. Existing imported imagery is compatible. Use these six prepared packs rather than the fifteen original export ZIPs.

Build, lint, 22 JVM tests, 11 JavaScript checks and browser workflow/touch checks passed. The streaming PDF writer also produced four-page and 80-page test atlases with a 48 MB Java heap limit; Poppler parsed and rendered the PDF. No emulator or physical phone validation is claimed; Android document-provider and live-download checks remain phone tasks.

[APK checksum](Ridgeline-3D-0.7.0-Base.apk.sha256) · [Versioned validation](Ridgeline-3D-0.7.0-validation.json) · [Detailed validation](Ridgeline-3D-VALIDATION.md).

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
