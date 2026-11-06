# Native download job controls

The native app now has three sliders in **Offline / imagery → Download jobs**.
Each selects 1–50 simultaneous network transfers:

| Setting | Default | Applies to |
| --- | --- | --- |
| While moving | 8 | Current-view transfers during movement |
| While stationary | 12 | Current-view transfers after movement stops |
| Background | 2 | Additional speculative/background transfers |

Settings are saved in the existing display preferences and apply immediately.
Reset restores 8/12/2. Reducing a limit lets admitted transfers finish, then
holds new admission until the corresponding active count falls below the cap.
Moving and stationary settings remain independent even if initially equal.
Loading diagnostics reports all three settings and the current foreground cap.

TileTransport owns admission for ordinary and cache-free HTTP paths. Both
underlying OkHttp dispatchers now accommodate the maximum allowed foreground
plus background setting (100) so they do not silently impose the previous
14/16-request caps. These are ceilings, not eagerly created threads. The actual
transfer count is controlled by the selected foreground/background limits,
available demand, and existing cancellation/priority rules. Retired canceled
callbacks can still exist separately, as documented for 0.1.4.

Imagery reservation scales down at small foreground limits: at least one slot
can serve terrain even when imagery demand is pending. Default reservation is
unchanged. Local cache reads, decoding, GPU uploads, prediction batch sizes and
offline preparation retain their existing independent limits. A larger setting
does not force work into those stages or create extra visible tile demand.
High concurrency can increase transient memory and data use; it does not imply
more bandwidth or a fix for the reported ten-second sharpening stall.

Reference refreshed from android-preview-download: Capacitor Performance
0.18.0, source d880143418ac46ece98adff0b3b2fbaedf044d46. Its existing moving/
stationary controls motivate the presentation; this native implementation uses
the requested 1–50 range and also exposes the existing background budget.

Two focused JVM checks pass, with no full suite or lint:

- Live movement changes, growth to 50, shrinking without cancellation,
  one-slot terrain progress, input clamping and background admission changes.
  Independent local readers may finish in either order; assertions track the
  actual admitted and waiting keys.
- Real local HTTP server holds 50 requests simultaneously on both ordinary and
  isolated cache-free paths; request 51 waits until capacity is released. The
  resulting bytes and request counts match on both paths.

This change is Kotlin/UI only. The APK reuses all three optimized 0.1.5 Rust
libraries and original assets. No physical-device UI or performance measurement
has been made. Packaging and exact source provenance accompany the APK under
validation/download-controls-2026-11-01.
