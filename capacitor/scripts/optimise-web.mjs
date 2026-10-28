// App-only work reductions. No source/detail/mesh selection changes.
function replace(text, from, to) {
 if(!text.includes(from))throw Error('Optimisation integration changed: '+from.slice(0,80));
 return text.replace(from,to);
}
export function optimiseWeb(name,text){
 if(name==='tile-cache.js'){
  text=replace(text,'let bytes=0;','let bytes=0,nextExpiry=Infinity;');
  text=replace(text,' function store(id,item,',' function prune(){const now=clock();if(now<nextExpiry)return;nextExpiry=Infinity;for(const [id,item]of items){if(item.until<=now)remove(id);else nextExpiry=Math.min(nextExpiry,item.until);}}\n function store(id,item,');
  text=replace(text,'prefetchOnly=false){if(item.data','prefetchOnly=false){prune();if(item.data');
  text=replace(text,'items.set(id,item);bytes+=','items.set(id,item);nextExpiry=Math.min(nextExpiry,item.until);bytes+=');
  text=replace(text,'hasEntries:()=>items.size>0','hasEntries:()=>{prune();return items.size>0;}');
  text=replace(text,'canPrefetch:()=>bytes<maxBytes*.85&&items.size<maxItems*.9','canPrefetch:()=>{prune();return bytes<maxBytes*.85&&items.size<maxItems*.9;}');
  text=replace(text,'pending.has(url),stats:',"(pending.has(url)&&!pending.get(url).controller.signal.aborted),stats:");
  text=replace(text,'clear(){items.clear();bytes=0;','clear(){items.clear();bytes=0;nextExpiry=Infinity;');
 }
 if(name==='raster-residency.js'){
  text=replace(text,'  let needed=protect();','  let needed=protect();\n  let coarseBytes=0;for(const [tile,entry]of entries)if(tile.tileID.canonical.z<=12)coarseBytes+=entry.bytes;');
  text=replace(text,'   let coarseBytes=0;for(const [tile,entry]of entries)if(tile.tileID.canonical.z<=12)coarseBytes+=entry.bytes;','');
  text=replace(text,'   if(wrapper){evictions++;','   if(tile.tileID.canonical.z<=12)coarseBytes-=entry.bytes;\n   if(wrapper){evictions++;');
 }
 return text;
}
