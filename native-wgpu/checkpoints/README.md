# Native Android checkpoints — incomplete prototypes

Latest performance checkpoint: **[Download 0.1.1 Base APK — 32.0 MB](Ridgeline-Native-Performance-2026-10-31-Base.apk)**.
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
