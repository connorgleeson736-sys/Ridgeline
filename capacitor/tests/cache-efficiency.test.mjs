import {test} from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
async function load(name,extra={}){const c={module:{exports:{}},URL,Response,Headers,AbortController,setTimeout,clearTimeout,...extra};c.globalThis=c;vm.runInNewContext(await readFile(new URL('../www/'+name,import.meta.url),'utf8'),c);return c.module.exports;}
test('expired compressed tiles release reserved admission and speculative cache space',async()=>{
 let now=1000,fetches=0;const {createCache}=await load('tile-cache.js');
 const cache=createCache({maxBytes:2,maxItems:2,clock:()=>now,fetcher:async()=>{fetches++;return new Response(new Uint8Array([1]),{headers:{'Cache-Control':'max-age=1'}});}});
 await cache.load('a');await cache.load('b');assert.equal(cache.canPrefetch(),false);assert.equal(cache.hasEntries(),true);
 now=2100;assert.equal(cache.hasEntries(),false);assert.equal(cache.canPrefetch(),true);assert.equal(cache.stats().bytes,0);
 await cache.load('a');assert.equal(fetches,3);cache.clear();
});
test('eviction keeps the same bounded coarse coverage without quadratic recounts',async()=>{
 let reads=0;const {createPool}=await load('raster-residency.js');
 const cache={data:{},setMaxSize(){},add(id,tile){this.data[id.key]=[{value:tile}];},getAndRemove(){},onRemove(){},reset(){},remove(id){const t=this.data[id.key][0].value;delete this.data[id.key];this.onRemove(t);}};
 const manager={getSource:()=>({id:'linz',type:'raster',tileSize:256}),_outOfViewCache:cache,_inViewTiles:{deleteTileById(){}},updateCacheSize(){},_removeTile(){},on(){},off(){}};
 const pool=createPool({bytes:100*1048576});pool.attach(manager);
 for(let i=0;i<100;i++){const canonical={get z(){reads++;return i<20?10:14;}},id={key:String(i),canonical,wrapped(){return this;}};cache.add(id,{tileID:id,state:'loaded',texture:{size:[256,256]}});}
 reads=0;pool.setBudget(2*1048576);assert.ok(reads<400,'Coarse coverage accounting must be linear');assert.ok(pool.stats().sourceResidentBytes<=2*1048576);assert.ok(pool.stats().sourceEvictions>90);pool.destroy();
});
test('production imagery worker decodes directly without per-image SHA256',async()=>{
 const source=await readFile(new URL('../www/native-regional-worker.js',import.meta.url),'utf8');assert.doesNotMatch(source,/RidgeBitmapCache|native-bitmap-cache/);assert.match(source,/regional-imagery-worker/);
});
test('response bodies snapshot cached bytes without an extra explicit ArrayBuffer copy',async()=>{
 const {create}=await load('response-cache.js');const api=create({fetcher:async()=>new Response(new Uint8Array([1,2,3]),{headers:{'cache-control':'max-age=60'}})});
 const a=await api.fetch('image'),b=await api.fetch('image');const bytes=await a.arrayBuffer();new Uint8Array(bytes)[0]=99;
 assert.deepEqual([...new Uint8Array(await b.arrayBuffer())],[1,2,3]);assert.deepEqual([...new Uint8Array(await(await api.fetch('image')).arrayBuffer())],[1,2,3]);api.destroy();
});
