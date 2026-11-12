# Native app data and adaptations

LINZ imagery, elevation and topographic data retain their source/provider
attribution and CC BY 4.0 terms; see the app's map attribution links. Esri World
Imagery gap/satellite imagery retains Esri and contributor attribution. Original
overview and boundary data are preserved.

The topographic style, route interaction rules, chart calculations and Igor
hillshade adaptation reference the pinned Capacitor 0.18.0 application and its
MapLibre shaders. MapLibre contributors' BSD notice is in MAPLIBRE-LICENSE.txt.
The native application does not bundle or run the MapLibre JavaScript engine.
Labels use Android's installed system fonts; no downloaded font distribution is
added. Existing Rust, Android and OkHttp dependencies retain their own licenses.

## World Magnetic Model (WMM2025)

`android/app/src/main/assets/wmm/WMM2025.COF` is the World Magnetic Model
2025 coefficient file from NOAA's National Centers for Environmental
Information and the British Geological Survey, downloaded from
ncei.noaa.gov. The WMM is a product of the US and UK governments and is
public domain in the United States. It is used to draw the optional magnetic
grid on exported PDFs; `MagneticModelTest` checks the implementation against
NOAA's published WMM2025 test values.

## DOC recreational hunting permit areas

The optional "Hunting permit areas" layer is requested live from the
Department of Conservation's ArcGIS service "DOC Recreational Hunting Areas"
(item 2142c60984cb4a4887ed189662bec148), licensed CC BY 4.0 and credited to
the Department of Conservation in the map credits and About text. Only
permitted areas are requested, as DOC asks for external distribution. Nothing
is bundled with the app; tiles are cached for a day. DOC makes no warranty of
accuracy; the app labels the layer a guide only. Two small DOC responses are
test fixtures in `android/app/src/test/fixtures/hunting/`.

## DOC tracks, walks and huts

Tapping a track name requests, live and only for that track, records from
the Department of Conservation's ArcGIS services "DOC Walking Experiences"
(summary, difficulty, time, photo and page links), "DOC Tracks"
(DOC_Tracks_Open_Data: status, type, dog access), the walk lines
(DOC_WebsiteWalkingandTrampingLns_DTO: the walk's line, for the elevation
profile), "DOC Huts" (DOC_Huts_Open_Data: type, bunks, facilities) and the hut
pages (DOC_Huts: bookable, page link). All are licensed CC BY 4.0 and credited
to the Department of Conservation in the map credits and About text. Nothing
is bundled; responses are cached for a day. The walk photo is DOC's thumbnail,
loaded from doc.govt.nz as DOC links it.
