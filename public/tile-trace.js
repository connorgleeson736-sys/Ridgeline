/* Passive, bounded stages. Attempt IDs survive cache admission and retries.
   No URLs/keys or route geometry are included in recorded rows. */
(function(root){
'use strict';
const listeners=new Set(),ids=new Map(),epochs=new Map(),attemptEpochs=new Map();let serial=0,moving=false,generation=0;
const sources=new Set(['linz','satellite','topo','terrain','terrain-fallback','shade-dem','terrain-rtt']);
const stages=new Set(['decode-queued','image-delivery-start','image-delivery-finished','native-image-queued','native-image-admitted','decode-start','decode-finished','request-queued','byte-hit','fetch-start','network-start','network-headers','network-body-ready','body-ready','request-error','transform-queued','transform-start','transform-finished','source-load-start','source-ready-upload-submitted','source-adopted','rtt-adopted','rtt-invalidated','rtt-repaint-submitted','rtt-draw-submitted']);
function emit(stage,source,q,details={}){
 if(!listeners.size||!stages.has(stage)||!sources.has(source)||!q||![q.z,q.x,q.y].every(Number.isInteger))return;
 const key=source+'/'+q.z+'/'+q.x+'/'+q.y;let id=details.attempt||ids.get(key);
 if(!id||stage==='source-load-start'||stage==='rtt-adopted'){id=++serial;ids.delete(key);ids.set(key,id);while(ids.size>1024)ids.delete(ids.keys().next().value);}
 if(!attemptEpochs.has(id)){attemptEpochs.set(id,epochs.get(source)||0);while(attemptEpochs.size>1024)attemptEpochs.delete(attemptEpochs.keys().next().value);}
 const row={id,key,stage,ms:root.performance.now(),moving,generation,sourceGeneration:attemptEpochs.get(id)};
 for(const k of ['elapsedMs','bytes','revision','generation','active','queued'])if(Number.isFinite(details[k]))row[k]=details[k];
 for(const k of ['tier','reason','variant'])if(typeof details[k]==='string'&&/^[a-zA-Z0-9_-]{1,40}$/.test(details[k]))row[k]=details[k];
 if(details.contributors)row.contributors=details.contributors.filter(v=>/^(linz|satellite|topo)\/\d+\/\d+\/\d+$/.test(v)).slice(0,8);
 for(const fn of listeners)try{fn(row);}catch{}return id;
}
function url(stage,value,details){if(!listeners.size)return;let u;try{u=new URL(value);}catch{return;}const q=u.pathname.match(/\/tiles\/(aerial|elevation|topo-raster)\/WebMercatorQuad\/(\d+)\/(\d+)\/(\d+)\./);if(q)return emit(stage,q[1]==='aerial'?'linz':q[1]==='topo-raster'?'topo':'terrain',{z:+q[2],x:+q[3],y:+q[4]},details);
 const s=u.protocol==='ridge:'&&u.hostname==='esri'?u.pathname.match(/^\/(imagery|topo)\/(\d+)\/(\d+)\/(\d+)$/):null;
 if(s)return emit(stage,s[1]==='topo'?'topo':'satellite',{z:+s[2],x:+s[4],y:+s[3]},details);
 const a=u.pathname.match(/\/MapServer\/tile\/(\d+)\/(\d+)\/(\d+)$/);if(a)return emit(stage,u.pathname.includes('Topo')?'topo':'satellite',{z:+a[1],x:+a[3],y:+a[2]},details);}
const api={emit,url,resetSource(source){if(!sources.has(source))return;epochs.set(source,(epochs.get(source)||0)+1);for(const key of ids.keys())if(key.startsWith(source+'/'))ids.delete(key);},setMoving(value){moving=!!value;if(moving)generation++;},enabled:()=>listeners.size>0,subscribe(fn){listeners.add(fn);return()=>{listeners.delete(fn);if(!listeners.size){ids.clear();attemptEpochs.clear();}};}};if(typeof module!=='undefined')module.exports=api;else root.RidgeTileTrace=api;
})(globalThis);
