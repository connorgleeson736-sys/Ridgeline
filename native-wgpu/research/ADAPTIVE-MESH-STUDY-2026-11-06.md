# Adaptive mesh study — 6 November 2026

Question: what could adaptive terrain meshes save in the native app? There are
two different savings, with different bottlenecks:
- **GPU:** fewer triangles inside a terrain tile.
- **Network:** fewer elevation downloads where coarser data already meets the
  error budget.

All data are live LINZ terrain-RGB tiles. The tools and raw results are in
`validation/adaptive-mesh-study-2026-11-06/`. No device was available.

## Current behaviour

`renderer/src/adaptive.rs` and `core/src/adaptive.rs` choose one grid per
terrain tile (128, 64 or 32 cells). The choice uses the tile's **worst** cell
residual against a 0.6 screen-pixel budget plus a 10-pixel maximum cell size,
and outer edges stay at full resolution for seams. One sharp feature forces the
whole tile to full density. Geometry tiles at zoom *z* use elevation at *z−1*,
capped at zoom 18. Capacitor's terrain source is also capped at 18.

## A. Local adaptive meshes (GPU)

The 5 November Capacitor research
(`capacitor/LATENCY-BREAKTHROUGHS-2026-11-05.md` §4) measured a local quadtree
mesh on real LINZ fields at the same 0.6-pixel budget, with fine borders kept:

| Area | Change in triangles |
| --- | --- |
| Total, 27 cases | 834,664 → 452,066 (**−45.8%**) |
| Cook | −29% |
| Rolling Pin | −48% |
| Wellington | −65% |

Native uses the same per-tile rule, so the result transfers. This lowers GPU
vertex work (frame rate, battery) and does **not** change downloads.

## B. Skipping elevation refinement (network)

**Per place.** For each zoom, a tile was compared with its parent upsampled. The
tolerance is 0.6 px at the view where that elevation feeds its geometry level.
The fraction of the tile changing by more than the tolerance:

| Place | Result |
| --- | --- |
| Aoraki/Mt Cook | 23–56% at every zoom 12–18 |
| Rolling Pin | ≤0.5% from z14 |
| Fiordland | ~0.1% from z16 |
| Canterbury Plains | 0% at all zooms |
| Wellington / Tongariro | 0.2–4% from z13 |

**Predicting before download** (160 random tiles over NZ land boxes, z14–17).
The rule skips the child when the detail the parent added over the grandparent is
at most *k* times the parent tolerance:

| *k* | Requests skipped | Bytes skipped | Wrongly skipped |
| --- | --- | --- | --- |
| 0.25 | 36% | 2.6% | 0 |
| 0.5 | 41% | 5.3% | 1 (1.07× tolerance) |
| 1.0 | 54% | 14.9% | 10 (up to 6× tolerance) |

Of the 58 safe skips at 0.25, **48 were all-zero sea tiles**. On land the safe
rule skips 9% of requests and 1.4% of bytes. Non-alpine land changes little in
most pixels, but small features (cliffs, embankments) exceed the budget, so
whole-tile skipping on land is not lossless.

**Above zoom 18.** LINZ serves elevation and aerial imagery at z19–21:
- Elevation adds about 0.1–0.3 m there (quantisation level), except Mt Cook at
  z19 (0.7 m p99).
- Aerial imagery adds real detail at z19–20 in cities (PSNR against an
  upsampled parent 27–32 dB), little in rural or alpine areas at z20+ (≥38 dB).

The existing caps (elevation 18, LINZ imagery 19) are appropriate for this app.

## Conclusions

1. **GPU:** local adaptive meshes are the substantial saving, at about 46% fewer
   triangles. Worth implementing as a renderer change, with on-device seam and
   frame-time checks.
2. **Network, sea:** an exact, cheap saving. When a parent elevation quadrant is
   entirely sea (all zero), its children are skipped and the parent drawn. In
   coastal views this removes a large share of elevation requests.
3. **Network, land:** predictive skipping is not worth the risk; savings are
   small (9% of requests, 1.4% of bytes) compared with the risk of losing detail.
4. **Bigger network wins remain elsewhere:** pre-built meshes or an edge bundle
   service (one request per tile), and the transport recovery in 0.1.16.

This sample is small (6 places, 160 random tiles) and the 0.6-pixel tolerance is
a nadir approximation. Pitched views near the camera are more sensitive.
