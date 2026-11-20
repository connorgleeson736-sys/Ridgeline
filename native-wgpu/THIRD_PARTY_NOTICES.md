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
loaded from doc.govt.nz as DOC links it. When a track's name matches no DOC
walk, the walk whose line runs along the track is used instead.

The walk's full description is read from its doc.govt.nz page (text only:
the "Description", "Getting there" and "Know before you go" sections; DOC
website content is CC BY 4.0, credited to the Department of Conservation).
Photos, videos and hut tiles on the page are not used. Two page excerpts
are test fixtures in `android/app/src/test/fixtures/docpages/`, and DOC walk
lines and LINZ tiles around Rabbit Pass in `android/app/src/test/fixtures/rabbit/`.

Nine LINZ topographic vector tiles around Roys Peak (CC BY 4.0, LINZ) are
test fixtures in `android/app/src/test/fixtures/tracks/`.

Hut cards use DOC's "DOC Huts" (DOC_Huts_DTO and DOC_Huts, CC BY 4.0) and
the hut's doc.govt.nz page text. Circuit names on the map use DOC's walk list
and walk lines (DOC_Walking_Experiences, DOC_WebsiteWalkingandTrampingLns_DTO,
CC BY 4.0). Real DOC and LINZ data around the Gillespie Pass Circuit are test
fixtures in `android/app/src/test/fixtures/gillespie/`, and a Siberia Hut page
excerpt in `android/app/src/test/fixtures/docpages/`.

Walk routes follow the legs in each walk's DOC page over DOC's walk line;
the Gillespie Pass Circuit's DOC page excerpt and line are test fixtures
(`android/app/src/test/fixtures/docpages/gillespie-pass-circuit.html`,
`android/app/src/test/fixtures/gillespie/doc-walk-line-gillespie-pass-circuit.json`).

A Milford Track DOC page excerpt is a test fixture
(`android/app/src/test/fixtures/docpages/milford-track.html`).

## Te Araroa

`android/app/src/main/assets/te-araroa/` holds Te Araroa as the Te Araroa
Trust publishes it at teararoa.org.nz (its GPS files, Google Earth file and
section notes), licensed CC BY 4.0 and credited on every Te Araroa page;
photographs from the Trust's website are not used. The trail's food stops are
compiled from walkers' published resupply guides (facts only) and the Trust's
notes. The shops and cafés at them are © OpenStreetMap contributors, available
under the Open Database Licence (ODbL), credited on each shop's page. Live
section alerts are read from the Trust's own site data when a section opens.
Bushline/Ridgeline is not affiliated with or endorsed by Te Araroa Trust.

The trail's name on the map is lettered in IM FELL English SC (Igino Marini's
digitisation of the Fell types), `te-araroa/IMFellEnglishSC-Regular.ttf`, under
the SIL Open Font License 1.1 (`te-araroa/IMFellEnglishSC-OFL.txt`).

## DOC alerts

Walk cards and DOC places to stay show DOC's current alerts, read live from the
service DOC's own pages use (www.doc.govt.nz/api/alerts/guid/…), Crown
copyright, Department of Conservation.
