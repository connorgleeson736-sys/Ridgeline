#!/usr/bin/env python3
"""Verify exported overview ZIPs against the pinned original index; build offline packs."""
import argparse, hashlib, io, json, pathlib, re, subprocess, zipfile
from PIL import Image
ROOT=pathlib.Path(__file__).resolve().parents[2]
def sha(b):return hashlib.sha256(b).hexdigest()
def encode(v):return (json.dumps(v,sort_keys=True,separators=(',',':'))+'\n').encode()
def prepare(zips,output,catalog):
 output.mkdir(parents=True,exist_ok=True);metadata={};image_locations={};opened=[]
 try:
  for p in zips:
   z=zipfile.ZipFile(p);opened.append(z)
   for entry in z.infolist():
    name=entry.filename
    if name.endswith('/image-manifest.json') or name.endswith('/tile-index.json'):metadata[pathlib.PurePosixPath(name).name]=json.loads(z.read(entry))
    match=re.search(r'(?:^|/)tiles/([a-f0-9]{64})\.webp$',name)
    if match:
     if match[1] in image_locations:raise ValueError('Duplicate exported image '+match[1])
     image_locations[match[1]]=(z,entry)
  exported=metadata['tile-index.json'];manifest=metadata['image-manifest.json']
  original=json.loads(subprocess.check_output(['node','--input-type=module','-e',"import fs from'node:fs';import vm from'node:vm';const c={};vm.runInNewContext(fs.readFileSync('public/south-overviews.js','utf8'),c);process.stdout.write(JSON.stringify(c.RidgeSouthOverviews));"],cwd=ROOT))
  expected=json.loads((ROOT/'SOUTH_ISLAND_IMAGERY_VALIDATION.json').read_text())['persistentIndex']['sha256']
  reconstructed={k:original[k] for k in ['version','run','sourceZoom','maximumZoom','grade']};reconstructed['tiles']={};needed={}
  assert set(exported['tiles'])==set(original['tiles']), 'Coordinate coverage differs from original'
  for coord,entry in original['tiles'].items():
   reconstructed['tiles'][coord]={}
   for mode in ['raw','natural']:
    row=entry.get(mode,entry) if isinstance(entry,dict) else entry;r=row.get('range',row) if isinstance(row,dict) else row
    path=exported['tiles'][coord][mode];m=manifest[path];h=pathlib.PurePosixPath(path).stem
    assert re.fullmatch('[a-f0-9]{64}',h) and m['sha256']==h and m['bytes']==r[2] and (m['width'],m['height'])==(256,256)
    reconstructed['tiles'][coord][mode]={'file':original['packs'][r[0]],'offset':r[1],'bytes':r[2],'sha256':h}
    if int(coord.split('/')[0])>=9:needed[h]=m['bytes']
  assert sha(encode(reconstructed))==expected, 'Authoritative index checksum does not match'
  # Check every referenced overview, including already-bundled coarse images.
  referenced={pathlib.PurePosixPath(p).stem for e in exported['tiles'].values() for p in e.values()}
  for h in sorted(referenced):
   z,e=image_locations[h];b=z.read(e);assert sha(b)==h
   with Image.open(io.BytesIO(b)) as image:assert image.format=='WEBP' and image.size==(256,256)
  groups=[];group=[];size=0
  for h,n in sorted(needed.items()):
   if size+n>60*1024*1024 and group:groups.append(group);group=[];size=0
   group.append(h);size+=n
  if group:groups.append(group)
  files=[];locations={};zip_paths=[]
  for i,group in enumerate(groups,1):
   temp=output/f'pack-{i}.part';digest=hashlib.sha256();offset=0
   with temp.open('wb') as out:
    for h in group:
     z,e=image_locations[h];b=z.read(e);assert sha(b)==h;out.write(b);digest.update(b);locations[h]=[i-1,offset,len(b)];offset+=len(b)
   h=digest.hexdigest();binary=output/(h+'.bin');temp.replace(binary);files.append({'sha256':h,'bytes':offset})
   target=output/f'Ridgeline-Overviews-0.3.0-{i}-of-{len(groups)}.zip'
   with zipfile.ZipFile(target,'w',compression=zipfile.ZIP_STORED) as z:z.write(binary,h+'.bin')
   zip_paths.append({'file':target.name,'bytes':target.stat().st_size,'sha256':sha(target.read_bytes())})
  tiles={}
  for coord,entry in exported['tiles'].items():
   if int(coord.split('/')[0])<9:continue
   tiles[coord]={}
   for mode,p in entry.items():
    i,offset,n=locations[pathlib.PurePosixPath(p).stem];tiles[coord][mode]={'file':files[i]['sha256'],'offset':offset,'bytes':n}
  result={'version':original['version'],'run':original['run'],'sourceIndexSHA256':expected,'minimumZoom':9,'maximumZoom':12,'files':files,'tiles':tiles}
  catalog.write_bytes(encode(result))
  report={'sourceIndexSHA256':expected,'verifiedImages':len(referenced),'coordinateCount':len(exported['tiles']),'variantMappings':sum(len(v) for v in exported['tiles'].values()),'extraImages':len(needed),'packBytes':sum(v['bytes'] for v in files),'packs':zip_paths,'cookReferenceAssetsIncluded':False,'method':'Lossless repacking of hash-verified original WebP bytes; no resampling or colour changes'}
  (output/'overview-validation.json').write_bytes(encode(report));print(json.dumps(report,indent=2))
 finally:
  for z in opened:z.close()
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--catalog',type=pathlib.Path,required=True);p.add_argument('zips',nargs='+',type=pathlib.Path);a=p.parse_args();prepare(a.zips,a.output,a.catalog)
