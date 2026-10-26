const assert=require('node:assert/strict');
(async()=>{
const E=await import('../public/assets/maplibre-gl.mjs');const F=require('../public/filtering-controls.js');assert.equal(F.install(E),true);
const nodes=new Map();function node(id){if(!nodes.has(id))nodes.set(id,{checked:true,disabled:false,listeners:[],addEventListener(n,f){this.listeners.push(f)},removeEventListener(){},dispatchEvent(){this.listeners.forEach(f=>f())}});return nodes.get(id)}
F.attach({document:{getElementById:node}});const set=(k,value)=>{const n=node('filter-'+k);n.checked=value;n.dispatchEvent();};
const gl={LINEAR:1,NEAREST:2,LINEAR_MIPMAP_NEAREST:3,LINEAR_MIPMAP_LINEAR:4,NEAREST_MIPMAP_NEAREST:5,NEAREST_MIPMAP_LINEAR:6,TEXTURE_2D:7,TEXTURE_MAG_FILTER:8,TEXTURE_MIN_FILTER:9,TEXTURE_WRAP_S:10,TEXTURE_WRAP_T:11,bindTexture(){},texParameteri(){},texParameterf(t,p,v){this.aniso=v;this.anisoCalls=(this.anisoCalls||0)+1},generateMipmap(){this.generated=(this.generated||0)+1}};
const proto=E.ridgeDirectTerrainAPI.texture.prototype,t=Object.assign(Object.create(proto),{context:{gl,extTextureFilterAnisotropic:{TEXTURE_MAX_ANISOTROPY_EXT:12},extTextureFilterAnisotropicMax:16},useMipmap:true});
t.bind(gl.LINEAR,0,gl.LINEAR_MIPMAP_LINEAR);assert.equal(t.minFilter,4);
set('trilinear',false);t.bind(1,0,4);assert.equal(t.minFilter,3);
set('bilinear',false);t.bind(1,0,4);assert.equal(t.magFilter,2);assert.equal(t.minFilter,5);
set('mipmaps',false);t.generateMipmap();assert.equal(gl.generated,undefined);t.bind(1,0,4);assert.equal(t.minFilter,2);assert.equal(gl.aniso,1);assert.equal(node('filter-trilinear').disabled,true);
set('mipmaps',true);t.bind(1,0,4);assert.equal(gl.generated,1,'Re-enabling mips must rebuild skipped levels exactly once');t.bind(1,0,4);assert.equal(gl.generated,1);assert.equal(gl.aniso,16);
set('anisotropic',false);t.bind(1,0,4);assert.equal(gl.aniso,1);set('anisotropic',true);t.bind(1,0,4);assert.equal(gl.aniso,16);
F.setAnisotropy(t,16);const stableCalls=gl.anisoCalls;for(let i=0;i<100;i++){t.bind(1,0,4);F.setAnisotropy(t,16);}assert.equal(gl.anisoCalls,stableCalls,'Stable Direct/native texture use must not repeat anisotropy writes');
set('anisotropic',false);t.bind(1,0,4);const offCalls=gl.anisoCalls;for(let i=0;i<100;i++){t.bind(1,0,4);F.setAnisotropy(t,16);}assert.equal(gl.anisoCalls,offCalls,'Disabled filtering should write once, including native high-pitch calls');
set('anisotropic',true);t.bind(1,0,4);assert.equal(gl.aniso,16);
t._ownedHandle={};F.setAnisotropy(t,16);assert.equal(gl.anisoCalls,offCalls+2,'Reallocated GL handles must invalidate cached parameters');
F.resetTextureState();F.setAnisotropy(t,16);assert.equal(gl.anisoCalls,offCalls+3,'Restored contexts must reapply texture state');
const other=Object.assign(Object.create(proto),{context:t.context,useMipmap:true,_ownedHandle:{}});other.bind(1,0,4);F.setAnisotropy(other,16);assert.equal(gl.anisoCalls,offCalls+4,'Distinct textures need independent state');F.setAnisotropy(t,16);assert.equal(gl.anisoCalls,offCalls+4);
const dem=Object.assign(Object.create(proto),{context:{gl},useMipmap:false});dem.bind(1,0,1);assert.equal(dem.magFilter,1,'DEM interpolation must remain native');
node('filter-reset').dispatchEvent();assert.ok(Object.values(F.values()).every(v=>v===true));
console.log('PASS: native filter toggles, deferred mips, anisotropy caching across 100 repeated draws, disabled/native writes, handle/context invalidation, independent textures and DEM isolation.');
})().catch(e=>{console.error(e);process.exitCode=1});
