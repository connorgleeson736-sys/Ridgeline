/* Bound downloaded-buffer retention independently of network concurrency.
   Cancellation settles the caller immediately; active slots remain occupied
   until the underlying worker actually acknowledges completion/cancellation. */
(function(root){
'use strict';
const abort=()=>Object.assign(Error('Regional work cancelled'),{name:'AbortError'});
function create({concurrency=2,maxQueued=32,maxBytes=16*1048576}={}){
 const queue=[],running=new Set();let dead=false,queuedBytes=0,cancelled=0,declined=0;
 const low=j=>j.priority?.()==='low';
 function unlink(j){const i=queue.indexOf(j);if(i<0)return;queue.splice(i,1);queuedBytes-=j.bytes;j.run=null;}
 function settle(j,error,value){if(j.settled)return;j.settled=true;j.signal?.removeEventListener('abort',j.cancel);error?j.reject(error):j.resolve(value);}
 function pump(){while(!dead&&running.size<concurrency&&queue.length){let i=0,best=Infinity;for(let k=0;k<queue.length;k++){const j=queue[k],rank=Number(j.rank?.())||0,score=(low(j)?10000:0)+rank-Math.min(50,(Date.now()-j.at)/1000);if(score<best){best=score;i=k;}}const j=queue[i],run=j.run;unlink(j);running.add(j);Promise.resolve().then(()=>{if(j.signal?.aborted)throw abort();return run();}).then(v=>settle(j,null,v),e=>settle(j,e)).finally(()=>{running.delete(j);pump();});}}
 function schedule(run,signal,{bytes=0,priority=()=> 'high',rank=()=>0}={}){return new Promise((resolve,reject)=>{
  if(dead||signal?.aborted){reject(abort());return;}
  const j={run,signal,bytes,priority,rank,at:Date.now(),resolve,reject,settled:false};j.cancel=()=>{cancelled++;unlink(j);settle(j,abort());};
  while(queue.length>=maxQueued||queuedBytes+bytes>maxBytes){const victim=queue.find(low);if(!victim)break;unlink(victim);settle(victim,abort());declined++;}
  if(queue.length>=maxQueued||queuedBytes+bytes>maxBytes){declined++;reject(low(j)?abort():Object.assign(Error('Regional queue busy'),{status:503}));return;}
  queue.push(j);queuedBytes+=bytes;signal?.addEventListener('abort',j.cancel,{once:true});if(signal?.aborted)j.cancel();pump();
 });}
 return{schedule,destroy(){dead=true;for(const j of [...queue]){unlink(j);settle(j,abort());}for(const j of running)settle(j,abort());},stats:()=>({compositesActive:running.size,compositesQueued:queue.length,queuedCompositionBytes:queuedBytes,activeCompositionInputBytes:[...running].reduce((n,j)=>n+j.bytes,0),compositionQueueByteLimit:maxBytes,compositionQueueLimit:maxQueued,compositionCancellations:cancelled,compositionDeclined:declined})};
}
const api={create,abort};if(typeof module!=='undefined')module.exports=api;else root.RidgeRegionalScheduler=api;
})(globalThis);
