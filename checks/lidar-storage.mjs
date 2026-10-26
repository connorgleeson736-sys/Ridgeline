import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { lidarStorage } from '../lib/lidar-storage.mjs';
const sha=b=>createHash('sha256').update(b).digest('hex');
class Bucket {
 objects=new Map();gets=new Map();
 async head(key){const d=this.objects.get(key);return d?{key,size:d.bytes.length,customMetadata:d.meta}:null;}
 async put(key,value,options={}){if(this.objects.has(key)&&options.onlyIf)return null;this.objects.set(key,{bytes:new Uint8Array(value),meta:options.customMetadata||{},type:options.httpMetadata?.contentType});return this.head(key);}
 async get(key,options={}){this.gets.set(key,(this.gets.get(key)||0)+1);const d=this.objects.get(key);if(!d)return null;const range=options.range,bytes=range?d.bytes.slice(range.offset,range.offset+range.length):d.bytes;return {key,size:d.bytes.length,customMetadata:d.meta,body:new Response(bytes).body,json:async()=>JSON.parse(new TextDecoder().decode(bytes))};}
 async list({prefix}){return {objects:[...this.objects].filter(([k])=>k.startsWith(prefix)).map(([key,d])=>({key,size:d.bytes.length,uploaded:new Date(0)})),truncated:false};}
}
const bucket=new Bucket(),env={LIDAR:bucket};
const call=(path,options={})=>lidarStorage(new Request('https://ridgeline.test/api/lidar/'+path,options),env);
const a=new TextEncoder().encode('measured '),b=new TextEncoder().encode('geometry'),all=new Uint8Array([...a,...b]);
for(const bytes of [a,b]){let r=await call('blobs/'+sha(bytes),{method:'PUT',body:bytes});assert.equal(r.status,200);assert.equal((await r.json()).sha256,sha(bytes));}
assert.equal((await call('blobs/'+sha(a),{method:'PUT',body:a})).status,200,'idempotent blob upload');
assert.equal((await call('blobs/'+'0'.repeat(64),{method:'PUT',body:a})).status,422,'reject corrupt upload');
assert.equal((await call('blobs/'+sha(a),{method:'PUT',body:a,headers:{Origin:'https://other.test'}})).status,403);
let descriptor={sha256:sha(all),bytes:all.length,mime:'application/octet-stream',chunks:[a,b].map(x=>({sha256:sha(x),bytes:x.length}))};
let r=await call('manifests/'+descriptor.sha256,{method:'PUT',body:JSON.stringify(descriptor)});assert.equal(r.status,200);assert.equal((await r.json()).verifiedPersistentSha256,sha(all));
const descriptorReads=bucket.gets.get('files/'+sha(all)+'.json')||0;await Promise.all(Array.from({length:12},()=>call('files/'+sha(all),{headers:{Range:'bytes=1-4'}})));assert.equal((bucket.gets.get('files/'+sha(all)+'.json')||0)-descriptorReads,1,'Concurrent ranges share one bounded immutable descriptor read');
r=await call('files/'+sha(all));assert.deepEqual(new Uint8Array(await r.arrayBuffer()),all);
r=await call('files/'+sha(all),{headers:{Range:'bytes=5-12'}});assert.equal(r.status,206);assert.deepEqual(new Uint8Array(await r.arrayBuffer()),all.slice(5,13));
r=await call('files/'+sha(all),{headers:{Range:'bytes=-4'}});assert.deepEqual(new Uint8Array(await r.arrayBuffer()),all.slice(-4));
assert.equal((await call('files/'+sha(all),{headers:{Range:'bytes=999-1000'}})).status,416);
const wrong={...descriptor,sha256:'1'.repeat(64)};assert.equal((await call('manifests/'+wrong.sha256,{method:'PUT',body:JSON.stringify(wrong)})).status,422,'verify assembled readback, not just manifest claim');
const record=new TextEncoder().encode(JSON.stringify({state:'durable',file:sha(all)})),run='a'.repeat(64),path='runs/'+run+'/tasks/tile-0381/'+sha(record);
assert.equal((await call(path,{method:'PUT',body:record})).status,200);
assert.equal((await call(path)).headers.get('X-Content-SHA256'),sha(record));
assert.equal((await (await call('runs/'+run)).json()).objects.length,1);
assert.equal((await call('runs/'+run+'/tasks/../'+sha(record))).status,404);
console.log('R2 checkpoint API: hashes, persistent assembled readback, idempotence, ranges, records and origin checks pass');
