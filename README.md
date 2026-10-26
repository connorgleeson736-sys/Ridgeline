# Ridgeline Android downloads

## Latest: Ridgeline 3D 0.3.0

Download **[Ridgeline-3D-0.3.0.apk](Ridgeline-3D-0.3.0.apk)**. Open the file page and choose **Download raw file** (↓). Sign in to GitHub if necessary. This universal APK supports Android phones/emulators with Android 8.0+ and an up-to-date Android System WebView/Chrome. It has the same signer as 0.2.0 and installs as an update to **Ridgeline 3D**.

The app uses the original website's Original + Direct 3D renderer. Version 0.3.0 centres the GPS SVG, improves printed Topo sharpness, and adds import of the complete precomputed South Island overview imagery. Cook-reference colour changes and alternate renderers remain excluded.

### Add the overview imagery

1. Download all six ZIPs from **[overview-packs/](overview-packs/)** — 364.8 MB of additional imagery.
2. Open **Layers → Download offline region → Import overview ZIPs**. Select the six ZIPs together, or import them one at a time.
3. Keep Ridgeline open until **Ready for offline use · 6/6 packs**. Download selected regions for elevation/3D terrain and profiles; the overview pack contains imagery only.

The existing 67 downsampled zoom 0–8 images in the APK were byte-matched against the new export. The six ZIPs add zoom 9–12 without changing original image pixels. Terrain/imagery tile zooms can differ from camera zooms. Normal providers may be used outside the saved coverage or as a fallback.

### Website fix

**[Ridgeline-Website-Topo-Fix-0.3.0.zip](Ridgeline-Website-Topo-Fix-0.3.0.zip)** contains the four website files needed for the Topo fix. Apply it through the original Site editing/deployment session. This cloud session updated GitHub source and produced the patch; it did not deploy the original live Site.

See **[validation results](Ridgeline-3D-VALIDATION.md)** and the versioned JSON/checksum files. No emulator validation was requested or performed. Source: [main/capacitor](https://github.com/connorgleeson736-sys/Ridgeline/tree/main/capacitor), commit 97cc48c.

Earlier APKs remain available for comparison. `Ridgeline-Android-0.1.0-*.apk` are the earlier Kotlin/MapLibre Native hillshade app and do not contain the website's 3D terrain. `Ridgeline-3D-0.2.0.apk` is the earlier Capacitor preview.
