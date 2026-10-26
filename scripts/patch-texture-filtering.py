from pathlib import Path
import hashlib,json
p=Path('public/assets/maplibre-gl.mjs');s=p.read_text()
if 'RIDGE_TEXTURE_FILTERING_V1' not in s:
 old='this.useMipmap&&!(Ff(e)&&e.data===null)&&s.generateMipmap(s.TEXTURE_2D)'
 assert s.count(old)==1;s=s.replace(old,'this.useMipmap&&!(Ff(e)&&e.data===null)&&this.generateMipmap()')
 for name in ['f','this.context']:
  old=name+'.extTextureFilterAnisotropicMax'
  # Only values passed to texParameterf, not extension initialization.
  s=s.replace(old, '((globalThis.RidgeFiltering?.values().anisotropic===false||globalThis.RidgeFiltering?.values().mipmaps===false)?1:'+old+')')
 s+='\n/* RIDGE_TEXTURE_FILTERING_V1: expose colour texture policy; DEM samplers are untouched. */\nridgeDirectTerrainAPI.texture=If;\n';p.write_text(s)
s=p.read_text()
if 'RIDGE_TEXTURE_STATE_V1' not in s:
 # The raster path may have bound its parent C rather than S. Cache against
 # the texture actually bound, not the child whose fade is being evaluated.
 replacements=[
  ('p.texParameterf(p.TEXTURE_2D,f.extTextureFilterAnisotropic.TEXTURE_MAX_ANISOTROPY_EXT,((globalThis.RidgeFiltering?.values().anisotropic===false||globalThis.RidgeFiltering?.values().mipmaps===false)?1:f.extTextureFilterAnisotropicMax))',
   '(globalThis.RidgeFiltering?.setAnisotropy?globalThis.RidgeFiltering.setAnisotropy(C?C.texture:S.texture,f.extTextureFilterAnisotropicMax):p.texParameterf(p.TEXTURE_2D,f.extTextureFilterAnisotropic.TEXTURE_MAX_ANISOTROPY_EXT,f.extTextureFilterAnisotropicMax))'),
  ('t.texParameterf(t.TEXTURE_2D,this.context.extTextureFilterAnisotropic.TEXTURE_MAX_ANISOTROPY_EXT,((globalThis.RidgeFiltering?.values().anisotropic===false||globalThis.RidgeFiltering?.values().mipmaps===false)?1:this.context.extTextureFilterAnisotropicMax))',
   '(globalThis.RidgeFiltering?.setAnisotropy?globalThis.RidgeFiltering.setAnisotropy(n.texture,this.context.extTextureFilterAnisotropicMax):t.texParameterf(t.TEXTURE_2D,this.context.extTextureFilterAnisotropic.TEXTURE_MAX_ANISOTROPY_EXT,this.context.extTextureFilterAnisotropicMax))'),
 ]
 old,new=replacements[0];assert s.count(old)==1;s=s.replace(old,new)
 old,new=replacements[1];assert s.count(old)==2;s=s.replace(old,new,1);s=s.replace(old,new.replace('setAnisotropy(n.texture,','setAnisotropy(r,'),1)
 s+='\n/* RIDGE_TEXTURE_STATE_V1: shared per-texture anisotropy state; native parent and RTT bindings included. */\n';p.write_text(s)
meta=Path('public/assets/maplibre-provenance.json');d=json.loads(meta.read_text());d['files']['maplibre-gl.mjs']=hashlib.sha256(p.read_bytes()).hexdigest();d['patches']=[x for x in d['patches'] if x['id'] not in ['RIDGE_TEXTURE_FILTERING_V1','RIDGE_TEXTURE_STATE_V1']]+[{'id':'RIDGE_TEXTURE_FILTERING_V1','script':'scripts/patch-texture-filtering.py','scope':'Runtime colour-texture filtering controls; deferred mip generation and anisotropy policy.'},{'id':'RIDGE_TEXTURE_STATE_V1','script':'scripts/patch-texture-filtering.py','scope':'Shared texture anisotropy cache, including native parent imagery and recycled terrain targets.'}];meta.write_text(json.dumps(d,indent=2)+'\n')
