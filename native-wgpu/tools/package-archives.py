#!/usr/bin/env python3
"""Copy the six ORIGINAL ZIPs; index their STORED payloads for direct native range reads."""
import hashlib
import json
from pathlib import Path
import shutil
import struct
import sys
import zipfile

root=Path(__file__).resolve().parents[2]
source,out=map(Path,sys.argv[1:3]);out.mkdir(parents=True,exist_ok=True)
catalog={p['sha256']:p for p in json.loads((root/'capacitor/overview-catalog.json').read_text())['files']}
manifest=json.loads((root/'native-wgpu/overview-archives.json').read_text())
archives={}
for record in manifest['files']:
    path=source/record['name']
    with path.open('rb') as stream: digest=hashlib.file_digest(stream,'sha256').hexdigest()
    assert path.stat().st_size==record['bytes'] and digest==record['sha256'], 'Original ZIP mismatch'
    with zipfile.ZipFile(path) as archive:
        assert len(archive.infolist())==1
        item=archive.infolist()[0];sha=record['payload'];expected=catalog[sha]
        assert item.filename==sha+'.bin' and item.compress_type==zipfile.ZIP_STORED and not(item.flag_bits&1)
        data=archive.read(item)
        assert len(data)==expected['bytes'] and hashlib.sha256(data).hexdigest()==sha
        with path.open('rb') as stream:
            stream.seek(item.header_offset);header=stream.read(30)
            assert header[:4]==b'PK\x03\x04'
            filename,extra=struct.unpack_from('<HH',header,26)
            offset=item.header_offset+30+filename+extra
        archives[sha]={'asset':'overview-packs/'+path.name,'offset':offset,'bytes':item.file_size,'archiveSha256':digest}
    shutil.copyfile(path,out/path.name)
assert len(archives)==6
print(json.dumps(archives))
