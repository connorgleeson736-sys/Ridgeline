/* Colour textures only. Encoded DEM textures and height interpolation stay native. */
(function(root){'use strict';
const defaults={bilinear:true,mipmaps:true,trilinear:true,anisotropic:true,fade:true,colour:true},key='ridgeline-filtering-v1';let state={...defaults};
let textureStates=new WeakMap();
// Called only after the named texture is bound. All pinned-engine anisotropy
// writes use this path, including parent-image and recycled RTT bindings.
function setAnisotropy(texture,requested){const context=texture.context,extension=context.extTextureFilterAnisotropic;if(!extension)return;
 const gl=context.gl,value=state.anisotropic&&state.mipmaps?requested:1,handle=texture._ownedHandle??texture.texture,previous=textureStates.get(texture);
 if(previous?.context===context&&previous.handle===handle&&previous.value===value)return;
 gl.texParameterf(gl.TEXTURE_2D,extension.TEXTURE_MAX_ANISOTROPY_EXT,value);textureStates.set(texture,{context,handle,value});
}
try{const raw=JSON.parse(root.localStorage?.getItem(key)||'null');for(const k of Object.keys(defaults))if(typeof raw?.[k]==='boolean')state[k]=raw[k];}catch{}
function install(engine){const proto=engine?.ridgeDirectTerrainAPI?.texture?.prototype;if(!proto||proto._ridgeFiltering)return false;const bind=proto.bind,generate=proto.generateMipmap;
 proto.generateMipmap=function(){if(this.useMipmap&&!state.mipmaps){this._ridgeMipDirty=true;return;}this._ridgeMipDirty=false;return generate.call(this);};
 proto.bind=function(mag,wrap,min){const gl=this.context.gl;if(this.useMipmap){
  if(state.mipmaps&&this._ridgeMipDirty)this.generateMipmap();
  const smooth=state.bilinear&&mag!==gl.NEAREST;mag=smooth?gl.LINEAR:gl.NEAREST;
  const mipped=state.mipmaps&&[gl.LINEAR_MIPMAP_NEAREST,gl.LINEAR_MIPMAP_LINEAR,gl.NEAREST_MIPMAP_NEAREST,gl.NEAREST_MIPMAP_LINEAR].includes(min);
  const blend=state.trilinear&&(min===gl.LINEAR_MIPMAP_LINEAR||min===gl.NEAREST_MIPMAP_LINEAR);
  min=mipped?(smooth?(blend?gl.LINEAR_MIPMAP_LINEAR:gl.LINEAR_MIPMAP_NEAREST):(blend?gl.NEAREST_MIPMAP_LINEAR:gl.NEAREST_MIPMAP_NEAREST)):mag;
 }
 const result=bind.call(this,mag,wrap,min);
 if(this.useMipmap&&this.context.extTextureFilterAnisotropic){const off=!state.anisotropic||!state.mipmaps;if(off||this._ridgeAnisoDisabled)setAnisotropy(this,off?1:this.context.extTextureFilterAnisotropicMax);this._ridgeAnisoDisabled=off;}
 return result;};proto._ridgeFiltering=true;return true;
}
function attach({document=root.document,onChange=()=>{}}={}){const nodes=[],natural=document.getElementById('natural-colour');
 function persist(){try{root.localStorage?.setItem(key,JSON.stringify(state));}catch{}}
 function sync(){for(const k of Object.keys(defaults)){const el=document.getElementById(k==='colour'?'natural-colour':'filter-'+k);if(el)el.checked=state[k];}document.getElementById('filter-trilinear').disabled=!state.mipmaps;}
 for(const k of Object.keys(defaults)){const el=document.getElementById(k==='colour'?'natural-colour':'filter-'+k);if(!el)continue;
 const fn=()=>{state={...state,[k]:el.checked};persist();sync();onChange();};el.addEventListener('change',fn);nodes.push([el,fn]);}
 const reset=document.getElementById('filter-reset');const resetFn=()=>{state={...defaults};persist();sync();natural?.dispatchEvent(new Event('change'));onChange();};reset?.addEventListener('click',resetFn);
 sync();return{destroy(){for(const [el,fn]of nodes)el.removeEventListener('change',fn);reset?.removeEventListener('click',resetFn);}};
}
const api={values:()=>state,install,attach,setAnisotropy,resetTextureState(){textureStates=new WeakMap();}};root.RidgeFiltering=api;if(typeof module!=='undefined')module.exports=api;
})(globalThis);
