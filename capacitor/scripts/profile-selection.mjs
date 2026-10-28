// Exploratory CPU sampling with controlled provider fixtures and software WebGL.
// This deliberately exercises selection synchronously; it does not measure phone FPS.
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {chromium} from 'playwright-core';
import {createServer} from 'node:http';
import {readFile,writeFile,stat,open} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {mkdir} from 'node:fs/promises';
import {deflateSync} from 'node:zlib';
import assert from 'node:assert/strict';
const diagnostics=process.env.RIDGELINE_DIAGNOSTICS||'/tmp/ridgeline-capacitor-check';await mkdir(diagnostics,{recursive:true});
function png(){const chunk=(type,data)=>{const head=Buffer.from(type),b=Buffer.concat([head,data]);let crc=0xffffffff;for(const n of b){crc^=n;for(let i=0;i<8;i++)crc=(crc>>>1)^((crc&1)?0xedb88320:0);}const len=Buffer.alloc(4),tail=Buffer.alloc(4);len.writeUInt32BE(data.length);tail.writeUInt32BE((crc^0xffffffff)>>>0);return Buffer.concat([len,b,tail]);};const header=Buffer.alloc(13);header.writeUInt32BE(256,0);header.writeUInt32BE(256,4);header[8]=8;header[9]=2;const data=Buffer.alloc(256*(256*3+1));for(let y=0;y<256;y++)for(let x=0;x<256;x++){let i=y*769+1+x*3;data[i]=1;data[i+1]=173;data[i+2]=176;}return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',header),chunk('IDAT',deflateSync(data)),chunk('IEND',Buffer.alloc(0))]);}
const base=fileURLToPath(new URL('../www/',import.meta.url));
const engineOverride=process.env.RIDGELINE_PROFILE_BASELINE_APK?execFileSync('unzip',['-p',path.resolve(process.env.RIDGELINE_PROFILE_BASELINE_APK),'assets/public/assets/maplibre-gl.mjs'],{maxBuffer:8*1048576}):null;
const engineBytes=engineOverride||await readFile(path.join(base,'assets/maplibre-gl.mjs'));
const engineSha256=createHash('sha256').update(engineBytes).digest('hex');
const bundled=JSON.parse(await readFile(path.join(base,'overview-manifest.json'),'utf8')).bundled;
const overviewData=process.env.RIDGELINE_OVERVIEW_DATA||(bundled?path.join(base,'overview-packs'):null);
const server=createServer(async(req,res)=>{try{const p=path.resolve(base,'.'+decodeURIComponent(new URL(req.url,'http://localhost').pathname));if(p!==path.resolve(base)&&!p.startsWith(base))throw Error('Forbidden');const file=(await stat(p)).isDirectory()?path.join(p,'index.html'):p;const type={'.js':'text/javascript','.mjs':'text/javascript','.css':'text/css','.html':'text/html','.png':'image/png','.webp':'image/webp','.json':'application/json'}[path.extname(file)]||'application/octet-stream';res.writeHead(200,{'content-type':type});const data=file===path.join(base,'assets/maplibre-gl.mjs')?engineBytes:await readFile(file);res.end(path.basename(file)==='app.js'?Buffer.concat([data,Buffer.from(";globalThis.__testMap=map;map.on('error',e=>{if(e.error?.name!=='AbortError')console.error(e.error?.message||'Map error');});")]):data);}catch{res.writeHead(404);res.end();}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
const errors=[],consoleErrors=[],requests=[];let page,archiveReads=0;
try{
 const context=await browser.newContext({viewport:{width:412,height:915},deviceScaleFactor:2,isMobile:true,hasTouch:true,reducedMotion:'reduce'});

 // Mock only Android's data bridge; UI, map rendering and gestures run in Chromium.
 await context.addInitScript(({bundled})=>{
  window.CapacitorCustomPlatform={name:'android'};
  localStorage.setItem('ridgeline-phone-mode-v1','1');localStorage.setItem('ridgeline-filtering-v1',JSON.stringify({bilinear:false,mipmaps:false,trilinear:false,anisotropic:false,fade:false,colour:false}));
  localStorage.setItem('ridgeline-routes-v1',JSON.stringify([{id:'second-route',name:'Second route',points:[[170.15,-43.58],[170.16,-43.59]],updated:'2026-10-27T00:00:00Z'}]));
  window.__nativeCalls=[];window.__regions=[{topo:true,zoom:16,id:'test-region',name:'Saved test region',state:'complete',done:12,total:12,bytes:1048576,bounds:[170.05,-43.65,170.2,-43.5]}];
  const methods=['exportDiagnostics','loadingStats','exportRegionPdf','estimateRegion','downloadRegion','getSettings','overviewStatus','listRegions','pauseRegion','resumeRegion','removeRegion','startLocation','stopLocation','addListener','removeListener'];
  window.Capacitor={PluginHeaders:[{name:'RidgelineOffline',methods:methods.map(name=>({name,rtype:'promise'}))}],nativePromise:async(plugin,method,args)=>{
   window.__nativeCalls.push({method,args});if(method==='estimateRegion')return {total:15,estimatedMiB:1.2,highMiB:1.5,samples:9};
   if(method==='getSettings')return {offlineOnly:false};
   if(method==='overviewStatus')return {bundled,complete:bundled,done:bundled?6:0,total:6,bytes:bundled?364762502:0,importedBytes:0};
   if(method==='listRegions')return {regions:window.__regions};
   if(method==='removeRegion')window.__regions=window.__regions.filter(r=>r.id!==args.id);
   return {};
  }};
 },{bundled});
 const dem=png();
 const imagery=await readFile(new URL('../../public/assets/cook-reference-v1/b872335eddfb7fc3c554d59a.webp',import.meta.url));
 await context.route('https://localhost/_ridge/**',async route=>{const url=route.request().url();requests.push(url);if(overviewData&&url.includes('/_ridge/overviews/')){const match=new URL(url).pathname.match(/^\/_ridge\/overviews\/([a-f0-9]{64})\/(\d+)\/(\d+)$/);assert.ok(match);const file=await open(path.join(overviewData,match[1]+'.bin'));let data;try{data=Buffer.alloc(Number(match[3]));const result=await file.read(data,0,data.length,Number(match[2]));assert.equal(result.bytesRead,data.length);}finally{await file.close();}archiveReads++;return route.fulfill({status:200,headers:{'access-control-allow-origin':'*'},contentType:'image/webp',body:data});}if(url.includes('.pbf'))return route.fulfill({status:200,headers:{'access-control-allow-origin':'*'},contentType:'application/x-protobuf',body:Buffer.alloc(0)});return route.fulfill({status:200,headers:{'access-control-allow-origin':'*'},contentType:url.includes('/elevation/')||url.includes('/mapzen/')?'image/png':'image/webp',body:url.includes('/elevation/')||url.includes('/mapzen/')?dem:imagery});});
 page=await context.newPage();page.setDefaultTimeout(60000);page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error')consoleErrors.push(m.text());});
 await page.goto(`http://127.0.0.1:${server.address().port}/`,{waitUntil:'load'});
 console.log('Website loaded');
 await page.waitForFunction(()=>globalThis.RidgeMobile?.status()?.ready,null,{timeout:60000});
 console.log('Renderer ready',JSON.stringify(await page.evaluate(()=>RidgeMobile.status())));await page.waitForTimeout(1000);
 await page.waitForFunction(()=>globalThis.RidgeMobile?.status()?.direct?.directPatches>0,null,{timeout:60000});
 console.log('Website Direct imagery ready');
 const cdp=await context.newCDPSession(page);await cdp.send('Profiler.enable');await cdp.send('Profiler.setSamplingInterval',{interval:250});await cdp.send('Profiler.start');
 const result=await page.evaluate(async()=>{
  const map=__testMap,control=RidgeBottleneckDiagnostics.attach(map);control.setEnabled(true);
  map.jumpTo({center:[170.141,-43.595],zoom:13,pitch:58,bearing:-24});
  await new Promise(r=>setTimeout(r,1000));
  const started=performance.now();
  const tr=map._camera.transform,terrain=map.terrain;
  for(let i=0;i<300;i++){
   tr.setCenter(new maplibregl.LngLat(170.141+Math.sin(i/15)*.003,-43.595+Math.cos(i/17)*.001));
   for(const manager of Object.values(map.style.tileManagers))manager.update(tr,terrain);
   terrain.tileManager.update(tr,terrain);
   map.painter.renderToTexture.prepareForRender(map.style,tr.zoom,true);
  }
  const result={elapsed:performance.now()-started,profile:control.snapshot().mainThreadBreakdown,stats:RidgeMobile.status()};control.destroy();return result;
 });
 const {profile}=await cdp.send('Profiler.stop');await writeFile(path.join(diagnostics,'cpu-profile.json'),JSON.stringify(profile));await writeFile(path.join(diagnostics,'profile-result.json'),JSON.stringify({engineSha256,baselineApk:process.env.RIDGELINE_PROFILE_BASELINE_APK||null,...result},null,2));
 const nodes=new Map(profile.nodes.map(n=>[n.id,n]));const self=new Map();for(let i=0;i<profile.samples.length;i++){const id=profile.samples[i];self.set(id,(self.get(id)||0)+profile.timeDeltas[i]);}
 console.log(JSON.stringify({result,top:[...self].sort((a,b)=>b[1]-a[1]).slice(0,35).map(([id,us])=>({ms:us/1000,...nodes.get(id).callFrame}))},null,2));
 assert.deepEqual(errors,[]);assert.deepEqual(consoleErrors,[]);
}finally{await browser.close();await new Promise(r=>server.close(r));}
