// Offline selection experiment against the shipped engine, not a phone benchmark.
// Uses explicit flat elevation envelopes and an ideal measured centre-ray hit.
// This does not change production quality policy or renderer assets.
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
const require=createRequire(import.meta.url);
globalThis.window={requestAnimationFrame:fn=>setTimeout(fn,0),cancelAnimationFrame:clearTimeout};
globalThis.self=globalThis;
const base=path.resolve(process.argv[2]||'capacitor/www');
const E=await import(pathToFileURL(path.join(base,'assets/maplibre-gl.mjs')));
const P=require(path.join(base,'performance.js'));
globalThis.RidgeSurfaceFocus=require(path.join(base,'surface-focus.js'));
let transform;
E.Style.prototype._setProjectionInternal.call({map:{migrateProjection:t=>transform=t},tileManagers:{},_globalState:{}},'mercator');
transform.resize(412,820);
const holder={},policy=(levels,ratio)=>{
 E.Map.prototype.setSourceTileLodParams.call({getSource:()=>holder,_update(){}},levels,ratio,'probe');
 return holder.calculateTileZoom;
};
const rows=[],aggressiveRows=[];
function contains(a,b){const x=a.canonical,y=b.canonical;return a.wrap===b.wrap&&x.z<=y.z&&Math.floor(y.x/2**(y.z-x.z))===x.x&&Math.floor(y.y/2**(y.z-x.z))===x.y;}
function coverComplete(target,cover){
 if(cover.some(id=>contains(id,target)))return true;
 const child=cover.filter(id=>contains(target,id));if(!child.length)return false;
 const q=target.canonical,z=q.z+1;
 for(let i=0;i<4;i++)if(!coverComplete({wrap:target.wrap,canonical:{z,x:q.x*2+i%2,y:q.y*2+(i>>1)}},child))return false;
 return true;
}
for(const location of [{name:'Mount Cook',center:[170.141,-43.595]},{name:'Rolling Pin',center:[168.7089609,-44.36369167]},{name:'Wellington',center:[174.777,-41.289]}])
for(const phone of [false,true])for(const pitch of [0,58,75])for(const zoom of [12,15,17]){
 transform.setCenter(new E.LngLat(...location.center));transform.setElevation(1200);transform.setZoom(zoom);transform.setPitch(pitch);transform.setBearing(-24);
 const hit=E.MercatorCoordinate.fromLngLat(transform.center,1200),range=transform.cameraToCenterDistance/transform.worldSize;
 const state={hit,pivot:hit,range,pivotRange:range,fov:transform.fov,tile:null};
 const source=P.focusLod(policy(phone?10:6,phone?2:3.5),.25,()=>state);
 const baseline=phone?P.nearbyLod(source,()=>50,()=>state):source;
 const provider=transform.getCoveringTilesDetailsProvider(),distance=provider.distanceToTile2d;
 provider.distanceToTile2d=function(px,py,tile,bounds){state.tile=tile;return distance.call(this,px,py,tile,bounds);};
 const terrain={getMinMaxElevation:()=>({minElevation:1200,maxElevation:1200})};
 const select=calculateTileZoom=>E.Map.prototype.coveringTiles.call({_camera:{transform}},{tileSize:256,minzoom:0,maxzoom:19,terrain,calculateTileZoom,maxContentElevation:0});
 const ordinary=select(baseline);
 const deferred=select(function(...args){
  const value=baseline(...args),weights=RidgeSurfaceFocus.weights(state);
  // Delay peripheral refinement by one level. All surface/pivot influence
  // regions are protected, including the original policy's soft margins.
  return weights.surface>0||weights.pivot>0?value:value-1;
 });
 assert.ok(ordinary.length&&deferred.length,'Never accept an empty-view comparison');
 assert.ok(ordinary.every(id=>coverComplete(id,deferred)),'Temporary cover must have no holes');
 let protectedTiles=0,unchangedProtected=0;
 for(const id of ordinary){state.tile=id.canonical;const w=RidgeSurfaceFocus.weights(state);if(w.surface>0||w.pivot>0){protectedTiles++;if(deferred.some(q=>q.key===id.key))unchangedProtected++;}}
 assert.equal(protectedTiles,unchangedProtected,'Preserve all focused tile IDs');
 const old=new Set(ordinary.map(id=>id.key)),next=new Set(deferred.map(id=>id.key));
 const deferredChildren=ordinary.filter(id=>!next.has(id.key)).length,newParents=deferred.filter(id=>!old.has(id.key)).length;
 rows.push({location:location.name,phone,pitch,zoom,baselineTiles:ordinary.length,initialTiles:deferred.length,protectedTiles,deferredChildren,newParents,
  netInitialRequestsSaved:ordinary.length-deferred.length,
  eventualExtraRequests:newParents,
  finalSelectionRestored:true}); // Selecting baseline after admission is exactly the original call.
 // More aggressive alternative: protect a smaller central cone, defer the
 // outer two levels. This deliberately delays peripheral quality, then
 // restores the unchanged original selection. Report added cold parents too.
 const central=tile=>{const n=2**tile.z;
  const distance=point=>Math.hypot(Math.max(tile.x/n-point.x,0,point.x-(tile.x+1)/n),Math.max(tile.y/n-point.y,0,point.y-(tile.y+1)/n));
  return distance(hit)<=range*Math.tan(transform.fov*Math.PI/360)*.45;
 };
 const aggressive=select(function(...args){const value=baseline(...args);return central(state.tile)?value:value-2;});
 assert.ok(aggressive.length&&ordinary.every(id=>coverComplete(id,aggressive)));
 const focused=ordinary.filter(id=>central(id.canonical));
 assert.ok(focused.length&&focused.every(id=>aggressive.some(q=>q.key===id.key)));
 aggressiveRows.push({location:location.name,phone,pitch,zoom,baselineTiles:ordinary.length,initialTiles:aggressive.length,
  protectedTiles:focused.length,newParents:aggressive.filter(id=>!old.has(id.key)).length,finalSelectionRestored:true});
 assert.deepEqual(select(baseline).map(q=>q.key),ordinary.map(q=>q.key),'Restoring the original policy must restore its ordered selection');
 provider.distanceToTile2d=distance;
}
const summary={engineSha256:createHash('sha256').update(fs.readFileSync(path.join(base,'assets/maplibre-gl.mjs'))).digest('hex'),
 fixture:'412x820 CSS pixels; elevation envelope exactly 1200 m, ideal centre hit, no real terrain or network',
 candidate:'One level coarser outside every current surface/pivot influence region, then restore baseline',
 scenes:rows.length,baselineTiles:rows.reduce((s,r)=>s+r.baselineTiles,0),initialTiles:rows.reduce((s,r)=>s+r.initialTiles,0),
 extraRequestsOnCompleteRefinement:rows.reduce((s,r)=>s+r.newParents,0),
 maxReduction:rows.reduce((a,r)=>(1-r.initialTiles/r.baselineTiles)>(1-a.initialTiles/a.baselineTiles)?r:a),rows,
 aggressive:{policy:'Temporary two-level reduction outside a central cone; cone radius is 22.5% of the nominal vertical ground-view span',
  baselineTiles:aggressiveRows.reduce((s,r)=>s+r.baselineTiles,0),initialTiles:aggressiveRows.reduce((s,r)=>s+r.initialTiles,0),
  extraRequestsOnCompleteRefinement:aggressiveRows.reduce((s,r)=>s+r.newParents,0),rows:aggressiveRows}};
console.log(JSON.stringify(summary,null,2));
