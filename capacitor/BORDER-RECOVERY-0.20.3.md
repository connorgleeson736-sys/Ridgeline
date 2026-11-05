# Capacitor border recovery — implementation checkpoint

User confirms 0.20.2 is installed, blurry border tiles occur across New Zealand,
and the irregular Rolling Pin issue includes black dotted lines in the wrong
place. The decimal survey-name fix is retained; it was not the entire issue.

Confirmed changes: an exact polygon/rectangle test bypasses Esri and survey inputs
only for tiles with zero possible replacement area. Conservative fine-alpha
protection is retained for every other tile. Forty thousand three hundred and
twenty source/zoom samples found 1,045 previously blocked pure-LINZ tiles;
independent Shapely intersection checks confirm zero replacement area in each,
and no other routing class changes.

The display overlay now traces the existing alpha-vetoed source masks instead of
nominal capture polygons. Raw contours independently reproduce all 41,287,680
mask pixels. Display outlines omit regions below 10,000 m² and simplify by at
most one z13 mask pixel, preserving topology. This is a display approximation at
closer zooms; neither original imagery nor source masks are changed. Rolling Pin
has two nearby measured outlines instead of 93 nominal outlines. See the geometry
comparison in `validation/borders-0.20.3/`. The coordinate reference is
[NZ Alpine Club's Rolling Pin entry](https://climbnz.org.nz/nz/si/aspiring/haast-range/rolling-pin).

Worker composition errors now retain transient status503 so a still-owned tile
can recover after the generic-error retry allowance. Five focused JS checks pass,
as does the existing seam-source check. Headless real-Canvas checks preserve
original LINZ pixels in both encoded and bitmap output. A synthetic high-zoom
terrain replay ended with an empty view and is not counted as validation.

Next: remove the mandatory Esri wait when effective boundary alpha is empty,
then build and verify the same-package/signer 0.20.3 Audit APK. No physical-device
execution or overall handset latency measurement is available.
