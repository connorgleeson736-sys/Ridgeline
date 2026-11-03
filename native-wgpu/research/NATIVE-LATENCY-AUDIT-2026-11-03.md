# Native latency audit implementation — 0.1.10

This implements the supplied `Ridgeline-native-0.1.9-latency-audit.zip` against
native 0.1.9 source `d258866d31509174c01aa0022094015d687566c2`. The audit's original
findings and standalone reproduction files are preserved in
`../validation/native-audit-2026-11-03/input/`. Those findings are evidence and
recommendations, not a claim of measured handset performance.

## Enabled changes

* **Gesture picking:** publish an immutable snapshot of the last submitted scene,
  including camera, viewport, relief and actually drawable terrain/image coverage.
  A dedicated input worker intersects its referenced CPU heightfields without
  joining the GPU handler queue or taking the renderer-state mutex. Only the
  current gesture receives a matching scene result. Exact route/waypoint placement
  keeps its existing path. The existing provisional plane fallback remains;
  this does not introduce provisional dragging/re-anchoring on mountain faces.
* **Camera admission:** with complete retained coverage, a changed camera goes
  ahead of optional tile, line and atlas work. Optional work gets a bounded later
  opportunity (64 ms maximum deferral, 32 ms follow-up); initial/missing coverage
  stays urgent. A zero optional allowance no longer admits the first tile anyway.
* **Boundary clipping:** index tight national path and 32-segment chunk bounds,
  then cache immutable clipped fragments by canonical tile ID (128 entries).
  The clip arithmetic, path order and fragment breaks remain unchanged.
* **Cartography:** route-only mutations no longer invalidate unrelated map lines,
  labels and inspection. Real terrain/style changes still invalidate draping.
  Per-patch fragments retain heightfield generations and reuse unchanged terrain;
  flattening preserves original path/cover order. A bounded latest-job worker
  performs draping outside the render thread; obsolete work cancels. Overlarge
  fragment sets use the original bounded builder instead of unbounded caching.
* **Labels:** stable sprite slots separate text/style content from placement and
  priority. Identical sprites share a slot; metadata-only changes reuse the same
  immutable pixel buffer and issue no texture upload. Same-size texture updates
  upload only changed row bands. Atlas repacking retains an atomic mapping/pixel
  pair. CPU collision placement runs on the existing background worker with
  latest-projection rejection. Reusing a line packet skips redundant JNI parsing.
* **Unshaded 2D:** one explicit shared zero heightfield and GPU height resource
  replaces per-tile buffers/textures. Cached plane metadata bypasses DEM worker
  readiness and follows mode-tagged GPU admission, preventing stale plane data
  from crossing into 3D. Shaded 2D retains measured DEMs. Original triangulation,
  texture sampling, clipping and overlay depth are retained.
* **Diagnostics:** render CPU time and mutex wait last/max, and completed
  centre-raster-to-halo readiness count/mean/max are exposed. The latter measures
  observed pipeline readiness, not exact HTTP time or input-to-display latency.

The camera snapshot references are retired outside the input publication lock.
All worker queues are bounded/latest-only. GPU/context reconstruction reapplies
style and overlay state. Tile networking, full imagery resolution, terrain detail
settings, original assets and route planning are preserved.

## Experimental progressive terrain — off by default

`Progressive focus terrain · experimental` offers one high-priority delta-one
focus patch when its main raster is finite/fresh, measured fallback terrain was
actually drawn, and usable imagery is resident. Its 128 × 128 valid vertices
supply exactly 16,129 of 16,384 cells (98.44% of patch cells, not screen pixels).
The missing 255-cell L-shaped rim retains measured parent ownership. Masked
heightfields exclude padding from bounds, picking, camera queries, draping and
error calculations; complete residency/acknowledgements exclude partial fields.
Full upload replaces the preview atomically. Tokens prevent canceled work from
clearing a newer preview or promoted complete source.

The same ownership partition feeds CPU queries and rendering. Exact rectangular
GPU clips coalesce the quadtree representation so the tested synthetic scene
needs only 5–6 terrain draw calls. No dummy/no-data heights are drawn or queried.

Rollout is deliberately restricted. Geometry zoom must be 1–19; a complete real
ancestor must have owned the entire patch in the last submitted scene; complete
loaded descendants reject staging. The parent is sampled at full grid detail.
Existing downward skirts must cover every checked fine-to-parent edge: a parent
above the fine edge or a drop exceeding the skirt rejects staging. Parent
replacement/eviction and a zoom that shortens the skirt remove the preview.
These restrictions can decline many real mountain joins. Unsupported cases use
the complete-patch path. This is not a universal fix for the cold-area stall.

If a donor becomes usable 300 ms after the main raster, that dependency gap is an
opportunity, not a promised 300 ms saving: imagery, scheduling, eligibility and
presentation still matter. Warm inputs avoid an extra preview upload. Collect
real centre-to-halo distributions and test mountain seams on a phone before
expanding rollout or enabling it by default.

## Deliberately deferred

The two-triangle planar quad is not shipped. The supplied audit found 1,950
nearest-texel differences at aligned sampling ties. The shared-resource path
removes allocation/readiness costs while retaining the original grid raster.
No resolution reduction, aggressive LOD substitution, network request hedge or
route-planning changes were introduced.

## Focused verification

* Three production Kotlin/JVM audit checks: 262,144 measured-interior Float32
  comparisons across all quadrants/zoom fixtures, including signed zero;
  boundary output/order equality across 72 real-asset boxes and cache reuse;
  urgent-only admission at zero optional budget.
* Boundary segment tests fall from **767,520 to 4,642** in those fixtures (99.40%
  fewer). These are work counts, not measured Android milliseconds.
* One focused Rust cache check compares output bytes to the original builder,
  verifies only the changed terrain fragment rebuilds and checks cancellation.
* Production-renderer synthetic scenes pass software **Vulkan and GLES**: four
  progressive quadrants, measured parent rim ownership, no false complete
  acknowledgement, immutable snapshot parity and pixel-identical complete frames
  after promotion. Shared planes preserve tested pixels with one height resource.
  Route-only edits launch no cartography job/upload. Metadata-only labels upload
  no texture bytes; the small dirty-row fixture uploads 64 bytes. Existing native
  layer/route, projection, sky and relief checks also pass.
* All three optimized Android release JNI builds and signed APK assembly pass.
  Source `655de14`, code11/name `0.1.10-native-audit`; artifact 32,716,605 bytes.
  Same native Performance signer/package as0.1.9; all621 original APK assets are
  byte-identical. Signature, ZIP/ELF16KiB alignment and all38 JNI exports perABI
  pass. No session CA, WebView/Capacitor runtime or large overview packs.
  Exact source/digests and published-download proof are in
  `../validation/native-audit-2026-11-03/`.

No broad test suite/lint was run. No handset or emulator was connected. Software
GPU fixtures cannot establish Android driver performance, touch drift, thermal
behaviour, installation execution or real input-to-present latency. In particular,
none of the work-count or synthetic pixel checks proves a 200 ms phone saving.
