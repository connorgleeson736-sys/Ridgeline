# Performance 0.17.0 — Full imagery APK

This packages the same 0.17.0 Performance app with all six original overview packs embedded. It uses **`nz.ridgeline.mobile.performance`**, version **0.17.0-performance / code 20**, and the same development signer as the Performance Base APK. Installing it over Performance Base preserves that app's data. The separate normal Ridgeline app remains untouched.

The six packs add **364,762,502 bytes** of verified, unchanged imagery: **5,277 original 256×256 WebP images**, covering 5,255 additional coordinates and 10,510 raw/natural mappings at zooms 9–12. With the existing 67 coarse images at zooms 0–8, the Full APK includes all **5,344** original overview images. Coverage is South Island, Rakiura and nearby islands. Offline elevation and finer imagery still require selected-region downloads.

Packs are stored uncompressed inside the APK for direct range reads. No separate download, import or first-launch extraction is required. This adds storage, not a requirement to load all imagery into memory. Renderer, terrain detail, image pixels and all performance fixes are identical to Base.

## Verification

- Original six ZIP checksums, extracted pack lengths and SHA256 values match the pinned catalogue.
- All 5,277 distinct added WebPs were decoded and verified at 256×256, with valid bounded catalogue ranges.
- The actual Full APK contains all six expected `.bin` assets uncompressed; each embedded SHA256 and byte count matches the catalogue.
- Base/Full ZIP comparison: only `index.html` and `overview-manifest.json` differ, with exactly six pack entries added. All native code, shaders, workers and other app resources are byte-identical.
- **55 JavaScript tests and 90 native/JVM tests pass.** Full assembly and lint pass. The updated browser fixture handles both Base and Full; the Full workflow passed with **25 actual verified pack-image reads**, 293 controlled provider requests, and no page/console errors.
- APK v2 signature, same signer as Performance Base, package/version/launcher metadata and ZIP alignment verified. No physical-device or emulator execution is claimed.

Build with the verified six `.bin` files under `capacitor/overview-data/` (ignored by Git):

```sh
RIDGELINE_BUNDLE_OVERVIEWS=1 npm run sync --prefix capacitor
cd capacitor/android
./gradlew :app:assemblePerformance :app:lintPerformance :app:testPerformanceUnitTest
```

The exact signed APK checksum, size and pack verification are published beside the APK on `android-preview-download` in the Full validation record.
