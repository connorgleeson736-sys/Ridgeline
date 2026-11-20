# Capacitor full latency audit APK

[Download 0.21.0 Full Audit Base — 27.0 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/claude/youthful-hypatia-lrzouw/capacitor/checkpoints/Ridgeline-Capacitor-Full-Audit-0.21.0-Base.apk)

**A separate app, "Ridgeline Full Audit"** (`nz.ridgeline.mobile.performance.fullaudit`).
The original Audit signing key was not available, so this APK is signed with a
new key. It installs alongside 0.20.3 Audit and every other Ridgeline app and
does not touch their data. It starts with its own empty routes, downloads and settings.

Contains every finished, tested latency improvement from the
[full audit](../PERFORMANCE-FULL-AUDIT-2026-11-06.md), on top of 0.20.3:

- Off-screen reloads that still hold a texture/elevation no longer keep
  image-download slots past the off-screen allowance. Their transfer is
  cancelled, the old data stays visible, and the tile refreshes when it returns.
- Survey seam masks download as WebP instead of PNG: 89% fewer bytes. Alpha was
  identical on 1,064 live tiles across all 46 surveys. PNG is still used as a
  fallback and for offline regions.
- Full detail returns as soon as movement ends instead of 350 ms later.
- South Island catalogue loads 302 → 59 ms (Chromium, 4× CPU throttle).
- Native storage, cache and provider connections are warmed during launch.
- A dropped download no longer scans the whole HTTP disk cache.

All 1,140 image/data/GeoJSON assets and the MapLibre engine are byte-identical
to 0.20.3. Imagery resolution, terrain detail and the renderer are unchanged.
98 Java unit tests, lint, the new JS tests and the browser check pass.
No physical-device run, so no phone speedup is claimed.

Version `0.21.0-full-audit`, code 27. APK 26,974,363 bytes;
SHA-256 `b55aee147836e618ca7394fd020b11cbc7a992d31d22d9c3f22361e769ab33da`;
signer SHA-256 `c4e30ed357e8ed59b0bbc6cbd5725277cd196bfb091a9f3b07e270b91300fe46`.

[Audit and measurements](../PERFORMANCE-FULL-AUDIT-2026-11-06.md) ·
[Exact APK record](../validation/full-audit-0.21.0/release.json) ·
[SHA-256 file](Ridgeline-Capacitor-Full-Audit-0.21.0-Base.apk.sha256)

# Previous Capacitor 0.20.3 border recovery APK

[Download 0.20.3 Audit Base — 27.0 MB](https://media.githubusercontent.com/media/connorgleeson736-sys/Ridgeline/capacitor-latency-audit/capacitor/checkpoints/Ridgeline-Capacitor-Border-Recovery-0.20.3-Base.apk)

Install over **Ridgeline Capacitor Audit 0.20.2** without uninstalling. Same
package/signer, code 26; keeps routes, downloads, settings and cache.

Fixes border tiles waiting for unused Esri inputs, including incorrect fallback
quality inherited from an unused donor. Mixed tiles release their preparation
slot during network waits. Original LINZ tiles outside the replacement polygon
bypass donor requests. Worker errors retain bounded recovery while still wanted.

The dotted overlay now follows measured source masks, removing the numerous
nominal Rolling Pin outlines. Display contours remain approximate at close zoom.
All 1,134 original photo/data assets, masks, the MapLibre engine, shaders and
source detail remain unchanged. Retains the decimal survey-name fix, sharpening
handoff fixes and 0–1 GB original-response cache slider. No cache wipe is needed.

With real Rolling Pin photo inputs and an **injected five-second Esri delay**,
the LINZ-only result became ready in 83.1 ms instead of 5,098.6 ms, with identical
pixels and no false fallback flag. Required-source failure, cancellation, worker
loss, no-worker output and processing-slot checks pass. These are controlled
host results, not phone measurements. Five focused JS checks, seam-source checks,
independent source-mask verification and APK checks pass. No physical-device run.

Source `f92dade`, version `0.20.3-latency-audit`. APK 27,005,763 bytes;
SHA-256 `cb22610e28528e9f7eaeb1a29faac6a5da166aa2f480ed6add695e068a2d66de`.

[Implementation and limits](../BORDER-RECOVERY-0.20.3.md) ·
[Exact APK record](../validation/borders-0.20.3/release.json) ·
[SHA-256 file](Ridgeline-Capacitor-Border-Recovery-0.20.3-Base.apk.sha256)

# Previous Capacitor 0.20.2 LINZ border fix APK

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
