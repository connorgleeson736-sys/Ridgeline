# Elevation cache and invalidation work — 0.16.0

## Duplicate elevation persistence

The app routed elevation through WebView CacheStorage before the shared native HTTP cache. The map path raced a CacheStorage lookup against a short timer and cloned/wrote responses back into a browser cache; the profile worker also opened/matched/wrote that namespace before using native fetch. Its storage lookup had no equivalent timeout. Native canonical provider URLs already share the bounded 256 MB HTTP cache and in-flight requests.

Production map elevation now delegates directly to native transport. Profile-engine defaults disable the additional browser disk cache while retaining its decoded-height memory LRU. This removes duplicate storage lookup/write/enumeration and response-clone work. Explicitly supplied test/tool cache implementations remain supported. Old browser auto-cache entries are not deleted by this update; they stop receiving production reads/writes and remain subject to browser storage management.

Downloaded regions remain in the native persistent store. Imported/bundled imagery packs and the separate explicitly saved NZ overview remain supported. Provider freshness, offline-only gating, cached-body validation and saved-ancestor fallback remain owned by the existing native gateway. The terrain decoding/sampling math and source resolutions are unchanged. Tests verify zero default CacheStorage opens, original response bytes/headers, unchanged decoded height values and memory-cache reuse.

## Tile-arrival invalidation

For imagery and vector content events, the terrain adapter previously merged active and warm tiles into temporary arrays, then calculated texture cost for every tile before checking geographic overlap. Most unrelated patches could not possibly be invalidated.

The app now selects related active/warm candidates first, without the intermediate warm-entry/map/merged arrays, and inspects RTT cost only for those candidates. It still scans geographic relationships; this is not an O(affected-only) spatial index. The same equality/ancestor/descendant tests, fingerprint source-membership filters, dirty marks, refresh budgets and native generic/style invalidation remain intact. Candidate order is unchanged.

Fixtures compare the website adapter and generated app adapter across active and warm tiles, vector content, hidden providers, parent tiles and unrelated arrivals. One affected patch among 1,000 causes one RTT-size read rather than 1,000; a parent covering two causes two, and an unrelated arrival causes zero. Invalidation counts, ignored-event counts, releases and warm counts match.

An illustrative Node CPU fixture (500 imagery events, 1,000 resident patches, one affected patch; median of five) changed from about **34.3 ms to 13.5 ms**, with the same 500 invalidations. An empty-RTT variant also improved in the fixture (approximately 36.7 ms to 19.0 ms), with zero invalidations preserved. An earlier generator/iterator version was slower and was discarded. These measurements do not establish physical-phone FPS.

## Verification and scope

Versioned APK validation records contain final test counts. Coverage includes the prior real pinned-engine lifecycle fixture, new before/after invalidation tests, elevation ownership/cache tests, browser profile/route/offline/Topo/overview and transport checks, Android tests/lint, signing and APK comparisons. Original + Direct, source zooms, terrain mesh/detail policies, filtering and the stable movement drawing buffer remain unchanged.

No physical phone comparison with Chrome has been performed. The changes remove verified work; they do not justify claiming all bottlenecks are fixed.
