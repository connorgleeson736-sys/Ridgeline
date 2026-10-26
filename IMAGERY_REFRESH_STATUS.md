# LINZ / Esri imagery refresh

The LINZ basemap uses Esri World Imagery in verified gaps in detailed LINZ coverage. Existing detailed aerial surveys take priority. Both Original and Original + Direct use the same routing and masks.

Search regions from the supplied coverage audit are candidates only. The refresh checks current LINZ source IDs against the archived survey footprints, inspects actual transparency in each intersecting fine survey, and excludes every pixel touched by fine coverage plus a two-pixel native-mask guard. Existing Cook summit protection remains in place. New replacement pixels must exactly match the current lossless national 10 m tile and composite aerial tile, including alpha; nonmatching pixels and their immediate neighbours are excluded. Small new fragments below 16 native pixels are omitted. A 1 km uncertainty strip inside the generalized audit coast is not expanded. Uncertain coverage stays original.

The native masks are binary at zoom 13. High zoom requests crop those masks; lower zooms use the existing sparse image pyramid. No terrain or elevation changes, colour matching, feathering, or invented detail are introduced.

Dotted borders are derived from the union of the actual replacement-mask pixels, including holes, with internal tile seams removed. Source labels use points verified on opposite sides of each border. Four label scales keep text away from the boundary; tiny regions acquire labels only when enough room exists at a closer zoom. The borders and labels appear only on the LINZ basemap. The label “LINZ imagery” identifies the original provider, including conservative guards that may still contain 10 m imagery.

## Reproduction

Python requirements: numpy, Pillow, scipy, shapely, pyproj and contourpy. `refresh-gaps.py --audit <extracted-audit> --work <cache>` expects current public aerial metadata at `<cache>/live-config.json`. It reads the existing application LINZ key in memory and stores no credentials in downloads or reports. Survey evidence is cached outside the repository. `package-refresh.py --work <cache>` emits the sparse atlas, imagery pyramid and source boundary. `check-refresh.py --work <cache>` independently compares generated masks to the cached fine-survey and lossless 10 m evidence. The baseline atlas is pinned to the opening source commit.

`IMAGERY_REFRESH_VALIDATION.json` records the source hashes, accepted pixels, excluded uncertain pixels, and boundary checks. `checks/gap-imagery.cjs` verifies the original fourteen confirmed sample locations and the Cook, Aspiring, Milford, Tutoko and water controls. `checks/imagery-boundary.cjs` validates native style layers, paired providers, basemap visibility, stack order and cleanup.

Managed preview reported running, but the cloud browser could not connect (ERR_CONNECTION_REFUSED). Browser visual and WebGL verification is unavailable in this environment. Native style validation, routing and compositor checks, and source-pixel comparisons are used; no FPS or browser visual-performance claim is made.
