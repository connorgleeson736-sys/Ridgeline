import {chromium} from 'playwright-core';
import {createServer} from 'node:http';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
const root=new URL('../www/',import.meta.url);
const server=createServer(async(req,res)=>{try{
 const p=new URL(req.url,'http://localhost').pathname;
 if(p==='/'){res.setHeader('content-type','text/html');return res.end('<!doctype html><title>Composite check</title>');}
 if(p==='/image-result.js'){res.setHeader('content-type','text/javascript');return res.end(await readFile(new URL('../mobile/image-result.js',import.meta.url)));}
 if(p==='/crash-worker.js'){res.setHeader('content-type','text/javascript');return res.end("self.onmessage=()=>{throw Error('controlled worker crash');};");}
 const file=new URL('.'+p,root);if(!file.href.startsWith(root.href))throw Error('Invalid path');
 res.setHeader('content-type',p.endsWith('.mjs')||p.endsWith('.js')?'text/javascript':'application/octet-stream');
 let bytes=await readFile(file);
 if(p==='/regional-imagery-worker.js')bytes=Buffer.concat([Buffer.from(`const audit={inputs:0,encodes:0,transfers:0,copies:0};const cib=createImageBitmap;self.createImageBitmap=(...a)=>{if(a[0] instanceof Blob)audit.inputs++;else audit.copies++;return cib(...a);};const encode=OffscreenCanvas.prototype.convertToBlob,transfer=OffscreenCanvas.prototype.transferToImageBitmap;OffscreenCanvas.prototype.convertToBlob=function(...a){audit.encodes++;return encode.apply(this,a);};OffscreenCanvas.prototype.transferToImageBitmap=function(...a){audit.transfers++;return transfer.apply(this,a);};const post=self.postMessage.bind(self);self.postMessage=(data,...a)=>post({...data,audit:{...audit}},...a);\n`),bytes]);
 res.end(bytes);
}catch(e){res.statusCode=404;res.end(e.message);}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
try{
 const page=await browser.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
 const base=`http://127.0.0.1:${server.address().port}`;await page.goto(base);
 for(const name of ['image-result.js','regional-scheduler.js','regional-blend.js','gap-imagery.js','response-cache.js','regional-imagery.js','tile-cache.js'])await page.addScriptTag({url:base+'/'+name});
 const result=await page.evaluate(async()=>{
  const E=await import('/assets/maplibre-gl.mjs'),audits=[];let outputDecodes=0,lastOwned;
  const nativeBitmap=createImageBitmap;globalThis.createImageBitmap=(...a)=>{if(a[0] instanceof Blob)outputDecodes++;return nativeBitmap(...a);};
  async function input(kind){const c=new OffscreenCanvas(256,256),ctx=c.getContext('2d'),im=ctx.createImageData(256,256);for(let y=0;y<256;y++)for(let x=0;x<256;x++){const at=(y*256+x)*4;im.data[at]=kind?x:(x*7+y*3)%256;im.data[at+1]=kind?y:(x*11+y*13)%256;im.data[at+2]=kind?83:237;im.data[at+3]=kind?255:(x+y)%255;}ctx.putImageData(im,0,0);return (await c.convertToBlob({type:'image/png'})).arrayBuffer();}
  const linz=await input(0),esri=await input(1);let rows;
  function gpu(image,premultiply){const canvas=new OffscreenCanvas(256,256),gl=canvas.getContext('webgl2'),t=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,t);gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL,premultiply);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,image);const f=gl.createFramebuffer();gl.bindFramebuffer(gl.FRAMEBUFFER,f);gl.framebufferTexture2D(gl.FRAMEBUFFER,gl.COLOR_ATTACHMENT0,gl.TEXTURE_2D,t,0);const pixels=new Uint8Array(256*256*4);gl.readPixels(0,0,256,256,gl.RGBA,gl.UNSIGNED_BYTE,pixels);gl.deleteFramebuffer(f);gl.deleteTexture(t);gl.getExtension('WEBGL_lose_context')?.loseContext();return pixels;}
  const bitmapContext=typeof ImageBitmap==='function';
  async function run(bitmap,premultiply,crash=false,uploadFailure=false){
   const start=outputDecodes,workerAudit=[];
   RidgeRegionalImagery.mode=()=> 'boundary';RidgeRegionalImagery.relation=()=>-1;
   const meta={z:13,x:7966,y:5200,width:256,height:256,vector:true,outer:[[[0,0],[160,0],[160,256],[0,256],[0,0]]],seamRings:[],fineSources:[]};
   globalThis.RidgeGapAtlas={z:13,entries:{'7966/5200':meta},coarse:{}};
   const regional=RidgeRegionalImagery.create({meta,south:{tiles:{}},overviews:{tiles:{}},fetcher:async url=>new Response(url.includes('World_Imagery')?esri.slice(0):linz.slice(0),{headers:{'Cache-Control':'max-age=60'}}),workerFactory:()=>{const w=new Worker(crash?'/crash-worker.js':'/regional-imagery-worker.js');w.addEventListener('message',e=>{if(e.data.audit)workerAudit.push(e.data.audit);});return w;}});
   const cache=RidgeTiles.createCache({fetcher:regional.fetch});
   const protocol='auditbitmap'+Number(bitmap)+Number(premultiply);
   E.addProtocol(protocol,async(r,c)=>{const row=await cache.load('https://localhost/_ridge/linz/v1/tiles/aerial/WebMercatorQuad/13/7966/5200.webp',c.signal,undefined,true,{original:bitmap,imageOptions:r.ridgeImageBitmapOptions});lastOwned=row.ridgeOwned?row.data:null;return row;});
   let texture;
   const tile={uid:1,tileID:{key:'fixture',canonical:{url:()=>protocol+'://tile'}},state:'loading'};
   const source={tiles:[],scheme:'xyz',_premultiplyAlpha:premultiply,map:{_refreshExpiredTiles:false,getPixelRatio:()=>1,_requestManager:{transformRequest:async url=>({url})},painter:{context:{gl:{RGBA:6408}},getTileTexture:()=>({update(image){if(uploadFailure)throw Error('controlled context loss');texture=gpu(image,premultiply);}})}}};
   let failures=0;
   if(crash)try{await E.RasterTileSource.prototype.loadTile.call(source,tile);}catch{failures++;}
   try{await E.RasterTileSource.prototype.loadTile.call(source,tile);}catch(e){if(uploadFailure)failures++;else throw e;}
   const row={bitmap,premultiply,pixels:texture?[...texture]:[],worker:workerAudit.at(-1),outputDecodes:outputDecodes-start,closedOutput:!lastOwned||lastOwned.width===0,byteCacheItems:cache.stats().tiles,failures,mainThreadJobs:regional.stats().mainThreadJobs};
   E.removeProtocol(protocol);regional.destroy();cache.clear();return row;
  }
  const pairs=[];let reference;
  for(const premultiply of [true,false]){const bytes=await run(false,premultiply),bitmap=await run(true,premultiply);if(premultiply)reference=bytes.pixels.slice();let different=0;for(let i=0;i<bytes.pixels.length;i++)if(bytes.pixels[i]!==bitmap.pixels[i])different++;delete bytes.pixels;delete bitmap.pixels;pairs.push({premultiply,different,bytes,bitmap});}
  const recovered=await run(true,true,true);let recoveredDifferent=0;for(let i=0;i<reference.length;i++)if(reference[i]!==recovered.pixels[i])recoveredDifferent++;delete recovered.pixels;
  const lost=await run(true,true,false,true);delete lost.pixels;
  const c=new OffscreenCanvas(256,256),owned=[];c.getContext('2d');const cache=RidgeTiles.createCache({fetcher:async()=>{const b=c.transferToImageBitmap();owned.push(b);return {ridgeBitmap:b,ok:true,status:200,headers:new Headers({'Cache-Control':'max-age=60'})};}});
  const [a,b]=await Promise.all([cache.load('fanout',new AbortController().signal,undefined,true,{original:true}),cache.load('fanout',new AbortController().signal,undefined,true,{original:true})]);const distinct=a.data!==b.data;a.data.close();const otherAlive=b.data.width===256;b.data.close();
  let release;const late=c.transferToImageBitmap();const canceled=RidgeTiles.createCache({fetcher:()=>new Promise(r=>release=()=>r({ridgeBitmap:late,ok:true,status:200,headers:new Headers()}))});const controller=new AbortController();const pending=canceled.load('cancel',controller.signal,undefined,true,{original:true}).catch(e=>e.name);controller.abort();release();await pending;await new Promise(r=>setTimeout(r,0));const lateClosed=late.width===0;
  const scheduler=RidgeRegionalScheduler.create();const lateWorker=c.transferToImageBitmap();let finish;
  const aborted=new AbortController();const waiting=scheduler.schedule(()=>new Promise(r=>finish=r),aborted.signal).catch(e=>e.name);await Promise.resolve();aborted.abort();finish(lateWorker);await waiting;await new Promise(r=>setTimeout(r,0));const canceledWorkerClosed=lateWorker.width===0;scheduler.destroy();
  cache.clear();canceled.clear();return{pairs,distinct,otherAlive,lateClosed,canceledWorkerClosed,bitmapContext,recovered,recoveredDifferent,lost};
 });
 assert.deepEqual(errors,[]);for(const pair of result.pairs){assert.equal(pair.different,0,'GPU pixels');assert.equal(pair.bytes.worker.encodes,1);assert.equal(pair.bitmap.worker.encodes,pair.premultiply?0:1);assert.equal(pair.bitmap.worker.transfers,pair.premultiply?1:0);assert.equal(pair.bitmap.outputDecodes,pair.premultiply?0:1);assert.equal(pair.bitmap.closedOutput,true);assert.equal(pair.bitmap.byteCacheItems,pair.premultiply?0:1);}
 assert.ok(result.distinct&&result.otherAlive&&result.lateClosed&&result.canceledWorkerClosed);
 assert.equal(result.recovered.failures,1);assert.equal(result.recovered.mainThreadJobs,1);assert.equal(result.recoveredDifferent,0);assert.equal(result.lost.failures,1);assert.equal(result.lost.closedOutput,true);
 const dir=process.env.RIDGELINE_DIAGNOSTICS||'/tmp/ridgeline-additional-audit';await mkdir(dir,{recursive:true});await writeFile(path.join(dir,'bitmap.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result,null,2));
}finally{await browser.close();await new Promise(r=>server.close(r));}
