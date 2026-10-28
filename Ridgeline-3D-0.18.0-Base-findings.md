# Ridgeline Performance 0.18.0 Base

[Download Base APK](Ridgeline-3D-0.18.0-Performance-Base.apk) · [Validation](Ridgeline-3D-0.18.0-Base-validation.json) · [All performance findings](Ridgeline-3D-0.18.0-findings.md)

The smaller Base installer includes all 0.18.0 optimisations with the same Original + Direct renderer, image resolution and terrain detail as Full. Source: `d880143418ac46ece98adff0b3b2fbaedf044d46`, branch `android-performance-0.18.0`. Built using `RIDGELINE_BUNDLE_OVERVIEWS=0 npm run sync --prefix capacitor`, then `assemblePerformance`.

Base leaves out the six large overview imagery packs (364,762,502 bytes). It retains the 67 original coarse overview images at zooms 0–8. Additional imagery is available online, through existing saved regions, or by importing the existing six overview ZIPs. Removing bundled imagery changes offline availability; it does not reduce the resolution of imagery that is loaded. Elevation and fine imagery still require the relevant downloads for offline use.

Package `nz.ridgeline.mobile.performance`, version `0.18.0-performance`, code 21. Same signer and package/version as 0.18.0 Full. Install over the Performance app to retain its private data. Switching from Full to Base removes the APK-bundled packs; it keeps separately saved regions, routes and settings. The normal Ridgeline app remains separate.

## Verification

Compared with the published 0.18.0 Full APK, exactly six pack entries are absent and only `assets/public/index.html` and `assets/public/overview-manifest.json` differ among shared entries. All app code, native bytecode, Android manifest, workers, shader/detail policies and all 1,133 remaining image/binary assets are byte-identical. Every packaged web asset matches the generated Base build.

Android assembly/lint, 55 mobile JavaScript tests and the Base browser workflow pass. APK v2 signature, same Performance signing certificate, and ZIP alignment including 16 KiB verified. Native code is identical to the Full build that passed 90 JVM/native tests. No new device speed measurement is claimed.
