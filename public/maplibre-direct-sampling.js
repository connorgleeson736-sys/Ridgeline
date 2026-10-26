/* Direct imagery on MapLibre's own terrain. Native camera, meshes, DEMs,
   raster selection, overlays, picking and loading remain authoritative. */
(function(root){'use strict';
const MAX_IMAGES=8;
function mapping(terrainID,imageID){
 const a=terrainID.canonical,b=imageID.canonical;if((terrainID.wrap||0)!==(imageID.wrap||0))return null;
 const scale=2**(b.z-a.z),x=a.x*scale-b.x,y=a.y*scale-b.y;
 const clip=[Math.max(0,-x/scale),Math.max(0,-y/scale),Math.min(1,(1-x)/scale),Math.min(1,(1-y)/scale)];
 return clip[2]>clip[0]&&clip[3]>clip[1]?{uv:[scale,scale,x,y],clip}:null;
}
function covers(id,rows){const c=id.canonical,ids=rows.map(r=>r.id.canonical),max=Math.max(c.z,...ids.map(q=>q.z));
 function visit(z,x,y){if(ids.some(q=>q.z<=z&&Math.floor(x/2**(z-q.z))===q.x&&Math.floor(y/2**(z-q.z))===q.y))return true;
  if(z>=max||!ids.some(q=>q.z>z&&Math.floor(q.x/2**(q.z-z))===x&&Math.floor(q.y/2**(q.z-z))===y))return false;
  for(let i=0;i<4;i++)if(!visit(z+1,x*2+i%2,y*2+(i>>1)))return false;return true;
 }return visit(c.z,c.x,c.y);
}
function plan(tile,manager,coords){
 const rows=[],seen=new Set();for(const id of coords||[]){if(seen.has(id.key))continue;seen.add(id.key);const image=manager.getTile(id),m=mapping(tile.tileID,id);if(image?.texture&&m)rows.push({id,image,...m});}
 rows.sort((a,b)=>b.id.canonical.z-a.id.canonical.z);
 // Fully covered ancestors contribute no pixels, but waste sampler slots and
 // can unnecessarily switch a patch back to composed rendering.
 const selected=[];for(const row of rows){const footprint=row.id.canonical.z>tile.tileID.canonical.z?row.id:tile.tileID;if(!covers(footprint,selected))selected.push(row);}
 // At most eight resident source textures; preserve native composition if a
 // fragmented mixed-resolution cover exceeds the bounded shader capacity.
 if(!selected.length||selected.length>MAX_IMAGES||!covers(tile.tileID,selected))return null;return selected;
}
// Validate identities without rebuilding mappings, sorting or walking coverage.
// Native coordinates may be fresh clones each frame, so compare their keys.
function cachedPlan(cache,tile,manager,coords,stats){
 const ids=coords||[],previous=cache.get(tile);
 if(previous?.manager===manager&&previous.key===tile.tileID.key&&previous.ids.length===ids.length){
  let same=true;for(let i=0;i<ids.length;i++){const image=manager.getTile(ids[i]);if(previous.ids[i]!==ids[i].key||previous.images[i]!==image||previous.textures[i]!==image?.texture){same=false;break;}}
  if(same){stats.planCacheHits++;return previous.rows;}
 }
 const rows=plan(tile,manager,ids);cache.set(tile,{manager,key:tile.tileID.key,ids:ids.map(id=>id.key),images:ids.map(id=>manager.getTile(id)),textures:ids.map(id=>manager.getTile(id)?.texture),rows});stats.planBuilds++;return rows;
}
// Only native line/fill buckets with known empty data may omit a drape.
// Missing, loading or unfamiliar resources keep the normal composition path.
function emptyOverlay(rtt,tile,layers,style,base){
 for(const id of layers){const layer=style._layers[id];if(layer===base||layer?.type==='background')continue;
  if(!layer||!['line','fill'].includes(layer.type))return false;
  const manager=style.tileManagers[layer.source],coords=rtt._coordsAscending[layer.source]?.[tile.tileID.key];
  if(!manager||!Array.isArray(coords))return false;
  for(const coordinate of coords){const source=manager.getTile(coordinate);if(source?.state!=='loaded'||typeof source.getBucket!=='function')return false;
   const bucket=source.getBucket(layer);if(bucket&&(typeof bucket.isEmpty!=='function'||bucket.isEmpty()!==true))return false;
  }
 }return true;
}
function shader(api){
 const types={u_ridge_count:'int',u_ridge_overlay:'int',u_ridge_background:'vec4',u_ridge_spin:'vec3',u_ridge_adjust:'vec4'};
 let declarations='uniform int u_ridge_count;uniform int u_ridge_overlay;uniform vec4 u_ridge_background;uniform vec3 u_ridge_spin;uniform vec4 u_ridge_adjust;';
 let sample='vec2 ridgeDx=dFdx(v_texture_pos),ridgeDy=dFdy(v_texture_pos);vec4 photo=vec4(0.);';
 for(let i=0;i<MAX_IMAGES;i++){
  for(const [suffix,type]of [['image','sampler2D'],['uv','vec4'],['clip','vec4']]){const name=`u_ridge_${suffix}${i}`;types[name]=type;declarations+=`uniform ${type} ${name};`;}
  sample+=`${i?'else ':''}if(u_ridge_count>${i} && all(greaterThanEqual(v_texture_pos,u_ridge_clip${i}.xy)) && all(lessThanEqual(v_texture_pos,u_ridge_clip${i}.zw)))photo=textureGrad(u_ridge_image${i},clamp(v_texture_pos*u_ridge_uv${i}.xy+u_ridge_uv${i}.zw,0.,1.),ridgeDx*u_ridge_uv${i}.xy,ridgeDy*u_ridge_uv${i}.xy);`;
 }
 const helper=`vec4 ridgeSurface(){${sample}
 if(photo.a>0.)photo.rgb/=photo.a;
 vec3 rgb=vec3(dot(photo.rgb,u_ridge_spin.xyz),dot(photo.rgb,u_ridge_spin.zxy),dot(photo.rgb,u_ridge_spin.yzx));
 float average=(photo.r+photo.g+photo.b)/3.;rgb+=(average-rgb)*u_ridge_adjust.z;
 rgb=(rgb-.5)*u_ridge_adjust.w+.5;
 photo=clamp(vec4(mix(vec3(u_ridge_adjust.x),vec3(u_ridge_adjust.y),rgb)*photo.a,photo.a),0.,1.);
 vec4 base=photo+u_ridge_background*(1.-photo.a);
 if(u_ridge_overlay==0)return base;
 vec4 overlay=texture(u_texture,vec2(v_texture_pos.x,1.-v_texture_pos.y));
 return overlay+base*(1.-overlay.a);}`;
 const original=api.shader('terrain').fragmentSource;
 const needle=/vec4 surface_color\s*=\s*texture\(u_texture,\s*vec2\(v_texture_pos.x,\s*1.0\s*-\s*v_texture_pos.y\)\);/;
 if(!needle.test(original))throw Error('Unsupported MapLibre terrain shader');
 const fragment=(declarations+original).replace('void main()',helper+'void main()').replace(needle,'vec4 surface_color=ridgeSurface();');
 api.register(fragment,types);return{fragment,types};
}
const registered=new WeakSet();
function attach(map,{engine=root.maplibregl,enabled=false}={}){
 const api=engine?.ridgeDirectTerrainAPI;let painter=map.painter;
 if(!api||api.version!=='6.14.0'||!painter)return{setEnabled(){},destroy(){},stats:()=>({supported:false})};
 if(!registered.has(api)){shader(api);registered.add(api);}
 let on=!!enabled,dead=false,frames=new WeakMap(),planCache=new WeakMap(),transparent=null;
 const stats={supported:true,enabled:on,directPatches:0,composedPatches:0,sourceTextures:0,planBuilds:0,planCacheHits:0,emptyOverlaySkips:0};
 function invalidate(){frames=new WeakMap();planCache=new WeakMap();map.terrain?.tileManager?.releaseAllRTT();map.triggerRepaint();}
 function prepare(rtt,style,zoom){
  if(!on)return;
  const plans=new Map();frames.set(rtt,{plans,base:null,background:[0,0,0,0]});
  const state=frames.get(rtt),visible=rtt._renderableLayerIds.map(id=>style._layers[id]);
  const images=visible.filter(l=>l.type==='raster'),base=images[0],background=visible.find(l=>l.type==='background');
  const eligible=on&&map.getProjection?.()?.type!=='globe'&&images.length===1&&['linz','satellite','topo'].includes(base?.id)&&style.tileManagers[base.source]?.getSource()?.type==='raster'&&base.paint.get('raster-opacity')===1&&!base.hasTransition?.()&&!visible.some(l=>['hillshade','color-relief','custom'].includes(l.type))&&(!background||!background.paint.get('background-pattern'))&&visible.slice(0,visible.indexOf(base)).every(l=>l.type==='background');
  const manager=eligible?style.tileManagers[base.source]:null;
  if(eligible){state.base=base;const colour=background?.paint.get('background-color'),opacity=background?.paint.get('background-opacity')??1;if(colour)state.background=[colour.r*opacity,colour.g*opacity,colour.b*opacity,colour.a*opacity];
   const saturation=base.paint.get('raster-saturation'),contrast=base.paint.get('raster-contrast'),angle=base.paint.get('raster-hue-rotate')*Math.PI/180,s=Math.sin(angle),c=Math.cos(angle);
   state.spin=[(2*c+1)/3,(-Math.sqrt(3)*s-c+1)/3,(Math.sqrt(3)*s-c+1)/3];
   state.adjust=[base.paint.get('raster-brightness-min'),base.paint.get('raster-brightness-max'),saturation>0?1-1/(1.001-saturation):-saturation,contrast>0?1/(1-contrast):1+contrast];
  }
  for(const tile of rtt._renderableTiles){const rows=manager?cachedPlan(planCache,tile,manager,rtt._coordsAscending[base.source]?.[tile.tileID.key],stats):null;
   if(!!rows!==!!tile._ridgeDirectRaster){tile.releaseRTT(painter);tile._ridgeDirectRaster=!!rows;}
   if(rows){plans.set(tile,rows);
    // Overlay RTTs do not depend on imagery arrivals; native fingerprints
    // still invalidate them for overlay source, style and zoom changes.
    if(rtt._rttFingerprints[base.source])delete rtt._rttFingerprints[base.source][tile.tileID.key];
   }
  }
  if(rtt===painter.renderToTexture){stats.directPatches=plans.size;stats.composedPatches=rtt._renderableTiles.length-plans.size;stats.sourceTextures=[...plans.values()].reduce((n,rows)=>n+rows.length,0);}
 }
 function skipLayer(rtt,tile,layer){const state=frames.get(rtt);return (rtt._ridgeCompositionStack??rtt._stacks.length-1)===0&&state?.plans.has(tile)&&(layer===state.base||layer.type==='background');}
 function skipTile(rtt,tile,index,layers,style){const state=frames.get(rtt);if(index!==0||!state?.plans.has(tile)||!emptyOverlay(rtt,tile,layers,style,state.base))return false;
  // Drop stale overlay pixels when content becomes empty, without releasing
  // unrelated later stacks. The next nonempty bucket gets a fresh RTT.
  const target=tile.getRTT(0);if(target){painter.releaseRTT(target);tile.rttObjects[0]=undefined;}
  for(const id of layers){const source=style._layers[id]?.source,fingerprint=source&&rtt._rttFingerprints[source]?.[tile.tileID.key];if(fingerprint)tile.rttFingerprint[source]=fingerprint;}
  stats.emptyOverlaySkips++;return true;
 }
 function draw(p,terrain,tile,frame){
  const rtt=p.renderToTexture,state=frames.get(rtt),rows=state?.plans.get(tile);
  if(!on||rtt._stacks.length!==1||!rows||frame.data.isRenderingGlobe)return false;
  const context=frame.context,gl=context.gl,base=state.base,program=frame.useProgram('ridgeDirectTerrain');
  let overlay=tile.getRTT(0)?.texture;
  if(!overlay){transparent??=new rows[0].image.texture.constructor(context,{width:1,height:1,data:new Uint8Array(4)},gl.RGBA);overlay=transparent;}
  context.activeTexture.set(gl.TEXTURE0);overlay.bind(gl.LINEAR,gl.CLAMP_TO_EDGE,gl.LINEAR_MIPMAP_LINEAR);
  const tr=frame.transform,values=api.terrainUniformValues(terrain.getSkirtLength(tr.zoom),tr.calculateFogMatrix(tile.tileID.toUnwrapped()),frame.data.sky,tr.pitch,false);
  values.u_ridge_count=rows.length;values.u_ridge_overlay=tile.getRTT(0)?.texture?1:0;values.u_ridge_background=state.background;
  values.u_ridge_spin=state.spin;values.u_ridge_adjust=state.adjust;
  const filter=base.paint.get('raster-resampling')==='nearest'?gl.NEAREST:gl.LINEAR;
  for(let i=0;i<MAX_IMAGES;i++){
   const row=rows[i]||rows[0];if(i<rows.length){context.activeTexture.set(gl.TEXTURE0+4+i);row.image.texture.bind(filter,gl.CLAMP_TO_EDGE,filter===gl.NEAREST?gl.NEAREST:gl.LINEAR_MIPMAP_LINEAR);
    if(row.image.texture.useMipmap&&context.extTextureFilterAnisotropic&&tr.pitch>frame.data.anisotropicFilterPitch){if(root.RidgeFiltering?.setAnisotropy)root.RidgeFiltering.setAnisotropy(row.image.texture,context.extTextureFilterAnisotropicMax);else gl.texParameterf(gl.TEXTURE_2D,context.extTextureFilterAnisotropic.TEXTURE_MAX_ANISOTROPY_EXT,context.extTextureFilterAnisotropicMax);}
   }
   values['u_ridge_image'+i]=i<rows.length?4+i:4;values['u_ridge_uv'+i]=row.uv;values['u_ridge_clip'+i]=row.clip;
  }
  const mesh=terrain.getTerrainMesh(tile.tileID,false),data=terrain.getTerrainData(tile.tileID),projection=frame.getProjectionDataForTile(tile.tileID,{applyTerrainMatrix:false});
  program.draw(context,gl.TRIANGLES,frame.getDepthModeFor3D(),api.stencil,frame.colorModeForRenderPass(),api.cull,values,data,projection,'terrain',mesh.vertexBuffer,mesh.indexBuffer,mesh.segments);return true;
 }
 const controller={prepare,skipLayer,skipTile,draw,setEnabled(value){if(dead||on===!!value)return;on=!!value;stats.enabled=on;if(!on){stats.directPatches=0;stats.composedPatches=0;stats.sourceTextures=0;for(const tile of Object.values(map.terrain?.tileManager?._tiles||{}))tile._ridgeDirectRaster=false;}invalidate();},stats:()=>({...stats}),destroy};
 function restored(){transparent=null;frames=new WeakMap();planCache=new WeakMap();root.RidgeFiltering?.resetTextureState?.();if(painter._ridgeDirectSampling===controller)delete painter._ridgeDirectSampling;painter=map.painter;if(painter)painter._ridgeDirectSampling=controller;}
 function destroy(){if(dead)return;dead=true;if(painter._ridgeDirectSampling===controller)delete painter._ridgeDirectSampling;transparent?.destroy();transparent=null;frames=new WeakMap();planCache=new WeakMap();map.off('webglcontextrestored',restored);map.off('remove',destroy);}
 painter._ridgeDirectSampling=controller;map.on('webglcontextrestored',restored);map.on('remove',destroy);return controller;
}
const api={mapping,covers,plan,emptyOverlay,shader,attach};if(typeof module!=='undefined')module.exports=api;else root.RidgeDirectSampling=api;
})(globalThis);
