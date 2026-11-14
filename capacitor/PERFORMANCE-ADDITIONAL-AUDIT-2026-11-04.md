# Capacitor additional latency audit and disk cache — 0.20.0

Implements all four findings from the supplied additional audit, reviewed against
0.18.0 source d880143 and integrated over the published 0.19.0 audit source 2b9aefb.
The exact original audit files and provenance are retained under
`validation/additional-audit-2026-11-04/input/`. Native Rust 0.1.11 remains separate.
The user's later instruction supersedes the prepared-terrain cache work: no
processed DEM/mesh persistence, IndexedDB store, engine hydration hook or reserved
terrain partition is shipped. Existing RAM/GPU terrain ownership remains native.

## 1. Empty overlay RTTs and continuity

Direct mode now treats a missing per-patch coordinate list as empty only when its
source manager and coordinate map exist and there is no existing overlay RTT.
Unknown layers/resources still use composition. Real bucket arrival composes a
new overlay; temporarily missing coordinates retain an existing overlay; a known
empty bucket removes stale overlay pixels.

A successfully drawn Direct patch records its actual source-image/texture owners
and geographic cover, separately from RTT byte cost. The continuity adapter can
retain/re-adopt that zero-RTT cover while a finer patch awaits imagery, with real
DEM coverage still required. Invalid image owners and context generations reject
it. Warm zero-byte metadata is capped at 128 entries. A committed Direct photo
remains drawable until Original composition is available when changing modes.
No full frame-wide plans are retained by these per-patch ownership records.
Visible layers split into multiple draped stacks use native composition throughout;
the Direct shortcut cannot omit their base imagery.

Only actual newly created RTTs are charged to composition work; a Direct-only
flush does not rebuild RTT readiness or inflate composition counts.

Controlled actual-engine fixture: 24 large RTT acquisitions and 24 mip calls become
zero, while 24 Direct terrain draws remain. The next real route adds exactly one
RTT; missing coordinates/known empty bucket transitions pass. A separate native
terrain-manager fixture restores the same zero-RTT owner on return, holds its
measured parent while a finer child is unready, hands off once ready, and enforces
the 128-entry metadata bound without RTT allocations. These are work counts with
controlled GPU resources, not Android frame-time or measured memory savings.

## 2. Coarse fallback provenance and sharp-child recovery

The native gateway now labels an ancestor crop with its true z/x/y, whether it
may retry online, and the provider's Retry-After. Offline-only crops do not poll;
permanent HTTP failures do not turn into unlimited online retries. Provenance
survives streamed-body recovery, response copies, successful regional/gap
composition, byte/protocol delivery, image decoding and native raster upload.
Fallback bytes/composites stay no-store. Mixed composites conservatively retain
the lowest fallback zoom and longest retry wait. Composite freshness is bounded
by the original input expiry, including time spent waiting in the worker queue.

The loaded GPU tile carries that provenance. Loaded fallback tiles retain a
bounded revalidation record instead of being mistaken for completed sharp
children. The native reload path preserves their texture while reloading, and
keeps the old fallback visible if revalidation fails. Sharp success clears the
record. Active source/tile identity, explicit removal, successful replacement and
context lifecycle gate callbacks. Online recovery wakes eligible records while
honouring server Retry-After; move end/context restoration re-discover active
cached fallback tiles. Ordinary transient errors also recover past the old small
retry cutoff, capped at 15s backoff plus any longer provider-requested delay.

Actual-quality comparisons use the ancestor zoom rather than the child's address.

The focused replay passes a 200/no-store crop through native transport, byte cache,
pinned image queue/raster source and retry controller: coarse remains visible,
no compressed fallback entry, one retry record, then a sharp stationary reload
with two total transfers/uploads and zero remaining retry records. Retry-After,
offline-only, restored-source discovery and retired-source cancellation pass.
Android gateway/resampling is compiled separately; no handset outage was replayed.
The compatible parent-crop path remains; this does not claim the audit's optional
64-child true-ancestor texture-sharing estimate was implemented or measured.

## 3. Owned bitmap composites

Visible standard-alpha composites transfer the finished worker ImageBitmap into
the pinned image queue. They avoid output PNG encoding, encoded-buffer extraction
and final PNG decoding. Passthrough imagery still follows its existing byte path.
Explicit overview/preload byte consumers and unsupported bitmap environments
retain the encoded path. Persistent reuse stays with validated original HTTP
inputs, not another disk cache or an unbounded output-bitmap cache.

Visible bitmap requests have independent output ownership: concurrent subscribers
receive distinct bitmaps while native/encoded input requests may still share.
The regional scheduler remains bounded. Late results from cancellation, missing
worker jobs and abandoned scheduler consumers are closed; native raster upload
closes its owned bitmap in finally, including upload failure. Worker failure can
retry using the still-valid source bytes and main-thread composition fallback.

Real Chromium worker/Canvas/WebGL checks compare every uploaded RGBA byte in
transparent/vector-boundary fixtures. Standard premultiplied bitmap output has
zero pixel differences, two input decodes, no output PNG encode, one bitmap
transfer and no main output decode. Direct transfer under explicit
`premultiplyAlpha: none` changed 352 channel values in this fixture; that uncommon
option deliberately keeps the original PNG path and also matches exactly. No
pixel-changing shortcut is shipped. Worker-failure recovery matches pixels;
subscriber independence, cancellation after completion and simulated upload loss
close the appropriate outputs. These are browser correctness/stage checks, not
phone timings or a claim about all Android graphics drivers.

## 4. Touch dispatch

The app-only pinned map-event handler constructs touchmove events only when
`map.listens('touchmove')` is true. Gesture handlers, preventable touchstart,
route/marker drag behaviour and parent/once listeners remain. The actual pinned
event/raycast check runs 120 one-finger moves with zero unused unprojects/DEM
samples; ordinary, once and parent listeners still receive their events. The
original audit observed240 unprojects/10,588 DEM samples without this guard.
No large standalone millisecond saving is claimed.

## Selection-interval correction

The earlier priority claiming ordinary imagery arrivals always defeat selection
cadence is withdrawn. Actual native tile completion has no content tag. The
updated replay measures 10 ideal selections over 60 ordinary-arrival frames and
60 for explicit content events; live native reconciliation still runs. Explicit
content/metadata invalidation is restored rather than suppressed using the earlier
misclassification. The prior implementation report carries the correction.

## Automatic disk-cache slider

Layers → Advanced now offers 0–1,000 MB (1GB), default 256 MB. Android preferences
persist the value; changes apply after a full app process restart, avoiding cache
journal replacement while map/download transfers are active. The existing shared
OkHttp cache receives the entire selected allowance. Free-space pressure can
reduce it using the native app's one-eighth-free-space rule. Normal accounted
entry limits exclude small filesystem/journal overhead.

Imagery, elevation PNGs and other original provider responses share LRU eviction
and provider freshness/revalidation. Zero disables the cache and removes its old
HTTP entries when the process creates its client. Saved regions, routes and
imported/bundled overviews are separate. No duplicate WebView elevation persistence
or prepared-terrain cache is introduced.

## Verification and limits

Focused JS boundary/recovery and slider checks, two real JVM cache/budget checks,
and the targeted engine/browser fixtures are recorded in the adjacent validation
folder. Real HTTP checks cover persistence after reopening, LRU eviction under a
small cap, disabling and preserving separately saved data. No broad suite/full
lint or route-planning work. APK assembly performs its normal required checks;
The signed 0.20.0 Audit Base APK passes signature, package/version, alignment,
exact engine-patch and original-asset comparisons. Its source is 70a3f0b and
all 1,134 original image/data assets match 0.19.0 byte-for-byte. The release and
download records are in `validation/additional-audit-2026-11-04/apk/`.
No handset/emulator execution or overall speedup is claimed.
