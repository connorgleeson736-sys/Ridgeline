"""Verify exact published assets, then save an immutable external proof."""
from pathlib import Path
import importlib.util,json,re,sys,termios,urllib.request,subprocess
spec=importlib.util.spec_from_file_location('south',Path(__file__).with_name('pipeline.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
settings=termios.tcgetattr(0);hidden=settings[:];hidden[3]&=~termios.ECHO;termios.tcsetattr(0,termios.TCSANOW,hidden)
print('Ready for private Site credentials (hidden).',flush=True)
try:credentials=json.loads(sys.stdin.readline())
finally:termios.tcsetattr(0,termios.TCSANOW,settings)
remote=m.Remote(credentials['url'],credentials['token'])
def get(path):
 request=urllib.request.Request(credentials['url'].rstrip('/')+path,headers={'OAI-Sites-Authorization':'Bearer '+credentials['token'],'Cache-Control':'no-cache'})
 with urllib.request.build_opener(m.NoRedirect).open(request,timeout=45)as response:
  assert response.status==200;return response.read()
checks={}
from concurrent.futures import ThreadPoolExecutor
def verify_module(item):
 name,query=item
 data=get('/'if name=='index.html'else'/'+name+query);local=(m.ROOT/'public'/name).read_bytes();assert m.digest(data)==m.digest(local),f'Published source mismatch: {name}';checks[name]=m.digest(data)
with ThreadPoolExecutor(max_workers=4)as pool:list(pool.map(verify_module,[('south-overviews.js','?v=1'),('viewer.mjs','?v=36'),('index.html',''),('native-residency.js','?v=19'),('raster-residency.js','?v=10'),('imagery-diagnostics.js','?v=19'),('app.js','?v=34'),('tile-cache.js','?v=9'),('prepare-ahead.js','?v=6'),('terrain-composition.js','?v=3'),('regional-imagery.js','?v=9'),('cook-reference-overviews.js','?v=1')]+[('regional-scheduler.js','?v=1'),('tile-retry.js','?v=1'),('resource-ledger.js','?v=1'),('tile-trace.js','?v=1'),('dem-revision.js','?v=1'),('terrain-visibility.js','?v=2'),('regional-imagery-worker.js','?v=6'),('regional-blend.js','?v=4'),('build-info.js','?v=8'),('terrain-streaming.js','?v=2'),('imagery-preload.js','?v=4'),('topographic-overlays.js','?v=2'),('loading-controls.js','?v=2'),('style.css','?v=18')]))
reference=json.loads(re.search(r'const data=(.*);if\(typeof module',(m.ROOT/'public/cook-reference-overviews.js').read_text())[1]);referenceFiles=sorted({r if isinstance(r,str)else r['natural']for r in reference['tiles'].values()});referenceSamples=referenceFiles[::max(1,len(referenceFiles)//3)][:3]
for path in referenceSamples:
 data=get(path);assert m.digest(data)==m.digest((m.ROOT/'public'/path.lstrip('/')).read_bytes());checks[path]=m.digest(data)
index=json.loads(re.search(r'const data=(.*);if\(typeof module',(m.ROOT/'public/south-overviews.js').read_text())[1]);assert index['run']==m.RUN and len(index['tiles'])==5313
static=sorted((m.ROOT/'public/assets/south-overviews-v1').glob('*.webp'));samples=static[::max(1,len(static)//4)][:4]
for file in samples:
 data=get('/assets/south-overviews-v1/'+file.name);assert m.digest(data)==m.digest(file.read_bytes());checks[file.name]=m.digest(data)
entry=index['tiles']['12/3983/2600'];row=entry if isinstance(entry,list)or 'url'in entry else entry['natural'];row=row['range']if isinstance(row,dict)else row
asset=json.loads((m.ROOT/'qa/south-island/range-proof.json').read_text());assert index['packs'][row[0]]==asset['file']and row[1]==asset['offset']and row[2]==asset['bytes'];offset,size=asset['offset'],asset['bytes']
status,headers,data=remote.request('GET','files/'+asset['file']+'.bin?tile=publication-proof',headers={'Range':f'bytes={offset}-{offset+size-1}'})
headers={key.lower():value for key,value in headers.items()}
assert status==206 and headers.get('content-range','').startswith(f'bytes {offset}-{offset+size-1}/')and len(data)==size and m.digest(data)==asset['sha256']
report={'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=m.ROOT,text=True).strip(),'defaultFourGBCacheMiB':352,'run':m.RUN,'overviewCoordinates':len(index['tiles']),'publishedSourceHashes':checks,'staticImageSamples':len(samples),'productionCookRangeSha256':asset['sha256'],'allChecksPassed':True,'caveat':'Authenticated HTTP delivery, not live GPU or device FPS.'}
proof=remote.record('status','publication-verified',report);print(json.dumps({'report':report,'persistentProof':proof}),flush=True)
