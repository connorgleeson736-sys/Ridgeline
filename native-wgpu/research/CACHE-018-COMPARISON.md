# Cache and pipeline comparison against published 0.18.0

Audit: 2026-10-30. Native checkpoint:06eed8b. This is a source/artifact audit,
not an implementation change, phone benchmark or proof of the A20 root cause.

## Reference resolution

Refreshed remote android-preview-download:39431dad230945919a7b7d9d0ef2e86db8492ead.
Read latest README, Ridgeline-3D-0.18.0-validation.json and Full checksum record.
Resolved source:d880143418ac46ece98adff0b3b2fbaedf044d46. Locally available Full
APK391684520bytes hashes tob4d286f5d6835447237eecaa15d5ee9a8bdf2870d56133c699f0b9ec3a173ffd.
Inspected both exact source and generated APK modules: app-only optimisations
matter; reading public/ alone misses production transformations. Asset hashes
are recorded in CACHE-018-ASSETS.json. Do not substitute0.19experiments.

Links below use the resolved source commit; referenced source paths are relative
to that commit. Public modules modified at packaging must also be read alongside
capacitor/scripts/optimise-web.mjs and mobile transport integration.

## Highest-value missing or incomplete behavior

### 1. Foreground disk/cache hits do not queue behind unrelated network waits

0.13.1, retained in0.18, deliberately removed the second native foreground
transfer cap. Renderer demand/concurrency controls remain, foreground transport
uses reusable idle-expiring threads, background transfer admission is bounded to2,
and a512outstanding-request guard protects runaway demand.

Native has two geometry and two image workers doing blocking HTTP, disk reads,
decoding and preparation. CPU-ready buffers bypass them; prepared/disk hits do
not. Two slow old requests can strand new cached work behind them. This is a
close match to the reported symptom, although not reproduced on the A20.

Adaptation: asynchronous HTTP and an independent local/cache-read path, with
bounded CPU decode/preparation admission. Preserve separate bounds; do not blindly
replace every worker pool with unlimited threads or raise HTTP concurrency.

Evidence: capacitor/LOADING-OPTIMISATIONS.md; TileDispatcher.java constructor and
pump; native TileLoader workers, Task.run, loadView and fetchBody.

### 2. Fresh compressed RAM hits and expiry metadata avoid repeated body work

Generated tile-cache.js retains bounded compressed imagery/DEM responses in RAM,
returns fresh hits immediately, and tracks absolute freshness. Generated
response-cache.js propagates X-Ridge-Expires-At. Native TileHttpClient adjusts
remaining lifetime using upstream policy, timestamps and age rather than
restarting lifetime after a slow read or disk hit. Composition responses inherit
input freshness. Expiry pruning is gated by earliest expiry, not a per-frame scan.

Native has CPU-ready buffers, decoded DEMs and OkHttp disk persistence, but no
persistent-between-jobs encoded-body RAM LRU: bodyJobs retains only active work.
Prepared terrain checks every source body through fetch and SHA on each hit.
That can introduce disk/network waits even when the derived bytes already exist.

Adaptation: retain source version/fingerprint plus actual freshness deadline when
responses arrive; use valid dependency metadata on prepared-cache hits. Only
validate/hash new or expired source versions. Consider a small shared compressed
RAM pool after measuring memory, and cache expensive completed gap-composition
results with all input versions/policies in the key. Do not serve expired content
indefinitely or reset max-age on a cache hit. General stale-while-revalidate is a
possible additional policy, not something this audit claims0.18always does.

Evidence: public/tile-cache.js, response-cache.js, gap-imagery.js;
capacitor/scripts/optimise-web.mjs; TileHttpClient.remainingCacheControl;
native PreparedTerrainCache.get and TileLoader.loadGeometry/fetch.

### 3. Consumer-aware cancellation, priority promotion and short reuse grace

0.18shared transfers have leases. Last consumer release can cancel the actual
OkHttp Call; other map/profile/download consumers retain their transfer. A visible
consumer promotes a shared speculative job. Normal gesture leftovers get a short
reuse grace (600ms at tile-cache,250ms response-input cache); speculative-only
aborts skip that grace. Source residency can park a bounded unfinished job and
adopt it if demand returns, rather than repeatedly restarting it.

Native shares response/DEM futures and has queued-task reprioritization plus
preparation checkpoints. It has no source-transfer leases, ordinary per-demand
Call.cancel, shared in-flight promotion or gesture reuse grace. It can spend
network capacity on truly obsolete work while discarding near-complete decoded
preparation that would be useful on a quick return.

Adaptation: ref-counted source jobs with map/profile/download ownership, mutable
priority and bounded nearby reuse grace. Cancel unused transfers without harming
shared halos or explicit downloads. Avoid blanket cancellation on every camera
change, and avoid letting grace recreate foreground head-of-line blocking.

Evidence: TileDispatcher.Lease.close/acquire, TileHttpClient output.onCancel,
public/tile-cache.js, response-cache.js, raster-residency.js.

### 4. Stronger reuse of resident coverage and previously achieved detail

0.18raster residency preserves a coarse reserve up to20%of its byte budget,
restores usable cached ancestors without starting parent downloads, shares failed
ancestor probes between siblings, and accepts a complete sharper child cover.
Generated native-residency.js retains finished sharper imagery when returning
source selection temporarily contains only a coarser parent. It also holds
coverage until replacements have appropriate imagery and DEM readiness.

Native already has exact non-overlapping resident cover, ancestor/descendant
fallback and warm GPU resources; these are not missing. However demanded_sources
prefers an exact requested parent immediately when it exists, bypassing resident
sharper descendants. There is no equivalent persisted achieved-image-quality hold
or dedicated coarse reserve, and source demand schedules parents without a full
resident-readiness inventory.

Adaptation: restore useful resident cover before fetching fallback parents; keep
complete sharper cover or a bounded quality hold where appropriate; retain a
small coarse reserve within existing memory policy. Preserve error bounds,
non-overlap and prompt refinement. Do not freeze old tiles or extend high-detail
drawing indefinitely when its GPU cost outweighs the benefit.

Rust samples source textures directly. MapLibre's composed RTT retention should
translate into source/plan residency here; adding an extra RTT pass solely to
copy this implementation would add unnecessary GPU work.

Evidence: public/raster-residency.js; native-residency.js differenceCovered,
_updateRetainedTiles and transition selection; native core demanded_sources,
resident_cover, surface_cover and SourceDemand.bounded.

### 5. Small predictive warming and separately gated prepare-ahead

imagery-preload.js predicts at most3nearby image tiles, or at most2ancestors when
zooming out, with foreground pressure, rate, byte and concurrency gates. Motion
changes cancel obsolete predictions. It is not a full surrounding high-detail ring.
prepare-ahead.js separately offers optional expanded-view source/GPU/composition
preparation, default setting off, only when visible work is ready and frame health,
memory, power and data policies permit it. It pauses on movement/backpressure.

Native retains visited resources but has no comparable directional/expanded-view
speculation. Turning around a peak can enter entirely unprepared coverage.

Adaptation: introduce tiny encoded-byte predictions only after cache-hit admission
and actual-demand scheduling work well. Consider opt-in idle GPU preparation later.
Speculation must never crowd out visible work or become a source-resolution cut.

Evidence: public/imagery-preload.js, prepare-ahead.js, app.js initial preference;
capacitor/LOADING-OPTIMISATIONS.md prediction integration.

### 6. Bounded negative offline lookup memo

TileMissCache remembers4096saved-resource misses, invalidated with an epoch on
commits/region changes. That avoids repeated SQLite/filesystem probes and prevents
a delayed miss from hiding a newly saved resource.

Native OfflineStore bypasses lookups when the entire DB is empty. Once it holds
resources, an unrelated online tile still hashes its URL and probes SQLite on
every fetch. A bounded per-resource negative memo would remove this repeated work.
It must invalidate after writes/deletions and not cache network failure as a saved
resource absence. This is useful with saved regions; not the sole explanation for
long return latency when no regions exist.

Evidence: TileArchive.misses/read methods, TileMissCache; native OfflineStore.read.

### 7. Exact plan/index reuse and narrow invalidation

0.18Direct sampling caches per-patch image mappings keyed by coordinate keys,
source tile identity and texture identity. Composition snapshots are keyed by
source and native residency revisions. Native-residency memoises geographic
parent/descendant indexes, checks related patches before RTT cost, and skips
transition scaffolding when membership is unchanged. Latest0.18also adds exact
camera-invariant LOD integrals, parent-key lookup and tile-local matrix memoisation.

Native already caches cover, index ranges, last plans, scene/instance uploads and
some scratch buffers. However an image arrival marks the shared cover dirty and
rebuilds terrain-related membership work, and active_sources repeatedly derives
finest ideal leaves with pairwise scans. Every draw also makes fresh rect/index
collections. Split geometry/image invalidation, cache membership indexes per demand
revision, and reuse static per-patch mappings where camera independence is proven.
Do not carry camera-dependent matrices across a changed camera under an incomplete key.

Evidence: public/maplibre-direct-sampling.js cachedPlan; terrain-composition.js
sourceView; capacitor/MAIN-THREAD-OPTIMISATIONS.md, CACHE-INVALIDATION-OPTIMISATIONS.md,
scripts/optimise-web.mjs; native Renderer.rebuild_cover/active_sources/render.

### 8. Useful per-tile observability already existed

0.18tile-trace stages distinguish queueing, byte hits, fetch headers/body, decode,
transform, source adoption/upload and terrain redraw. Native loadingStats exposes
HTTP hit/network/sharing/cancellation counters; terrain adapter tracks restore,
quality holds and return timing. These are bounded/optional, with no URL/key logs.

Native has host benchmarks and coarse renderer statistics/errors but not a
comparable export explaining where an individual returning phone tile waited.
Port the observable stages with counters disabled or cheap when not recording.
Cache storage correctness and a fast host hit do not establish a fast revisit.

Evidence: public/tile-trace.js; TileHttpClient.stats; native-residency returnMetrics;
capacitor/scripts/optimise-web.mjs diagnostic export integration.

## Useful offline/download improvements, lower priority for the viewer symptom

0.18SharedTileStore deduplicates payloads by content hash and keeps per-region
coordinate/version ownership. RegionTransfer runs3bounded producers and commits
up to16entries/about4MiB per batch, with durable resume/cancellation/generation
handling. Hashing occurs outside write transactions; large rows are sliced to
avoid Android CursorWindow limits. Indexed size metadata avoids reading bodies.

Native OfflineStore shares one body per URL across region membership, but cannot
preserve two versions at that URL for separate regions; per-resource transactions
and serial download work are simpler. downloadRegion also calls imagery.load and
discards its produced pixels. A raw-resource download plan could avoid much of
that decode/composition while still saving every required input/fallback resource.
These improvements help storage, downloads and contention, not every ordinary pan.

## Do not blindly port historical mechanisms

- 0.14removed the SHA-based decoded-image cache from production because copies,
  hashes and allocations hurt new-area work. The module remains in source for
  comparative tooling; its presence is not evidence it runs in0.18.
- 0.16removed duplicate WebView elevation CacheStorage persistence in favor of
  shared native HTTP storage. Keep one automatic source-persistence owner;
  derived terrain is a different representation but must justify its cost.
- Browser bridge batching, Blob transfer and WebGL RTT release-hook wrappers
  solve browser/MapLibre-specific costs; Rust has no WebView bridge and often
  already avoids those operations.
- Do not copy memory values, add all speculative modes at once, or claim the
  historical CPU fixture speedups establish phone FPS gains.

## Recommended implementation order

1. Add bounded per-tile traces; separate local/prepared hits from blocking HTTP.
2. Replace unconditional prepared-hit body validation with version/freshness metadata.
3. Add consumer-aware cancellation/promotion and bounded quick-return grace.
4. Reuse resident coverage/quality before making new fallback requests; tune coarse reserve.
5. Add measured encoded RAM/final-composition reuse and negative offline misses.
6. Add tiny pressure-aware predictions, then optimise unchanged membership/plan work.
7. Improve durable offline storage/download batching independently.

No runtime code, original Capacitor assets, source data or APK changed in this audit.
