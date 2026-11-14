# 0.1.2 native loading APK

Source: `d831d2f3ddc9c301e0b2282fa58912d3940dd222`, branch
`native-renderer-performance`. Includes all native loading changes through
`0b936a0` and optional separate package/signing configuration.

The user requested an APK and instructed stopping further tests and pushing.
Final packaging ran **only `:app:assembleDebug`**, succeeding in 19 seconds.
The source checkpoint predates this artifact/documentation commit.

- New installation: `nz.ridgeline.nativeapp.performance`, label Ridgeline Native
  Performance, version `0.1.2-loading-checkpoint`, code 3.
- The original key was unavailable. The user authorized a new key and private
  Google Drive backup. Key and restore instructions were uploaded; readback
  showed only the owner permission and `shared=false`. No key material is in Git.
- Previous native baseline: `Ridgeline-Native-Performance-2026-10-31-Base.apk`,
  0.1.1, source `150ad30e5db269a549769fb011667c31ec975178`. The separate package
  preserves that app's data; its private caches/routes are not migrated.
- All three JNI libraries were built with Cargo's optimized release profile and
  stripped using NDK 29. Signature and ZIP16KiB/64-bit ELF16KiB alignment passed.
  ARMv7 ELF load segments retain 4KiB alignment.
- All 618 asset files are byte-identical to the previous Base, including all
  67 coarse images, catalog and gap atlas. No large packs or session CA.
- Before testing stopped: 39 Rust tests passed, 6 ignored. Android unit run:
  99 tests, 1 failure, 5 skipped. The unresolved failure is a
  `java.util.concurrent.TimeoutException` in
  `TileTransportTest.actualHttpDiskHitFinishesWhileAllForegroundNetworkSlotsAreBlocked`.
  No rerun; lint was stopped and no lint success is claimed.

See [release.json](release.json) for exact source, APK/library hashes, signer and
validation scope; [apk.json](apk.json), [signature.txt](signature.txt),
[badging.txt](badging.txt), [zipalign.txt](zipalign.txt) and
[assemble.log](assemble.log) for package evidence. No physical-phone execution,
installation, visual acceptance or app-wide speedup is claimed.

Independent earlier controlled host timings and their limitations remain in
[viewport loading](../../research/VIEWPORT-LOADING-AUDIT-2026-10-31.md) and
[latency barriers](../../research/LATENCY-BARRIERS-2026-11-01.md).
