# Native Android checkpoints — incomplete prototypes

Latest smaller build: [Ridgeline-Native-Base-a585548.apk](Ridgeline-Native-Base-a585548.apk).
Source checkpoint `a585548`; signed debug APK, approximately32MB, all three ABIs.
Omits the six large overview ZIPs; retains67coarse overview images and original
imagery resolution/terrain policy. Detailed imagery and DEM require network.
It contains the newer center-grounding code (28unit tests pass); Android execution
of that new grounding behavior remains pending. No session CA is included.

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

This remains approximately25percent of the complete migration. Reference visual
parity, real cross-LOD transitions, complete camera interactions, native planning
UI/features and physical-phone performance are not accepted. This APK exposes
the terrain prototype, not the completed app. See [GOAL.md](../../GOAL.md) and
[NATIVE_PROGRESS.md](../../NATIVE_PROGRESS.md) for outstanding work.

The APK is stored with Git LFS. Its checksum is in the adjacent `.sha256` file.
No debug keystore or session certificate is committed.
