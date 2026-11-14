const sourceRoot=process.env.RIDGE_PERFORMANCE_SOURCE||'public';
const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const streaming=require('../'+sourceRoot+'/terrain-streaming.js');
class ID{constructor(z,x,y){this.canonical={z,x,y};this.key=z+'/'+x+'/'+y;}scaledTo(z){const d=2**(this.canonical.z-z);return new ID(z,Math.floor(this.canonical.x/d),Math.floor(this.canonical.y/d));}wrapped(){return this;}}
function fixture(){let reads=0,parents=0,overviews=0;const active=new Map(),context={module:{exports:{}},RidgeTerrainStreaming:streaming,RidgeNZOverview:{hasTile(){overviews++;return false;}}};context.globalThis=context;vm.runInNewContext(fs.readFileSync(sourceRoot+'/raster-residency.js','utf8'),context);
 const cache={data:{},setMaxSize(){},add(){},getAndRemove(id){const v=this.data[id.key]?.[0]?.value;delete this.data[id.key];return v;},onRemove(){},reset(){}};
 const source={id:'linz',type:'raster',tileSize:256,minzoom:0,tiles:['test']};
 const manager={getSource:()=>source,getTile(id){reads++;if(id.canonical.z<12)parents++;return active.get(id.key);},_inViewTiles:{deleteTileById(){}},_outOfViewCache:cache,updateCacheSize(){},_removeTile(){},on(){},off(){},_updateRetainedTiles(ids){return Object.fromEntries(ids.map(id=>[id.key,id]));},_addTile(id){const tile=cache.getAndRemove(id)||{tileID:id,state:'loading'};active.set(id.key,tile);return tile;}};
 const pool=context.module.exports.createPool({bytes:128*1048576});pool.attach(manager);
 return{manager,cache,active,pool,counts:()=>({reads,parents,overviews})};
}
test('ready views skip the extra retained-tile scan',()=>{
 const f=fixture(),ids=Array.from({length:512},(_,i)=>new ID(12,1000+i,1500));for(const id of ids)f.active.set(id.key,{tileID:id,state:'loaded',texture:{}});
 const result=f.manager._updateRetainedTiles(ids,12);assert.equal(Object.keys(result).length,512);assert.equal(f.counts().reads,512);assert.equal(f.counts().overviews,0);f.pool.destroy();
});
test('siblings share failed parent and packaged-overview probes within one update only',()=>{
 const f=fixture(),ids=Array.from({length:512},(_,i)=>new ID(12,1000+i,1500));
 const result=f.manager._updateRetainedTiles(ids,12);assert.deepEqual(Object.keys(result),ids.map(id=>id.key));
 const counts=f.counts();assert.ok(counts.parents<600);assert.equal(counts.parents,counts.overviews);
 // A newly available nearest parent must be found on the next update.
 const parent=ids[0].scaledTo(11);f.active.set(parent.key,{tileID:parent,state:'loaded',texture:{}});
 const next=f.manager._updateRetainedTiles(ids,12);assert.equal(next[parent.key]?.key,parent.key);
 assert.ok(next[parent.key]);console.log(JSON.stringify({uncachedRepeatedParentProbes:512*12,sharedParentProbes:counts.parents}));f.pool.destroy();
});
test('cached nearest ancestors still restore at full detail',()=>{
 const f=fixture(),ids=[new ID(12,1000,1500),new ID(12,1001,1500)],parent=ids[0].scaledTo(11),tile={tileID:parent,state:'loaded',texture:{}};f.cache.data[parent.key]=[{value:tile}];
 const result=f.manager._updateRetainedTiles(ids,12);assert.equal(result[parent.key].key,parent.key);assert.equal(f.active.get(parent.key),tile);assert.equal(f.pool.stats().ancestorRestores,1);f.pool.destroy();
});
