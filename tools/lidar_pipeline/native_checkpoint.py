"""Persist the exact native executables used by a reconstruction generation.

Rebuilding identical C++ can change ELF bytes with a different compiler/runtime.
Saving the tested binary avoids silently invalidating completed tile recipes on
recovery. Execution still requires a compatible Linux x86-64 runtime.
"""
import json
import platform
from pathlib import Path
import subprocess
from .state import atomic_bytes, atomic_json, digest, key
from .build_native import PACKAGES

SOURCES = [('advancing-front', 'advancing_front.cpp'),
           ('local-ballmerge', 'local_ballmerge.cpp'), ('orient-soup', 'orient_soup.cpp')]


def checkpoint(state, built):
    built = Path(built); source = Path(__file__).parent
    recipe = dict(stage='native-executables-v1', platform=platform.system(), machine=platform.machine(),
                  dependencies=PACKAGES, builder_sha256=digest(source/'build_native.py'),
                  sources={name:digest(source/file) for name,file in SOURCES},
                  binaries={name:digest(built/name) for name,_ in SOURCES})
    if recipe['platform'] != 'Linux' or recipe['machine'] != 'x86_64':
        raise ValueError('This pinned native generation requires Linux x86-64')
    for name, _ in SOURCES:
        if (built/(name+'.source-sha256')).read_text() != recipe['sources'][name]:
            raise ValueError('Native binary/source marker mismatch')
    signature = key(recipe); name = 'native-toolchain-'+signature[:16]
    directory = state.root/'data/toolchains'/signature
    with state.lock(name):
        if not state.valid(name, signature):
            state.start(name, signature); paths = []
            for binary, _ in SOURCES:
                target = directory/binary; atomic_bytes(target, (built/binary).read_bytes())
                target.chmod(0o755); paths.append(target)
                marker = directory/(binary+'.source-sha256')
                atomic_bytes(marker, recipe['sources'][binary].encode()); paths.append(marker)
            recipe['runtime_dependencies'] = subprocess.run(['ldd', str(directory/'local-ballmerge')],
                check=True, capture_output=True, text=True).stdout.splitlines()
            receipt = directory/'provenance.json'; atomic_json(receipt, recipe); paths.append(receipt)
            state.stage(name, signature, paths, dict(binary_sha256=recipe['binaries'],
                source_sha256=recipe['sources'], machine=recipe['machine'],
                execution_compatibility='Linux x86-64 and listed dynamic libraries; rerun native planar tests after recovery'))
        elif state.remote:
            state.remote.sync_task(state, name)
        if state.remote and state.task(name)['state'] != 'durable':
            raise RuntimeError('Exact native binaries await persistent checkpoint')
        # Artifact restoration preserves bytes, not executable mode. Restore it
        # explicitly only after the checkpoint's complete hash verification.
        for binary, _ in SOURCES:
            (directory/binary).chmod(0o755)
    return dict(task=name, directory=str(directory), signature=signature, binaries=recipe['binaries'])


if __name__ == '__main__':
    from .remote import Remote
    from .state import State
    print('Ready for private native checkpoint JSON on hidden stdin.', flush=True)
    settings=json.loads(input()); root=Path(settings['root'])
    state=State(root,json.loads((root/'config.json').read_text()),Remote(settings['base'],settings.pop('token')))
    print(json.dumps(checkpoint(state,settings['built'])),flush=True)
