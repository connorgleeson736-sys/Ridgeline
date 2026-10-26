# Ridgeline source v115 — split export
Source commit: 17aae08725dc48a806b09f5c3708d35cb8889741

Extract ALL 4 ZIP files into the SAME directory. Their contents merge into
one ridgeline/ folder. These are independent standard ZIPs, not a multipart ZIP format.
Part 1 contains the application source, configuration, scripts, checks and documents.
Remaining parts contain required public/assets/ files. Keep every asset part.

Excluded: qa/ historical evidence, node_modules/, dist/, .git/, other generated
runtime folders and ignored local files. Dependencies are restored with npm.
Some historical audit checks refer to qa/ data deliberately omitted from this smaller
export; the application source and runtime assets are retained.

Node.js >=22.13.0 is required. Inside ridgeline/, run:
  npm run install:ci
  npm run dev
For a production build:
  npm run build

Main website files: public/index.html, public/viewer.mjs, public/app.js, public/style.css.
Source manifest: EXPORT_SOURCE_SHA256.json (hashes of all included original files).
The original public LINZ client configuration is preserved. Production server secrets,
R2 data and browser-local saved routes are not part of the source export. Separate hosting
requires configuration for services used by the app. The live website is unchanged.

The five tracked files in build/ are required source code (Vite plugins and Worker
entrypoints), not generated output. They are included because vite.config.ts imports them.
