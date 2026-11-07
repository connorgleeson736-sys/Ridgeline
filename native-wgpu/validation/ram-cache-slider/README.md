# Combined RAM tile-cache budget

0–1000 MiB, persisted and applied live in Offline / imagery. This controls retained
payloads, not total process RSS/heap/GPU usage. 25% encoded bytes, 25% decoded DEM
samples, 50% prepared direct buffers; inactive shares are not borrowed. Zero clears
these stores and prevents subsequent retained payload insertion. Active work can
still own references until it finishes. Metadata/objects and renderer working sets
are separate. Maximum settings can exhaust A20 memory; UI shows a conservative
recommendation. Increasing the ceiling allocates no payloads by itself.

Focused JVM budget/transport tests and Android lint passed; source tests exercise
all 1001 budget values and shrink/zero/response delivery/re-enable behavior. Existing
GPU libraries/source are unchanged; no GPU benchmark or A20 run was repeated.
