/* Renderer-independent policies. No GL calls, camera changes or timers in planners. */
const clamp=(v,a,b)=>Math.max(a,Math.min(b,v));
export function limits(raw={},moving=false){const n=(k,d,a,b)=>clamp(Math.round(Number.isFinite(raw[k])?raw[k]:d),a,b);return{images:n(moving?'moving':'stationary',moving?8:12,1,96),imageryPending:n('imageryPending',4,0,24),demPending:n('demPending',2,0,12),predictive:n('predictive',2,0,12)};}
export function nearbyWeight(distance,range){const t=clamp((distance/Math.max(1,range)-.75)/1.25,0,1);return 1-t*t*(3-2*t);}
export function detailPolicy({distance,range,phoneMode=false,phoneDetail=50,imagery=.25,terrain=.25}){const weight=nearbyWeight(distance,range),near=phoneMode?(clamp(phoneDetail,0,100)-50)/50*.75:0,far=phoneMode?(1-weight)*1.25:0;return{imagery:2**(imagery+near*weight-far),terrain:2**(terrain+near*weight-far),weight};}
export function expandedView(fov,aspect,angle){const h=2*Math.atan(Math.tan(fov*Math.PI/360)*aspect),target=Math.min(175*Math.PI/180,h+clamp(angle,30,70)*Math.PI/180),scale=Math.min(8,Math.tan(target/2)/Math.tan(h/2),Math.tan(80*Math.PI/180)/Math.tan(fov*Math.PI/360));return{scale,fov:2*Math.atan(Math.tan(fov*Math.PI/360)*scale)*180/Math.PI};}
export function memoryPlan(totalMiB=352,lowPower=false){const total=clamp(totalMiB,96,3000)*1048576;return{resident:total*(lowPower?.22:.38),worker:Math.min(48*1048576,total*.1),prepared:Math.min(24*1048576,total*.045),queued:Math.min(lowPower?8*1048576:24*1048576,total*.06)};}
export function pendingOrder(a,b){return(a.gap?0:1)-(b.gap?0:1)||(a.gap&&b.gap?a.z-b.z:0)||(a.cached?0:1)-(b.cached?0:1)||a.priority-b.priority||a.z-b.z;}
const background=job=>typeof job.background==='function'?job.background():job.background;
const ready=job=>job.cached&&!background(job);
const aborted=()=>Object.assign(Error('Tile job cancelled'),{name:'AbortError'});
/* A cached ready lane is INSIDE the image limit. Background work cannot occupy it.
   Limits apply immediately to admission; existing work finishes or is cancelled. */
export class JobQueue{
 constructor(limit=12,{reserve=2,onChange=()=>{}}={}){this.limit=limit;this.reserve=reserve;this.onChange=onChange;this.queue=[];this.running=new Set();this.dead=false;}
 setLimit(n){this.limit=Math.max(1,Math.round(n));this.pump();}
 run(fn,{signal,cached=false,background=false,priority=()=>0}={}){return new Promise((resolve,reject)=>{if(this.dead||signal?.aborted){reject(aborted());return;}const job={fn,signal,cached,background,priority,resolve,reject,started:false};job.abort=()=>{if(job.started)return;this.queue=this.queue.filter(j=>j!==job);signal?.removeEventListener('abort',job.abort);reject(aborted());this.onChange();};signal?.addEventListener('abort',job.abort,{once:true});this.queue.push(job);this.pump();});}
 pump(){if(this.dead)return;while(this.running.size<this.limit&&this.queue.length){this.queue.sort((a,b)=>Number(background(a))-Number(background(b))||Number(ready(b))-Number(ready(a))||a.priority()-b.priority());const reserved=Math.min(this.reserve,Math.max(0,this.limit-1)),ordinary=[...this.running].filter(j=>!ready(j)).length;let i=this.queue.findIndex(j=>ready(j)||ordinary<this.limit-reserved);if(i<0)break;const job=this.queue.splice(i,1)[0];job.started=true;job.signal?.removeEventListener('abort',job.abort);if(job.signal?.aborted){job.reject(aborted());continue;}this.running.add(job);Promise.resolve().then(()=>{if(job.signal?.aborted)throw aborted();return job.fn();}).then(job.resolve,job.reject).finally(()=>{this.running.delete(job);this.pump();this.onChange();});}this.onChange();}
 stats(){return{active:this.running.size,limit:this.limit,queued:this.queue.length,cachedActive:[...this.running].filter(j=>j.cached).length,backgroundActive:[...this.running].filter(j=>background(j)).length};}
 destroy(){this.dead=true;for(const job of this.queue){job.signal?.removeEventListener('abort',job.abort);job.reject(aborted());}this.queue=[];}
}
