# Capacitor additional-audit APK

[Download 0.20.0 Audit Base — 26.9 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/capacitor-latency-audit/capacitor/checkpoints/Ridgeline-Capacitor-Additional-Audit-0.20.0-Base.apk)

Install as **Ridgeline Capacitor Audit**, Android 8.0+. Updates **0.19.0 Audit**
in place using the same package and signer, retaining its private data.
This is the Capacitor MapLibre app. The original Performance app remains separate
because its signing key is unavailable; keep it installed to retain its data.

All four additional audit findings are implemented: avoid empty Direct overlay
textures with continuity ownership, recover sharp tiles after coarse fallback,
transfer visible composites as owned bitmaps, and skip unused touchmove picking.
Also includes LINZ/Esri seam-donor recovery and **Layers → Advanced → Automatic
disk cache**, 0–1,000 MB (1 GB), default 256 MB. The shared original-response HTTP
cache gets the selected allowance, reduced if free space is low. Changes apply
after a full app restart. Existing elevation PNG caching remains; the requested
new prepared terrain/mesh cache has been removed. Saved regions stay separate.

Base retains the same 67 coarse overviews; import the six larger overview packs
or download regions for further offline coverage. All 1,134 original imagery/data
assets, imagery resolution and terrain-detail defaults are preserved.

Source `70a3f0b48f8f2f6adb1a7af99dba910d10b2dd22`, code 23,
`0.20.0-latency-audit`. APK 26,928,036 bytes; SHA-256
`27590a11c2d120b517009bbbfed6a47d9e96043f4e673b1ed640a450908dee45`.

Focused checks, normal assembly/release-vital checks, signature, package/version,
ZIP alignment, engine provenance and original-asset comparisons pass. No handset
execution or overall phone speedup is established.

[Implementation report](../PERFORMANCE-ADDITIONAL-AUDIT-2026-11-04.md) ·
[Exact APK record](../validation/additional-audit-2026-11-04/apk/release.json) ·
[SHA-256 file](Ridgeline-Capacitor-Additional-Audit-0.20.0-Base.apk.sha256)

Previous: [0.19.0 Audit Base](Ridgeline-Capacitor-Latency-Audit-0.19.0-Base.apk).
