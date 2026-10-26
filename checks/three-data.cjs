const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),crypto=require('node:crypto');
const c=vm.createContext({AbortController,Float32Array,Map,Math,Error,Number,Promise,Date,Response,setTimeout,clearTimeout,fetch});vm.runInContext(fs.readFileSync('public/terrain-data.js','utf8'),c);const T=c.RidgeTerrainData;
const grid=undefined;
const rgba=new Uint8Array(256*256*4);for(let i=0;i<rgba.length;i+=4)rgba.set([1,182,217,255],i);
assert.ok(Math.abs(T.decodeRGBA(rgba)[0]-1234.5)<.001);rgba[3]=0;assert.ok(Number.isNaN(T.decodeRGBA(rgba)[0]));
// A global linear ramp catches half-pixel shifts and tile-edge clamping mistakes.
const z=12,x=3983,y=2600;
const ramp=(tx,ty)=>{const h=new Float32Array(256*256);for(let j=0;j<256;j++)for(let i=0;i<256;i++)h[j*256+i]=(tx*256+i)*.001+(ty*256+j)*.002;return{z,x:tx,y:ty,heights:h};};
const records=new Map();for(let dy=-1;dy<=2;dy++)for(let dx=-1;dx<=2;dx++)records.set((x+dx)+'/'+(y+dy),ramp(x+dx,y+dy));
const a=T.assemble(records,x,y,z,grid),east=T.assemble(records,x+1,y,z,grid),south=T.assemble(records,x,y+1,z,grid);
for(let i=0;i<=256;i++){assert.equal(a[i*257+256],east[i*257]);assert.equal(a[256*257+i],south[i]);}
for(const [i,j]of[[0,0],[128,128],[256,256]]){const expected=(x*256+i-.5)*.001+(y*256+j-.5)*.002;assert.ok(Math.abs(a[j*257+i]-expected)<.001);}
async function engineCheck(){
 let calls=0,active=0,maxActive=0;const decoder=async()=>new Float32Array(256*256).fill(1234.5);
 const engine=T.createEngine({key:'test',grid,cacheStorage:null,concurrency:3,maxBytes:3*256*256*4,decoder,fetcher:async(url,{signal})=>{calls++;maxActive=Math.max(maxActive,++active);await new Promise(resolve=>setImmediate(resolve));active--;if(signal.aborted)throw Object.assign(Error('Cancelled'),{name:'AbortError'});return{ok:true,blob:async()=>({})};}});
 const controller=new AbortController();const results=await Promise.all([engine.tile(x,y,z,controller.signal),engine.tile(x+1,y,z,controller.signal)]);
 assert.equal(calls,12,'Overlapping tile neighbours must share in-flight fetches');assert.ok(maxActive<=3);assert.ok(engine.stats().cacheBytes<=3*256*256*4);
 for(let j=0;j<=256;j++)assert.equal(results[0][j*257+256],results[1][j*257]);
 engine.destroy();await assert.rejects(engine.tile(x,y,z,controller.signal),{name:'AbortError'});
 const stalled=T.createEngine({key:'test',cacheStorage:null,decoder,fetcher:async(url,{signal})=>new Promise((resolve,reject)=>signal.addEventListener('abort',()=>reject(Object.assign(Error('Cancelled'),{name:'AbortError'})),{once:true}))});
 const aborter=new AbortController(),pending=stalled.tile(x,y,z,aborter.signal);aborter.abort();await assert.rejects(pending,{name:'AbortError'});stalled.destroy();
}
(async()=>{await engineCheck();console.log('PASS: Terrain-RGB decoding, exact E/S seams, pixel-centre alignment, bounded shared fetches, cancellation.');})().catch(e=>{console.error(e);process.exitCode=1});
