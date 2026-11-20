# Native UI, route editing and map layers — 0.1.9 checkpoint

The native Rust/wgpu application now follows the released Capacitor application's
main map controls, route drawer, route sheet, editing rules and map-layer controls.
The existing native route editor, SQLite storage and GPX codec were extended
directly. The renderer remains native; there is no WebView or MapLibre runtime.
This is an implementation and controlled-host report. No connected phone or
emulator was available for Android interaction, visual or latency acceptance.

## Reference and scope

The remote `android-preview-download` branch was refreshed and its release records
read. The reference is Capacitor **0.18.0-performance**, exact application source
`d880143418ac46ece98adff0b3b2fbaedf044d46`, Base APK SHA-256
`0c452e427e7d4fda13950ebae61be06d8f93c804ede644d0a8c496d72ab2fa14`.
Relevant checked-out Capacitor/public sources match that release source. Native
baseline is the published 0.1.8 HTTP-latency checkpoint at `91aaa7b`.

Original imagery pixels, overview assets, full-resolution imagery downloads,
terrain data and the 0.1.8 HTTP/cache improvements are retained. Optional user
detail settings keep the existing defaults. Route planning algorithms were not
optimized or changed.

## UI and route behavior

- Paper, green and orange controls; route sidebar with cached north-up previews;
  zoom, compass, 2D/3D, GPS and map-centre coordinates; Layers/Advanced panels.
- Compact and expanded route-sheet stops use the reference's sizing rules.
  Dragging the sheet transforms and clips views; it does not resize the map on
  every pointer event. The map resizes at gesture boundaries.
- Explicit Edit mode gates append, insertion, selection, dragging and deletion.
  Waypoints drag from touch-down with a 3 dp movement threshold. A completed drag
  creates one undo/save operation; cancellation restores the original point.
  Selection without movement does not restart profile work or save the route.
- Editing taps bypass the double-tap-confirmation delay. Double-tap zoom is
  disabled during editing. Done clears waypoint selection and keeps undo history.
- Saved routes can be renamed, deleted, opened together, individually closed or
  closed together. Ordered combination selection and per-route reversal are
  separate from route visibility. Selection clears after a successful combine.
- Four route statistics, elevation chart inspection, selected-range statistics,
  endpoint interpolation, grade and missing-data gaps; linked green map cursor.
  Profiles pause during actual waypoint dragging and on app pause, then resume.
- GPX import, export and Android sharing. Sharing uses a private, read-only,
  URI-granted provider for generated GPX files. Existing database and route
  precision, autosave and size limits remain.
- Active orange route with white halo, off-white waypoint circles and blue-green
  passive routes. Passive routes draw before the active halo and core. Chart
  cursor and GPS marker have a separate small batch.

## Layers and loading policy

| Reference group | Native implementation |
| --- | --- |
| Aerial | Existing LINZ imagery and original gap handling |
| Satellite | Esri World Imagery, same original source pixel size |
| NZ Topo | LINZ raster Topo, ungraded pixels, nearest mip sampling, 2D |
| Names and peaks | LINZ vector data, reference filters/zoom/style expressions; independent switches |
| Roads and tracks | Shared vector source, including walking/vehicle/closed-track style rules |
| Huts and bivouacs | Shared vector source; native text atlas and circle symbols |
| Contours | Shared vector source; normal/index lines and labels |
| Imagery boundary | Original geographic boundary |
| Natural colours, relief, sky | Persisted controls; six sky presets and custom colour |
| Hillshade | Native adaptation of MapLibre's Igor arithmetic, optional/default off |

The style table and boundary are packaged from the pinned application's actual
sources. Labels use Android fonts rasterized on a worker into one cached atlas;
there are no remote font-range requests or per-label Android Views. Geographic
labels are depth-tested and use a screen grid for collision placement. Only
enabled source layers at the current zoom are decoded.

The source uses the existing shared transport, HTTP cache, offline store and job
limits. One topographic tile transfer is owned at a time; it remains in the
background network budget. Profiles also remain background work, below visible
imagery. Active labels/profiles have the same 40 ms local-cache escape deadline
without becoming foreground requests. Speculative warming remains cache-first.
Moving/pause cancels obsolete work. Useful cached parents/children bridge vector
zoom changes; unrelated tile data is retired. The atlas and styled line packet
are rebuilt only when their data/style/coverage changes, rather than every frame.

Offline Aerial/Satellite region downloads also save vector sources at z7–15.
Topo regions skip DEM and halo downloads. New region metadata supports Open;
legacy regions remain readable and explain when their old metadata lacks bounds.
The tile database migration adds one nullable metadata column. Route database
schema is unchanged. Native offline forms are retained; the Capacitor paper-frame
selector and Topo PDF workflow are outside this route/layer checkpoint.

## Most substantial performance changes

**2D no longer means tilted terrain with a zero-pitch camera.** With shading off,
the loader uses real flat cartographic geometry and does not request elevation or
its neighbouring DEM halo. A separate planar viewport cover avoids the native
terrain-height envelope and preserves non-overlapping nearest-child refinement
within the source budget. No synthetic zero-height data enters the real DEM or
prepared-terrain cache. Profiles continue to use actual elevation independently.
Optional 2D hillshade deliberately needs real DEM gradients. Switching modes
atomically invalidates geometry before the new mode renders.

**Line uploads are six times smaller.** One 64-byte instance describes a line
quad; the shader generates its six corners. The prior equivalent expansion wrote
six duplicate 64-byte records. Route and cartographic overlays share a compiled
pipeline, reuse buffer capacity and keep camera changes separate from tessellation.
Flat lines avoid expansion across terrain triangles; segment bounds reject
unrelated terrain before draping. Chart hovering does not redrape routes.

**Layer work is bounded and avoids unnecessary I/O.** A bounded protobuf reader
skips disabled source-layer geometry. Native fonts remove remote glyph startup.
Cache-backed parent/child coverage reduces disappearing names on zoom changes.
Profiles refine from better DEM tiles already resident in memory, coalesced at
150 ms, without extra network downloads. The source height quality is tracked so
coarser results cannot overwrite finer profiles. Cached route-card views avoid
thumbnail/layout churn during profile updates.

These remove work or bound its cost. They are not claims of a particular phone
speedup or that every original imagery stall has been eliminated.

## Focused evidence

Records are in [the validation folder](../validation/ui-layers-2026-11-01/).
No full test suite or lint was run for this checkpoint.

| Check | Result and scope |
| --- | --- |
| 11 unique JVM checks | 5 existing route-controller checks plus 6 MVT/style/profile/flat-cover/fallback/cache-deadline checks; all pass |
| Final Kotlin compilation | Pass; API-guarded legacy inset/back/memory callbacks produce deprecation warnings |
| Host Rust release check | Pass |
| Vulkan and GLES graphics fixtures | Actual software-driver rendering; route/layer/depth/atlas/Topo/sky/relief/2D checks pass |
| Instanced vs expanded line rendering | Exact frame pixels; 29,952 → 4,992 uploaded bytes for the same fixture |
| Full planar vs adaptive planar mesh | Exact checker-image pixels in the 2D fixture |
| Route cache behavior | Idle and camera pan do not repeat route geometry uploads in the fixture |
| Android release builds | ARM64, ARMv7 and x86-64 optimized/stripped JNI libraries pass; all 33 NativeBridge exports present per ABI |
| APK verification | Signature/package/version, ZIP/ELF 16 KiB alignment and native-only payload pass; no session CA or large packs |
| Original assets | All 619 baseline APK assets byte-identical to 0.1.8, including 618 source assets and the SDK public-suffix database |
| Tile schema migration | Host SQLite original resource bytes and membership preserved; nullable legacy metadata; not Android lifecycle execution |

The real LINZ z12/3983/2600 MVT fixture is 41,926 decompressed bytes with 135
features. Names-only decoding retains 20 relevant features and produces identical
enabled label output. Five alternating warmed host batches yield median decode
time **2.413637 ms → 0.0633849 ms**, about 38× for this disabled-layer case.
That is decode-only on the build host, not phone frame time or HTTP throughput.
All-layer styling of the same fixture yields 119 lines. Fixture provenance and
hash are recorded without credentials.

## Remaining differences and risks

- Android fonts, atlas scaling and collision placement approximate MapLibre's
  font stack/SDF placement. Line labels use horizontal midpoint placement rather
  than MapLibre's full curved-line layout. This is not pixel-perfect map parity.
- Igor arithmetic follows the reference shader, but gradients come from native
  mesh elevation rather than MapLibre's separate Sobel preparation pass.
- Dense 3D contours can still trigger CPU draping when terrain cover/heights
  change. Per-tile asynchronous drape caching remains a worthwhile next step.
  The implementation caches stable batches, but does not claim that work is
  already asynchronous or incrementally rebuilt per terrain tile.
- Limits bound malformed/huge sources and GPU work: 8 MiB source tiles, 50,000
  features, 262,144 points per geometry, 500,000 packet points, 32,768 packet paths,
  333,333 GPU line instances and at most 512 atlas candidates in a 2048² atlas.
  Dense views can omit content under these limits and normal label collision.
- Android sheet/gesture/font appearance, GPS permissions/fixes, GPX chooser
  sharing and migration lifecycle still need a physical-device pass. Host fixtures
  validate selected calculations and renderer behavior, not those OS interactions.
- At 5 Mbps, cold source bytes remain a real transfer constraint. Optional vector
  data shares the background budget; it is not free bandwidth. Job sliders retain
  their 1–50 range and do not increase connection or bandwidth by themselves.

See the checkpoint README and release JSON for the exact final source, signer,
APK hash, three ABI build records and packaging results.

Final source and native builds: `d258866d31509174c01aa0022094015d687566c2`.
APK: `Ridgeline-Native-UI-Layers-0.1.9-Base.apk`, 32,513,845 bytes, code 10,
SHA-256 `6faf1d0b4f0466834048859781aeb3fb05aaff236404935d2bfdf1102ebedf23`.
Same `nz.ridgeline.nativeapp.performance` package and existing signer; compatible
in-place update over 0.1.2–0.1.8. Android OS/device migration behavior is untested.
