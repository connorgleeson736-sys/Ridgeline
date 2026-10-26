# Authentic LINZ vector tile fixtures

Downloaded 2026-10-20 from LINZ Basemaps `topographic-v2/3857/14/{x}/{y}.pbf` using the existing Ridgeline Basemaps key. Keys are intentionally absent from these fixtures and documentation.

- 15935/10400: Aoraki/Mount Cook summit labels and contours.
- 15933/10408: Hooker Track, Hooker River, Stocking Stream.
- 15931/10408: Mueller Hut.
- 15937/10429: Lake Pukaki.

Original gzip-compressed PBF responses preserved. LINZ CC BY 4.0: https://www.linz.govt.nz/ . Vector schema: https://basemaps.linz.govt.nz/docs/user-guide/working-with-vector-tiles/usage/ .

`python checks/read-topographic-fixtures.py` regenerates extracted properties without third-party dependencies. The production viewer uses native MapLibre decoding, not this fixture reader.
