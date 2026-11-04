# Capacitor sharpening fix — 0.20.1 candidate, APK not published

Two continuity checks could retain low-detail terrain/colour patches after the
data required for the visible view had loaded. The zero-RTT Direct retention in
0.20.0 made these conservative checks apply to more transitions.

1. Whole-footprint imagery readiness and quality checks could wait forever for
   off-screen corners that native camera-frustum selection never requested.
2. Geometry readiness compared a patch's zoom with DEM requests without applying
   the native terrain manager's deltaZoom (normally 1). A z14 terrain patch is
   normally ready with a z13 DEM, bounded by source maxzoom; a neighbouring z14
   DEM request must not block that handoff.

Retaining a larger coarse terrain patch also stretches its finite-resolution
colour texture over a larger area, so finer source images can be loaded without
the displayed surface becoming sharp.

The app-only adapter now clips imagery requirements to the native manager's
current ideal tile selection, with conservative full-patch checks for unknown
or empty demand. Loaded-image coverage indexing is still reused, while each
readiness snapshot captures current camera demand. Geometry readiness follows
native deltaZoom and source maxzoom. It still requires measured elevation and
retains the parent while an actual dependency is pending. Source LOD, pixels,
mesh detail, shaders, route features and cache budgets are not reduced.

## Observed verification before the environment disconnected

Four focused regression checks passed:
- visible versus unrequested off-screen imagery;
- camera-demand changes with unchanged loaded image keys;
- coarse-to-fine RTT invalidation, still holding missing/worse visible imagery;
- native DEM dependency handoff, source ceilings and delayed DEM arrival.

A controlled actual-app Chromium/WebGL replay at Mount Cook delayed requests
at z14 and above by 2.5 seconds, then jumped to camera zoom14 and stopped moving.
The old 0.20.0 run finished 47 imagery and19 DEM loads, with zero queued image
work, but retained one coarse cover, counted six quality holds and two deferred
refreshes. After both fixes, 47 imagery and19 DEM loads completed with zero
waiting covers, zero quality holds, zero deferred refreshes and no captured
page/console errors. These are correctness/work counts, not handset timings.

Sampled LINZ coarse/fine and Esri fine URLs returned valid HTTP200 images from
the build environment. This establishes availability there, not the state of
the user's Wi-Fi or Android network path. The reproduced blockers match a way
sharpening can stall; the user's entire handset symptom is not yet confirmed fixed.

Earlier component fixtures missed this coupled camera/imagery/DEM transition.
A final strengthened replay comparing desired and drawn terrain tile sets was
prepared but not completed before the environment disconnected.

## Publication status and recovery

The managed workspace became unavailable while final Capacitor sync and evidence
copying were in progress. The provider reports the environment running but its
runtime connectivity offline. A 0.20.1 Android assembly/signing/public download
was NOT completed. Latest published APK remains 0.20.0 Audit Base.

The source fix and four tested regression cases were preserved through GitHub
after shell access failed. They are on capacitor-latency-audit. Version bumps to
0.20.1/code24 and the rendering replay were prepared in the workspace; recover
and reconcile those uncommitted files with the newer remote commits before
resuming. Local scripts/logs use:
- capacitor/scripts/visible-refinement-browser.mjs
- /workspace/Ridgeline/work/apk-build/cap-sharpen-regression.log
- /workspace/Ridgeline/work/apk-build/cap-sharpen-delayed.log
- /workspace/Ridgeline/work/apk-build/cap-sharpen-fixed.log
- /workspace/Ridgeline/work/apk-build/cap-sharpen-sync.log

Finish sync, run the strengthened rendering replay, freeze a source commit,
assembleLatencyAudit with the existing signing key, and verify the final APK's
package/version/signature/alignment and original assets before publication.
No new signing key is needed. Keep the separate Audit app identity so this
updates 0.20.0/0.19.0 Audit in place.

The original-response 0–1GB disk-cache slider, existing elevation PNG HTTP
caching, saved regions and all four additional audit changes remain. No new
prepared terrain/mesh disk cache is included.
