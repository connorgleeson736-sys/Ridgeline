/* MapLibre 6.14.0 composition-only adapter. Uses native terrain-coordinate
   transforms, fingerprints, layer shaders, targets and mipmaps. Never draws
   terrain to the canvas or modifies the camera/source selection. */
(function(root){
'use strict';
const eligible=new Set(['background','fill','line','raster','hillshade','color-relief']);
const sourceIndexes=new WeakMap();
function sourceView(manager,id,coverage){
 const source=manager.getSource(),stamp=manager._ridgeResidencyRevision,nativeRevision=manager.getState?.().revision;let snapshot=sourceIndexes.get(manager);
 if(!Number.isFinite(stamp)||snapshot?.stamp!==stamp||snapshot?.nativeRevision!==nativeRevision||snapshot?.source!==source){const tiles=new Map();
 for(const t of manager._inViewTiles?.getAllTiles?.()||[])tiles.set(t.tileID.key,t);
 for(const rows of Object.values(manager._outOfViewCache?.data||{}))for(const {value:t}of rows)if(!tiles.has(t.tileID.key))tiles.set(t.tileID.key,t);
 const ready=[...tiles.values()].filter(t=>t.hasData?.()&&(!['raster','raster-dem'].includes(source.type)||(source.type==='raster'?!!t.texture:!!t.dem)));
 snapshot={stamp,nativeRevision,source,ready};if(Number.isFinite(stamp))sourceIndexes.set(manager,snapshot);}
 const related=snapshot.ready.filter(t=>t.tileID.equals(id)||id.isChildOf(t.tileID)||t.tileID.isChildOf(id));
 // Prefer a complete child cover, otherwise a single nearest measured parent.
 // Never layer overlapping cached resolutions or freeze an incomplete atlas.
 const children=related.filter(t=>t.tileID.canonical.z>=id.canonical.z).sort((a,b)=>a.tileID.canonical.z-b.tileID.canonical.z),selected=[];
 for(const t of children)if(!selected.some(p=>t.tileID.equals(p.tileID)||t.tileID.isChildOf(p.tileID)))selected.push(t);
 let rows=coverage(selected.map(t=>t.tileID))(id)?selected:[];
 if(!rows.length){const parent=related.filter(t=>id.equals(t.tileID)||id.isChildOf(t.tileID)).sort((a,b)=>b.tileID.canonical.z-a.tileID.canonical.z)[0];if(parent)rows=[parent];}
 if(source.type==='raster'&&!coverage(rows.map(t=>t.tileID))(id))return null;
 // Native hillshade targets need a separate offscreen preparation pass. Only
 // reuse already prepared targets; do not invoke that pass speculatively.
 if(source.type==='raster-dem'&&rows.some(t=>!t.fbo||t.needsHillshadePrepare))return null;
 const lookup=new Map(rows.map(t=>[t.tileID.key,t]));
 return Object.assign(Object.create(manager),{getVisibleCoordinates:()=>rows.map(t=>t.tileID),getTile:q=>lookup.get(q.key),getTileByID:key=>lookup.get(key),getLoadedTile:q=>lookup.get(q.key)});
}
function estimate(style,state,size){
 let count=0,previous=false;
 for(const id of style._order){const layer=style._layers[id];if(layer.isHidden(state.transform.zoom))continue;
  if(layer.type==='custom')return Infinity;
  const draped=eligible.has(layer.type);if(draped&&!previous)count++;previous=draped;
 }return count>0&&count<=4?count*size*size*4*4/3:Infinity;
}
function compose({manager,painter,tile,style,state,coverage}){
 const context=painter.context,ubo=context?.frameUniformBuffer;
 if(!state||!ubo?.pending||ubo.pending.length!==16||typeof ubo.upload!=='function'||!context.bindFramebuffer?.get||!context.viewport?.get||typeof painter.bindRTT!=='function')return null;
 const zoom=state.transform.zoom,order=style._order,visible=order.filter(id=>!style._layers[id].isHidden(zoom));
 if(visible.some(id=>style._layers[id].type==='custom'))return null;
 const stacks=[];let stack=null;const views={};
 for(const id of visible){const layer=style._layers[id];if(!eligible.has(layer.type)){stack=null;continue;}
  if(!stack){stack=[];stacks.push(stack);}stack.push(id);
  if(layer.source&&!views[layer.source]){const native=style.tileManagers[layer.source];if(!native)return null;views[layer.source]=sourceView(native,tile.tileID,coverage);if(!views[layer.source])return null;}
 }
 if(!stacks.length||stacks.length>4)return null;
 const estimate=stacks.length*painter.renderToTexture.rttSize**2*4*4/3;
 const shadow=Object.assign(Object.create(manager),{_tiles:{[tile.tileID.key]:tile},_renderableTilesKeys:[tile.tileID.key]});
 // Native coordinate generation also initializes the terrain patch's own RTT
 // matrix, exactly as TerrainTileManager.update does for a visible patch.
 tile.tileID.terrainRttPosMatrix32f=shadow.getTerrainCoords(tile.tileID)[tile.tileID.key].terrainRttPosMatrix32f;
 const terrain=Object.assign(Object.create(painter.renderToTexture.terrain),{tileManager:shadow});
 const renderer=new painter.renderToTexture.constructor(painter,terrain);renderer.rttSize=painter.renderToTexture.rttSize;
 const projected=Object.assign(Object.create(style),{tileManagers:{...style.tileManagers,...views}});
 renderer.prepareForRender(projected,zoom,false);
 const draw=new state.constructor(state);draw.isRenderingToTexture=true;draw.currentPass='translucent';draw.opaquePassCutoff=0;
 draw.data={...state.data,moving:false};
 const uniforms=ubo.pending.slice(),framebuffer=context.bindFramebuffer.get(),viewport=context.viewport.get().slice();
 try{
  // Pinned FrameUBO: u_world_size is the vec2 at words 2–3. Same operation
  // as native RenderToTexture.renderLayer, restored even if a shader throws.
  ubo.pending[2]=ubo.pending[3]=renderer.rttSize;ubo.upload();
  for(let index=0;index<stacks.length;index++){
   renderer._ridgeCompositionStack=index;
   const target=tile.acquireRTT(painter,index,renderer.rttSize);painter.bindRTT(target);context.clear({color:{r:0,g:0,b:0,a:0},stencil:0});draw.invalidateTileClippingMasks();
   for(const id of stacks[index]){const layer=style._layers[id],coords=layer.source?renderer._coordsAscending[layer.source]?.[tile.tileID.key]||[]:[tile.tileID];
    if(painter._ridgeDirectSampling?.skipLayer(renderer,tile,layer))continue;
    context.viewport.set([0,0,renderer.rttSize,renderer.rttSize]);draw.currentLayer=order.indexOf(id);draw.renderTileClippingMasks(layer,coords);
    painter.renderLayer(painter,projected.tileManagers[layer.source],layer,coords,draw);
    if(layer.source&&renderer._rttFingerprints[layer.source]?.[tile.tileID.key])tile.rttFingerprint[layer.source]=renderer._rttFingerprints[layer.source][tile.tileID.key];
   }target.texture.generateMipmap();
  }
  context.gl.flush?.();
  const images=Object.values(views).filter(v=>v.getSource().type==='raster').map(v=>coverage(v.getVisibleCoordinates()));
  return{estimate,quality:root.RidgeResidency?.imageryQuality?.(tile,projected,renderer._coordsAscending)||null,zoomIndependent:root.RidgeResidency?.zoomIndependent?.(tile,projected,renderer._coordsAscending,zoom)===true,coverage:id=>images.length>0&&images.every(check=>check(id)),stacks:stacks.length};
 }finally{ubo.pending.set(uniforms);ubo.upload();context.setDefault();context.bindFramebuffer.set(framebuffer);context.viewport.set(viewport);}
}
const api={compose,sourceView,estimate,invalidateSource:manager=>sourceIndexes.delete(manager)};if(typeof module!=='undefined')module.exports=api;else root.RidgeTerrainComposition=api;
})(globalThis);
