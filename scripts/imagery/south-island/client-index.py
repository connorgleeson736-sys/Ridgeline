"""Compact already verified R2 ranges; never place the image corpus in Site code."""
from pathlib import Path
import hashlib,json,os
root=Path(__file__).resolve().parents[3]
work=Path(os.environ.get('RIDGE_SOUTH_WORK','/workspace/scratch/3dd2ae8bd0a2/south-island-overviews-v1'))
d=json.loads((work/'tile-index.json').read_text());packs=sorted({r['file']for v in d['tiles'].values()for r in v.values()});lookup={p:i for i,p in enumerate(packs)}
hot=root/'public/assets/south-overviews-v1';hot.mkdir(parents=True,exist_ok=True);hot_files={}
def compact(r,key):
 a=[lookup[r['file']],r['offset'],r['bytes']]
 if int(key.split('/')[0])<=8:
  data=(work/'tiles'/(r['sha256']+'.webp')).read_bytes();assert hashlib.sha256(data).hexdigest()==r['sha256'];name=r['sha256'][:24]+'.webp';(hot/name).write_bytes(data);hot_files[name]=len(data)
  return {'url':'/assets/south-overviews-v1/'+name,'range':a}
 return a
tiles={}
for k,modes in d['tiles'].items():
 a,b=compact(modes['raw'],k),compact(modes['natural'],k);tiles[k]=a if a==b else {'raw':a,'natural':b}
public={k:d[k]for k in ['version','run','sourceZoom','maximumZoom','grade']};public.update(packs=packs,tiles=tiles)
s='/* Persistent South Island overview ranges. See SOUTH_ISLAND_IMAGERY_STATUS.md. */\n(function(r){const data='+json.dumps(public,separators=(',',':'))+';if(typeof module!=="undefined")module.exports=data;else r.RidgeSouthOverviews=data;})(globalThis);\n'
p=root/'public/south-overviews.js';t=p.with_suffix('.js.part');t.write_text(s);t.replace(p)
print(json.dumps({'publicIndexBytes':len(s),'packs':len(packs),'coordinates':len(tiles),'staticCoarseFiles':len(hot_files),'staticCoarseBytes':sum(hot_files.values()),'SHA256':hashlib.sha256(s.encode()).hexdigest()}))
