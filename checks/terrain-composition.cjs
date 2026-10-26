const assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),{pathToFileURL}=require('node:url');
(async()=>{
 global.window={requestAnimationFrame:fn=>setTimeout(fn,0),cancelAnimationFrame:clearTimeout};
 // Expose the pinned native render-state class ONLY in a temporary test copy.
 // Production engine and its integrity manifest stay byte-for-byte unchanged.
 const source=fs.readFileSync('public/assets/maplibre-gl.mjs','utf8'),symbol=source.match(/var (\w+)=class\{constructor\(e\)\{this.currentLayer=0/)[1],painterSymbol=source.match(/,(\w+)=class e\{constructor\(e\)\{this.drawFunctions=/)[1];
 const temp=fs.mkdtempSync(path.join(os.tmpdir(),'ridge-composition-')),file=path.join(temp,'native.mjs');fs.writeFileSync(file,source+`\nexport {${symbol} as NativeState,${painterSymbol} as NativePainter,${source.match(/this.drawFunctions=(\w+)/)[1]} as NativeDrawFunctions};\n`);
 let E;try{E=await import(pathToFileURL(file));}finally{fs.rmSync(temp,{recursive:true,force:true});}
 const C=require('../public/terrain-composition.js'),N=require('../public/native-residency.js'),S=require('../public/terrain-streaming.js'),A=require('../public/prepare-ahead.js');global.maplibregl=E;global.RidgeResidency=N;global.RidgeTerrainComposition=C;global.RidgeTerrainStreaming=S;global.RidgePrepareAhead=A;
 let tr;E.Style.prototype._setProjectionInternal.call({map:{migrateProjection:t=>tr=t},tileManagers:{},_globalState:{}},'mercator');tr.resize(1200,600);tr.setZoom(13);tr.setCenter(new E.LngLat(170.141,-43.595));tr.setPitch(58);
 const map={_maxTileCacheSize:192,_maxTileCacheZoomLevels:8,_requestManager:{transformRequest:async url=>({url})},_refreshExpiredTiles:false};
 const style=Object.assign(new E.Evented(),{map,tileManagers:{},dispatcher:{},_layers:{},_order:[],_checkLoaded(){},_validate:()=>false});map.style=style;style.on('error',()=>{});
 E.Style.prototype.addSource.call(style,'dem',{type:'raster-dem',tiles:['https://example.test/{z}/{x}/{y}'],tileSize:256,maxzoom:18});await new Promise(r=>setTimeout(r,10));
 const dem=style.tileManagers.dem;dem.getSource().loadTile=async tile=>{tile.dem={min:3000,max:3000,stride:260,dim:256};tile.state='loaded';};dem.getSource().unloadTile=()=>{};
 let draws=0,compositions=0,mips=0,releases=0,flushes=0,throwDraw=false;const drawNames=[];
 const stateSlot=value=>({value,get(){return this.value;},set(x){this.value=x;}}),pending=new Float32Array(16).map((_,i)=>i+10);
 const context={gl:{flush(){flushes++;},LESS:513,LEQUAL:515,ALWAYS:519,KEEP:7680,REPLACE:7681,GEQUAL:518,TEXTURE0:33984,TEXTURE1:33985,LINEAR:9729,NEAREST:9728,CLAMP_TO_EDGE:33071,LINEAR_MIPMAP_NEAREST:9985,TRIANGLES:4,drawingBufferWidth:1200,drawingBufferHeight:600},frameUniformBuffer:{pending,upload(){}},viewport:stateSlot([0,0,1200,600]),bindFramebuffer:stateSlot(null),activeTexture:stateSlot(0),clear(){assert.notEqual(this.bindFramebuffer.get(),null,'Never clear canvas');},setDefault(){}};
 const nativeRender=E.NativePainter.prototype.renderLayer;
 const painter={drawFunctions:E.NativeDrawFunctions,context,width:1200,height:600,renderLayer(...args){if(throwDraw)throw Error('test shader failure');return nativeRender.apply(this,args);},acquireRTT:size=>{compositions++;return{size,texture:{generateMipmap(){mips++;},destroy(){}}};},bindRTT:t=>context.bindFramebuffer.set(t),releaseRTT(){releases++;},markTerrainDepthDirty(){},patternAtlas:{isPatternMissing:()=>false}};
 const scene={terrain:null,style:{...style,off(){},on(){},_checkLoaded(){},triggerSymbolPlacement(){}},painter,_camera:{setTerrain(){}},fire(){}};
 E.Map.prototype.setTerrain.call(scene,{source:'dem',exaggeration:1});const terrain=scene.terrain,manager=terrain.tileManager;terrain.getMinMaxElevation=()=>({minElevation:0,maxElevation:4000});manager.update(tr,terrain);const example=manager.getRenderableTiles()[0],ID=example.tileID.constructor,Tile=example.constructor;
 const hit=E.MercatorCoordinate.fromLngLat([170.141,-43.595]),z=13,n=2**z,x=Math.floor(hit.x*n),y=Math.floor(hit.y*n);const originalKeys=manager._renderableTilesKeys.slice();
 const candidate=new ID(z,0,z,x+20,y),coarse=candidate.scaledTo(z-1),texture={bind(){},size:[256,256],useMipmap:true};
 const imageTiles=new Map(),image={getSource:()=>({type:'raster'}),getState:()=>({revision:0}),getVisibleCoordinates:()=>[],getTile:id=>imageTiles.get(id.key),_inViewTiles:{getAllTiles:()=>[]},_outOfViewCache:{data:{}}};
 const putImage=(id,where='cache')=>{const t=new Tile(id,256);t.state='loaded';t.texture=texture;imageTiles.set(id.key,t);if(where==='cache')image._outOfViewCache.data[id.key]=[{value:t}];return t;};putImage(coarse);
 const props={'raster-opacity':1,'raster-resampling':'linear','raster-fade-duration':0,'raster-brightness-min':.058,'raster-brightness-max':1,'raster-saturation':-.25,'raster-contrast':.26,'raster-hue-rotate':0};
 const layer={id:'photo',type:'raster',source:'photo',isHidden:()=>false,isTileClipped:()=>false,paint:{get:k=>props[k]},serialize:()=>({type:'raster',paint:props}),hasTransition:()=>false};style._order=['photo'];style._layers={photo:layer};style.tileManagers.photo=image;
 const drawState=new E.NativeState({transform:tr,terrain,data:{moving:false,useSubdivision:false,anisotropicFilterPitch:20},context,projection:{getMeshFromTileID:()=>({vertexBuffer:{},indexBuffer:{},segments:{}})},programCache:{getProgram:({name})=>({draw(...args){assert.notEqual(context.bindFramebuffer.get(),null);draws++;drawNames.push(name);}})},currentPass:'translucent'});
 drawState.opaquePassCutoff=0;const rtt=painter.renderToTexture;rtt.rttSize=512;
 const drapes=N.retainDrapes(manager,painter,{bytes:8*1048576,readiness:()=>()=>false});
 // Capture the real render-state through production's normal completion hook.
 rtt.prepareForRender(style,tr.zoom,false);rtt._renderableTiles=[];rtt.renderLayer(layer,style,drawState);assert.ok(drapes.planComposition({transform:A.expandedTransform(tr),hit}).length);
 const getDEM=manager.getSourceTile;manager.getSourceTile=()=>({dem:{},tileID:coarse});
 const beforeUniforms=pending.slice(),beforeKeys=manager._renderableTilesKeys.slice(),beforeTarget=Object.keys(manager._tiles),beforeCoords=image.getVisibleCoordinates();
 assert.equal(drapes.prepareComposition(candidate),'prepared');assert.equal(draws,1);assert.equal(mips,1);assert.equal(context.bindFramebuffer.get(),null);assert.deepEqual(context.viewport.get(),[0,0,1200,600]);assert.deepEqual(pending,beforeUniforms);assert.deepEqual(manager._renderableTilesKeys,beforeKeys);assert.deepEqual(Object.keys(manager._tiles),beforeTarget);assert.deepEqual(image.getVisibleCoordinates(),beforeCoords);assert.equal(drapes.stats().residentDrapes,1);assert.equal(drapes.prepareComposition(candidate),'reused');assert.equal(draws,1);
 // Actual native update adopts the exact prepared patch, without recomposing.
 let requested=candidate;const wantedKeys=manager._renderableTilesKeys.slice();const originalUpdate=manager.update; // wrapper calls native covering logic; control only frustum intersection.
 const nativeTransform={...tr,getCoveringTilesDetailsProvider:()=>({getCenter:()=>tr.center,getWrap:()=>0,allowVariableZoom:()=>false,allowWorldCopies:()=>false,distanceToTile2d:()=>0,getTileBoundingVolume({z,x,y}){const d=requested.canonical.z-z;return{intersectsFrustum:()=>d>=0&&x===Math.floor(requested.canonical.x/2**d)&&y===Math.floor(requested.canonical.y/2**d)?1:0};}}),center:tr.center,elevation:0,pitch:0,bearing:0,bearingInRadians:0,cameraToCenterDistance:500,worldSize:512*2**12,tileSize:512,maxZoom:19,zoom:12,fov:36.87,renderWorldCopies:false,width:256,height:256,populateCache(){},getCameraFrustum:()=>({}),getClippingPlane:()=>null};
 // Match z13 selection with native terrain tile size 256.
 manager.tileSize=256;dem.update=()=>{};nativeTransform.worldSize=512*2**12;
 manager.update(nativeTransform,terrain);const adopted=manager._tiles[candidate.key];assert.ok(adopted?.getRTT(0),'Native selection adopts prepared final texture');assert.ok(drapes.stats().reuses>=1);assert.equal(draws,1);
 image.getVisibleCoordinates=()=>[coarse];const restoredRTT=adopted.getRTT(0);rtt.prepareForRender(style,tr.zoom+.0001,false);rtt.prepareForRender(style,tr.zoom+.0001,false);assert.equal(adopted.getRTT(0),restoredRTT,'Precomposed raster patch carries zoom-independent reuse metadata');
 // Native fingerprints keep this coarse composition while fine coverage is
 // incomplete, then allow normal progressive refinement when it is complete.
 image.getVisibleCoordinates=()=>[new ID(z+1,0,z+1,(x+20)*2,y*2)];image.getTile=id=>({texture});const saved=adopted.getRTT(0);rtt.prepareForRender(style,tr.zoom,false);assert.equal(adopted.getRTT(0),saved);
 // Style/source revision must invalidate even a complete prepared cover.
 image.getState=()=>({revision:1});rtt.prepareForRender(style,tr.zoom,false);assert.equal(adopted.getRTT(0),undefined);
 // Multiple drape stacks, separated by a screen-space circle layer, retain
 // native stack ownership, full style fingerprints and colour shader values.
 const multiID=new ID(z,0,z,x+8,y);putImage(multiID.scaledTo(z-1));
 style._order=['photo','marker','photo2'];style._layers.marker={id:'marker',type:'circle',isHidden:()=>false};style._layers.photo2={...layer,id:'photo2'};
 assert.equal(C.estimate(style,drawState,512),2*512*512*4*4/3);
 assert.equal(drapes.prepareComposition(multiID),'prepared');assert.equal(drawNames.at(-1),'raster');assert.equal(mips,3);assert.equal(flushes,2);assert.equal(drapes.stats().residentDrapes,1);
 style._order=['photo'];
 // Never retain partial coverage; failed shader work is released atomically.
 const missing=new ID(z,0,z,x+40,y);assert.equal(drapes.prepareComposition(missing),'coverage');
 const failID=new ID(z,0,z,x+4,y);putImage(failID.scaledTo(z-1));throwDraw=true;const count=drapes.stats().residentDrapes;assert.throws(()=>drapes.prepareComposition(failID),/shader failure/);assert.equal(drapes.stats().residentDrapes,count);assert.deepEqual(pending,beforeUniforms);assert.equal(context.bindFramebuffer.get(),null);throwDraw=false;
 drapes.setBudget(0);assert.equal(drapes.prepareComposition(failID),'pressure');assert.equal(drapes.stats().residentBytes,0);
 drapes.destroy();manager.getSourceTile=getDEM;terrain.destroy();
 const report={engine:'6.14.0',actualNativeCoordinatesFingerprintsRasterDraw:true,finishedTextureAdoptedWithoutRecomposition:true,coarseCoverRetainedUntilRefinement:true,sourceRevisionInvalidates:true,incompleteCoverageRejected:true,shaderFailureAtomic:true,frameUniformsFramebufferViewportRestored:true,noVisibleSelectionMutation:true,noCanvasTerrainDraw:true,cacheBudgetEnforced:true,multipleNativeDrapeStacks:true,preparedCompositionZoomMetadata:true,nonblockingFlush:true,draws,mips,releases,drawNames,caveat:'Real native CPU coordinate/fingerprint/raster shader-dispatch paths with controlled GPU handles/program draw; no real browser GPU or device FPS benchmark.'};fs.writeFileSync('TERRAIN_COMPOSITION_VALIDATION.json',JSON.stringify(report,null,2)+'\n');console.log(JSON.stringify(report));
})().catch(e=>{console.error(e);process.exitCode=1});
