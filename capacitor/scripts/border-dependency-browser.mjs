// Focused real-worker/Canvas regression. No route or general UI workflows.
// Default fixtures are deterministic; set RIDGELINE_BORDER_FIXTURES for the
// separately recorded live Rolling Pin photo fixtures. Baseline is the APK.
import {chromium} from 'playwright-core';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {createServer} from 'node:http';
import assert from 'node:assert/strict';
const base=fileURLToPath(new URL('../',import.meta.url)),root=path.join(base,'www'),out=process.env.RIDGELINE_DIAGNOSTICS||'/tmp/ridgeline-border-dependency',work=process.env.RIDGELINE_BORDER_FIXTURES||out;await mkdir(out,{recursive:true});
const baseline=path.join(base,'checkpoints/Ridgeline-Capacitor-LINZ-Border-Fix-0.20.2-Base.apk'),scripts=new Map();
for(const name of ['gap-imagery.js','regional-blend.js'])scripts.set('probe/baseline-'+name,execFileSync('python3',['-c','import sys,zipfile;sys.stdout.buffer.write(zipfile.ZipFile(sys.argv[1]).read("assets/public/"+sys.argv[2]))',baseline,name]));
const server=createServer(async(req,res)=>{try{const file=decodeURIComponent(new URL(req.url,'http://local').pathname).slice(1),p=file.startsWith('probe/')?work+'/'+file.slice(6):root+'/'+file;res.setHeader('content-type',file.endsWith('.js')?'text/javascript':file.endsWith('.png')?'image/png':'text/html');res.end(!file?'<!doctype html><html></html>':scripts.get(file)||await readFile(p));}catch{res.writeHead(404);res.end();}});await new Promise(r=>server.listen(0,'127.0.0.1',r));
const b=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
try{const p=await b.newPage(),errors=[];p.on('pageerror',e=>errors.push(e.message));await p.goto('http://127.0.0.1:'+server.address().port);
await p.addScriptTag({content:await readFile(path.join(base,'mobile/image-result.js'),'utf8')});
for(const n of ['response-cache.js','regional-scheduler.js','regional-blend.js','gap-imagery-atlas.js','gap-imagery.js','regional-imagery.js'])await p.addScriptTag({url:'/'+n});
await p.evaluate(()=>{globalThis.CurrentGap=RidgeGapImagery;globalThis.CurrentBlend=RidgeRegionalBlend;});
await p.addScriptTag({url:'/probe/baseline-gap-imagery.js'});await p.evaluate(()=>{globalThis.BaselineGap=RidgeGapImagery;globalThis.RidgeGapImagery=CurrentGap;});
await p.addScriptTag({url:'/probe/baseline-regional-blend.js'});await p.evaluate(()=>{globalThis.BaselineBlend=RidgeRegionalBlend;globalThis.RidgeRegionalBlend=CurrentBlend;});
const result=await p.evaluate(async({realFixtures})=>{
 const fixtures={};if(realFixtures){for(const [x,y]of[[126961,83596],[126961,83598]])for(const s of['aerial','otago-2017-2019-0.3m'])fixtures[x+'/'+y+'/'+s]=await fetch(`/probe/17-${x}-${y}-${s}.png`).then(r=>r.arrayBuffer());
 }
 async function synthetic(seed,alpha=255){const c=new OffscreenCanvas(256,256),ctx=c.getContext('2d'),pixels=ctx.createImageData(256,256);for(let y=0;y<256;y++)for(let x=0;x<256;x++){const i=(y*256+x)*4;pixels.data[i]=(x*7+y*3+seed)%256;pixels.data[i+1]=(x*3+y*11+seed)%256;pixels.data[i+2]=(x^y^seed)%256;pixels.data[i+3]=alpha;}ctx.putImageData(pixels,0,0);return(await c.convertToBlob({type:'image/png'})).arrayBuffer();}
 if(!realFixtures)for(const y of[83596,83598]){fixtures['126961/'+y+'/aerial']=await synthetic(y);fixtures['126961/'+y+'/otago-2017-2019-0.3m']=await synthetic(12,y===83596?255:0);}
 const satellite=realFixtures?await fetch('/probe/17-126961-83596-esri.png').then(r=>r.arrayBuffer()):await synthetic(74);
 const assert=(v,message)=>{if(!v)throw Error(message);};
 const url=(x,y)=>`https://localhost/_ridge/linz/v1/tiles/aerial/WebMercatorQuad/17/${x}/${y}.webp?api=fixture&ridge-gap=gap-v5`;
 const imagePixels=async(data)=>{const bm=data instanceof ImageBitmap?data:await createImageBitmap(new Blob([data]));const c=new OffscreenCanvas(256,256),ctx=c.getContext('2d');ctx.drawImage(bm,0,0);bm.close();return ctx.getImageData(0,0,256,256).data;};
 const original=await imagePixels(fixtures['126961/83596/aerial']);
 function source(mode='slow'){
  const state={aborted:0,started:0,gates:[],fallback:false};
  const fetcher=async(address,options={})=>{
   if(address.includes('/_ridge/esri/')){
    if(mode==='error')return new Response('unavailable',{status:503});
    state.started++;const headers={'Content-Type':'image/png','Cache-Control':'max-age=60'};
    if(mode==='slow'){headers['X-Ridge-Fallback']='12/3967/2612';headers['X-Ridge-Fallback-Retry']='1';state.fallback=true;}
    if(mode==='slow'||mode==='gate')await new Promise((resolve,reject)=>{let timer;const done=()=>{clearTimeout(timer);options.signal?.removeEventListener('abort',cancel);resolve();},cancel=()=>{clearTimeout(timer);state.aborted++;reject(Object.assign(Error('Cancelled'),{name:'AbortError'}));};options.signal?.addEventListener('abort',cancel,{once:true});if(options.signal?.aborted)cancel();if(mode==='slow')timer=setTimeout(done,5000);else state.gates.push(done);});
    return new Response(satellite.slice(0),{headers});
   }
   const m=address.match(/tiles\/(aerial|otago-2017-2019-0.3m)\/WebMercatorQuad\/17\/(\d+)\/(\d+)\./);assert(m,'Unexpected fixture source');
   if(mode==='fine-error'&&m[1]!=='aerial')return new Response('unavailable',{status:503});
   return new Response(fixtures[m[2]+'/'+m[3]+'/'+m[1]].slice(0),{headers:{'Content-Type':m[1]==='aerial'?'image/webp':'image/png','Cache-Control':'max-age=60'}});
  };return{state,fetcher};
 }
 const timings={};
 for(const baseline of[true,false]){
  RidgeGapImagery=baseline?BaselineGap:CurrentGap;const s=source(),r=RidgeRegionalImagery.create({fetcher:s.fetcher,concurrency:1});let photo;
  try{const start=performance.now();photo=await r.fetch(url(126961,83596),{ridgeOriginal:true,ridgeBitmap:true});const elapsed=performance.now()-start,pixels=await imagePixels(photo.ridgeBitmap||await photo.arrayBuffer());assert(pixels.every((v,i)=>v===original[i]),'LINZ-only pixels changed');
   const fallback=photo.headers.get('X-Ridge-Fallback');if(!baseline){assert(elapsed<1500,'LINZ-only output waited for Esri');assert(!fallback,'Unused fallback contaminated photo quality');await new Promise(r=>setTimeout(r,350));assert(s.state.aborted===1,'Unneeded input was not cancelled');}
   else{assert(elapsed>=4900,'Baseline must reproduce the forced five-second wait');assert(fallback,'Baseline fallback propagation missing');}
   timings[baseline?'baseline':'fixed']={elapsedMs:elapsed,fallback,abortedEsri:s.state.aborted,stats:r.stats()};
  }finally{photo?.ridgeBitmap?.close();r.destroy();}
 }
 RidgeGapImagery=CurrentGap;
 const s=source('gate'),r=RidgeRegionalImagery.create({fetcher:s.fetcher,concurrency:1});
 try{
  let mixedDone=false;const mixed=r.fetch(url(126961,83598),{ridgeOriginal:true,ridgeBitmap:true}).then(v=>{mixedDone=true;return v;});
  const deadline=performance.now()+3000;while(r.stats().waitingEsriCompositions!==1&&performance.now()<deadline)await new Promise(r=>setTimeout(r,10));assert(r.stats().waitingEsriCompositions===1,'Mixed job did not release a prepared wait');
  const start=performance.now(),photo=await r.fetch(url(126961,83596),{ridgeOriginal:true,ridgeBitmap:true});const elapsed=performance.now()-start;photo.ridgeBitmap?.close();assert(elapsed<1500&&!mixedDone,'An Esri wait occupied the only composition slot');
  for(const gate of s.state.gates)gate();const mixedPhoto=await mixed;const actual=await imagePixels(mixedPhoto.ridgeBitmap||await mixedPhoto.arrayBuffer());
  const z=17,x=126961,y=83598,e=RidgeGapAtlas.entries[Math.floor(x/16)+'/'+Math.floor(y/16)];const reference=await BaselineBlend.composite({linz:fixtures[x+'/'+y+'/aerial'],esri:satellite,fine:[{data:fixtures[x+'/'+y+'/otago-2017-2019-0.3m'],z}],z,x,y,esriZ:z,meta:{...e,rings:e.outer},bitmap:false});const expected=await imagePixels(reference);assert(actual.every((v,i)=>v===expected[i]),'Mixed-source pixels changed');
  timings.scheduler={linzElapsedMs:elapsed,oneSlotAvailableDuringEsriWait:true,mixedPixelsByteIdentical:true};
 }finally{r.destroy();}
 const c=source('gate'),q=RidgeRegionalImagery.create({fetcher:c.fetcher,concurrency:1}),controller=new AbortController();
 try{const pending=q.fetch(url(126961,83598),{signal:controller.signal,ridgeOriginal:true,ridgeBitmap:true}).then(()=>false,e=>e.name==='AbortError');const deadline=performance.now()+3000;while(q.stats().waitingEsriCompositions!==1&&performance.now()<deadline)await new Promise(r=>setTimeout(r,10));assert(q.stats().waitingEsriCompositions===1,'Cancellation fixture did not prepare');controller.abort();assert(await pending,'Cancellation did not reject');await new Promise(r=>setTimeout(r,350));assert(q.stats().pendingWorkerCompositions===0&&q.stats().compositesActive===0,'Prepared job leaked after cancellation');timings.cancel={preparedInputsReleased:true,pendingWorkerCompositions:q.stats().pendingWorkerCompositions};}finally{q.destroy();}
 const fallbackSource=source('gate'),fallback=RidgeRegionalImagery.create({fetcher:fallbackSource.fetcher,concurrency:1,workerFactory:()=>{throw Error('Forced unavailable worker');}});
 try{const start=performance.now(),photo=await fallback.fetch(url(126961,83596),{ridgeOriginal:true,ridgeBitmap:false});const pixels=await imagePixels(await photo.arrayBuffer());assert(pixels.every((v,i)=>v===original[i]),'Encoded fallback pixels changed');assert(fallback.stats().mainThreadJobs===1,'Fallback path was not exercised');timings.mainThreadFallback={elapsedMs:performance.now()-start,encodedPixelsByteIdentical:true};}finally{fallback.destroy();}
 const failedSource=source('error'),failed=RidgeRegionalImagery.create({fetcher:failedSource.fetcher,concurrency:1});
 try{const photo=await failed.fetch(url(126961,83596),{ridgeOriginal:true,ridgeBitmap:true});photo.ridgeBitmap?.close();let rejected=false;try{await failed.fetch(url(126961,83598),{ridgeOriginal:true,ridgeBitmap:true});}catch(e){rejected=e.status===503;}assert(rejected,'A needed failed source must not publish incomplete imagery');timings.sourceFailures={unusedEsriFailureDoesNotBlock:true,neededEsriFailureRejects:true};}finally{failed.destroy();}
 const fineSource=source('fine-error'),fineFailure=RidgeRegionalImagery.create({fetcher:fineSource.fetcher,concurrency:1});
 try{let rejected=false;try{await fineFailure.fetch(url(126961,83596),{ridgeOriginal:true,ridgeBitmap:true});}catch(e){rejected=e.status===503;}assert(rejected,'Missing fine alpha must still fail closed');timings.sourceFailures.fineAlphaFailureRejects=true;}finally{fineFailure.destroy();}
 const brokenSource=source('gate');let actualWorker;
 const broken=RidgeRegionalImagery.create({fetcher:brokenSource.fetcher,concurrency:1,workerFactory:()=>{actualWorker=new Worker('/native-regional-worker.js?v=2');return actualWorker;}});
 try{const pending=broken.fetch(url(126961,83598),{ridgeOriginal:true,ridgeBitmap:true}).then(()=>false,e=>/unavailable/i.test(e.message));const deadline=performance.now()+3000;while(broken.stats().waitingEsriCompositions!==1&&performance.now()<deadline)await new Promise(r=>setTimeout(r,10));assert(broken.stats().waitingEsriCompositions===1,'Worker-loss fixture did not prepare');actualWorker.onerror({preventDefault(){}});assert(await Promise.race([pending,new Promise(r=>setTimeout(()=>r(false),1000))]),'Worker loss left a prepared tile waiting on network');assert(broken.stats().pendingWorkerCompositions===0,'Worker loss leaked jobs');timings.workerLoss={preparedNetworkWaitRejectedPromptly:true};}finally{broken.destroy();}
 return timings;
},{realFixtures:!!process.env.RIDGELINE_BORDER_FIXTURES});assert.deepEqual(errors,[]);await writeFile(out+'/deferred-results.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result,null,2));}finally{await b.close();await new Promise(r=>server.close(r));}
