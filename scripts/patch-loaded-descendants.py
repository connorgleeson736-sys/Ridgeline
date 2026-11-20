"""Index native fallback-child candidates without changing selection or tile ordering."""
from pathlib import Path
import hashlib,json
p=Path('public/assets/maplibre-gl.mjs');meta=Path('public/assets/maplibre-provenance.json')
s=p.read_text();d=json.loads(meta.read_text())
assert hashlib.sha256(p.read_bytes()).hexdigest()==d['files'][p.name], 'Unexpected engine bytes'
old='_getLoadedDescendents(e){let t={};for(let n of this._inViewTiles.getAllTiles().filter(e=>e.hasData()))for(let r of e)n.tileID.isChildOf(r)&&(t[r.key]||=[],t[r.key].push(n));return t}'
new='''_getLoadedDescendents(e){/* RIDGE_DESCENDANT_INDEX_V1 */
 const result={};if(e.size===0)return result;
 // Small sets avoid index allocation. Both branches retain native ancestry.
 if(e.size<=4){for(const tile of this._inViewTiles.getAllTiles())if(tile.hasData())for(const parent of e)if(tile.tileID.isChildOf(parent))(result[parent.key]??=[]).push(tile);return result;}
 const levels=new Map();
 for(const parent of e){const q=parent.canonical;let row=levels.get(q.z);if(!row)levels.set(q.z,row=new Map());const key=parent.wrap+'/'+q.x+'/'+q.y;let bucket=row.get(key);if(!bucket)row.set(key,bucket=[]);bucket.push(parent);}
 for(const tile of this._inViewTiles.getAllTiles()){
  if(!tile.hasData())continue;const id=tile.tileID,q=id.canonical;
  for(const [z,row]of levels){if(z>q.z)continue;const shift=q.z-z,key=id.wrap+'/'+(q.x>>shift)+'/'+(q.y>>shift),candidates=row.get(key);if(!candidates)continue;
   for(const parent of candidates)if(id.isChildOf(parent))(result[parent.key]??=[]).push(tile);
  }
 }return result;
}'''
if 'RIDGE_DESCENDANT_INDEX_V1' not in s:
 assert s.count(old)==1,'Pinned fallback method changed; rebase explicitly'
 s=s.replace(old,new);p.write_text(s)
oldSelection='_retainLoadedChildren(t,n){let r=this._getLoadedDescendents(n),i=/* @__PURE__ */ new Set;for(let a of n){let n=r[a.key];if(!n?.length){i.add(a);continue}let o=a.overscaledZ+e.maxOverzooming,s=n.filter(e=>e.tileID.overscaledZ<=o);if(!s.length){i.add(a);continue}let c=Math.min(...s.map(e=>e.tileID.overscaledZ)),l=s.filter(e=>e.tileID.overscaledZ===c).map(e=>e.tileID);for(let e of l)t[e.key]=e;this._areDescendentsComplete(l,c,a.overscaledZ)||i.add(a)}return i}'
newSelection="""_retainLoadedChildren(t,n){/* RIDGE_CHILD_SELECTION_V1 */
 const descendants=this._getLoadedDescendents(n),pending=new Set();
 for(const parent of n){
  const rows=descendants[parent.key];if(!rows?.length){pending.add(parent);continue;}
  const ceiling=parent.overscaledZ+e.maxOverzooming;let nearest=Infinity;
  for(const tile of rows){const z=tile.tileID.overscaledZ;if(z<=ceiling&&z<nearest)nearest=z;}
  if(nearest===Infinity){pending.add(parent);continue;}
  const selected=[];for(const tile of rows)if(tile.tileID.overscaledZ===nearest){const id=tile.tileID;selected.push(id);t[id.key]=id;}
  if(!this._areDescendentsComplete(selected,nearest,parent.overscaledZ))pending.add(parent);
 }return pending;
}"""
if 'RIDGE_CHILD_SELECTION_V1' not in s:
 assert s.count(oldSelection)==1,'Pinned child-selection method changed; rebase explicitly'
 s=s.replace(oldSelection,newSelection);p.write_text(s)
d['files'][p.name]=hashlib.sha256(p.read_bytes()).hexdigest()
if not any(x['id']=='RIDGE_DESCENDANT_INDEX_V1' for x in d['patches']):d['patches'].append({'id':'RIDGE_DESCENDANT_INDEX_V1','script':'scripts/patch-loaded-descendants.py','scope':'Index native fallback-child candidates; retain native isChildOf, loaded order, requested detail and coverage checks.'})
meta.write_text(json.dumps(d,indent=2)+'\n')
