"""Avoid temporary native tile IDs when only a DEM ancestor lookup key is needed."""
from pathlib import Path
import hashlib,json
p=Path('public/assets/maplibre-gl.mjs');meta=Path('public/assets/maplibre-provenance.json')
s=p.read_text();d=json.loads(meta.read_text())
assert hashlib.sha256(p.read_bytes()).hexdigest()==d['files'][p.name], 'Unexpected engine bytes'
old='getSourceTile(e,t){let n=this.tileManager._source,r=e.overscaledZ-this.deltaZoom;if(r>n.maxzoom&&(r=n.maxzoom),r<n.minzoom)return;this._sourceTileCache[e.key]||=e.scaledTo(r).key;let i=this.findTileInCaches(this._sourceTileCache[e.key]);if(!i?.dem&&t)for(;r>=n.minzoom&&!i?.dem;)i=this.findTileInCaches(e.scaledTo(r--).key);return i}'
new=old.replace('getSourceTile(e,t){','getSourceTile(e,t){/* RIDGE_DEM_PARENT_KEYS_V1 */').replace('e.scaledTo(r).key','e.calculateScaledKey(r,!0)').replace('e.scaledTo(r--).key','e.calculateScaledKey(r--,!0)')
if 'RIDGE_DEM_PARENT_KEYS_V1' not in s:
 assert s.count(old)==1,'Pinned DEM source lookup changed; rebase explicitly'
 s=s.replace(old,new);p.write_text(s)
d['files'][p.name]=hashlib.sha256(p.read_bytes()).hexdigest()
if not any(x['id']=='RIDGE_DEM_PARENT_KEYS_V1' for x in d['patches']):d['patches'].append({'id':'RIDGE_DEM_PARENT_KEYS_V1','script':'scripts/patch-dem-parent-keys.py','scope':'Use the pinned native calculateScaledKey for DEM ancestry lookups; preserve exact lookup keys/order, cache reads, min/max zoom and fallback behavior.'})
meta.write_text(json.dumps(d,indent=2)+'\n')
