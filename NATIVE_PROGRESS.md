# Durable progress log

## 2026-10-29 — migration started

User explicitly replaced the Capacitor optimisation task with a custom native
Rust/wgpu Android migration. New branch native-rust-wgpu in /workspace/Ridgeline-native
starts from authoritative 0.18.0 source d880143. Original source and APKs preserved.

Initial findings: android/ is an older Compose + MapLibre Native 13.7.1 app. Its
README explicitly says flat raster/hillshade, not the Capacitor draped 3D terrain,
and excludes patches/grade. It is a source of reusable native planner/storage code,
not an accepted renderer or visual reference. Capacitor owns the current behavior.

Environment: JDK21/SDK36/Gradle8.13 present in /workspace/android-tools; Rust is not
on PATH. Need inspect/install Rust and Android NDK before wgpu cross-build.
Network policy restricted; inherit proxy and CA trust. No device execution yet.

Commands: git worktree add -b native-rust-wgpu /workspace/Ridgeline-native d880143...
Result: successful. No native implementation or APK yet.

Exact next step: fetch official wgpu current Cargo/example/docs and Android surface
lifecycle guidance; inspect Capacitor camera/terrain shaders/routing/native storage
and existing Compose planner; write concrete parity audit and architecture decision;
install Rust/NDK and implement the smallest terrain/imagery proof.

## Toolchain and initial pure core

Installed Rust stable 1.99.1 with CARGO_HOME=/workspace/android-tools/cargo and
RUSTUP_HOME=/workspace/android-tools/rustup. Android aarch64 target install started.
Official source snapshots fetched using raw.githubusercontent.com; GitHub REST API
is proxy-denied. Read research/DECISIONS.md for source URLs and provisional choices.
Rust workspace started: core geographic/height encoding primitives and renderer
crate pinned to wgpu 30.0.1. NDK 29.0.14206865 install started via SDK manager with
Java proxy/CA flags. No GPU implementation, Android native migration build or
parity validation exists yet. Cargo dependency resolution initially caught an
incorrect ndk feature name; corrected to rwh_06/nativewindow before compilation.

Running commands/logs: /tmp/ridge-native-core-test.log, /tmp/ridge-native-ndk-install.log,
/tmp/ridge-native-rust-target.log. Exact next step: finish these commands, run core
tests (fix any failures), then implement SurfaceView/JNI wgpu surface and textured
DEM mesh prototype. Do not confuse the stub renderer module with working rendering.

## Native GPU and Android prototype checkpoint

Host core: 3 tests pass. Real wgpu draw+readback passed using software Vulkan:
VK_ICD_FILENAMES=/usr/lib/chromium/vk_swiftshader_icd.json cargo run ... --example gpu_smoke.
Pixel center [36,113,74,255] from a synthetic flat 1000m field and uniform image.
This proves native pipeline/depth/grade/readback execution, NOT real terrain parity.

Rust ARM64 cdylib successfully cross-built against NDK29/API26. Android SurfaceView
JNI integration and native widget Activity compile; assembleDebug and lintDebug pass.
APK is native-wgpu/android/app/build/outputs/apk/debug/app-debug.apk. v2 signature
and 16KiB ZIP alignment checked. No emulator/physical device execution yet.

Prototype includes real-source LINZ loading, deduplicates per tile job, bounds
HTTP persistence and decoded-ready tile count, uses native Android bitmap decode,
and passes direct native buffers to Rust. Renderer work is scheduled on a dedicated
GPU thread and only on changed camera/data (no continuous idle render loop).
These are prototype paths: route UI, geographic gap selection, overview data,
proper LOD/fallback, halo seams/mips and robust memory budgets remain open.

External blocker: LINZ/S3/Esri and some official documentation hosts are denied by
the runtime proxy. Saved configuration draft 2018dde912308198977079bf4a502b5f adds
only needed hosts. Told user to review/save in environment settings and publish;
not active yet. No bypass attempted. Live DEM fetch failed without printing key.
Continue all independent work; do not claim a real-DEM or device parity test.

Exact next step: add deterministic neighbor-halo mesh/seam tests and parent-cover
selection, port genuine bundled overview routing/range reads and gap masks, then
run the Android native surface on an emulator if feasible (no /dev/kvm currently).
Native UI/route migration must wait for the critical renderer proof acceptance gate.

## Geographic imagery and bounded native residency checkpoint

Data-only native port now selects original LINZ, all 630 reviewed geographic gap
entries, coarse gap overlays, z18 Esri parent crops, fine-survey alpha veto, and
37 Cook reference coordinates. Six original binary overview payloads are verified
by SHA-256/length before packaging, preserving 364,762,502 bytes plus coarse images.
No JavaScript is packaged or executed by the native app. Build-time Node only
extracts reference data and generates independent algorithm outputs.

Validation: 2,520 native Kotlin routing cases and 360 masks exactly match executed
0.18 JavaScript reference outputs; polygon-hole test also passes. Android unit
suite (2 tests), assembleDebug and lintDebug passed before addition of Cook data.
Need rerun packaging/build after that addition. No live data or phone claim.

Tile loader now uses pipeline=terrain-rgb, deduplicated neighbor DEM downloads,
real parent fallback on 404/nodata, global pixel-centre halo sampling, bounded
memory caches based on Android memory class, three priority workers and removal
of obsolete queued jobs. GPU renderer now bounds tile residency and uses cached
non-overlapping ancestor/descendant coverage. Controlled GPU smoke still passes.
These are not a finished viewport LOD system: frustum selection, LOD stitching,
mips, fades, bounded upload scheduling and actual camera parity remain open.

Android emulator system image and Rust x86_64 target installed. Software emulator
started without KVM; boot is slow. x86_64 native library compiles. adb cannot create
its default auth directory under read-only /home/agent; investigating an explicit
writable config/transport. Logs in /tmp/ridge-native-{emulator,x86-build}.log.
Network policy still has no additional approved hosts; real DEM validation blocked
pending the previously requested environment draft publish.

Exact next step: run GPU parent/fine coverage and memory-bound tests; rebuild both
Android ABIs with the current library, package the verified imagery/reference data,
run Android surface/lifecycle proof on the emulator, and audit reference camera.

## Camera equivalence and bounded upload scheduling

Generated camera fixtures directly from the authoritative bundled MapLibre engine:
1,712 screen projections across NZ positions, zoom5/11.9/16/19, bearings, pitches
and phone/landscape sizes. Native Rust passes with worst screen deviation
0.00390625px. Near/far and horizon policy also match numerically (unpadded, zero
roll, default minimum elevation). This exposed and fixed prototype camera-up sign
and altitude scaling: MapLibre uses mean Earth circumference 40,030,228.88407185m,
not the EPSG:3857 circumference. Rebase altitude as well as horizontal position
before Float32 GPU transforms to keep close high-elevation views stable.

This does NOT prove terrain collision, gesture anchor/inertia, padding/fitRoute,
real DEM shape or visual parity. Those remain explicit acceptance items.

Android GPU uploads now coalesce by tile identity, bound pending ownership and
upload at most two complete tile payloads per frame. Device memory class sets
GPU residency; paused apps stop frames. Added queue burst/dedup unit test (must
rerun latest build). GPU smoke additionally verifies exact graded pixel, one fine
child plus parent siblings without uncovered pixels and a 24-tile residency cap.

Software Android emulator reached boot_completed=1 and package service is ready.
Direct standard local ADB protocol tooling avoids changing read-only host HOME.
First APK install before package service was ready failed with Broken pipe; retry
with full x86_64 APK is running. System image had a system-server restart during
slow software boot; these logs are not app crashes. Need actual app launch, GPU
surface and lifecycle evidence before claiming emulator validation.

## Viewport selection, filtering and actual Android integration attempts

Repository audit now durable in native-wgpu/research/AUDIT-018.md, including
route/profile contracts, original JSON trace limits, exact source chain and
remaining acceptance gates. Latest preview branch independently remains39431dad.
Additional current official-source snapshots/hashes recorded in research/.

Replaced temporary3×3 loader requests with native conservative viewport quadtree,
bounded96-target test plans, prioritized refinement heap, actual parent requests
and retained ready data. Phone density is included in the native projection;
initial camera now uses exact Aoraki coordinates/elevation3724m. Plan tests verify
center coverage and no ancestor overlap at multiple pitches/zooms. Unknown DEM
extrema are conservatively0..4000m for visibility only; actual terrain is never
synthesized. Real per-tile extrema, stitching and movement policy remain open.

GPU cover recomputes once per changed residency batch; duplicate JNI uploads touch
resident content without rebuilding height arrays. Image resources now close on
all success/error paths. Dedicated priority workers, bounded streams and HTTP
cache cleanup are present; combined peak memory still needs measured budgets.
Added GPU9-level imagery mips and supported anisotropy; GPU checkerboard
minification readback passes while full-resolution level0 remains unchanged.

Rust optimized libraries compile for ARM64, ARMv7 and x86_64. Added reproducible
build script, pinned/checksummed Gradle wrapper, overview ZIP extractor and APK
verifier. Full x86_64 packaging verifies6unchanged packs/364,762,502bytes,
5,277unique packed photo ranges (plus67coarse) and no web assets/WebView/Capacitor
DEX references. This is packaging evidence, not feature completion.

Emulator first boot took≈397s without KVM. Full debug fixture APK installed and
activity launched, but the host emulator segfaulted in SwiftShader after logging
unsupported WGSL debug OpSource metadata. Disabled optional InstanceFlags::DEBUG,
retaining validation. Second boot≈210s; optimized APK installs successfully and
ProofActivity launches (am start statusok). Vulkan device creation and first
sync export are logged. Screenshot initially black; inspecting warm UI/capture
before claiming surface success. Debug fixture is explicitly labeled synthetic
flat heights/genuine Cook imagery; it never appears as live-source fallback.

Cloud selection benchmark:500 warmed calls, median33.42µs/p9583.024µs with95–96
leaves/96budget. This is isolated optimized cloud CPU selection only, not a phone
speed estimate, full frame time, or a comparison with the trace's nested timings.

Exact next step: finish optimized emulator fixture capture and six-pack range-read
check; test surface recreation/background. If emulator rendering fails, diagnose
native/backend compatibility with actual logs. Rebuild ARM optimized device APK
with latest source (mips/selection/JNI changes), run all tests/lint/package checks,
commit. Live DEM/visual acceptance still needs the pending network draft publish;
then complete parity before migrating the full planner/UI. Do not stop at a plan.

## Successful controlled Android rendering and exact original ZIP packaging

Optimized x86_64 retry renders genuine Cook imagery on the controlled flat height
fixture. Captures now committed under native-wgpu/validation/ in portrait and
landscape. Android ATD had debug.hwui.drawing_enabled=0, explaining missing native
UI in initial screenshots. Set it to1 and restart app; native TextView and wgpu
SurfaceView render together. Manual background/resume and rotation both render.
This is actual Android integration evidence, NOT real-terrain or phone validation.

Important packaging correction: six original overview ZIPs are ZIP_STORED inside,
so can preserve the literal original ZIPs as requested without extraction or
payload duplication. package-archives.py verifies original outer SHA/size plus
inner payload SHA/size; generated catalog gives native absolute range offsets.
All six exact ZIPs are now stored uncompressed in APK and read directly through
AssetFileDescriptor/FileChannel. The wrappers add just1,404bytes over payloads.
Verifier proves six originals,364,762,502payloadbytes/5277ranges +67coarseimages,
and zero JS/HTML/WebView/Capacitor runtime assets/references. Updated build input
is RIDGELINE_OVERVIEW_ZIPS pointing to original preview overview-packs directory.
Older log entries describing .bin-only packaging are superseded by this change.

Added frustum culling of retained GPU regions using their actual DEM extrema and
coverage-aware eviction: preserve visible partial parents without real fallback;
evict redundant or off-screen data first. GPU smoke passes after these changes.
Native picking now retains a bounded CPU copy of the rendered height grid, uses
2D cell traversal and exact triangle intersections clipped to the same ownership
regions, avoiding GPU readback and full mesh scans. Eleven pure-core tests pass,
including600rays compared with exhaustive triangle search, exact edge hits,
missing-data rejection, clipped regions and triangle-vs-bilinear interpolation.
GPU draw/readback + center terrain pick also passes. Picking is wired as an
asynchronous native call; gesture anchoring/collision behavior still open.

Reproducible build script passed for optimized ARM64+ARMv7 debug APK:
395,702,587bytes,SHA269df0794365fa390af6b34ac41cb1d511df59576020e0768dbe8dc602cce6b6,
v2signature and16KiB ZIP alignment pass. This earlier artifact predates the latest
picking/visibility/original-ZIP changes; do not present it as the final build.

Added debug-only platform Instrumentation checking native frames, exact flat-field
terrain pick, all six ZIP reads, surface destroy/recreate, rotated activity and
zero retained native surfaces after finish. Latest Full x86_64 instrumentation APK
is installing from immutable /workspace/native-artifacts/Ridgeline-Native-Six-Zips-Proof-x86_64.apk.
No automated lifecycle result yet. Logs /tmp/ridge-native-original-zips-install.log.

User action: network status still revision1/allowedHosts[]. Sent a single async
follow-up asking user to publish the saved network draft. Continue independent
work; do not bypass policy or claim live LINZ validation. M1 audit marked complete;
M2 remains open until real terrain/source parity is demonstrated.

Exact next step: wait for current APK install while working independently, run
`python3 native-wgpu/tools/emulator-adb.py 'am instrument -w nz.ridgeline.nativeapp/.ProofInstrumentation'`,
inspect/fix failures; rebuild latest optimized ARM APK using new ZIP input and
verify all tests/package/signature/alignment. Keep feature migration and M2 gates
honest. Commit and push the separate native source branch for resumability.

## Automated proof passed; independent route/profile backend migration

Full x86_64 original-six-ZIP proof installed successfully. Platform Instrumentation
passes native frame/pick, all6ZIPrange decodes, surface destroy/recreate, rotation,
and remainingNativeSurfaces=0. Emulator log also shows VkDevice/VkInstance destruction.

Replaced fixed16ms Handler scheduling with display Choreographer callbacks on the
GPU owner Looper, retaining dirty-only rendering and two-upload budget. One immutable
CameraPose snapshot now feeds a frame/selection; generation checks discard obsolete
surface initialization, and resized surfaces request fresh viewport selection.
The complete automated proof passed AGAIN after this scheduling change.

Independent M4 backend work (not yet wired into app UI): NativeGeo matches100
reference route/profile cases (lengths, distance locators, samples, missing-height
statistics), including exact binary rounding boundaries. RouteEditor preserves
append/insert limits,50undo snapshots, selected-point restoration and one snapshot
per completed drag/cancel behavior. Native GPX handles current2MiB/2000point/NZ/
single-track semantics, namespace alternatives, escaping and full coordinate precision.

Added application-private SQLite route primitive: WAL/full-sync transactions,50
records, stable IDs, original creation ordering, metadata-only list reads and one
changed route write. Coordinates use lossless Float64 BLOBs (32,008bytes for2000
points), avoiding whole-library JSON serialization and unused route decoding.
Async autosave controller and native UI still need implementation; do not claim
route feature migration complete based on these primitives.

Host Android unit suite now12tests passes, including reference goldens, GPX,
editor and binary storage tests; lint/build pass. Extended ACTUAL Android
Instrumentation additionally passes exact Float64 SQLite save/load, edit/name
ordering,50-record transaction rollback, close/reopen persistence and native GPX
roundtrip. Saved result in validation/emulator-vsync-data-instrumentation.txt.
Current installed test artifact is /workspace/native-artifacts/Ridgeline-Native-Vsync-Data-Proof-x86_64.apk.

Native source branch was pushed successfully to origin/native-rust-wgpu atf3d828c
before this backend checkpoint. Original preview branch/artifacts are unchanged.
Network draft still pending user publication; async question remains open.

Exact next step: implement/test the I/O autosave/session controller and native
route overlay/UI against reference behavior while retaining explicit real-DEM/
visual gates. Confirm network status when user replies, fetch actual LINZ DEM and
same-camera reference captures, complete cross-LOD terrain/gesture parity. Rebuild
latest ARM release-equivalent debug APK and verify literal6ZIPs/signature/alignment
before final release. Current prototype remains feature-incomplete.

## Autosave session controller and stalled environment publishing

Added RouteStorage interface and serial background RouteController. Stable IDs
prevent duplicate new records when editing overtakes a save;400ms name debounce,
immediate geometry commits, no writes for drag previews, one undo per drag,
save-failure navigation gating, explicit retry, route open/unload/delete/rename/
combine and opened-route state are implemented as backend session operations.
Native UI binding remains the exact next feature step. Four concurrent/controller
regressions pass: editing during first save creates one latest row; failed save
blocks navigation and retry preserves edits; drag preview avoids disk writes;
deleting one route at the50-record limit retries the current unsaved route.
All16Android host unit tests and lint pass. Controller is not yet included in an
emulator integration run; preceding vsync/data proof remains the actual platform
result, not a claim about unimplemented UI.

Further current-Capacitor audit: build-web.mjs injects authoritative mobile route
open/unload/delete/rename/combine APIs (not just public/app.js). mobile.css reserves
map height for snapped native-equivalent sheets; mobile.js also includes page-size
region selection, topo-only downloads, PDF options/magnetic declination and saved
region sidebar. These are in full feature parity scope. Do not silently omit them.

User reported environment publishing has been loading for hours. Checked current
runtime and saved draft: running/current spec revision1, no failure, no custom
allowed hosts; draftrevision3 is saved with required7hosts. Diagnostic metadata
history_status=not_found/records=[], no publish job or restart control exposed.
Explained this is an unapplied publish, with no evidence of root cause; advised
reloading settings/retrying once and platform support if still hung. Continuing
independent work. Preserve source checkpoint before any environment restart.

Exact next step: push this tested checkpoint; bind RouteController to native
planner/sidebar/sheet/profile UI and terrain overlay interactions. Continue
remaining rendering/gesture/real-DEM parity gates when networking is available.
Do not claim the whole application, speedup or physical phone validation complete.

## Critical packaged-reference correction before UI migration

Found and corrected an audit/port error: website public/ includes Cook reference
substitutions, but Capacitor build-web.mjs deliberately removes their selector,
import and assets. Actual0.18Full APK confirms zero Cook entries/selector and
Direct=true. Native main imagery path and debug fixture now exclude Cook and use
original bundled South Island overview images. Earlier committed screenshots are
historical GPU mechanics evidence only, not accepted Android reference imagery.
Need replace captures with corrected fixture images before reporting parity.

Added audit-reference-apk.py against actual0.18Full APK (SHAb4d286f…): exact630
atlas entries,58coarse coordinates/67photo files, every coarse overlay image and
all6original payload hashes match. Native APK verifier now rejects Cook assets or
reference selectors. Corrected full x86_64 APK installed and automated GPU/pick/
sixZIP/lifecycle/SQLite/GPX proof PASSES again. Saved latest result and packaging
record under validation/. Source masks/goldens and all16host tests/build/lint pass.

Also narrowed fine-survey fallback to match packaged behavior: network transport
failure retains original imagery; HTTP failure or invalid bitmap propagates tile
failure so a ready parent remains visible. Previous catch-all was too permissive.

Performance architecture finding: prototype couples129mesh and image tiles and
selects from physical drawing pixels. Reference independently controls geometry
(default tileSize round(512/2^.25)=431logicalpx) and imagery (256tile plus .25bias),
with drawing ratio normally1.5, sharpening up to2 atzoom>=15. Native benchmark
same-camera: physical basis96tiles vs logical63; these are prototype counts, not
phone speedups. Reference terrain proxy without fetched DEM requests9z11 tiles at
411×731 before applying detail policy. Must decouple geometry/image residency and
source selection before accepting performance/LOD parity, rather than overbuild
meshes for high-resolution photos. Native render remains full-resolution for now;
no quality reduction is authorized as an assumed speed fix.

Lyon current official release1.0.23/README researched for future native vector
route overlays. No new dependency or route renderer committed yet. Preferred
future tile-only overlay RTT preserves draping and avoids copying base imagery;
prove it before entire UI migration. Route backend/controller is independent and
tested, not a claim that UI migration is done.

User publishing issue remains platform-side/unexplained: saved draft3 has not
applied to running revision1. Already gave reload/retry/support guidance; do not
repeat permission prompts or bypass policy. Continue independent work.

Exact next step: checkpoint this correction, then separate terrain/image pools,
plans and upload paths with GPU/readback/picking regression tests; keep legacy
combined upload API for existing proof fixture. Adapt tile loader to logical-pixel
independent plans and bound both budgets. Then native overlay/UI binding. Live DEM
and visual parity gate remains open pending network or genuine source fixtures.

### 2026-10-29 — Independent native geometry and image LOD checkpoint

Separated geometry/heightfield and full-resolution photo pools, upload methods,
source ownership and plans. Full original 256px imagery is unchanged; photo mips
remain GPU-generated. Geometry uses the reference default431logicalpx target,
photos256/2^.25logicalpx, independent ceilings22/19. Source selection no longer
uses Android drawing-density zoom; the actual framebuffer stays full resolution.
LOD error/frustum policy is still a prototype, not accepted terrain parity.

Intersections of two disjoint source covers produce unique drawable regions.
Subrectangle shared index ranges avoid drawing an entire128mesh for every photo.
The controlled GPU readback test proves four child photos correctly cropped on
one parent geometry with32768 triangles total; no terrain accuracy or phone FPS
claim follows from this synthetic fixture. Picking uses this same visible source
intersection and now rejects hits beyond the camera far plane.

Android loader schedules geometry and photo jobs independently across the same
3workers; DEM halos and HTTP dedup/cache remain. Native residency is mirrored to
avoid decoding/uploading already-resident sources. CPU ready data is byte-bounded;
queued uploads coalesce by source type and obey650KiB +4source/frame limits.
Fallback ancestors now count inside each source budget; demand coarsening retains
coverage under pressure. Replaced a cancel/reschedule debounce that could starve
loading during a continuous drag with latest-camera100ms throttling. Off-screen
retention and eviction transition behavior still require real-device movement
validation; obsolete in-flight shared HTTP requests are not yet demand-refcounted.

Commands/results: cargo fmt; cargo test --locked --workspace:17tests PASS;
softwareVulkan gpu_smoke PASS; cross-release builds forARM64,ARMv7,x86_64 PASS;
Gradle offline testDebugUnitTest/assembleDebug/lintDebug:19tests PASS,0lint errors,
13existing/prototype localization warnings. Exact commands are in README/build
script; this session uses android-tools toolchain directories from earlier log.
Current full x86 APK packaging passes:6exact originalZIPs/364762502payload bytes,
5277photo ranges, no web runtime and no website-only Cook substitutions. Packaging
report and host/GPU/selection results are saved under native-wgpu/validation/.
Raw leaf counts at411×731/defaultcamera:32geometry,79images, before ancestor
budgeting; old coupled physical selection hit96meshes. These counts/host timing
are architecture diagnostics only, not a measurement of faster phone behavior.

Emulator fixture now uses geometryz11 and independent photosz12, verifying both
source pools before accepting a frame. Installation/instrumentation result will
be appended after it completes; do not infer device validation from host tests.

Publishing issue rechecked: saved draftrevision3 remains unpublished against live
runtime/spec1. Diagnostics exposes no retained records and tools expose no
publish/restart operation. Cannot diagnose the platform failure or repair its
publishing job from here. Required provider hosts remain unavailable; no bypass.
The original app and committed native branch remain preserved.

Exact next step: finish independent-source Android instrumentation, replace old
Cook-fixture captures with corrected original-pack fixture captures, checkpoint
and push. Real DEM/imagery proof remains a blockingM2 acceptance requirement before
building the whole UI around this renderer. Once network publishing recovers,
validate live terrain seams/LOD/picking/colour, then prove draped native overlays
and bind the already-tested route controller to native UI. Later milestones stay
open in GOAL.md.

Independent-source emulator follow-up: frozen x86 APK32561d59… installed; Android
platform instrumentation PASSES with geometryz11/photosz12, exact center pick,
sixZIPreads, surface destruction/recreation, rotatedActivity, SQLite/GPX checks;
remainingNativeSurfaces=0. Result saved as independent-sources-android-instrumentation.txt.
Universal debug APK with ARM64/ARMv7/x86_64 also assembled; packaging audit,
APK v2 signature verification and zipalign16KiB pass. ARM code cross-built only,
not executed on a physical phone. Remote checkpointb959bae pushed successfully.

Corrected portrait capture now replaced with original-pack independent-source
fixture. It remains explicitly labelled synthetic flat terrain; no visual parity
inference. Prior Cook fixture capture is superseded, retained only in Git history.
Remaining pipeline tuning: drop obsolete queued uploads promptly on demand changes
and measure eviction/re-admission during gestures; hard count/byte budgets alone
do not prove stable memory or prevent all reloads. Do not callM5complete.
Corrected landscape capture replaced too; both reviewed to confirm original-pack
photos and explicit synthetic-fixture label. These demonstrate functioning drawing,
not seamless real-terrain/imagery-source transition acceptance. All tests and
native sources are checkpointed; awaiting usable provider access forM2realDEMproof.

## 2026-10-29 — Published environment, genuine LINZ host proof

Resumed exact ddee0d3 on native-rust-wgpu; refreshed authoritative download branch
and confirmed it still advertises 0.18.0/source d880143. Capacitor remains untouched.
New runtime has enforced unrestricted HTTP policy. Actual LINZ terrain-rgb PNG and
aerial WebP requests now succeed (HTTP200,256x256). No new key was requested.

Added reproducible fetch-live-proof.py (Pillow) and live_terrain Rust GPU example.
Fetched12genuine halo DEMs and2original aerial tiles at Aoraki z11. No fallback or
fabricated elevations. Source hashes/lengths are saved in validation/live-linz.
129shared mesh-edge samples agree exactly; heights span450.8–3671.2m. Actual native
softwareVulkan draw/readback passes and center terrain pick matches1087.000m.
Full256px input imagery retained. Saved top-down host capture is evidence of real
input execution only, not visual parity, Android loader parity or phone performance.

Restored Rust1.99.1/Cargo and locked host tests (17pass), JDK21 plus pinned Android
SDK/NDK under /workspace/android-tools. Extracted original6overview ZIPs from the
refreshed download branch to /workspace/native-reference; native Android build and
emulator toolchain restoration are in progress. No generated toolchain/APK committed.
M2/M3 remain open: cross-LOD stitching, oblique same-camera reference captures,
live Android loading and gesture/terrain collision acceptance must precede full UI.

## Source-demand upload pruning and rebuilt native APK

Queued uploads now adopt immutable geometry/image demand sets and release obsolete
queued ownership immediately on viewport-plan changes. Late in-flight results
cannot displace wanted uploads; re-admission remains allowed. This does not evict
resident fallback tiles or reduce source/drawing resolution. Added regression for
independent source kinds, late completion and re-admission. All20Android host
unit tests pass (none skipped); assembleDebug/lintDebug pass. All3native ABIs
cross-compiled optimized. APK signature/16KiB ZIP alignment and exact6originalZIP
packaging audit pass. Frozen artifact is native-wgpu/artifacts/
Ridgeline-Native-Live-Proof-Debug.apk (not committed); hashes in validation/live-linz.

Restored actual0.18Full reference APK through GitLFS (the Git blob is only a pointer)
and audited it: same b4d286f… SHA,630gap entries,67coarse photos,6original payloads,
no Cook substitution. Original Capacitor/android/public sources remain untouched.
System Java was a JRE without javac; replaced with full Temurin21.0.13, verified
against official Adoptium API checksum. Managed Java CA trust retained. Earlier
parallel Gradle clients were stopped; successful final run used writable caches,
fullJDK and max-workers2. Recreated toolchain/build files stay outside Git.

Added opt-in debug LiveProofInstrumentation exercising MainActivity/production
TileLoader and genuine source residency/picking (no synthetic fallback). Compiles
and lint passes; Android execution is still pending slow unaccelerated emulator
boot. Host oblique real-terrain capture saved separately. Neither this capture
nor packaging establishes same-camera reference parity or completedM2/M3.

## Reference-scaled boundary skirts and current Android execution blocker

Implemented lowered boundary skirts on selected native geometry subrectangles.
Surface samples and128-cell triangle grids are unchanged; imagery remains original
256px plus existing GPU mips. Skirt depth matches authoritative MapLibre6.14:
meanEarthCircumference /2^max(logicalZoom,0) /5. Added separate logicalZoom to the
Android render JNI call; framebuffer projection still uses full drawing density.
Skirt vertices duplicate the surface buffer (399384GPU bytes/source vs199692).
Existing source-count caps remain; combined device peak memory is not yet measured.
Picking continues to use genuine surface heights, not lowered curtains.

Host19Rust tests pass, including preserving every base index and exact boundary
connectivity; all20Android tests pass,0lint errors/16warnings. Controlled GPU
readback shows36092background pixels closed at an intentionally discontinuous
parent/fine boundary. Existing exact-grade/source-crop/mip/residency checks and
actual LINZ draw/1087mcenter pick still pass. This is a boundary-gap mechanism
proof, not accepted real-terrain visual parity or morphing/fades. All3optimized
ABIs rebuilt; frozen Skirt-Proof APK is signed/aligned with6unchanged originalZIPs.
Hash/report: validation/skirts/universal-apk.json. No APK/toolchain committed.

Esri imagery and S3Terrarium also returnHTTP200and decode256px (host requests).
Full build script reproduced successfully; added early fullJDK/javac preflight
and configurable default2Gradle workers. ADB helper now accepts a response timeout.

Current emulator cannot provide valid app execution evidence: initial software
boot showed system-level DeadSystemException events, and package/settings/activity
services disappear before app installation. No Ridgeline process ran in those
attempts. Rebooted retained AVD; unsupported ro.hw_timeout_multiplier boot override
was rejected. Android supported watchdog_timeout_millis setting was confirmed at
600000and boot_completed at1, but installer still lost package service. Failed
attempts do not mean the APK failed. No /dev/kvm is available; root cause beyond
these system-service observations remains unconfirmed. Do not retry unchanged
install commands or claim this session's emulator validation passed.

Exact next step: establish a stable Android runtime (or physical device), install
frozen latest Skirt-Proof APK, run ProofInstrumentation then LiveProofInstrumentation,
and compare genuine same-camera terrain/LOD/colour/fog/gesture captures to0.18Full.
Continue source-independent parity work if the runtime remains unavailable; full
native UI remains gated on critical renderer proof. Capacitor/android/public source
and original overview ZIPs remain untouched. Overall completion estimated≈25%,
withM1complete, M2partial, M3–M6open; never describe this as the finished app.

Emulator diagnostic follow-up: focused crash buffer reports Android system process
IllegalStateException "Lost network stack" during startup, plus unrelated Bluetooth
SIGABRT and DeadSystemException cascades. Android's own message says lost network
stack is a side effect and earlier logs are needed for root cause; do not claim
watchdog, networking policy or Ridgeline caused it. Package/activity services were
absent in subsequent checks despite stale boot_completed=1. No app process/install
success or instrumentation pass exists for this current session. Toolchain/build,
source hashes and hostGPU checks are independent successful evidence.

## Quality-preserving CPU/GPU work removal

Continued from7a3e659 on native-rust-wgpu. Removed repeated viewport selection on
accepted-data callbacks by caching one immutable combined geometry/photo demand
plan keyed by all camera fields and logical viewport dimensions. Residency and
missing-source scheduling are still reconciled on every callback, so an evicted
source or recreated surface can be refilled without changing camera. Failed plan
builds cannot publish a stale value under a new key. No LOD/detail policy changed.

DEM geometry now loads only source tiles touched by its exact pixel-centre mesh
footprint. A representative interior z20 mesh uses1z18DEM instead of9; standard
z18stilluses all9halo tiles, boundary z19uses4. Exact same-level integer samples
avoid a redundant bilinear read; true parent fallback retains original sampling.
Frozen checkpoint7a3e659 algorithm is the test oracle. All266256height samples in
16full-buffer comparisons match byte-for-byte across source/overscaled zooms,
world wrap/poles, parent fallback and signed zero. Initial test caught negative
zero behavior; the zero path retains original arithmetic, assertions unchanged.
These are source lookup/operation reductions, not measured device timing.

Duplicate pending immutable payloads no longer call requestFrame again. New
buffer ownership and re-admission after draining still work. GPU scene/instance
buffers are transferred only when their contents change; rendering itself still
occurs whenever requested. A data refinement still updates instance ownership;
unchanged camera uniforms are reused. Full framebuffer,128-cell surface/skirt
geometry,256px source imagery, grade/mips/filtering and budgets remain unchanged.
The extra retained instance snapshot is bounded by existing visible source cover.

Validation: all25Android tests pass, no skips/failures;19Rust host tests pass;
lint0errors/16warnings; all3optimizedABIs rebuilt. Existing GPU exact-grade,
parent/fine coverage, crop, mips,36092-pixel skirt-gap and residency regressions
pass, including pixel-identical repeated frames with zero additional uniform/
instance transfers. Genuine LINZ GPU draw/pick passes; oblique capture RGB bytes
exactly equal previous skirt checkpoint. APK signature/16KiB alignment and all6
originalZIP hashes pass. Report: validation/quality-work/checks.json. Frozen APK:
artifacts/Ridgeline-Native-Quality-Work-Debug.apk (generated, not committed).
No Capacitor/android/public source changes; no overview ZIP modifications.

No phone FPS or live-Android/visual acceptance claim: previous emulator system
service failure remains unresolved and this checkpoint does not change that
runtime. Next critical gate remains stable Android execution of controlled/live
instrumentation and genuine same-camera0.18reference comparisons before full UI.

## Photographic skirt removal, exact index pressure and adapter compatibility

Geometry cover now retains its ownership region across photographic subdivision.
Full image coverage shares outer geometry skirts; missing photos conservatively
keep all exposed walls. Independent full-resolution child photos no longer add
internal curtains to the same mesh. Genuine LINZ test: same32768surface triangles,
skirt triangles2048->1024. Topdown capture is byte-identical. Strict repeated
oblique comparison differs at1of65536pixels: oldwhite/newterrain color beside
internal boundaryv=.5; CPU terrain pick at that pixel givesv=.5000111955.
This difference is recorded explicitly in strict-pixel-comparison.json; the full
oblique equality assertion failed and was not relaxed into a passing parity claim.
No source resolution, height samples, mips or filtering were reduced. No0.18
visual parity claim follows from this native-before/after fixture.

Bounded index atlas now compacts stale entries under pressure; a frame that still
exceeds it uses exact transient index buffers, not the old broader-mesh fallback.
GPU regression forces24-byte atlas and requires pixel- and triangle-identical
mixed-LOD output. Existing36092-pixel boundary repair, exact grade/crop/filter/
residency checks pass. All22Rust tests and25Android tests pass, lint0errors.
All3optimized Android ABIs rebuilt; latest Compatible-Mips APK packaging audit,
6originalZIPs, signature and16KiB alignment pass. Artifacts remain outside Git.

A clean official Android30ATD image boots and installs the complete APK, avoiding
the previous35image's failing system services. Actual platform route SQLite/GPX
proof passes. Initial GPU proof times out: log confirms device creation requested
compute-workgroup limit65535while this adapter allows0. Source loading also reports
unavailable; it is not yet diagnosed as provider authorization or TLS failure.
Added debug-only exception-class logging without provider URLs/key values.

Renderer now requests the adapter's actual limits and selects render-pass mipmap
generation when compute is unavailable. Both paths use the exact same4texel mean,
retain original256pxlevel0and9levels, and hardware compute remains preferred where
available. Forced-render and compute host GPU suites pass; genuine oblique RGB
captures match between both mipmap backends. Latest APK rebuild/reinstall and
controlled/live instrumentation reruns are in progress; do not infer AndroidGPU
or live-source success yet. Results will be appended after commands finish.

## Android30 native presentation, genuine loading and mip-content diagnosis

Continued after7b35071. A clean API30ATD software emulator now supports installation
and actual platform checks. Native surface ownership was the next blocker:
initializing Vulkan and GLES together connected the same Android buffer queue
more than once. Single-backend attempts, an explicit Android display handle and
scoped surface-configuration validation now allow GLES presentation. Failed API
state drops before fallback; the working API is preferred on surface recreation.
No phone benchmark is implied by software-emulator results.

Controlled instrumentation passes native imagery/geometry pools, terrain pick,
six original ZIP reads, surface destruction/recreation and rotation, with zero
remaining native surfaces. Platform SQLite/GPX integration passes. Genuine
LiveProofInstrumentation passes production TileLoader source residency and actual
terrain pick170.14190780126034,-43.595099224916105,3688.406470926.
These tests do not require or establish cross-LOD/visual/gesture parity.

Provider transport was TLS trust, not authorization: the managed proxy public CA
verifies LINZ on the host, while the clean emulator lacked that anchor. Optional
RIDGELINE_DEBUG_CA_PEM now generates debug-only network security resources for
exactly LINZ basemaps, Esri imagery and S3, retaining TLS/hostname checks. No CA is
committed. Normal build has system anchors only; separate local test APK includes
the managed public CA. Audit confirms625assets/native-library entries identical
between profiles and no session anchor in the normal APK. Release is unaffected.

The first genuine Android capture had large grey gaps. Host GPU smoke alone did
not catch the cause: an opt-in device mip readback found original level0correct,
but all8derived levels fully transparent. Cached JPEG/WebP source bodies were all
opaque. A failing GLES adapter now switches to CPU-generated complete mip chains
only after an actual startup readback fails; the fallback must pass that same
readback or initialization fails. Healthy GPU paths remain GPU-generated. Original
256pxRGBA level0is untouched, all9levels/filtering/geometry retained. This is a
compatibility correction, not a claim of reduced CPU work or phone speedup.
Readback occurs on GLES initialization or explicit diagnostics, never each frame.

Latest Android MipmapProofInstrumentation passes every pixel/all9levels with
minimum alpha255. Controlled and live integration both pass again afterward.
Host Vulkan/GLES CPU fallback suites pass exact checker, grade/crop/coverage,
mixed-LOD skirts and forced24byteindexpressure. Genuine host CPU/GPU mip capture
comparison differs in3RGBbytes by at most1step; do not call it pixel-identical.
All22Rust tests and25Android tests pass; lint/build and all3optimizedABIs succeed.
Normal frozen artifact: artifacts/Ridgeline-Native-Validated-Mips-Debug.apk,
SHA2568ad58495bd9a40c4ba1c4decf714419a907f36e432963dc8c69e3fb20c7bdd7b.
Evidence in native-wgpu/validation/android30, including the original mip failure.

A fresh standalone real-view capture now shows continuous mountain imagery where
large grey triangular gaps appeared before. Before/after PNGs and capture scope
are saved in validation/android30. This is qualitative native-before/after evidence
with unsynchronized residency, not reference pixel parity. The successful
integration assertion is not a visual-hole regression test. Do not mark M2/M3
complete until genuine settled terrain/LOD captures and same-camera0.18comparison
are accepted. Full native UI remains incomplete and gated on renderer proof.
Capacitor/android/public and all6originalZIPs remain unchanged. Overall estimate
remains approximately25percent of the complete app. Next: finish real-view capture,
check remaining gaps, then exact reference camera/terrain/fog/gesture comparison.


Reference runtime follow-up: unchanged authoritative0.18FullAPK installed beside
native app (SHA matches published b4d286f5...). Its first launch timed out; WebView
inspection then confirmed complete document but no canvas or map engine. Exact
reload emitted SyntaxError:Unexpected reserved word,line1783. This API30image has
WebView83.0.4103.120. Splash/state/errors are recorded in validation/android30;
this is a reference-runtime compatibility blocker, not a native parity failure.
Do not patch the reference to silently bypass it. Next reference step is a modern
supported WebView/runtime or the exact packaged assets on supported Chromium,
then camera-matched captures with source/elevation settings recorded.

User explicitly requested a repository APK checkpoint before usage limits.
Normal complete-data/all3ABI debug build is now in native-wgpu/checkpoints using
GitLFS, with checksum and scope README. It contains no session trust anchor.
Do not replace it with the separately generated local managed-proxy test APK.


## Reproducible exact-APK reference capture on supported Chromium

Refreshed android-preview-download:still39431dad/0.18Full, source d880143.
The API30reference syntax error is exactly the packaged module's top-level await,
unsupported by WebView83. Used unchanged APK assets on Chromium154instead, with
explicitly reproduced local provider/range transport, system/proxy TLS verification
and genuine original inputs. New tools/capture-reference.py checks the full APK
hash, records served-body hashes and rejects unsettled/error runs. Production
reference source/shaders and all quality settings stay unchanged. No Android
reference success or full parity is inferred from this host result.

Settled320x640camera:170.14219572,-43.5955671,zoom11.9,bearing-24,pitch58,
pixelRatio1; loaded/tilesReadytrue, no script or provider failures. Reference
clamps center elevation to3648.731912109524m. Native prototype still held3724m,
a concrete camera discrepancy to resolve before pixel comparison. Canvas/fullUI
captures and182body hashes are in validation/reference-host. Ground-centering
correction and stronger live frame assertions are being implemented separately;
this checkpoint only establishes the supported-host authoritative reference.

Removed8superseded generated local APK copies to recover3.2GB. Latest normal,
local trust test, committed checkpoint, authoritative reference APK, all6literal
originalZIPs, source data and evidence remain intact.


## Native center grounding and exact reference DEM cover

Native TileLoader now queries the original pixel-centre DEM at the camera center
once the finest demanded center geometry is resident. Available decoded sources
only; no new downloads or synthetic plane/zero fallback. Missing halo retains the
previous camera height. Posted updates compare the entire immutable camera pose,
so movement invalidates stale asynchronous results. Unchanged heights mark the
grounding state without calling changed/requestFrame or rebuilding a plan. The
center geometry is cached with the existing demand plan. Actual source pixels
use direct reads; parent fallback preserves the original interpolation.

TerrainView records the presented camera pose; strengthened live integration now
requires a grounded frame before picking. Mesh/imagery resolution, all height
buffers, nine mip levels, source ceilings and original ZIPs remain untouched.
All28Android tests pass, including analytic four-tile seams, wrap/poles, missing
halos, real parent fallback and four genuine LINZ source pixels matching the
captured reference center within.001m(Float32quantization only). Lint/build,
normal APK signature/16KiB alignment and all6originalZIP hashes pass. All3ABIs
use the unchanged optimized native libraries. Normal/local-test625asset/library
entries match; the normal APK contains no managed-session CA. Audit and sampler
provenance are in validation/ground-camera. New Android live verification is
pending: earlier emulator exited; cloud environment restart interrupted its boot.
Previous checkpoint's Android passes remain valid only for that checkpoint.

The reference capture now exports actual rendered terrain cover, not just source
requests. Default view has12geometry tiles: center geometry12/3983/2600uses
DEM11/1991/1300. Exact engine TerrainSourceCache has deltaZoom=1 and maps geometry
zoom to source zoom minus1, capped at source maxzoom. Merely fetching z12DEM does
not mean that tile is the active center source. Four z11pixels give reference
3648.731912109524m; the available z12source gives3672.6977629647536m. Native source
mapping/LOD still differs; do not force a captured height, lower a source ceiling,
or claim full camera/terrain parity from the interpolation unit test. Next: finish
new grounded Android proof, then port/verify exact geometry-to-DEM ownership and
LOD policy with genuine multi-zoom captures before opening full UI migration.


## User-requested smaller APK checkpoint

Built normal Base APK from a585548 using existing RIDGELINE_BUNDLE_OVERVIEWS=0,
without the six large overview packs. This changes bundled availability only:
67coarse images remain; full256pxnetwork imagery, DEM detail, source ceilings,
all3optimizednativeABIs and camera code match the latest normal Full build.
Empty archive index makes NativeImagery use the existing network path. Signature,
pack absence and native-library equality checked once; no unrelated test reruns.
Normal trust only, no session CA. APK/checksum are in native-wgpu/checkpoints;
previous Full checkpoint and all6original source ZIPs remain intact. User asked
to focus on implementation with fewer repeated checks. Continuing terrain source
ownership correction; latest Android grounding execution still pending.

Base packaging follow-up: incremental Android packager retained deleted Full ZIP
bodies as unreferenced bytes. Recreated only packageDebug state/output, producing
a genuinely smaller Base archive; signature and actual file-size bound checked.
Source/code/native libraries unchanged; no broad test rerun.


## Reference geometry-to-DEM ownership correction

Production terrain now implements exact0.18TerrainSourceCache deltaZoom=1:
sourceZoom=clamp(geometryZoom-1,0,18). Geometry and camera ground queries use the
same source mapping. Original256pxDEM inputs,129x129vertices, pixel-center halo,
same-source nodata parents and source ceiling18 are retained. This removes the
incorrect extra DEM source level relative to the reference; it does not globally
cap terrain detail or change imagery. The generic old mesh sampler remains for
unchanged bitwise compatibility regressions, separate from production ownership.

Ran only6affected DemSampling tests and assembled the Base APK. Analytic full-buffer
cases verify every vertex with parent source coordinates, adjacent geometry,
source ceiling and overscaled geometry. Original frozen sampling regressions and
captured genuine reference ground-pixel case still pass. No full Rust/emulator/
lint cycle repeated for this data-policy correction. Android visual execution of
the new policy remains pending; no new full-parity or phone-performance claim.

Saved the existing selection diagnostic left uncommitted by the environment
interruption. At320x640with explicit48/96budgets and reference camera height,
heuristic native center geometry isz13versus referencez12. Ownership correction
is complete, but exact LOD cover selection/transitions remain a separate blocker;
do not tune down a target just to make one captured view match.


## Native map navigation controls and attribution

Added native Android zoom-in/out and north-up controls, plus LINZ imagery/elevation
and Esri gap attribution links. These are minimal map controls for the renderer
prototype, not the full planner UI. Pinch, double-tap and buttons share one bounded
zoom update; unchanged zoom or an already-north bearing does not schedule a frame
or source replan. Rendering resolution is unchanged behind native overlays.
Compiled/assembled once; no new test suite for these reversible widget bindings.
Build wrapper now recreates only APK packaging state to prevent stale Full payload
bytes in future Base outputs, retaining compile/dependency caches. Next acceptance
work remains exact LOD cover and camera/transition behavior, followed by full
native feature UI. No new Android runtime/visual-parity claim.

Latest normal Base download now includes source4c0b942(parent DEM fix plus native
map controls). Stable path:native-wgpu/checkpoints/Ridgeline-Native-Base.apk;
checksum/build-scopeJSON adjacent. Six original ZIPs remain separate and intact.


## Priority change and native planner integration

User now explicitly prioritizes performance/full visual and data quality over exact
0.18pixel matching, authorizes continued implementation to completion, and wants
fewer repeated checks. GOAL.md records this; exact reference matching no longer
blocks feature UI. Existing Capacitor/olderAndroid/public and6originalZIPs remain
untouched; Base APKs remain the requested normal checkpoint format.

Connected existing native route controller/SQLite/GPX backend to native planner:
create/open/rename/delete, autosave/status, undo, point deletion, insertion near
segments, long-press waypoint dragging with coalesced terrain picks, multiple
shown routes, combine, fit, SAF GPX import/export and elevation profile. Profile
jobs are debounced/cancelled, use real DEMs, leave missing data incomplete, and
cache the chart path; no chart redraw loop on camera movement. Metadata-only lists
still avoid decoding unused routes. Close flushes queued route edits before DB
close; file/storage/network work stays off UI thread.

New Rust/wgpu route overlay follows crossed heightfield cells and actual triangle
diagonals, depth tests lines/waypoints against terrain, and rebases around one
route origin. Geometry is retained across idle/camera motion; only camera uniforms
change. Geometry/source refinement or route changes rebuild it. No GPU depth
readback or terrain/imagery resolution reduction. Active waypoint projection is
CPU matrix math for picking UI; drag requests coalesce and preview never saves.

Focused checks: route/controller/GPX tests and Kotlin assembly pass; exact draping
invariant and route GPU fixture pass (1024affected pixels; no geometry uploads on
idle/pan), plus existing GPU smoke on the changed pipeline. All3Android JNI ABIs
are rebuilding; Android UI/runtime execution is still pending. No finished-app,
phone-speedup or complete feature-acceptance claim at this source checkpoint.

## Restored feature workspace checkpoint

Preserved the interrupted native offline-region store/downloader, one-shot location,
camera preferences/perspective pan and planner integration fixture. Kotlin assembly
and focused camera checks passed before interruption; the final small profile/cache
edits still need compilation. All three route-enabled JNI ABIs were built. Actual
Android planner UI opened, but provider requests timed out and the grounded route
fixture did not complete. No new Android terrain acceptance or phone-speed claim.
Next user priority is MapLibre-like anchored gestures, stable view/loading and HLOD.

## MapLibre-style anchored gestures and demand-owned HLOD

Read current MapLibre GL JS/native gesture, terrain-anchor tests, inertia, raster
fades and tile-pyramid sources, plus Cesium screen-space-error traversal. Exact
revisions/links and applied/open behaviors: native-wgpu/research/MAPLIBRE-GESTURES-HLOD.md.
Replaced incremental centre-plane pan with one geographic terrain anchor per
pan/pinch/rotate/tilt gesture. Camera moves opposite finger; map content follows.
A single rendered-terrain pick fixes anchor elevation, with fixed center-plane
fallback when missing. Center elevation is held through gesture and eased after
release. Pointer count changes rebase, async callbacks reject stale gestures.

Separated GPU cache residency from requested render ownership. Ready ideal sources
supersede cached descendants; missing ideals keep real loaded parent/children.
This fixes history-dependent LOD ownership. Detail leaves no longer get collapsed
merely to pay for full ancestor chains. DEM/image workers have independent bounded
slots, queued priorities refresh, source failures get bounded retries, and demand
updates run during dragging at most every50ms. Same source ceilings/full imagery,
mesh resolution/filtering and upload budgets; original6ZIPs and Capacitor untouched.

Focused evidence:3camera tests(including32pitch/bearing/density combinations and
pinch),3source-demand tests,2newcoverownership tests pass. GPU readback proves
repeated coarse/fine/coarse demand produces identical coarse pixels; missing
siblings retain parent pixels, and settled frames repeat no scene/instance/route
uploads. One actual Android30GLES controlled diagonal pitched drag passes with
0.023479336975551036m anchor drift. Test is explicitly synthetic/offline and needs
no imagery packs or provider CA. All3optimizedJNI ABIs compile. No new phone
benchmark or real-terrain visual acceptance. HLOD geomorph/image fades, inertia,
collision and full terrain-aware cover remain open; this addresses concrete input,
height-snap, cache-ownership and loading-starvation problems without claiming all
renderer behavior complete.


User highlighted closest-zoom detail again. Reproduced geometry selection stuck at
z16 at camera19 even with a level camera: identical projected depths gave distant
conservative mountain bounds the same split priority as the focus. Read MapLibre
Native's distanceLOD/minimum center-radius and JS variable-zoom cover. Replaced
score with tile-bound distance/pitch-area metric; prioritize center branch and
one-tile focus neighborhood. Camera still4..19 (same reference), sources still
DEM18/photo19/geometry22. Zoom19 now requests centergeometry20/21(fullDEM18) and
photo19 at0/45/58/75/80pitch within existing36/72leafbudgets. Closest detail download
now precedes intermediate fallback chains. Selection regression covers maximum
source detail plus bounded non-overlapping coverage. No new GPU shader or mesh/
image downsampling. Actual provider availability and phone visuals remain separate.
The native Android immediate-release drag case also passes(0.0951m drift), so a
quick gesture cannot disappear while its asynchronous start pick is pending.

Real provider check at default Aoraki focus also succeeds: LINZ aerial z19 and
terrain-RGB DEM z18 both returnHTTP200, original256x256images; DEMalpha255throughout.
Provider tile coordinates/body hashes are recorded without URLs/client key in
validation/gestures-loading/providers.json. This establishes available real highest
source inputs at this focus, not new Android live-terrain or visual acceptance.
Fallback spare slots now favor broad coverage plus nearest parents rather than
spending the pool on all intermediate levels; closest parent and fine leaf start
promptly. Three focused source-demand tests pass after this ordering change.


## MapLibre6.14camera and shared-grid checkpoint

User confirms customRust/wgpu architecture and asks close0.18behavior, fewer broad
checks, online/upstreamcode research and executive questions. User chose0.18camera
feel and1GBadaptiveautomaticcache (separate savedregions/packs). Exact6.14.0
handler_inertia.ts, camera.ts, two_fingers_touch.ts and terrain.ts read fromupstream;
0.18bundledprovenance confirms6.14.0. Mainreference refreshed unchanged.

Added bounded60ms/160msreleasevelocityhistory, referencepan/zoom/bearing/pitch
speed/deceleration/linearity, interruptibleeasing, smooth300mszoom/north/doubletap,
terrainanchorheldthroughmomentum. Corrected twofingerrotation/tiltsigns; deliberate
parallelfingerpitch(-0.5degrees/logicalpixel),pinch0.1activation and25pxrotation
threshold nowfollowreference. Threefocusedmotion/gesturetests andKotlincompilepass.
Pinch-releasecoastacrosspointercountchanges still needsfinalintegration.

Terrainnowsharesonefull129x129surface+skirtUVgrid, withoneR32Floatheighttexture
pertile instead ofduplicatepositionbuffers. Alloriginalf32heightbits, diagonals,
triangles,skirts,CPUterrainqueries,independentimagery/mips retained. RawGPUterrain
payload per tile falls399384->66564bytes, plusone266256bytegrid perrenderer;
this ispayload accounting, notphoneFPS. GPU fixturepasses Vulkan andGLES, including
mixedLODskirts,detailownership,filtering andboundedcache. ExistingcontrolledAndroid
inputfixture updated toholdbeforerelease so it stillisolatesanchoringfrommomentum;
notrerunatthischeckpoint. MapLibreBSDnotice includedinsource andassetpackaging.


## Cache retention, terrain camera safety and performance decisions

User reaffirmed maximumperformance over copyingMapLibrestructure and chose immediate
imageryreplacement (nofades). Directshader keepsoneimagerysample, nofadeframe-loop
orintermediatedrape targets. Userchose1GBautomaticdiskcache, adaptivelylimitedby
free space; explicitoffline regions andall6originalpacks excludedfromeviction.
HTTPsourcebodyrequests nowdeduplicateacross imagery/DEM/profile/downloadworkers,
retainingHTTPexpiration/conditionalrequests andseparateoffline-onlypolicy. Shared
responses stilljoin eachsavedregion; originalproviderexceptiontypes preserved.
CPUreadycacheevictsnonwantedentries first;GPUcacheLRUusesactualrendereduse instead
ofuploadage. Viewselectionstopswhilebackgrounded andresumesfromlatestcamera.

Camera safety follows exact6.14eye+3x3nearclip sampling againstresidentrealheight
fields, withoutGPUreadback. It lifts a buriedcamera beforedraw andcorrects only
currentcamera revisions. Centre grounding uses theactualrenderedtrianglefield
only once finestrequestedcentresourceisready, soevicting decodedDEMcache cannot
leaveheightstale. Safetyquerycacheinvalidates oncamera/viewport/demand/geometry
changes; imageryarrival/routeupdates donotrecomputeunchangednearplanechecks.
Pinch/rotate/tilt releasecoast now survivesquicktwo-to-one-to-zeropointerrelease,
withoutaddingcentroidjumps toonefingerdrag. Normalzoom4..19,terrainceilings,
originalmips/filtering/fullsurfacesamples retained. DedicatedGPUclearance/payload
checks pass; Kotlinintegrationcompiles. No newAndroidruntimeorphonebenchmarkclaim.

LatestnormalBaseAPKbuiltfrom40d2788withall3optimizedABIs, fullheight/imagequality,
newsharedgrid/clearance/cache/camera code, packagedMapLibreBSDnotice andno6large
packs/sessionCA. Signaturepasses. Size31559809bytes; SHA256
301488d08668ea2375263598ce44fb95678e74f4e64e135c9c912bc545208d67.
Scope/evidence in validation/maplibre-behavior andresearch/MAPLIBRE-614-ADAPTATION.md.
No fullemulator/lint/testloops repeated; no phoneperformance claim. Preserve
performance-first choices whencontinuing: matchbehavior, avoidunusedMapLibre
infrastructure; improve actualterrain-awareselection/idlecamera re-solve only
wherevisiblebenefit orprofilingjustifiescost. ExistingCapacitorapp untouched.


## In-place overview ZIP attachments

Userrequested Addimagery inthenextAPK, choseattach-existingfiles withoutcopies,
anddelegatedadaptive/performancepolicy. NativeOffline/imagerymenu nowhas Addimagery
ZIPs usingAndroidSAFmulti-select,persistedreadaccess,backgroundwholeZIPhash+size
validationagainstthe6originalmanifest,payloadheadervalidation,anddirectpositional
readsfromoneSTORED.binentry. Noextraction,resampling,duplicatepackstorage orfile
modification. Attachedpacks surviveapprestartandbypassnetwork forcatalogtiles;
unavailable/revokedattachments useexistingnetwork/bundledsourcefallback. Detach
releasesaccess/handleswithoutdeletingphonefiles. Coarse67bundledimages andoriginal
sixpacks preserved. Assetpackaging includesoriginalarchiveidentitymanifest and
ignoresgeneratedlicense/manifestassets. Kotlincompilepasses; nativefilepicker/
persistedURIexecution stillneedsAndroidruntimeacceptance. Nextbuildmustincludethis.

## Adaptive terrain, GPU residency and submission reuse

Read exact0.18adaptive source, MARTINI RTINcode andCDLODpaper/sourceoverview.
Chose0.18bounded32/64/128indexpolicy onexisting sharedgrid: .6drawing-buffer-pixel
entry/.9retention,10/13pixelcellbounds,350msstablecoarsening,immediaterefinement.
Originalf32heights, fineboundaryvertices andskirts retained; invalid/unknown/near-
plane metadata keepsfull128. Sourceworkercomputesresidualbounds once; readybuffers
carry16bytesofmetadata. No perframeanalysis ornewDEMfetch. Indexvariants sharethe
existing boundedatlas; oneidlewakeallowscoarsening without blockingimmediateinput.
Route-bearing sourceskeepfull128for exactdepthwiththenativeroute/markerpass.

AndroidGPUterrainretentionis3xprevioussourcecount, capped192, usinglessrawgeometry
payloadthantheoldduplicatedvertexlayout. Warmrevisitsreuseheighttextures,CPUfields
andadaptive metadata. Memory-pressurecallbacksreleaseoff-demand caches, preserve
activecoverage anddo notdiscardcachemerelybecauseUIhidden. Sharedgrid/instance
buffersbindonce/pass; texturebindingsskipduplicates, compatibleadjacentpatches
batch; perframevisible/instance/adaptivescratchstorage reused. Nativegeometryupload
movesitsheightvectorintotheretainedfieldinstead ofcloning it.

Twofocusedcorechecks pass. GPUplanefixture32768->8952surfacetriangleswithidentical
pixels; routegeometryrestoresfull128. VulkanandGLES executechangedterrainpath;
cachepressurefixturekeepsdemandedterrain. No phoneFPSclaim. In-placeattachment
pickercompiles andall6originalZIPheadersmatchnativeoffsetreader. NextnormalBase
APKmustincludeAddimagery plus thesechanges; archivesremainexternal/user-owned.


Further exactworkremoval: terrainindices nowUint16(all33282possibleverticesfit),
halvingindexstorage/fetchbytes withunchangedtopology andsameboundedindexcapacity.
Vulkan/GLES GPUfixtures passafterformat/instancing/scratchchanges. No terrainheight,
imagefiltering,mips,sourceceilings ororiginalZIPdatareduced. Sourcevectorownership
andbinding/scratchreuse are exactworkremoval; adaptivity remains the separately
bounded0.18visualpolicy withroutegeometryprotected. Attachmentreaderdetach race
nowrejectsstalepackhandles. Proceeding tobuildnormalBasewithAddimagery included.

LatestnormalBaseAPKfrom22858d0includes Addimagery/in-placeZIPattachments plusadaptive,
warmterraincache, pressurehandling, submission/scratchreuse and16bitindices.
Size31614320bytes,SHA256
0c96a6896546e6b5c588fa8f5bef361ac7a649b2ad48af009d5f2a0d17e3cac4.
All3optimizedJNIABIs, Kotlin/assembly/signature pass. NativeZIPidentitymanifest
included; no largepacks orsessionCA. Scope in validation/attach-adaptive;
phoneSAFpersistedaccess andphysicalFPSstillneedexecution. Do notclaimprototype
featurecomplete or inferdeviceFPSfromtrianglecounts. OriginalZIPs/Capacitorintact.

## Samsung A20 terrain stability and recovery

User clarified two-finger tilt. Removed automatic height-follow animation;
terrain pivot recentering retains physical eye/pitch, render-only rest clearance
avoids repeated state drift, and exclusive pitch pivots around map centre like
MapLibre. Gesture elevation keeps a baseline with temporary collision lift.
Planner uses the actual presented pose. Full upstream max-zoom recenter search
is not yet ported; physical interaction acceptance remains open.

Bounded background parent coverage now precedes most fine leaves so terrain
behind a peak is not held behind foreground refinement. Same source ceilings.
Uploads bounded to1/gesture or2/idle frame and paused during failed presentation.
Temporary timeout/occlusion retries no longer stop after8failures; lost surfaces
recreate, repeated hard/device errors rebuild renderer and reupload cached data.
No A20 root-cause reproduction or measured FPS claim.

Remove skirts only on proven continuous adjoining edges; retain exposed/mismatched
joins to prevent cracks. Masks update on cover/geometry changes; unused fallback
uploads/evictions no longer always retessellate routes. Vulkan/GLES fixtures pass,
including 16->8 skirt edges in flat fixture and retained discontinuity protection.
CameraMath5 and SourceDemand4 focused tests pass; Android native target compiles.
See research/TERRAIN-STABILITY-AND-RECOVERY.md and validation/terrain-stability.
Original six ZIPs, full source quality, Add imagery and Capacitor app preserved.

Latest normal Base APK from c602395: 31684240 bytes, SHA256
18e11cf44922da81a4dfa4b5aafff6bbb4af91e6113ff1f1bc7439e785d9dea1. All three optimized JNI ABIs, assembly and signature pass.
No packs/session CA; Add imagery retained. A20 runtime acceptance remains open.

## Prepared cache, coordinated residency and cost-aware scheduling

Implemented all four requested improvements. Prepared terrain uses bounded,
lossless versioned disk entries with full halo/parent/404provenance, validated
through normal HTTP/offline source access. Fast gzip writes use one background
worker with8queued entries; interrupted/corrupt writes recover. Allocates1/8of
existing automatic disk budget, at most128MiB, subtracting from HTTP cache.

GPU photographs may borrow unused terrain payload space, retaining up to25%extra
photos under the previous combined target. Active coverage protected; explicit
count caps bound temporary protected-coverage debt. GPU/CPU caches still release
off-demand data under real memory pressure. No quality/source-policy changes.

Obsolete tasks skip later image decoding/composition/RGBA work, halo work and
terrain rows, without canceling shared source I/O or poisoning DEM futures.
Aborted tasks have no failure backoff and reschedule if demand returns in flight.
Uploads learn actual JNI cost by kind and available render CPU time, keep byte/
count limits, and gate new uploads/frames at2outstanding GPU submissions using
completion callbacks and nonblocking polling. Latest camera retries nextvsync.

Real-input host measurements and reproducible fixtures in validation/pipeline-cache:
prepared-hit1.138ms vs cold-read/decode/mesh12.485ms; warm decoded mesh1.116ms,
so no gain over decoded RAM. Obsolete mesh0.109ms vs1.116ms full.36-photo revisit
4->0reuploads (40->36total),10%less upload/mip work. Vulkan36.979->35.995ms;
GLES40.113->36.826ms medians across6alternating trials. Timing noisy; work counts
stronger evidence. Pressure fixture bounds queued submissions12->2Vulkan/6->2GLES,
identical final per-backend pixels; GLES pressure throughput is slower, documented.
Light upload-cost replay shows no CPU improvement; no A20/FPSclaim.16focusedtests
pass; GPU coverage/quality fixture, all3optimizedABIs, assembly/signature pass.
Full imagery/heights/filters/mips, Add imagery, original6ZIPs andCapacitor intact.

Latest normal Base APK from 0e2d7ee: 31919402 bytes, SHA256
474ed2be9fa8157d45132a0b3ff851b577504594b26725137e92f3975f687c55. All3optimizedABIs/signature/assembly pass; no packs orsessionCA.
Measurement scope and reproducible original-data fixture in validation/pipeline-cache.

## Published0.18cache/pipeline comparison audit

Refreshed android-preview-download39431da andresolved d880143 usinglatestREADME,
validation/checksum. FullAPKhashmatches. Readexactsource andgeneratedAPKmodules;
app-onlyoptimise-web transforms matter. Detailed gaps/recommendations andasset
provenance in research/CACHE-018-COMPARISON.md andCACHE-018-ASSETS.json.

Highestpriority: local/prepared/disk hits mustnotqueuebehindtwo blockingHTTP
workers; metadatafreshness shouldavoidbodyfetch/hash on everypreparedhit;
subscriber-awaretransfercancel/promotion plusboundedquick-returngrace; restore
residentcoverage/sharperchildren/coarsereserve beforemorefallbackrequests;
boundedencodedRAM/compositionreuse; epoch-invalidatedsaveddatamissmemo;
smallpressure-awareprediction; exactmembership/planindexreuse; per-tilephone
traces.0.18alreadyimplements manyofthese. Nativecurrentlyhaspartialdedup,
parentfallback,residency andcostadmission, notfull0.18cachearchitecture.

Do notcopyremovedSHAdecoded-imagepool, duplicateWebViewelevationdiskcache,
bridge/RTT-specificwrappers oroldmemoryvalues. OptionalexpandedGPUpreparation
wasoffbydefault. No production code/APKchanged; no newperformanceclaim.
