# Cached parent fallback during zoom/pan

Three concrete failures were addressed:

1. Render ownership selected only a ready ideal child and discarded its resident
   parent backdrop. Camera motion can precede the next throttled demand plan,
   leaving siblings blank despite a cached parent. Render sources now include the
   nearest resident ancestor even for ready targets. The non-overlapping cover
   still gives fine tiles their complete footprint; complete children eliminate
   parent draws, and coarser demand still suppresses isolated stale descendants.
2. Upload admission accepted exact planned IDs only. Zoom jumps can skip several
   levels, so a previously prepared cached grandparent was rejected. The loader
   now walks prepared RAM keys (up to22lookups, no I/O/decode) and offers the nearest
   parent for missing GPU coverage, independently for terrain and photos. The
   queue admits useful ancestors of current targets and gives coverage uploads
   priority. Unrelated/cancelled demand still prunes queues. Cache-only restoration
   here means an existing prepared RAM payload, not an arbitrary HTTP disk hit.
3. Count-cache fallback eviction chose the newest non-explicitly-requested source,
   even if it was still rendering coverage. Owners/backdrops now stay protected;
   fallback chooses oldest non-owner entries first, or detail with a real resident
   ancestor. If no safe victim exists, preserve coverage debt instead of deleting
   the last source. This can temporarily exceed the configured soft count target;
   existing bounded array pages and upload backpressure remain. RAM-slider limits
   are unchanged. This is not an unlimited retention or a zero-memory fallback.

Regression evidence:
- Fine-only demand over an already rendered parent/child fixture now preserves
  every RGBA pixel of the complete parent-backed frame on Vulkan and GLES arrays.
  This specifically exercises moving outside the fine child's footprint before
  the next demand plan. It previously selected just that child.
- CPU source-cover test preserves three parent siblings alongside the fine child;
  all four ready children still replace every parent surface draw. Zooming back
  out does not revive a partial stale fine cover.
- Prepared RAM parent lookup picks the nearest ancestor across a four-level jump.
  Upload tests prove ancestor admission/priority and unrelated-viewport pruning.
- Memory-pressure fixture retains requested terrain/photo and the nearest cached
  parent, dropping unrelated cache entries. Existing coarse/fine demand history,
  no-hole cover, exact crop/mip/grade/picking, skirts and 24-tile unrelated-upload
  pressure fixtures continue passing.

27core,4renderer,1CLI Rust tests;11focusedJVM tests andlint pass. Allthree optimized
Android ABIs build. No physical A20 movement test or FPS gain is claimed. Full
source resolution/mips/heights, selective skirts, cache sliders and attached ZIP
behavior remain. Network/provider delays still matter where no usable source is
resident or prepared; this does not invent missing data.
