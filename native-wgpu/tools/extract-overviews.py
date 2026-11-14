#!/usr/bin/env python3
"""Extract ONLY the six catalogued .bin payloads from the original ZIPs, checking hashes."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile

root = Path(__file__).resolve().parents[2]
archive_dir, output_dir = map(Path, sys.argv[1:3])
expected = {p['sha256']: p['bytes'] for p in json.loads((root/'capacitor/overview-catalog.json').read_text())['files']}
output_dir.mkdir(parents=True, exist_ok=True)
found = set()
for archive in sorted(archive_dir.glob('*.zip')):
    with zipfile.ZipFile(archive) as pack:
        for item in pack.infolist():
            digest = item.filename.removesuffix('.bin')
            if digest not in expected:
                continue
            if item.file_size != expected[digest]:
                raise ValueError(f'Invalid payload length in {archive.name}')
            data = pack.read(item)
            if hashlib.sha256(data).hexdigest() != digest:
                raise ValueError(f'Invalid payload hash in {archive.name}')
            # Explicit fixed filename; never extract arbitrary archive paths.
            (output_dir/(digest+'.bin')).write_bytes(data)
            found.add(digest)
if found != expected.keys():
    raise ValueError(f'Missing {len(expected.keys()-found)} overview payloads')
print(f'Verified all {len(found)} packs: {sum(expected.values()):,} original bytes')
