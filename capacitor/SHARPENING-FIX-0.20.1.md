# Capacitor 0.20.1 — sharpening handoff fix

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

## Focused verification

Four focused regression checks passed:
- visible versus unrequested off-screen imagery;
- camera-demand changes with unchanged loaded image keys;
- coarse-to-fine RTT invalidation, still holding missing/worse visible imagery;
- native DEM dependency handoff, source ceilings and delayed DEM arrival.

A controlled actual-app Chromium/WebGL replay at Mount Cook delayed requests
at z14 and above by 2.5 seconds, then jumped to camera zoom 14 and stopped moving.
The old 0.20.0 run finished 47 imagery and 19 DEM loads, with zero queued image
work, but retained one coarse cover, counted six quality holds and two deferred
refreshes. After both fixes, 47 imagery and 19 DEM loads completed with zero
waiting covers, zero quality holds, zero deferred refreshes and no captured
page/console errors. These are correctness/work counts, not handset timings.

Sampled LINZ coarse/fine and Esri fine URLs returned valid HTTP 200 images from
the build environment. This establishes availability there, not the state of
the user's Wi-Fi or Android network path. The reproduced blockers match a way
sharpening can stall; the user's entire handset symptom is not yet confirmed fixed.

Earlier component fixtures missed this coupled camera/imagery/DEM transition.
The focused rendering replay now also asserts that drawn terrain tiles match
the native desired tile set once loading completes. It exercises rendering only,
without route workflows or a broad test/lint run.

## APK packaging

0.20.1 / version code 24 uses the existing Audit package and signing key so it
updates 0.20.0 and 0.19.0 Audit in place. Exact source, signature, package/version,
alignment, original-asset and public-download records are stored in
`validation/sharpening-0.20.1/`. The original Performance app remains separate.

The original-response 0–1GB disk-cache slider, existing elevation PNG HTTP
caching, saved regions and all four additional audit changes remain. No new
prepared terrain/mesh disk cache is included.
