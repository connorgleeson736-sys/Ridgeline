#!/usr/bin/env python3
"""Verify native-only APK assets and the complete original overview payloads.
Runs on the build host, never on app launch. Prints hashes, never API keys.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

parser=argparse.ArgumentParser()
parser.add_argument('apk',type=Path)
parser.add_argument('--output',type=Path)
args=parser.parse_args()
root=Path(__file__).resolve().parents[2]
catalog=json.loads((root/'capacitor/overview-catalog.json').read_text())
with zipfile.ZipFile(args.apk) as apk:
    names=apk.namelist()
    assert not any(n.endswith(('.js','.mjs','.html')) for n in names), 'Unexpected web runtime assets'
    for name in names:
        if name.endswith('.dex'):
            data=apk.read(name)
            assert b'Landroid/webkit/WebView;' not in data and b'Lcom/getcapacitor/' not in data, 'Web runtime dependency'
    payloads=[];ranges=0
    for entry in catalog['files']:
        digest=entry['sha256'];path='assets/overview-packs/'+digest+'.bin'
        info=apk.getinfo(path);assert info.compress_type==zipfile.ZIP_STORED,'Range packs must be uncompressed'
        data=apk.read(info)
        assert len(data)==entry['bytes'] and hashlib.sha256(data).hexdigest()==digest,'Overview payload mismatch'
        unique=set()
        for row in catalog['tiles'].values():
            for variant in ([row['raw'],row['natural']] if 'raw' in row else [row]):
                if variant['file']!=digest: continue
                offset,size=variant['offset'],variant['bytes']
                assert offset>=0 and size>=20 and offset+size<=len(data)
                header=data[offset:offset+20]
                assert header[:4]==b'RIFF' and header[8:12]==b'WEBP' and struct.unpack_from('<I',header,4)[0]+8==size
                unique.add((offset,size))
        ranges+=len(unique);payloads.append({'sha256':digest,'bytes':len(data),'imageRanges':len(unique)})
    libs=[n for n in names if n.startswith('lib/') and n.endswith('.so')]
    assert libs and all(n.endswith('/libridgeline_renderer.so') for n in libs)
    abi_machines={'arm64-v8a':183,'armeabi-v7a':40,'x86_64':62}
    for lib in libs:
        binary=apk.read(lib);assert binary[:4]==b'\x7fELF'
        assert struct.unpack_from('<H',binary,18)[0]==abi_machines[lib.split('/')[1]]
    assert len(payloads)==6
    result={'scope':'Prototype packaging only, not feature/device/visual acceptance','apk':args.apk.name,
        'bytes':args.apk.stat().st_size,'sha256':hashlib.file_digest(args.apk.open('rb'),'sha256').hexdigest(),
        'nativeLibraries':libs,'webRuntimeAssets':0,'webViewOrCapacitorDexReferences':0,
        'overviewPayloadBytes':sum(row['bytes'] for row in payloads),'imageRanges':ranges,'packs':payloads}
    print(json.dumps(result,indent=2))
    if args.output: args.output.write_text(json.dumps(result,indent=2)+'\n')
