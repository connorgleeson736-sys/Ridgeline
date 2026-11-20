#!/usr/bin/env python3
"""Audit the PACKAGED 0.18 Android source/data, including build-web adaptations.
Never print app.js or keys. Website-only reference substitutions are forbidden.
"""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

p=argparse.ArgumentParser();p.add_argument('apk',type=Path);p.add_argument('--output',type=Path);args=p.parse_args()
root=Path(__file__).resolve().parents[2];assets=root/'native-wgpu/android/app/src/main/assets'
def assignment(text,marker):
    start=text.index(marker)+len(marker);start=text.index('{',start)
    return json.JSONDecoder().raw_decode(text[start:])[0]
with zipfile.ZipFile(args.apk) as apk:
    names=apk.namelist();app=apk.read('assets/public/app.js').decode()
    assert not any('cook-reference' in n for n in names)
    assert 'ridge-reference=cook-reference-v1' not in app and 'let directSampling=true' in app
    index=json.loads((assets/'overview-index.json').read_text());assert 'reference' not in index
    atlas=assignment(apk.read('assets/public/gap-imagery-atlas.js').decode(),'const data=')
    assert atlas==json.loads((assets/'gap-atlas.json').read_text())
    south=assignment(apk.read('assets/public/south-overviews.js').decode(),'RidgeSouthOverviews=')
    for key,entry in index['coarse'].items():
        for mode,path in entry.items():
            reference=south['tiles'][key];row=reference.get(mode,reference)
            assert row['url'].lstrip('/')==path
            assert (assets/path).read_bytes()==apk.read('assets/public/'+path)
    for row in atlas['coarse'].values():
        path=row['url'].lstrip('/')
        assert (assets/path).read_bytes()==apk.read('assets/public/'+path)
    manifest=json.loads(apk.read('assets/public/overview-manifest.json'));assert manifest['bundled']
    assert manifest['files']==index['files']
    for pack in index['files']:
        data=apk.read('assets/public/overview-packs/'+pack['sha256']+'.bin')
        assert len(data)==pack['bytes'] and hashlib.sha256(data).hexdigest()==pack['sha256']
    result={'reference':'android-preview-download /0.18.0 Full','source':'d880143418ac46ece98adff0b3b2fbaedf044d46',
        'apkSha256':hashlib.file_digest(args.apk.open('rb'),'sha256').hexdigest(),'directDefault':True,'cookSubstitution':False,
        'exactGapEntries':len(atlas['entries']),'exactCoarseCoordinates':len(index['coarse']),'exactCoarsePhotoFiles':len({path for entry in index['coarse'].values()for path in entry.values()}),
        'exactOriginalPackPayloads':len(index['files']),'scope':'Packaged reference routing/assets, not real-terrain visual parity'}
    print(json.dumps(result,indent=2))
    if args.output:args.output.write_text(json.dumps(result,indent=2)+'\n')
