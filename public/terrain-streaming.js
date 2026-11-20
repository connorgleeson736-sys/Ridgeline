/* Request ordering and readiness only. Native frustum/LOD selection remains
   authoritative; no speculative fine tiles or off-screen draw calls are added. */
(function(root){
'use strict';
function priority(id,focus){
 const q=id.canonical||id,n=2**q.z;if(!focus?.hit)return null;
 const distance=p=>Math.hypot(Math.max(q.x/n-p.x,0,p.x-(q.x+1)/n),Math.max(q.y/n-p.y,0,p.y-(q.y+1)/n));
 const hit=distance(focus.hit)/Math.max(1e-12,focus.range);
 const pivot=focus.pivot?distance(focus.pivot)/Math.max(1e-12,focus.pivotRange||focus.range)+.15:Infinity;
 return Math.min(hit,pivot);
}
function completeCoverage(ids){
 const levels=new Map();let max=0;
 for(const id of ids){const q=id.canonical||id;max=Math.max(max,q.z);if(!levels.has(q.z))levels.set(q.z,new Set());levels.get(q.z).add(q.x+'/'+q.y);}
 for(let z=max;z>0;z--){const row=levels.get(z);if(!row)continue;const parents=new Map();for(const key of row){const [x,y]=key.split('/').map(Number),p=Math.floor(x/2)+'/'+Math.floor(y/2);parents.set(p,(parents.get(p)||0)|(1<<((x%2)+(y%2)*2)));}
  for(const [key,bits] of parents)if(bits===15){if(!levels.has(z-1))levels.set(z-1,new Set());levels.get(z-1).add(key);}}
 return id=>{const q=id.canonical||id;for(let z=q.z;z>=0;z--){const d=2**(q.z-z);if(levels.get(z)?.has(Math.floor(q.x/d)+'/'+Math.floor(q.y/d)))return true;}return false;};
}
// An exact quadtree partition: retain measured, finished descendants and fill
// only their uncovered siblings with ordinary native patches. Never overlap a
// parent with children. Bound transient geometry during large zoom jumps.
function partitionCover(id,entries,{maxTiles=64,maxDepth=8}={}){
 const related=entries.filter(e=>e.tile.tileID.isChildOf(id));if(!related.length)return null;
 const result=[];
 function visit(node,rows,depth){
  if(result.length>=maxTiles)return false;
  const exact=rows.find(e=>e.tile.tileID.equals(node));if(exact){result.push({id:node,entry:exact});return true;}
  if(!rows.length){result.push({id:node});return true;}
  if(depth>=maxDepth)return false;
  const q=node.canonical,z=q.z+1,ID=node.constructor;
  for(let dy=0;dy<2;dy++)for(let dx=0;dx<2;dx++){
   const child=new ID(z,node.wrap,z,q.x*2+dx,q.y*2+dy);
   if(!visit(child,rows.filter(e=>e.tile.tileID.equals(child)||e.tile.tileID.isChildOf(child)),depth+1))return false;
  }return true;
 }
 return visit(id,related,0)?result:null;
}
function imageReadiness(map){
 const zoom=map.getZoom?.()??0,checks=[],sources=new Set();
 for(const layer of Object.values(map.style?._layers||{})){
  if(layer.type!=='raster'||!['linz','satellite','topo'].includes(layer.source))continue;
  const hidden=typeof layer.isHidden==='function'?layer.isHidden(zoom):layer.layout?.visibility==='none';if(hidden||sources.has(layer.source))continue;sources.add(layer.source);
  const manager=map.style.tileManagers?.[layer.source];if(typeof manager?.getVisibleCoordinates!=='function')continue;
  const ids=manager.getVisibleCoordinates().filter(id=>!!manager.getTile?.(id)?.texture);checks.push(completeCoverage(ids));
 }
 return id=>checks.every(check=>check(id));
}
const api={priority,completeCoverage,partitionCover,imageReadiness};if(typeof module!=='undefined')module.exports=api;else root.RidgeTerrainStreaming=api;
})(globalThis);
