const assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),{pathToFileURL}=require('node:url');
const A=require('../public/adaptive-terrain.js');
const xy=i=>[i%129,Math.floor(i/129)],shapes=[32,64,128].map(n=>A.geometry(n));
// Closed, consistently wound, exactly covering triangulations; all boundary
// edges are identical to the original 128 mesh, without T junctions.
const boundaries=[];
for(const shape of shapes){let area=0;const edges=new Map();
 for(let i=0;i<shape.surfaceTriangles*3;i+=3){const ids=[...shape.indices.slice(i,i+3)],p=ids.map(xy);const cross=(p[1][0]-p[0][0])*(p[2][1]-p[0][1])-(p[1][1]-p[0][1])*(p[2][0]-p[0][0]);assert.ok(cross<0,'Native terrain winding, no degenerates');area-=cross/2;
  for(let j=0;j<3;j++){const a=ids[j],b=ids[(j+1)%3],key=Math.min(a,b)+'/'+Math.max(a,b);edges.set(key,(edges.get(key)||0)+1);}
 }
 assert.equal(area,128*128);const boundary=[];
 for(const [key,n]of edges){assert.ok(n===1||n===2,'Manifold edges');if(n===1){const [a,b]=key.split('/').map(Number).map(xy);assert.ok(a[0]===b[0]&&(a[0]===0||a[0]===128)||a[1]===b[1]&&(a[1]===0||a[1]===128));assert.equal(Math.hypot(a[0]-b[0],a[1]-b[1]),1);boundary.push(key);}}
 assert.equal(boundary.length,512);boundaries.push(boundary.sort());assert.ok(Math.max(...shape.indices)<17415);
 assert.equal(shape.triangles-shape.surfaceTriangles,1024,'All original skirt triangles retained');
}
assert.deepEqual(boundaries[0],boundaries[2]);assert.deepEqual(boundaries[1],boundaries[2]);
assert.deepEqual(shapes[0].indices.slice(shapes[0].surfaceTriangles*3),shapes[2].indices.slice(shapes[2].surfaceTriangles*3));
assert.deepEqual(shapes[1].indices.slice(shapes[1].surfaceTriangles*3),shapes[2].indices.slice(shapes[2].surfaceTriangles*3));
assert.ok(shapes[1].triangles/shapes[2].triangles<.3);assert.ok(shapes[0].triangles/shapes[2].triangles<.12);
const id={key:123,canonical:{z:10,x:995,y:644}};
function analyse(fn){const dem={dim:256,data:{},sampleBilinear:(x,y)=>fn((x+.5)/2,(y+.5)/2)},job=A.profileJob(dem,id,id);while(!A.advance(job)){}return job;}
const plane=analyse((x,y)=>1200+2*x-3*y);assert.ok(plane.errors[32]<1e-9&&plane.errors[64]<1e-9);
const fields=[(x,y)=>1500+150*Math.sin(x*.7)*Math.cos(y*.6),(x,y)=>x>63?2000:100,(x,y)=>x===64&&y===64?4000:0,(x,y)=>1000+.03*x*x+.07*x*y+.09*y*y];
let seed=109;const random=()=>{seed=(1664525*seed+1013904223)>>>0;return seed/2**32;};
function interpolate(p,a,b,c,h){const det=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1]),u=((b[1]-c[1])*(p[0]-c[0])+(c[0]-b[0])*(p[1]-c[1]))/det,v=((c[1]-a[1])*(p[0]-c[0])+(a[0]-c[0])*(p[1]-c[1]))/det;if(u< -1e-9||v< -1e-9||u+v>1+1e-9)return null;return u*h[0]+v*h[1]+(1-u-v)*h[2];}
for(const field of fields){const job=analyse(field),h=job.heights;
 for(const shape of shapes.slice(0,2)){const step=128/shape.size,cells=new Map();
  for(let i=0;i<shape.surfaceTriangles*3;i+=3){const ids=[...shape.indices.slice(i,i+3)],p=ids.map(xy),cx=Math.floor(p.reduce((s,a)=>s+a[0],0)/3/step),cy=Math.floor(p.reduce((s,a)=>s+a[1],0)/3/step),key=cx+'/'+cy;(cells.get(key)||cells.set(key,[]).get(key)).push({p,h:ids.map(j=>h[j])});}
  for(let i=0;i<2000;i++){const x=random()*128,y=random()*128,ix=Math.floor(x),iy=Math.floor(y),u=x-ix,v=y-iy,tl=h[iy*129+ix],tr=h[iy*129+ix+1],bl=h[(iy+1)*129+ix],br=h[(iy+1)*129+ix+1],fine=v>=u?tl*(1-v)+bl*(v-u)+br*u:tl*(1-u)+br*v+tr*(u-v);
   let coarse=null;for(const t of cells.get(Math.floor(x/step)+'/'+Math.floor(y/step))){coarse=interpolate([x,y],...t.p,t.h);if(coarse!==null)break;}assert.notEqual(coarse,null);assert.ok(Math.abs(coarse-fine)<=job.errors[shape.size]+1e-7,'Residual range bounds the full interpolated surface, including boundary fans');
  }
 }
}
assert.equal(A.levelFor({heightPixels:1,span:64},{errors:{32:0,64:0}}),32);
assert.equal(A.levelFor({heightPixels:1,span:600},{errors:{32:0,64:0}}),64);
assert.equal(A.levelFor({heightPixels:2,span:64},{errors:{32:8,64:3}}),128);
assert.equal(A.levelFor(null,plane),128);
let chosen=A.choose(null,32,0);assert.equal(chosen.level,128);chosen=A.choose(chosen,32,349);assert.equal(chosen.level,128);chosen=A.choose(chosen,32,350);assert.equal(chosen.level,32);assert.equal(A.choose(chosen,128,351).level,128,'Refinement must be immediate');
(async()=>{
 // Use the actual pinned terrain, DEM sampler and buffer/segment constructors.
 const source=fs.readFileSync('public/assets/maplibre-gl.mjs','utf8'),terrainName=source.match(/(\w+)=class\{constructor\(e,t,n,r=`auto`\)\{this._meshCache=/)[1],demName=source.match(/q\(`DEMData`,(\w+)\)/)[1];
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'ridge-adaptive-')),file=path.join(dir,'native.mjs');fs.writeFileSync(file,source+`\nexport {${terrainName} as NativeTerrain,${demName} as NativeDEM};\n`);let E;try{E=await import(pathToFileURL(file));}finally{fs.rmSync(dir,{recursive:true,force:true});}
 const gpu={vertices:0,indices:0,destroyedVertices:0,destroyedIndices:0},context={createVertexBuffer(data){gpu.vertices++;return{length:data.length,destroy(){gpu.destroyedVertices++;}};},createIndexBuffer(data){gpu.indices++;return{data:new Uint16Array(data.arrayBuffer.slice(0,data.length*6)),destroy(){gpu.destroyedIndices++;}};}};
 const painter={context,depthDirty:0,markTerrainDepthDirty(){this.depthDirty++;},render(){const t=map.terrain,depth=t.getTerrainMesh(id,false),colour=t.getTerrainMesh(id,false);assert.equal(depth,colour,'Depth and colour must share the frame selection');return colour;}};
 const sourceManager={_source:{type:'raster-dem',tileSize:256},_outOfViewCache:{getByKey:()=>null},getTileByID(){return sourceTile;}};
 const terrain=new E.NativeTerrain(painter,sourceManager,{exaggeration:1});
 const raw=new Uint8Array(260*260*4);for(let y=0;y<260;y++)for(let x=0;x<260;x++){const h=1000+x*.1+y*.2,v=Math.round((h+10000)*10),i=(y*260+x)*4;raw[i]=v>>16;raw[i+1]=v>>8;raw[i+2]=v;raw[i+3]=255;}
 const dem=new E.NativeDEM(1,{width:260,height:260,data:raw},'mapbox'),sourceTile={tileID:id,dem};terrain.tileManager.getSourceTile=()=>sourceTile;terrain.tileManager.getRenderableTiles=()=>[{tileID:id}];
 const originalPixels=new Uint8Array(dem.data.buffer.slice(0)),nativeMesh=terrain.getTerrainMesh(id,false);assert.deepEqual(nativeMesh.indexBuffer.data,shapes[2].indices,'128 reference and skirts must match the shipped native mesh byte for byte');
 const nativeSampler=analyse((x,y)=>dem.sampleBilinear(x*2-.5,y*2-.5));assert.ok(Number.isFinite(nativeSampler.errors[32]));
 let transform;E.Style.prototype._setProjectionInternal.call({map:{migrateProjection:t=>transform=t},tileManagers:{},_globalState:{}},'mercator');transform.resize(1200,700);
 let projectionCases=0;for(const latitude of [-35,-39,-43.6,-46.5])for(const pitch of [0,45,70])for(const bearing of [-24,90]){
  transform.setCenter(new E.LngLat(170,latitude));transform.setElevation(1200);transform.setZoom(11);transform.setPitch(pitch);transform.setBearing(bearing);
  const coordinate=E.MercatorCoordinate.fromLngLat(transform.center),q={canonical:{z:12,x:Math.floor(coordinate.x*4096),y:Math.floor(coordinate.y*4096)}},profile={min:900,max:1800},screen=A.projection(transform,q,profile,1.5,1.25);if(!screen)continue;projectionCases++;
  for(let i=0;i<20;i++){const p={x:(q.canonical.x+random())/4096,y:(q.canonical.y+random())/4096},z=1000+random()*500,dz=.05,a=transform._coordinateClipPoint(p,z*1.5,transform._pixelMatrix3D),b=transform._coordinateClipPoint(p,(z+dz)*1.5,transform._pixelMatrix3D),distance=Math.hypot(a[0]/a[3]-b[0]/b[3],a[1]/a[3]-b[1]/b[3])*1.25;assert.ok(distance<=screen.heightPixels*dz+1e-7,'Conservative perspective error across New Zealand camera cases');}
 }assert.ok(projectionCases>=12);
 const events={},idle=new Map(),timers=new Map();let serial=0,time=0,moving=false,enabled=true,repaints=0;
 const environment={requestIdleCallback(fn){const id=++serial;idle.set(id,fn);return id;},cancelIdleCallback:id=>idle.delete(id),setTimeout(fn,ms){const id=++serial;timers.set(id,{fn,at:time+ms});return id;},clearTimeout:id=>timers.delete(id)};
 const map={terrain,painter,getPixelRatio:()=>1,getProjection:()=>({type:'mercator'}),isMoving:()=>moving,triggerRepaint(){repaints++;},on(n,f){(events[n]??=[]).push(f);},off(n,f){events[n]=events[n].filter(x=>x!==f);}};
 const matrix=[1,0,0,0,0,1,0,0,0,0,1,0,-995*64+100,-644*64+100,0,1],view={_pixelMatrix3D:matrix,worldSize:1024*64},originalGetMesh=terrain.getTerrainMesh,originalRender=painter.render;
 const control=A.attach(map,{engine:E,enabled:()=>enabled,clock:()=>time+=.01,timers:environment,document:{hidden:false}}),emit=n=>events[n]?.forEach(f=>f({}));
 painter.render({},view);assert.equal(control.stats().adaptiveTerrain.grids[128],1,'Unknown DEM metadata must retain native detail');
 function flush(){let n=0;while(idle.size){assert.ok(n++<10000);const [id,fn]=idle.entries().next().value;idle.delete(id);fn({timeRemaining:()=>4});}}
 flush();assert.equal(control.stats().adaptiveTerrain.analysed,1);painter.render({},view);time+=400;painter.render({},view);let s=control.stats().adaptiveTerrain;assert.equal(s.grids[32],1);assert.ok(s.triangles<s.nativeTriangles*.12);assert.equal(gpu.vertices,1,'Grid variants must share the native vertex buffer');
 assert.equal(terrain.getTerrainMesh(id,false),painter.render({},view),'Picking/depth mesh lookup keeps the same grid');assert.equal(terrain.getTerrainMesh(id,true),nativeMesh,'Globe rendering must keep native meshes');
 // A close projected tile refines before the next depth/colour draw.
 const close={...view,worldSize:view.worldSize*20};painter.render({},close);assert.equal(control.stats().adaptiveTerrain.grids[128],1);assert.ok(painter.depthDirty>0);
 painter.render({},view);time+=400;painter.render({},view);enabled=false;control.refresh();assert.equal(painter.render({},view),nativeMesh);enabled=true;control.refresh();painter.render({},view);time+=400;painter.render({},view);
 moving=true;emit('movestart');assert.equal(idle.size,0);assert.equal(control.stats().adaptiveTerrain.activeAnalysis,false);moving=false;emit('moveend');
 const before=E.ridgeAdaptiveTerrainAPI.revision(dem);dem.backfillBorder(dem,0,0);assert.ok(E.ridgeAdaptiveTerrainAPI.revision(dem)>before);painter.render({},view);assert.equal(control.stats().adaptiveTerrain.grids[128],1,'Mutated DEM borders must invalidate error metadata');flush();
 assert.deepEqual(new Uint8Array(dem.data.buffer),originalPixels,'Adaptive grids must not alter measured heights');
 emit('webglcontextlost');assert.equal(idle.size,0);emit('webglcontextrestored');painter.render({},view);assert.equal(control.stats().adaptiveTerrain.grids[128],1);flush();
 control.destroy();assert.equal(terrain.getTerrainMesh,originalGetMesh);assert.equal(painter.render,originalRender);assert.ok(Object.values(events).every(list=>!list.length));assert.equal(idle.size,0);assert.equal(timers.size,0);assert.equal(gpu.destroyedVertices,0,'Destroying variants must not destroy shared native vertices');terrain.destroy();assert.equal(gpu.destroyedVertices,1);
 console.log(JSON.stringify({pass:true,triangles:shapes.map(x=>({grid:x.size,surface:x.surfaceTriangles,total:x.triangles})),exactNativeBoundaryAndSkirts:true,continuousSurfaceErrorSamples:16000,perspectiveCases:projectionCases,nativeBuffersAndDEM:true,sharedDepthColourPicking:true,cooperativeAnalysis:true,immediateRefinement:true,sourceBorderInvalidation:true,contextToggleCleanup:true,repaints,gpu,note:'Native CPU paths with controlled GPU handles; not device FPS.'}));
})().catch(error=>{console.error(error);process.exitCode=1;});
