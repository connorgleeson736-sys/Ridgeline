import {test} from 'node:test';import assert from 'node:assert/strict';import vm from 'node:vm';import {readFile} from 'node:fs/promises';
async function load(app){const c={module:{exports:{}}};c.globalThis=c;vm.runInNewContext(await readFile(new URL(app?'../www/native-residency.js':'../../public/native-residency.js',import.meta.url),'utf8'),c);return c.module.exports;}
function id(z,x,y){return {key:`${z}/${x}/${y}`,canonical:{z,x,y},equals(other){const q=other.canonical;return z===q.z&&x===q.x&&y===q.y;},isChildOf(other){const q=other.canonical,d=2**(z-q.z);return z>q.z&&Math.floor(x/d)===q.x&&Math.floor(y/d)===q.y;}};}
async function measure(app,{warm=false,vector=false,hidden=false,parent=false,unrelated=false,empty=false}={}){
 const {retainDrapes}=await load(app);let reads=0,releases=0;
 const eventId=parent?id(11,250,2):id(12,unrelated?2000:500,4),tiles={};
 for(let i=0;i<1000;i++){let objects=empty?[]:[{size:64}];const q=id(12,i,4);tiles[q.key]={tileID:q,rttFingerprint:{photo:{_tileKeys:eventId.key}},get rttObjects(){reads++;return objects;},releaseRTT(){releases++;objects=[];}};}
 const manager={_tiles:tiles,_renderableTilesKeys:Object.keys(tiles),update(){if(warm){this._renderableTilesKeys=[id(12,0,4).key];for(const key of Object.keys(this._tiles))if(key!==this._renderableTilesKeys[0]){this._tiles[key].releaseRTT();delete this._tiles[key];}}return true;},getSourceTile:()=>({dem:{}}),releaseAllRTT(){},releaseRTT(){},destruct(){}};
 const control=retainDrapes(manager,{}, {contentUpdate:()=>vector,imageryUpdate:()=>!vector,imageryEvent:()=>({sourceId:hidden?'other':'photo'})});if(warm)manager.update();reads=0;manager.releaseRTT(eventId);
 const result={reads,releases,invalidations:control.stats().invalidations,ignored:control.stats().cacheReturns.ignoredImageryEvents,warm:control.stats().residentDrapes};control.destroy();return result;
}
test('arrivals inspect texture sizes only for related active/warm patches, with identical invalidation decisions',async()=>{
 for(const options of [{},{warm:true},{vector:true},{warm:true,vector:true},{hidden:true},{parent:true},{unrelated:true},{empty:true}]){
  const before=await measure(false,options),after=await measure(true,options);assert.equal(before.reads,1000);assert.equal(after.reads,options.unrelated?0:options.parent?2:1);
  for(const key of ['releases','invalidations','ignored','warm'])assert.equal(after[key],before[key],key+JSON.stringify(options));
 }
});
