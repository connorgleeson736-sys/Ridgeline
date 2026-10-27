import {chromium} from 'playwright-core';
import {readFile,readdir} from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import {fileURLToPath} from 'node:url';
const base=fileURLToPath(new URL('../www/',import.meta.url));
const files=await readdir(path.join(base,'assets/south-overviews-v1'));
const fixture=await readFile(path.join(base,'assets/south-overviews-v1',files.find(n=>n.endsWith('.webp'))));
const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
const requests=[],errors=[];
try{
 const context=await browser.newContext();
 await context.addInitScript(()=>{window.__controls=[];window.RidgeTileControl={begin:(id,priority)=>__controls.push({op:'begin',id,priority}),priority:(id,priority)=>__controls.push({op:'priority',id,priority}),cancel:id=>__controls.push({op:'cancel',id})};});
 await context.route('https://localhost/**',async route=>{
  const request=route.request(),url=new URL(request.url());
  if(url.pathname==='/')return route.fulfill({contentType:'text/html',body:'<!doctype html><title>Native transport verification</title>'});
  if(url.pathname==='/fixture.webp')return route.fulfill({contentType:'image/webp',body:fixture});
  if(url.pathname==='/worker-check.js')return route.fulfill({contentType:'text/javascript',body:`importScripts('/native-transport.js');RidgeNativeTransport.installWorker();let active,rank=120;self.onmessage=async({data})=>{if(data==='cancel'){active?.abort();return;}if(data==='promote'){rank=0;return;}active=new AbortController();try{const r=await fetch('/_ridge/hold',{signal:active.signal,ridgeRank:()=>rank});await r.arrayBuffer();postMessage({done:true});}catch(e){postMessage({error:e.name});}};`});
  if(url.pathname.startsWith('/_ridge/')){requests.push({url:request.url(),headers:request.headers()});if(url.pathname.endsWith('/hold'))return;return route.fulfill({body:'full-detail',headers:{'Cache-Control':'max-age=60','X-Ridge-Expires-At':String(Date.now()+5000)}});}
  const name=path.resolve(base,'.'+url.pathname);if(!name.startsWith(base))return route.abort();
  return route.fulfill({contentType:'text/javascript',body:await readFile(name)});
 });
 const page=await context.newPage();page.setDefaultTimeout(10000);page.on('pageerror',e=>errors.push(e.message));await page.goto('https://localhost/');
 await page.addScriptTag({url:'/native-transport.js'});await page.evaluate(()=>RidgeNativeTransport.installPage());
 const direct=await page.evaluate(async()=>{const r=await fetch(new Request('https://localhost/_ridge/direct'));const copy=r.clone();const [a,b]=await Promise.all([r.arrayBuffer(),copy.arrayBuffer()]);if(new TextDecoder().decode(a)!==new TextDecoder().decode(b))throw Error('Response clone changed tile bytes');const text=new TextDecoder().decode(a);return{text,controls:__controls};});assert.equal(direct.text,'full-detail');assert.ok(direct.controls.some(c=>c.op==='begin'));
 await page.evaluate(()=>{window.__messages=[];window.worker=new Worker('/worker-check.js');worker.onmessage=e=>__messages.push(e.data);worker.postMessage('start');});
 await page.waitForFunction(()=>__controls.some(c=>c.op==='begin'&&c.priority===220));
 await page.evaluate(()=>worker.postMessage('promote'));await page.waitForFunction(()=>__controls.some(c=>c.op==='priority'&&c.priority===0));
 await page.evaluate(()=>worker.postMessage('cancel'));await page.waitForFunction(()=>__messages.some(m=>m.error==='AbortError'));assert.equal(await page.evaluate(()=>__messages.some(m=>m.__ridge_transport_v1)),false);
 await page.evaluate(()=>worker.terminate());
 const count=await page.evaluate(()=>__controls.filter(c=>c.op==='begin').length);
 await page.evaluate(()=>{window.second=new Worker('/worker-check.js');second.postMessage('start');});await page.waitForFunction(n=>__controls.filter(c=>c.op==='begin').length>n,count);
 const id=await page.evaluate(()=>__controls.filter(c=>c.op==='begin').at(-1).id);await page.evaluate(()=>second.terminate());await page.waitForFunction(id=>__controls.some(c=>c.op==='cancel'&&c.id===id),id);
 for(const request of requests)assert.match(request.headers['x-ridge-request'],/^[a-f0-9]{32}$/);
 await page.addScriptTag({url:'/native-bitmap-cache.js'});await page.addScriptTag({url:'/imagery.js'});await page.addScriptTag({url:'/regional-blend.js'});
 const pixels=await page.evaluate(async()=>{
  const native=createImageBitmap.bind(globalThis),esri=await(await fetch('/fixture.webp')).arrayBuffer(),image=await native(new Blob([esri]));
  const canvas=new OffscreenCanvas(image.width,image.height),ctx=canvas.getContext('2d');ctx.fillStyle='#497c62';ctx.fillRect(0,0,image.width,image.height);ctx.fillStyle='#f8f3ed';ctx.fillRect(0,0,image.width/2,image.height);image.close();
  const linz=await(await canvas.convertToBlob({type:'image/png'})).arrayBuffer();
  const cache=RidgeBitmapCache.create({decode:native});
  const meta={z:10,x:1,y:1,rings:[[[0,0],[256,0],[256,256],[0,256],[0,0]],[[80,80],[80,160],[160,160],[160,80],[80,80]]]};
  const cases=[{overlay:true},{meta,hardMask:true},{meta,hardMask:true,compensate:true},{meta,hardMask:true,fine:[{data:linz,z:10}]}];
  async function rgba(bytes){const b=await native(new Blob([bytes])),c=new OffscreenCanvas(b.width,b.height),cx=c.getContext('2d');cx.drawImage(b,0,0);b.close();return cx.getImageData(0,0,c.width,c.height).data;}
  let comparisons=0;
  for(const options of cases){const job={linz,esri,z:10,x:1,y:1,...options};const expected=await rgba(await RidgeRegionalBlend.composite(job,{OffscreenCanvas,createImageBitmap:native,RidgeImagery}));for(let i=0;i<2;i++){const actual=await rgba(await RidgeRegionalBlend.composite(job,{OffscreenCanvas,createImageBitmap:cache.bitmap,RidgeImagery}));if(actual.length!==expected.length||actual.some((n,k)=>n!==expected[k]))throw Error('Cached composition changed pixels');comparisons++;}}
  const stats=cache.stats();cache.clear();if(cache.stats().decodedBytes!==0)throw Error('Decode cache did not release unused images');return{comparisons,stats};
 });
 assert.equal(pixels.comparisons,8);assert.ok(pixels.stats.hits>0);assert.ok(pixels.stats.decodedBytes<=2*1048576);assert.deepEqual(errors,[]);
 console.log(JSON.stringify({mainAndWorkerControls:true,priorityPromotion:true,abortAfterStart:true,workerTerminationCancellation:true,privateRequestHeaders:true,pixelExactCompositions:pixels.comparisons,decodeCache:pixels.stats,pageErrors:errors},null,2));
 await context.close();
}finally{await browser.close();}
