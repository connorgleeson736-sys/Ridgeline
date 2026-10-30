# Native Android checkpoints — incomplete prototypes

Latest smaller build: [Ridgeline-Native-Base.apk](Ridgeline-Native-Base.apk).
Source checkpoint `22858d0`; signed debug Base APK, 31.6MB, all three ABIs.

**Add your existing imagery:** open **Offline / imagery → Add imagery ZIPs**, then
select the original Ridgeline overview ZIPs already on your phone (up to six at
once). Files attach in place: no extraction, copying or modification. Keep them
at the selected location. Detach removes app access without deleting your files.
The overview packs contain imagery; terrain needs network or a saved region.

Also includes bounded adaptive terrain interiors, a larger warm terrain GPU cache,
memory-pressure cleanup, fewer repeated bindings, reused frame storage and16bit
terrain indices. Original source heights, full boundary/skirt samples, imagery
resolution/filtering and previous MapLibre-style camera work are preserved.

The APK excludes all six large packs and session CA; retains67coarse images and
full-resolution online sources. Focused GPU/core checks, optimized three-ABI build
and signature pass. Native document-provider persistence and phone FPS remain
unverified. [Evidence](../validation/attach-adaptive/) ·
[implementation/research](../research/ADAPTIVE-TERRAIN-AND-RESIDENCY.md).

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
