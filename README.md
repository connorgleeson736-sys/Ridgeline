# Ridgeline Android downloads

## Latest: 0.9.0 Base APK — 26.5 MB

[Download Ridgeline 3D 0.9.0 Base](Ridgeline-3D-0.9.0-Base.apk). Open the file page and choose **Download raw file**, then install over your current Ridgeline 3D app.

Route points can only be dragged or inserted in Edit mode. Layers is slightly smaller and closes when tapping the map outside it; the mapped-feature warning and overlay-status prose have been removed from view.

Route and offline sheets now have only compact and expanded positions. During dragging, transforms and clipping move the sheet and visible map boundary, with map resizing and elevation-chart rendering deferred until release. Saved routes reuse unchanged cards, distance summaries and thumbnails, and hidden lists defer updates until opened. An unchanged 50-route refresh creates zero new elements.

The base APK keeps zoom 0–8 overview imagery. Add zooms 9–12 with [all six prepared ZIPs](overview-packs/) via **Logo menu → Download offline region → Included imagery → Import overview ZIPs**. Existing imports remain compatible. The renderer, imagery, native offline mapping, single-page full-detail Topo PDFs and route persistence remain unchanged.

Build, lint, 23 Android unit tests, 13 JavaScript tests, and browser workflow/touch checks passed. Browser checks cover Edit-only dragging, dragging and undo in Edit mode, no map resizing during sheet movement, both sheet stops and rotation, map dismissal, saved routes, combining routes, region-frame gestures and native bridge calls. APK signature verified. No emulator or physical-device performance validation is claimed.

[APK checksum](Ridgeline-3D-0.9.0-Base.apk.sha256) · [Versioned validation](Ridgeline-3D-0.9.0-validation.json).

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
