/* Explicit low-zoom LINZ overview. Encoded browser storage, not resident GPU tiles. */
(function(root){'use strict';
const LIMIT=64*1048576,NAME='ridgeline-nz-overview-v1',INDEX='/__ridgeline/nz-overview-index-v1';
const records=new Map();let chain=Promise.resolve(),epoch=0;
const abort=()=>Object.assign(Error('Overview cancelled'),{name:'AbortError'});
function eligible(url){try{const u=new URL(url);const q=u.hostname==='basemaps.linz.govt.nz'&&u.pathname.match(/^\/v1\/tiles\/(aerial|elevation)\/WebMercatorQuad\/(\d+)\/\d+\/\d+\.(webp|png)$/);return !!q&&+q[2]>=4&&+q[2]<=7;}catch{return false;}}
const disk=(async()=>{try{if(!root.caches)return null;const cache=await root.caches.open(NAME),index=await cache.match(INDEX);if(index){const rows=await index.json();for(const row of rows)if(eligible(row.url)&&Number.isFinite(row.until)&&Number.isFinite(row.bytes)&&row.bytes>=0)records.set(row.url,row);}return cache;}catch{return null;}})();
const ready=()=>new Promise(resolve=>{let done=false;const finish=value=>{if(done)return;done=true;root.clearTimeout(timer);resolve(value);},timer=root.setTimeout(()=>finish(null),2500);disk.then(finish,()=>finish(null));});
function has(value){try{const url=value.startsWith('ridge:')?root.RidgeTiles.networkURL(value):value;return (records.get(url)?.until||0)>Date.now();}catch{return false;}}
function hasTile(template,id){const q=id.canonical;return q.z<=7&&has(template.replaceAll('{z}',q.z).replaceAll('{x}',q.x).replaceAll('{y}',q.y));}
async function read(url,signal){
 const cache=await disk;if(!cache||signal?.aborted)return null;
 const r=await cache.match(url);if(!r||Number(r.headers.get('x-ridge-overview-until'))<=Date.now())return null;
 const h=new Headers(r.headers);h.set('Cache-Control','private, max-age='+Math.max(0,Math.floor((Number(h.get('x-ridge-overview-until'))-Date.now())/1000)));h.set('Age','0');return new Response(r.body,{headers:h});
}
function wrap(fetcher){return async(url,options={})=>{
 if(options.ridgeOriginal!==true||!eligible(url)||!has(url))return fetcher(url,options);
 if(options.signal?.aborted)throw abort();
 const cached=await new Promise((resolve,reject)=>{let done=false;const end=(error,value)=>{if(done)return;done=true;root.clearTimeout(timer);options.signal?.removeEventListener('abort',stop);error?reject(error):resolve(value);},stop=()=>end(abort()),timer=root.setTimeout(()=>end(null,null),16);options.signal?.addEventListener('abort',stop,{once:true});read(url,options.signal).then(value=>{if(done)value?.body?.cancel()?.catch(()=>{});else end(null,value);},()=>end(null,null));if(options.signal?.aborted)stop();});
 if(options.signal?.aborted)throw abort();return cached||fetcher(url,options);
};}
function save(url,result,signal){const generation=epoch;
 const operation=chain.catch(()=>{}).then(async()=>{
  if(signal?.aborted||generation!==epoch)throw abort();const cache=await ready();if(!cache)throw Error('Browser overview storage is unavailable');
  if(!eligible(url)||!result?.data)throw Error('Invalid overview tile');
  // A stale or non-cacheable response is usable by the live map, but must not
  // terminate the remaining overview downloads or be persisted as fresh.
  if(!(result.until>Date.now()))return{saved:false,reason:'cache-policy'};
  for(const [key,row]of records)if(row.until<=Date.now()){await cache.delete(key);records.delete(key);}
  const total=[...records.values()].reduce((sum,row)=>sum+row.bytes,0)-(records.get(url)?.bytes||0)+result.data.byteLength;
  if(total>LIMIT)throw Error('Overview storage limit reached; clear the saved overview and retry');
  if(signal?.aborted||generation!==epoch)throw abort();
  if((records.get(url)?.until||0)>Date.now()){const existing=await cache.match(url);if(existing){void existing.body?.cancel()?.catch(()=>{});return{saved:true};}}
  const h=new Headers(result.headers||[]);h.set('x-ridge-overview-until',String(result.until));h.set('Content-Length',String(result.data.byteLength));
  await cache.put(url,new Response(result.data,{headers:h}));records.set(url,{url,until:result.until,bytes:result.data.byteLength});
  await cache.put(INDEX,new Response(JSON.stringify([...records.values()]),{headers:{'Content-Type':'application/json'}}));return{saved:true};
 });chain=operation;return operation;
}
async function clear(){epoch++;await chain.catch(()=>{});const cache=await ready();if(!cache)throw Error('Browser overview storage is unavailable');for(const key of await cache.keys())await cache.delete(key);records.clear();}
function tiles(){const result=[];for(let z=4;z<=7;z++){const n=2**z,toY=lat=>Math.floor((1-Math.asinh(Math.tan(lat*Math.PI/180))/Math.PI)/2*n),west=Math.floor((165+180)/360*n),east=Math.min(n-1,Math.ceil((180+180)/360*n)-1);for(let y=toY(-33);y<=toY(-49);y++)for(let x=west;x<=east;x++)result.push({z,x,y});}return result;}
function attach(map,{cache,templates,engine=root.maplibregl,document=root.document}={}){
 const start=document.getElementById('nz-overview-start'),cancel=document.getElementById('nz-overview-cancel'),remove=document.getElementById('nz-overview-clear'),status=document.getElementById('nz-overview-status');
 let dead=false,running=false,generation=0,timer=null,queue=[],done=0,failed=0,skipped=0,total=0,active=0,clearing=false,templateKey='';const controllers=new Set();
 const say=text=>{if(status)status.textContent=text;};
 function controls(){if(start)start.disabled=running||clearing;if(cancel)cancel.disabled=!running;if(remove)remove.disabled=running||clearing;}
 const fill=(template,q)=>template.replaceAll('{z}',q.z).replaceAll('{x}',q.x).replaceAll('{y}',q.y);
 function available(){const q=engine.getImageRequestQueueStats?.();return !document.hidden&&root.navigator?.onLine!==false&&!root.navigator?.connection?.saveData&&!map.isMoving?.()&&map.areTilesLoaded?.()===true&&!(q?.queued>0)&&!cache.stats?.().pressureConstrained;}
 function schedule(){if(timer!==null||!running||dead)return;timer=root.setTimeout(()=>{timer=null;pump();},400);}
 function progress(){say(`${done}/${total} overview tiles saved · ${active} loading${skipped?' · '+skipped+' not cacheable':''}${failed?' · '+failed+' failed':''}`);}
 function pump(){
  if(dead||!running)return;if(JSON.stringify(templates())!==templateKey){stop();say('Imagery settings changed. Preload again for the current settings.');return;}if(!queue.length&&!active){running=false;controls();say(failed||skipped?`Overview finished: ${done}/${total} saved${skipped?', '+skipped+' not cacheable under provider policy':''}${failed?', '+failed+' failed; preload again to retry':''}.`:`New Zealand overview ready · ${total} imagery/elevation tiles saved · ${([...records.values()].reduce((n,r)=>n+r.bytes,0)/1048576).toFixed(1)} MiB`);return;}
  if(!available()){say(`${done}/${total} saved · paused for visible loading, movement, connectivity or data saver`);schedule();return;}
  while(active<2&&queue.length){const value=queue.shift();const controller=new AbortController(),current=generation;controllers.add(controller);active++;
   cache.preloadOverview(value,controller.signal).then(result=>{if(current===generation){if(result?.saved===false)skipped++;else done++;}},error=>{if(current===generation&&error.name!=='AbortError'){failed++;if(error.name==='QuotaExceededError'||/storage|quota/i.test(error.message)){running=false;say(error.name==='QuotaExceededError'?'Browser storage is full. Clear saved overview or free space, then retry.':error.message);controls();for(const c of controllers)c.abort();}}}).finally(()=>{controllers.delete(controller);if(current!==generation)return;active--;if(running){progress();schedule();}});
  }if(!queue.length&&!active)pump();else{progress();schedule();}
 }
 async function begin(){if(dead||running||clearing)return;running=true;controls();say('Opening overview storage…');const current=++generation;try{
  if(!await ready())throw Error('Browser overview storage is unavailable');if(dead||!running||current!==generation)return;
  const sources=templates();templateKey=JSON.stringify(sources);if(!sources.imagery||!sources.terrain)throw Error('LINZ overview sources are not ready');
  queue=tiles().flatMap(q=>[fill(sources.imagery,q),fill(sources.terrain,q)]);done=0;failed=0;skipped=0;active=0;total=queue.length;pump();
 }catch(error){if(current!==generation)return;running=false;controls();say(error.message);}}
 function stop(){generation++;running=false;if(timer!==null)root.clearTimeout(timer);timer=null;for(const c of controllers)c.abort();controllers.clear();active=0;queue=[];controls();say('Preload stopped · completed tiles remain saved');}
 async function erase(){if(running||clearing)return;clearing=true;controls();say('Clearing saved overview…');try{await clear();say('Saved overview cleared. Existing live map tiles may remain in memory.');}catch(error){say(error.message);}finally{clearing=false;controls();}}
 function destroy(){if(dead)return;stop();dead=true;start?.removeEventListener('click',begin);cancel?.removeEventListener('click',stop);remove?.removeEventListener('click',erase);map.off('remove',destroy);}
 start?.addEventListener('click',begin);cancel?.addEventListener('click',stop);remove?.addEventListener('click',erase);map.on('remove',destroy);controls();
 return{destroy};
}
root.RidgeNZOverview={wrap,save,has,hasTile,attach,tiles,ready};
})(globalThis);
