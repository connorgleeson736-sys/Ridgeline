"""Narrow, reproducible patch for the pinned MapLibre 6.14.0 bundle.
No source selection, image pixels, DEMs, matrices or shaders are changed.
"""
from pathlib import Path
import hashlib,json
p=Path('public/assets/maplibre-gl.mjs');s=p.read_text();upstream='244d36122c1398da3a8259817cc6d71e04321afe6404f32606b4b6c6cc4f26fb'
if 'RIDGE_SCHEDULER_V1' not in s and 'RIDGE_SCHEDULER_V2' not in s:
 assert hashlib.sha256(p.read_bytes()).hexdigest()==upstream,'Unexpected engine; rebase patch explicitly'
 def replace(old,new):
  global s
  assert s.count(old)==1,(old[:80],s.count(old));s=s.replace(old,new)
 replace('let Un;(function(e){let t,n,r,i;', 'let Un;/* RIDGE_SCHEDULER_V1: bounded ready-byte lane and passive stages. */(function(e){let t,n,r,i,cachedActive=0,cachedStreak=0,policy=null;const info=j=>{try{return policy?.(j.requestParameters)||{}}catch{return{}}},trace=(stage,j,extra={})=>globalThis.RidgeTileTrace?.url(stage,j.requestParameters.url,{attempt:j.ridgeAttempt,...extra});e.setPolicy=f=>{policy=typeof f===`function`?f:null;l()},e.pump=()=>l(),e.stats=()=>({active:n,cachedActive,queued:t.length,moving:a(),limit:a()?On.MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME:On.MAX_PARALLEL_IMAGE_REQUESTS,cachedReserve:policy?Math.min(2,Math.max(0,(a()?On.MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME:On.MAX_PARALLEL_IMAGE_REQUESTS)-1)):0});')
 replace('t.push(s),l()','s.ridgeAttempt=globalThis.RidgeTileTrace?.url(`native-image-queued`,e.url),t.push(s),l()')
 replace('c=async e=>{e.state=`running`;','c=async e=>{e.state=`running`;if(e.ridgeCached)cachedActive++;trace(`native-image-admitted`,e,{active:n+1,queued:Un.stats().queued});')
 replace('data:await s(t.data,i),cacheControl:t.cacheControl,expires:t.expires})','data:await(async()=>{trace(`decode-start`,e);const decoded=await s(t.data,i);trace(`decode-finished`,e);return decoded})(),cacheControl:t.cacheControl,expires:t.expires})')
 replace('finally{n--,l()}},l=()=>{let e=a()?On.MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME:On.MAX_PARALLEL_IMAGE_REQUESTS;for(let r=n;r<e&&t.length>0;r++){let e=t.shift();if(e.abortController.signal.aborted){r--;continue}c(e)}}', '''finally{n--;if(e.ridgeCached)cachedActive--;l()}},l=()=>{
 const limit=a()?On.MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME:On.MAX_PARALLEL_IMAGE_REQUESTS,reserve=policy?Math.min(2,Math.max(0,limit-1)):0,networkLimit=limit-reserve;
 for(let k=t.length-1;k>=0;k--)if(t[k].abortController.signal.aborted){const j=t.splice(k,1)[0];j.onError(new un(j.abortController.signal.reason))}
 while(t.length){let chosen=-1,best=Infinity,regular=-1,regularScore=Infinity;
 for(let k=0;k<t.length;k++){const j=t[k],v=info(j),ready=v.cached===true,score=Number.isFinite(v.priority)?v.priority:0;
 if(!ready&&n-cachedActive<networkLimit&&score<regularScore){regular=k;regularScore=score}
 if((n<limit&&(ready||n-cachedActive<networkLimit))&&score+(ready?-10000:0)<best){chosen=k;best=score+(ready?-10000:0)}}
 if(chosen<0)break;if(n<limit&&cachedStreak>=3&&regular>=0)chosen=regular;
 const j=t.splice(chosen,1)[0];j.ridgeCached=info(j).cached===true;cachedStreak=j.ridgeCached?cachedStreak+1:0;c(j)
 }}''')
 replace('this.needsFollowUpFrame=!1;let o=r||n,s=!1;for(let e of this._renderableTiles){let t=this._textureDifference(e);t!==`none`&&(t===`zoom`&&o||t===`visibleLayers`&&r?this.needsFollowUpFrame=!0:t===`zoom`||t===`sourceTiles`?(s?this.needsFollowUpFrame=!0:e.releaseRTT(this.painter),s=!0):e.releaseRTT(this.painter))}', '''this.needsFollowUpFrame=!1;let released=0;const budget=n?2:4,maxRefresh=Math.max(1,Math.min(n?2:4,Math.floor(budget/Math.max(.25,this._ridgeCostPerPatch||1))));this.ridgeRefreshBacklog=0;
 for(let e of this._renderableTiles){let t=this._textureDifference(e);if(t===`none`)continue;
 if(t===`zoom`||t===`sourceTiles`){this.ridgeRefreshBacklog++;if(released>=maxRefresh){this.needsFollowUpFrame=!0;continue}released++}e.releaseRTT(this.painter)}this.ridgeRefreshes=released;''')
 # Pump on moveend even if all old requests remain pending.
 replace('this.on(`moveend`,()=>this._update(!1))','this.on(`moveend`,()=>{Un.pump();this._update(!1)})')
 s+='\nexport const setImageRequestPolicy=Un.setPolicy,getImageRequestQueueStats=Un.stats,pumpImageRequestQueue=Un.pump;\n'
 p.write_text(s)
# Upgrade only the recorded queue; preserve native request/decode dependencies.
s=p.read_text()
a=min(s.index('let Un;'),s.index('/* RIDGE_SCHEDULER_V2') if '/* RIDGE_SCHEDULER_V2' in s else len(s)); b=s.index('var Wn=class',a)
s=s[:a]+Path(__file__).with_name('maplibre-image-queue.js').read_text()+s[b:]
s=s.replace('pumpImageRequestQueue=Un.pump;', 'pumpImageRequestQueue=Un.pump,notifyImageRequestBytesReady=Un.notifyReady;')
s=s.replace('notifyImageRequestBytesReady=Un.notifyReady;', 'notifyImageRequestBytesReady=Un.notifyReady,setImageDecodeLimit=Un.setDecodeLimit;')
p.write_text(s)
meta=Path('public/assets/maplibre-provenance.json');d=json.loads(meta.read_text());d.setdefault('upstream_files',dict(d['files']));d['files']['maplibre-gl.mjs']=hashlib.sha256(p.read_bytes()).hexdigest();d['patches']=[r for r in d.get('patches',[]) if r['id']!='RIDGE_SCHEDULER_V2']+[{'id':'RIDGE_SCHEDULER_V2','script':'scripts/patch-image-scheduler.py','scope':'Indexed image admission, adaptive ready reserve, optional tracing and bounded moving RTT refresh; no shaders or terrain data changes.'}];meta.write_text(json.dumps(d,indent=2)+'\n')
