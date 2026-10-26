# LINZ imagery seams and source borders

Data attribution: © Toitū Te Whenua Land Information New Zealand and the providers named in the linked source collections, CC BY 4.0.

Coverage uses 129 official LINZ capture-area files, with conservative protection for island datasets lacking a capture file. The nominal dotted border follows these published polygons. The files are simplified; actual boundary-tile source selection is refined by fine-survey transparency at the requested image zoom.

## Removing the coarse strip

The former 50 m exclusion left a visible strip of LINZ's 10 m background. There is now no fixed-width imagery exclusion at a survey seam. The nominal gap is expanded by 50 m as a sampling envelope. Inside a narrow 125 m seam band, the loader requests the relevant active fine-survey PNGs and composites Esri only where all fine-survey alpha values are zero. Every nonzero alpha preserves the original LINZ composite pixel, including partially covered edge pixels. This uses transparency, not colour matching.

Fine inputs respect their individual zoom ceiling and use the correct parent crop when overzoomed. Any failed fine input stops composition; it cannot silently permit Esri to overwrite aerial imagery. Survey input bytes use the existing response cache, transformed tiles remain cached, and worker transfers account for the extra buffers. All decoded per-job fine bitmaps are closed. Interior gap tiles retain the single-request Esri fast path without survey downloads.

The low-zoom sparse pyramid uses masks with the same measured fine-alpha veto. Cook summit, audited water and generalized coastal uncertainty exclusions remain protected. This change removes the survey-edge strip; it does not claim complete replacement of every remaining 10 m pixel.

## Border rendering

The overlay has two native MapLibre layers: a thin, low-opacity black dotted line and a line-placed source label, “LINZ imagery | Esri World Imagery”. Text follows the line and stays upright as the view rotates. It replaces the previous two line passes and four point-label layers. No thousands of pre-positioned labels are shipped.

The Layers panel has an “Imagery source borders” checkbox, saved on the device. Data and layers are created only on first visible use of the LINZ basemap with borders enabled. Hiding them turns off both drawing and symbol placement while retaining the source for fast re-enabling. Refreshes do nothing when visibility is unchanged. The overlay has no frame, move or resize listeners. Border data is 281,906 bytes, down from 1,308,986 bytes; browser FPS is not inferred from these structural savings.

## Reproduction and checks

Python requirements: numpy, Pillow, shapely and pyproj. `fetch-official-capture-areas.py` retrieves the reviewed public polygons from the source manifest. `prepare-imagery-seams.py --work <work> --audit <audit> --cache <cache>` builds the sampling geometry and checks fine-survey tiles. `package-imagery-seams.py --work <work> --cache <cache>` emits the atlas, pyramid and nominal border. `check-imagery-seams.py --work <work> --cache <cache>` independently checks emitted masks and creates native/close-zoom fixtures.

Checks cover 816 fine-survey tiles, original confirmed gaps and protected controls, real compositor pixel preservation, per-source zoom ceilings, worker cancellation and cleanup, failed survey requests, interior fast paths, native geometry, style validation, persistent border visibility, lazy loading and absence of redundant visibility updates. `OFFICIAL_IMAGERY_VALIDATION.json` and `qa/official-surveys/independent-validation.json` record the evidence. The live Basemaps IDs must match the reviewed source configuration before rebuilding. No credentials are recorded in reports or cached filenames.

Browser visual/WebGL verification remains unavailable in this environment. Native style checks and offline source-pixel comparisons are used; browser frame-rate improvements are not claimed.
