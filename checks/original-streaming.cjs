const assert=require('node:assert/strict'),fs=require('node:fs');
const P=require('../public/performance.js'),{createCache,networkURL}=require('../public/tile-cache.js');
(async()=>{
 const {Map:NativeMap,RasterDEMTileSource,addProtocol,removeProtocol}=await import('../public/assets/maplibre-gl.mjs');
 let now=1000,network=0;const wait=ms=>new Promise(r=>setTimeout(r,ms));
 const fetcher=async(url,{signal})=>{network++;await wait(8);if(signal.aborted)throw Object.assign(Error('aborted'),{name:'AbortError'});return new Response(new Uint8Array([1,2,3,4]),{headers:{'cache-control':'max-age=3600'}});};
 const cache=createCache({maxBytes:4096,fetcher,clock:()=>now,grace:30});
 const first=new AbortController(),second=new AbortController();
 const cancelled=cache.load('tile-a',first.signal);const shared=cache.load('tile-a',second.signal);first.abort();await assert.rejects(cancelled,{name:'AbortError'});assert.deepEqual([...new Uint8Array((await shared).data)],[1,2,3,4]);assert.equal(network,1,'One subscriber cancelling must not cancel another');
 // A newly requested tile after a quick camera reversal reuses the same fetch.
 const a=new AbortController(),old=cache.load('tile-b',a.signal);a.abort();await assert.rejects(old,{name:'AbortError'});await cache.load('tile-b',new AbortController().signal);assert.equal(network,2);
 const detached=(await cache.load('tile-a')).data;structuredClone(detached,{transfer:[detached]});assert.equal(detached.byteLength,0);assert.equal((await cache.load('tile-a')).data.byteLength,4,'Worker transfer must not detach the cached buffer');
 for(let i=0;i<200;i++)await cache.load('tile-'+i%20);assert.equal(network,22,'Repeated pans reuse compressed source bytes');
 now+=3601000;await cache.load('tile-a');assert.equal(network,23,'Expired data must reload');
 const bounded=createCache({maxBytes:12,maxItems:2,fetcher,clock:()=>now});await bounded.load('a');await bounded.load('b');await bounded.load('c');assert.equal(bounded.stats().tiles,2);assert.ok(bounded.stats().bytes<=12);
 const errorCache=createCache({fetcher:async()=>new Response('',{status:403})});await assert.rejects(errorCache.load('bad'),e=>e.status===403);assert.equal(errorCache.stats().tiles,0);
 const noStore=createCache({fetcher:async()=>new Response('a',{headers:{'cache-control':'no-store'}})});await noStore.load('a');assert.equal(noStore.stats().tiles,0);
 const stale=createCache({grace:5,fetcher:async(url,{signal})=>{await wait(25);if(signal.aborted)throw Object.assign(Error('cancelled'),{name:'AbortError'});return new Response('a');}});const staleController=new AbortController(),staleLoad=stale.load('stale',staleController.signal);staleController.abort();await assert.rejects(staleLoad,{name:'AbortError'});await wait(35);assert.equal(stale.stats().pending,0);assert.equal(stale.stats().cancelled,1,'Truly obsolete work must stop after grace');
 const url='ridge://linz/v1/tiles/elevation/WebMercatorQuad/18/254969/166413.png?pipeline=terrain-rgb&api=test';assert.ok(networkURL(url).startsWith('https://basemaps.linz.govt.nz/v1/tiles/elevation/'));assert.throws(()=>networkURL('ridge://example.com/x'));assert.throws(()=>networkURL('https://basemaps.linz.govt.nz/x'));
 // Use the actual bundled MapLibre LOD implementation, not a copy of its formula.
 function nativeLod(levels,ratio){const source={},fake={getSource:()=>source,_update(){}};NativeMap.prototype.setSourceTileLodParams.call(fake,levels,ratio,'terrain');return source.calculateTileZoom;}
 const oldLod=nativeLod(4,4),newLod=P.focusLod(nativeLod(P.lodParams().levels,P.lodParams().ratio));const pitch=58*Math.PI/180,z=Math.cos(pitch),centre=Math.sin(pitch),cases=[];
 for(const zoom of [5,12,17,19]){
  for(const distance of [0,centre,centre+.1])assert.ok(newLod(zoom,distance,z,1,36.87)>=zoom,'Foreground and focus retain full requested source detail');
  const before=oldLod(zoom,8,z,1,36.87),after=newLod(zoom,8,z,1,36.87);assert.ok(after<before-1.8,'Distant detail must fall away faster');cases.push({zoom,oldDistantZoom:before,newDistantZoom:after,focusZoom:newLod(zoom,centre,z,1,36.87)});
 }
 const edge=centre+.12;assert.ok(Math.abs(newLod(19,edge-1e-6,z,1,36.87)-newLod(19,edge+1e-6,z,1,36.87))<1e-4,'Focus transition is continuous');
 assert.equal(P.tileOptions().cancelPendingTileRequestsWhileZooming,false);assert.equal(P.tileOptions().maxTileCacheZoomLevels,8);assert.equal(P.tileOptions().centerClampedToGround,true);assert.equal(P.tileOptions().terrainSkirtLength,'auto');
 // Exercise real native RasterDEMTileSource -> ImageRequest -> addProtocol ->
 // cache -> source decode handoff. Browser bitmap decode is mocked, GPU unused.
 global.self=global;global.HTMLImageElement=class{};global.ImageBitmap=class{constructor(){this.width=256;this.height=256;}close(){}};
 const decoded=[];global.createImageBitmap=async(blob,options)=>{decoded.push({bytes:new Uint8Array(await blob.arrayBuffer()),options});return new ImageBitmap();};
 let protocolReads=0;addProtocol('ridge',async(req,controller)=>{protocolReads++;return cache.load(networkURL(req.url),controller.signal);});
 let handed=0;const source=new RasterDEMTileSource('terrain',{type:'raster-dem',tileSize:256,encoding:'mapbox'}, {waitForInitComplete:async()=>{},getReadyActor:()=>({sendAsync:async({data})=>{handed++;assert.equal(data.encoding,'mapbox');return{min:0,max:3724};}})},null);
 source.tiles=[url];source.map={getPixelRatio:()=>1,_refreshExpiredTiles:false,_requestManager:{transformRequest:async url=>({url})}};source.readImageNow=async image=>({width:image.width,height:image.height,data:new Uint8Array(256*256*4)});
 const tile=()=>({uid:Math.random(),tileID:{wrap:0,overscaledZ:18,canonical:{z:18,x:254969,y:166413,url:()=>url}},state:'loading'});
 const t=tile();await source.loadTile(t);assert.equal(t.state,'loaded');const netBefore=network;const revisit=tile();await source.loadTile(revisit);assert.equal(revisit.state,'loaded');assert.equal(network,netBefore);assert.equal(protocolReads,2);assert.equal(handed,2);assert.equal(decoded[0].options.colorSpaceConversion,'none','DEM data must never be colour managed');assert.deepEqual([...decoded[0].bytes],[1,2,3,4]);removeProtocol('ridge');
 cache.clear();bounded.clear();errorCache.clear();noStore.clear();stale.clear();
 const result={engine:'MapLibre 6.14.0',scope:'Original only',repeatPanLoads:200,uniquePanNetworkRequests:20,cancellationIsolation:'passed',quickReversalReuse:'passed',transferSafety:'passed',boundedCache:'passed',expiryAndErrors:'passed',nativeDEMProtocolHandoff:'passed',nativeLOD:cases,caveat:'Native CPU/source path with mock bitmap decode; no browser pixels or device FPS measured'};
 fs.writeFileSync('ORIGINAL_STREAMING_VALIDATION.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
})().catch(e=>{console.error(e);process.exitCode=1});
