"""Isolated frozen-baseline / shader / batching / combined host GPU trials.

Use inherited Cargo/proxy settings. No phone or network-loading measurements.
Build all variants before timing; three alternating trials by default.
"""
import argparse
import io
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile

args = argparse.ArgumentParser()
args.add_argument('--cargo', default='cargo')
args.add_argument('--trials', type=int, default=3)
args.add_argument('--output', type=Path, required=True)
options = args.parse_args()
root = Path(__file__).resolve().parents[2]
options.output.mkdir(parents=True, exist_ok=True)
baseline = '3cc6f903670d6796f04adc5201c112b067469172'
archive = subprocess.check_output(['git', 'archive', baseline,
    'native-wgpu/Cargo.toml', 'native-wgpu/Cargo.lock',
    'native-wgpu/core', 'native-wgpu/renderer'], cwd=root)
with tempfile.TemporaryDirectory(prefix='ridgeline-skirt-ab-') as temporary:
    temporary = Path(temporary)
    env = os.environ | {'CARGO_TARGET_DIR': str(temporary / 'target')}
    code = (root / 'native-wgpu/renderer/src/lib.rs').read_text()
    start = code.index('        if self.image_arrays.is_some() && self.height_arrays.is_some() {', code.index('pub fn render('))
    end = code.index('        let mut data = std::mem::take', start)
    binaries = {}
    for mode in ['baseline', 'shader', 'batch', 'full']:
        directory = temporary / mode
        directory.mkdir()
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            tar.extractall(directory, filter='data')
        workspace = directory / 'native-wgpu'
        renderer = workspace / 'renderer'
        shutil.copy(root / 'native-wgpu/renderer/examples/skirt_bench.rs', renderer / 'examples')
        if mode != 'baseline':
            variant = code[:start] + code[end:] if mode == 'shader' else code
            if mode == 'batch':
                variant, count = re.subn(r'pass\.set_pipeline\(if clipped \{\s*&self\.pipeline\s*\} else \{\s*&self\.unclipped_pipeline\s*\}\);',
                    'pass.set_pipeline(&self.pipeline);', variant)
                assert count == 1, 'Rebase batching-only fixture'
            (renderer / 'src/lib.rs').write_text(variant)
            for name in ['terrain.wgsl', 'seams.rs']:
                shutil.copy(root / 'native-wgpu/renderer/src' / name, renderer / 'src')
        # Force local-crate rebuilding when using one dependency target directory.
        (renderer / 'src/lib.rs').touch()
        subprocess.run([options.cargo, 'build', '--locked', '--manifest-path', str(workspace / 'Cargo.toml'),
            '-p', 'ridgeline-renderer', '--example', 'skirt_bench'], env=env, check=True)
        binaries[mode] = temporary / ('bench-' + mode)
        shutil.copy(temporary / 'target/debug/examples/skirt_bench', binaries[mode])
    for trial in range(options.trials):
        modes = ['baseline', 'shader', 'batch', 'full']
        for mode in modes if trial % 2 == 0 else reversed(modes):
            for backend in ['vulkan', 'gles']:
                if mode == 'batch' and backend == 'gles':
                    continue  # Separate-texture backend cannot share instance bindings.
                result = subprocess.run([str(binaries[mode]), '--arrays' if backend == 'vulkan' else '--gles'],
                    env=env, check=True, capture_output=True, text=True)
                (options.output / f'{mode}-{backend}-{trial}.txt').write_text(result.stdout)
                print(mode, backend, trial, result.stdout, flush=True)
