const assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),{pathToFileURL}=require('node:url');
(async()=>{
 global.window={requestAnimationFrame:fn=>setTimeout(fn,0),cancelAnimationFrame:clearTimeout};
 const source=fs.readFileSync('public/assets/maplibre-gl.mjs','utf8'),symbol=source.match(/var (\w+)=class\{constructor\(e\)\{this.currentLayer=0/)[1],painterSymbol=source.match(/,(\w+)=class e\{constructor\(e\)\{this.drawFunctions=/)[1];
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'ridge-direct-'));const file=path.join(dir,'native.mjs');fs.writeFileSync(file,source+`\nexport {${symbol} as NativeState,${painterSymbol} as NativePainter,${source.match(/this.drawFunctions=(\w+)/)[1]} as NativeDrawFunctions};\n`);
 let E;try{E=await import(pathToFileURL(file));}finally{fs.rmSync(dir,{recursive:true,force:true});}
 const D=require('../public/maplibre-direct-sampling.js');let tr;E.Style.prototype._setProjectionInternal.call({map:{migrateProjection:t=>tr=t},tileManagers:{},_globalState:{}},'mercator');tr.resize(1200,600);tr.setZoom(13);tr.setCenter(new E.LngLat(170.141,-43.595));tr.setPitch(58);
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
 const N=require('../public/native-residency.js');global.RidgeResidency=N;const drapes=N.retainDrapes(terrain.tileManager,painter,{bytes:8*1048576,readiness:()=>()=>true});assert.ok(drapes);
 const adapter=D.attach(map,{engine:E,enabled:true});
 function frame(){rtt.prepareForRender(style,tr.zoom,false);for(const key of style._order)rtt.renderLayer(style._layers[key],style,state);}
 frame();assert.equal(directDraws,1);assert.equal(compositions,0,'Pure imagery must bypass RTT allocation');assert.equal(nativeDraws,0);assert.equal(uniforms[0].u_ridge_count,1);assert.equal(uniforms[0].u_ridge_adjust[0],.058);
 const firstBuilds=adapter.stats().planBuilds;frame();assert.equal(compositions,0);assert.equal(directDraws,2);assert.equal(adapter.stats().planBuilds,firstBuilds,'Stable cloned coordinates should reuse the Direct plan');assert.ok(adapter.stats().planCacheHits>0);assert.equal(uniforms.at(-1).u_ridge_overlay,0);
 const originalTexture=images.get(id.key).texture;images.get(id.key).texture=new Texture();frame();assert.equal(adapter.stats().planBuilds,firstBuilds+1,'Replacing a texture on the same native tile must rebuild its plan');images.get(id.key).texture=originalTexture;
 // Finer images must cover the complete terrain patch. Four children sample
 // directly; one missing child falls back to normal native composition.
 coords=Array.from({length:4},(_,q)=>new ID(11,0,11,id.canonical.x*2+q%2,id.canonical.y*2+(q>>1)));coords.forEach(image);frame();assert.equal(uniforms.at(-1).u_ridge_count,4);
 coords=coords.slice(0,3);frame();assert.equal(adapter.stats().directPatches,0);assert.ok(nativeDraws>0);assert.ok(compositions>0);
 coords=[id];frame();assert.equal(adapter.stats().directPatches,1);
 // Route overlays retain the normal native RTT pass; only imagery/background
 // are omitted, and only the first drape stack is sampled directly.
 const route={id:'route',type:'line',source:'route',isHidden:()=>false,isTileClipped:()=>false};style._layers.route=route;style._order.push('route');
 let routeRevision=0;const routeTile=new Tile(id,256);routeTile.state='loaded';routeTile.buckets.route={isEmpty:()=>false};
 style.tileManagers.route={...imageManager,getSource:()=>({type:'geojson'}),getTile:q=>q.key===id.key?routeTile:undefined,getVisibleCoordinates:()=>[id],getState:()=>({revision:routeRevision})};
 const nativeDraw=painter.renderLayer;painter.renderLayer=(p,m,l,c,s)=>{if(l===route){overlayDraws++;return;}if(l===photo)rasterDraws++;return nativeDraw.call(painter,p,m,l,c,s);};tile.releaseRTT(painter);frame();assert.ok(overlayDraws>0);assert.equal(adapter.stats().directPatches,1);
 // Imagery arrival alone must not invalidate an unchanged overlay RTT.
 const overlay=tile.getRTT(0);revision++;frame();assert.equal(tile.getRTT(0),overlay);
 // Empty confirmed native buckets need neither allocation nor mip generation.
 const beforeEmpty=compositions,beforeEmptyMips=mips;routeTile.buckets.route={isEmpty:()=>true};routeRevision++;frame();assert.equal(tile.getRTT(0),undefined,'Old route pixels must be released when the overlay becomes empty');assert.equal(compositions,beforeEmpty);assert.equal(mips,beforeEmptyMips);assert.equal(uniforms.at(-1).u_ridge_overlay,0);
 frame();assert.equal(compositions,beforeEmpty,'Repeated empty frames must not allocate');
 // A later arrival must appear even when no RTT/fingerprint invalidation was needed.
 routeTile.buckets.route={isEmpty:()=>false};routeRevision++;frame();assert.ok(tile.getRTT(0));assert.equal(compositions,beforeEmpty+1);assert.equal(uniforms.at(-1).u_ridge_overlay,1);
 routeTile.buckets={};routeRevision++;frame();assert.equal(tile.getRTT(0),undefined,'A loaded source with no bucket is confirmed empty');
 routeTile.state='loading';routeRevision++;frame();assert.ok(tile.getRTT(0),'Pending source content must preserve conservative composition');routeTile.state='loaded';routeTile.buckets.route={isEmpty:()=>false};routeRevision++;
 photoProps['raster-contrast']=.5;frame();assert.equal(uniforms.at(-1).u_ridge_adjust[3],2,'Cached imagery plans must not freeze paint controls');photoProps['raster-contrast']=.26;
 // Wider-view preparation must bake only overlays, otherwise adopting its
 // opaque imagery RTT would hide newly sampled photographs.
 const C=require('../public/terrain-composition.js'),S=require('../public/terrain-streaming.js');
 imageManager._inViewTiles={getAllTiles:()=>[...images.values()]};imageManager._outOfViewCache={data:{}};
 style.tileManagers.route._inViewTiles={getAllTiles:()=>[routeTile]};style.tileManagers.route._outOfViewCache={data:{}};
 const candidate=new Tile(id,256);candidate.state='loaded';const beforeRaster=rasterDraws,beforeOverlay=overlayDraws;
 const prepared=C.compose({manager:terrain.tileManager,painter,tile:candidate,style,state,coverage:S.completeCoverage});
 assert.ok(prepared);assert.equal(candidate._ridgeDirectRaster,true);assert.equal(rasterDraws,beforeRaster,'Prewarming baked base imagery over the direct sampler');assert.ok(overlayDraws>beforeOverlay,'Prewarming dropped route overlays');
 adapter.setEnabled(false);frame();assert.equal(adapter.stats().directPatches,0);assert.ok(rasterDraws>0);
 const beforeEnable=adapter.stats().planBuilds;adapter.setEnabled(true);frame();assert.ok(adapter.stats().planBuilds>beforeEnable,'Re-enabling Direct must reset its plans');map.getProjection=()=>({type:'globe'});frame();assert.equal(adapter.stats().directPatches,0,'Globe must retain native imagery');delete map.getProjection;
 const shade={id:'shade',type:'hillshade',isHidden:()=>false};style._layers.shade=shade;style._order.push('shade');rtt.prepareForRender(style,tr.zoom,false);assert.equal(adapter.stats().directPatches,0,'Hillshade preserves its native blend path');style._order.pop();
 const beforeRestore=adapter.stats().planBuilds;map.painter={...painter};map.events.webglcontextrestored();assert.equal(painter._ridgeDirectSampling,undefined);assert.equal(map.painter._ridgeDirectSampling,adapter,'Context restoration lost the direct renderer');rtt.painter=map.painter;frame();assert.ok(adapter.stats().planBuilds>beforeRestore,'Restoration must rebuild plans');
 adapter.destroy();assert.equal(map.painter._ridgeDirectSampling,undefined);
 // Mapping and coverage across large zoom gaps and the bounded capacity.
 const fine=new ID(15,0,15,id.canonical.x*32+7,id.canonical.y*32+19),m=D.mapping(fine,id);assert.deepEqual(m.uv,[1/32,1/32,7/32,19/32]);
 assert.equal(D.mapping(id,new ID(10,0,10,0,0)),null);assert.equal(D.covers(id,[{id:fine}]),false);

 // Seven fine regions plus redundant ancestors must fit eight shader slots.
 const children=Array.from({length:4},(_,q)=>new ID(11,0,11,id.canonical.x*2+q%2,id.canonical.y*2+(q>>1)));
 const grand=Array.from({length:4},(_,q)=>new ID(12,0,12,children[0].canonical.x*2+q%2,children[0].canonical.y*2+(q>>1)));
 const redundant=[...grand,...children.slice(1),id,id.scaledTo(9)];redundant.forEach(image);
 const pruned=D.plan(tile,imageManager,redundant);assert.equal(pruned.length,7,'Covered ancestors forced a needless fallback');
 assert.equal(D.covers(id,pruned),true);assert.ok(pruned.every(r=>r.id.canonical.z>10));
 const fragment=D.shader(E.ridgeDirectTerrainAPI).fragment;
 assert.ok(fragment.includes('textureGrad(u_ridge_image0'));
 assert.ok(fragment.indexOf('dFdx(v_texture_pos)')<fragment.indexOf('if(u_ridge_count'),'Derivatives must be evaluated outside divergent tile branches');
 drapes.destroy();terrain.tileManager.destruct();console.log('PASS: native Direct draws, cached plans and replacement invalidation, empty overlay allocation/mip skips, content arrivals/removal, pending-data fallback, live colour controls, preparation, mode/globe/hillshade/context fallback and teardown.');
})().catch(e=>{console.error(e);process.exitCode=1;});
