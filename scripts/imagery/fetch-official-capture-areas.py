"""Fetch the reviewed public LINZ capture files. No API key is required."""
from pathlib import Path
import argparse,json,urllib.request,hashlib,concurrent.futures,gzip
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);a=p.parse_args();a.work.mkdir(parents=True,exist_ok=True)
manifest=json.loads((ROOT/'qa/official-surveys/manifest.json').read_text())
def fetch(r):
 if 'captureURL'not in r:return
 folder=a.work/r['id'];folder.mkdir(exist_ok=True);path=folder/'capture-area.geojson'
 if path.exists():data=path.read_bytes()
 else:
  for attempt in range(3):
   try:data=urllib.request.urlopen(r['captureURL'],timeout=35).read();data=gzip.decompress(data)if data[:2]==b'\x1f\x8b'else data;break
   except Exception:
    if attempt==2:raise RuntimeError('Incomplete official survey download: '+r['name'])from None
 assert hashlib.sha256(data).hexdigest()==r['sha256'],'Published capture area changed; review the source manifest before rebuilding: '+r['name']
 json.loads(data);path.write_bytes(data)
with concurrent.futures.ThreadPoolExecutor(max_workers=12)as pool:list(pool.map(fetch,manifest))
(a.work/'manifest.json').write_text(json.dumps(manifest,indent=2));print('Verified public LINZ capture files against the reviewed source manifest')
