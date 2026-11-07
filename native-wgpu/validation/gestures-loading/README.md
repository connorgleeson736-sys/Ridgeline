# Anchored gesture and close-detail checkpoint

- Native Android30 GLES synthetic/offline drag: 0.02348m point drift. Immediate
  release before async anchor callback: 0.09506m. `android-drag.txt` records result.
- Focused Kotlin checks: 3 CameraMath tests (32 pitch/bearing/density cases plus
  pinch), 3 SourceDemand tests. Final source-order check passes.
- Pure cover tests: 2 demand ownership/fallback regressions pass.
- GPU replay: coarse/fine/coarse demand returns identical coarse pixels,
  unavailable siblings retain parent pixels, settled frame transfers no changed
  scene/instance/route data. Existing full-resolution/mip/seam checks also pass.
- Before close-detail fix: camera19/pitch0/320x640/elevation3670/geometryBudget36
  selected center geometry16. After distance/focus correction: geometry20/21
  (full DEM18), imagery19 at pitch0/45/58/75/80, same leaf budgets. `close-detail.txt`.
- Real LINZ aerial19 and DEM18 at default focus return200, original256px;
  DEM fully opaque. `providers.json` records body hashes without key-bearing URLs.

No phone benchmark, real-terrain visual acceptance, completed HLOD geomorph or
imagery crossfade is claimed. Android gesture fixture is explicitly synthetic;
host provider access is a separate observation. Normal APK has system trust only,
all3ABIs and no six large imagery packs. Original packs remain untouched.
