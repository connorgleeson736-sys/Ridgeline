const assert=require('node:assert/strict'),fs=require('node:fs'),crypto=require('node:crypto'),R=require('../public/regional-imagery.js');
const data=fs.readFileSync('qa/south-island/range-proof.webp'),proof=JSON.parse(fs.readFileSync('qa/south-island/range-proof.json'));
assert.equal(proof.proof,'completed-overview');assert.equal(crypto.createHash('sha256').update(data).digest('hex'),proof.sha256);
const key='12/3983/2600',row=[0,proof.offset,proof.bytes],south={packs:[proof.file],tiles:{[key]:{raw:row,natural:row}}};
const url=(z=12,x=3983,y=2600,grade=true)=>`https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/${z}/${x}/${y}.webp?api=TEST${grade?'&ridge-grade=snow-v3':''}`;
function response(){return new Response(data,{status:206,headers:{'Content-Range':`bytes ${proof.offset}-${proof.offset+proof.bytes-1}/99999999`,'Content-Type':'application/octet-stream'}});}
(async()=>{
 const calls=[],r=R.create({south,meta:null,fetcher:async(u,o)=>{calls.push(u);if(u.startsWith('/api/lidar/')){assert.equal(new Headers(o.headers).get('Range'),`bytes=${proof.offset}-${proof.offset+proof.bytes-1}`);return response();}return new Response('passthrough');}});
 assert.deepEqual(Buffer.from(await(await r.fetch(url())).arrayBuffer()),data);assert.equal(calls.length,1);assert.ok(calls[0].endsWith('mode=natural'));assert.equal(r.stats().workerJobs,0);
 calls.length=0;await r.fetch(url(12,3983,2600,false));assert.ok(calls[0].endsWith('mode=raw'));
 for(const input of [url(13,7966,5200),url(12,4000,2400),url().replace('/aerial/','/elevation/')]){calls.length=0;await r.fetch(input);assert.equal(calls.length,1);assert.ok(calls[0].startsWith('https://basemaps.linz.govt.nz'));}
 r.destroy();
 for(const kind of ['wrong-range','html','network']){
  let n=0;const bad=R.create({south,meta:null,fetcher:async()=>{n++;if(kind==='network')throw Error('offline');return kind==='wrong-range'?new Response(data):new Response('<html>bad</html>',{status:206,headers:{'Content-Range':`bytes ${proof.offset}-${proof.offset+proof.bytes-1}/99999999`}});}});
  await assert.rejects(bad.fetch(url()));assert.equal(n,1,'Never fall back to old coarse imagery');bad.destroy();
 }
 const hot={packs:south.packs,tiles:{[key]:{url:'/assets/south-overviews-v1/'+proof.sha256.slice(0,24)+'.webp',range:row}}};
 for(const success of [true,false]){
  const requests=[],h=R.create({south:hot,meta:null,fetcher:async u=>{requests.push(u);return u.startsWith('/assets/')?(success?new Response(data,{headers:{'content-type':'application/octet-stream'}}):new Response('missing',{status:404})):response();}});
  assert.deepEqual(Buffer.from(await(await h.fetch(url())).arrayBuffer()),data);assert.equal(requests.length,success?1:2);assert.equal(h.stats().southStatic,success?1:0);h.destroy();
 }
 global.RidgeSouthOverviews=south;global.RidgeRegionalImagery=R;
 const T=require('../public/tile-cache.js');let handler,network=0;
 const cache=T.install({addProtocol(name,fn){assert.equal(name,'ridge');handler=fn;}},{fetcher:async()=>{network++;return response();}});
 const request={url:url().replace('https://basemaps.linz.govt.nz','ridge://linz')};
 const results=await Promise.all([handler(request,new AbortController()),handler(request,new AbortController())]);
 results.push(await handler(request,new AbortController()));for(const result of results)assert.deepEqual(Buffer.from(result.data),data);assert.equal(network,1);
 const report={proof:proof.proof,authenticProductionRange:true,sha256:proof.sha256,rangeBytes:data.length,networkRequestsIncludingRevisits:network,noWorkerAssembly:true,staticAndExactRangeFallback:true,wrongRangeAndMalformedImageFailSafely:true,LODAndDEMPassthrough:true,caveat:'Actual authenticated image bytes through registered protocol/cache; not live browser/GPU or FPS.'};
 fs.writeFileSync('SOUTH_ISLAND_DELIVERY_VALIDATION.json',JSON.stringify(report,null,2)+'\n');cache.destroy();delete global.RidgeRegionalImagery;delete global.RidgeSouthOverviews;
 console.log('PASS: authentic R2 range, static generic MIME / identical range fallback, shared cache, variants, safe failures and fine/DEM/outside-region passthrough.');
})().catch(error=>{console.error(error);process.exitCode=1});
