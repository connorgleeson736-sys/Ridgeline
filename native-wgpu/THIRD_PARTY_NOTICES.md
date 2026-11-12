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
