const assert=require('node:assert/strict'),fs=require('node:fs');
const R=require('../public/regional-imagery.js'),meta=require('../public/west-coast-footprint.js');
const tile=(lon,lat,z)=>({z,x:Math.floor((lon+180)/360*2**z),y:Math.floor((1-Math.asinh(Math.tan(lat*Math.PI/180))/Math.PI)/2*2**z)});
(async()=>{
 const source=JSON.parse(fs.readFileSync('WEST_COAST_REGIONAL_VALIDATION.json'));
 const routes=[];for(const [name,p] of Object.entries(source.points)){const q=tile(p.longitude,p.latitude,18),selected=R.mode(q.z,q.x,q.y,meta);routes.push({name,...q,selected});assert.equal(selected,p.fineLINZ?'linz':'esri');}
 assert.equal(R.mode(14,0,0,meta),'linz');assert.equal(R.mode(...Object.values(tile(168.727,-44.384,18)),meta),'linz');
 const calls=[],fetcher=async(url)=>{calls.push(url);return new Response(new Uint8Array([1,2,3]),{headers:{'cache-control':'private, max-age=300'}});};
 let composed=0;const regional=R.create({fetcher,meta,composer:async data=>{assert.ok(data.linz.byteLength&&data.esri.byteLength);composed++;return new Uint8Array([9,8,7]).buffer;}});
 const linz=q=>`https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/${q.z}/${q.x}/${q.y}.webp?api=TEST`,cook=tile(170.141,-43.595,18),gap=tile(170.103,-43.56,18);
 const original=await regional.fetch(linz(cook));assert.equal(calls.length,1);assert.ok(calls[0].includes('basemaps.linz.govt.nz'));assert.deepEqual([...new Uint8Array(await original.arrayBuffer())],[1,2,3]);
 calls.length=0;await regional.fetch(linz(gap));assert.equal(calls.length,1);assert.ok(calls[0].includes('World_Imagery'));
 const gradedCalls=[],gradedJobs=[],graded=R.create({meta,fetcher:async url=>{gradedCalls.push(url);return new Response(new Uint8Array([1,2,3]));},composer:async data=>{gradedJobs.push(data);return new Uint8Array([4]).buffer;}});
 await graded.fetch(linz(cook)+'&ridge-grade=snow-v3');assert.equal(gradedJobs.length,0,'LINZ uses shader uniforms; never decode/encode original LINZ tiles');assert.ok(!gradedCalls[0].includes('ridge-grade'),'Private grade flag never reaches LINZ');
 gradedCalls.length=0;await graded.fetch(linz(gap)+'&ridge-grade=snow-v3');assert.equal(gradedCalls.length,1);assert.equal(gradedJobs[0].compensate,true);assert.equal(gradedJobs[0].linz,null);graded.destroy();
 calls.length=0;const dem=linz(cook).replace('/aerial/','/elevation/').replace('.webp','.png');await regional.fetch(dem);assert.equal(calls[0],dem,'Elevation must never be replaced');
 let boundary;for(let x=15930;x<=15937&&!boundary;x++)for(let y=10396;y<=10403;y++)if(R.mode(14,x,y,meta)==='boundary'){boundary={z:14,x,y};break;}assert.ok(boundary);
 calls.length=0;const mixed=await regional.fetch(linz(boundary));assert.equal(calls.length,2);assert.equal(composed,1);assert.equal(mixed.headers.get('content-type'),'image/png');assert.deepEqual([...new Uint8Array(await mixed.arrayBuffer())],[9,8,7]);
 // Never treat Esri's successful HTTP-200 z19 placeholder as imagery.
 const cropCalls=[],crops=[];const cropper=R.create({meta,fetcher:async url=>{cropCalls.push(url);return new Response(new Uint8Array([1]),{headers:{'cache-control':'max-age=120','age':'10'}});},composer:async data=>{crops.push(data);return new Uint8Array([5]).buffer;}});
 const closeGap=tile(170.103,-43.56,19);assert.equal(R.mode(closeGap.z,closeGap.x,closeGap.y,meta),'esri');
 const crop=await cropper.fetch(linz(closeGap));assert.deepEqual(cropCalls,[`https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/18/${Math.floor(closeGap.y/2)}/${Math.floor(closeGap.x/2)}`]);assert.equal(crops[0].linz,null);assert.equal(crops[0].esriZ,18);assert.equal(crops[0].x,closeGap.x);assert.equal(crop.headers.get('cache-control'),'private, max-age=110');
 cropper.destroy();await assert.rejects(cropper.fetch(linz(cook)),{name:'AbortError'});
 // Destruction while network work is pending must reject, not strand a queued job.
 let finish;const pending=R.create({meta,fetcher:()=>new Promise(resolve=>{finish=resolve;}),composer:async()=>new ArrayBuffer(1)});const inFlight=pending.fetch(linz(closeGap));pending.destroy();finish(new Response(new Uint8Array([1])));await assert.rejects(inFlight,{name:'AbortError'});
 // Shared byte cache must deduplicate and retain the finished boundary output,
 // so revisiting does not download, decode or recompose it again.
 global.RidgeRegionalImagery={create:opts=>R.create({...opts,meta,composer:async()=>{composed++;return new Uint8Array([7,7,7]).buffer;}})};
 const T=require('../public/tile-cache.js'),cache=T.createTileCaches({fetcher});const ridge=linz(boundary).replace('https://basemaps.linz.govt.nz','ridge://linz');calls.length=0;
 await Promise.all([cache.load(ridge),cache.load(ridge)]);const after=composed;await cache.load(ridge);assert.equal(calls.length,2);assert.equal(composed,after);assert.ok(cache.stats().imagery.hits>0);
 cache.destroy();delete global.RidgeRegionalImagery;regional.destroy();
 const result={reviewedSurveyFootprints:true,sourceSurveyRequests:source.screenedTiles+source.footprintTiles,failedRequests:source.failedTiles,cookAndTasmanUseOriginalLINZ:true,goodWestCoastSurveysStayLINZ:true,onlyReviewedGapsUseEsri:true,elevationUntouched:true,outsideRegionUntouched:true,ungradedBoundaryOnlyComposites:true,onlyBoundaryTilesFetchBothProviders:true,LINZGradeUsesNativeShaderOnly:true,EsriCompensationConfinedToReviewedGaps:true,verifiedEsriZoomCeilingWithGeographicCrop:true,cacheTTLRespectsUpstreamAge:true,destructionRejectsPendingJobs:true,cachePreventsRepeatDownloadsAndComposition:true,routes,caveat:'Routing, cache and boundary-job lifecycle are tested here. Pixel preservation/feathering are separately checked against authentic image samples; no browser screenshot/FPS claim.'};fs.writeFileSync('REGIONAL_IMAGERY_RUNTIME_VALIDATION.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
})().catch(e=>{console.error(e);process.exitCode=1});
