"""Expose bounded index-buffer construction for the pinned native terrain.
Native vertex buffers, shaders, DEM decoding and elevation queries stay intact.
"""
from pathlib import Path
import hashlib, json
p = Path('public/assets/maplibre-gl.mjs')
s = p.read_text()
if 'RIDGE_ADAPTIVE_TERRAIN_V1' not in s:
    assert hashlib.sha256(p.read_bytes()).hexdigest() == '68aa1091faf1e3c59ff6a266be1bbd93050435f0ba625a67ded033db2e298d1e', 'Unexpected engine; rebase explicitly'
    s += '''
/* RIDGE_ADAPTIVE_TERRAIN_V1: shared native vertices, owned variant indices. */
const ridgeDEMGridRevisions=new WeakMap(),ridgeNativeBackfill=Lf.prototype.backfillBorder;
Lf.prototype.backfillBorder=function(...args){const result=ridgeNativeBackfill.apply(this,args);ridgeDEMGridRevisions.set(this,(ridgeDEMGridRevisions.get(this)||0)+1);return result;};
export const ridgeAdaptiveTerrainAPI={
 version:'6.14.0',
 revision:dem=>ridgeDEMGridRevisions.get(dem)||0,
 invalidate(dem){ridgeDEMGridRevisions.set(dem,(ridgeDEMGridRevisions.get(dem)||0)+1);},
 createMesh(context,base,triangles){
  const indices=new Uu();for(let i=0;i<triangles.length;i+=3)indices.emplaceBack(triangles[i],triangles[i+1],triangles[i+2]);
  const indexBuffer=context.createIndexBuffer(indices),segments=Ju.simpleSegment(0,0,base.segments.get()[0].vertexLength,indices.length);
  return{vertexBuffer:base.vertexBuffer,indexBuffer,segments,destroy(){segments.destroy();indexBuffer.destroy();}};
 }
};
'''
    p.write_text(s)
meta=Path('public/assets/maplibre-provenance.json')
d=json.loads(meta.read_text());d['files']['maplibre-gl.mjs']=hashlib.sha256(p.read_bytes()).hexdigest()
d['patches']=[x for x in d['patches'] if x['id']!='RIDGE_ADAPTIVE_TERRAIN_V1']+[{'id':'RIDGE_ADAPTIVE_TERRAIN_V1','script':'scripts/patch-adaptive-terrain.py','scope':'Native terrain index variants share native vertices and skirts; DEM border revisions invalidate runtime error metadata.'}]
meta.write_text(json.dumps(d,indent=2)+'\n')
