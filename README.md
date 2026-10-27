# Ridgeline Android downloads

## Latest: 0.8.0 Base APK — 26.5 MB

[Download Ridgeline 3D 0.8.0 Base](Ridgeline-3D-0.8.0-Base.apk). Open the file page and choose **Download raw file**, then install over your current Ridgeline 3D app.

Topo PDF export now produces **one continuous page at full downloaded detail**. Every native zoom-16 tile stays losslessly embedded; long maps use PDF 1.6 page scaling rather than reducing pixel resolution. Choose **Export Topo PDF** in Saved regions after completing a maximum-detail Topo download. Older regions missing zoom-16 tiles need a new Topo download.

Saved routes have distance/waypoint summaries, small route-shape previews, and inline Rename/Save/Cancel. Open/Close, multiple routes and combining selected routes remain available. Tapping the exposed map closes the sidebar without changing route points. Peak labels default off; explicit changes are saved.

The sheet grip is slightly taller, and the map extends underneath the rounded corners to remove the square backdrop. **Draw rectangle** now creates a screen-fixed frame and leaves the sheet open. Pan/zoom the map underneath it; drag its corners to resize it. Current view expands the frame, and Around route fits the map and frame to your route.

The base APK keeps zoom 0–8 overview imagery. Add zooms 9–12 with [all six prepared ZIPs](overview-packs/) via **Logo menu → Download offline region → Included imagery → Import overview ZIPs**. Existing imports remain compatible.

Build, lint, 23 JVM tests, 12 JavaScript tests, browser workflow/touch checks and independent PDF parsing/rendering checks passed. Normal, large and long map fixtures all export as one page under a 48 MB Java heap limit. No emulator or physical phone validation is claimed.

[APK checksum](Ridgeline-3D-0.8.0-Base.apk.sha256) · [Versioned validation](Ridgeline-3D-0.8.0-validation.json) · [Detailed validation](Ridgeline-3D-VALIDATION.md).

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
