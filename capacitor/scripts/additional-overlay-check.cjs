const assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),{pathToFileURL}=require('node:url');
process.chdir(process.argv[2]||'audit');
(async()=>{
 global.window={requestAnimationFrame:fn=>setTimeout(fn,0),cancelAnimationFrame:clearTimeout};
 const source=fs.readFileSync('capacitor/www/assets/maplibre-gl.mjs','utf8'),symbol=source.match(/var (\w+)=class\{constructor\(e\)\{this.currentLayer=0/)[1],painterSymbol=source.match(/,(\w+)=class e\{constructor\(e\)\{this.drawFunctions=/)[1];
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'ridge-direct-'));const file=path.join(dir,'native.mjs');fs.writeFileSync(file,source+`\nexport {${symbol} as NativeState,${painterSymbol} as NativePainter,${source.match(/this.drawFunctions=(\w+)/)[1]} as NativeDrawFunctions};\n`);
 let E;try{E=await import(pathToFileURL(file));}finally{fs.rmSync(dir,{recursive:true,force:true});}
 const Module=require('node:module'),adapterFile=path.resolve('capacitor/www/maplibre-direct-sampling.js');
 let adapterSource=fs.readFileSync(adapterFile,'utf8');
 if(process.argv.includes('--candidate'))adapterSource=adapterSource.replace('if(!manager||!Array.isArray(coords))return false;',`if(!manager)return false;
  if(coords===undefined && rtt._coordsAscending[layer.source] && !tile.getRTT(0))continue;
  if(!Array.isArray(coords))return false;`);
 const moduleD=new Module(adapterFile,module);moduleD.filename=adapterFile;moduleD.paths=module.paths;moduleD._compile(adapterSource,adapterFile);const D=moduleD.exports;let tr;E.Style.prototype._setProjectionInternal.call({map:{migrateProjection:t=>tr=t},tileManagers:{},_globalState:{}},'mercator');tr.resize(1200,600);tr.setZoom(13);tr.setCenter(new E.LngLat(170.141,-43.595));tr.setPitch(58);
 const map={_maxTileCacheSize:192,_maxTileCacheZoomLevels:8,_requestManager:{transformRequest:async url=>({url})},_refreshExpiredTiles:false,events:{},on(n,f){this.events[n]=f;},off(n,f){if(this.events[n]===f)delete this.events[n];},triggerRepaint(){}};
 const style=Object.assign(new E.Evented(),{map,tileManagers:{},dispatcher:{},_layers:{},_order:[],_checkLoaded(){},_validate:()=>false});map.style=style;style.on('error',()=>{});
 E.Style.prototype.addSource.call(style,'dem',{type:'raster-dem',tiles:['https://example.test/{z}/{x}/{y}'],tileSize:256,maxzoom:18});await new Promise(r=>setTimeout(r,10));
 const dem=style.tileManagers.dem;dem.getSource().loadTile=async t=>{t.dem={min:3000,max:3000,stride:260,dim:256};t.state='loaded';};dem.getSource().unloadTile=()=>{};
 const slot=value=>({value,get(){return this.value;},set(v){this.value=v;}});let compositions=0,mips=0,directDraws=0,nativeDraws=0,rasterDraws=0,overlayDraws=0,skips=0;const uniforms=[];
 const context={gl:{flush(){},LESS:513,LEQUAL:515,ALWAYS:519,KEEP:7680,REPLACE:7681,GEQUAL:518,TEXTURE0:33984,TEXTURE1:33985,LINEAR:9729,NEAREST:9728,CLAMP_TO_EDGE:33071,LINEAR_MIPMAP_NEAREST:9985,LINEAR_MIPMAP_LINEAR:9987,TRIANGLES:4,drawingBufferWidth:1200,drawingBufferHeight:600},frameUniformBuffer:{pending:new Float32Array(16),upload(){}},viewport:slot([0,0,1200,600]),bindFramebuffer:slot(null),activeTexture:slot(0),clear(){},setDefault(){}};
 const nativeRender=E.NativePainter.prototype.renderLayer;
 const painter={drawFunctions:{...E.NativeDrawFunctions,background(){}},context,width:1200,height:600,renderLayer(...args){const layer=args[2];if(layer.id==='photo')rasterDraws++;if(layer.id==='route')overlayDraws++;return nativeRender.apply(this,args);},acquireRTT:size=>{compositions++;return{size,texture:{bind(){},generateMipmap(){mips++;},destroy(){}}};},bindRTT:t=>context.bindFramebuffer.set(t),releaseRTT(){},markTerrainDepthDirty(){},patternAtlas:{isPatternMissing:()=>false}};map.painter=painter;
 const scene={terrain:null,style:{...style,off(){},on(){},_checkLoaded(){},triggerSymbolPlacement(){}},painter,_camera:{setTerrain(){}},fire(){}};
 E.Map.prototype.setTerrain.call(scene,{source:'dem',exaggeration:1});const terrain=scene.terrain;map.terrain=terrain;terrain.getMinMaxElevation=()=>({minElevation:0,maxElevation:4000});terrain.tileManager.update(tr,terrain);
 terrain.getTerrainData=()=>({});terrain.getTerrainMesh=()=>({vertexBuffer:{},indexBuffer:{},segments:{}});
 const example=terrain.tileManager.getRenderableTiles()[0],ID=example.tileID.constructor,Tile=example.constructor;
 const id=new ID(10,0,10,995,644),tile=new Tile(id,256);tile.state='loaded';terrain.tileManager._tiles={[id.key]:tile};terrain.tileManager._renderableTilesKeys=[id.key];terrain.tileManager.getRenderableTiles=function(){return this._renderableTilesKeys.map(k=>this._tiles[k]);};
 const photoProps={'raster-opacity':1,'raster-resampling':'linear','raster-fade-duration':420,'raster-brightness-min':.058,'raster-brightness-max':1,'raster-saturation':-.25,'raster-contrast':.26,'raster-hue-rotate':0};
 const photo={id:'linz',type:'raster',source:'photo',isHidden:()=>false,isTileClipped:()=>false,paint:{get:k=>photoProps[k]},hasTransition:()=>false};
 const background={id:'background',type:'background',isHidden:()=>false,isTileClipped:()=>false,paint:{get:k=>k==='background-color'?{r:.7,g:.8,b:.9,a:1}:k==='background-opacity'?1:null}};
 const images=new Map();let coords=[id],revision=0;const imageManager={getSource:()=>({type:'raster'}),getState:()=>({revision}),getVisibleCoordinates:()=>coords,getTile:id=>images.get(id.key)};
 class Texture{constructor(){this.useMipmap=true;}bind(){}destroy(){}};
 function image(id){const t=new Tile(id,256);t.state='loaded';t.texture=new Texture();images.set(id.key,t);return t;}image(id);
 style._layers={background,linz:photo};style._order=['background','linz'];style.tileManagers.photo=imageManager;
 // Use the real native coordinate path so the adapter must handle cloned IDs.
 const rtt=painter.renderToTexture;
 const state=new E.NativeState({transform:tr,terrain,data:{moving:false,useSubdivision:false,isRenderingGlobe:false,anisotropicFilterPitch:20},context,projection:{getMeshFromTileID:()=>({vertexBuffer:{},indexBuffer:{},segments:{}})},programCache:{getProgram:({name})=>({draw(...args){if(name==='ridgeDirectTerrain'){directDraws++;uniforms.push(args[6]);}else if(name==='terrain')nativeDraws++;}})},currentPass:'translucent'});state.opaquePassCutoff=0;
 const {optimiseWeb}=await import(pathToFileURL(path.resolve('capacitor/scripts/optimise-web.mjs')));
 const residencyFile=path.resolve('public/native-residency.js'),moduleN=new Module(residencyFile,module);moduleN.filename=residencyFile;moduleN.paths=module.paths;
 moduleN._compile(optimiseWeb('native-residency.js',fs.readFileSync(residencyFile,'utf8')),residencyFile);
 const N=moduleN.exports;global.RidgeResidency=N;const drapes=N.retainDrapes(terrain.tileManager,painter,{bytes:8*1048576,readiness:()=>()=>true});assert.ok(drapes);
 const adapter=D.attach(map,{engine:E,enabled:true});
 function frame(){rtt.prepareForRender(style,tr.zoom,false);for(const key of style._order)rtt.renderLayer(style._layers[key],style,state);}

 // The actual RTT preparation turns an empty visible-coordinate list into no
 // entry for this terrain patch. A background plus valid Direct photo exists.
 const patchIDs=Array.from({length:24},(_,i)=>new ID(10,0,10,995+i%6,644+Math.floor(i/6)));
 const patches=patchIDs.map(q=>{const t=new Tile(q,256);t.state='loaded';image(q);return t;});
 for(const key of Object.keys(terrain.tileManager._tiles))delete terrain.tileManager._tiles[key];
 Object.assign(terrain.tileManager._tiles,Object.fromEntries(patches.map(t=>[t.tileID.key,t])));
 terrain.tileManager._renderableTilesKeys=patchIDs.map(q=>q.key);coords=patchIDs;
 rtt.rttSize=1025;
 const route={id:'route',type:'line',source:'route',isHidden:()=>false,isTileClipped:()=>false};
 style._layers.route=route;style._order.push('route');
 const routeTile=new Tile(patchIDs[0],256);routeTile.state='loaded';routeTile.buckets.route={isEmpty:()=>false};
 let routeCoords=[],routeRevision=0,actualRouteDraws=0;
 style.tileManagers.route={getSource:()=>({type:'geojson'}),getVisibleCoordinates:()=>routeCoords,
  getTile:q=>q.key===patchIDs[0].key?routeTile:undefined,getState:()=>({revision:routeRevision})};
 painter.drawFunctions.line=()=>{actualRouteDraws++;};
 frame();
 assert.equal(actualRouteDraws,0);
 assert.equal(directDraws,24);
 const first={allocations:compositions,mips,directDraws,actualRouteDraws};
 assert.equal(compositions,0);assert.equal(drapes.stats().preparedStackPatches,0);assert.equal(patches.filter(t=>t._ridgeDirectCoverage?.valid()).length,24);
 const before=compositions;frame();assert.equal(compositions,before);
 routeCoords=[patchIDs[0]];routeRevision++;frame();
 assert.equal(actualRouteDraws,1,'Later actual line must be composed');
 assert.ok(patches[0].getRTT(0));
 assert.equal(uniforms.at(-24).u_ridge_overlay,1);
 const afterLineArrival={allocations:compositions,mips,actualRouteDraws};
 const saved=patches[0].getRTT(0);routeCoords=[];routeRevision++;frame();
 assert.equal(patches[0].getRTT(0),saved,'Missing coordinates must preserve an existing overlay');
 routeCoords=[patchIDs[0]];routeTile.buckets.route={isEmpty:()=>true};routeRevision++;frame();
 assert.equal(patches[0].getRTT(0),undefined,'Known empty bucket must remove stale line pixels');
 console.log(JSON.stringify({mode:'implemented',first,
  afterLineArrival,retainedExistingOverlayWhenCoordinatesMissing:true,removedConfirmedEmptyOverlay:true,
  textureBytesPerPatch:Array.from({length:11},(_,i)=>4*Math.floor(1025/2**i)**2).reduce((a,b)=>a+b,0)},null,2));
 const beforeMode=directDraws;adapter.setEnabled(false);assert.equal(adapter.draw(painter,terrain,patches[1],state),true,'A committed Direct cover remains drawable before Original composition');assert.equal(directDraws,beforeMode+1);adapter.setEnabled(true);frame();
 // A non-draped separator must preserve normal imagery composition in every
 // stack, including a transition from previously committed zero-RTT Direct.
 style._layers.names={id:'names',type:'symbol',isHidden:()=>false};
 style._order=['background','linz','names','route'];
 rtt.prepareForRender(style,tr.zoom,false);
 assert.equal(adapter.stats().directPatches,0,'Multiple terrain stacks use native composition');
 assert.equal(adapter.skipLayer(rtt,patches[1],photo),false,'Base imagery is never omitted from composed stacks');
 assert.equal(adapter.skipTile(rtt,patches[1],0,['background','linz'],style),false);
 console.log(JSON.stringify({multipleStacksRetainNativeComposition:true}));
  adapter.destroy();drapes.destroy();terrain.tileManager.destruct();
})().catch(e=>{console.error(e);process.exitCode=1;});
