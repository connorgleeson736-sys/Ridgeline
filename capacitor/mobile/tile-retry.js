/* App-only recovery: still-owned transient failures keep a bounded timer while
   the view is stationary. Source/context lifetimes and successful loads retire it. */
(function(root){'use strict';
function create(map,retry,{clock=()=>Date.now(),random=()=>Math.random(),setTimer=setTimeout,clearTimer=clearTimeout,windowMs=30000,capacity=64}={}){
 const entries=new Map();let dead=false,scheduled=0,attempted=0,abandoned=0,declined=0;
 const key=e=>{const q=e.tile?.tileID?.canonical;return q?e.sourceId+'/'+q.z+'/'+q.x+'/'+q.y:null;};
 const tile=v=>v.manager===map.style?.tileManagers?.[v.source]?v.manager?.getTile?.(v.id):null;
 function drop(k){const v=entries.get(k);if(v?.timer)clearTimer(v.timer);entries.delete(k);}
 function prune(){for(const [k,v]of entries)if(v.manager!==map.style?.tileManagers?.[v.source]||v.timer&&!tile(v)||!v.timer&&clock()-v.at>=windowMs)drop(k);}
 function transient(error){const status=error?.status??error?.statusCode;return status===408||status===429||status>=500&&status<=599||
  status==null&&(['TypeError','NetworkError','TimeoutError'].includes(error?.name)||/network|fetch|timeout|temporar|unavailable/i.test(error?.message||''));}
 function arm(k,v,delay){
  scheduled++;v.timer=setTimer(()=>{
   v.timer=0;if(dead||entries.get(k)!==v)return;
   const current=tile(v);
   if(!current||current.state!=='errored'&&!(current.state==='loaded'&&current._ridgeFallback?.retry)){abandoned++;drop(k);return;}
   if(root.document?.hidden||root.navigator?.onLine===false){arm(k,v,15000);return;}
   attempted++;if(!retry(map,v.source,v.id))drop(k);
  },delay);
 }
 function error(e){
  if(dead||!e.tile?.tileID?.canonical||!['linz','satellite','topo'].includes(e.sourceId))return;
  const status=e.error?.status??e.error?.statusCode,k=key(e);
  if(e.error?.name==='AbortError'||status>=400&&status<500&&status!==408&&status!==429){drop(k);return;}
  prune();let v=entries.get(k);const now=clock();
  if(v?.timer||v&&v.attempts>=2&&!transient(e.error)&&now-v.at<windowMs)return;
  if(!v){if(entries.size>=capacity){const cold=[...entries].find(([,r])=>!r.timer);if(cold)drop(cold[0]);else{declined++;return;}}v={at:now,attempts:0,timer:0,manager:map.style?.tileManagers?.[e.sourceId],source:e.sourceId,id:e.tile.tileID};entries.set(k,v);}
  v.attempts=Math.min(1000,v.attempts+1);v.lastFailureAt=now;
  const requested=Number(e.error?.retryAfterMs),retryAfter=Number.isFinite(requested)?Math.max(0,Math.min(2147483647,requested)):0;
  const delay=Math.max(retryAfter,Math.min(15000,1500*2**Math.min(4,v.attempts-1)*(.85+.3*random())));
  v.serverNotBefore=now+retryAfter;v.nextAllowedAt=now+delay;arm(k,v,delay);
 }
 function data(e){const k=key(e);if(k&&e.tile.state==='loaded'){
  if(e.tile._ridgeFallback?.retry)error({...e,error:{status:503,retryAfterMs:e.tile._ridgeFallback.retryAfterMs}});
  else drop(k);
 }if(e.sourceDataType==='metadata')for(const [k,v]of entries)if(v.source===e.sourceId)drop(k);}
 function reset(){for(const k of [...entries.keys()])drop(k);}
 function scan(){if(dead)return;for(const sourceId of ['linz','satellite','topo']){const manager=map.style?.tileManagers?.[sourceId];const rows=manager?._inViewTiles?.getAllTiles?.()||Object.values(manager?._tiles||{});for(const t of rows)if(t.state==='loaded'&&t._ridgeFallback?.retry)data({sourceId,tile:t});}}
 let lastRecovery=-Infinity;
 function recover(){if(dead||clock()-lastRecovery<5000)return;lastRecovery=clock();scan();for(const [k,v]of [...entries]){const t=tile(v);if(t&&(t.state==='errored'||t._ridgeFallback?.retry)&&clock()>=(v.serverNotBefore||0)){if(v.timer)clearTimer(v.timer);v.timer=0;arm(k,v,0);}}}
 return{error,data,prune,reset,recover,scan,destroy(){dead=true;reset();},stats:()=>({retryRecords:entries.size,retryTimers:[...entries.values()].filter(v=>v.timer).length,retriesScheduled:scheduled,retriesAttempted:attempted,retriesAbandoned:abandoned,retriesDeclined:declined})};
}
const api={create};if(typeof module!=='undefined')module.exports=api;else root.RidgeTileRetry=api;
})(globalThis);
