import {test} from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
import {webcrypto} from 'node:crypto';
const source=await readFile(new URL('../mobile/native-transport.js',import.meta.url),'utf8');
function setup(fetcher,extra={}){
 const calls=[],pending=new Map();let sequence=0;
 const timers={setTimeout(fn){pending.set(++sequence,fn);return sequence;},clearTimeout(id){pending.delete(id);}};
 const c={module:{exports:{}},URL,Request,Response,Headers,ReadableStream,Blob,crypto:webcrypto,location:{origin:'https://localhost',href:'https://localhost/'},fetch:fetcher,...extra};c.globalThis=c;
 vm.runInNewContext(source,c);const api=c.module.exports.install({send:(op,id,priority)=>calls.push({op,id,priority}),fetcher,timers});
 return{c,api,calls,tick(){for(const [id,fn]of [...pending]){pending.delete(id);fn();}}};
}
const tile='https://localhost/_ridge/linz/v1/tiles/aerial/WebMercatorQuad/3/7/5.webp?api=test';
test('transport preserves source URL and bytes, and sends request metadata as headers',async()=>{
 let seen;const x=setup(async(input,options)=>{seen={input,options};return new Response(new Uint8Array([0,128,255]),{headers:{'Cache-Control':'max-age=60'}});});
 try{const r=await x.api.fetch(tile,{ridgePriority:()=> 'low',ridgeRank:()=>12});assert.equal(seen.input,tile);assert.equal(seen.options.headers.get('X-Ridge-Priority'),'212');assert.match(seen.options.headers.get('X-Ridge-Request'),/^[a-f0-9]{32}$/);assert.deepEqual(new Uint8Array(await r.arrayBuffer()),new Uint8Array([0,128,255]));assert.equal(x.api.stats().active,0);assert.equal(x.calls[0].op,'begin');assert.equal(x.calls.at(-1).op,'cancel');}finally{x.api.destroy();}
});
test('Request objects retain headers and their AbortSignal; priorities can change while queued',async()=>{
 let controller,options,rank=120;const x=setup(async(i,o)=>{options=o;return new Response(new ReadableStream({start(c){controller=c;}}));});
 try{const request=new Request(tile,{headers:{Accept:'image/webp'}});const response=await x.api.fetch(request,{ridgeRank:()=>rank});assert.equal(options.headers.get('Accept'),'image/webp');assert.equal(options.signal,request.signal);rank=0;x.tick();assert.equal(x.calls.at(-1).op,'priority');assert.equal(x.calls.at(-1).priority,0);controller.close();await response.arrayBuffer();}finally{x.api.destroy();}
});
test('cancellation remains connected after headers until the body finishes',async()=>{
 const stop=new AbortController();let body;
 const x=setup(async(i,o)=>new Response(new ReadableStream({start(c){body=c;o.signal.addEventListener('abort',()=>c.error(new DOMException('Cancelled','AbortError')),{once:true});}})));
 try{const r=await x.api.fetch(tile,{signal:stop.signal});const bytes=r.arrayBuffer();stop.abort();await assert.rejects(bytes,{name:'AbortError'});assert.equal(x.api.stats().active,0);assert.equal(x.calls.filter(c=>c.op==='cancel').length,1);}finally{x.api.destroy();}
});
test('non-map provider requests are passed through untouched',async()=>{const options={headers:{Accept:'text/plain'}};let seen;const x=setup(async(i,o)=>{seen=[i,o];return new Response('ok');});try{await x.api.fetch('https://example.org/test',options);assert.equal(seen[1],options);assert.equal(x.calls.length,0);}finally{x.api.destroy();}});
test('mid-body failures recover only from saved coverage and never cache a coarse fallback',async()=>{
 let attempts=0;const x=setup(async(i,o)=>{attempts++;if(o.headers.get('X-Ridge-Only-Saved')==='1')return new Response(new Uint8Array([7,8]),{headers:{'Content-Type':'image/png','Cache-Control':'no-store'}});return new Response(new ReadableStream({start(c){c.error(new TypeError('Connection lost'));}}),{headers:{'X-Ridge-Expires-At':String(Date.now()+60000)}});});
 try{const r=await x.api.fetch(tile);assert.deepEqual(new Uint8Array(await r.arrayBuffer()),new Uint8Array([7,8]));assert.equal(attempts,2);assert.equal(r.headers.get('Cache-Control'),'no-store');assert.equal(r.headers.get('Content-Type'),'image/png');assert.equal(r.headers.has('X-Ridge-Expires-At'),false);}finally{x.api.destroy();}
});
test('absolute upstream expiry is not restarted when a streamed body finishes',async()=>{const x=setup(async()=>new Response('tile',{headers:{'Cache-Control':'max-age=3600','X-Ridge-Expires-At':String(Date.now()-1000)}}));try{const r=await x.api.fetch(tile);await r.arrayBuffer();assert.equal(r.headers.get('Cache-Control'),'no-store');}finally{x.api.destroy();}});
test('worker messages reach native control and terminating a worker releases its requests',()=>{
 class Worker extends EventTarget{postMessage(m){this.sent=m;}terminate(){this.terminated=true;}}
 const x=setup(async()=>new Response(''),{Worker});try{const worker=new x.c.Worker('/native-regional-worker.js');let applicationMessages=0;worker.addEventListener('message',()=>applicationMessages++);worker.dispatchEvent(new MessageEvent('message',{data:{__ridge_transport_v1:true,op:'begin',id:'1'.repeat(32),priority:0}}));assert.equal(applicationMessages,0);assert.equal(x.calls[0].op,'begin');x.api.trim();assert.equal(worker.sent.__ridgeTrimImages,true);worker.terminate();assert.equal(x.calls.at(-1).op,'cancel');assert.equal(worker.terminated,true);}finally{x.api.destroy();}
});
