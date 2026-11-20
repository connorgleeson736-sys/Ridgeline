"""Pinned 6.14.0 hooks: cache ideal demand while reconciling live loaded coverage."""
from pathlib import Path
import hashlib,json
p=Path('public/assets/maplibre-gl.mjs');s=p.read_text()
if 'RIDGE_LATENCY_AUDIT_V1' not in s:
 def replace(a,b):
  global s
  assert s.count(a)==1,(a[:100],s.count(a));s=s.replace(a,b)
 helper='''/* RIDGE_LATENCY_AUDIT_V1 */
function ridgeIdealFor(owner,view,terrain,make){
 const source=owner._source||owner.tileManager?._source||owner,previous=owner._ridgeIdeal;
 const key=[view.width,view.height,view.elevation,owner.tileSize,owner.minzoom,owner.maxzoom,source.minzoom,source.maxzoom,source.tileSize,owner.used,owner.usedForTerrain,terrain?.exaggeration].join('/');
 const world=512*2**view.zoom,centre=view.center;
 const safe=previous&&Math.abs(view.zoom-previous.zoom)<.25&&Math.abs(view.pitch-previous.pitch)<5&&Math.abs(view.bearing-previous.bearing)<10&&Math.abs(centre.lng-previous.lng)/360*world<view.width*.15&&Math.abs(centre.lat-previous.lat)/360*world/Math.max(.1,Math.cos(centre.lat*Math.PI/180))<view.height*.15;
 if(owner._ridgeReuseIdeal&&safe&&previous.key===key&&previous.terrain===terrain&&previous.source===source&&previous.projection===view.projection)return previous.ids.slice();
 const ids=make();owner._ridgeIdeal={ids:ids.slice(),key,terrain,source,projection:view.projection,zoom:view.zoom,pitch:view.pitch,bearing:view.bearing,lng:centre.lng,lat:centre.lat};return ids;
}
'''
 s=helper+s
 start=s.index('let n;!this.used&&!this.usedForTerrain?n=[]:');end=s.index(';let r=n.length===0',start)
 expr=s[start+6:end]
 replace(s[start:end], 'let n=ridgeIdealFor(this,e,t,()=>{let n;'+expr+';return n})')
 replace('for(let i of xE(e,{tileSize:this.tileSize,minzoom:this.minzoom,maxzoom:this.maxzoom,reparseOverscaled:!1,terrain:t}))','for(let i of ridgeIdealFor(this,e,t,()=>xE(e,{tileSize:this.tileSize,minzoom:this.minzoom,maxzoom:this.maxzoom,reparseOverscaled:!1,terrain:t})))')
 replace('getMinMaxElevation(e){let t=this.tileManager.getSourceTile(e,!0),n=', 'getMinMaxElevation(e,t){if(arguments.length<2)t=this.tileManager.getSourceTile(e,!0);let n=')
 p.write_text(s)
# These pinned AABB methods are read-only; quadrant() copies its bounds.
# Resolve/touch the DEM before consulting the volume cache, so native eviction
# order and every extrema mutation remain observable even on hits.
s=p.read_text()
if 'RIDGE_MERCATOR_BOUNDS_V1' not in s:
 a='let o=1<<e.z;return new sE([t+e.x/o,e.y/o,i],[t+(e.x+1)/o,(e.y+1)/o,a])}allowVariableZoom'
 b="let o=1<<e.z;if(r?.terrain){const terrain=r.terrain,cache=terrain._ridgeMercatorBounds||(terrain._ridgeMercatorBounds=new Map),key=[e.z,e.x,e.y,t,i,a].join('/');if(cache.has(key))return cache.get(key);const box=new sE([t+e.x/o,e.y/o,i],[t+(e.x+1)/o,(e.y+1)/o,a]);cache.set(key,box);if(cache.size>4096)cache.delete(cache.keys().next().value);return box;}return new sE([t+e.x/o,e.y/o,i],[t+(e.x+1)/o,(e.y+1)/o,a])}allowVariableZoom"
 assert s.count(a)==1
 s=s.replace(a,b);s='/* RIDGE_MERCATOR_BOUNDS_V1 */\n'+s;p.write_text(s)
meta=Path('public/assets/maplibre-provenance.json');d=json.loads(meta.read_text());d['files']['maplibre-gl.mjs']=hashlib.sha256(p.read_bytes()).hexdigest();d['patches']=[r for r in d['patches'] if r['id']!='RIDGE_LATENCY_AUDIT_V1']+[{'id':'RIDGE_LATENCY_AUDIT_V1','script':'scripts/patch-latency-audit.py','scope':'Separate cached ideal demand from live reconciliation; reuse resolved DEM for conservative bounds. Original pixels and shaders preserved.'}];meta.write_text(json.dumps(d,indent=2)+'\n')
