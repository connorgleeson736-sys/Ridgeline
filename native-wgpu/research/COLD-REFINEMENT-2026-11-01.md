# Sharper imagery must not wait behind every terrain transfer

Baseline: native 0.1.2 / source `d831d2f`, on top of the requested 0.1.1
publication `93be86c`. The user clarified that new areas show coarse imagery,
then stay blurry for 10+ seconds. That is a refinement complaint, rather than
only a missing-first-coverage complaint. Phone Wi-Fi has not been measured.

## Main finding: network priority did not reserve execution capacity

Geometry and imagery shared all 8 moving / 12 idle foreground slots. A terrain
job fans out several DEM/halo/parent requests. Once those requests were active,
even a top-ranked newly visible image waited for an existing transfer to finish.
Priority ordering could choose only queued work; it could not free an occupied
slot. A slow terrain response could therefore delay sharper imagery while coarse
imagery was already visible, without the imagery request itself being slow.

The loader now publishes unsatisfied visible image demand with the entire camera
generation, **before** CPU workers fan out source dependencies. While that demand
exists, at most 6 of 8 (or 10 of 12 idle) foreground slots can belong to non-image
work. Two existing slots remain available for image refinement. Images can use
more when available. Once image demand is satisfied, terrain can use every slot.
Shared requests, cancellation and the two-background-slot cap remain intact.
No new connections/concurrency budget is added, and active terrain is not aborted.

| Controlled production-transport replay | Before | After |
| --- | ---: | ---: |
| Eight terrain transfers take 250 ms; newly requested sharp image needs 5 ms service | 255.914 ms | 5.457 ms |
| Maximum simultaneous foreground transfers | 8 | 8 |

Six alternating trials; same returned image bytes. These are injected service
times on a host JVM, not Wi-Fi measurements or an app-wide speed ratio. The
change removes the admitted-terrain wait in this fixture. If the image provider
itself is slow, reserving a slot cannot remove that provider delay.

## Other waits removed

Prepared terrain/composition disk reads, directory indexing, validation and gzip
decompression formerly ran on the two CPU workers. A blocked cache could stop
source admission before any HTTP request existed. `DerivedReadGate` gives these
optional shortcuts two independent readers, priority ordering, a hard 16-operation
limit and a 12 ms decision deadline. Tasks suspend rather than block CPU workers.
A timed-out shortcut becomes a miss; sources build the same full-detail payload.
Late disk values cannot replace that result, and timed-out running reads retain
their permits until actual exit, preventing unbounded I/O/thread creation.

| Actual prepared-cache monitor held for 250 ms, two old readers; foreground 129×129 mesh from genuine decoded LINZ inputs | Before | After |
| --- | ---: | ---: |
| Foreground payload ready, median of six alternating trials | 251.516 ms | 13.775 ms |
| Healthy prepared-file hit, ten trials after warmup | 0.853 ms | 1.465 ms |

All mesh bytes match. The improved case finishes while the actual cache remains
locked. Inputs are already decoded; network, Android BitmapFactory, JNI adaptive
profile, GPU and first display are excluded. Healthy hits pay about 0.61 ms in
this fixture for off-thread scheduling. Expired derived terrain now follows the
normal source/rebuild path instead of revalidating sources inside an open gzip
reader; it can do more CPU work when source versions are unchanged after expiry.

Overlapping immutable PNG decodes also used `Future.get()` on the second CPU
worker. Consumers now suspend with a bounded operation-local waiter and reuse the
completed producer result even after its active-map entry disappears. This frees
the second worker for unrelated visible tiles without duplicating the decode.
Cancellation and memory pressure clear operation references. Composition-store
initialization no longer owns the loader's camera-scheduling monitor; delayed
cache writes also initialize stores on the persistence executor.

## Cold-start coverage and persistence, secondary to the blurry-image complaint

On a completely uncovered focus, the plan can now include the nearest original
bundled coarse image (up to zoom 8), plus matching coarse terrain when needed.
The coarse image needs no provider request. All ideal fine targets and the normal
focus parent remain; only a distant fallback may give up one slot, within the same
source budget. Coarse coverage uses real sources and normal terrain sampling,
then fine tiles replace it. It is not a fix for imagery that is already coarse
and waiting to sharpen.

For the default focus, four genuine zoom-7 DEM payloads total 91,695 bytes versus
394,388 for the existing nearby parent's four zoom-10 DEMs. This is a single
cloud byte-volume probe, not a latency benchmark. Bootstrap adds some source work
and can trade final-refinement bandwidth for earlier useful coverage; full
resolution and ideal source selection are retained.

Cache-free HTTP winners previously bypassed persistent caching entirely. Once
encoded RAM evicted them, a return could redownload the tile. They now enqueue
unchanged encoded bodies into a separate bounded disk store, using at most
16 MiB carved **from the existing total disposable allowance**. HTTP cache size
is unchanged; derived geometry/composition allowances are reduced accordingly.
Source delivery never waits for the write. Expiry and no-store are respected;
offline access retains the existing explicit stale-cache behavior. Atomic files,
size bounds and CRC checks reject incomplete/corrupt entries. The writer has one
thread and eight queued operations; overload may discard disposable writes.
This addresses revisits, not the user's main new-area symptom.

## Phone diagnostics and verification

**Offline / imagery → Loading diagnostics** refreshes once a second and can copy
the snapshot. It shows active/queued HTTP, current oldest request/wait ages, last
and maximum HTTP duration, CPU queues, GPU upload queue/submissions, resident
sources, cache hits and retry backoffs. No URLs or API keys are exported.
HTTP duration includes cache, connection, provider and body work; it does not
isolate radio/Wi-Fi time. This is a stage snapshot, not a presented-frame trace.

- Old HTTP requests while sharper images remain queued suggest network admission
  or source/provider delay; the new reserved slots address the terrain monopoly.
- Small HTTP times with CPU or GPU queues indicate an app processing backlog.
- Retry backoffs can also produce multi-second waits; provider errors still need
  phone evidence. Existing 12 s connect / 15 s read limits are not lowered blindly.

Only seven focused checks were run, including the two alternating latency
fixtures above, shared-decode continuation, bounded timeout/late-result behavior,
original coarse asset/budget preservation and real loopback cache-free HTTP
persistence across restart. Production Kotlin compiles. The earlier disk-hit
test timeout did not reproduce in its isolated run; no claim is made that all
historical suite failures are resolved. No broad tests/lint or phone benchmark.
Rust shaders, geometry sampling and route-planning algorithms are unchanged.

Raw records: [pipeline-waits validation](../validation/pipeline-waits-2026-11-01/).
