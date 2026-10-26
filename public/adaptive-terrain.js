/* Streaming native terrain: 32/64 interiors, exact 128 boundary and skirts.
   Error metadata is measured cooperatively from resident DEMs, never fetched.
   Missing metadata uses the original mesh. Height samples are never changed. */
(function(root){'use strict';
const FINE=128,WIDTH=129,LEVELS=[32,64,128],MAX_QUEUE=32;
function geometry(size,skirts=true){
 if(!LEVELS.includes(size))throw Error('Unsupported terrain grid');
 const indices=[],step=FINE/size,index=(x,y)=>y*WIDTH+x,tri=(a,b,c)=>indices.push(a,b,c);
 for(let y=0;y<FINE;y+=step)for(let x=0;x<FINE;x+=step){
  const a=index(x,y),b=index(x,y+step),c=index(x+step,y+step),d=index(x+step,y);
  if(size===FINE||x>0&&y>0&&x+step<FINE&&y+step<FINE){tri(a,b,c);tri(a,c,d);continue;}
  // Boundary cells are convex fans; all vertices belong to the fine grid.
  const ring=[],addEdge=(ax,ay,bx,by,fine)=>{const n=fine?step:1;for(let i=0;i<n;i++)ring.push(index(ax+(bx-ax)*i/n,ay+(by-ay)*i/n));};
  addEdge(x,y,x,y+step,x===0);addEdge(x,y+step,x+step,y+step,y+step===FINE);
  addEdge(x+step,y+step,x+step,y,x+step===FINE);addEdge(x+step,y,x,y,y===0);
  const centre=index(x+step/2,y+step/2);for(let i=0;i<ring.length;i++)tri(centre,ring[i],ring[(i+1)%ring.length]);
 }
 const surfaceTriangles=indices.length/3;
 if(skirts){
  // Exact native _buildSkirts index order. The shared native vertex buffer
  // retains all fine edge vertices and the original skirt extrusion flags.
  const top=WIDTH*WIDTH,bottom=top+WIDTH,left=top+2*WIDTH,right=left+2*WIDTH;
  for(let i=0;i<FINE;i++){
   tri(FINE*WIDTH+i,bottom+i,bottom+i+1);tri(FINE*WIDTH+i,bottom+i+1,FINE*WIDTH+i+1);
   tri(i,top+i+1,top+i);tri(i,i+1,top+i+1);
  }
  for(let i=0;i<FINE*2;i+=2){tri(left+i,left+i+1,left+i+3);tri(left+i,left+i+3,left+i+2);tri(right+i,right+i+3,right+i+1);tri(right+i,right+i+2,right+i+3);}
 }
 return{size,indices:new Uint16Array(indices),surfaceTriangles,triangles:indices.length/3};
}
function profileJob(dem,id,sourceId,revision=0){
 const q=id.canonical||id,s=sourceId.canonical||sourceId,delta=q.z-s.z;
 if(delta<0||delta>16||!dem||typeof dem.sampleBilinear!=='function'||!Number.isFinite(dem.dim)||dem.dim<1)return null;
 const scale=2**delta,x=q.x-s.x*scale,y=q.y-s.y*scale;
 if(x<0||y<0||x>=scale||y>=scale)return null;
 return{dem,id,sourceId,data:dem.data,revision,key:delta+'/'+x+'/'+y,scale,x,y,row:0,phase:128,heights:new Float64Array(WIDTH*WIDTH),min:Infinity,max:-Infinity,errors:{32:0,64:0}};
}
function advance(job){
 const {dem,heights:h}=job;
 if(job.phase===128){const y=job.row++,sy=(job.y+y/FINE)/job.scale*dem.dim-.5;
  for(let x=0;x<=FINE;x++){const height=dem.sampleBilinear((job.x+x/FINE)/job.scale*dem.dim-.5,sy);if(!Number.isFinite(height))throw Error('Invalid DEM sample');h[y*WIDTH+x]=height;job.min=Math.min(job.min,height);job.max=Math.max(job.max,height);}
  if(job.row===WIDTH){job.phase=64;job.row=0;}return false;
 }
 const size=job.phase,step=FINE/size,y=job.row++*step;
 for(let x=0;x<FINE;x+=step){
  // Subtract an affine plane before measuring the residual range. Both
  // meshes are convex interpolants of these fine vertices inside this cell:
  // their vertical difference is bounded by maxResidual-minResidual,
  // including the boundary fans. Sloping planes therefore simplify exactly.
  const origin=h[y*WIDTH+x],dx=(h[y*WIDTH+x+step]-origin)/step,dy=(h[(y+step)*WIDTH+x]-origin)/step;
  let min=Infinity,max=-Infinity;
  for(let j=0;j<=step;j++)for(let i=0;i<=step;i++){const r=h[(y+j)*WIDTH+x+i]-(origin+dx*i+dy*j);min=Math.min(min,r);max=Math.max(max,r);}
  job.errors[size]=Math.max(job.errors[size],max-min);
 }
 if(job.row===size){if(size===64){job.phase=32;job.row=0;}else return true;}return false;
}
// Bound the perspective height derivative over the entire measured tile
// prism. A corner minimum of homogeneous w bounds every point in the prism.
function projection(transform,id,profile,exaggeration=1,pixelRatio=1){
 const m=transform?._pixelMatrix3D,q=id.canonical||id;
 if(!m||m.length!==16||!Number.isFinite(exaggeration)||exaggeration<=0)return null;
 const n=2**q.z,world=transform.worldSize;let minW=Infinity,maxDx=0,maxDy=0,left=Infinity,right=-Infinity,top=Infinity,bottom=-Infinity;
 for(const dx of [0,1])for(const dy of [0,1])for(const height of [profile.min,profile.max]){
  const x=((q.x+dx)/n+(id.wrap||0))*world,y=(q.y+dy)/n*world,z=height*exaggeration;
  const X=m[0]*x+m[4]*y+m[8]*z+m[12],Y=m[1]*x+m[5]*y+m[9]*z+m[13],w=m[3]*x+m[7]*y+m[11]*z+m[15];
  if(![X,Y,w].every(Number.isFinite)||w<=1e-5)return null;minW=Math.min(minW,w);
  maxDx=Math.max(maxDx,Math.abs(m[8]*w-m[11]*X));maxDy=Math.max(maxDy,Math.abs(m[9]*w-m[11]*Y));
  left=Math.min(left,X/w);right=Math.max(right,X/w);top=Math.min(top,Y/w);bottom=Math.max(bottom,Y/w);
 }
 return{heightPixels:Math.hypot(maxDx,maxDy)/(minW*minW)*exaggeration*pixelRatio,span:Math.max(right-left,bottom-top)*pixelRatio};
}
function levelFor(metrics,profile,limit=.6,cellLimit=10){
 if(!metrics||!profile)return 128;
 for(const size of [32,64])if(profile.errors[size]*metrics.heightPixels<=limit&&metrics.span/size<=cellLimit)return size;
 return 128;
}
function choose(previous,target,time){
 const old=previous||{level:128,target:128,since:time};
 if(target>=old.level)return{level:target,target,since:time};
 if(old.target!==target)return{level:old.level,target,since:time};
 return time-old.since>=350?{level:target,target,since:time}:old;
}
function attach(map,{engine=root.maplibregl,enabled=()=>true,clock=()=>root.performance.now(),timers=root,document=root.document}={}){
 const api=engine?.ridgeAdaptiveTerrainAPI,supported=api?.version==='6.14.0';
 let dead=false,lost=false,current=null,undoTerrain=null,painter=null,undoPainter=null,frameLevels=new Map(),history=new Map(),profiles=new WeakMap(),queue=new Map(),active=null,idle=null,timeout=null,wake=null;
 const meshes=new Map(),shapes=new Map(),counts={32:0,64:0,128:0},metrics={analysed:0,analysisSlices:0,analysisMs:0,failedAnalyses:0,switches:0,triangles:0,nativeTriangles:0};
 const activeMode=()=>!dead&&!lost&&supported&&enabled()&&!document?.hidden;
 const hasWork=()=>!!active||queue.size>0;
 function clearIdle(){if(idle!==null)timers.cancelIdleCallback?.(idle);if(timeout!==null)timers.clearTimeout(timeout);idle=timeout=null;}
 function cancelWork(){clearIdle();queue.clear();active=null;}
 function schedule(){if(!activeMode()||!hasWork()||idle!==null||timeout!==null||map.isMoving?.())return;
  if(typeof timers.requestIdleCallback==='function')idle=timers.requestIdleCallback(drain);
  else timeout=timers.setTimeout(()=>drain({timeRemaining:()=>3}),40);
 }
 const valid=job=>job.dem.data===job.data&&api.revision(job.dem)===job.revision;
 function drain(deadline){idle=timeout=null;if(!activeMode()||map.isMoving?.())return;
  const start=clock();metrics.analysisSlices++;
  try{while(clock()-start<2&&deadline.timeRemaining()>.5){
   if(!active){const first=queue.entries().next().value;if(!first)break;queue.delete(first[0]);active=first[1];}
   if(!valid(active)){active=null;continue;}
   if(!active.heights){active=initialise(active);if(!active)continue;}
   try{if(advance(active)){
    let entries=profiles.get(active.dem);if(!entries){entries=new Map();profiles.set(active.dem,entries);}
    const margin=Math.max(.01,Math.max(Math.abs(active.min),Math.abs(active.max))*2e-6);
    entries.set(active.key,{data:active.data,revision:active.revision,min:active.min,max:active.max,errors:{32:active.errors[32]+margin,64:active.errors[64]+margin}});while(entries.size>64)entries.delete(entries.keys().next().value);
    metrics.analysed++;active=null;map.triggerRepaint();
   }}catch{metrics.failedAnalyses++;active=null;}
  }}finally{metrics.analysisMs+=Math.max(0,clock()-start);schedule();}
 }
 function profileFor(id){
  const source=current?.tileManager?.getSourceTile(id,true),dem=source?.dem;if(!dem)return null;
  const q=id.canonical,s=source.tileID.canonical,delta=q.z-s.z;if(delta<0||delta>16)return null;
  const factor=2**delta,x=q.x-s.x*factor,y=q.y-s.y*factor,key=delta+'/'+x+'/'+y,revision=api.revision(dem),saved=profiles.get(dem)?.get(key);
  if(saved&&saved.data===dem.data&&saved.revision===revision)return saved;
  if(active?.dem!==dem||active.key!==key||!valid(active)){
   const token=id.key,old=queue.get(token);if(!old||old.dem!==dem||old.key!==key||!valid(old)){
    // Jobs allocate the temporary sample buffer only when admitted below.
    const job={dem,data:dem.data,revision,key,id,sourceId:source.tileID};queue.set(token,job);
    while(queue.size>MAX_QUEUE)queue.delete(queue.keys().next().value);
   }
  }return null;
 }
 // Queue entries retain only DEM identities; one 129² Float64 sample array
 // is created at a time, and is released as soon as its metadata is complete.
 function initialise(job){if(job.heights)return job;return profileJob(job.dem,job.id,job.sourceId,job.revision);}
 function shape(size){const key=size+'/'+(current._terrainSkirtLength!=='none');if(!shapes.has(key))shapes.set(key,geometry(size,current._terrainSkirtLength!=='none'));return shapes.get(key);}
 function prepare(transform){
  frameLevels=new Map();for(const size of LEVELS)counts[size]=0;metrics.triangles=metrics.nativeTriangles=0;
  if(!current)return;const now=clock(),mode=activeMode()&&current.meshSize===FINE&&map.getProjection?.()?.type!=='globe';let pending=false;
  for(const tile of current.tileManager.getRenderableTiles()){
   const id=tile.tileID,profile=mode?profileFor(id):null,screen=profile?projection(transform,id,profile,current.exaggeration,map.getPixelRatio?.()||1):null,previous=history.get(id.key);
   let target=levelFor(screen,profile);
   // Retain a coarser grid through a wider error band, then refine promptly.
   if(previous?.level<128&&target>previous.level&&levelFor(screen,profile,.9,13)<=previous.level)target=previous.level;
   const selected=mode&&profile?choose(previous,target,now):{level:128,target:128,since:now};
   if(previous&&previous.level!==selected.level){metrics.switches++;painter.markTerrainDepthDirty?.();}
   if(!previous&&selected.level!==128)painter.markTerrainDepthDirty?.();
   history.delete(id.key);history.set(id.key,selected);frameLevels.set(id.key,selected.level);counts[selected.level]++;
   const skirts=current._terrainSkirtLength!=='none'?1024:0;metrics.nativeTriangles+=32768+skirts;metrics.triangles+=selected.level===128?32768+skirts:shape(selected.level).triangles;
   if(selected.level!==selected.target)pending=true;
  }
  while(history.size>1024)history.delete(history.keys().next().value);
  for(const key of queue.keys())if(!frameLevels.has(key))queue.delete(key);
  if(active&&!frameLevels.has(active.id.key))active=null;
  if(pending&&wake===null)wake=timers.setTimeout(()=>{wake=null;if(activeMode())map.triggerRepaint();},360);
  schedule();
 }
 function releaseMeshes(){for(const mesh of meshes.values())mesh.destroy();meshes.clear();shapes.clear();}
 function sync(){if(dead||!supported)return;
  if(map.terrain!==current){undoTerrain?.();undoTerrain=null;releaseMeshes();cancelWork();frameLevels.clear();history.clear();current=map.terrain;
   if(current){const terrain=current,native=terrain.getTerrainMesh,destroy=terrain.destroy;
    function getMesh(id,globe){const base=native.call(this,id,globe),size=!globe&&activeMode()?frameLevels.get(id.key)||128:128;if(size===128)return base;
     const key=size+'/'+(terrain._terrainSkirtLength!=='none');if(!meshes.has(key))meshes.set(key,api.createMesh(terrain.painter.context,base,shape(size).indices));return meshes.get(key);
    }
    function destruct(...args){releaseMeshes();return destroy.apply(this,args);}
    terrain.getTerrainMesh=getMesh;terrain.destroy=destruct;
    undoTerrain=()=>{if(terrain.getTerrainMesh===getMesh)terrain.getTerrainMesh=native;if(terrain.destroy===destruct)terrain.destroy=destroy;};
   }
  }
  if(map.painter!==painter){undoPainter?.();painter=map.painter;const owner=painter,native=owner?.render;
   if(typeof native==='function'){function render(style,transform,...rest){sync();prepare(transform);return native.call(this,style,transform,...rest);}owner.render=render;undoPainter=()=>{if(owner.render===render)owner.render=native;};}
  }
 }
 function data(event){if(event.tile?.dem){api.invalidate(event.tile.dem);map.triggerRepaint();}}
 function refresh(){if(!supported||dead)return;cancelWork();history.clear();frameLevels.clear();painter?.markTerrainDepthDirty?.();sync();map.triggerRepaint();}
 function onStart(){cancelWork();}
 function onEnd(){schedule();map.triggerRepaint();}
 function onLost(){lost=true;cancelWork();frameLevels.clear();history.clear();if(wake!==null)timers.clearTimeout(wake);wake=null;}
 function onRestored(){lost=false;releaseMeshes();profiles=new WeakMap();current=null;sync();refresh();}
 function visibility(){if(document?.hidden)cancelWork();else refresh();}
 function destroy(){if(dead)return;dead=true;cancelWork();if(wake!==null)timers.clearTimeout(wake);undoTerrain?.();undoPainter?.();releaseMeshes();history.clear();frameLevels.clear();for(const [event,fn]of listeners)map.off(event,fn);document?.removeEventListener?.('visibilitychange',visibility);}
 const listeners=[['terrain',sync],['sourcedata',data],['movestart',onStart],['moveend',onEnd],['webglcontextlost',onLost],['webglcontextrestored',onRestored],['remove',destroy]];
 if(supported){for(const [event,fn]of listeners)map.on(event,fn);document?.addEventListener?.('visibilitychange',visibility);sync();}
 return{refresh,destroy,stats:()=>({adaptiveTerrain:{supported,enabled:activeMode(),grids:{...counts},...metrics,queuedAnalyses:queue.size,activeAnalysis:!!active,indexBytes:[...shapes.values()].reduce((n,s)=>n+s.indices.byteLength,0),temporaryAnalysisBytes:active?.heights?.byteLength||0}})};
}
const api={geometry,profileJob,advance,projection,levelFor,choose,attach};if(typeof module!=='undefined')module.exports=api;else root.RidgeAdaptiveTerrain=api;
})(globalThis);
