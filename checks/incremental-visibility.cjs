const assert=require('node:assert/strict'),V=require('../public/terrain-visibility.js');
let scans=0;const active=new Map(),events={},data=[],cache={data:{},onRemove(){},reset(){this.data={};},add(id,tile){this.data[id.key]=[{value:tile}];}};
const source={getSource:()=>({type:'raster-dem'}),_inViewTiles:{getTileById:key=>active.get(key),getAllTiles(){scans++;return[...active.values()];}},_outOfViewCache:cache,on(n,f){if(n==='data')data.push(f);},off(n,f){const i=data.indexOf(f);if(i>=0)data.splice(i,1);}};
const terrain={exaggeration:1,getMinMaxElevation:()=>({minElevation:0,maxElevation:100}),tileManager:{tileManager:source,getSourceTile:()=>null,update(){}}},map={terrain,on:(n,f)=>events[n]=f,off:n=>delete events[n]};
const tile=i=>({tileID:{key:i,canonical:{z:14,x:i,y:1},wrapped(){return this;}},dem:{min:200+i,max:3000+i,dim:256}});
for(let i=0;i<96;i++)active.set(i,tile(i));const control=V.attach(map,{engineVersion:'6.14.0'}),root={canonical:{z:0,x:0,y:0}};
function verify(){const rows=[...active.values(),...Object.values(cache.data).flatMap(a=>a.map(v=>v.value))],bounds=terrain.getMinMaxElevation(root);assert.equal(bounds.minElevation,Math.min(0,...rows.map(t=>t.dem.min)));assert.equal(bounds.maxElevation,Math.max(100,...rows.map(t=>t.dem.max)));}
verify();const initial=scans;
for(let i=0;i<500;i++){
 const key=i%96,t=active.get(key);t.dem.max=1000+(i*71)%4000;t.dem.min=-100+(i*37)%1000;if(i%7===0){active.delete(key);cache.add(t.tileID,t);}else for(const f of data)f({tile:t});verify();
 if(i%7===0){delete cache.data[key];cache.onRemove(t);verify();active.set(key,tile(key));for(const f of data)f({tile:active.get(key)});verify();}
}
assert.equal(scans,initial,'Known tile/extrema changes must not enumerate all DEMs');assert.ok(control.stats().visibilityIncrementalUpdates>500);control.destroy();assert.equal(data.length,0);assert.equal(Object.getOwnPropertyDescriptor(active.get(1).dem,'max').get,undefined);
console.log(JSON.stringify({incrementalMutationCases:500,fullScans:scans,exactExtremaAgainstFullReference:true,cleanup:true}));
