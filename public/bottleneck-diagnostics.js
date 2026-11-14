/* Opt-in, five-second measurements. No forced frames, GPU readbacks, URLs or
   camera coordinates. A GPU timer is an asynchronous elapsed-work query,
   never a GPU-utilisation percentage or proof of screen presentation. */
(function(root){
'use strict';
const WINDOW=5000,LIMIT=1024;
function percentile(values,p=.95){if(!values.length)return null;const a=values.slice().sort((a,b)=>a-b);return a[Math.floor((a.length-1)*p)];}
function classify(m){
 if(m.contextLost)return{pan:'Graphics context lost',loading:'Paused',advice:'The browser lost the graphics context. Wait for recovery.'};
 const slow=m.frames.samples>=8&&m.frames.p95Ms>22,cpu=m.render.samples>=8&&m.render.p95Ms>16.7||m.longTasks.blockingMs>=100,
  gpu=m.gpu.samples>=4&&m.gpu.p95Ms>12;
 let pan=!slow?'No sustained slowdown measured':cpu&&gpu?'Likely mixed CPU and GPU pressure':cpu?'Likely main-thread bottleneck':gpu?'Likely GPU drawing bottleneck':'Slow frames; cause unresolved';
 if(m.frames.samples<8)pan='Pan the map to collect samples';
 const s=m.stages,q=m.imageQueue||{},r=m.regional||{},waiting=(q.queued||0)>0||m.pending>0;
 let loading='No loading backlog measured',advice='Pan for several seconds, then revisit the same area to compare cached loading.';
 if((r.compositesQueued||0)>0&&s.transform?.samples>=3&&s.transform.p95Ms>40){loading='Image preparation backlog';advice='Regional image composition is queued. More image jobs may increase this backlog.';}
 else if(waiting&&s.decode?.samples>=3&&s.decode.p95Ms>40){loading='Slow image decoding';advice='Try fewer concurrent image jobs and compare frame times.';}
 else if((q.queued||0)>0&&s.admission?.samples>=3&&s.admission.p95Ms>80){loading='Image job admission backlog';advice='Compare a modest increase in image jobs; watch decoding and frame times.';}
 else if(m.pending>0&&s.delivery?.samples>=3&&s.delivery.p95Ms>350){loading='Slow tile delivery';advice='Delivery includes network, cache access and regional processing; this does not isolate network speed.';}
 else if(m.refreshBacklog>0){loading='Terrain texture refresh backlog';advice='Source imagery may be ready while terrain textures are still being prepared.';}
 else if(waiting)loading='Tiles loading; collecting stage timings';
 if(slow&&cpu)advice='Main-thread work is delaying frames. Lower nearby detail and compare the timings.';
 else if(slow&&gpu)advice='Try Phone mode to reduce drawing resolution and distant detail, then compare GPU time.';
 else if(slow)advice='Frame pacing is slow, but the measured work does not isolate the cause. GPU timing may be unavailable; browser scheduling and unmeasured work remain possible.';
 return{pan,loading,advice};
}
function gpuTimer(gl){
 let ext=null,active=null,pending=[],ticks=0,status='Unavailable',dead=false;
 try{if(gl?.createQuery)ext=gl.getExtension('EXT_disjoint_timer_query_webgl2');if(ext)status='Collecting';}catch{}
 function discard(){if(!gl?.isContextLost?.())for(const q of pending)gl.deleteQuery(q);pending=[];}
 return{
  start(){if(dead||!ext||gl.isContextLost()||active||pending.length>=4||++ticks%12)return;
   // Do not overlap a query owned by the renderer or another diagnostic.
   if(gl.getQuery(ext.TIME_ELAPSED_EXT,gl.CURRENT_QUERY))return;
   const q=gl.createQuery();if(!q)return;gl.beginQuery(ext.TIME_ELAPSED_EXT,q);active=q;
  },
  end(){if(!active)return;const q=active;active=null;if(gl.isContextLost()){status='Context lost';return;}gl.endQuery(ext.TIME_ELAPSED_EXT);pending.push(q);},
  poll(){if(!ext||dead)return{status,values:[]};if(gl.isContextLost()){pending=[];return{status:'Context lost',values:[],invalid:true};}
   if(gl.getParameter(ext.GPU_DISJOINT_EXT)){discard();status='Timing reset by GPU';return{status,values:[],invalid:true};}
   const values=[];while(pending.length&&gl.getQueryParameter(pending[0],gl.QUERY_RESULT_AVAILABLE)){
    const q=pending.shift(),ms=gl.getQueryParameter(q,gl.QUERY_RESULT)/1e6;gl.deleteQuery(q);if(Number.isFinite(ms)&&ms>=0)values.push(ms);
   }if(values.length)status='Measured';return{status,values};
  },
  destroy(){dead=true;discard();},get status(){return status;}
 };
}
function attach(map,{stats=()=>({}),clock=()=>root.performance.now(),onUpdate=()=>{},onCapture=()=>{},environment=root}={}){
 const env=environment,series={},spans=new Map();let enabled=false,dead=false,interval=null,observer=null,unsubscribe=null,original=null,wrapped=null,renderSession=null,timer=null,started=0,lastFrame=null;
 let gpuStatus='Unavailable',longTaskSupported=false;
 function add(name,value,time=clock()){if(!Number.isFinite(value)||value<0)return;const a=series[name]??=[];a.push({time,value});if(a.length>LIMIT)a.shift();}
 function recent(name,now=clock()){const a=series[name]??=[];while(a.length&&a[0].time<now-WINDOW)a.shift();return a.map(r=>r.value);}
 function summary(name,now){const v=recent(name,now);return{samples:v.length,p95Ms:percentile(v),meanMs:v.length?v.reduce((a,b)=>a+b,0)/v.length:null};}
 const pairs={'decode-start':['decode-queued','decodeAdmission'],'image-delivery-finished':['image-delivery-start','imageDelivery'],'network-headers':['network-start','networkHeaders'],'network-body-ready':['network-headers','networkBody'],'native-image-admitted':['native-image-queued','admission'],'decode-finished':['decode-start','decode'],'body-ready':['fetch-start','delivery'],'transform-finished':['transform-start','transform']};
 function trace(row){if(!enabled)return;let a=spans.get(row.id);if(!a){a={};spans.set(row.id,a);if(spans.size>1024)spans.delete(spans.keys().next().value);}const p=pairs[row.stage];if(p&&a[p[0]]!==undefined)add(p[1],row.ms-a[p[0]]);a[row.stage]=row.ms;}
 function snapshot(){const now=clock(),s=stats()||{},frames=summary('frames',now),render=summary('render',now),gpu=summary('gpu',now),tasks=recent('blocking',now),regional=s.tileCache?.regional||{};
  const m={enabled,windowSeconds:5,observedSeconds:Math.min(5,Math.max(0,(now-started)/1000)),moving:!!map.isMoving?.(),contextLost:map.painter?.context?.gl?.isContextLost?.()||false,
   frames:{...frames,fps:frames.meanMs?1000/frames.meanMs:null},render,gpu:{...gpu,status:s.gpuTiming||gpuStatus},longTasks:{supported:longTaskSupported,count:tasks.length,blockingMs:tasks.reduce((a,b)=>a+b,0)},
   stages:Object.fromEntries(['delivery','imageDelivery','networkHeaders','networkBody','decodeAdmission','decode','admission','transform'].map(n=>[n,summary(n,now)])),imageQueue:s.imageQueue||env.maplibregl?.getImageRequestQueueStats?.()||null,
   pending:s.pending??((s.tileCache?.imagery?.pending||0)+(s.tileCache?.terrain?.pending||0)),regional:{compositesActive:regional.compositesActive||0,compositesQueued:regional.compositesQueued||0},refreshBacklog:s.uploadQueued??s.refreshBacklog??0,
   terrain:s.adaptiveTerrain||null,
   notes:'Rolling 5-second samples while this panel is enabled. Frames are measured only during map movement. CPU render time covers synchronous map drawing submission. Delivery includes routing/transforms. Preparation measures the instrumented image or terrain path; total CPU, all workers, VRAM and network bandwidth are not measured.'};
  return{...m,diagnosis:classify(m)};
 }
 function update(){if(!enabled)return;try{const result=timer?.poll();if(result){gpuStatus=result.status;if(result.invalid)delete series.gpu;for(const ms of result.values)add('gpu',ms);}onUpdate(snapshot());}catch{gpuStatus='Unavailable';}}
 function frame(){if(!enabled||env.document?.hidden||!map.isMoving?.()){lastFrame=null;return;}const now=clock();if(lastFrame!==null&&now>lastFrame)add('frames',now-lastFrame);lastFrame=now;}
 function boundary(){lastFrame=null;}
 function performanceSample(event){if(enabled&&['render','delivery','decode','admission','transform','gpu'].includes(event.stage))add(event.stage,event.ms);}
 function clear(){for(const name of Object.keys(series))delete series[name];spans.clear();lastFrame=null;started=clock();}
 function start(){if(enabled||dead||env.document?.hidden)return;enabled=true;onCapture(true);map.setDiagnosticsEnabled?.(true);clear();timer=gpuTimer(map.painter?.context?.gl);gpuStatus=timer.status;
  const painter=map.painter;original=painter?.render;
  if(typeof original==='function'){
   const native=original,session=renderSession={active:true};
   wrapped=function(...args){if(!session.active)return native.apply(this,args);const before=clock();try{try{timer?.start();}catch{gpuStatus='Unavailable';}return native.apply(this,args);}finally{try{timer?.end();}catch{gpuStatus='Unavailable';}add('render',clock()-before);}};painter.render=wrapped;
  }
  unsubscribe=typeof map.setDiagnosticsEnabled==='function'?null:env.RidgeTileTrace?.subscribe(trace);
  try{if(env.PerformanceObserver?.supportedEntryTypes?.includes('longtask')){observer=new env.PerformanceObserver(list=>{for(const entry of list.getEntries())if(entry.startTime>=started)add('blocking',Math.max(0,entry.duration-50),entry.startTime+entry.duration);});observer.observe({type:'longtask'});longTaskSupported=true;}}catch{longTaskSupported=false;}
  interval=env.setInterval(update,500);update();
 }
 function stop(){if(!enabled)return;enabled=false;onCapture(false);map.setDiagnosticsEnabled?.(false);env.clearInterval(interval);interval=null;unsubscribe?.();unsubscribe=null;observer?.disconnect();observer=null;
  if(renderSession)renderSession.active=false;renderSession=null;if(map.painter?.render===wrapped)map.painter.render=original;wrapped=null;original=null;try{timer?.destroy();}catch{}timer=null;lastFrame=null;
 }
 function reset(){const was=enabled;stop();clear();if(was)start();}
 function destroy(){if(dead)return;stop();dead=true;for(const [n,f]of listeners)map.off(n,f);env.document?.removeEventListener?.('visibilitychange',visibility);clear();}
 let wanted=false;
 function setEnabled(value){wanted=!!value;wanted?start():stop();}
 function visibility(){stop();clear();if(wanted&&!env.document.hidden)start();else if(wanted)onUpdate({paused:true});}
 function lost(){stop();clear();onUpdate(snapshot());}
 function restored(){if(wanted)start();}
 const listeners=[['performance',performanceSample],['render',frame],['movestart',boundary],['moveend',boundary],['webglcontextlost',lost],['webglcontextrestored',restored],['remove',destroy]];for(const [n,f]of listeners)map.on(n,f);
 env.document?.addEventListener?.('visibilitychange',visibility);
 return{setEnabled,reset,snapshot,destroy};
}
function mount(map,options={}){
 const doc=root.document,toggle=doc?.getElementById('performance-diagnostics'),panel=doc?.getElementById('performance-panel');if(!toggle||!panel)return null;
 const text=(id,value)=>{const el=doc.getElementById(id);if(el&&el.textContent!==value)el.textContent=value;},ms=v=>v===null?'—':v.toFixed(1)+' ms';
 function draw(m){if(m.paused){text('performance-pan','Paused while this tab is hidden');return;}
  text('performance-pan',m.diagnosis.pan);text('performance-loading',m.diagnosis.loading);text('performance-advice',m.diagnosis.advice);
  text('performance-frames',m.frames.samples?Math.round(m.frames.fps)+' fps · '+ms(m.frames.p95Ms)+' p95':'Pan to measure');
  text('performance-cpu',m.render.samples?ms(m.render.p95Ms)+' p95':'Collecting');
  text('performance-gpu',m.gpu.samples?ms(m.gpu.p95Ms)+' p95':m.gpu.status);
  text('performance-longtasks',m.longTasks.supported?Math.round(m.longTasks.blockingMs)+' ms blocking':'Unavailable');
  text('performance-jobs',m.imageQueue?m.imageQueue.active+' active / '+m.imageQueue.limit+' limit · '+m.imageQueue.queued+' queued':'Unavailable');
  const g=m.terrain;text('performance-terrain',g?.supported?(g.enabled?`Terrain grids: ${g.grids[32]} coarse / ${g.grids[64]} medium / ${g.grids[128]} full · ${g.nativeTriangles?Math.round((1-g.triangles/g.nativeTriangles)*100):0}% fewer triangles`:'Adaptive terrain detail off'):'');
  text('performance-stages','Delivery '+ms(m.stages.delivery.p95Ms)+' · decode '+ms(m.stages.decode.p95Ms)+' · preparation '+ms(m.stages.transform.p95Ms));
  text('performance-backlog',m.pending+' tile requests · '+m.regional.compositesQueued+' image preparations · '+m.refreshBacklog+' terrain refreshes');
 }
 const control=attach(map,{...options,onUpdate:draw});
 function change(){panel.hidden=!toggle.checked;control.setEnabled(toggle.checked);if(toggle.checked){doc.getElementById('layers-panel').hidden=true;doc.getElementById('layers-toggle').setAttribute('aria-expanded','false');doc.getElementById('performance-close').focus();}}
 function close(){toggle.checked=false;change();doc.getElementById('layers-toggle').focus();}
 function download(){const data={build:root.RidgeBuildInfo?.fingerprint||null,...control.snapshot()},url=root.URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json'})),a=doc.createElement('a');a.href=url;a.download='ridgeline-performance.json';a.click();root.setTimeout(()=>root.URL.revokeObjectURL(url),1000);}
 toggle.addEventListener('change',change);const closeButton=doc.getElementById('performance-close'),resetButton=doc.getElementById('performance-reset'),saveButton=doc.getElementById('performance-save');
 closeButton.addEventListener('click',close);resetButton.addEventListener('click',control.reset);saveButton.addEventListener('click',download);change();
 return{destroy(){control.destroy();panel.hidden=true;toggle.removeEventListener('change',change);closeButton.removeEventListener('click',close);resetButton.removeEventListener('click',control.reset);saveButton.removeEventListener('click',download);}};
}
const api={classify,percentile,gpuTimer,attach,mount};if(typeof module!=='undefined')module.exports=api;else root.RidgeBottleneckDiagnostics=api;
})(globalThis);
