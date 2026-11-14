# Android preview reference

For Android preview analysis, fixes, tests and version claims, use the remote
`android-preview-download` branch as the reference. Refresh that branch, read
its latest README and versioned validation/checksum records, then resolve the
exact source commit linked by the release. The download branch contains release
artifacts, not application source. Do not assume the current checkout or `main`
represents the installed preview. Verify relevant APK assets when available.

Preserve full imagery resolution, terrain detail and the current renderer.
