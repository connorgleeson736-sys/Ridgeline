import * as Terrain from './terrain-primitives.mjs';
import {MapControls} from './terrain-primitives.mjs';
import {TerrainGPU,Texture,PatchGeometryCache,elevationTexture,normalTexture,gpuTerrainMaterial,pickHeightfield} from './luma-terrain.mjs?v=3';
import {JobQueue,limits,detailPolicy,expandedView,memoryPlan,pendingOrder} from './terrain-scheduler.mjs?v=2';

const P=globalThis.RidgePatches;
const C=40075016.68557849,R=Math.PI/180,clamp=(v,a,b)=>Math.max(a,Math.min(b,v));
export const mercator=p=>[(p[0]+180)/360*C,(1-Math.asinh(Math.tan(clamp(p[1],-85,85)*R))/Math.PI)/2*C];
export const geographic=p=>[p[0]/C*360-180,Math.atan(Math.sinh(Math.PI*(1-2*p[1]/C)))/R];
export function outputRatio(width,height,dpr,lowPower=false){return Math.max(.5,Math.min(dpr||1,lowPower?1:1.25,Math.sqrt((lowPower?900000:1500000)/Math.max(1,width*height))));}
export const tileID=(z,x,y)=>`${z}/${x}/${y}`;
const cancelled=()=>Object.assign(Error('luma.gl startup cancelled'),{name:'AbortError'});

function graphicsReady(promise,signal,timeout){return new Promise((resolve,reject)=>{
 let done=false;const finish=(fn,value)=>{if(done)return;done=true;clearTimeout(timer);signal?.removeEventListener('abort',abort);fn(value);},
  abort=()=>finish(reject,cancelled()),timer=setTimeout(()=>finish(reject,Error('luma.gl graphics initialisation timed out')),timeout);
 signal?.addEventListener('abort',abort,{once:true});promise.then(value=>finish(resolve,value),error=>finish(reject,error));if(signal?.aborted)abort();
});}

class TerrainWorker {
 constructor(key,wake,onFatal=()=>{}){this.pending=new Map();this.next=0;this.stats={};this.worker=new Worker(new URL('./terrain-view-worker.js?v=1',import.meta.url));this.ready=new Promise((resolve,reject)=>{
  this.timer=setTimeout(()=>reject(Error('Terrain worker did not initialise')),10000);
  this.worker.onmessage=({data:d})=>{if(d.type==='ready'){clearTimeout(this.timer);this.initialised=true;resolve();return;}const p=this.pending.get(d.id);if(!p)return;clearTimeout(p.timer);this.pending.delete(d.id);this.stats=d.stats||this.stats;d.type==='error'?p.reject(Object.assign(Error(d.message),{name:d.name})):p.resolve(d.mesh);wake();};
  this.worker.onerror=()=>{reject(Error('Terrain worker could not load'));this.fail(Error('Terrain processing stopped'));if(this.initialised)onFatal(Error('Terrain processing stopped'));};
 });this.worker.postMessage({type:'init',key});}
 tile(node){const id=++this.next;let cancel;const promise=new Promise((resolve,reject)=>{const timer=setTimeout(()=>{this.worker.postMessage({type:'cancel',id});this.pending.delete(id);reject(Error('Elevation tile timed out'));},30000);this.pending.set(id,{resolve,reject,timer});cancel=()=>{const p=this.pending.get(id);if(!p)return;clearTimeout(p.timer);this.pending.delete(id);this.worker.postMessage({type:'cancel',id});reject(cancelled());};this.worker.postMessage({type:'tile',id,z:node.z,x:node.x,y:node.y,error:node.error});});return{promise,cancel};}
 fail(error){clearTimeout(this.timer);for(const p of this.pending.values()){clearTimeout(p.timer);p.reject(error);}this.pending.clear();}
 configure(options){this.worker.postMessage({type:'configure',options});}
 destroy(){this.fail(cancelled());this.worker.terminate();}
}

export async function createLumaRenderer(options){
 const {TerrainGPU:GPU=TerrainGPU,Texture:ImageTexture=Texture,elevationTexture:heightTexture=elevationTexture,normalTexture:normalsTexture=normalTexture}=options.graphicsBackend||{};
 const direct=!!options.graphicsBackend,label=direct?'Direct WebGL':'luma.gl';
 const {container,key,peaks=[],selected=0,onPeak=()=>{},onProgress=()=>{},onError=()=>{},signal}=options;
 let dead=false,raf=0,frame=0,settle=2,moving=false,lastMove=0,lastSelect=0,lastStats=0,lastFrame=0,frameMS=16.7,slow=0,ratio=1,visible=[],routePoints=[],labelVisible=true,selectedPeak=selected,animation=null,geometryBytes=0,sourceErrorAt=0;
 let resolveFirst,rejectFirst;const first=new Promise((a,b)=>{resolveFirst=a;rejectFirst=b;});first.catch(()=>{});
 const settings={basemap:options.basemap||'linz',is3d:options.is3d!==false,exaggeration:options.exaggeration||1,lowPower:!!options.lowPower,natural:options.natural!==false,shading:options.shading!==false,phoneMode:!!options.phoneMode,phoneDetail:options.phoneDetail??50,imageryDetail:options.detail?.imagery??.25,terrainDetail:options.detail?.terrain??.25,cacheMiB:options.detail?.cacheMiB??352,prepareAhead:!!options.prepareAhead,expandedAngle:options.expandedAngle??30,loading:options.loading||{}};
 const nodes=new Map(),uploadQueue=[],events=new Map(),listeners=[],roots=[],geometryCache=new PatchGeometryCache();
 let fatal=false,compiled=false,compiling=false;
 let sharedMaterial=null,triangleBudgetScale=1,lastLabels=0,routeDirty=false,lastRouteUpdate=0,lastTickMs=0,elevationReuses=0;
 let active=0,worker,gpu,controls,resizeObserver,backend='initialising',ready=false;
 let selectionDirty=true,foreground=[],background=[],backgroundIDs=new Set(),backgroundTimer=0,retryTimer=0,lastPrediction=0,previousPrediction=null,diagnosticEnabled=false,pressure=false,pressureCount=0,healthyCount=0,phoneRatio=Infinity,phoneGeometry=1,downshifts=0,committed=false,lastPressure=0,gpuTiming='Unavailable';
 const backgroundControllers=new Set(),preparation={prepared:0,predicted:0,reason:'Preparation off'},metrics={imageHits:0,uploads:0,cancelled:0};
 const getCache=()=>globalThis.RidgeTiles&&globalThis.maplibregl?RidgeTiles.install(maplibregl):null;
 const imageJobs=new JobQueue(limits(settings.loading).images,{reserve:2}),terrainJobs=new JobQueue(4,{reserve:0});
 const clock=()=>performance.now(),memory=()=>memoryPlan(settings.cacheMiB,settings.lowPower),loading=()=>limits(settings.loading,moving);
 function sample(stage,ms){if(diagnosticEnabled)emit('performance',{stage,ms});}
 const scene=new Terrain.Scene();scene.background=new Terrain.Color(globalThis.RidgeSky?.current()||'#becbd0');
 const group=new Terrain.Group();scene.add(group);
 const camera=new Terrain.PerspectiveCamera(45,1,.5,20000000);camera.up.set(0,1,0);
 const view=options.view||{center:[170.141,-43.595],zoom:11.9,bearing:-24,pitch:58};let origin=mercator(view.center),cos=Math.cos(view.center[1]*R);
 const ray=new Terrain.Raycaster(),box=new Terrain.Box3(),v=new Terrain.Vector3();
 const canvas=document.createElement('canvas');canvas.className='terrain-canvas';canvas.setAttribute('aria-label',`Interactive ${label} terrain. Drag to pan; right-drag to orbit; scroll to zoom.`);canvas.tabIndex=0;container.append(canvas);
 const labels=document.createElement('div');labels.className='terrain-peak-labels';container.append(labels);
 const labelNodes=peaks.map((p,i)=>{const b=document.createElement('button');b.className='peak-marker';b.textContent=`${p.short} · ${p.height.toLocaleString()} m`;b.onclick=e=>{e.stopPropagation();onPeak(i);};labels.append(b);return b;});
 const tools=document.createElement('div');tools.className='terrain-map-controls';container.append(tools);
 const status=document.createElement('div');status.className='terrain-status';status.setAttribute('role','status');container.append(status);
 const credits=document.createElement('div');credits.className='terrain-credits';credits.innerHTML='<a href="https://www.linz.govt.nz/data/linz-data/linz-basemaps/data-attribution" target="_blank" rel="noopener">LINZ elevation & imagery · CC BY 4.0 · contributors</a>';container.append(credits);
 const emit=(name,event={})=>{for(const fn of events.get(name)||[])fn(event);};
 const listen=(target,name,fn,opts)=>{target.addEventListener(name,fn,opts);listeners.push(()=>target.removeEventListener(name,fn,opts));};
 const graphicsError=error=>Object.assign(error instanceof Error?error:Error(String(error)),{ridgeGraphics:true,ridgeBackend:gpu?.backend?.isWebGPUBackend?'WebGPU':'WebGL 2'});
 function fail(error){if(dead||fatal)return;fatal=true;rejectFirst(error);if(ready){options.onFatal?.(error);onError(Error('The luma.gl viewer stopped. Restoring Original.'));}}
 function wake(frames=2){if(dead||fatal)return;settle=Math.max(settle,frames);if(!raf&&!document.hidden)raf=requestAnimationFrame(tick);}
 function reset(){wake(2);}
 function dimensions(){return{width:Math.max(1,container.clientWidth||innerWidth),height:Math.max(1,container.clientHeight||innerHeight)};}
 function resize(){if(!gpu||dead)return;const {width,height}=dimensions();ratio=Math.min(settings.phoneMode?phoneRatio:Infinity,pressure?.8:Infinity,outputRatio(width,height,devicePixelRatio,settings.lowPower));gpu.setPixelRatio(ratio);gpu.setSize(width,height,false);camera.aspect=width/height;camera.updateProjectionMatrix();reset();emit('resize');}
 function point(p,h=0){const m=mercator(p);return new Terrain.Vector3((m[0]-origin[0])*cos,h*cos/Math.cos(p[1]*R)*(settings.is3d?settings.exaggeration:0),(m[1]-origin[1])*cos);}
 function lngLat(p){return geographic([p.x/cos+origin[0],p.z/cos+origin[1]]);}
 function currentView(){const centre=lngLat(controls.target),delta=camera.position.clone().sub(controls.target),distance=delta.length(),height=dimensions().height,mpp=distance*2*Math.tan(camera.fov*R/2)/height;return{center:centre,zoom:clamp(Math.log2(cos*C/(512*mpp)),4,19),pitch:Math.acos(clamp(delta.y/distance,-1,1))/R,bearing:Math.atan2(-delta.x,delta.z)/R,elevation:ground(centre),heightReference:'nzvd2016'};}
 function applyView(next,cut=true){const p=next.center||currentView().center,zoom=next.zoom??currentView().zoom,pitch=clamp(next.pitch??currentView().pitch,0,85)*R,bearing=(next.bearing??currentView().bearing)*R;
  const nearest=peaks.find(a=>Math.hypot(a.p[0]-p[0],a.p[1]-p[1])<.015),h=Number.isFinite(next.elevation)?next.elevation:ground(p)??nearest?.height??0;
  controls.target.copy(point(p,h));const distance=cos*C/(512*2**clamp(zoom,4,19))*dimensions().height/(2*Math.tan(camera.fov*R/2));
  camera.position.copy(controls.target).add(new Terrain.Vector3(-Math.sin(bearing)*Math.sin(pitch),Math.cos(pitch),Math.cos(bearing)*Math.sin(pitch)).multiplyScalar(distance));camera.lookAt(controls.target);camera.updateMatrixWorld();controls.update();lastSelect=0;selectionDirty=true;if(cut){cancelBackground();reset();}
 }
 function rebase(){if(Math.hypot(controls.target.x,controls.target.z)<30000)return;const old=cos,newOrigin=[origin[0]+controls.target.x/old,origin[1]+controls.target.z/old],newCos=Math.cos(geographic(newOrigin)[1]*R),target=controls.target.clone(),delta=camera.position.clone().sub(target);origin=newOrigin;cos=newCos;controls.target.set(0,target.y*cos/old,0);camera.position.copy(controls.target).add(delta.multiplyScalar(cos/old));for(const n of nodes.values())if(n.mesh)positionNode(n);updateRoute();reset();}
 function positionNode(n){n.mesh.position.set(((n.x+.5)/2**n.z*C-origin[0])*cos,0,((n.y+.5)/2**n.z*C-origin[1])*cos);n.mesh.scale.setScalar(cos);n.mesh.userData.ridge.geo.w=settings.is3d?settings.exaggeration:0;}
 function ground(p){const m=mercator(p);for(let z=19;z>=6;z--){const x=m[0]/C*2**z,y=m[1]/C*2**z,n=nodes.get(tileID(z,Math.floor(x),Math.floor(y)));if(!n?.data)continue;const size=n.data.size,k=size-1,u=(x-n.x)*k,w=(y-n.y)*k,a=clamp(Math.floor(u),0,k),b=clamp(Math.floor(w),0,k),c=Math.min(k,a+1),d=Math.min(k,b+1),fx=u-a,fy=w-b,h=n.data.heights;return h[b*size+a]*(1-fx)*(1-fy)+h[b*size+c]*fx*(1-fy)+h[d*size+a]*(1-fx)*fy+h[d*size+c]*fx*fy;}return null;}
 function node(z,x,y,parent=null){const id=tileID(z,x,y);let n=nodes.get(id);if(n)return n;const span=C/2**z,lat=geographic([(x+.5)*span,(y+.5)*span])[1];n={id,z,x,y,parent,span,vertical:1/Math.cos(lat*R),children:null,mesh:null,data:null,state:'new',used:0,wanted:0,retry:0,error:Math.max(.15,Math.min(25,span*Math.cos(lat*R)/600)),bytes:0,controller:null};nodes.set(id,n);return n;}
 function children(n){return n.children??=Array.from({length:4},(_,q)=>node(n.z+1,n.x*2+q%2,n.y*2+(q>>1),n));}
 function bounds(n){const low=n.data?.min??-500,high=n.data?.max??5000,ex=settings.is3d?settings.exaggeration:0;box.min.set((n.x*n.span-origin[0])*cos,Math.min(0,low*ex-1000), (n.y*n.span-origin[1])*cos);box.max.set(((n.x+1)*n.span-origin[0])*cos,high*ex*1.4+100,((n.y+1)*n.span-origin[1])*cos);return box;}
 function policy(n){return detailPolicy({distance:Math.max(1,bounds(n).distanceToPoint(camera.position)),range:camera.position.distanceTo(controls.target),phoneMode:settings.phoneMode,phoneDetail:settings.phoneDetail,imagery:settings.imageryDetail,terrain:settings.terrainDetail});}
 function plan(expanded=false){
  camera.updateMatrixWorld();let planningCamera=camera,height=dimensions().height;
  if(expanded){const extra=expandedView(camera.fov,camera.aspect,settings.expandedAngle);planningCamera=camera.clone();planningCamera.fov=extra.fov;planningCamera.updateProjectionMatrix();height*=extra.scale;}
  const planningFrustum=new Terrain.Frustum().setFromProjectionMatrix(new Terrain.Matrix4().multiplyMatrices(planningCamera.projectionMatrix,planningCamera.matrixWorldInverse),gpu.coordinateSystem),wanted=[],queue=[],splits=new Set(),budget=(settings.lowPower?28:48)*(expanded?3:1);let leaves=0;
  function consider(n){const b=bounds(n);if(!planningFrustum.intersectsBox(b))return false;const distance=Math.max(10,b.distanceToPoint(camera.position)),projected=n.span*cos/distance*height/(2*Math.tan(planningCamera.fov*R/2)),detail=policy(n);wanted.push(n);queue.push({n,projected,detail});return true;}
  roots.forEach(n=>{if(consider(n))leaves++;});
  while(queue.length&&leaves+3<=budget){queue.sort((a,b)=>b.projected*b.detail.imagery-a.projected*a.detail.imagery);const {n,projected,detail}=queue.shift();if(n.z>=19||projected*detail.imagery<=(settings.lowPower?700:560)*(!expanded&&n.split?.83:1))continue;splits.add(n.id);let added=0;children(n).forEach(c=>{if(consider(c))added++;});leaves+=Math.max(0,added-1);}
  return{wanted,splits};
 }
 function needs(n){return !n.mesh||n.imageBasemap!==settings.basemap||!!(n.data?._preview&&!n.split);}
 function visibleDemand(){return foreground.some(n=>needs(n));}
 function priority(n){let p=n.parent,covered=!!n.mesh&&n.imageBasemap===settings.basemap;while(p&&!covered){covered=!!p.mesh&&p.imageBasemap===settings.basemap;p=p.parent;}const distance=Math.max(1,bounds(n).distanceToPoint(camera.position)),focus=controls.target,centre=v.set(((n.x+.5)*n.span-origin[0])*cos,focus.y,((n.y+.5)*n.span-origin[1])*cos),focusDistance=centre.distanceTo(focus);return{gap:!covered,z:n.z,cached:!!getCache()?.ready?.(cacheURL(n)),priority:distance+focusDistance*.65};}
 function select(now){lastSelect=now;selectionDirty=false;const result=plan();foreground=result.wanted;const wantedIDs=new Set(foreground.map(n=>n.id));
  for(const n of foreground){n.split=result.splits.has(n.id);n.wantedAt=now;n.wanted=frame;}
  function cover(n){if(!n.mesh)return false;let mask=0;if(n.split&&n.children)for(let q=0;q<4;q++){const c=n.children[q];if(wantedIDs.has(c.id)&&cover(c))mask|=1<<q;}n.coverage=mask;n.mesh.visible=mask!==15;if(n.mesh.visible){patchLOD(n);visible.push(n);n.usedAt=now;}return true;}
  for(const n of visible)if(n.mesh)n.mesh.visible=false;visible=[];roots.forEach(cover);
  const triangles=visible.reduce((sum,n)=>sum+n.triangles,0),target=settings.lowPower?260000:moving?550000:850000;
  if(triangles>target){triangleBudgetScale=Math.min(8,triangleBudgetScale*1.25);if(settings.phoneMode)phoneGeometry=Math.max(phoneGeometry,triangleBudgetScale);for(const n of visible)patchLOD(n);}
  // Prioritize complete screen coverage, then cached/focal work. Never force a
  // cached foreground request behind speculative downloads.
  const offscreen=[...nodes.values()].filter(n=>n.state==='loading'&&!wantedIDs.has(n.id)&&!backgroundIDs.has(n.id));let images=0,dem=0;
  for(const n of offscreen.sort((a,b)=>(b.wantedAt||0)-(a.wantedAt||0))){if((!n.imageDone&&++images>loading().imageryPending)||(!n.terrainDone&&++dem>loading().demPending)){n.controller?.abort();metrics.cancelled++;}}
  pumpForeground();trim(now);scheduleBackground();
 }
 function pumpForeground(){if(dead||fatal)return;const limit=loading();imageJobs.setLimit(limit.images);terrainJobs.setLimit(Math.max(1,Math.min(limit.images,8,Math.max(2,(globalThis.navigator?.hardwareConcurrency||4)-1))));
  const queued=uploadQueue.reduce((sum,i)=>sum+i.data.heights.byteLength+i.data.normals.byteLength+i.bitmap.width*i.bitmap.height*4,0);
  if(queued>=memory().queued)return;
  if(visibleDemand()){for(const c of backgroundControllers)c.abort();for(const n of nodes.values())if(n.background&&n.state==='loading'&&!foreground.includes(n))n.controller?.abort();}
  const pending=foreground.filter(n=>needs(n)&&n.state!=='loading'&&n.state!=='upload'&&Date.now()>n.retry).map(n=>({n,...priority(n)})).sort(pendingOrder);
  const capacity=Math.min(limit.images+terrainJobs.limit+2,Math.max(2,Math.floor(memory().queued/(257*257*8+256*256*4))));
  let slots=[...nodes.values()].filter(q=>q.state==='loading'&&(!q.imageDone||!q.mesh)).length+uploadQueue.length;
  for(const {n,cached}of pending){if(slots>=capacity&&!(cached&&slots<capacity+Math.min(2,limit.images)))continue;load(n,false);slots++;}
  clearTimeout(retryTimer);const retry=foreground.filter(n=>needs(n)&&n.state!=='loading'&&n.state!=='upload'&&n.retry>Date.now()).map(n=>n.retry);if(retry.length)retryTimer=setTimeout(()=>{retryTimer=0;selectionDirty=true;wake();},Math.max(1,Math.min(...retry)-Date.now()));
 }
 function cancelBackground(){clearTimeout(backgroundTimer);backgroundTimer=0;background=[];backgroundIDs.clear();for(const c of backgroundControllers)c.abort();backgroundControllers.clear();for(const n of nodes.values())if(n.background&&n.state==='loading')n.controller?.abort();preparation.reason=moving?'Moving':'Visible view first';}
 function angularPriority(n){const q=v.set(((n.x+.5)*n.span-origin[0])*cos,controls.target.y,((n.y+.5)*n.span-origin[1])*cos).project(camera),radius=n.span*cos/Math.max(1,bounds(n).distanceToPoint(camera.position))*dimensions().height/(2*Math.tan(camera.fov*R/2));return Math.hypot(Math.max(0,(Math.abs(q.x)-1)*dimensions().width/2-radius*.5),Math.max(0,(Math.abs(q.y)-1)*dimensions().height/2-radius*.5));}
 function scheduleBackground(){clearTimeout(backgroundTimer);backgroundTimer=0;if(dead||fatal||document.hidden)return;
  if(!settings.prepareAhead){preparation.reason='Preparation off';return;}
  if(moving||settings.lowPower||globalThis.navigator?.connection?.saveData||pressure||visibleDemand()||active||uploadQueue.length){preparation.reason=pressure?'Memory pressure':moving?'Moving':'Visible view first';return;}
  preparation.reason='Waiting for visible idle';backgroundTimer=setTimeout(prepareBackground,450);
 }
 function prepareBackground(){backgroundTimer=0;if(dead||fatal||moving||document.hidden||visibleDemand()||active||uploadQueue.length||!settings.prepareAhead||settings.lowPower||pressure||globalThis.navigator?.connection?.saveData)return;
  const current=new Set(foreground.map(n=>n.id));background=plan(true).wanted.filter(n=>!current.has(n.id)&&needs(n)&&n.state==='new'&&Date.now()>n.retry).map(n=>({n,angle:angularPriority(n)})).sort((a,b)=>a.angle-b.angle||a.n.z-b.n.z).slice(0,128).map(q=>q.n);backgroundIDs=new Set(background.map(n=>n.id));
  if(!background.length){preparation.reason='Wider view ready';return;}
  if(geometryBytes>=memory().resident*.85||getCache()?.canPrefetch?.()===false){preparation.reason='Cache budget reached';return;}
  preparation.reason='Preparing wider view';for(const n of background.slice(0,Math.min(2,loading().images)))load(n,true);
 }
 function predict(now){if(now-lastPrediction<350)return;lastPrediction=now;const current=currentView(),p=mercator(current.center),next={x:p[0]/C,y:p[1]/C},previous=previousPrediction;previousPrediction={...next,zoom:current.zoom};
  const cache=getCache(),limit=loading().predictive;if(!previous||!moving||!limit||visibleDemand()||settings.lowPower||pressure||globalThis.navigator?.connection?.saveData||backgroundControllers.size>=limit||cache?.canPrefetch?.()===false)return;
  const ids=globalThis.RidgeImageryPreload?.plan({hit:next,previous,zoom:current.zoom,previousZoom:previous.zoom,baseZoom:Math.min(18,Math.floor(current.zoom)),elapsed:350})||[];
  for(const id of ids){if(backgroundControllers.size>=limit)break;const url=cacheURL(id);if(!cache||cache.has(url))continue;const controller=new AbortController();backgroundControllers.add(controller);void imageJobs.run(()=>cache.prefetch(url,controller.signal),{signal:controller.signal,background:true,priority:()=>100000}).then(()=>{preparation.predicted++;},()=>{}).finally(()=>backgroundControllers.delete(controller));}
 }
 function imageURL(n){if(settings.basemap==='linz')return`https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/${n.z}/${n.x}/${n.y}.webp?api=${encodeURIComponent(workerKey)}`;const z=Math.min(n.z,settings.basemap==='topo'?17:18),f=2**(n.z-z),x=Math.floor(n.x/f),y=Math.floor(n.y/f);return`https://services.arcgisonline.com/ArcGIS/rest/services/${settings.basemap==='topo'?'World_Topo_Map':'World_Imagery'}/MapServer/tile/${z}/${y}/${x}`;}
 function cacheURL(n){const url=imageURL(n);if(settings.basemap==='linz')return url.replace('https://basemaps.linz.govt.nz','ridge://linz');const z=Math.min(n.z,settings.basemap==='topo'?17:18),f=2**(n.z-z);return`ridge://esri/${settings.basemap==='topo'?'topo':'imagery'}/${z}/${Math.floor(n.y/f)}/${Math.floor(n.x/f)}`;}
 let workerKey=key;
 // A decoded photograph can use measured ancestor elevations while its own DEM
 // is pending. Never treat this temporary surface as a completed leaf DEM.
 function previewTerrain(n){let ancestor=n.parent;while(ancestor&&!ancestor.data)ancestor=ancestor.parent;if(!ancestor)return null;const source=ancestor.data,factor=2**(n.z-ancestor.z),ox=n.x-ancestor.x*factor,oy=n.y-ancestor.y*factor,heights=new Float32Array(17*17);for(let y=0;y<17;y++)for(let x=0;x<17;x++)heights[y*17+x]=P.sample(source.heights,source.size,(ox+x/16)/factor,(oy+y/16)/factor);return Object.assign(P.prepare(heights,{x:n.x,y:n.y,z:n.z,size:17}),{_preview:true});}
 async function load(n,backgroundJob=false){const loadBasemap=settings.basemap,requestedURL=imageURL(n),cachedURL=cacheURL(n),generation=(n.requestGeneration||0)+1;n.requestGeneration=generation;active++;n.background=backgroundJob;n.state='loading';n.imageDone=false;n.terrainDone=false;const controller=new AbortController();n.controller=controller;let bitmap,data,keepBitmap=false;
  const dynamicPriority=()=>foreground.includes(n)?priority(n).priority:100000+angularPriority(n),cache=getCache(),cached=!!cache?.ready?.(cachedURL);if(cached)metrics.imageHits++;if(n.data)elevationReuses++;
  const timer=setTimeout(()=>controller.abort(),30000);try{
   const terrain=terrainJobs.run(async()=>{if(n.data&&!n.data._preview)return n.data;const preview=n.split&&previewTerrain(n);if(preview)return preview;const task=worker.tile(n),cancel=()=>task.cancel();controller.signal.addEventListener('abort',cancel,{once:true});try{const start=clock(),result=await task.promise;sample('transform',worker.stats.processingMs??clock()-start);return result;}finally{controller.signal.removeEventListener('abort',cancel);}},{signal:controller.signal,background:()=>!foreground.includes(n),priority:dynamicPriority}).then(value=>{data=value;if(n.requestGeneration===generation)n.terrainDone=true;return value;});
   void terrain.catch(()=>{});
   const admitted=clock();const image=n.bitmap&&n.imageBasemap===loadBasemap?(keepBitmap=true,n.imageDone=true,Promise.resolve(bitmap=n.bitmap)):imageJobs.run(async()=>{sample('admission',clock()-admitted);const start=clock();let blob;if(cache){const cached=await cache.load(cachedURL,controller.signal,direct?{original:true}:undefined);blob=new Blob([cached.data]);}else{const r=await fetch(requestedURL,{signal:controller.signal});if(!r.ok)throw Error('Imagery HTTP '+r.status);blob=await r.blob();}sample('delivery',clock()-start);const decoding=clock();const decoded=await createImageBitmap(blob,{imageOrientation:'none'});sample('decode',clock()-decoding);if(controller.signal.aborted||dead){decoded.close();throw cancelled();}bitmap=decoded;if(n.requestGeneration===generation)n.imageDone=true;return decoded;},{signal:controller.signal,cached,background:()=>!foreground.includes(n),priority:dynamicPriority});
   const enqueue=value=>{for(let i=uploadQueue.length-1;i>=0;i--)if(uploadQueue[i].n===n&&uploadQueue[i].generation===generation)uploadQueue.splice(i,1);uploadQueue.push({n,data:value,bitmap,basemap:loadBasemap,generation,background:backgroundJob});keepBitmap=true;wake();};
   await image;if(!dead&&!controller.signal.aborted&&n.requestGeneration===generation&&settings.basemap===loadBasemap&&!data){const preview=previewTerrain(n);if(preview)enqueue(preview);}
   await terrain;if(dead||controller.signal.aborted||n.requestGeneration!==generation||settings.basemap!==loadBasemap)return;
   n.state='upload';enqueue(data);
  }catch(e){const wasCancelled=controller.signal.aborted||e.name==='AbortError';controller.abort();if(n.requestGeneration===generation){n.state=n.mesh?'ready':'new';n.retry=wasCancelled?0:Date.now()+15000;}if(!backgroundJob&&n.requestGeneration===generation&&!dead&&e.name!=='AbortError'&&Date.now()-sourceErrorAt>12000){sourceErrorAt=Date.now();onError(Error('Some terrain tiles could not load. The existing terrain is retained.'));}}
  finally{clearTimeout(timer);if(!keepBitmap)bitmap?.close();if(n.controller===controller)n.controller=null;active--;if(!dead){selectionDirty=true;wake();}}
 }
 function patchLOD(n){const pixelError=(settings.lowPower?2:moving?1.5:.8)*Math.max(triangleBudgetScale,settings.phoneMode?phoneGeometry:1)/policy(n).terrain;n.cells??=[0,0,0,0];n.triangles=0;
  for(let q=0;q<4;q++){const mesh=n.mesh.children[q];mesh.visible=!((n.coverage||0)&(1<<q));if(!mesh.visible)continue;bounds(n);const x0=((n.x+(q%2)*.5)*n.span-origin[0])*cos,z0=((n.y+(q>>1)*.5)*n.span-origin[1])*cos;box.min.x=x0;box.min.z=z0;box.max.x=x0+n.span*cos*.5;box.max.z=z0+n.span*cos*.5;const metresPerPixel=Math.max(3,box.distanceToPoint(camera.position))*2*Math.tan(camera.fov*R/2)/dimensions().height,errors=n.quadErrors[q],cells=P.chooseCells(errors,metresPerPixel,pixelError,n.cells[q]);n.cells[q]=cells;mesh.geometry=geometryCache.get(cells);n.triangles+=mesh.geometry.index.count/3;const error=errors.find(e=>e.cells===cells).error;mesh.userData.ridge.sampling.z=Math.min(2000,Math.max(20,error*3,n.span*cos/cells*2));}
 }
 function updateAppearance(n){n.mesh.userData.ridge.appearance.set(settings.natural?1:0,settings.shading&&settings.is3d&&settings.basemap!=='topo'?1:0);}
 function upload(){uploadQueue.sort((a,b)=>Number(!foreground.includes(a.n))-Number(!foreground.includes(b.n))||priority(a.n).priority-priority(b.n).priority);const item=uploadQueue.shift();if(!item)return;try{if(item.background&&(moving||visibleDemand()&&!foreground.includes(item.n))){if(item.bitmap!==item.n.bitmap)item.bitmap.close();item.n.state=item.n.mesh?'ready':'new';return;}uploadItem(item);metrics.uploads++;if(item.background)preparation.prepared++;}catch(error){if(item.n.bitmap!==item.bitmap)item.bitmap.close();throw error;}}
 function uploadItem(item){const {n,data,bitmap,basemap,generation}=item;if(generation!==n.requestGeneration){if(bitmap!==n.bitmap)bitmap.close();return;}if(basemap!==settings.basemap){if(bitmap!==n.bitmap)bitmap.close();n.state=n.mesh?'ready':'new';return;}
  const reusePhoto=n.bitmap===bitmap,tex=reusePhoto?n.texture:new ImageTexture(bitmap);
  const imageUV=new Terrain.Vector4(1,1,0,0);if(settings.basemap!=='linz'){const cap=settings.basemap==='topo'?17:18,f=2**Math.max(0,n.z-cap);imageUV.set(1/f,1/f,(n.x%f)/f,1-((n.y%f)+1)/f);}
  if(n.mesh&&n.data===data){
   const oldBytes=n.bitmap.width*n.bitmap.height*4*(1+4/3),nextBytes=bitmap.width*bitmap.height*4*(1+4/3);if(!reusePhoto){n.texture.dispose();n.bitmap.close();}n.bitmap=bitmap;n.texture=tex;n.imageBasemap=basemap;n.state='ready';n.mesh.userData.ridge.photoTexture=tex;n.mesh.userData.ridge.imageUV.copy(imageUV);
   for(const quad of n.mesh.children){quad.userData.ridge.photoTexture=tex;quad.userData.ridge.imageUV.copy(imageUV);}n.bytes+=nextBytes-oldBytes;geometryBytes+=nextBytes-oldBytes;gpu.prepareNode(n);lastSelect=0;reset();return;
  }
  if(n.mesh){gpu.releaseNode(n);group.remove(n.mesh);n.heightTexture.dispose();n.normalTexture.dispose();if(!reusePhoto){n.texture.dispose();n.bitmap.close();}geometryBytes-=n.bytes;n.cells=null;}
  n.imageBasemap=basemap;n.bitmap=bitmap;n.texture=tex;n.data=data;n.quadErrors=Array.from({length:4},(_,q)=>data.errors.map(e=>({cells:e.cells,error:e.quadrants[q]})));n.heightTexture=heightTexture(data);n.normalTexture=normalsTexture(data);
  const uniforms={geo:new Terrain.Vector4(data.span,n.y/2**n.z,1/2**n.z,settings.is3d?settings.exaggeration:0),sampling:new Terrain.Vector4((data.size-1)/data.size,.5/data.size,20,0),imageUV,appearance:new Terrain.Vector2(),heightTexture:n.heightTexture,normalTexture:n.normalTexture,photoTexture:tex};
  uniforms.offset=new Terrain.Vector2();sharedMaterial??=gpuTerrainMaterial(uniforms);n.mesh=new Terrain.Group();n.mesh.visible=false;n.mesh.userData.ridge=uniforms;n.mesh.name=n.id;
  for(let q=0;q<4;q++){const mesh=new Terrain.Mesh(geometryCache.get(16),sharedMaterial);mesh.userData.ridge={...uniforms,sampling:uniforms.sampling.clone(),offset:new Terrain.Vector2(q%2*.5,(q>>1)*.5)};mesh.frustumCulled=false;n.mesh.add(mesh);}updateAppearance(n);n.state=data._preview&&n.controller?'loading':'ready';n.coverage=0;
  n.usedAt=clock();n.bytes=(data.heights.byteLength+data.normals.byteLength)*2+bitmap.width*bitmap.height*4*(1+4/3);geometryBytes+=n.bytes;group.add(n.mesh);positionNode(n);gpu.prepareNode(n);patchLOD(n);lastSelect=0;reset();if(routePoints.length)routeDirty=true;
 }
 function disposeNode(n){if(!n.mesh&&!n.texture&&!n.heightTexture&&!n.normalTexture&&!n.bitmap)return;n.requestGeneration=(n.requestGeneration||0)+1;n.controller?.abort();gpu?.releaseNode(n);if(n.mesh)group.remove(n.mesh);n.texture?.dispose();n.heightTexture?.dispose();n.normalTexture?.dispose();n.bitmap?.close();geometryBytes-=n.bytes;n.mesh=null;n.data=null;n.texture=null;n.bitmap=null;n.heightTexture=null;n.normalTexture=null;n.bytes=0;n.state='new';}
 function trim(now=clock()){const budget=memory().resident*(pressure?.7:1);const protectedNodes=new Set();for(const n of foreground){let p=n;while(p){protectedNodes.add(p.id);p=p.parent;}}const cold=[...nodes.values()].filter(n=>n.mesh&&!n.mesh.visible&&!protectedNodes.has(n.id)).sort((a,b)=>(a.usedAt||0)-(b.usedAt||0));if(geometryBytes>budget)for(const n of cold){disposeNode(n);if(geometryBytes<budget*.8)break;}
  // Prune empty leaves by elapsed time, including idle periods, rather than
  // requiring hundreds of rendered frames before releasing bookkeeping.
  if(nodes.size>2400)for(const n of nodes.values()){if(nodes.size<1800)break;if(n.children?.every(c=>!c.mesh&&!c.children&&c.state==='new'&&!protectedNodes.has(c.id)&&now-(c.wantedAt||0)>10000)){for(const c of n.children)nodes.delete(c.id);n.children=null;}}
 }
 function memoryPressure(now){if(now-lastPressure<1000)return;lastPressure=now;const byteStats=getCache()?.stats(),bytes=(byteStats?.imagery?.bytes||0)+(byteStats?.terrain?.bytes||0),queued=uploadQueue.reduce((sum,i)=>sum+i.bitmap.width*i.bitmap.height*4+i.data.heights.byteLength+i.data.normals.byteLength,0),workerBytes=(worker?.stats.cacheBytes||0)+(worker?.stats.preparedBytes||0),estimate=geometryBytes+bytes+queued+workerBytes+(byteStats?.regional?.inputBytes||0)+(byteStats?.regional?.queuedCompositionBytes||0)+dimensions().width*dimensions().height*ratio*ratio*(8),over=estimate>settings.cacheMiB*1048576*1.25||queued>memory().queued;
  pressureCount=over?pressureCount+1:0;healthyCount=over?0:healthyCount+1;const next=pressureCount>=2?true:healthyCount>=8?false:pressure;if(next!==pressure){pressure=next;if(committed)getCache()?.setPressure?.(pressure);if(pressure)cancelBackground();resize();configureWorker();}trim(now);
 }
 function configureWorker(){const budget=memory();worker?.configure({maxBytes:budget.worker*(pressure?.65:1),preparedBytes:budget.prepared*(pressure?.65:1),concurrency:Math.max(2,Math.min(8,loading().images))});}
 function configure(next={}){const wasPhone=settings.phoneMode;Object.assign(settings,next);if(next.loading)settings.loading={...next.loading};if(wasPhone!==settings.phoneMode){phoneRatio=Infinity;phoneGeometry=1;triangleBudgetScale=1;}cancelBackground();getCache()?.setBudget?.(settings.cacheMiB);configureWorker();selectionDirty=true;resize();pumpForeground();wake();}

 function updateRoute(){wake();}
 function labelUpdate(){if(!labelVisible){for(const b of labelNodes)b.hidden=true;return;}const zoom=currentView().zoom;const cameraDir=camera.getWorldDirection(new Terrain.Vector3()),projected=peaks.map(p=>{const q=point(p.p,p.height+12),ahead=q.clone().sub(camera.position).dot(cameraDir)>0;q.project(camera);return{x:(q.x+1)*container.clientWidth/2,y:(1-q.y)*container.clientHeight/2,hidden:!ahead||Math.abs(q.x)>1.2||Math.abs(q.y)>1.2||q.z>1};}),shown=globalThis.RidgeAlpineLabels?.visible(peaks,selectedPeak,zoom,(p,i)=>projected[i],container.clientWidth,container.clientHeight);
 peaks.forEach((p,i)=>{const b=labelNodes[i],q=projected[i];b.hidden=!labelVisible||q.hidden||(shown?!shown.has(i):zoom<(p.minZoom??8));b.classList.toggle('selected',i===selectedPeak);if(!b.hidden)b.style.transform=`translate(${q.x}px,${q.y}px) translate(-50%,-100%)`;});}

 function render(){gpu.render(scene,camera);}
 function tick(now){raf=0;if(dead||fatal||document.hidden)return;try{drawFrame(now);}catch(error){fail(graphicsError(error));}}
 function drawFrame(now){const tickStart=performance.now();frame++;const dt=lastFrame&&now-lastFrame<250?now-lastFrame:16.7;lastFrame=now;
  if(animation){const t=clamp((now-animation.start)/animation.duration,0,1),a=t*t*(3-2*t),view={};for(const k of ['zoom','pitch','bearing','elevation'])view[k]=animation.from[k]+(animation.to[k]-animation.from[k])*a;view.center=animation.from.center.map((x,i)=>x+(animation.to.center[i]-x)*a);applyView(view,false);if(t===1){animation=null;emit('moveend');}else moving=true;}
  if(controls)controls.update();if(moving&&now-lastMove>220&&!animation){moving=false;triangleBudgetScale=settings.phoneMode?phoneGeometry:1;emit('moveend');settle=1;lastSelect=0;}
  const targetLL=lngLat(controls.target),limited=[clamp(targetLL[0],165,179.99),clamp(targetLL[1],-49,-33)];if(targetLL.some((x,i)=>Math.abs(x-limited[i])>1e-8)){const p=point(limited),dx=p.x-controls.target.x,dz=p.z-controls.target.z;controls.target.x+=dx;controls.target.z+=dz;camera.position.x+=dx;camera.position.z+=dz;reset();}
  rebase();if(settings.is3d&&currentView().zoom>11){const h=ground(lngLat(camera.position));if(h!==null)camera.position.y=Math.max(camera.position.y,point(lngLat(camera.position),h).y+2);}
  if(settings.phoneMode&&moving&&slow>24&&ratio>.6){const next=Math.max(.6,ratio*.85);if(next<phoneRatio-.05){phoneRatio=next;downshifts++;slow=0;resize();}}
  camera.lookAt(controls.target);camera.near=Math.max(.15,Math.min(30,camera.position.distanceTo(controls.target)/10000));camera.far=Math.max(20000,camera.position.distanceTo(controls.target)*35);camera.updateProjectionMatrix();
  if(uploadQueue.length&&(!direct||!moving||lastTickMs<12||now-(metrics.lastUploadAt||0)>80)){upload();metrics.lastUploadAt=now;}if(!lastSelect||selectionDirty&&now-lastSelect>=(moving?100:32)||(moving&&now-lastSelect>160))select(now);else if(!direct)pumpForeground();predict(now);memoryPressure(now);
  if(routeDirty&&now-lastRouteUpdate>=120){routeDirty=false;lastRouteUpdate=now;updateRoute();}
  const renderStart=clock();render();sample('render',clock()-renderStart);
  const cost=performance.now()-tickStart;lastTickMs=cost;frameMS=frameMS*.9+Math.min(200,dt)*.1;if(moving){if(cost>22||dt>32)slow=Math.min(40,slow+1);else slow=Math.max(0,slow-1);}if(now-lastLabels>100||!moving){lastLabels=now;labelUpdate();}emit('render');
  if(visible.length&&!ready){
   if(compiled){ready=true;resolveFirst();}
   else if(!compiling){compiling=true;Promise.resolve().then(()=>gpu.compileAsync(scene,camera)).then(()=>{if(dead||fatal)return;compiled=true;wake();},error=>fail(graphicsError(error)));}
  }
  if(now-lastStats>500){lastStats=now;status.textContent=`${backend} · GPU patches · Direct · ${Math.round(1000/frameMS)} fps${moving?'':' (last frames)'} · ${visible.reduce((s,n)=>s+n.triangles,0).toLocaleString()} tri · ${Math.round(geometryBytes/1048576)} MB cache est · ${active+uploadQueue.length} loading`;}
  if(moving||animation||uploadQueue.length||routeDirty||selectionDirty||settle-->0)wake(0);else scheduleBackground();
 }
 function pick(event){const rect=canvas.getBoundingClientRect(),p=new Terrain.Vector2((event.clientX-rect.left)/rect.width*2-1,1-(event.clientY-rect.top)/rect.height*2);ray.setFromCamera(p,camera);const hit=pickHeightfield(ray.ray,visible,origin,cos,settings.is3d?settings.exaggeration:0);return hit?lngLat(hit):null;}

 function remove(){if(dead)return;dead=true;clearTimeout(backgroundTimer);clearTimeout(retryTimer);for(const c of backgroundControllers)c.abort();imageJobs.destroy();terrainJobs.destroy();if(committed){getCache()?.setViewPolicy?.();getCache()?.setPressure?.(false);}cancelAnimationFrame(raf);resizeObserver?.disconnect();listeners.forEach(fn=>fn());worker?.destroy();controls?.dispose();for(const n of nodes.values()){n.controller?.abort();disposeNode(n);}uploadQueue.splice(0).forEach(i=>i.bitmap.close());sharedMaterial?.dispose();geometryCache.dispose();try{gpu?.dispose();}catch{}container.replaceChildren();emit('remove');events.clear();rejectFirst(cancelled());}
 const api={settings,configure,activate(){committed=true;getCache()?.setViewPolicy?.(value=>{const q=String(value).match(/WebMercatorQuad\/(\d+)\/(\d+)\/(\d+)/);if(!q)return 0;const n=nodes.get(tileID(+q[1],+q[2],+q[3]));return n&&foreground.includes(n)?Math.min(99,priority(n).priority/10000):1000;},()=>visibleDemand()||pressure);configure();},setDiagnosticsEnabled(value){diagnosticEnabled=!!value;gpuTiming='Unavailable';},isMoving:()=>moving,on(name,fn){if(!events.has(name))events.set(name,new Set());events.get(name).add(fn);return api;},off(name,fn){events.get(name)?.delete(fn);return api;},getCanvas:()=>canvas,getContainer:()=>container,getSource:()=>undefined,getZoom:()=>currentView().zoom,getPixelRatio:()=>ratio,setPixelRatio:value=>{ratio=Math.min(value,outputRatio(dimensions().width,dimensions().height,devicePixelRatio,settings.lowPower));gpu.setPixelRatio(ratio);reset();},captureView:currentView,
  getBounds(){const p=currentView().center,range=camera.position.distanceTo(controls.target),delta=range*2/111000;return{getWest:()=>p[0]-delta/Math.cos(p[1]*R),getEast:()=>p[0]+delta/Math.cos(p[1]*R),getSouth:()=>p[1]-delta,getNorth:()=>p[1]+delta};},
  jumpTo(next){animation=null;applyView(next);emit('moveend');return api;},flyTo(next){if(!next.duration)return api.jumpTo(next);const from=currentView(),to={...from,...next};to.elevation=ground(to.center)??peaks.find(p=>Math.hypot(p.p[0]-to.center[0],p.p[1]-to.center[1])<.015)?.height??from.elevation??0;from.elevation??=0;cancelBackground();animation={from,to,start:performance.now(),duration:next.duration};moving=true;lastMove=performance.now();emit('movestart');wake();return api;},easeTo(next){return api.flyTo(next);},
  fitBounds(bounds,opts={}){const a=Array.isArray(bounds)?bounds[0]:[bounds.getWest(),bounds.getSouth()],b=Array.isArray(bounds)?bounds[1]:[bounds.getEast(),bounds.getNorth()],centre=[(a[0]+b[0])/2,(a[1]+b[1])/2],m0=mercator(a),m1=mercator(b),{width,height}=dimensions(),padding=typeof opts.padding==='number'?opts.padding:Math.max(...Object.values(opts.padding||{a:60})),mpp=Math.max(Math.abs(m1[0]-m0[0])/Math.max(100,width-padding*2),Math.abs(m1[1]-m0[1])/Math.max(100,height-padding*2));return api.flyTo({center:centre,zoom:Math.min(opts.maxZoom||19,Math.log2(C/(512*mpp))),pitch:0,duration:opts.duration});},
  setSkyColour(value){if(/^#[0-9a-f]{6}$/i.test(value)){scene.background.set(value);reset();}},setRoute(points){routePoints=points.map(p=>p.slice());updateRoute();},setLabels(show,i=selectedPeak){labelVisible=show;selectedPeak=i;wake();},setNatural(value){settings.natural=value;for(const n of nodes.values())if(n.mesh)updateAppearance(n);reset();},setShading(value){settings.shading=value;for(const n of nodes.values())if(n.mesh)updateAppearance(n);reset();},
  setTerrain(value){settings.is3d=!!value;if(value)settings.exaggeration=value.exaggeration||1;for(const n of nodes.values())if(n.mesh){positionNode(n);updateAppearance(n);}updateRoute();applyView(currentView());reset();},setLowPower(value){settings.lowPower=value;configure();},
  async setKey(next){
   const replacement=new TerrainWorker(next,()=>wake(),e=>{if(ready)options.onFatal?.(e);});let checkImage;try{await replacement.ready;const p=mercator(currentView().center),z=11,x=Math.floor(p[0]/C*2**z),y=Math.floor(p[1]/C*2**z);await replacement.tile({x,y,z,error:2}).promise;const r=await fetch(`https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/${z}/${x}/${y}.webp?api=${encodeURIComponent(next)}`,{signal:AbortSignal.timeout(15000)});if(!r.ok)throw Error('The LINZ key could not load imagery');checkImage=await createImageBitmap(await r.blob());if(dead)throw cancelled();
    cancelBackground();for(const n of nodes.values()){n.requestGeneration=(n.requestGeneration||0)+1;n.controller?.abort();}worker.destroy();worker=replacement;workerKey=next;configureWorker();for(const n of nodes.values()){disposeNode(n);n.state='new';n.retry=0;}uploadQueue.splice(0).forEach(i=>{if(i.bitmap!==i.n.bitmap)i.bitmap.close();i.n.state='new';});lastSelect=0;reset();
   }catch(e){replacement.destroy();throw e;}finally{checkImage?.close();}
  },
  setBasemap(value){if(settings.basemap===value)return;cancelBackground();settings.basemap=value;for(const n of nodes.values()){n.requestGeneration=(n.requestGeneration||0)+1;n.controller?.abort();n.state=n.mesh?'ready':'new';}uploadQueue.splice(0).forEach(i=>{if(i.bitmap!==i.n.bitmap)i.bitmap.close();i.n.state=i.n.mesh?'ready':'new';});credits.innerHTML=value==='linz'?'<a href="https://www.linz.govt.nz/data/linz-data/linz-basemaps/data-attribution">LINZ elevation & imagery · CC BY 4.0 · contributors</a>':'LINZ elevation · Esri basemap & contributors';lastSelect=0;reset();},resize,remove,doubleClickZoom:{enable(){},disable(){}},
  diagnostics:()=>({foregroundMissing:foreground.filter(needs).length,gpuTiming,preparationStage:'Terrain fetch, decode and patch preparation',imageQueue:imageJobs.stats(),terrainQueue:terrainJobs.stats(),pending:active,phoneMode:settings.phoneMode,phoneDetail:settings.phoneDetail,phoneResolutionCeiling:Number.isFinite(phoneRatio)?phoneRatio:null,adaptiveDownshifts:downshifts,memoryPressure:pressure,cacheBudgetMiB:settings.cacheMiB,loadingLimits:loading(),preparation:{...preparation,enabled:settings.prepareAhead,active:[...nodes.values()].filter(n=>n.background&&n.state==='loading').length},...metrics,backend,outputRatio:ratio,visibleTiles:visible.length,triangles:visible.reduce((s,n)=>s+n.triangles,0),tileBytes:geometryBytes,sharedGeometryBytes:geometryCache.bytes,terrainMaterials:sharedMaterial?1:0,terrainDrawCalls:visible.reduce((sum,n)=>sum+n.mesh.children.filter(c=>c.visible).length,0),terrainMode:direct?'Direct WebGL imagery on terrain':'luma.gl WebGPU displacement',activeTiles:active,elevationReuses,lastTickMs,uploadQueued:uploadQueue.length,uploadQueuedBytes:uploadQueue.reduce((sum,item)=>sum+item.data.heights.byteLength+item.data.normals.byteLength+item.bitmap.width*item.bitmap.height*4,0),source:worker?.stats})};
 try{
  if(signal?.aborted)throw cancelled();signal?.addEventListener('abort',remove,{once:true});listeners.push(()=>signal?.removeEventListener('abort',remove));
  onProgress('Initialising '+label+' graphics');gpu=new GPU({canvas});
  const initialising=Promise.resolve().then(()=>gpu.init());initialising.then(()=>{if(dead)try{gpu.dispose();}catch{}},()=>{});
  try{await graphicsReady(initialising,signal,options.graphicsTimeoutMs??8000);}catch(error){throw graphicsError(error);}if(dead)throw cancelled();
  for(const cells of [16,32,64,128,256])gpu.model(geometryCache.get(cells));
  gpu.onError=e=>{if(!ready){fail(graphicsError(Error(e.message||'luma.gl shader compilation failed')));return;}fail(graphicsError(Error(e.message||'WebGPU terrain draw failed')));};gpu.onDeviceLost=e=>fail(graphicsError(Error(e.message||'The graphics device was lost')));backend=gpu.backend.isWebGPUBackend?'WebGPU':'WebGL 2';
  controls=new MapControls(camera,canvas);controls.enableDamping=true;controls.dampingFactor=.16;controls.screenSpacePanning=false;controls.minPolarAngle=.001;controls.maxPolarAngle=85*R;controls.minDistance=3;controls.maxDistance=4000000;controls.zoomToCursor=true;controls.listenToKeyEvents(canvas);
  controls.addEventListener('start',()=>{animation=null;if(!moving){cancelBackground();emit('movestart');}moving=true;lastMove=performance.now();selectionDirty=true;wake();});controls.addEventListener('change',()=>{if(!moving){cancelBackground();emit('movestart');}moving=true;lastMove=performance.now();selectionDirty=true;emit('move');wake();});
  resize();applyView(view);
  onProgress('Preparing direct imagery and GPU-displaced LINZ terrain');worker=new TerrainWorker(key,()=>wake(),e=>{if(ready)options.onFatal?.(e);});await worker.ready;if(dead)throw cancelled();configureWorker();
  // Root coverage is limited to New Zealand; deeper tiles use frustum and projected size.
  for(let y=37;y<=42;y++)for(let x=61;x<64;x++){const west=x/64*360-180,east=(x+1)/64*360-180,north=geographic([0,y/64*C])[1],south=geographic([0,(y+1)/64*C])[1];if(east>=165&&west<=180&&north>=-49&&south<=-33)roots.push(node(6,x,y));}
  for(const [label,fn] of [['+',()=>api.flyTo({zoom:api.getZoom()+1,duration:250})],['−',()=>api.flyTo({zoom:api.getZoom()-1,duration:250})],['↑',()=>api.flyTo({bearing:0,duration:350})]]){const b=document.createElement('button');b.textContent=label;b.setAttribute('aria-label',label==='+'?'Zoom in':label==='−'?'Zoom out':'Face north');b.onclick=fn;tools.append(b);}
  let down=null;listen(canvas,'pointerdown',e=>{down=[e.clientX,e.clientY,performance.now()];});listen(canvas,'pointerup',e=>{if(down&&Math.hypot(e.clientX-down[0],e.clientY-down[1])<5&&performance.now()-down[2]<600){const p=pick(e);if(p)emit('click',{lngLat:{lng:p[0],lat:p[1]}});}down=null;});let mouseAt=0;listen(canvas,'pointermove',e=>{if(moving||performance.now()-mouseAt<160)return;mouseAt=performance.now();const p=pick(e);if(p)emit('mousemove',{lngLat:{lng:p[0],lat:p[1]}});});
  listen(document,'visibilitychange',()=>{if(document.hidden){cancelBackground();cancelAnimationFrame(raf);raf=0;}else{lastFrame=0;reset();}});listen(canvas,'webglcontextlost',e=>{e.preventDefault();emit('webglcontextlost');fail(graphicsError(Error('luma.gl graphics context lost.')));});listen(window,'resize',resize);resizeObserver=new ResizeObserver(resize);resizeObserver.observe(container);
  onProgress('Loading the first terrain and imagery tiles');lastSelect=0;selectionDirty=true;wake();const timer=setTimeout(()=>rejectFirst(Error('Terrain could not load in time. Original is available.')),35000);try{await first;}finally{clearTimeout(timer);}if(dead)throw cancelled();return api;
 }catch(e){remove();throw e;}
}
