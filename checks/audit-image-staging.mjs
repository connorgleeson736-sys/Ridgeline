import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import assert from 'node:assert/strict';
const template=readFileSync('scripts/maplibre-image-queue.js','utf8');
const engine=readFileSync('public/assets/maplibre-gl.mjs','utf8');
assert.ok(engine.includes(template.trim()));
const deliveries=[],decodes=[],gates=[];
const env={On:{MAX_PARALLEL_IMAGE_REQUESTS:8,MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME:8}, I:Object.assign,fn:signal=>signal.throwIfAborted(),Wt:()=>false,HTMLImageElement:class{},createImageBitmap(){},nt:e=>e,
 Rn:async({url})=>{deliveries.push(url);return {data:new Uint8Array(8).buffer}},
 Gt:async data=>{decodes.push(data);return new Promise(resolve=>gates.push(resolve))}};
const queue=runInNewContext(template+'\nUn;',env);
queue.addThrottleControl(()=>true);
const jobs=Array.from({length:24},(_,i)=>queue.getImage({url:String(i)},new AbortController()));
const tick=()=>new Promise(r=>setImmediate(r));
await tick();
console.log('bodies ready, all first decodes held:',{delivered:deliveries.length,decodeStarted:decodes.length,stats:queue.stats()});
assert.equal(deliveries.length,16);assert.equal(queue.stats().queued,8);assert.equal(queue.stats().stagedItems,16);
gates.shift()({});await tick();assert.equal(deliveries.length,17);
console.log('after releasing one decode:',{delivered:deliveries.length,decodeStarted:decodes.length,queued:queue.stats().queued});
while(gates.length){for(const release of gates.splice(0))release({});await tick()}await Promise.all(jobs);
