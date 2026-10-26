const assert=require('node:assert/strict'),N=require('../public/native-residency.js'),S=require('../public/terrain-streaming.js');
(async()=>{
 const E=await import('../public/assets/maplibre-gl.mjs');global.RidgeTerrainStreaming=S;
 let allocations=0,releases=0,revision=0;
 const painter={context:{},acquireRTT(size){return{size,texture:{id:++allocations}};},releaseRTT(){releases++;}};
 const source={_source:{tileSize:256,type:'raster-dem'},map:{painter},reload(){},update(){}};
 const map={terrain:null,style:{_checkLoaded(){},off(){},on(){},tileManagers:{terrain:source},_layers:{},triggerSymbolPlacement(){}},painter,_camera:{setTerrain(){}},fire(){}};
 E.Map.prototype.setTerrain.call(map,{source:'terrain',exaggeration:1});const m=map.terrain.tileManager;m.getSourceTile=()=>({dem:{}});
 const provider={getCenter:t=>t.center,getWrap:()=>0,allowVariableZoom:()=>false,allowWorldCopies:()=>false,distanceToTile2d:()=>0,getTileBoundingVolume({z,x,y}){return{intersectsFrustum:()=>x===0&&y===0?(z>=1?2:1):0};}};
 m.update({center:new E.LngLat(0,0),elevation:0,pitch:0,bearing:0,cameraToCenterDistance:500,worldSize:512,tileSize:512,maxZoom:19,zoom:1,fov:36.87,renderWorldCopies:false,getCameraFrustum:()=>({}),getClippingPlane:()=>null,getCoveringTilesDetailsProvider:()=>provider},map.terrain);
 const tile=m.getRenderableTiles()[0];assert.ok(tile);const q=tile.tileID,Tile=tile.constructor;
 const factory=Object.assign(new E.Evented(),{_layers:{},_order:[],_removedLayers:{},_globalState:{},_checkLoaded(){},_validate:()=>false,_validateLayer(){},_updateLayer(){},getLayer:E.Style.prototype.getLayer});
 for(const spec of [{id:'photo',type:'raster',source:'linz',paint:{'raster-opacity':1}},{id:'separator',type:'circle',source:'route'},{id:'route-line',type:'line',source:'route',paint:{'line-width':3}}])E.Style.prototype.addLayer.call(factory,spec);
 const bucket=new Tile(q,256);bucket.buckets={'route-line':{}};
 const photo={getSource:()=>({type:'raster'}),getState:()=>({revision}),getVisibleCoordinates:()=>m.getRenderableTiles().map(t=>t.tileID)},route={getSource:()=>({type:'geojson'}),getState:()=>({revision:0}),getVisibleCoordinates:()=>m.getRenderableTiles().map(t=>t.tileID),getTile:()=>bucket};
 const style={_order:['photo','separator','route-line'],_layers:factory._layers,tileManagers:{linz:photo,route}},rtt=painter.renderToTexture,state={context:painter.context,transform:{zoom:1}};
 // Keep native preparation and production stack metadata/invalidation, with GPU painting controlled.
 rtt.renderLayer=function(layer){const eligible=layer.type==='raster'||layer.type==='line',last=this._renderableLayerIds.at(-1)===layer.id;
  if(eligible){if(!this._prevRenderedToTexture)this._stacks.push([]);this._prevRenderedToTexture=true;this._stacks.at(-1).push(layer.id);if(!last)return true;}
  if(this._prevRenderedToTexture){const index=this._stacks.length-1;for(const t of this._renderableTiles)if(!t.getRTT(index)){t.acquireRTT(painter,index,512);for(const id of this._stacks[index]){const source=style._layers[id].source;t.rttFingerprint[source]=this._rttFingerprints[source][t.tileID.key];}}this._prevRenderedToTexture=eligible;}return eligible;
 };
 const cache=N.retainDrapes(m,painter,{readiness:()=>()=>true}),frame=zoom=>{state.transform.zoom=zoom;rtt.prepareForRender(style,zoom,false);for(const id of style._order)rtt.renderLayer(style._layers[id],style,state);};
 frame(1);const firstPhoto=tile.getRTT(0),firstRoute=tile.getRTT(1);assert.ok(firstPhoto&&firstRoute);const before=allocations;frame(1.1);assert.equal(tile.getRTT(0),firstPhoto,'Photographic target survives route-only zoom change');assert.notEqual(tile.getRTT(1),firstRoute,'Route target refreshes to retain pixel width');assert.equal(allocations-before,1);assert.equal(cache.stats().partialZoomStackReuses,1);
 revision++;frame(1.1);assert.notEqual(tile.getRTT(0),firstPhoto,'Real photo source revision still invalidates');
 const secondPhoto=tile.getRTT(0);factory._layers.photo.hasTransition=()=>true;frame(1.2);assert.notEqual(tile.getRTT(0),secondPhoto,'Active photo paint transition blocks reuse');
 cache.destroy();m.destruct();delete global.RidgeTerrainStreaming;console.log(JSON.stringify({photoPreservedAcrossOverlayZoom:true,overlayRefreshed:true,sourceRevisionAndTransitionsInvalidate:true,allocations,releases}));
})().catch(e=>{console.error(e);process.exitCode=1});
