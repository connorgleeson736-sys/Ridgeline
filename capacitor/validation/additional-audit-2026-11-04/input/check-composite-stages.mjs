import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const root=path.resolve(process.argv[2]||'audit');
const blend=fs.readFileSync(path.join(root,'public/regional-blend.js'),'utf8');
const queueText=fs.readFileSync(path.join(root,'scripts/maplibre-image-queue.js'),'utf8');
const engine=fs.readFileSync(path.join(root,'public/assets/maplibre-gl.mjs'),'utf8');assert.ok(engine.includes(queueText.trim()));
async function run(candidate){
 const stages={inputDecodes:0,pngEncodes:0,arrayBufferReads:0,outputDecodes:0,bitmapTransfers:0,closedInputs:0};
 class Bitmap{width=256;height=256;close(){stages.closedInputs++;}}
 class Canvas{constructor(w,h){this.width=w;this.height=h;}getContext(){return {drawImage(){},createImageData:()=>({data:new Uint8ClampedArray(256*256*4)}),putImageData(){}};}async convertToBlob(){stages.pngEncodes++;return {arrayBuffer:async()=>{stages.arrayBufferReads++;return new ArrayBuffer(8);}};}transferToImageBitmap(){stages.bitmapTransfers++;return new Bitmap();}}
 const env={Blob,Uint8ClampedArray,OffscreenCanvas:Canvas,createImageBitmap:async()=>{stages.inputDecodes++;return new Bitmap();}};
 let source=blend;
 if(candidate)source=source.replace("const blob=await result.convertToBlob({type:'image/png'});checkCancelled();const data=await blob.arrayBuffer();checkCancelled();return data;",'return result.transferToImageBitmap();');
 vm.runInNewContext(source,env);
 const data=await env.RidgeRegionalBlend.composite({linz:new ArrayBuffer(8),esri:new ArrayBuffer(8),z:13,x:1,y:1,meta:{z:13,x:1,y:1,rings:[[[0,0],[256,0],[256,256],[0,256],[0,0]]]}});
 const queueEnv={On:{MAX_PARALLEL_IMAGE_REQUESTS:8,MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME:8},I:Object.assign,fn:signal=>signal.throwIfAborted(),Wt:value=>value instanceof Bitmap,HTMLImageElement:class{},createImageBitmap(){},nt:e=>e,
  Rn:async()=>({data}),Gt:async()=>{stages.outputDecodes++;return new Bitmap();}};
 const queue=vm.runInNewContext(queueText+'\nUn;',queueEnv);
 await queue.getImage({url:'fixture'},new AbortController());
 return stages;
}
const baseline=await run(false),bitmapCandidate=await run(true);
assert.equal(baseline.pngEncodes,1);assert.equal(baseline.outputDecodes,1);
assert.equal(bitmapCandidate.pngEncodes,0);assert.equal(bitmapCandidate.outputDecodes,0);
assert.equal(bitmapCandidate.closedInputs,2);
console.log(JSON.stringify({baseline,bitmapCandidate,note:'Mock Canvas and image decoding, actual compositor control flow and pinned image queue. No pixel or time benchmark; protocol/cache integration remains to implement.'},null,2));
