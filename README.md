# Ridgeline Android downloads

## Latest: 0.10.0 preview APKs

| Build | Download | Size |
| --- | --- | --- |
| Base | [Ridgeline 3D 0.10.0 Base](Ridgeline-3D-0.10.0-Base.apk) | 26.5 MB |
| Full imagery | [Ridgeline 3D 0.10.0 Full](Ridgeline-3D-0.10.0-Full.apk) | 391.2 MB |

Choose either build, open its file page and use **Download raw file**, then install over your existing Ridgeline 3D app. Full is stored with Git LFS; Download raw file serves the complete APK. Both have the same app code and signing certificate.

Full includes all original precomputed imagery overviews through zoom 12, with a verified 364,762,502-byte extra pack. Base includes zooms 0–8; import [the six overview ZIPs](overview-packs/) to add zooms 9–12. Existing imports remain compatible. Elevation and more detailed offline imagery still use selected-region downloads.

Offline downloads now start with page size (A5, A4, A3, Letter or Legal), orientation and **Save current view**. Move/zoom the map under the page frame and resize its corners with the page aspect preserved. Saving freezes the geographic area and opens a large dialog for name, map content, detail, estimation and downloading. Change area returns to page selection. Saved-region Open and Export Topo PDF actions have distinct spacing.

PDF export offers standard paper or a full-detail custom page, an NZTM2000 (EPSG:2193) grid with edge references, true north, magnetic north and a scale bar. All native raster pixels remain losslessly embedded on one page. Standard paper fits the saved area within the page margins; print scale depends on the area selected. The grid is projected onto the Web Mercator map. Magnetic north is an Android model estimate at the region centre/date, or a user-supplied declination, and is labelled accordingly.

LINZ API peak labels default on, with the legacy bundled summit labels disabled. Topo on dense screens now requests finer tiles through zoom 18. New Topo downloads include zoom 18; re-download older zoom-16 regions for finer offline detail. Older regions remain exportable, and paused older downloads retain their original detail plan. The 12,000-file region limit remains.

Builds, signatures, lint, **29 Android unit tests**, **15 JavaScript tests**, browser workflow/touch checks, and a separate actual-request Topo density check passed. Twelve PDF fixtures cover all page sizes/orientations and parse as single pages; a lossless image stream was compared with its original RGB bytes. NZTM calculations were checked against independent PROJ control points. All six full-pack hashes and uncompressed APK storage were verified, and Base/Full app code and DEX match. No emulator or physical-phone performance validation is claimed.

[Base checksum](Ridgeline-3D-0.10.0-Base.apk.sha256) · [Full checksum](Ridgeline-3D-0.10.0-Full.apk.sha256) · [Validation](Ridgeline-3D-0.10.0-validation.json) · [PDF fixtures validation](Ridgeline-3D-0.10.0-PDF-validation.json)

Source: [0.10.0 implementation](https://github.com/connorgleeson736-sys/Ridgeline/commit/1e805cfeb7bf7b885561a4afb6130e3f5978ea4d).

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
