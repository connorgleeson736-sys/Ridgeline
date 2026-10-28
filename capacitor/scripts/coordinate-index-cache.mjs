// Cache geographic membership only. Native coordinate/matrix calculation still runs each call.
export function createCoordinateIndexCache(){
 const snapshots=new WeakMap();let builds=0,hits=0,reorders=0;
 function get(manager,build){
  const keys=manager._renderableTilesKeys;let old=snapshots.get(manager),same=!!old&&old.keys.length===keys.length,ordered=same;
  if(same)for(let i=0;i<keys.length;i++){
   const key=keys[i],id=manager._tiles[key]?.tileID,q=id?.canonical,row=old.rows.get(key);
   if(!row||row.id!==id||row.z!==q?.z||row.x!==q?.x||row.y!==q?.y){same=false;break;}
   if(old.keys[i]!==key)ordered=false;
  }
  if(same){hits++;if(!ordered){reorders++;old={...old,keys:keys.slice(),index:{...old.index,order:new Map(keys.map((key,i)=>[key,i]))}};snapshots.set(manager,old);}return old.index;}
  const index=build(),rows=new Map();
  for(const key of keys){const id=manager._tiles[key]?.tileID,q=id?.canonical;rows.set(key,{id,z:q?.z,x:q?.x,y:q?.y});}
  snapshots.set(manager,{keys:keys.slice(),rows,index});builds++;return index;
 }
 return{get,stats:()=>({coordinateIndexBuilds:builds,coordinateIndexHits:hits,coordinateIndexReorders:reorders})};
}
