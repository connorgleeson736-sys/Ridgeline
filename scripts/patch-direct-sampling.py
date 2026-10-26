"""Guarded hooks for the pinned MapLibre 6.14.0 terrain renderer.
Original drawing stays unchanged unless a per-painter adapter is attached.
"""
from pathlib import Path
import hashlib,json
p=Path('public/assets/maplibre-gl.mjs');s=p.read_text()
if 'RIDGE_DIRECT_SAMPLING_V1' not in s:
 assert hashlib.sha256(p.read_bytes()).hexdigest()=='858ced2a69330ced330357248ab7bc90e9fc0772a222d1d265c37e5b0a4189f1','Unexpected engine: rebase patch explicitly'
 def replace(old,new):
  global s
  assert s.count(old)==1,(old[:100],s.count(old));s=s.replace(old,new)
 # Plans run after native source-to-terrain mapping, before RTT invalidation.
 replace('this.needsFollowUpFrame=!1;let released=0;', 'this.painter._ridgeDirectSampling?.prepare(this,e,t);this.needsFollowUpFrame=!1;let released=0;')
 replace('for(let d of n){let n=t.getTerrainMesh(d.tileID,i),f=e.renderToTexture.getTexture(d)', 'for(let d of n){if(e._ridgeDirectSampling?.draw(e,t,d,r))continue;let n=t.getTerrainMesh(d.tileID,i),f=e.renderToTexture.getTexture(d)')
 replace('if(this._rttTiles.push(r),r.getRTT(e))continue;', 'if(this._rttTiles.push(r),this.painter._ridgeDirectSampling?.skipTile(this,r,e,a,t)||r.getRTT(e))continue;')
 replace('for(let e of a){let a=t._layers[e],o=a.source?', 'for(let e of a){let a=t._layers[e];if(this.painter._ridgeDirectSampling?.skipLayer(this,r,a,e))continue;let o=a.source?')
 s+='''\n/* RIDGE_DIRECT_SAMPLING_V1: Original-path hooks and native shader registration. */
export const ridgeDirectTerrainAPI={
 version:'6.14.0',
 register(fragment,types){
  ZD.ridgeDirectTerrain=QD(fragment,ZD.terrain.vertexSource);
  Gj.ridgeDirectTerrain=(context,locations)=>{const values=MA(context,locations),constructors={float:Z,int:X,sampler2D:X,vec3:sd,vec4:cd};for(const[name,type]of Object.entries(types))values[name]=new constructors[type](context,locations[name]);return values;};
 },
 terrainUniformValues:FA,stencil:Jj.disabled,cull:Zj.backCCW,
 shader:name=>ZD[name]
};
'''
 p.write_text(s)
meta=Path('public/assets/maplibre-provenance.json');d=json.loads(meta.read_text());d['files']['maplibre-gl.mjs']=hashlib.sha256(p.read_bytes()).hexdigest();d['patches']=[r for r in d['patches'] if r['id']!='RIDGE_DIRECT_SAMPLING_V1']+[{'id':'RIDGE_DIRECT_SAMPLING_V1','script':'scripts/patch-direct-sampling.py','scope':'Opt-in direct raster sampling on native terrain; native camera, DEM, overlays and normal rendering fallback.'}];meta.write_text(json.dumps(d,indent=2)+'\n')
