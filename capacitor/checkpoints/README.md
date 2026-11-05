# Capacitor LINZ border fix APK

[Download 0.20.2 Audit Base — 26.9 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/capacitor-latency-audit/capacitor/checkpoints/Ridgeline-Capacitor-LINZ-Border-Fix-0.20.2-Base.apk)

Install over **Ridgeline Capacitor Audit** without uninstalling. Same Audit
package/signer, version code 25; preserves routes, downloads, settings and cache.

Fixes an Android gateway rejection affecting the zoom-dependent blurry strip on
the LINZ side of the imagery border. LINZ survey names contain decimal resolutions
such as `canterbury-2015-2016-0.3m`; the old validator rejected the period and
returned local 403 before contacting LINZ. Border composition then retained its
coarse parent indefinitely. Of 46 actual catalogue survey names, 45 were rejected.
The corrected parser/constructor accepts all 46 and serves survey PNGs as PNG.

Seven focused Java address tests pass. All 1,218 public assets, including 1,134
image/data/GeoJSON assets, are byte-identical to 0.20.1: the renderer, shaders,
source masks and detail remain unchanged. Retains the sharpening handoff fix,
0–1 GB original-response cache slider and existing saved maps; no cache wipe.
No physical-device execution or measured phone speedup is claimed.

Source `d3e3340`, version `0.20.2-latency-audit`. APK 26,928,608 bytes;
SHA-256 `3f9d38cd8f519fbdf2820e4c78f8a62db4d4045fed66ca2dfac7007719904f75`.

[Cause and evidence](../LINZ-BORDER-FIX-0.20.2.md) ·
[Exact release record](../validation/linz-border-0.20.2/release.json) ·
[SHA-256 file](Ridgeline-Capacitor-LINZ-Border-Fix-0.20.2-Base.apk.sha256)

# Previous Capacitor 0.20.1 sharpening-fix APK

[Download 0.20.1 Audit Base — 26.9 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/capacitor-latency-audit/capacitor/checkpoints/Ridgeline-Capacitor-Sharpening-Fix-0.20.1-Base.apk)

Install as **Ridgeline Capacitor Audit**, Android 8.0+. Updates **0.20.0 or 0.19.0
Audit** in place using the same package and signer, retaining its private data.
The original Performance app remains separate because its signing key is
unavailable; keep it installed to retain that app's data.

Fixes coarse imagery remaining on screen after finer tiles have loaded. Imagery
readiness/quality checks now follow the visible native tile selection; terrain
handoff uses the DEM zoom MapLibre actually requires. A delayed-loading rendering
replay finishes with all requested native terrain patches drawn and no stuck
coarse covers. Four focused regression checks pass. No handset performance claim.

Retains the four additional audit fixes, LINZ/Esri seam recovery, and **Layers →
Advanced → Automatic disk cache**, 0–1,000 MB (1 GB), default 256 MB. Changes apply
after a full app restart; low free space can reduce the effective allowance.
Original provider responses share that cache, including existing elevation PNGs.
Saved regions are separate. No new prepared terrain/mesh disk cache is included.

Base retains 67 coarse overviews; import the six larger overview packs or download
regions for further offline coverage. All 1,134 original imagery/data assets and
all public assets except the two sharpening-fix modules match 0.20.0 byte-for-byte.
The rendering engine, workers, shaders and detail defaults are unchanged.

Source `a2a71cd4365c5d528ef62d2b3474c8cb095119f3`, code 24,
`0.20.1-latency-audit`. APK 26,928,468 bytes; SHA-256
`b12d6f09947bf6d00c7641b8914521c6361c35af273b7b6bafdda58334ceb417`.

Normal assembly/release-vital checks, signature, package/version, ZIP alignment
and original-asset comparisons pass. No broad test suite or full lint was invoked.
No handset execution or overall phone speedup is established.

[Sharpening fix and evidence](../SHARPENING-FIX-0.20.1.md) ·
[Exact APK record](../validation/sharpening-0.20.1/apk/release.json) ·
[SHA-256 file](Ridgeline-Capacitor-Sharpening-Fix-0.20.1-Base.apk.sha256)

Previous: [0.20.0 Audit Base](Ridgeline-Capacitor-Additional-Audit-0.20.0-Base.apk) ·
[0.19.0 Audit Base](Ridgeline-Capacitor-Latency-Audit-0.19.0-Base.apk).
