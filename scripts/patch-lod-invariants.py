"""Reuse camera-invariant native LOD integrals; retain exact per-tile math."""
from pathlib import Path
import hashlib,json
p=Path('public/assets/maplibre-gl.mjs');meta=Path('public/assets/maplibre-provenance.json')
s=p.read_text();d=json.loads(meta.read_text())
assert hashlib.sha256(p.read_bytes()).hexdigest()==d['files'][p.name], 'Unexpected engine bytes'
old='function hE(e,t){return function(n,r,i,a,o){let s=2*((e-1)/At(Math.cos(Xt(Pm-o))/Math.cos(Xt(Pm)))-1),c=Math.acos(i/a),l=2*mE(s-1,0,Xt(o/2)),u=Math.min(Xt(Pm),c+Xt(o/2)),d=Math.min(u,c-Xt(o/2)),f=mE(s-1,d,u),p=Math.atan(r/i),m=Math.hypot(r,i),h=n;return h+=At(a/m/Math.max(.5,Math.cos(Xt(o/2)))),h+=s*At(Math.cos(p))/2,h-=At(Math.max(1,f/l/t))/2,h}}'
new="""function hE(e,t){/* RIDGE_LOD_INVARIANTS_V1 */
 let ready=false,lastFov,lastZ,lastRange,s,l,halfCos,correction;
 return function(n,r,i,a,o){
  const fovChanged=!ready||o!==lastFov;
  if(fovChanged){
   s=2*((e-1)/At(Math.cos(Xt(Pm-o))/Math.cos(Xt(Pm)))-1);
   l=2*mE(s-1,0,Xt(o/2));halfCos=Math.max(.5,Math.cos(Xt(o/2)));
  }
  if(fovChanged||i!==lastZ||a!==lastRange){
   const c=Math.acos(i/a),u=Math.min(Xt(Pm),c+Xt(o/2)),d=Math.min(u,c-Xt(o/2)),f=mE(s-1,d,u);
   correction=At(Math.max(1,f/l/t))/2;
   lastFov=o;lastZ=i;lastRange=a;ready=true;
  }
  const p=Math.atan(r/i),m=Math.hypot(r,i);let h=n;
  h+=At(a/m/halfCos);h+=s*At(Math.cos(p))/2;h-=correction;return h;
 };
}"""
if 'RIDGE_LOD_INVARIANTS_V1' not in s:
 assert s.count(old)==1, 'Pinned native LOD function changed; rebase explicitly'
 s=s.replace(old,new);p.write_text(s)
d['files'][p.name]=hashlib.sha256(p.read_bytes()).hexdigest()
if not any(x['id']=='RIDGE_LOD_INVARIANTS_V1' for x in d['patches']):d['patches'].append({'id':'RIDGE_LOD_INVARIANTS_V1','script':'scripts/patch-lod-invariants.py','scope':'Reuse native integrals only when FOV, vertical distance and range match exactly; preserve native arithmetic, per-tile distance, selection and detail.'})
meta.write_text(json.dumps(d,indent=2)+'\n')
