# HTTP latency investigation and implementation — native 0.1.8

**1 November 2026, Australia/Melbourne.** Baseline: native 0.1.7, source
`36f94778587447c5519623bea318f2d0e0b5e072`. Final source and installer hashes
are pinned in the accompanying release record. This pass changes HTTP/cache
delivery and diagnostics; imagery/terrain pixels, renderer, source selection,
download-job settings and route algorithms are unchanged.

Two independently reproduced waits exceed the requested 200 ms threshold:

| Controlled HTTP scenario | Before, median | After, median | Avoided wait |
| --- | ---: | ---: | ---: |
| Provider headers arrive, then the disk-cache monitor is held for 500 ms | 505.143 ms | 2.557 ms | **502.587 ms** |
| First address does not connect for 650 ms; the second address works | 651.938 ms | 251.875 ms | **400.063 ms** |

Three alternating pairs were recorded for each. Responses arrive over real
loopback HTTP, with identical bytes and one HTTP GET per trial. The cache hold
and failed-address delay are controlled inputs. **These are not phone/Wi-Fi
measurements or proof that either mechanism caused the reported five seconds.**

## Why the old HTTP stage could include a disk stall

The former normal network call used the same OkHttp cache as the local reader.
A cache miss did not end cache involvement. `CacheInterceptor` could still:

1. Acquire its disk-cache monitor again before connection setup.
2. Open/write cache metadata after the provider returned headers, before the
   application callback saw the response.
3. Copy body chunks into cache files and close/commit the cache editor while
   the application consumed the response.

The local 40 ms escape deadline did not bound those later operations. Detecting
canceled calls also did not help when the cache work belonged to a valid request.
Consequently a five-second aggregate HTTP stage could contain local journal or
storage work even if the provider had already responded.

### Deliver cold responses before publishing them to disk

For a genuine cache miss, network delivery now uses the existing cache-free
client/isolated dispatcher. Its proxy, connection pool, DNS, TLS configuration
and certificate/hostname verification remain inherited from the main client.
After the complete response is available, `HttpCachePublisher` schedules the
same response metadata and exact bytes into the **existing full-size OkHttp
cache**. Foreground delivery does not await that publication.

The implementation does not redirect the automatic cache into the earlier
16 MiB escape tier. The original automatic HTTP disk budget and cache format
remain. ETag, Vary, absolute request/response timestamps and cache directives are
retained. Transparent gzip is handled by matching the stored Content-Encoding/
Content-Length to the bytes actually persisted. Cacheable redirect bodies use
the SDK's existing redirect behavior and a bounded 100 ms close/drain opportunity,
with publication off the critical path.

Publication uses one Android-background-priority worker, up to 512 queued jobs
and a hard **8 MiB aggregate payload handoff limit**. The thread expires while
idle. A full queue or byte budget drops a disposable cache write and increments
a diagnostic counter; it does not make the live download wait indefinitely.
Temporary pending bodies can satisfy another consumer before disk commit,
including with encoded RAM retention set to zero. Their reuse delegates freshness
and offline max-stale/must-revalidate decisions to the pinned SDK's strategy.
No-store and Vary:* responses do not become pending cache hits.

The 50-response burst check holds the real cache monitor, delivers every body,
then releases the writer and reopens the cache. **All 50 bodies persist, with no
extra GET and no dropped writes**. The larger bounded job queue is necessary:
an eight-job queue would discard healthy bursts long before reaching the payload
byte limit.

Existing stale entries keep OkHttp's standard conditional validation path.
It still owns ETag/Last-Modified/304 header merging and may perform synchronous
cache updates. The new phase probes expose that remaining path. This deliberately
preserves established validation semantics while removing the reproduced cold
miss bottleneck. A phone trace showing a long processing span on validation is
the evidence needed for a subsequent revalidation-specific change.

### Cache interoperability

The small `OkHttpCacheAccess` Java adapter calls the **pinned 5.3.2 JVM cache ABI**.
It is an internal dependency interface, not a claim of a stable public cache-put
API. Java avoids Kotlin's unspecified invisible-member suppression. Upgrades
must compile this adapter and repeat the cache/migration fixtures.

A fixture was produced by the actual OkHttp **4.12.0** jar, including Vary headers
and its journal/metadata/body files. The new client reads that fixture without
network access. Both versions use cache format version 201105. No automatic
cache deletion/migration was added. Real HTTP checks cover gzip, Vary, no-cache,
ETag/304 freshness renewal, no-store, Vary:*, redirects, restart and pending-body
must-revalidate rules. These are host checks, not Android storage benchmarks.

## A failing first connection address no longer blocks all alternatives

The old 4.12 client tried routes sequentially. If DNS returned a usable address
after an unresponsive first address, the latter could consume a substantial part
of the 12-second connect timeout before the working route was tried.

The app now pins **OkHttp 5.3.2**, with fast fallback explicitly enabled. It can
start an alternative TCP connection after 250 ms and use the successful route.
Losing connection attempts are canceled. The fixture injects an unresponsive
first IPv6 socket and a real IPv4 HTTP server; it verifies two address attempts
but only **one HTTP request**, avoiding duplicated tile downloads.

This does not disable IPv6, hardcode provider IPs, shorten the normal response
timeout, or skip TLS validation. A single available route, slow DNS, a slow TLS
handshake on the winning route, or genuine provider processing can still take
seconds. The improvement applies when a better connection route is available.

5.5.1 was inspected but its Android variant requires compileSdk 37. 5.3.2 provides
the relevant route racing while remaining compatible with the existing SDK 36/
AGP toolchain and Kotlin version. Runtime minimum SDK remains 26.

## Diagnose the actual five-second interval

**Offline / imagery → Loading diagnostics** now scrolls and retains Copy. It adds:

- Oldest uncanceled HTTP call, its current phase and age; canceled retired calls
  are counted separately. An uncanceled call can include background work.
- Latest and longest HTTP phase records: dispatch/cache wait, DNS, connection
  including TLS, TLS separately, provider response headers, wire-source reads,
  and outer response/cache processing. Protocol and connection-attempt counts
  are included.
- Observed response-body rate over approximately five seconds, in KiB/s.
  Raw reads are measured before transparent decompression and cache writes.
  The rate excludes HTTP headers, TLS overhead, other applications and some
  SDK-discarded noncacheable redirect data; it is not total Wi-Fi utilization.
- Pending cache-publication bytes/jobs and completed/dropped/failed writes.

The wire probe sits inside cache writing. Its read duration measures the inner
source wait; gaps before/between/after those reads measure outer processing.
The cache-hold fixture asserts that the old 500 ms hold appears in **processing**
rather than blaming a slow body source. Cached bodies do not count as network
bytes. Timing spans may overlap during retries and concurrent connection attempts;
they are not a strict additive accounting of every nanosecond.

Records contain host/category and numeric timings, **never URL query strings or
API keys**. A regression uses a credential-shaped query and verifies it does not
appear in the snapshot. The traffic meter uses 20 fixed buckets; completed
history retains only latest/longest records rather than an unbounded event log.

During the next slow load, the phase record can distinguish:

| Dominant phase | What it points toward |
| --- | --- |
| Dispatch/cache wait | Client queue or pre-connection cache access |
| DNS | Name resolution |
| Connection/TLS | Route establishment, address fallback or handshake |
| Provider headers | Waiting for provider response headers, including network RTT |
| Wire reads | Body delivery, packet loss, provider streaming or bandwidth |
| Processing | Cache/body handling outside the measured raw reads |

If provider headers genuinely take five seconds, these client changes do not
establish a five-second saving. The previous aggregate could not make that
distinction. Low observed payload throughput alone also does not prove slow Wi-Fi:
the app may be waiting on the provider, cache, demand or connection setup.

## Verification and next work

Eight distinct targeted JVM checks pass, including existing retired-call isolation
and 50 simultaneous transfers on both paths. The publication burst regression was
extended/rerun after the admission-time handoff recheck; no broad suite or lint.
The host Android-variant jar reports the SDK-stub logging fallback and uses its
JVM platform for these fixtures. No physical Android TLS/network execution is
claimed. Source and results are in
[http-path-2026-11-01](../validation/http-path-2026-11-01/checks.json).

The APK reuses all three optimized 0.1.7 native libraries and the original assets.
Package/signature/alignment and downloaded-byte verification accompany it.

Connection prewarming was also inspected. The compatible stable connection-pool
API does not expose the required safe preconnect operation. Opening and closing
independent TLS sockets would not populate the actual HTTP pool, so no speculative
TLS warmup, duplicate-request hedge, forced IPv4 or protocol replacement was added.
The next decision should use the phone's new phase record, particularly to
separate slow conditional cache processing from actual provider/network delay.

Primary references:

- [Pinned OkHttp 5.3.2 source jar](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp-jvm/5.3.2/okhttp-jvm-5.3.2-sources.jar): `CacheInterceptor`, `CacheStrategy`, `Cache`, `FastFallbackExchangeFinder`, `OkHttpClient`, `ConnectionPool`; per-file hashes are recorded with the checks.
- [OkHttp 4.12.0 source](https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0-sources.jar): previous synchronous cache-write behavior.
- [RFC 6555 — Happy Eyeballs](https://datatracker.ietf.org/doc/html/rfc6555).
- [RFC 9111 — HTTP caching](https://www.rfc-editor.org/rfc/rfc9111.html): freshness, validation, Vary, no-store and must-revalidate semantics.
