/* Owner-private Site checkpoint storage. Platform dispatch authenticates browser
 * visitors and OAI-Sites-Authorization service requests before this Worker.
 * Preserve owner-private access; this is not a public upload service. */
import { createHash } from 'node:crypto';

const SHA=/^[a-f0-9]{64}$/,NAME=/^[A-Za-z0-9._-]{1,160}$/;
const MAX=8*1024*1024,PRIVATE='private, max-age=31536000, immutable';
const json=(value,status=200)=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json','Cache-Control':'private, no-store','X-Content-Type-Options':'nosniff'}});
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
async function bounded(request,max=MAX){
 const length=Number(request.headers.get('Content-Length'));
 if(length>max)throw Object.assign(Error('Request exceeds checkpoint chunk limit'),{status:413});
 const reader=request.body?.getReader();if(!reader)return new Uint8Array();
 const chunks=[];let total=0;
 for(;;){const {value,done}=await reader.read();if(done)break;total+=value.length;if(total>max){await reader.cancel();throw Object.assign(Error('Request too large'),{status:413});}chunks.push(value);}
 const bytes=new Uint8Array(total);let offset=0;for(const c of chunks){bytes.set(c,offset);offset+=c.length;}return bytes;
}
async function immutable(bucket,key,bytes,type,sha){
 const existing=await bucket.head(key);
 if(existing){if(existing.size!==bytes.length||existing.customMetadata?.sha256!==sha)throw Error('Stored object integrity conflict');return true;}
 await bucket.put(key,bytes,{onlyIf:{etagDoesNotMatch:'*'},httpMetadata:{contentType:type},customMetadata:{sha256:sha}});
 const saved=await bucket.head(key);
 if(!saved||saved.size!==bytes.length||saved.customMetadata?.sha256!==sha)throw Error('Stored object readback failed');
 return false;
}
function rangeFor(header,size){
 if(!header)return [0,size-1,false];
 const m=/^bytes=(\d*)-(\d*)$/.exec(header);if(!m||(!m[1]&&!m[2]))return null;
 let first=m[1]?Number(m[1]):Math.max(0,size-Number(m[2])),last=m[1]?(m[2]?Number(m[2]):size-1):size-1;
 if(!Number.isSafeInteger(first)||!Number.isSafeInteger(last)||first<0||first>=size||last<first)return null;
 return [first,Math.min(last,size-1),true];
}
// Immutable descriptors only; never cache authenticated responses or range bodies.
const descriptorPools=new WeakMap();
async function descriptorFor(bucket,sha){
 let pool=descriptorPools.get(bucket);if(!pool){pool={rows:new Map(),pending:new Map(),bytes:0};descriptorPools.set(bucket,pool);}
 const now=Date.now(),found=pool.rows.get(sha);
 if(found&&found.until>now){pool.rows.delete(sha);pool.rows.set(sha,found);return found.value;}
 if(found){pool.bytes-=found.bytes;pool.rows.delete(sha);}
 const shared=pool.pending.get(sha);if(shared)return shared;
 const task=(async()=>{const stored=await bucket.get('files/'+sha+'.json');if(!stored)return null;
 const value=await stored.json(),bytes=JSON.stringify(value).length*2;
 if(value.sha256!==sha||!Array.isArray(value.chunks)||value.chunks.length>256||!Number.isSafeInteger(value.bytes))throw Error('Invalid descriptor');
 if(bytes<=131072){const previous=pool.rows.get(sha);if(previous)pool.bytes-=previous.bytes;pool.rows.set(sha,{value,bytes,until:now+300000});pool.bytes+=bytes;
  while(pool.rows.size>32||pool.bytes>524288){const [key,row]=pool.rows.entries().next().value;pool.rows.delete(key);pool.bytes-=row.bytes;}}
 return value;})();if(pool.pending.size<64)pool.pending.set(sha,task);
 try{return await task;}finally{if(pool.pending.get(sha)===task)pool.pending.delete(sha);}
}
async function fileResponse(request,bucket,sha){
 const descriptor=await descriptorFor(bucket,sha);if(!descriptor)return json({error:'Unknown checkpoint file'},404);
 const range=rangeFor(request.headers.get('Range'),descriptor.bytes);
 if(!range)return new Response(null,{status:416,headers:{'Content-Range':'bytes */'+descriptor.bytes}});
 const [first,last,partial]=range,headers={'Content-Type':descriptor.mime,'Content-Length':String(last-first+1),'Cache-Control':PRIVATE,'Accept-Ranges':'bytes','ETag':'"'+sha+'"','X-Content-SHA256':sha,'X-Content-Type-Options':'nosniff'};
 if(partial)headers['Content-Range']=`bytes ${first}-${last}/${descriptor.bytes}`;
 if(request.method==='HEAD')return new Response(null,{status:partial?206:200,headers});
 let position=0,index=0,current;
 const stream=new ReadableStream({async pull(controller){
  try{for(;;){
   if(current){const item=await current.read();if(!item.done){controller.enqueue(item.value);return;}current=null;}
   if(index>=descriptor.chunks.length){controller.close();return;}
   const part=descriptor.chunks[index++],begin=position,end=position+part.bytes-1;position+=part.bytes;
   if(end<first||begin>last)continue;
   const offset=Math.max(0,first-begin),length=Math.min(end,last)-Math.max(begin,first)+1;
   const object=await bucket.get('blobs/'+part.sha256,{range:{offset,length}});
   if(!object?.body)throw Error('Missing checkpoint part');current=object.body.getReader();
  }}catch(error){controller.error(error);}
 },async cancel(){await current?.cancel();}});
 return new Response(stream,{status:partial?206:200,headers});
}

export async function lidarStorage(request,env){
 const url=new URL(request.url),parts=url.pathname.slice('/api/lidar/'.length).split('/').filter(Boolean),bucket=env.LIDAR;
 if(!bucket)return json({error:'Persistent checkpoint storage unavailable'},503);
 if(!['GET','HEAD','PUT'].includes(request.method))return json({error:'Method not allowed'},405);
 if(request.method==='PUT'&&((request.headers.has('Origin')&&request.headers.get('Origin')!==url.origin)||request.headers.get('Sec-Fetch-Site')==='cross-site'))return json({error:'Cross-origin write rejected'},403);
 try{
  if(parts[0]==='health'&&request.method==='GET')return json({schema:1,storage:'r2',chunkLimitBytes:MAX,immutable:true});
  if(parts[0]==='blobs'&&parts.length===2&&SHA.test(parts[1])){
   const key='blobs/'+parts[1];
   if(request.method==='PUT'){
    const bytes=await bounded(request),sha=hash(bytes);if(sha!==parts[1])return json({error:'Content hash mismatch'},422);
    const existed=await immutable(bucket,key,bytes,'application/octet-stream',sha);return json({sha256:sha,bytes:bytes.length,existed});
   }
   const object=request.method==='HEAD'?await bucket.head(key):await bucket.get(key);
   if(!object)return json({error:'Unknown object'},404);
   return new Response(request.method==='HEAD'?null:object.body,{headers:{'Content-Type':'application/octet-stream','Content-Length':String(object.size),'Cache-Control':PRIVATE,'X-Content-SHA256':object.customMetadata.sha256,'X-Content-Type-Options':'nosniff'}});
  }
  if(parts[0]==='files'&&parts.length===2){const sha=parts[1].replace(/\.(glb|json|webp|tar|bin)$/,'');if(SHA.test(sha)&&request.method!=='PUT')return fileResponse(request,bucket,sha);}
  if(parts[0]==='manifests'&&parts.length===2&&SHA.test(parts[1])){
   const key='files/'+parts[1]+'.json';
   if(request.method==='PUT'){
    const existing=await bucket.get(key);if(existing)return json({...await existing.json(),verifiedPersistentSha256:parts[1]});
    const descriptor=JSON.parse(new TextDecoder().decode(await bounded(request,1<<20)));
    if(descriptor.sha256!==parts[1]||!Number.isSafeInteger(descriptor.bytes)||descriptor.bytes<=0||!Array.isArray(descriptor.chunks)||!descriptor.chunks.length||descriptor.chunks.length>256)return json({error:'Invalid file descriptor'},422);
    const allowed=['application/octet-stream','application/x-tar','application/json','model/gltf-binary','image/webp','image/png','image/jpeg','text/plain'];
    if(!allowed.includes(descriptor.mime))return json({error:'Unsupported checkpoint MIME type'},422);
    let size=0;const combined=createHash('sha256');
    for(const part of descriptor.chunks){
     if(!SHA.test(part.sha256)||!Number.isSafeInteger(part.bytes)||part.bytes<=0||part.bytes>MAX)return json({error:'Invalid checkpoint part'},422);
     const object=await bucket.get('blobs/'+part.sha256);
     if(!object||object.size!==part.bytes||object.customMetadata?.sha256!==part.sha256)return json({error:'Missing or mismatched checkpoint part'},409);
     const own=createHash('sha256'),reader=object.body.getReader();
     for(;;){const {done,value}=await reader.read();if(done)break;combined.update(value);own.update(value);size+=value.length;}
     if(own.digest('hex')!==part.sha256)return json({error:'Persistent part failed readback hash'},500);
    }
    if(size!==descriptor.bytes||combined.digest('hex')!==descriptor.sha256)return json({error:'Assembled file hash mismatch'},422);
    const bytes=new TextEncoder().encode(JSON.stringify(descriptor));await immutable(bucket,key,bytes,'application/json',hash(bytes));
    return json({...descriptor,verifiedPersistentSha256:descriptor.sha256});
   }
   const object=await bucket.get(key);return object?new Response(request.method==='HEAD'?null:object.body,{headers:{'Content-Type':'application/json','Cache-Control':PRIVATE,'X-Content-Type-Options':'nosniff'}}):json({error:'Unknown descriptor'},404);
  }
  if(parts[0]==='runs'&&SHA.test(parts[1]||'')){
   const prefix='runs/'+parts[1]+'/';
   if(parts.length===2&&request.method==='GET'){
    const listed=await bucket.list({prefix,cursor:url.searchParams.get('cursor')||undefined,limit:500});
    return json({objects:listed.objects.map(o=>({key:o.key,bytes:o.size,uploaded:o.uploaded})),truncated:listed.truncated,cursor:listed.truncated?listed.cursor:null});
   }
   if(parts.length===5&&['tasks','events','status','configs'].includes(parts[2])&&NAME.test(parts[3])&&SHA.test(parts[4])){
    const key=prefix+parts.slice(2).join('/');
    if(request.method==='PUT'){
     const bytes=await bounded(request),sha=hash(bytes);if(sha!==parts[4])return json({error:'Record hash mismatch'},422);
     JSON.parse(new TextDecoder().decode(bytes));await immutable(bucket,key,bytes,'application/json',sha);return json({sha256:sha,key});
    }
    const object=await bucket.get(key);return object?new Response(request.method==='HEAD'?null:object.body,{headers:{'Content-Type':'application/json','Cache-Control':PRIVATE,'X-Content-SHA256':parts[4],'X-Content-Type-Options':'nosniff'}}):json({error:'Unknown run record'},404);
   }
  }
  return json({error:'Unknown checkpoint endpoint'},404);
 }catch(error){return json({error:error.status?error.message:'Checkpoint operation failed'},error.status||500);}
}
