/* Source-union content revisions. Mutation hooks avoid enumerating stable
   caches each frame; extrema setters catch in-place min/max replacements. */
(function(root){
'use strict';
function track(manager,{onChange=()=>{}}={}){let revision=0,dead=false;const undo=[],watched=new Map();const bump=change=>{if(!dead){revision++;onChange(change||{full:true});}};
 const cache=manager._outOfViewCache;
 for(const name of ['onRemove','reset'])if(typeof cache?.[name]==='function'){const old=cache[name];function changed(...args){try{return old.apply(this,args);}finally{bump(name==='reset'?{full:true}:{tile:args[0]});}}cache[name]=changed;undo.push(()=>{if(cache[name]===changed)cache[name]=old;});}
 if(typeof manager._unloadTile==='function'){const old=manager._unloadTile;function unload(tile,...args){const hadDEM=!!tile.dem;try{return old.call(this,tile,...args);}finally{if(hadDEM)bump({tile});}}manager._unloadTile=unload;undo.push(()=>{if(manager._unloadTile===unload)manager._unloadTile=old;});}
 if(typeof cache?.add==='function'){const old=cache.add;function add(id,tile,...args){const result=old.call(this,id,tile,...args);if(tile?.dem)bump({tile});return result;}cache.add=add;undo.push(()=>{if(cache.add===add)cache.add=old;});}
 const data=e=>bump(e?.tile?{tile:e.tile}:{full:true});manager.on?.('data',data);
 function watch(dem){if(watched.has(dem))return;const restores=[];
  for(const key of ['min','max']){const d=Object.getOwnPropertyDescriptor(dem,key);if(!d?.configurable||!d.writable)continue;let value=d.value;const get=()=>value,set=v=>{if(v!==value){value=v;bump({dem});}};Object.defineProperty(dem,key,{get,set,configurable:true,enumerable:d.enumerable});restores.push(()=>{if(Object.getOwnPropertyDescriptor(dem,key)?.get===get)Object.defineProperty(dem,key,{...d,value});});}
  watched.set(dem,()=>restores.forEach(fn=>fn()));
 }
 return{get revision(){return revision;},bump,observe:watch,release(dem){watched.get(dem)?.();watched.delete(dem);},retain(dems){for(const [dem,restore]of watched)if(!dems.has(dem)){restore();watched.delete(dem);}for(const dem of dems)watch(dem);},destroy(){dead=true;manager.off?.('data',data);undo.reverse().forEach(fn=>fn());for(const restore of watched.values())restore();watched.clear();}};
}
const api={track};if(typeof module!=='undefined')module.exports=api;else root.RidgeDEMRevision=api;
})(globalThis);
