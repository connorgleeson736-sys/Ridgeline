# Native Android checkpoints — incomplete prototypes

Latest: **[Download 0.1.39 Hold To Paint Base APK — 33.8 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Hold-To-Paint-0.1.39-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 40:
**updates 0.1.13–0.1.38 in place, keeping its data.**

- **Hold to paint:** the map moves normally; press and hold, then drag, to
  paint. A note at the top of the map says so.
- **Regions up to 30,000 tiles** (was 2,000), paced to stay under LINZ's
  limits and pausing if the server says "too many requests".
- **The region frame shows again** (it was hidden under the map in 0.1.37–0.1.38).
- **Saved regions** with an overview image, Rename and Delete are in the
  routes drawer; the offline bar is simpler.

209 JVM tests pass; lint 0 errors; native libraries and map assets identical
to 0.1.38. Not run on a phone.
[Exact release record](../validation/hold-to-paint-2026-11-12/release.json) ·
[SHA-256](Ridgeline-Native-Hold-To-Paint-0.1.39-Base.apk.sha256).

## Previous 0.1.38 paint switch checkpoint

**[Download 0.1.38 Paint Switch Base APK — 33.8 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Paint-Switch-0.1.38-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 39:
**updates 0.1.13–0.1.37 in place, keeping its data.**

- **Move / Paint switch:** the map moves normally until you choose Paint (or
  a colour); two fingers always move the map.
- **Region tab** is the 0.1.36 region download again (map, detail, overlays,
  coordinates, PDF and Download).
- **Sharp PDFs** at about 200 dpi, built from the map's own tiles.
- **Show stored map detail** turns on quickly (a saved index instead of
  reading every cache file).

209 JVM tests pass; lint 0 errors; native libraries and map assets identical
to 0.1.37. Not run on a phone.
[Exact release record](../validation/paint-switch-2026-11-12/release.json) ·
[SHA-256](Ridgeline-Native-Paint-Switch-0.1.38-Base.apk.sha256).

## Previous 0.1.37 paint offline checkpoint

**[Download 0.1.37 Paint Offline Base APK — 33.8 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Paint-Offline-0.1.37-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 38:
**updates 0.1.13–0.1.36 in place, keeping its data.**

- **Paint what you want offline** onto the 2D or 3D map: one finger paints,
  two fingers move. Four detail colours (Overview, Standard, Detailed,
  Maximum) and an eraser; the brush is fixed on screen.
- **Undo, Clear, Along route** (a 1 km corridor along the open or a saved
  route), and a live size estimate. Areas already on the phone show faintly.
- **Topo map tab** keeps the page frame for NZ Topo regions and Save as PDF.

205 JVM tests pass (6 new for painting); lint 0 errors; native libraries and
map assets identical to 0.1.36. Not run on a phone.
[Exact release record](../validation/paint-offline-2026-11-12/release.json) ·
[SHA-256](Ridgeline-Native-Paint-Offline-0.1.37-Base.apk.sha256).

## Previous 0.1.36 offline bar checkpoint

**[Download 0.1.36 Offline Bar Base APK — 33.8 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Offline-Bar-0.1.36-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 37:
**updates 0.1.13–0.1.35 in place, keeping its data.**

- **Offline regions in one bottom bar:** frame, page size (or Custom), map,
  detail, live size estimate, **Save as PDF** and **Download**. Saved regions
  and Advanced fold away. GPU textures and loading diagnostics moved to
  Layers > Advanced.
- **Names clear when zoomed far out** (they could stay on screen in 0.1.35).
- **Route sheet:** drag it by the route name or stats, not only the grip;
  stats line up with the buttons below.

199 JVM tests and 51 Rust tests pass; overlay GPU check passes on Vulkan and
GLES; lint 0 errors; map assets identical to 0.1.35. Not run on a phone.
[Exact release record](../validation/offline-bar-2026-11-11/release.json) ·
[SHA-256](Ridgeline-Native-Offline-Bar-0.1.36-Base.apk.sha256).

## Previous 0.1.35 region sheet checkpoint

**[Download 0.1.35 Region Sheet Base APK — 33.8 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Region-Sheet-0.1.35-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 36:
**updates 0.1.13–0.1.34 in place, keeping its data.**

- **NZ Topo** switches to finer tiles one zoom level later, so the printed map
  stays at a readable scale longer. Full detail is still reached at zoom 16.
- **Hut names (and other new names) appear while moving.** They used to wait
  until the map stopped when they made the name atlas grow. Names also no
  longer blink out when the atlas grows, or fade in again when a finer tile
  replaces a coarser one.
- **Compact route bar** is smaller (116 dp, was 136 dp).
- **Choose region** has a **Custom** size: drag any corner freely. The panel
  shows the area in km.
- **Download region** is now a bottom panel like the route bar.
  **Included imagery** is under **Advanced**, with the region coordinates.

197 JVM tests and 51 Rust tests pass; overlay GPU check passes on Vulkan and
GLES; lint 0 errors; map assets identical to 0.1.34. Not run on a phone.
[Exact release record](../validation/region-sheet-2026-11-10/release.json) ·
[SHA-256](Ridgeline-Native-Region-Sheet-0.1.35-Base.apk.sha256).

## Previous 0.1.34 disk first checkpoint

**[Download 0.1.34 Disk First Base APK — 33.8 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Disk-First-0.1.34-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 35:
**updates 0.1.13–0.1.33 in place, keeping its data.**

- **Stored tiles stay on the phone.** One slow disk read used to send every
  waiting tile to the network and skip the disk for 30 s (362 escapes vs 166
  disk hits in your diagnostics). Now only the late tile also downloads, and
  its disk read can still win.
- **Overlay tiles decoded once** (260 decodes for 39 tiles before); recent zoom
  bands are reused when pinching back and forth; overlay work no longer runs
  at background priority.
- **Finished work is kept:** cache writes are bounded by size, not 8 jobs, and
  the prepared-terrain cache gets 40 ms instead of 12 ms.
- **Smoother frames under load:** the render thread runs above tile decoding.
- **Dark mode or split screen** no longer reloads the map.
- **Bug fixes:** GPU errors are recorded instead of closing the app, and
  several tile lookups can no longer crash it; a refused overlay update backs
  off instead of retrying every frame; resuming an offline region keeps its
  own overlay choice; the phantom "oldest HTTP call" is gone.

196 JVM tests and 51 Rust tests pass; overlay GPU check passes on Vulkan and
GLES; lint 0 errors; map assets identical to 0.1.33. Not run on a phone, and
no speed-up has been measured.
[Exact release record](../validation/disk-first-2026-11-10/release.json) ·
[SHA-256](Ridgeline-Native-Disk-First-0.1.34-Base.apk.sha256).

## Previous 0.1.33 empty tiles checkpoint

**[Download 0.1.33 Empty Tiles Base APK — 33.7 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Empty-Tiles-0.1.33-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 34:
**updates 0.1.13–0.1.32 in place, keeping its data.**

- **Overlays fixed: root cause found from loading diagnostics** (accepted 0,
  rejected 844). Since 0.1.27 a tile with no lines was sent in a way the
  renderer read as "reuse the lines you already have", so any line-free tile
  in view made it reject every update: no lines anywhere, and names never
  applied. References now have their own marker.

191 JVM tests and 51 Rust tests pass, including real Wanaka packets; lint 0
errors; 623 original assets identical. Not run on a phone.
[Exact release record](../validation/empty-tiles-2026-11-07/release.json) ·
[SHA-256](Ridgeline-Native-Empty-Tiles-0.1.33-Base.apk.sha256).

## Previous 0.1.32 overlay watchdog checkpoint

**[Download 0.1.32 Overlay Watchdog Base APK — 33.7 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Overlay-Watchdog-0.1.32-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 33:
**updates 0.1.13–0.1.31 in place, keeping its data.**

- **Stuck overlay downloads recover on their own.** Overlays came back after
  toggling them or moving away, both of which free the four overlay download
  slots, which points at downloads that never finish. Any overlay download
  outstanding 20 s is now released and retried.
- **Loading diagnostics show the overlay pipeline**: tiles in view and
  prepared, downloads in flight and their age, line packets accepted or
  rejected, and whether names are applied.

Root cause not yet confirmed on a phone. 190 JVM tests pass; lint 0 errors;
623 original assets identical. Renderer unchanged from 0.1.30.
[Exact release record](../validation/overlay-watchdog-2026-11-07/release.json) ·
[SHA-256](Ridgeline-Native-Overlay-Watchdog-0.1.32-Base.apk.sha256).

## Previous 0.1.31 overlay slots checkpoint

**[Download 0.1.31 Overlay Slots Base APK — 33.7 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Overlay-Slots-0.1.31-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 32:
**updates 0.1.13–0.1.30 in place, keeping its data.**

- **Overlays no longer stop after a busy area.** When 512 downloads were
  queued (e.g. moving over Wanaka), a refused track/road/name download leaked
  one of the four overlay download slots; after four, no overlay data was
  downloaded again until the app restarted. Refusals now free the slot and
  retry shortly.

189 JVM tests (including one against the real download guard) and Rust tests
pass; Wanaka's map data checked through the app's own packing (438,390 lines,
none rejected); lint 0 errors; 623 original assets identical. Renderer
unchanged from 0.1.30. Not run on a phone.
[Exact release record](../validation/overlay-slots-2026-11-07/release.json) ·
[SHA-256](Ridgeline-Native-Overlay-Slots-0.1.31-Base.apk.sha256).

## Previous 0.1.30 overlays fixed checkpoint

**[Download 0.1.30 Overlays Fixed Base APK — 33.7 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Overlays-Fixed-0.1.30-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 31:
**updates 0.1.13–0.1.29 in place, keeping its data.**

- **Overlays fixed (broken since 0.1.27).** A native function added in 0.1.27
  returned one byte while the app read four; on the phone the extra bytes are
  undefined, so new names were usually treated as not applied and re-sent
  every frame, resetting the map's names each time. It now returns the right
  type, and any unexpected result falls back to a full update.
- A new test checks every native function's return type against the app's
  declaration (it fails on 0.1.29).

188 JVM tests, Rust tests and GPU checks pass on Vulkan and GLES; lint 0
errors; 623 original assets identical. Not run on a phone.
[Exact release record](../validation/overlays-fixed-2026-11-07/release.json) ·
[SHA-256](Ridgeline-Native-Overlays-Fixed-0.1.30-Base.apk.sha256).

## Previous 0.1.29 steady names checkpoint

**[Download 0.1.29 Steady Names Base APK — 33.7 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Steady-Names-0.1.29-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 30:
**updates 0.1.13–0.1.28 in place, keeping its data.**

- **Names no longer come and go after you stop moving.** The check for names
  hidden by terrain runs at most 15 times a second; if the map stopped just
  after a check, the final view was never checked and names stayed hidden or
  shown until the next touch. The renderer now keeps going until it has been.
- **Search loads about 3.5× faster** and starts loading in the background
  shortly after launch.
- Track and road data: real LINZ tiles packed by the app now go through the
  renderer's parser in a test (all 12,155 lines parse).

187 JVM tests, Rust tests and GPU checks pass on Vulkan and GLES; lint 0
errors; 623 original assets identical. Not run on a phone.
[Exact release record](../validation/steady-names-2026-11-07/release.json) ·
[SHA-256](Ridgeline-Native-Steady-Names-0.1.29-Base.apk.sha256).

## Previous 0.1.28 search and offline checkpoint

**[Download 0.1.28 Search and Offline Base APK — 33.7 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Search-Offline-0.1.28-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 29:
**updates 0.1.13–0.1.27 in place, keeping its data.**

- **Search**, above the saved routes: 48,000 places, peaks, passes, lakes,
  rivers, huts, campsites and DOC tracks, offline. Macrons, case, word order,
  "Mt" and small typos don't matter. Tap a result to fly there.
- **Smaller saved-route cards.**
- **Offline downloads like the Capacitor app:** a page-shaped frame over a 2D
  map (paper size and orientation, resizable corners), then name, detail,
  map content, NZ Topo and overlays, size estimate, and saved regions with
  View, Resume and Remove.
- **Show stored map detail** (Layers › Advanced): tints the 3D terrain red →
  yellow → green by how detailed the imagery stored on the phone is.

185 JVM tests, Rust tests and GPU checks pass on Vulkan and GLES, including
new search, coverage and region frame tests; lint 0 errors; 623 original
assets identical. Not run on a phone. Search data: NZ Gazetteer (LINZ,
CC BY 4.0) and DOC open data.
[Exact release record](../validation/search-offline-2026-11-07/release.json) ·
[SHA-256](Ridgeline-Native-Search-Offline-0.1.28-Base.apk.sha256).

## Previous 0.1.27 lean updates checkpoint

**[Download 0.1.27 Lean Updates Base APK — 32.9 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Lean-Updates-0.1.27-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 28:
**updates 0.1.13–0.1.26 in place, keeping its data.**

- **Names update while you move.** A new name used to copy and convert the
  whole label image (4–16 MB) and wait until the map had been still for
  250 ms. Now only the few new rows are converted and uploaded, during gestures.
- **Track and road updates send only what changed.** Each update re-sent and
  re-read every visible tile's lines (often megabytes); unchanged tiles are now
  referenced by id instead.
- **Prepared track tiles are kept.** Panning back or zooming out and in reuses
  them: no download, decoding or styling (24 tiles on a 3 GB phone).
- **Drawn routes rebuild only when the terrain under them changes**, not every
  time terrain arrives anywhere on screen.

178 JVM tests, Rust tests and GPU checks pass on Vulkan and GLES, including a
new route rebuild check that fails on 0.1.26; lint 0 errors; 623 original
assets identical. Not run on a phone.
[Exact release record](../validation/lean-updates-2026-11-07/release.json) ·
[SHA-256](Ridgeline-Native-Lean-Updates-0.1.27-Base.apk.sha256).

## Previous 0.1.26 quick tracks checkpoint

**[Download 0.1.26 Quick Tracks Base APK — 32.9 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Quick-Tracks-0.1.26-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 27:
**updates 0.1.13–0.1.25 in place, keeping its data.**

- **Tracks and roads arrive sooner.** Their map data was downloaded last,
  behind every visible imagery and terrain tile (priority 800). It now ranks
  with visible imagery: the two nearest tiles right after the centre of the
  view, the rest in view order.
- **Fewer repeated road and river names.** A highway or river split into many
  pieces got a name on every piece. Now there is one name per piece of map
  data, and the same name is not repeated closer than the style's spacing
  (850 px for highways, 900 px for rivers): about one per name per screen.

Renderer unchanged from 0.1.25. 180 JVM tests pass, including new name
spacing tests; lint 0 errors; 623 original assets identical. Not run on a phone.
[Exact release record](../validation/quick-tracks-2026-11-07/release.json) ·
[SHA-256](Ridgeline-Native-Quick-Tracks-0.1.26-Base.apk.sha256).

## Previous 0.1.25 hut names checkpoint

**[Download 0.1.25 Hut Names Base APK — 32.9 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Hut-Names-0.1.25-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 26:
**updates 0.1.13–0.1.24 in place, keeping its data.**

- **Hut names show in steep valleys again.** Since 0.1.21, names hidden
  behind ridges were still given screen space, so in a valley the invisible
  names on the far side crowded out the hut's name (its dot, which never takes
  space, still showed). The GPU now reads back which names terrain hides and
  placement skips them, as MapLibre does.
- **Important names first.** A named hut outranks a river or track name even
  if that one was shown first; equally important names still keep their places.
- **No missing names after a pause.** A name could stay hidden on a slow,
  still map until the next touch; the renderer now draws the frame it needs.

178 JVM tests, Rust tests and GPU checks pass on Vulkan and GLES, including new
placement and visibility read-back tests; lint 0 errors; 623 original assets
identical. Not run on a phone.
[Exact release record](../validation/hut-names-2026-11-07/release.json) ·
[SHA-256](Ridgeline-Native-Hut-Names-0.1.25-Base.apk.sha256).

## Previous 0.1.24 sharp topo checkpoint

**[Download 0.1.24 Sharp Topo Base APK — 32.8 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Sharp-Topo-0.1.24-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 25:
**updates 0.1.13–0.1.23 in place, keeping its data.**

- **NZ Topo is drawn like MapLibre.** Shrunk tiles were point-sampled, which
  dropped up to a third of the map's pixel rows: broken contours, jagged text
  and shimmer while panning. They are now smoothed from the nearest mip level;
  enlarged tiles keep crisp pixel edges, antialiased over one screen pixel.
- **NZ Topo stops at z17.** LINZ's z18 printed-map tiles are its own 2x
  upscale of z17 (checked at Aoraki, Wellington, Queenstown and Ruapehu), so
  the closest zooms now enlarge z17 on the GPU: about 4x fewer downloads per
  area, no detail lost.
- **Lower resolution while moving** (Layers › Advanced; on by default for
  phones with up to 4 GB RAM): 0.75x scale (44% fewer pixels) while you pan,
  zoom or rotate, then an exact full-resolution frame when the map stops.

175 JVM tests, Rust tests and GPU checks pass, including a new NZ Topo
filtering and resolution test on Vulkan and GLES; lint 0 errors; 623 original
assets identical. Not run on a phone.
[Exact release record](../validation/sharp-topo-2026-11-07/release.json) ·
[SHA-256](Ridgeline-Native-Sharp-Topo-0.1.24-Base.apk.sha256).

## Previous 0.1.23 steady lines checkpoint

**[Download 0.1.23 Steady Lines Base APK — 32.8 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Steady-Lines-0.1.23-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 24:
**updates 0.1.13–0.1.22 in place, keeping its data.**

- **Route lines no longer flicker in and out.** The terrain you see is
  simplified with distance and could sit above the route line, hiding pieces
  that changed as you moved. Route lines are now depth-tested from slightly
  above the ground (10 m plus 3 px, scaled with distance), like the waypoint
  dots; a ridge in front still hides them.
- **Tracks and roads stay on every tile.** Overlay textures now come in two
  sizes: sharp 512 px for the nearest tiles and 256 px (four per MB) for the
  rest. A 3 GB phone covers 40 tiles from 16 MB instead of 16, so tiles no
  longer lose their lines when many are visible. Missing tiles are painted
  before stale ones.

174 JVM tests, Rust tests and GPU checks pass, including a new overlay pool
test and route-line checks on Vulkan and GLES; lint 0 errors; 623 original
assets identical. Not run on a phone.
[Exact release record](../validation/steady-lines-2026-11-07/release.json) ·
[SHA-256](Ridgeline-Native-Steady-Lines-0.1.23-Base.apk.sha256).

## Previous 0.1.22 lighter frames checkpoint

**[Download 0.1.22 Lighter Frames Base APK — 32.8 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Lighter-Frames-0.1.22-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 23:
**updates 0.1.13–0.1.21 in place, keeping its data.**

From a Perfetto trace on an Exynos 7884B phone (GPU-bound, ~15 fps, memory
pressure):

- **Labels back in the main render pass.** 0.1.21 drew labels in a second pass,
  which on phone GPUs stores and reloads the whole screen every frame. Labels
  now read the previous frame's terrain depth (projected with that frame's
  camera, so hiding behind terrain stays exact while moving).
- **Controls redraw less while panning.** The scale bar updates at most 4 times
  a second while touching and once when you stop.
- **Less memory on smaller phones.** The overlay texture pool is sized by RAM
  (16 MB on 3 GB phones; it was 49 MB on every phone).

174 JVM tests, Rust tests and GPU checks pass, including label occlusion with a
moving camera on Vulkan and GLES; lint 0 errors; 623 original assets identical.
Not yet re-traced on a phone.
[Exact release record](../validation/lighter-frames-2026-11-07/release.json) ·
[SHA-256](Ridgeline-Native-Lighter-Frames-0.1.22-Base.apk.sha256).

## Previous 0.1.21 GPU labels checkpoint

**[Download 0.1.21 GPU Labels Base APK — 32.8 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-GPU-Labels-0.1.21-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 22:
**updates 0.1.13–0.1.20 in place, keeping its data.**

- **Labels no longer slow the app.** Whether terrain hides a label is decided
  on the GPU from the terrain depth at its anchor, as MapLibre does. The CPU
  line-of-sight march (up to 512 samples per label, on the render thread) is
  gone. Labels are still hidden whole, never cut by a slope.
- **Labels stop flickering while moving.** Labels on screen stay until their
  replacement placement arrives, keep their place and anchor between
  placements, keep their height while terrain refines, and fade in.
- **Fewer river and track names**, 1.5 zooms later (rivers from 15.5, major
  tracks 13.5, other tracks 15.5), one per name per tile.
- **NZ Topo crisp and steady.** Capacitor's 128 px topo tiles with rounded
  tile zoom, and enough tile budget for a full screen (overflowing it made
  the view drop a level and refine a shifting patch: blurry and jumpy).

174 JVM tests, Rust tests and GPU checks pass, including a new GPU label
occlusion test on Vulkan and GLES; lint 0 errors; 623 original assets
identical. Not run on a phone.
[Exact release record](../validation/gpu-labels-2026-11-06/release.json) ·
[SHA-256](Ridgeline-Native-GPU-Labels-0.1.21-Base.apk.sha256).

## Previous 0.1.20 parent preload checkpoint

**[Download 0.1.20 Parent Preload Base APK — 32.7 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Parent-Preload-0.1.20-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 21:
**updates 0.1.13–0.1.19 in place, keeping its data.**

- **Preload coarser surrounding tiles** (Layers → Advanced, on by default).
  Once the view has loaded and you stop, lower-detail terrain and imagery for
  the surrounding area (2 and 3 levels coarser) load at idle priority, so
  moving stays smooth the way it does after zooming out and back in.
- **NZ Topo switches maps when zooming out.** A loaded tile at the right zoom
  is now drawn even when finer tiles are cached, as MapLibre does in
  Capacitor, so Topo250 replaces shrunken Topo50. Zoom is limited to 16 on
  NZ Topo, as in Capacitor.
- **Map layers closes on any tap outside it**, and the tap still works.
- Saved route cards like Capacitor's; smaller route stats and buttons; slightly
  larger close X.

173 JVM tests, Rust tests (incl. a new NZ Topo source test) and GPU checks
pass; lint 0 errors; 623 original assets identical. Not run on a phone.
[Exact release record](../validation/parent-preload-2026-11-06/release.json) ·
[SHA-256](Ridgeline-Native-Parent-Preload-0.1.20-Base.apk.sha256).

## Previous 0.1.19 terrain drape checkpoint

**[Download 0.1.19 Terrain Drape Base APK — 32.7 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Terrain-Drape-0.1.19-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 20:
**updates 0.1.13–0.1.18 in place, keeping its data.**

- **Overlays drawn the way the Capacitor app draws them.** Tracks, roads,
  contours and imagery borders are painted on the GPU into a texture for each
  visible image tile and laid over the photo, as MapLibre's terrain
  render-to-texture does. They sit exactly on the terrain, hide behind ridges,
  and are never rebuilt by moving the camera or by terrain loading; only tiles
  whose lines change are repainted. The CPU line draping is gone.
- **No overlay work while overlays are off** (no texture, no selection, no
  repainting).
- Smaller Map layers panel; tapping elsewhere closes it. Compact Saved routes
  card that closes when you open a route. Route sheet rounded on top only. The
  scale bar and "i" button sit above the sheet. Dragging the elevation chart
  backwards swaps ascent and descent.
- The scale bar no longer re-lays-out the screen on every camera change.

171 JVM tests, Rust tests and GPU checks pass, including a new overlay drape
test on Vulkan and GLES; lint 0 errors; 623 original assets identical. Not run
on a phone; UI not seen on a device.
[Exact release record](../validation/terrain-drape-2026-11-06/release.json) ·
[SHA-256](Ridgeline-Native-Terrain-Drape-0.1.19-Base.apk.sha256).

## Previous 0.1.18 smooth overlays checkpoint

**[Download 0.1.18 Smooth Overlays Base APK — 32.7 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Smooth-Overlays-0.1.18-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 19:
**updates 0.1.13–0.1.17 in place, keeping its data.**

- **Tracks and roads keep up while moving.** Overlay view selection was a
  180 ms wait that every camera frame restarted, so new tiles were not even
  requested until you paused; it now runs every 50 ms while moving. Arrived
  tiles appear within 100 ms (was 300 ms plus 60 ms), and per-tile styling is
  cached so this stays cheap.
- **Route dots are drawn whole.** Their lower half used to fail the depth test
  against the ground just in front of them; a ridge in front still hides them.
- **Route sheet like Capacitor.** Square corners; no ⋮ or boxed × (the top ×
  stays; long-press Export GPX to share). The elevation chart fills the
  expanded sheet with Capacitor's tooltip instead of the caption line. Edit,
  Undo, Export GPX and Delete point are spaced apart with clean corners.
  Smaller top-right controls.
- **A/B sections.** Dragging the chart marks the start A (green) and end B
  (orange) in the chart and on the map, with distance, ascent and descent.
- **Faster chart dragging.** The chart is cached as an image and section
  statistics are O(log n), so a drag redraws only the cursor, section and
  tooltip; no sheet re-layout on each move.

171 JVM tests, Rust tests and GPU route/label/terrain checks pass (new
waypoint test on Vulkan and GLES); lint 0 errors; 623 original assets
identical. Not run on a phone; UI not seen on a device.
[Exact release record](../validation/smooth-overlays-2026-11-06/release.json) ·
[SHA-256](Ridgeline-Native-Smooth-Overlays-0.1.18-Base.apk.sha256).

## Previous 0.1.17 Capacitor parity checkpoint

**[Download 0.1.17 Capacitor Parity Base APK — 32.7 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Capacitor-Parity-0.1.17-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 18:
**updates 0.1.13–0.1.16 in place, keeping its data.**

Follows the Capacitor app for overlays, peak labels, route profiles and the map UI:

- **Overlays come on and off by Capacitor's rules.** Vector topography stops at
  zoom 14 and is over-zoomed closer, as Capacitor's source does (native asked
  for zoom 15). Layer filters use each tile's own zoom, as MapLibre does.
  Visible tiles load four at a time, including during gestures, so mapped
  tracks and roads appear when zoomed close.
- **Peak names are no longer drawn inside mountains.** Line of sight is checked
  every 25 m of ground (was 48 samples per ray), and only the last 30 m before
  the summit is exempt (was 2% of the ray), so thin and near-summit ridges
  hide the label.
- **High-detail elevation profiles.** Routes are sampled every 5 m from zoom 17
  LINZ elevation (was 100 m spacing from zoom 14), with 2 m samples at zoom 17
  along the part in view at zoom 15+. A quick zoom 13 preview shows first. This is
  Capacitor's profile engine; ascent and descent appear only with full coverage.
- **Capacitor map UI.** Kea launcher and locate button, 2D/3D switch, compass
  and layers buttons, scale bar and an "i" attribution button. The zoom +/−
  buttons are gone. The Map layers panel and the Saved routes drawer follow
  Capacitor's layout and wording.
- **Top buttons respond on the first tap.** An open Layers panel used to swallow
  the first tap on any other control; camera updates on the UI thread no longer
  wait on the tile loader's lock; every button shows a pressed state.

170 JVM tests and the Rust and GPU label/terrain checks pass; lint 0 errors.
All 623 original assets identical, plus the 40 KB launcher icon. Not run on a
phone, and the UI was not seen on a device (no emulator here).
[Details](../research/FULL-AUDIT-2026-11-06.md#0117-capacitor-parity) ·
[Exact release record](../validation/capacitor-parity-2026-11-06/release.json) ·
[SHA-256](Ridgeline-Native-Capacitor-Parity-0.1.17-Base.apk.sha256).

## Previous 0.1.16 connection recovery checkpoint

**[Download 0.1.16 Connection Recovery Base APK — 32.6 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Connection-Recovery-0.1.16-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 17:
**updates 0.1.13–0.1.15 in place, keeping its data.**

Network transport recovery. All LINZ requests share one HTTP/2 connection; when
it dies silently (Wi-Fi/mobile handover, tower change, NAT timeout) every LINZ
tile previously stalled until the 15 s read timeout.

- **Stalled requests are hedged.** No response headers after 2.5 s → the same
  request goes out on a fresh connection; first answer wins. New requests then
  move to the connection that proved healthy.
- **Network changes act immediately.** Pooled connections are dropped, stalled
  requests are repeated over the new network at once, connections re-warmed.
- **Connections are warmed at start** (LINZ and Esri DNS/TCP/TLS).

Real-socket tests: a stalled connection recovered in ~0.3 s (test hedge) instead
of the full read timeout; a network change recovered in 0.17 s; healthy
requests are never duplicated. 159 JVM tests pass. Renderer identical to 0.1.15;
all 623 original assets identical. No phone measurement.
[Findings and limits](../research/FULL-AUDIT-2026-11-06.md#0116-network-transport) ·
[Exact release record](../validation/connection-recovery-2026-11-06/release.json) ·
[SHA-256](Ridgeline-Native-Connection-Recovery-0.1.16-Base.apk.sha256).

## Previous 0.1.15 fast coverage checkpoint

**[Download 0.1.15 Fast Coverage Base APK — 32.6 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Fast-Coverage-0.1.15-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer, version code 16:
**updates 0.1.13/0.1.14 in place, keeping its data.**

Aimed at blank or slow-loading ground after moving quickly over terrain:

- **Coarse backdrop under every view.** A few zoom 5–10 terrain and imagery
  tiles are planned first, so new ground shows coarse terrain immediately.
- **Imagery no longer waits for fine terrain.** Arrived photos drape on the
  backdrop (or any resident coarser terrain) until the fine mesh is ready.
- **Terrain from its own elevation first.** Progressive terrain is on by default
  and now covers up to 16 nearby tiles at once (was one). The rim that needs
  neighbouring elevation keeps the complete parent until it arrives; unsafe
  joins fall back to normal loading. Turn off under Layers → Progressive terrain.
- **Less wasted work.** Started downloads that leave the view may finish for
  1.5 s in background transfer slots, never taking foreground slots.

GPU checks pass on Vulkan and GLES (multi-tile progressive terrain fails on the
previous renderer). All 623 original assets identical; signature, 16 KiB
alignment and non-debuggable build verified. No phone measurement.
[Audit and limits](../research/FULL-AUDIT-2026-11-06.md#0115-fast-coverage) ·
[Exact release record](../validation/fast-coverage-2026-11-06/release.json) ·
[SHA-256](Ridgeline-Native-Fast-Coverage-0.1.15-Base.apk.sha256).

## Previous 0.1.14 motion and overlays checkpoint

**[Download 0.1.14 Motion and Overlays Base APK — 32.6 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Motion-Overlays-0.1.14-Base.apk)**.
Same "Ridgeline Native Full Audit" package and signer as 0.1.13, version code 15:
**updates 0.1.13 in place, keeping its data.**

- **Terrain and imagery keep sharpening while moving.** Up to two terrain uploads
  and one imagery upload per frame during gestures (bounded to 2 ms / 4 ms of
  upload time, GPU backlog and byte limits kept). Previously non-urgent
  refinement waited until the camera stopped. 120 Hz gesture replay: 0 → 240
  uploads/s.
- **Names no longer sit inside mountains.** Labels are drawn whole on top of
  the terrain, as in the Capacitor app; a label is hidden when terrain blocks its
  anchor, instead of being clipped by nearby slopes.
- **Dotted lines no longer crawl while zooming.** Dash patterns are fixed to the
  ground and crossfade between zoom levels, like MapLibre. Routes stay solid.

GPU parity checks pass on Vulkan and GLES and fail against the previous code.
All 623 original assets identical; signature, 16 KiB alignment and
non-debuggable build verified. No phone measurement.
[Audit and limits](../research/FULL-AUDIT-2026-11-06.md) ·
[Exact release record](../validation/motion-overlays-2026-11-06/release.json) ·
[SHA-256](Ridgeline-Native-Motion-Overlays-0.1.14-Base.apk.sha256).

## Previous 0.1.13 full audit checkpoint

**[Download 0.1.13 Full Audit Base APK — 32.6 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/native-wgpu/checkpoints/Ridgeline-Native-Full-Audit-0.1.13-Base.apk)**.
**A separate app, "Ridgeline Native Full Audit"** (`nz.ridgeline.nativeapp.fullaudit`),
signed with a new key because the earlier signing key is unavailable. It installs
alongside 0.1.12 and does not touch its data. Version code 14.

- **Not debuggable.** Every earlier native checkpoint was a debuggable build (ART
  debuggable mode + CheckJNI); every Capacitor APK it was compared with was not.
- **Still-camera refinement no longer waits on frame slack.** At 120 Hz/heavy
  frames the old rule could fall to ~4 tile uploads/s; replay now 240/s.
- **Opaque imagery is copied directly** into the upload buffer (no 256 KiB int
  array or per-pixel Kotlin loop); same bytes.
- **Survey seam masks download as WebP** (alpha identical on 1,064 live tiles,
  all 46 surveys; 89% fewer bytes) with PNG fallback.

Renderer libraries are byte-identical to 0.1.12 (reproduced with Rust 1.99.1);
all 623 original assets identical; 16 KiB alignment and signature verified.
No phone measurement; no speedup is claimed as measured.
[Audit and limits](../research/FULL-AUDIT-2026-11-06.md) ·
[Exact release record](../validation/full-audit-2026-11-06/release.json) ·
[SHA-256](Ridgeline-Native-Full-Audit-0.1.13-Base.apk.sha256).

## Previous 0.1.12 additional latency audit checkpoint

**[Download 0.1.12 Additional Latency Audit Base APK — 33.2 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/native-renderer-performance/native-wgpu/checkpoints/Ridgeline-Native-Additional-Audit-0.1.12-Base.apk)**.
Source `6052065`, version code13. Same Native Performance package and signer;
updates recent Native Performance builds in place, preserving app data.

Post-acquire camera latching, bounded incremental GPU overlays, completion-triggered
background draping, first-qualified adaptive terrain and active-refresh budgets.
Routes match Capacitor's nominal stroke widths and colours, with corrected
perspective/antialiasing, reference layer ordering and Open Sans labels.

In the synthetic one-patch workload, host-to-GPU overlay bytes fall from
20,480,000 to640,000 (96.875%). Four focused JVM and16renderer checks pass,
including Vulkan/GLES GPU readback and route rendering. All three optimized ABIs,
signer, install metadata, 16KiB alignment and621original assets are verified.
No physical-device speedup measurement or full MapLibre pixel-parity claim.
[Implementation and limits](../research/ADDITIONAL-AUDIT-2026-11-05.md) ·
[Exact release record](../validation/additional-audit-2026-11-05/release.json) ·
[SHA-256](Ridgeline-Native-Additional-Audit-0.1.12-Base.apk.sha256).

## Previous 0.1.11 imagery border recovery checkpoint

**[Download 0.1.11 Imagery Border Recovery Base APK — 32.7 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/native-renderer-performance/native-wgpu/checkpoints/Ridgeline-Native-Imagery-Border-Recovery-0.1.11-Base.apk)**.
Source `aab3c12`, version code12. Same Native Performance package/signer;
updates 0.1.2–0.1.10 in place, preserving app data.

Fixes two recovery paths relevant to blurry LINZ/Esri border tiles around Mount
Cook: survey network failures can no longer publish/cache LINZ-only pixels as a
completed seam tile, and repeated transient failures keep scheduling bounded
retries for still-wanted tiles while the camera is stationary. Old prepared
compositions are bypassed; valid original HTTP caches and saved maps remain.

A live Cook source sample has detailed Esri replacement for36,308pixels of the
256×256 border tile. This is a source comparison, not a phone failure trace.
Four focused JVM checks pass, including real HTTP recovery after four503s and the
exact Cook source-mask reference. All621original assets and all3renderer libraries
are unchanged from0.1.10. Signature, package/version and ZIP/ELF16KiB verified.
No broad tests/lint, Rust/GPU changes or physical-device run.
[Findings and limitations](../research/IMAGERY-BORDER-RECOVERY-2026-11-03.md) ·
[Exact release record](../validation/border-recovery-2026-11-03/release.json) ·
[SHA-256](Ridgeline-Native-Imagery-Border-Recovery-0.1.11-Base.apk.sha256).

## Previous 0.1.10 native audit checkpoint

**[Download 0.1.10 Native Latency Audit Base APK — 32.7 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/native-renderer-performance/native-wgpu/checkpoints/Ridgeline-Native-Latency-Audit-0.1.10-Base.apk)**.
Source `655de14`, version code 11. Same Native Performance package and signer;
updates 0.1.2–0.1.9 in place, preserving app data.

Submitted-scene CPU picking runs on an independent input worker. A changed camera
gets priority over optional uploads with complete coverage. Boundary indexing,
per-terrain line caches and route-only invalidation avoid repeated map-layer work.
Stable label sprites support metadata-only changes and dirty-row texture uploads.
Unshaded 2D shares one height resource and bypasses DEM preparation queues while
keeping the original grid rasterisation and imagery pixels.

**Progressive focus terrain is experimental and off by default.** It can reveal
measured interior detail while a small rim retains a measured parent, only when
strict parent/image/skirt checks pass. Unsupported joins keep complete-patch
loading. The proposed two-triangle planar quad is deferred due to texel ties.

Three focused JVM checks, a focused Rust fragment-cache check and production
Vulkan/GLES fixtures pass. Boundary segment tests fall 767,520 → 4,642 with exact
ordered output in 72 tile fixtures; this is not a handset latency measurement.
All three optimized Android ABIs rebuilt; signature, package/version, ZIP/ELF
16KiB alignment, all38 JNI exports perABI and all621 original assets verified.
No broad test suite/lint or physical-device execution.
[Implementation and restrictions](../research/NATIVE-LATENCY-AUDIT-2026-11-03.md) ·
[Exact release record](../validation/native-audit-2026-11-03/release.json) ·
[SHA-256](Ridgeline-Native-Latency-Audit-0.1.10-Base.apk.sha256).

## Previous 0.1.9 UI and layers checkpoint

**[Download 0.1.9 UI and Layers Base APK — 32.5 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/native-renderer-performance/native-wgpu/checkpoints/Ridgeline-Native-UI-Layers-0.1.9-Base.apk)**.
Source `d258866d31509174c01aa0022094015d687566c2`, version code 10. Same Native
Performance package and signer; updates 0.1.2–0.1.8 in place, preserving app data.

Native map controls, route drawer and two-stop sheet now follow Capacitor 0.18.0.
The existing route editor gains gated direct waypoint dragging, single-step
drag undo, ordered route combination/reversal, multiple opened routes, chart
inspection/ranges and GPX sharing. All map-layer groups are present, including
Aerial/Satellite/NZ Topo, names/peaks/roads/tracks/huts/contours/boundary, natural
colour, hillshade, relief and sky. Detail, download-job and cache controls remain.

Unshaded 2D skips DEM/halo requests and uses planar selection. A shared background
vector stream, cached native font atlas, parent/child cover and disabled-layer
skipping bound extra map work. Instanced lines use 29,952 → 4,992 upload bytes
with identical pixels in the host graphics fixture. Real-MVT names-only decode
median is 2.414 → 0.063 ms in the controlled host case; not phone FPS/latency.

Eleven targeted JVM checks and Vulkan/GLES graphics fixtures pass; all three
optimized Android ABIs rebuilt. Signature, package/version, ZIP/ELF 16 KiB
alignment, every JNI export and original assets verified. No full suite/lint or
physical-device run. Native fonts/horizontal line labels approximate MapLibre;
native offline forms remain, with paper-frame/Topo PDF outside this checkpoint.
[Scope, measurements and remaining differences](../research/UI-LAYERS-MIGRATION-2026-11-02.md) ·
[Exact release record](../validation/ui-layers-2026-11-01/release.json) ·
[SHA-256](Ridgeline-Native-UI-Layers-0.1.9-Base.apk.sha256).

## Previous 0.1.8 HTTP checkpoint

**[Download 0.1.8 HTTP Latency Base APK — 32.2 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/native-renderer-performance/native-wgpu/checkpoints/Ridgeline-Native-HTTP-Latency-0.1.8-Base.apk)**.
Source `9b7e82fbe9e33dce2f70c81d1ab80624f7bd1f83`, version code 9. Same Native
Performance package and signer; installs over 0.1.2–0.1.7 preserving data.

Cold HTTP responses reach decoding before publication into the existing full-size
disk cache. Pending bytes remain reusable during publication, including at zero
retained RAM. OkHttp 5.3.2 starts an alternative connection address after 250 ms
and cancels losing attempts. Existing 4.12 cache files remain readable, and stale
responses retain the SDK's conditional-validation policy.

Controlled real-HTTP host fixtures: cache-publication delay **505.143 → 2.557 ms**;
unresponsive first address **651.938 → 251.875 ms**. Three alternating pairs each,
same bytes and one actual GET per trial. These reproduce avoidable waits rather
than establish the cause or magnitude of the phone's five-second HTTP stalls.
Loading diagnostics now reports DNS, connect/TLS, provider headers, raw wire
reads, cache/body processing and observed payload rate over about five seconds.

Eight distinct focused JVM checks pass, covering caching policy, redirects,
gzip/chunked bodies, old-cache compatibility, retired-call isolation and 50-job
bursts. Signature, package/version and ZIP/ELF alignment verified. All 618 original
assets and three optimized native libraries match 0.1.7 byte-for-byte; the SDK
adds its public-suffix database asset. No broad suite/lint or physical-device run.
[HTTP report and limits](../research/HTTP-LATENCY-2026-11-01.md) ·
[Exact release record](../validation/http-path-2026-11-01/release.json) ·
[SHA-256](Ridgeline-Native-HTTP-Latency-0.1.8-Base.apk.sha256).

## Previous 0.1.7 latency checkpoint

**[Download 0.1.7 Latency Base APK — 32.1 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/native-renderer-performance/native-wgpu/checkpoints/Ridgeline-Native-Latency-0.1.7-Base.apk)**.
Source `36f94778587447c5519623bea318f2d0e0b5e072`, version code 8. Same Native
Performance package and signer; installs over 0.1.2–0.1.6 preserving data.

Eight changes across original local I/O, late prepared-cache adoption, offline
prefetch, saved-reader locks, saved-region query scheduling, GPU admission,
Vulkan mip/frame batching and asynchronous HTTP-cache teardown. The 1–50 job
controls remain. All three optimized native ABIs rebuilt; original detail,
filters and all 618 assets preserved.

Controlled host fixtures: late prepared cache **363.223 → 41.986 ms**;
a 250 ms writer-monitor hold **250.218 → 0.000431 ms** for an established reader;
ready-image CPU queue behind held local reads **250.345 → 0.120 ms**. Offline
lookahead roughly halves the injected source-pipeline workload with identical
requests/bytes and zero retained RAM. These are conditional subsystem results,
not phone latency/FPS claims. Vulkan batching reduces four-image frame submissions
from five to one; GLES retains its previous path after mixed host timing results.

Eleven targeted JVM checks pass; three graphics configurations compare three
submission modes with identical frame pixels and nine-level mip probes. No full
suite/lint or physical-device benchmark. Signature, package/version, ZIP/ELF
alignment, new JNI exports and unchanged original assets verified.
[Full report, measurements and next candidates](../research/ASYNCHRONOUS-PIPELINE-2026-11-01.md) ·
[Exact release record](../validation/asynchronous-pipeline-2026-11-01/release.json) ·
[SHA-256](Ridgeline-Native-Latency-0.1.7-Base.apk.sha256).

## Previous 0.1.6 download-controls checkpoint

**[Download 0.1.6 Download Controls Base APK — 32.1 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/native-renderer-performance/native-wgpu/checkpoints/Ridgeline-Native-Download-Controls-0.1.6-Base.apk)**.
Source `f6f5ea0682945d3e42fc73f392b298e57ea2c37d`, version code 7. Same Native
Performance package and signer; installs over 0.1.2–0.1.5 preserving data.

**Offline / imagery → Download jobs** now has three saved sliders, each 1–50:
While moving (default 8), While stationary (default 12), and Background (default 2).
Changes apply immediately; lowering a cap lets current transfers finish. Reset
restores the defaults. Background jobs are additional to foreground jobs.
Both HTTP paths accommodate the full selectable range; the transport still
controls actual admission. One-slot settings allow terrain to progress.

Two focused checks pass, including 50 simultaneous real HTTP transfers on both
ordinary and isolated cache-free paths. No full suite or lint. Signature,
version/package and ZIP alignment verified; all 618 assets and three optimized
native libraries match 0.1.5 byte-for-byte. No phone speed measurement.
[Implementation/scope](../research/DOWNLOAD-CONTROLS-2026-11-01.md) ·
[Exact release record](../validation/download-controls-2026-11-01/release.json) ·
[SHA-256](Ridgeline-Native-Download-Controls-0.1.6-Base.apk.sha256).

## Previous 0.1.5 surface-focus checkpoint

**[Download 0.1.5 Surface Focus Base APK — 32.1 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/native-renderer-performance/native-wgpu/checkpoints/Ridgeline-Native-Surface-Focus-0.1.5-Base.apk)**.
Source `b0d76de4d551ee5ae68a18ce02e9bc210196ca84`, version code 6. Same Native
Performance package and signing key; installs over 0.1.2–0.1.4 preserving data.

Terrain and imagery detail now prioritize the actual terrain under the centre
screen ray, keeping the map pivot as a secondary focus. Uses the existing CPU
terrain picker, with no GPU readback or extra source requests for picking.
Missing/stale/off-screen hits fall back to the old policy. Detail defaults,
source ceilings and source budgets remain. Loading diagnostics shows whether
the measured terrain focus is active. All three optimized native ABIs rebuilt.

Focused elevated-surface fixture: face request rank 33 → 0 and source zoom
15 → 16, with the same 72-source budget. This is selection behavior, not a
phone latency result or a claim that the ten-second stall is solved.
Two Rust and two Kotlin checks pass; no broad tests/lint. Package signature,
ZIP/ELF alignment and focused JNI exports verified; all 618 assets unchanged.
[Implementation/scope](../research/SURFACE-FOCUS-2026-11-01.md) ·
[Exact release record](../validation/surface-focus-2026-11-01/release.json) ·
[SHA-256](Ridgeline-Native-Surface-Focus-0.1.5-Base.apk.sha256).

## Previous 0.1.4 current-view checkpoint

**[Download 0.1.4 Current View Base APK — 32.1 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/native-renderer-performance/native-wgpu/checkpoints/Ridgeline-Native-Current-View-0.1.4-Base.apk)**.
Source `db36617a595ef0058c43622864f2bb603a6223b7`, version code 5; same Performance
package and signer for in-place updates from 0.1.2/0.1.3, preserving app data.

Fixes a structural queue gap: canceled off-screen calls could still occupy
OkHttp's Dispatcher even after the loader freed their permits. The cache-free
escape client shared that queue. It now has an independent bounded dispatcher;
new network admission uses it when retired calls still occupy the normal queue.
Underlying running/queued and canceled-call counts are now visible in
**Offline / imagery → Loading diagnostics**.

One focused real-OkHttp regression, four alternating trials: current image
253.3 → 2.0 ms while canceled calls remain blocked on a 250 ms cache-monitor hold.
Same image bytes; canceled requests never reach the HTTP server. This reproduces
the mechanism, not the user's particular phone stall. No full tests/lint or phone
speedup claim. Package/signature/alignment checks pass; all assets and optimized
native libraries remain byte-identical to 0.1.3.
[Structural audit](../research/RETIRED-HTTP-2026-11-01.md) ·
[Release record](../validation/retired-http-2026-11-01/release.json) ·
[SHA-256](Ridgeline-Native-Current-View-0.1.4-Base.apk.sha256).

## Previous 0.1.3 cold-loading checkpoint

**[Download 0.1.3 Cold Loading Base APK — 32.1 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/native-renderer-performance/native-wgpu/checkpoints/Ridgeline-Native-Cold-Loading-0.1.3-Base.apk)**.
Source `8ac2e3db62817161af54513cb89638ad3e693bfb`, version code 4.
Installs over **Ridgeline Native Performance 0.1.2** with the same package and
signer, preserving its settings, routes, attached ZIP permissions and cache.

Visible imagery refinement now has two reserved slots within the existing
foreground network limit, so terrain dependency fan-out cannot occupy every slot.
Optional derived disk reads and shared PNG waits no longer block CPU workers.
Adds original local coarse coverage for uncovered views, persistent cache-free
HTTP winners, and **Offline / imagery → Loading diagnostics** with live queue and
HTTP-stage timing snapshots plus Copy. Full imagery/terrain detail remains.

Controlled host fixtures: sharpened image admission 255.9 → 5.5 ms with 250 ms
terrain transfers; foreground prepared-cache stall 251.5 → 13.8 ms, same mesh
bytes. These are independent injected workloads, not phone/Wi-Fi measurements.
Healthy derived hits pay about 0.61 ms extra in the host fixture.
[Investigation, tradeoffs and scope](../research/COLD-REFINEMENT-2026-11-01.md) ·
[Exact release record](../validation/pipeline-waits-2026-11-01/release.json) ·
[SHA-256](Ridgeline-Native-Cold-Loading-0.1.3-Base.apk.sha256).
Seven focused checks passed; no broad suite/lint or device performance claim.
Signing/alignment/package checks pass. All 618 assets and three optimized native
libraries are byte-identical to 0.1.2; this pass changes Kotlin loading code/UI.

## Previous 0.1.2 loading checkpoint

**[Download 0.1.2 Loading Base APK — 32.0 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/native-renderer-performance/native-wgpu/checkpoints/Ridgeline-Native-Performance-2026-11-01-Base.apk)**.
Source [`d831d2f`](https://github.com/connorgleeson736-sys/Ridgeline/commit/d831d2f3ddc9c301e0b2282fa58912d3940dd222), version code 3; optimized ARM64, ARMv7 and x86_64.

Installs as **Ridgeline Native Performance**, package
`nz.ridgeline.nativeapp.performance`. A new signing key was created at the user's
request because the previous key was unavailable. This is a separate installation;
the old native app and its private data remain in place. Its caches, saved routes
and imagery attachments are not automatically transferred. The new key and restore
instructions are backed up privately in the user's Google Drive, outside Git.

Includes foreground tile priority throughout selection, cache/network/CPU/GPU
admission, independent imagery and terrain detail sliders, decoded DEM reuse,
coverage/seam reuse, bounded bypass of stalled disk-cache reads, early DEM parent
loading and atomic camera-demand cancellation. Existing detail defaults remain.
See the [viewport audit](../research/VIEWPORT-LOADING-AUDIT-2026-10-31.md) and
[latency measurements](../research/LATENCY-BARRIERS-2026-11-01.md).

[Build/package record](../validation/loading-checkpoint-2026-11-01/release.json) ·
[Validation scope](../validation/loading-checkpoint-2026-11-01/README.md) ·
[SHA-256](Ridgeline-Native-Performance-2026-11-01-Base.apk.sha256).
APK signature, ZIP/ELF alignment and all 618 original asset files verified;
67 coarse images, no large packs or session CA. No phone benchmark is claimed.
Before the user stopped further testing, one Android HTTP-cache fixture timed
out (99 tests, 1 failure, 5 skipped); it remains unresolved. Final assembly
succeeded without test or lint tasks.

## Previous native checkpoint used as the baseline

**[0.1.1 Base APK — 32.0 MB](Ridgeline-Native-Performance-2026-10-31-Base.apk)**.
The [latest Base alias](Ridgeline-Native-Base.apk) contains the same bytes.
Source checkpoint [`150ad30`](https://github.com/connorgleeson736-sys/Ridgeline/commit/150ad30e5db269a549769fb011667c31ec975178), version code 2, signed debug APK with optimized ARM64, ARMv7 and x86_64.

Install over the previous native prototype without uninstalling: package identity
and signing certificate match, preserving app-private routes, caches and attached
imagery permissions. The existing Capacitor app remains separate.

This checkpoint replaces the quadratic terrain/imagery cover scan with an exact
geographic merge: **9.398 → 0.705 ms** in the fragmented-source host fixture,
with an identical ordered patch list. It also starts idle demand immediately,
rejects stale derived entries before decompression, shares overlapping DEM PNG
decodes, reuses exact halo/axis calculations, improves skirt submission work,
and accelerates exact CPU mipmaps and tile bounds. No detail or source imagery
resolution is reduced. The more complex culling experiment was rejected.

[Individual measurements and limits](../research/PERFORMANCE-AUDIT-2026-10-31.md) ·
[APK verification](../validation/performance-checkpoint-2026-10-31/apk.json) ·
[SHA-256](Ridgeline-Native-Performance-2026-10-31-Base.apk.sha256)

Validation: 37 Rust tests, 69 Android unit tests (2 skipped), lint, six Vulkan/GLES
GPU modes, all three optimized ABIs, matching signer and ZIP/64-bit ELF alignment.
All 67 coarse images, overview catalog and gap atlas match the prior checkpoint.
No large packs or session CA are bundled. **No A20 speedup or device acceptance
is claimed.** Raw subsystem timings cannot be combined into an app-wide speedup.

Previous native Base: [source/checkpoint da2579c](https://github.com/connorgleeson736-sys/Ridgeline/tree/3cc6f903670d6796f04adc5201c112b067469172/native-wgpu/checkpoints).

**Parent fallback fix:** cached parents remain as background coverage after a fine
tile arrives. Prepared RAM ancestors can return across multi-level zoom jumps,
with ancestor uploads admitted and prioritized. Eviction protects useful owners.
[Regression evidence](../validation/parent-fallback/) preserves the complete cached
frame under fine-only demand on Vulkan/GLES arrays. RAM/disk sliders and selective
skirts remain; no imagery packs/session CA included. A20 movement still needs
phone verification.

This audit build adds **GPU texture arrays**, compatible instanced draws,
reusable GLES mip resources, stable index suballocation and bulk pixel packing.
**Offline / imagery → GPU textures** selects Automatic, Compatibility or Texture
arrays. Automatic uses arrays on Vulkan and pooled separate textures on GLES;
host GLES arrays reduced draw/allocation calls but were slower overall.

**Offline / imagery** also has a **0–1000 MiB automatic disk-cache slider**.
Changes apply after restarting the app; the current active limit is shown.
Zero disables and clears automatic disk caches at next launch. Saved regions,
attached imagery ZIPs and RAM/GPU working sets are separate.
[Measured evidence and limits](../validation/comprehensive-audit/).

**Combined RAM tile cache (0–1000 MiB)** is also in Offline / imagery and
applies immediately. One retained-payload budget covers compressed downloaded
bytes, decoded terrain and prepared terrain/imagery buffers. Zero clears/disables
their retention. The setting persists; recommended defaults and a high-budget
warning are shown. Active loading memory, renderer CPU working sets and GPU
resources are separate. [Scope and checks](../validation/ram-cache-slider/).

**Add your existing imagery:** open **Offline / imagery → Add imagery ZIPs**, then
select the original Ridgeline overview ZIPs already on your phone (up to six at
once). Files attach in place: no extraction, copying or modification. Keep them
at the selected location. Detach removes app access without deleting your files.
The overview packs contain imagery; terrain needs network or a saved region.

This checkpoint restores **selective terrain skirts** by request, omitting proven
continuous terrain joins and photo-only internal subdivisions. Real neighbour DEM
halos, fallback coverage and full imagery filtering remain.
[Seam protection checks](../validation/seam-protection/) close about 36,000 exposed
background pixels in a controlled mixed-LOD fixture on Vulkan/GLES, including GLES
texture arrays. All three optimized ABIs, signing and alignment pass. No phone
performance or visual acceptance claim.

It retains all six cache/revisit behaviors from the published 0.18 reference: independent local-cache
admission, freshness-aware encoded RAM, shared cancellable/promotable transfers,
quick-return grace, cached sharper/ancestor coverage and coarse reserve, completed
imagery/plan reuse, and tightly bounded predictions.

Optional **Offline / imagery → Prepare nearby terrain while idle** prepares a
small expanded GPU batch; it is off by default. Add imagery still attaches the
original ZIPs in place. No large packs or session CA; 67 coarse images included.
Full source resolution, heights, mips/filtering and adaptive error bounds remain.
Skirts hide geometric gaps within their depth; missing source coverage or colour
differences baked into imagery still require real provider data.

Earlier cache-revisit evidence: 18 focused JVM checks and 26 core tests passed; Vulkan/GLES and all three optimized
JNI ABIs/assembly/signature pass. A real host HTTP fixture returns a disk-cached
tile in 1.53ms while eight foreground transfers are blocked. This is not A20
performance or interaction acceptance. [Scope and evidence](../validation/cache-revisit/).

Earlier Base: [Ridgeline-Native-Base-a585548.apk](Ridgeline-Native-Base-a585548.apk).

The older Full checkpoint below retains all six original packs.

[Ridgeline-Native-Checkpoint-5c6d05b.apk](Ridgeline-Native-Checkpoint-5c6d05b.apk)
is the normal signed debug build from source checkpoint `5c6d05b`.
Download the binary through GitHub or run `git lfs pull` after checkout.

Android8/API26or newer; ARM64, ARMv7 and x86_64. Separate application ID
`nz.ridgeline.nativeapp`; existing Ridgeline apps and private data stay separate.
All six exact original overview ZIPs are included. Terrain/finer imagery need
network access. No managed-session CA is included in this distributable build.

Validated:22Rust tests,25Android unit tests, lint/build, APK signing/alignment,
original ZIP hashes; Android30software-emulator controlled lifecycle/rotation,
SQLite/GPX, genuine terrain loading/picking, and all9mip levels. Emulator source
integration used a separate local debug trust profile with identical native
libraries/assets. Before/after captures and exact scope are in
[validation/android30](../validation/android30/).

The native migration remains incomplete. Real cross-LOD transitions, remaining
camera/UI features and physical-phone performance are not accepted. The latest
Base includes more native features than the older Full artifact. See
[GOAL.md](../../GOAL.md) and [NATIVE_PROGRESS.md](../../NATIVE_PROGRESS.md).

The APK is stored with Git LFS. Its checksum is in the adjacent `.sha256` file.
No debug keystore or session certificate is committed.
