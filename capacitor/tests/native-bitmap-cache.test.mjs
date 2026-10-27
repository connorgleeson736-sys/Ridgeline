import {test} from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
import {webcrypto} from 'node:crypto';
const s=await readFile(new URL('../mobile/native-bitmap-cache.js',import.meta.url),'utf8');
const c={module:{exports:{}},Blob,crypto:webcrypto};c.globalThis=c;vm.runInNewContext(s,c);const {create}=c.module.exports;
function setup(maxBytes=32){let decoded=0,closed=0;const cache=create({maxBytes,decode:async b=>({width:2,height:2,value:new Uint8Array(await b.arrayBuffer())[0],close(){closed++;this.width=0;}})});return{cache,closed:()=>closed};}
test('identical content reuses decoding without closing a bitmap still in use',async()=>{const x=setup();const a=await x.cache.bitmap(new Blob(['a'])),b=await x.cache.bitmap(new Blob(['a']));assert.equal(a,b);a.close();assert.equal(b.width,2);b.close();assert.equal(x.closed(),0);assert.equal(x.cache.stats().decodes,1);assert.equal(x.cache.stats().hits,1);x.cache.clear();assert.equal(x.closed(),1);});
test('different source bytes never share a decoded image',async()=>{const x=setup();const a=await x.cache.bitmap(new Blob(['a'])),b=await x.cache.bitmap(new Blob(['b']));assert.notEqual(a,b);assert.equal(a.value,97);assert.equal(b.value,98);a.close();b.close();x.cache.destroy();});
test('LRU eviction respects active consumers and the retained memory budget',async()=>{const x=setup(16);const a=await x.cache.bitmap(new Blob(['a'])),b=await x.cache.bitmap(new Blob(['b']));assert.equal(x.cache.stats().decodedBytes,16);assert.equal(a.width,2);a.close();assert.equal(x.closed(),1);b.close();x.cache.clear();assert.equal(x.closed(),2);});
test('clearing during a pending decode cannot reinsert an untracked allocation',async()=>{let release,entered;const ready=new Promise(r=>entered=r);let closed=0;const cache=create({decode:async()=>{entered();await new Promise(r=>release=r);return{width:2,height:2,close(){closed++;}};}});const job=cache.bitmap(new Blob(['a']));await ready;cache.clear();release();const image=await job;image.close();assert.equal(cache.stats().entries,0);assert.equal(cache.stats().decodedBytes,0);assert.equal(closed,1);});
test('oversized images are not retained and each consumer stays valid',async()=>{const x=setup(1);const [a,b]=await Promise.all([x.cache.bitmap(new Blob(['a'])),x.cache.bitmap(new Blob(['a']))]);a.close();assert.equal(b.width,2);b.close();assert.equal(x.closed(),a===b?1:2);assert.equal(x.cache.stats().decodedBytes,0);});
