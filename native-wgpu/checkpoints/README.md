# Native Android checkpoints — incomplete prototypes

Latest smaller build: [Ridgeline-Native-Base.apk](Ridgeline-Native-Base.apk).
Source checkpoint `3d29322`; signed debug Base APK, all three ABIs.
Includes corrected terrain-anchored pan/pinch direction, held/eased center height,
current-view HLOD ownership, close-up full-source detail selection and prioritized
progressive loading. Also includes the saved native planner/offline/location work.
Omits the six large overview ZIPs; retains67coarse overview images and original
full-resolution online sources. Detailed imagery and DEM require network or
saved regions. All six original ZIPs and older Full checkpoint remain available.

Focused input/selection/demand checks pass. Actual Android controlled pitched drag
and immediate-release checks pass; GPU LOD reversal is pixel-identical for the
same view. Highest LINZ aerial19/DEM18 inputs at default focus return200.
Real-terrain visual acceptance, full transition smoothing and phone performance
remain open. No session CA is included. Checksum/build scope are alongside it.
See [evidence](../validation/gestures-loading/) and
[MapLibre implementation study](../research/MAPLIBRE-GESTURES-HLOD.md).

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
