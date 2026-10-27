# Capacitor preview 0.8.0 validation

Validated 27 October 2026 (Melbourne). Development-signed preview; no Play Store release.

## Imagery evidence

- All 15 user-provided imagery ZIPs were read. Uploaded documentation was treated as export metadata, not execution instructions.
- Reconstructed the original authoritative 2,115,490-byte overview index from the export and repository catalogue. SHA256 exactly matches the pinned `0131124bab52e92164eb46f4e61ca1e41743cb1df68ce6a49a2cb35427c22da1`.
- Verified SHA256, WebP format and 256×256 dimensions of all **5,344** original overview images, covering **5,313 coordinates / 10,626 raw/natural mappings**.
- All **67** zoom 0–8 image files in the existing **0.2.0 APK** exactly match the uploaded export. They total **3,681,702 bytes** and are the downsampled overviews, not substituted 10 m imagery. Ordinary provider imagery may still appear outside their coverage or as a fallback.
- Repacked the missing **5,277** zoom 9–12 images into **six** data ZIPs without changing their bytes, resolution or colour. Additional payload: **364,762,502 bytes**. Every optimized file range was independently checked against the original image hash.
- The exact streaming SHA256/size verifier used by the Android importer accepted all six real data ZIPs in a desktop JVM. This verifies the ZIP stream/crypto path, not Android's document picker.
- Separate Cook-reference assets, duplicate coarse assets and intermediate overlays are excluded from the new data packs. The original general imagery shader and raw/natural variants remain.

## Full APK packaging

The 0.4.0 installer bundles the six verified zoom 9–12 binary packs uncompressed, alongside the already-bundled zoom 0–8 images. The native gateway reads each image directly from its APK asset descriptor using the asset start offset and validated image range. It does not extract or duplicate the 364.8 MB on first launch. Full-build packaging checks verify every embedded pack SHA256/size; final APK checks verify ZIP storage mode and image ranges inside the actual installer.

New JVM tests cover nonzero APK asset offsets, bounds, truncated reads and oversized reads. The full APK UI immediately states that overviews are included and hides download/import controls. Existing 0.3.0 imports can be removed independently to reclaim their duplicate storage.

## Software checks

- Android debug/preview builds and lint; JVM tests include archive streaming, tamper detection, truncation/excess-size rejection, download plans and resource addressing.
- Web packaging checks include local overview addressing and bounded file ranges, with no dependency on the original site's private archive endpoint inside the app.
- Website checks cover baseline rendering, phone performance/LOD, Topo protocol and overlays, and the new Topo native-resolution policy. Existing aerial LOD/resolution behaviour is retained.
- Chromium checks exercise the actual 3D Direct renderer, route/profile and rectangle workflows, precise SVG GPS centring, Topo DPR/zoom/sampling, and restoring aerial zoom limits. Provider fixtures are controlled; archive-backed runs read the actual verified overview files.
- Preview signatures, ZIP CRC and signer continuity with 0.2.0 are checked before delivery. No emulator validation is requested or performed for this update.

## Phone checks still needed

Test direct APK asset reading and offline use after restart, GPS/gesture behaviour and memory/storage limits on the phone. No new import flow is required in the full build. GPS positioning was confirmed working by the user in the previous app; its location logic is preserved in this update. Desktop/JVM/browser checks do not replace these phone checks.

The printed Topo service is a raster map with finite detail. The changes remove photo-oriented degradation and excessive enlargement; they do not manufacture additional survey detail.

Website source changes are committed and provided in a four-file deployment ZIP. The original live Site was **not deployed** by this cloud session: its host is blocked by the environment's outbound policy and no Site deployment connector is available. Apply the patch through the original Site editing/deployment session.

## 0.5.0 panel changes

Route creation lives under Import GPX in the logo menu. Route and offline panels reserve the bottom 3/8 of the viewport; camera fitting and hit testing use the resized map. App popup dragging/resizing is disabled for these fixed panels. Coordinates and bundled imagery information are collapsed. Rectangle selection has a first-corner marker, pointer preview, small-area validation and cancellation that retains the previous region. Browser checks cover saved-route reopening, panel/map geometry and selection cancellation. No emulator validation is used.

## 0.6.0 bottom sheets and region controls

The route and offline panels use a Material-style pill handle with compact, 40% and 64% height stops. Touch/pointer dragging, tap cycling and keyboard controls update the actual map viewport. Resize events preserve the selected stop across rotation. Compact route mode hides the graph and route name, retaining metrics and edit/export/delete-point/undo; the graph returns in the two larger positions. Legacy More, automatic-save text and elevation-toggle controls are not presented. The route close control is an ×. Automatic saving still protects routes.

The offline download action is pinned to the bottom of the logo sidebar. Saved regions use the native download list, with state/size and an Open action; no second region catalogue is maintained. Rectangle corner markers provide 44 px touch targets and pointer/keyboard resizing, update stored bounds and preserve route points. Region editing suppresses route picking and drawing. The smaller base APK and six prepared ZIP import workflow are retained.

The browser check uses a controlled Android data bridge for saved-region responses and real MapLibre gestures for sheet and corner dragging, including Chromium touch events. It exercises initial route creation in 3D, then repeated sheet resizing, rotation and corner manipulation in 2D to keep software-rendering load bounded. It also checks rotation, compact/expanded controls, chart width, saved-route/region reopening and cancellation. These checks exercise the app UI in Chromium, not physical Android device gestures or the native download service.

Pattern reference: [Material Components bottom sheets](https://github.com/material-components/material-components-android/blob/master/docs/components/BottomSheet.md), including drag handles, collapsed/half-expanded/expanded states and accessibility actions.

## 0.7.0 verification

- New pure Java tests verify Topo-only plans, maximum Topo detail independent of aerial zoom, measured source-specific byte estimates, unavailable sample fallbacks, PDF tile coverage, cross-reference offsets and rejection of missing images.
- A standalone run of the exact PDF writer generated four-page and 80-page fixture atlases with a 48 MB Java heap limit. Poppler parsed and rasterized the four-page PDF; its landscape page geometry, crop, image orientation, title and attribution were visually inspected. This validates PDF serialization, not Android's document picker.
- JavaScript checks cover route join order, reversal, identical shared endpoints, preserving originals and the 2,000-point limit. Browser workflows cover real route unloading, multiple visible routes, combined-route creation, revised layer placement, smaller sheets, 2D SVG region editing, touch/keyboard corner movement, maximum-detail Topo form settings, sampled-estimate display and the PDF bridge request. Native data/provider responses are controlled fixtures.
- Full Topo PDF output from live LINZ downloads, Android document-provider behaviour and phone GPU responsiveness still need physical-device checks. No emulator was requested or used. Estimates are not confidence intervals; their storage allowances reflect sampling and fallback heuristics.

## 0.8.0 verification

Single-page export keeps every zoom-16 tile as a 256×256 lossless PDF image and clips to exact selected bounds. PDF unit tests check page count, resource coverage, cross-reference offsets, and UserUnit on a long map. Independent normal, large, and long fixture exports each report one page; the writer runs under a 48 MB Java heap. Poppler parses the PDF 1.6 output and renders the complete continuous map. Original v0.7 atlas results above are historical.

JavaScript tests check route-summary distances and thumbnail fitting for point, horizontal and vertical routes. Browser checks exercise summary/thumbnail cards, persisted sidebar rename, backdrop dismissal without point edits, default-off peak labels, actual map underlap and rounded clipping, and a fixed screen frame while the map is panned/zoomed. Touch/keyboard corner resizing and sheet stops remain covered. Android file-picker interaction, live provider downloads and physical GPU performance are not validated by these fixtures.
