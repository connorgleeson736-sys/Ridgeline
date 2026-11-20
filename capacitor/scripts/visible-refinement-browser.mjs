import {chromium} from 'playwright-core';
import {createServer} from 'node:http';
import {readFile,stat,open} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {mkdir} from 'node:fs/promises';
import {deflateSync} from 'node:zlib';
import assert from 'node:assert/strict';
const diagnostics=process.env.RIDGELINE_DIAGNOSTICS||'/tmp/ridgeline-capacitor-check';await mkdir(diagnostics,{recursive:true});
function png(){const chunk=(type,data)=>{const head=Buffer.from(type),b=Buffer.concat([head,data]);let crc=0xffffffff;for(const n of b){crc^=n;for(let i=0;i<8;i++)crc=(crc>>>1)^((crc&1)?0xedb88320:0);}const len=Buffer.alloc(4),tail=Buffer.alloc(4);len.writeUInt32BE(data.length);tail.writeUInt32BE((crc^0xffffffff)>>>0);return Buffer.concat([len,b,tail]);};const header=Buffer.alloc(13);header.writeUInt32BE(256,0);header.writeUInt32BE(256,4);header[8]=8;header[9]=2;const data=Buffer.alloc(256*(256*3+1));for(let y=0;y<256;y++)for(let x=0;x<256;x++){let i=y*769+1+x*3;data[i]=1;data[i+1]=173;data[i+2]=176;}return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',header),chunk('IDAT',deflateSync(data)),chunk('IEND',Buffer.alloc(0))]);}
const base=fileURLToPath(new URL('../www/',import.meta.url));
const bundled=JSON.parse(await readFile(path.join(base,'overview-manifest.json'),'utf8')).bundled;
const overviewData=process.env.RIDGELINE_OVERVIEW_DATA||(bundled?path.join(base,'overview-packs'):null);
const server=createServer(async(req,res)=>{try{const p=path.resolve(base,'.'+decodeURIComponent(new URL(req.url,'http://localhost').pathname));if(p!==path.resolve(base)&&!p.startsWith(base))throw Error('Forbidden');const file=(await stat(p)).isDirectory()?path.join(p,'index.html'):p;const type={'.js':'text/javascript','.mjs':'text/javascript','.css':'text/css','.html':'text/html','.png':'image/png','.webp':'image/webp','.json':'application/json'}[path.extname(file)]||'application/octet-stream';res.writeHead(200,{'content-type':type});const data=await readFile(file);res.end(path.basename(file)==='app.js'?Buffer.concat([data,Buffer.from(";globalThis.__testMap=map;globalThis.__testQuality=()=>renderQuality?.stats();globalThis.__testQueue=()=>maplibregl.getImageRequestQueueStats();map.on('error',e=>{if(e.error?.name!=='AbortError')console.error(e.error?.message||'Map error');});")]):data);}catch{res.writeHead(404);res.end();}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
const errors=[],consoleErrors=[],requests=[];let page,archiveReads=0;
try{
 const context=await browser.newContext({viewport:{width:412,height:915},deviceScaleFactor:2,isMobile:true,hasTouch:true,reducedMotion:'reduce'});

 // Mock only Android's data bridge; UI, map rendering and gestures run in Chromium.
 await context.addInitScript(({bundled})=>{
  window.CapacitorCustomPlatform={name:'android'};
  localStorage.setItem('ridgeline-phone-mode-v1','1');localStorage.setItem('ridgeline-filtering-v1',JSON.stringify({bilinear:false,mipmaps:false,trilinear:false,anisotropic:false,fade:false,colour:false}));
  localStorage.removeItem('ridgeline-routes-v1');
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
 await context.route('https://localhost/_ridge/**',async route=>{const url=route.request().url();requests.push(url);const m=url.match(/WebMercatorQuad\/(\d+)\//)||url.match(/MapServer\/tile\/(\d+)\//);if(m&&+m[1]>=14)await new Promise(r=>setTimeout(r,2500));if(overviewData&&url.includes('/_ridge/overviews/')){const match=new URL(url).pathname.match(/^\/_ridge\/overviews\/([a-f0-9]{64})\/(\d+)\/(\d+)$/);assert.ok(match);const file=await open(path.join(overviewData,match[1]+'.bin'));let data;try{data=Buffer.alloc(Number(match[3]));const result=await file.read(data,0,data.length,Number(match[2]));assert.equal(result.bytesRead,data.length);}finally{await file.close();}archiveReads++;return route.fulfill({status:200,headers:{'access-control-allow-origin':'*'},contentType:'image/webp',body:data});}if(url.includes('.pbf'))return route.fulfill({status:200,headers:{'access-control-allow-origin':'*'},contentType:'application/x-protobuf',body:Buffer.alloc(0)});return route.fulfill({status:200,headers:{'access-control-allow-origin':'*'},contentType:url.includes('/elevation/')||url.includes('/mapzen/')?'image/png':'image/webp',body:url.includes('/elevation/')||url.includes('/mapzen/')?dem:imagery});});
 page=await context.newPage();page.setDefaultTimeout(60000);page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error')consoleErrors.push(m.text());});
 await page.goto(`http://127.0.0.1:${server.address().port}/`,{waitUntil:'load'});
 console.log('Website loaded');
 await page.waitForFunction(()=>globalThis.RidgeMobile?.status()?.ready,null,{timeout:60000});
 console.log('Renderer ready',JSON.stringify(await page.evaluate(()=>RidgeMobile.status())));await page.waitForTimeout(7000);console.log('COARSE',JSON.stringify(await page.evaluate(()=>({direct:RidgeMobile.status().direct,queue:__testQueue()}))));

 await page.evaluate(()=>__testMap.jumpTo({center:[170.141,-43.595],zoom:14,pitch:60}));
 await page.waitForTimeout(15000);
 const stages=await page.evaluate(()=>({quality:__testQuality(),queue:__testQueue(),desired:__testMap.terrain.tileManager._ridgeIdeal.ids.map(q=>q.key).sort(),patches:__testMap.terrain.tileManager.getRenderableTiles().map(t=>({id:t.tileID.canonical,direct:t._ridgeDirectCoverage?.rows?.map(r=>r.id.canonical),rt:Object.fromEntries(Object.entries(t.rttFingerprint).map(([k,v])=>[k,v?._tileKeys]))}))}));
 assert.equal(stages.queue.active,0);assert.equal(stages.queue.queued,0);assert.equal(stages.quality.waitingCovers,0);assert.equal(stages.quality.cacheReturns.qualityHolds,0);
 assert.deepEqual(stages.patches.map(t=>t.id.key).sort(),stages.desired,'All ready native terrain patches replace their coarse continuity covers');
 console.log('STAGES',JSON.stringify(stages));
 console.log('DETAIL',JSON.stringify(await page.evaluate(()=>({status:RidgeMobile.status(),zoom:__testMap.getZoom(),layers:__testMap.style._order,managers:Object.fromEntries(Object.entries(__testMap.style.tileManagers).map(([id,m])=>[id,{source:m.getSource().type,visible:m.getVisibleCoordinates().map(q=>[q.canonical.z,q.canonical.x,q.canonical.y,m.getTile(q)?.state,!!m.getTile(q)?.texture]),active:m._inViewTiles?.getAllTiles?.().map(t=>[t.tileID.canonical.z,t.state,!!t.texture]),stats:m.getState()}]))}))));
 assert.deepEqual(errors,[]);assert.deepEqual(consoleErrors,[]);
 console.log('ERRORS',JSON.stringify({errors,consoleErrors,requests:requests.length}));
}finally{await browser.close();await new Promise(r=>server.close(r));}
