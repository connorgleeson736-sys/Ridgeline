"""Reuse exact native tile-local projection matrices with independent output ownership."""
from pathlib import Path
import hashlib,json
p=Path('public/assets/maplibre-gl.mjs');meta=Path('public/assets/maplibre-provenance.json')
s=p.read_text();d=json.loads(meta.read_text())
assert hashlib.sha256(p.read_bytes()).hexdigest()==d['files'][p.name], 'Unexpected engine bytes'
start=s.index('_getTerrainCoordsForRegularTile(e){');end=s.index('_getTerrainCoordsForTileRanges(',start)
old='_getTerrainCoordsForRegularTile(e){let t={};for(let n of this._renderableTilesKeys){let r=this._tiles[n].tileID,i=e.clone(),a=at();if(r.canonical.equals(e.canonical))ce(a,0,P,P,0,0,1);else if(r.canonical.isChildOf(e.canonical)){let t=r.canonical.z-e.canonical.z,n=r.canonical.x-(r.canonical.x>>t<<t),i=r.canonical.y-(r.canonical.y>>t<<t),o=P>>t;ce(a,0,o,o,0,0,1),j(a,a,[-n*o,-i*o,0])}else if(e.canonical.isChildOf(r.canonical)){let t=e.canonical.z-r.canonical.z,n=e.canonical.x-(e.canonical.x>>t<<t),i=e.canonical.y-(e.canonical.y>>t<<t),o=P>>t;ce(a,0,P,P,0,0,1),j(a,a,[n*o,i*o,0]),ee(a,a,[1/2**t,1/2**t,0])}else continue;i.terrainRttPosMatrix32f=new Float32Array(a),t[n]=i}return t}'
new='''_getTerrainCoordsForRegularTile(e){/* RIDGE_TERRAIN_MATRICES_V1 */
 const result={};
 // These matrices depend only on the relative canonical tile coordinates.
 // Camera matrices remain live. Each result gets its own ID and Float32Array.
 const cache=_ridgeRegularMatrixCache;
 for(const key of this._renderableTilesKeys){
  const r=this._tiles[key].tileID;let kind,dz=0,x=0,y=0;
  if(r.canonical.equals(e.canonical))kind=0;
  else if(r.canonical.isChildOf(e.canonical)){kind=1;dz=r.canonical.z-e.canonical.z;x=r.canonical.x-(r.canonical.x>>dz<<dz);y=r.canonical.y-(r.canonical.y>>dz<<dz);}
  else if(e.canonical.isChildOf(r.canonical)){kind=2;dz=e.canonical.z-r.canonical.z;x=e.canonical.x-(e.canonical.x>>dz<<dz);y=e.canonical.y-(e.canonical.y>>dz<<dz);}
  else continue;
  const pattern=kind+'/'+dz+'/'+x+'/'+y;let matrix=cache.get(pattern);
  if(!matrix){
   const a=at(),o=P>>dz;
   if(kind===0)ce(a,0,P,P,0,0,1);
   else if(kind===1){ce(a,0,o,o,0,0,1);j(a,a,[-x*o,-y*o,0]);}
   else{ce(a,0,P,P,0,0,1);j(a,a,[x*o,y*o,0]);ee(a,a,[1/2**dz,1/2**dz,0]);}
   matrix=new Float32Array(a);cache.set(pattern,matrix);
   if(cache.size>128)cache.delete(cache.keys().next().value);
  }
  const coordinate=e.clone();coordinate.terrainRttPosMatrix32f=new Float32Array(matrix);result[key]=coordinate;
 }
 return result;
}'''
if 'RIDGE_TERRAIN_MATRICES_V1' not in s:
 assert s[start:end]==old,'Pinned regular projection changed; rebase explicitly'
 s='const _ridgeRegularMatrixCache=new Map();\n'+s[:start]+new+s[end:];p.write_text(s)
d['files'][p.name]=hashlib.sha256(p.read_bytes()).hexdigest()
if not any(x['id']=='RIDGE_TERRAIN_MATRICES_V1' for x in d['patches']):d['patches'].append({'id':'RIDGE_TERRAIN_MATRICES_V1','script':'scripts/patch-terrain-matrices.py','scope':'Reuse at most 128 exact native tile-local projection matrices, preserving native arithmetic, fresh output ownership, camera transforms and custom tile-range projection.'})
meta.write_text(json.dumps(d,indent=2)+'\n')
