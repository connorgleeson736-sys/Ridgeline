/* Official LINZ survey boundaries with hard source masks. Original imagery passes through elsewhere. */
(function(root){'use strict';
function create({fetcher,base,compose,atlas=root.RidgeGapAtlas,deferEsri=false}){
 let dead=false;const stats={gapInterior:0,gapBoundary:0,gapOverview:0,gapEsriWaitsAvoided:0};
 async function fetch(url,options={}){
  if(dead||options.signal?.aborted)throw Object.assign(Error('Cancelled'),{name:'AbortError'});
  const u=new URL(url,'https://viewer.invalid'),m=u.hostname==='basemaps.linz.govt.nz'&&u.pathname.match(/^\/v1\/tiles\/aerial\/WebMercatorQuad\/(\d+)\/(\d+)\/(\d+)\.webp$/);
  if(!m)return base(url,options);const [z,x,y]=m.slice(1).map(Number);
  if(root.RidgeImageryCatalog)await root.RidgeImageryCatalog.ensure(options.ridgeOriginal?{z,x,y}:undefined);atlas??=root.RidgeGapAtlas;
  if(!atlas)return base(url,options,true);const coarse=z<atlas.z?atlas.coarse[z+'/'+x+'/'+y]:null;
  const f=2**(z-atlas.z),entry=z>=atlas.z?atlas.entries[Math.floor(x/f)+'/'+Math.floor(y/f)]:null;
  const mode=coarse?(coarse.full?'esri':'boundary'):entry?root.RidgeRegionalImagery.mode(z,x,y,entry):'linz';
  if(mode==='linz')return base(url,options,true);
  const esriZ=Math.min(z,18),factor=2**(z-esriZ),source=coarse?.url||`https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/${esriZ}/${Math.floor(y/factor)}/${Math.floor(x/factor)}`;
  if(mode==='esri'&&(coarse||z===esriZ)){stats[coarse?'gapOverview':'gapInterior']++;const response=await fetcher(source,options);if(dead||options.signal?.aborted){await response.body?.cancel();throw Object.assign(Error('Cancelled'),{name:'AbortError'});}if(!response.ok){await response.body?.cancel();throw Object.assign(Error('Regional satellite imagery unavailable'),{status:503});}return response;}
  // Fine-survey PNGs have transparent nodata. They veto Esri at the actual
  // aerial edge, avoiding a fixed-width coarse-imagery guard strip.
  const size=256/f,px=x*size-entry?.x*256,py=y*size-entry?.y*256;
  const onSeam=!coarse&&entry?.seamRings?.length&&root.RidgeRegionalImagery.relation(entry.seamRings,[px-2,py-2,px+size+2,py+size+2],true)!==-1;
  const fineRows=onSeam?(entry.fineSources||[]):[];
  const fineRequests=fineRows.map(row=>{const fineZ=Math.min(z,row.maxZoom??19,19),ff=2**(z-fineZ);const address=new URL(`https://basemaps.linz.govt.nz/v1/tiles/${row.name}/WebMercatorQuad/${fineZ}/${Math.floor(x/ff)}/${Math.floor(y/ff)}.png`);address.searchParams.set('api',u.searchParams.get('api')||'');return{url:address.href,z:fineZ};});
  if(deferEsri&&mode==='boundary'&&!coarse){
   const controller=new AbortController(),cancel=()=>controller.abort();options.signal?.addEventListener('abort',cancel,{once:true});if(options.signal?.aborted)cancel();
   let esriResponse=null,usedEsri=false;
   // Start in parallel, but this optional input cannot hold a LINZ-only result.
   const satellite=Promise.resolve().then(()=>fetcher(source,{...options,signal:controller.signal})).then(async r=>{
    esriResponse=r;if(!r.ok){await r.body?.cancel();throw Object.assign(Error('Regional satellite imagery unavailable'),{status:503});}return r.arrayBuffer();
   });satellite.catch(()=>{});
   try{
    const inputs=await Promise.allSettled([base(url,options,true),...fineRequests.map(row=>fetcher(row.url,options))]);
    const failure=inputs.find(r=>r.status==='rejected'),a=inputs[0].value;
    if(failure||inputs.some(r=>r.status==='fulfilled'&&!r.value.ok)){
     await Promise.allSettled(inputs.filter(r=>r.status==='fulfilled').map(r=>r.value.body?.cancel()));
     if(failure)throw failure.reason;if(a&&!a.ok)return a;throw Object.assign(Error('Survey imagery unavailable'),{status:503});
    }
    const linz=await a.arrayBuffer(),fine=await Promise.all(inputs.slice(1).map(async(r,i)=>({data:await r.value.arrayBuffer(),z:fineRequests[i].z})));
    if(dead||options.signal?.aborted)throw Object.assign(Error('Cancelled'),{name:'AbortError'});
    const loadEsri=()=>{usedEsri=true;return satellite;};
    const data=await compose({linz,esri:null,loadEsri,fine,z,x,y,esriZ,hardMask:true,meta:{z:entry.z,x:entry.x,y:entry.y,width:256,height:256,maskURL:entry.maskURL,rings:entry.vector?entry.outer:undefined}},options.signal,options);
    if(!usedEsri)stats.gapEsriWaitsAvoided++;
    // An unused donor's fallback/expiry must not downgrade the finished photo.
    const pair=[inputs[0],...(usedEsri?[{status:'fulfilled',value:esriResponse}]:[]),...inputs.slice(1)];
    return finish(data,pair,'gapBoundary',options);
   }finally{controller.abort();options.signal?.removeEventListener('abort',cancel);}
  }
  const pair=await Promise.allSettled([mode==='boundary'?base(url,options,true):Promise.resolve(null),fetcher(source,options),...fineRequests.map(row=>fetcher(row.url,options))]);
  const fail=pair.find(r=>r.status==='rejected');const a=pair[0].value,b=pair[1].value;
  if(fail||pair.some(r=>r.status==='fulfilled'&&r.value&&!r.value.ok)){await Promise.allSettled(pair.filter(r=>r.status==='fulfilled'&&r.value).map(r=>r.value.body?.cancel()));if(fail)throw fail.reason;if(a&&!a.ok)return a;throw Object.assign(Error('Regional satellite imagery unavailable'),{status:503});}
  const linz=a?await a.arrayBuffer():null,esri=await b.arrayBuffer(),fine=await Promise.all(pair.slice(2).map(async (r,i)=>({data:await r.value.arrayBuffer(),z:fineRequests[i].z}))); if(dead||options.signal?.aborted)throw Object.assign(Error('Cancelled'),{name:'AbortError'});
  const data=await compose({linz,esri,fine,z,x,y,esriZ:coarse?z:esriZ,overlay:!!coarse,hardMask:true,meta:entry?{z:entry.z,x:entry.x,y:entry.y,width:256,height:256,maskURL:entry.maskURL,rings:entry.vector?entry.outer:undefined}:null},options.signal,options);
  return finish(data,pair,coarse?'gapOverview':mode==='boundary'?'gapBoundary':'gapInterior',options);
 }
 function finish(data,pair,kind,options){
  if(dead||options.signal?.aborted)throw Object.assign(Error('Cancelled'),{name:'AbortError'});
  stats[kind]++;
  let ttl=3600;for(const r of pair.filter(v=>v.status==='fulfilled').map(v=>v.value).filter(Boolean)){const cc=r.headers.get('cache-control')||'',age=Number(r.headers.get('age'))||0,max=cc.match(/(?:^|,)\s*max-age=(\d+)/i);if(/no-store|no-cache/i.test(cc))ttl=0;else if(max)ttl=Math.min(ttl,Math.max(0,Number(max[1])-age));}
  return new Response(data,{headers:{'Content-Type':'image/png','Cache-Control':ttl?'private, max-age='+ttl:'no-store'}});
 }
 return{fetch,destroy(){dead=true;},stats:()=>({...stats})};
}
root.RidgeGapImagery={create};
})(globalThis);
