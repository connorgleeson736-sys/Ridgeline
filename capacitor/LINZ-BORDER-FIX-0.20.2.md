# Capacitor 0.20.2 — LINZ border survey requests

The zoom-dependent blurry strip on the LINZ side has a concrete Android gateway failure. The published 0.20.1 APK rejects actual survey names before they reach LINZ. This is separate from 0.20.1's repaired imagery/DEM handoff.

## Cause and evidence

Boundary composition requests PNG alpha masks from individual LINZ surveys, for example `canterbury-2015-2016-0.3m` around Mount Cook. Both `TileAddress.local`'s survey URL parser and `TileAddress.tile`'s source-name validator allowed letters, digits and hyphens, **but not decimal points**. Resolution suffixes contain decimal points. The gateway therefore returned HTTP 403 for these valid localhost requests without making an upstream request. The composer refuses an incomplete boundary, so MapLibre retains its lower-detail parent rather than replacing the missing fine tile. Retrying cannot fix a deterministic address rejection.

Pure LINZ tiles use the accepted `aerial` name. Interior Esri tiles use their existing accepted route. The narrow boundary class needs the extra survey input and fails, including wholly LINZ pixel tiles admitted by conservative seam routing. Classification uses the currently requested tile footprint, explaining why the affected strip can move or change width with zoom. The symptom's exact handset extent has not been recorded.

Reproduction uses the production Java class, not a mocked browser gateway:

- Before: Cook survey rejected; **1/46** real catalogue survey names accepted. **45/46** contain decimal points.
- After: Cook survey accepted; **46/46** accepted, including constructor reconstruction for archive/parent identities.
- The installed-reference 0.20.1 APK DEX contains the rejecting expressions; its gap/composition assets match the generated app used for analysis.
- Sixteen sampled live original/survey responses at Cook at z17–19 returned HTTP 200, including sharp imagery on the LINZ side. These validate upstream availability in this environment, not the phone's network.

Earlier browser checks mocked `https://localhost/_ridge/**` and returned successful images directly. They did not execute `TileAddress.local`, so they could not expose this Android-only rejection. The earlier border regression used the same bypass. The new Java regression covers the actual failing class.

## Fix

Share a bounded survey-name expression supporting decimal resolutions between the URL parser and constructor. Keep origin, path structure, tile-coordinate and extension validation. Survey responses now advertise `image/png`, preserving their alpha purpose, instead of the generic imagery `image/webp` MIME. Source URLs, pixels, geographic masks, cache identities for valid sources and rendering/detail settings are otherwise unchanged. No cache wipe is needed: locally rejected requests never produced a successful cached composition.

Seven focused address tests pass, covering decimal source round trips/MIME and existing origin/path/coordinate validation. The catalogue probe is retained alongside before/after and sanitized live-response records in [validation](validation/linz-border-0.20.2/). No broad suite or renderer changes are needed for this correction. APK 0.20.2 (code25) uses the same Capacitor Audit package/signer and preserves app data; final package/assets/download checks are recorded beside the APK metadata. No physical-device execution was available.
