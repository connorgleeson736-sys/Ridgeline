# Additional Capacitor audit checks

Source commit `d880143418ac46ece98adff0b3b2fbaedf044d46` from `connorgleeson736-sys/Ridgeline`, branch `android-performance-0.18.0`. Reviewed 4 November 2026.

Read FINDINGS.md first. These are isolated audit experiments, not a production patch. No Android, browser pixel comparison or GPU benchmark is included.

With Node 24 installed, pass the repository checkout path explicitly.

```sh
node check-empty-overlay.cjs /path/to/Ridgeline
node check-empty-overlay.cjs /path/to/Ridgeline --candidate
node check-touch-events.mjs /path/to/Ridgeline
node check-tile-lifecycle.mjs /path/to/Ridgeline
node check-composite-stages.mjs /path/to/Ridgeline
```

`check-empty-overlay.cjs` adapts the repository's own `checks/maplibre-direct.cjs` fixture and loads the Capacitor-optimized residency module. It uses real preparation/dispatch and fake GPU resources. The candidate is an in-memory change only. It tests first-frame allocation calls, subsequent route arrival, missing-coordinate continuity and confirmed empty-bucket removal.

`check-touch-events.mjs` uses the real map event handler and raycast with a synthetic DEM, then verifies ordinary, one-time and parent listeners. Its timings are host CPU observations only.

`check-tile-lifecycle.mjs` verifies the actual native completion event, selection coalescing, and the handling of a simulated successful no-store fallback image through the real cache, queue, raster loader and retry controller. GPU upload and decoding are stubbed. It does not execute Android Java.

`check-composite-stages.mjs` counts calls through the actual compositor and queue using mocked Canvas/image primitives. Its bitmap candidate is not an integrated protocol/cache implementation or a pixel-equivalence test.

`RESULTS.txt` contains one successful run. `SOURCE-SHA256.txt` fingerprints the reviewed input files. Other repository dependencies are obtained from the pinned checkout. No application source or credentials are bundled.
