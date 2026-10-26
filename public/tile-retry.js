/* Time-windowed, bounded recovery. An abandoned timer cannot occupy a retry
   slot forever. Source replacement/context transitions own distinct lifetimes. */
(function(root){
'use strict';
function create(map,retry,{clock=()=>Date.now(),random=()=>Math.random(),setTimer=setTimeout,clearTimer=clearTimeout,windowMs=30000,capacity=64}={}){
 const entries=new Map();let dead=false,scheduled=0,attempted=0,abandoned=0,declined=0;
 const key=e=>{const q=e.tile?.tileID?.canonical;return q?e.sourceId+'/'+q.z+'/'+q.x+'/'+q.y:null;};
 function drop(k){const v=entries.get(k);if(v)clearTimer(v.timer);entries.delete(k);}
 function prune(){const now=clock();for(const [k,v]of entries)if(v.manager!==map.style?.tileManagers?.[v.source]||!v.timer&&now-v.at>=windowMs)drop(k);}
 function error(e){if(dead||!e.tile?.tileID?.canonical||!['linz','satellite','topo'].includes(e.sourceId))return;const status=e.error?.status??e.error?.statusCode;if([401,403,404].includes(status)||e.error?.name==='AbortError'){const k=key(e);if(k)drop(k);return;}
  prune();const k=key(e),now=clock();let v=entries.get(k);if(v?.timer||v&&v.attempts>=2&&now-v.at<windowMs)return;
  if(!v){if(entries.size>=capacity){const cold=[...entries].find(([,r])=>!r.timer);if(cold)drop(cold[0]);else{declined++;return;}}v={at:now,attempts:0,timer:0,manager:map.style?.tileManagers?.[e.sourceId],source:e.sourceId,id:e.tile.tileID};entries.set(k,v);}
  v.attempts++;v.lastFailureAt=now;const requested=Number(e.error?.retryAfterMs),retryAfter=Number.isFinite(requested)?Math.max(0,Math.min(2147483647,requested)):0,delay=Math.max(retryAfter,Math.min(8000,1500*2**(v.attempts-1))*(.85+.3*random()));v.nextAllowedAt=now+delay;
  scheduled++;v.timer=setTimer(()=>{v.timer=0;if(dead)return;if(v.manager!==map.style?.tileManagers?.[v.source]||!v.manager?.getTile?.(v.id)){abandoned++;drop(k);return;}attempted++;if(!retry(map,v.source,v.id))drop(k);},delay);
 }
 function data(e){const k=key(e);if(k&&e.tile.state==='loaded')drop(k);if(e.sourceDataType==='metadata')for(const [k,v]of entries)if(v.source===e.sourceId)drop(k);}
 function reset(){for(const k of [...entries.keys()])drop(k);}
 let lastRecovery=-Infinity;function recover(){if(dead||clock()-lastRecovery<5000)return;lastRecovery=clock();const pending=[...entries.entries()].filter(([,v])=>!v.timer);for(const [k,v]of pending){drop(k);const tile=map.style?.tileManagers?.[v.source]?.getTile?.(v.id);if(tile?.state==='errored')error({sourceId:v.source,tile,error:{status:503}});}}
 return{error,data,prune,reset,recover,destroy(){dead=true;reset();},stats:()=>({retryRecords:entries.size,retryTimers:[...entries.values()].filter(v=>v.timer).length,retriesScheduled:scheduled,retriesAttempted:attempted,retriesAbandoned:abandoned,retriesDeclined:declined})};
}
const api={create};if(typeof module!=='undefined')module.exports=api;else root.RidgeTileRetry=api;
})(globalThis);
