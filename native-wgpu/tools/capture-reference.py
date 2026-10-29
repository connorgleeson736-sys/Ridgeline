#!/usr/bin/env python3
"""Capture unchanged authoritative0.18FullAPK assets in supported host Chromium.
Requires Pillow/Playwright Python and Chromium. Provider TLS stays verified through
inherited proxy/CA settings. This emulates native HTTP/range transport only; it is
not Android reference execution, a phone benchmark, or an accepted parity test.
"""
import argparse,asyncio,base64,hashlib,json,mimetypes,os,re,ssl,threading,time,urllib.request,urllib.error
from pathlib import Path
from zipfile import ZipFile
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from urllib.parse import urlsplit
from playwright.async_api import async_playwright
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('apk',type=Path);parser.add_argument('output',type=Path)
parser.add_argument('--chromium',default='/usr/bin/chromium')
args=parser.parse_args();APK=args.apk;OUT=args.output
apk_sha=hashlib.file_digest(APK.open('rb'),'sha256').hexdigest()
if apk_sha!='b4d286f5d6835447237eecaa15d5ee9a8bdf2870d56133c699f0b9ec3a173ffd':
 raise SystemExit('Expected the exact published0.18FullAPK; checksum differs')
OUT.mkdir(parents=True,exist_ok=True)
CACHE=OUT/'http-cache';CACHE.mkdir(exist_ok=True)
archive=ZipFile(APK); records={};failures={};lock=threading.Lock()
ca=os.environ.get('NODE_EXTRA_CA_CERTS');tls=ssl.create_default_context(cafile=ca) if ca else ssl.create_default_context()
def result(data,kind='application/octet-stream',status=200):return status,{'Content-Type':kind,'Access-Control-Allow-Origin':'*','Cache-Control':'public,max-age=21600'},data
def proxy(url):
 host=urlsplit(url).hostname
 if host not in ['basemaps.linz.govt.nz','services.arcgisonline.com','s3.amazonaws.com']:return result(b'',status=403)
 ident=hashlib.sha256(url.encode()).hexdigest();file=CACHE/ident
 if file.exists() and time.time()-file.stat().st_mtime<21600:
  data=file.read_bytes()
  kind='image/png' if data.startswith(b'\x89PNG') else 'image/webp' if data.startswith(b'RIFF') else 'image/jpeg' if data.startswith(b'\xff\xd8') else 'application/octet-stream'
  with lock:records[ident]={'host':host,'path':urlsplit(url).path,'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data),'cacheHit':True}
  return result(data,kind)
 try:
  with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Ridgeline-Reference-Validation/1'}),context=tls,timeout=25) as response:
   data=response.read(8*1024*1024+1);assert len(data)<=8*1024*1024
   # Publish complete fixture bodies atomically, including concurrent requests.
   import tempfile
   with tempfile.NamedTemporaryFile(dir=CACHE,delete=False) as tmp:tmp.write(data);temp=Path(tmp.name)
   temp.replace(file)
   with lock:records[ident]={'host':host,'path':urlsplit(url).path,'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data)}
   return result(data,response.headers.get('Content-Type','application/octet-stream'))
 except urllib.error.HTTPError as e:
  with lock:failures[str(e.code)]=failures.get(str(e.code),0)+1
  return result(b'',status=e.code)
 except Exception as e:
  with lock:failures[type(e).__name__]=failures.get(type(e).__name__,0)+1
  return result(b'',status=502)
def local(path):
 u=urlsplit(path);p=u.path
 if p.startswith('/_ridge/linz/'):return proxy('https://basemaps.linz.govt.nz'+p.removeprefix('/_ridge/linz')+('?' +u.query if u.query else ''))
 if p.startswith('/_ridge/esri/'):return proxy('https://services.arcgisonline.com/'+p.removeprefix('/_ridge/esri/'))
 m=re.fullmatch(r'/_ridge/overviews/([a-f0-9]{64})/(\d+)/(\d+)',p)
 if m:
  file,offset,size=m.groups();offset=int(offset);size=int(size)
  if size>8*1024*1024:return result(b'',status=413)
  with archive.open('assets/public/overview-packs/'+file+'.bin') as stream:stream.seek(offset);data=stream.read(size)
  assert len(data)==size
  with lock:records[p]={'sha256':hashlib.sha256(data).hexdigest(),'bytes':size,'kind':'original-apk-overview-range'}
  return result(data,'image/webp')
 name='assets/public/'+(p.lstrip('/') or 'index.html')
 try:data=archive.read(name)
 except KeyError:return result(b'',status=404)
 with lock:records[p]={'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data),'kind':'unchanged-apk-asset'}
 kind='application/javascript' if name.endswith(('.js','.mjs')) else mimetypes.guess_type(name)[0] or 'application/octet-stream'
 return result(data,kind)
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*args):pass
 def do_GET(self):
  try:status,headers,data=local(self.path)
  except Exception:status,headers,data=result(b'',status=500)
  self.send_response(status)
  for k,v in headers.items():self.send_header(k,v)
  self.send_header('Content-Length',str(len(data)));self.end_headers()
  try:self.wfile.write(data)
  except BrokenPipeError:pass
server=ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start()
async def main():
 async with async_playwright() as pw:
  browser=await pw.chromium.launch(executable_path=args.chromium,headless=True,args=['--no-sandbox','--disable-dev-shm-usage','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader'])
  context=await browser.new_context(viewport={'width':320,'height':640},device_scale_factor=1,service_workers='block')
  await context.add_init_script("""(()=>{let mobile;Object.defineProperty(globalThis,'RidgeMobile',{configurable:true,get(){return mobile},set(value){mobile=value;const original=value.attach;value.attach=function(api){globalThis.__referenceApi=api;return original.apply(this,arguments)}}})})();""")
  async def route_handler(route):
   url=route.request.url;u=urlsplit(url)
   status,headers,data=await asyncio.to_thread(local,u.path+('?' +u.query if u.query else '')) if u.hostname=='localhost' else await asyncio.to_thread(proxy,url)
   try:await route.fulfill(status=status,headers=headers,body=data)
   except Exception:pass
  await context.route('https://**/*',route_handler)
  page=await context.new_page();errors=[]
  page.on('pageerror',lambda e:errors.append(re.sub(r'(?:https?|ridge)://\S+','<url>',str(e))[:200]))
  await page.goto(f'http://127.0.0.1:{server.server_port}/',wait_until='domcontentloaded',timeout=60000)
  try:await page.wait_for_function('globalThis.__referenceApi?.isReady()',timeout=120000)
  except Exception:print('Ready wait failed')
  print('state',await page.evaluate("({load:document.getElementById('load-state')?.textContent,canvas:document.querySelectorAll('canvas').length,api:!!globalThis.__referenceApi})"),flush=True)
  # Await source loading with a bounded settling window, preserving original policy.
  for i in range(45):
   state=await page.evaluate("(()=>{const m=globalThis.__referenceApi?.getMap();return m?{loaded:m.loaded(),tiles:m.areTilesLoaded(),moving:m.isMoving(),zoom:m.getZoom(),elevation:m.getCenterElevation(),clamped:m.getCenterClampedToGround(),terrainElevation:m.queryTerrainElevation(m.getCenter()),center:m.getCenter().toArray(),pitch:m.getPitch(),bearing:m.getBearing(),pixelRatio:m.getPixelRatio(),canvas:[m.getCanvas().width,m.getCanvas().height]}:null})()")
   if i%5==0:print('settle',i,state,'requests',len(records),'failures',failures,flush=True)
   if state and state['loaded'] and state['tiles'] and not state['moving']:break
   await page.wait_for_timeout(1000)
  await page.screenshot(path=str(OUT/'reference-full.png'))
  image=await page.evaluate("new Promise(resolve=>{const m=__referenceApi.getMap();m.once('render',()=>resolve(m.getCanvas().toDataURL('image/png')));m.triggerRepaint()})")
  (OUT/'reference-canvas.png').write_bytes(base64.b64decode(image.split(',',1)[1]))
  cover=await page.evaluate("(()=>{const terrain=__referenceApi.getMap().terrain;return terrain.tileManager.getRenderableTiles().map(t=>({geometry:t.tileID.canonical,source:terrain.tileManager.getSourceTile(t.tileID,true)?.tileID.canonical}))})()")
  report={'terrainCover':cover,'scope':'Unchanged APK assets in host Chromium with reproduced local HTTP provider/range transport; not Android reference execution or native visual parity','browser':browser.version,'apkSha256':apk_sha,'state':state,'errors':errors,'transportFailures':failures,'assetsAndSources':records}
  (OUT/'report.json').write_text(json.dumps(report,indent=2)+'\n')
  await browser.close()
  if not state or not state['loaded'] or not state['tiles'] or state['moving'] or errors or any(k!='404' for k in failures):
   raise SystemExit('Reference did not settle cleanly; inspect the saved scoped report')
try:asyncio.run(main())
finally:server.shutdown()
