/* MapLibre 6.14.0: culling bounds must include measured fine peaks, even
   while a lower-resolution DEM supplies a parent tile's envelope. This changes
   visibility tests only. It never changes elevation samples, meshes or routes. */
(function(root){
'use strict';
function attach(map,{engineVersion}={}){
 let current=null,undo=null,dead=false,dirty=true,dems=new Set(),index=new Map(),rebuilds=0,expanded=0,scans=0,incrementalUpdates=0;
 const compatible=engineVersion==='6.14.0';
 function sync(){
  if(dead||!compatible||map.terrain===current)return;
  undo?.();undo=null;current=map.terrain;dems=new Set();index=new Map();dirty=true;
  const terrain=current,manager=terrain?.tileManager,sourceManager=manager?.tileManager;
  if(!manager||!sourceManager?.getSource||typeof terrain.getMinMaxElevation!=='function'||typeof manager.update!=='function')return;
  const getBounds=terrain.getMinMaxElevation,update=manager.update,Revision=root.RidgeDEMRevision||(typeof require==='function'?require('./dem-revision.js'):null);
  const records=new Map(),owners=new Map(),changed=new Set();let full=true;
  const indexed=!!sourceManager._inViewTiles?.getTileById||typeof sourceManager.getTileByID==='function'||typeof sourceManager.getTile==='function';
  const revision=Revision?.track(sourceManager,{onChange(change){dirty=true;if(!indexed||change.full)full=true;else if(change.tile)changed.add(change.tile);else if(change.dem)for(const tile of owners.get(change.dem)||[])changed.add(tile);else full=true;}});let seenRevision=-1;
  function remove(tile){const old=records.get(tile);if(!old)return;records.delete(tile);const set=owners.get(old.dem);set.delete(tile);if(!set.size){owners.delete(old.dem);dems.delete(old.dem);revision?.release?.(old.dem);}
   for(const key of old.keys){const node=index.get(key);node.rows.delete(tile);if(!node.rows.size)index.delete(key);else node.dirty=true;}
  }
  function add(tile){const dem=tile.dem;if(!dem||!Number.isFinite(dem.min)||!Number.isFinite(dem.max)||dem.min>dem.max){remove(tile);return;}
   const q=tile.tileID.canonical,old=records.get(tile);if(old?.dem===dem&&old.min===dem.min&&old.max===dem.max&&old.z===q.z&&old.x===q.x&&old.y===q.y)return;
   remove(tile);const keys=[],record={dem,min:dem.min,max:dem.max,z:q.z,x:q.x,y:q.y,keys};records.set(tile,record);
   if(!owners.has(dem)){owners.set(dem,new Set());dems.add(dem);revision?.observe?.(dem);}owners.get(dem).add(tile);
   for(let z=q.z;z>=0;z--){const d=2**(q.z-z),key=z+'/'+Math.floor(q.x/d)+'/'+Math.floor(q.y/d);keys.push(key);let node=index.get(key);if(!node){node={min:dem.min,max:dem.max,rows:new Map(),dirty:false};index.set(key,node);}node.rows.set(tile,record);node.min=Math.min(node.min,dem.min);node.max=Math.max(node.max,dem.max);}
  }
  function resident(tile){const key=tile.tileID.key;if(sourceManager._inViewTiles?.getTileById?.(key)===tile||sourceManager.getTile?.(tile.tileID)===tile||sourceManager.getTileByID?.(key)===tile)return true;
   return(sourceManager._outOfViewCache?.data?.[tile.tileID.wrapped?.().key??key]||[]).some(row=>row.value===tile);
  }
  function refresh(){
   if(!dirty&&seenRevision===(revision?.revision??seenRevision))return;dirty=false;
   if(full){full=false;scans++;const rows=[...(sourceManager._inViewTiles?.getAllTiles?.()||[]),...Object.values(sourceManager._outOfViewCache?.data||{}).flatMap(rows=>rows.map(row=>row.value))],next=new Set(rows);
    for(const tile of records.keys())if(!next.has(tile))remove(tile);for(const tile of next)add(tile);rebuilds++;
   }else{for(const tile of changed){if(resident(tile))add(tile);else remove(tile);}incrementalUpdates+=changed.size;}
   changed.clear();seenRevision=revision?.revision??seenRevision;
  }
  function knownAt(key){const node=index.get(key);if(node?.dirty){node.min=Infinity;node.max=-Infinity;for(const row of node.rows.values()){node.min=Math.min(node.min,row.min);node.max=Math.max(node.max,row.max);}node.dirty=false;}return node;}
  function bounds(id){
   refresh();const native=getBounds.call(this,id),q=id.canonical,known=knownAt(q.z+'/'+q.x+'/'+q.y),scale=terrain.exaggeration;
   let min=native.minElevation,max=native.maxElevation;
   if(known){min=Number.isFinite(min)?Math.min(min,known.min*scale):known.min*scale;max=Number.isFinite(max)?Math.max(max,known.max*scale):known.max*scale;}
   const sourceTile=manager.getSourceTile(id,true);
   if(sourceTile?.dem&&Number.isFinite(min)&&Number.isFinite(max)){
    // A coarse pixel average is not an upper/lower geometric bound. Widen its
    // culling interval by two ground pixels, capped at 800 m, never its heightmap.
    const z=sourceTile.tileID.canonical.z,latitude=Math.atan(Math.sinh(Math.PI*(1-2*(q.y+.5)/2**q.z)));
    const margin=Math.min(800,Math.max(2,2*40075016.686*Math.cos(latitude)/(2**z*sourceTile.dem.dim)))*scale;
    min-=margin;max+=margin;
   }else if(!Number.isFinite(min)||!Number.isFinite(max)){
    // Conservative NZ land envelope while the very first DEM is pending.
    // Keep unknown bounding volumes tall enough to discover alpine terrain.
    min=-50*scale;max=4000*scale;
   }
   if(min!==native.minElevation||max!==native.maxElevation)expanded++;
   return{minElevation:min,maxElevation:max};
  }
  function onData(){dirty=true;full=true;}
  function run(...args){if(!revision){dirty=true;full=true;}return update.apply(this,args);}
  terrain.getMinMaxElevation=bounds;manager.update=run;if(!revision)sourceManager.on?.('data',onData);
  undo=()=>{revision?.destroy();sourceManager.off?.('data',onData);if(terrain.getMinMaxElevation===bounds)terrain.getMinMaxElevation=getBounds;if(manager.update===run)manager.update=update;};
 }
 function destroy(){if(dead)return;dead=true;undo?.();undo=null;dems.clear();index.clear();for(const [n,f] of listeners)map.off(n,f);}
 const listeners=[['terrain',sync],['remove',destroy]];if(compatible){listeners.forEach(([n,f])=>map.on(n,f));sync();}
 return{refresh:sync,destroy,stats:()=>({visibilityBoundsSupported:compatible,visibilityBoundScans:scans,visibilityIncrementalUpdates:incrementalUpdates,visibilityBoundRebuilds:rebuilds,visibilityBoundsExpanded:expanded,visibilityIndexedDEMs:dems.size,visibilityIndexNodes:index.size})};
}
const api={attach};if(typeof module!=='undefined')module.exports=api;else root.RidgeTerrainVisibility=api;
})(globalThis);
