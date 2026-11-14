# Canceled off-screen requests retained the underlying HTTP slots

The user's structural concern was valid: the loader's network counts did not
describe every queue through which a current-view request passed. This work
continues from 0.1.3 (`8ac2e3d` source, `d2c793e` publication), itself based on
the requested 0.1.1 publication `93be86c`.

## Two dispatchers existed conceptually, but shared one underlying queue

`TileTransport` bounds live foreground/background requests and ranks them by
current demand. On cancellation it releases its logical permit immediately and
calls OkHttp's `Call.cancel()`. That cancels the call but does **not** synchronously
remove a running asynchronous call from OkHttp's Dispatcher. The running slot is
released only after its callback/worker exits. A canceled queued call can also
remain queued until dispatcher processing reaches it.

This matters for calls blocked acquiring OkHttp's disk-cache monitor: Java monitor
acquisition is not interrupted by `Call.cancel()`. The obsolete call remains a
Dispatcher running call even though it no longer belongs to the camera's demand.
The cache-free escape client introduced earlier shared the same Dispatcher, so
its requests could still wait behind these canceled calls. A high-priority image
could have a free logical permit and still never reach the network.

| Stage after old-view cancellation | Loader accounting | Underlying OkHttp accounting |
| --- | --- | --- |
| Old request removed, cancellation issued | Permit free | Canceled worker may still occupy its slot |
| New-view image admitted | One active request | May be FIFO-queued behind obsolete calls |
| Cache-free request on the old shared dispatcher | One active request | Still subject to that same full queue |

The earlier terrain-versus-imagery reservation did not test this underlying
queue. This fixture does, with real canceled OkHttp calls rather than a mocked
source callback.

## Change

The cache-free sibling now has its own **bounded** Dispatcher (maximum 14 total
and per host, matching the existing 12 idle foreground + 2 background ceiling).
It still shares connection pooling, proxy/TLS configuration and the original
client's other policies. `TileTransport` remains the live-request admission owner.
When ordinary network admission sees canceled running/queued calls in the normal
Dispatcher, it routes the new request through this isolated escape path.
Ordinary healthy requests continue through the existing HTTP cache.

No duplicate request race or new watchdog is introduced. Escaped successful
bodies retain 0.1.3's asynchronous bounded persistence, freshness and no-store
behavior. Closing transport now closes the backend's owned escape dispatcher;
it does not shut down the original client's shared connection pool.

Isolation does not terminate blocked I/O: canceled normal workers can remain
alive until their underlying operation returns. Both dispatchers are bounded;
there can temporarily be more callback workers than current live source permits.
The change prevents retired cache workers from withholding current-view admission.
It is not a cure for genuinely slow DNS/provider/body transfer on the live path.

## Reproduction

One focused test; four alternating before/after trials:

1. Hold the actual OkHttp `DiskLruCache` monitor.
2. Enqueue two old-view calls into a two-slot Dispatcher. Inspect worker stacks
   to confirm both reached the held cache monitor.
3. Cancel both calls and verify they still occupy both Dispatcher running slots.
4. Request the current image. The baseline cache-free client shares those slots;
   the changed backend detects retired calls and uses the isolated dispatcher.
5. Release the monitor 250 ms after current-view enqueue.

| Current image over real loopback HTTP | Old shared queue | Isolated queue |
| --- | ---: | ---: |
| Median delivery latency | 253.320 ms | 1.952 ms |
| Canceled old calls still blocked when new image completes | 0 | 2 |

Same image bytes. Eight total HTTP requests for eight current images; canceled
old requests never reached the server. The reduced dispatcher capacity and held
monitor are replay controls, not phone/Wi-Fi measurements. The result proves
the queue mechanism, **not that it caused the user's particular 10-second stall**.
Raw samples: [retired-http.json](../validation/retired-http-2026-11-01/retired-http.json).

## Other off-screen ownership paths reviewed

- Current-view demand is published before old consumers are removed and freed
  logical permits are filled. Cancellation is batched.
- At most two returning old operations can be parked for 600 ms, and only when
  their actual dependency fan-out fits the existing two background slots.
- Current shared consumers retain their dependency when another consumer leaves.
- Idle preparation and predictions remain background work; no live foreground
  allocation is added for them.
- Current selection uses margin 1.0, with conservative -100..4000 m terrain
  bounds. Those bounds can include tiles whose real terrain is off screen; they
  are not proof of visibility. Tightening them without actual extrema could
  remove real mountains. No aggressive culling or quality reduction is applied.

## Diagnostics and scope

**Offline / imagery → Loading diagnostics** now reports the underlying normal
HTTP running/queued counts, how many are canceled but still occupying slots, and
the isolated queue's running/queued counts. Existing logical request, CPU and GPU
counts remain. This makes a discrepancy between logical and real queue ownership
visible during the phone stall. Snapshot reads contain no URLs or API keys.

Production Kotlin compilation and the one targeted real-HTTP regression pass.
No full test suite/lint or phone benchmark. Rust rendering, detail, sampling,
source selection and route planning are unchanged in this follow-up.
