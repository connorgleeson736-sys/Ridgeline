import {test} from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
import {optimiseWeb} from '../scripts/optimise-web.mjs';

const env={module:{exports:{}},performance};vm.createContext(env);
for(const name of ['terrain-streaming.js','native-residency.js']){
 env.module={exports:{}};
 vm.runInContext(optimiseWeb(name,await readFile(new URL('../../public/'+name,import.meta.url),'utf8')),env);
 if(name==='terrain-streaming.js')env.RidgeTerrainStreaming=env.module.exports;
}
const S=env.RidgeTerrainStreaming,N=env.module.exports;
const id=(z,x,y)=>({key:z+'/'+x+'/'+y,overscaledZ:z,canonical:{z,x,y},
 scaledTo(level){const d=2**(z-level);return id(level,Math.floor(x/d),Math.floor(y/d));},
 equals(other){return this.key===other.key;},
 isChildOf(other){return z>other.canonical.z&&this.scaledTo(other.canonical.z).equals(other);}});

test('visible coverage admits completed refinement without waiting for unrequested terrain corners',()=>{
 const patch=id(12,3983,2600),fine=id(14,15932,10400),missing=id(14,15933,10400);
 const manager={_ridgeIdeal:{ids:[fine]}};
 assert.equal(S.completeCoverage([fine])(patch),false);
 assert.equal(S.visibleCoverage(manager,[fine])(patch),true);
 manager._ridgeIdeal={ids:[fine,missing]};
 assert.equal(S.visibleCoverage(manager,[fine])(patch),false,'Missing visible imagery must still hold continuity');
 assert.equal(S.visibleCoverage({},[fine])(patch),false,'Unknown native demand cannot weaken readiness');
 const coarse=id(11,1991,1300);
 assert.equal(S.visibleCoverage(manager,[coarse])(patch),true,'A real loaded ancestor may supply native fallback');
});

test('unchanged loaded image keys cannot retain a previous camera demand snapshot',()=>{
 const patch=id(12,3983,2600),fine=id(14,15932,10400),missing=id(14,15933,10400);
 const manager={_ridgeIdeal:{ids:[fine]},getVisibleCoordinates:()=>[fine],getTile:()=>({texture:{}})};
 const map={getZoom:()=>14,style:{_layers:{linz:{type:'raster',source:'linz',isHidden:()=>false}},tileManagers:{linz:manager}}};
 const before=S.imageReadiness(map);assert.equal(before(patch),true);
 manager._ridgeIdeal={ids:[fine,missing]};
 assert.equal(S.imageReadiness(map)(patch),false);
 assert.equal(before(patch),true,'Recorded completed coverage keeps its original immutable demand');
});

test('residency releases a coarse drape for finer visible imagery and still holds genuinely missing or worse detail',()=>{
 const patch=id(12,3983,2600),old=id(13,7966,5200),fine=id(14,15932,10400),missing=id(14,15933,10400);
 let loaded=[fine],reason;
 const fingerprint=()=>({_revision:0,_visibleLayerIds:'linz'});
 const tile={tileID:patch,rttObjects:[{size:1025}],rttFingerprint:{linz:fingerprint()},releaseRTT(){},getRTT(i){return this.rttObjects[i];}};
 const source={_ridgeIdeal:{ids:[fine]},getSource:()=>({type:'raster'}),getVisibleCoordinates:()=>loaded,getTile:()=>({texture:{}})};
 const style={_order:['linz'],_layers:{linz:{type:'raster',source:'linz',serialize:()=>({paint:{}})}},tileManagers:{linz:source}};
 const rtt={_lastPrepareZoom:14,_rttFingerprints:{linz:{[patch.key]:fingerprint()}},_coordsAscending:{linz:{[patch.key]:loaded}},prepareForRender(){reason=this._textureDifference(tile);},_textureDifference:()=> 'sourceTiles'};
 const manager={_tiles:{[patch.key]:tile},_renderableTilesKeys:[patch.key],update(){},releaseRTT(){},releaseAllRTT(){},destruct(){}};
 const map={getZoom:()=>14,style};
 const cache=N.retainDrapes(manager,{renderToTexture:rtt},{readiness:()=>S.imageReadiness(map)});
 cache.recordImageCoverage(tile,()=>true,true,[{source:'linz',requirements:[{id:old,minZoom:13}]}]);
 const visibleCoverage=S.visibleCoverage;S.visibleCoverage=undefined;
 try{rtt.prepareForRender(style,14,false);assert.equal(reason,'none','The old whole-footprint quality check incorrectly held the coarse drape');}
 finally{S.visibleCoverage=visibleCoverage;}
 rtt.prepareForRender(style,14,false);
 assert.equal(reason,'sourceTiles','Completed fine pixels must invalidate the coarse drape');
 source._ridgeIdeal={ids:[fine,missing]};
 rtt.prepareForRender(style,14,false);assert.equal(reason,'none','Incomplete visible replacement preserves the old image');
 loaded=[id(11,1991,1300)];rtt._coordsAscending.linz[patch.key]=loaded;source._ridgeIdeal={ids:[fine]};
 rtt.prepareForRender(style,14,false);assert.equal(reason,'none','A lower quality source cannot overwrite sharper finished pixels');
 cache.destroy();
});

test('geometry handoff follows native DEM deltaZoom and source ceiling, and still waits for a missing native dependency',()=>{
 function scenario({deltaZoom=1,maxzoom=18,loadedZoom=13}){
  const parent=id(13,7966,5200),child=id(14,15932,10400);
  const painter={releaseRTT(){}};
  const patch=q=>({tileID:q,rttObjects:[],getRTT(i){return this.rttObjects[i];},releaseRTT(){this.rttObjects=[];}});
  const old=patch(parent);old.rttObjects=[{size:1025}];const next=patch(child);
  let availableZoom=loadedZoom;
  const manager={deltaZoom,_tiles:{[parent.key]:old},_renderableTilesKeys:[parent.key],
   tileManager:{_source:{minzoom:0,maxzoom},_inViewTiles:{getAllTiles:()=>[14,13,12].map(z=>({tileID:child.scaledTo(z),state:z<=availableZoom?'loaded':'loading'}))}},
   getSourceTile(q){return {tileID:q.scaledTo(Math.min(q.canonical.z,availableZoom)),dem:{}};},
   update(){this._tiles[child.key]=next;this._renderableTilesKeys=[child.key];old.releaseRTT(painter);delete this._tiles[parent.key];return true;},
   releaseRTT(){},releaseAllRTT(){},destruct(){}};
  const cache=N.retainDrapes(manager,painter,{readiness:()=>()=>true});
  cache.recordImageCoverage(old,()=>true);manager.update();
  return{manager,cache,child,parent,arrive(z){availableZoom=z;manager.update();}};
 }
 const ready=scenario({loadedZoom:13});
 assert.equal(ready.manager._renderableTilesKeys[0],ready.child.key,'z14 patch is ready with native z13 DEM even while z14 DEM is requested nearby');ready.cache.destroy();
 const capped=scenario({maxzoom:12,loadedZoom:12});
 assert.equal(capped.manager._renderableTilesKeys[0],capped.child.key,'Source maxzoom is a complete dependency, not an impossible future wait');capped.cache.destroy();
 const pending=scenario({loadedZoom:12});
 assert.equal(pending.manager._renderableTilesKeys[0],pending.parent.key,'A genuinely pending z13 native dependency retains measured parent coverage');
 pending.arrive(13);assert.equal(pending.manager._renderableTilesKeys[0],pending.child.key);pending.cache.destroy();
});
