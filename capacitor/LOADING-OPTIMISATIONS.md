# Loading and caching — Android 0.13.1

The website's renderer already supplies scheduling, JavaScript cancellation, prepare-ahead loading, encoded-input caching, texture reuse and composition-plan reuse. Version 0.13.0 improves those systems' integration with Android and adds bounded native storage/transfer improvements. It does not introduce another renderer or lower source detail.

## Changes

| Area | Existing behaviour | Android 0.13.0 improvement |
| --- | --- | --- |
| Cancellation | JavaScript could stop waiting while intercepted native HTTP continued | Random per-consumer IDs carry cancellation to native calls; the last subscriber cancels a transfer. Other map/download consumers keep their transfer |
| Priorities | Renderer scheduling acted before native interception | Dynamic priorities reach native admission, including promotions when a visible tile joins a source input first requested by prefetch |
| Delivery | Native interception collected the whole online body before responding | Online response headers and bytes stream earlier. Successful EOF is withheld until size/format validation passes. APK/imported imagery ranges stream directly from bounded file descriptors |
| Decoding | Encoded inputs and composition plans were reused | The imagery worker also reuses exact decoded inputs by SHA256, within a 2 MiB retained-cache limit |
| Prediction | Website directional warmup and expanded-view preparation already existed | Obsolete directional predictions cancel on a new gesture, visibility changes and foreground backpressure. Speculative-only cancellation skips the ordinary reuse grace |
| Offline lookup/storage | Individual files in region directories, with a recent-path memo | SQLite indexes coordinates and region ownership. Identical payloads share a content hash; different versions remain distinct |
| Region downloads | Each region fetched tiles serially | Three bounded producers share native admission, commit small batches and honour retry/backoff and cancellation |

Version 0.13.1 removes the additional native foreground-transfer cap. The renderer's existing loading controls (8 moving / 12 stationary by default, configurable up to 96) govern image demand without a second eight-slot restriction underneath them. Foreground requests start independently on reusable idle-expiring threads, so fresh OkHttp disk-cache hits no longer queue behind unrelated network transfers. Cache lookup, expiry/revalidation, validation, streaming and cancellation remain unchanged. At most two background transfers are admitted; estimates precede downloads and speculative preparation. The existing 512-entry outstanding-request guard remains for runaway demand, rather than imposing a normal transfer limit. This is not a claim of measured physical-device speedup.

A main-page control object exposes only begin/priority/cancel operations for bounded random request IDs. Workers forward these messages through their parent, and worker termination releases its requests. The interface is installed before the first document loads. Provider URLs remain allowlisted; control headers are not forwarded upstream and do not fragment the provider HTTP-cache key. No base64 image bridge is used.

## Delivery, caching and quality

A shared encoded body supplies independent readers. Bytes can arrive early, but malformed/oversized responses cannot finish successfully. Invalid successes are evicted from the native HTTP cache. Whole-tile readers (`arrayBuffer`/`blob`, including decorated clones) can recover the existing saved-ancestor fallback after a mid-body failure. Failed direct-stream CacheStorage copies are discarded. Coarse fallbacks remain non-cacheable.

An absolute upstream expiry accompanies streamed responses and is applied when body consumption finishes; a slow body or native disk-cache hit does not restart its lifetime. The elevation CacheStorage path respects that absolute deadline. The removable native HTTP cache remains **256,000,000 bytes** of accounted entries, with small filesystem/journal overhead. Provider cache rules, revalidation and credential separation remain in force. Downloaded-only mode bypasses this online cache.

Original + Direct, the MapLibre engine/worker, Direct sampling, terrain/detail policies, imagery grading, blending math, source tile zooms and lossless PDF pixels are preserved. The retained Topo enhancement still requests zoom-18 source tiles with 128 px source sizing on dense screens. Cook reference substitution remains excluded.

The decoded-image cache is worker-only. Hashing includes the exact encoded bytes, so changed imagery produces a new entry. Real ImageBitmap objects remain usable by canvas. Reference counting delays native close until all active consumers release an evicted entry. Memory trimming clears unused cached bitmaps; in-use images remain valid. Crop/resize decode overloads pass through unchanged. This adds a bounded retained pool, not a larger renderer/GPU budget.

## Offline storage and migration

`tile_blobs` stores encoded bytes by SHA256; `region_tiles` records each region's coordinate-to-payload ownership. Removing a region cannot remove data owned by another. Different imagery versions at the same coordinate remain byte-exact for their respective saved regions/PDFs. Indexed size queries avoid reading bodies merely to estimate their lengths, and resume loads the saved-size index in one pass.

SQLite uses WAL, foreign keys and full synchronous writes. New databases use automatic vacuuming; removing unreferenced payloads reclaims database space, with best-effort WAL truncation. Large payloads use bounded SQL slices to respect older Android CursorWindow limits. A database-corruption handler retains existing files rather than silently deleting/recreating them.

Migration is idempotent and bounded to 16 entries or approximately 4 MiB per batch (an individual tile may reach the existing 8 MiB limit). Each batch is committed and durably checkpointed before its original files are removed. Existing files remain readable during migration. Interrupted writes, busy checkpoints, insufficient space and interrupted cleanup preserve readable originals for a later retry. This avoids temporarily duplicating an entire region. Region deletion and generation checks prevent stale workers/migration from recreating removed ownership.

Once migrated, saved maps require version 0.13.0 or later. Region names, bounds, logical byte counts, routes and imagery imports remain intact. Overlapping regions can report the same logical tile bytes while sharing their physical payload. The existing 30-region/12,000-resource limits and Topo compatibility rules remain.

Download production is bounded to three outstanding futures, with at most two admitted background HTTP transfers globally. Completed futures are released immediately, and commits use bounded batches. Pause/resume/removal still check the region generation at commit. Permanent provider failures are not retried; 429/5xx responses honour Retry-After, with long requested waits left for an explicit resume. Uncommitted tiles may be fetched again after interruption; committed tiles remain resumable.

## Verification

- Real local HTTP tests cover sharing, priority, cancellation, streaming before completion, validated EOF, malformed/oversized responses, cache expiry/revalidation and restart persistence.
- Real SQLite tests cover byte equality, overlapping ownership, distinct source versions, transaction rollback, concurrent readers/writers, storage reclamation, large payloads and migration interruption/retry/deletion.
- Browser tests exercise real workers, private control headers, priority promotion, aborts, worker termination and response clones with a mocked Java control object. Eight imagery-composition comparisons match the reference RGBA pixels exactly; the cached path performs two input decodes and records 16 reuse hits in that fixture.
- Packaging tests preserve the graphics modules and high-density Topo policy. The full workflow check exercises the real WebGL engine with controlled provider data and the existing mocked Capacitor bridge. APK signatures, Base/Full code equality and all six imagery-pack hashes are checked separately.

`await RidgeMobile.loadingStats()` exposes native admission/cancellation/sharing and HTTP-cache counters for manual device profiling. Counters contain no provider URLs or API keys.

The browser control mock and JDBC SQLite backend do not substitute for an Android-device runtime test. Instrumentation tests compile; no emulator run or physical-phone speed percentage is claimed. Provider latency, actual HTTP/2 negotiation, decoding and graphics-driver costs still affect phone performance. The versioned APK validation record contains the final results and hashes.

## Research references

- [Android WebView interface threading, injection and lifetime](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/webkit/WebView.java)
- [Android WebView interception contract](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/webkit/WebViewClient.java)
- [OkHttp Call cancellation](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/Call.kt)
- [OkHttp streaming bodies and resource ownership](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/ResponseBody.kt)
- [Android SQLite WAL and transaction APIs](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/database/sqlite/SQLiteDatabase.java)
- [Android CursorWindow](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/database/CursorWindow.java)
- [ImageBitmap resource release](https://github.com/mdn/content/blob/main/files/en-us/web/api/imagebitmap/close/index.md)
