# Comprehensive audit checkpoint evidence

See [implementation and limits](../../research/COMPREHENSIVE-AUDIT-IMPLEMENTATION.md).
Raw host measurements: pixel-packing.json, vulkan-arrays.jsonl, gles-arrays.jsonl.
Machine-readable GPU medians: gpu-summary.json. No physical A20 test is claimed.

Reproduction (from repository root, with Rust/wgpu software-driver dependencies):

```sh
# Extract the genuine payload fixture into a temporary directory.
tar -xzf native-wgpu/validation/pipeline-cache/inputs.tar.gz -C /tmp
VK_ICD_FILENAMES=/path/to/swiftshader_icd.json cargo run --release --locked \
  --manifest-path native-wgpu/Cargo.toml -p ridgeline-renderer \
  --example texture_arrays_bench -- /path/to/extracted-inputs
EGL_PLATFORM=surfaceless cargo run --release --locked \
  --manifest-path native-wgpu/Cargo.toml -p ridgeline-renderer \
  --example texture_arrays_bench -- /path/to/extracted-inputs --gles
cargo test --locked --manifest-path native-wgpu/Cargo.toml -p ridgeline-renderer \
  index_atlas -- --nocapture
```

Startup probes and 80-upload recycle checks are outside the reported timing.
Each process alternates three modes for five trials and asserts identical output
for main, in-flight recycled and returned views. This is not geographic parity.
Warmup allocations are excluded; pooled retained bytes are reported separately.
The original six overview ZIPs, full image resolution/all mips and original terrain
heights remain. Skirts remain removed by request. No new pack/session CA is bundled.
