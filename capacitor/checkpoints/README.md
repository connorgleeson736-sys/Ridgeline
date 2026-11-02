# Capacitor latency-audit APK

[Download 0.19.0 Latency Audit Base — 26.9 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/capacitor-latency-audit/capacitor/checkpoints/Ridgeline-Capacitor-Latency-Audit-0.19.0-Base.apk)

Install as **Ridgeline Capacitor Audit**, Android 8.0+. This is the Capacitor
Original + Direct renderer with all six audit changes, not the Rust renderer.
Selection interval defaults to 100 ms.

The original Capacitor signing key is unavailable. This APK therefore uses a
separate package `nz.ridgeline.mobile.performance.latencyaudit`, signed with the
existing backed-up development key. It installs alongside the original app and
has its own cache, routes, settings and offline regions. Keep the original app
installed to retain its data. Future audit APKs can update this audit app.

Base includes the same 67 coarse overviews as 0.18.0 Base; the six large overview
packs are not bundled. Import those packs or download regions in the audit app
for further offline coverage. Imagery resolution, terrain-detail defaults and
all 1,134 original image/binary/GeoJSON assets match 0.18.0 Base byte-for-byte.

Source `2b9aefb41b1e252def846c6b1e526e42bee5f1e8`, code 22,
`0.19.0-latency-audit`. APK 26,923,164 bytes; SHA-256
`7b12d894d3c96929c62c3763b1ed79394f7a1af7606aa6a54de8811ae8e71020`.

Assembly, automatic release-vital lint, signature, package/version, ZIP alignment,
worker inclusion and original payload comparison pass. No extra test suites or
full lint were invoked for packaging. No physical-device execution or phone
speedup established.

[Implementation report](../PERFORMANCE-LATENCY-AUDIT-2026-11-03.md) ·
[Exact APK record](../validation/latency-audit-2026-11-03/apk/release.json) ·
[SHA-256 file](Ridgeline-Capacitor-Latency-Audit-0.19.0-Base.apk.sha256)
