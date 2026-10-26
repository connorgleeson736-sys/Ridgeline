# Capacitor preview 0.3.0 validation

Validated 27 October 2026 (Melbourne). Development-signed preview; no Play Store release.

## Imagery evidence

- All 15 user-provided imagery ZIPs were read. Uploaded documentation was treated as export metadata, not execution instructions.
- Reconstructed the original authoritative 2,115,490-byte overview index from the export and repository catalogue. SHA256 exactly matches the pinned `0131124bab52e92164eb46f4e61ca1e41743cb1df68ce6a49a2cb35427c22da1`.
- Verified SHA256, WebP format and 256×256 dimensions of all **5,344** original overview images, covering **5,313 coordinates / 10,626 raw/natural mappings**.
- All **67** zoom 0–8 image files in the existing **0.2.0 APK** exactly match the uploaded export. They total **3,681,702 bytes** and are the downsampled overviews, not substituted 10 m imagery. Ordinary provider imagery may still appear outside their coverage or as a fallback.
- Repacked the missing **5,277** zoom 9–12 images into **six** data ZIPs without changing their bytes, resolution or colour. Additional payload: **364,762,502 bytes**. Every optimized file range was independently checked against the original image hash.
- The exact streaming SHA256/size verifier used by the Android importer accepted all six real data ZIPs in a desktop JVM. This verifies the ZIP stream/crypto path, not Android's document picker.
- Separate Cook-reference assets, duplicate coarse assets and intermediate overlays are excluded from the new data packs. The original general imagery shader and raw/natural variants remain.

## Software checks

- Android debug/preview builds and lint; JVM tests include archive streaming, tamper detection, truncation/excess-size rejection, download plans and resource addressing.
- Web packaging checks include local overview addressing and bounded file ranges, with no dependency on the original site's private archive endpoint inside the app.
- Website checks cover baseline rendering, phone performance/LOD, Topo protocol and overlays, and the new Topo native-resolution policy. Existing aerial LOD/resolution behaviour is retained.
- Chromium checks exercise the actual 3D Direct renderer, route/profile and rectangle workflows, precise SVG GPS centring, Topo DPR/zoom/sampling, and restoring aerial zoom limits. Provider fixtures are controlled; archive-backed runs read the actual verified overview files.
- Preview signatures, ZIP CRC and signer continuity with 0.2.0 are checked before delivery. No emulator validation is requested or performed for this update.

## Phone checks still needed

Test Android's multi-file document picker and import UI, full offline use after restart, GPS/gesture behaviour and memory/storage limits on the phone. GPS positioning was confirmed working by the user in the previous app; its location logic is preserved in this update. Desktop/JVM/browser checks do not replace these phone checks.

The printed Topo service is a raster map with finite detail. The changes remove photo-oriented degradation and excessive enlargement; they do not manufacture additional survey detail.

Website source changes are committed and provided in a four-file deployment ZIP. The original live Site was **not deployed** by this cloud session: its host is blocked by the environment's outbound policy and no Site deployment connector is available. Apply the patch through the original Site editing/deployment session.
