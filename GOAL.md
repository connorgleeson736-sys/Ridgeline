# Ridgeline fully native Android migration

Status: IN PROGRESS. Do not label a prototype as the complete app.

## Authoritative reference

Capacitor 0.18.0 source d880143418ac46ece98adff0b3b2fbaedf044d46;
android-preview-download 39431da. Reference APKs remain in the sibling download
worktree. Existing Capacitor application and earlier android/ MapLibre Native
application must remain intact. The unfinished 0.19 optimisation experiment is
in the separate preview-verification worktree and is not the migration baseline.

## Deliverable

Separate native Android application using Kotlin native UI and Rust/wgpu terrain
rendering, with no WebView, Capacitor or JavaScript renderer runtime. Include all
six original overview packs, actual LINZ key/source selection/gap replacement,
routes/profiles/overlays and current interaction semantics. Ship complete source,
reproducible instructions, signed debug APK and evidence-based parity/performance
report. Never claim device validation without executing it.

## Milestones and gates

- [x] M1 Audit exact app, camera, shaders, imagery/catalogs, storage, route/UI behavior;
      research current official Rust/wgpu/Android/MapLibre documentation and record sources.
- [ ] M2 Installable minimal native terrain+imagery proof with real decoded DEM,
      actual images, correct projection, depth, sky, lifecycle and touch camera.
      Prove shared tile boundaries and parent fallback before broader migration.
- [ ] M3 Reference captures and numeric/pixel comparison for camera, terrain,
      exaggeration, grade, sky/horizon, tile detail/transitions and geographic patches.
- [ ] M4 Native UI/feature migration: planner editing/insertion/drag/selection,
      autosave, undo, GPX import/export, multi-route presentation, markers, labels,
      profile interactions, offline regions, settings, location and attribution.
- [ ] M5 Bounded asynchronous tile pipeline, deduplicated priority downloads,
      persistent HTTP/offline caches, GPU upload budgets, off-screen retention,
      device memory budgets and measured movement-quality policy.
- [ ] M6 Release: bundled six packs verified, host tests, Android lint/build,
      device/emulator evidence where available, runtime dependency audit, debug
      installable APK, checksum, known differences and reproducibility report.

## Acceptance requirements

Full source imagery and terrain detail remain available. Movement adaptation
must restore full detail and be explicitly measurable. No blank terrain/flicker,
no fabricated heightmaps/imagery, and no flat hillshade presented as 3D parity.
Reference capture tolerances must be documented before calling parity achieved.
Original data stays untouched; use a separate application ID and private data.
No secrets/keystores in source; the existing public client key may be reused as
explicitly requested, with licensing/attribution preserved.

## Current proof status

M1 audit is recorded in native-wgpu/research/AUDIT-018.md. M2 has real Android
wgpu imagery rendering and six-pack reads on a controlled synthetic height fixture,
plus reference camera/geometry tests. Real LINZ DEM and terrain visual parity are
still blocked by the pending network configuration; M2 remains open. No later
feature or release milestone is complete.

## Resume

Read NATIVE_PROGRESS.md, then native-wgpu/research/ and git status. Resume the
recorded exact next step; keep unfinished work and evidence explicit. Commit
meaningful milestones frequently. Continue autonomously until acceptance gates
are genuinely met or a concrete external blocker requires user input.
