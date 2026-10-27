import {test} from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';
const source=await readFile(new URL('../www/imagery-preload.js',import.meta.url),'utf8');
function setup(){
 const events={},signals=[],visibility={};let now=1000,busy=false,hit={x:.9,y:.6};
 const map={getZoom:()=>14,getSource:()=>({maxzoom:19,tiles:['ridge://linz/v1/tiles/aerial/WebMercatorQuad/{z}/{x}/{y}.webp']}),style:{tileManagers:{linz:{_inViewTiles:{getAllTiles:()=>[{tileID:{canonical:{z:14,x:Math.floor(hit.x*16384),y:Math.floor(hit.y*16384)}}}]}}}},getCanvas:()=>({addEventListener(){},removeEventListener(){}}),on:(n,f)=>events[n]=f,off:(n)=>delete events[n]};
 const document={hidden:false,addEventListener:(n,f)=>visibility[n]=f,removeEventListener:n=>delete visibility[n]};
 const c={module:{exports:{}},AbortController,document,navigator:{},performance:{now:()=>now}};c.globalThis=c;vm.runInNewContext(source,c);
 const cache={stats:()=>({imagery:{pending:0}}),canPrefetch:()=>!busy,has:()=>false,prefetch:(url,signal)=>{signals.push(signal);return new Promise((resolve,reject)=>signal.addEventListener('abort',()=>reject(Object.assign(Error('cancel'),{name:'AbortError'})),{once:true}));}};
 const attached=c.module.exports.attach(map,{cache,focus:()=>({hit}),clock:()=>now,engine:{getVersion:()=> '6.14.0'}});
 function begin(){events.movestart();now+=300;hit={...hit,x:hit.x+.00001};events.move();assert.ok(signals.length>0);}
 return{begin,signals,attached,events,document,visibility,busy(){busy=true;now+=300;hit={...hit,x:hit.x+.00001};events.move();}};
}
test('a new gesture cancels predictions from the previous gesture',()=>{const x=setup();try{x.begin();x.events.movestart();assert.ok(x.signals.every(s=>s.aborted));}finally{x.attached.destroy();}});
test('visible-tile backpressure cancels predictions even when all warmup slots are occupied',()=>{const x=setup();try{x.begin();x.busy();assert.ok(x.signals.every(s=>s.aborted));assert.ok(x.attached.stats().obsolete>0);}finally{x.attached.destroy();}});
test('hidden pages release prediction demand',()=>{const x=setup();try{x.begin();x.document.hidden=true;x.visibility.visibilitychange();assert.ok(x.signals.every(s=>s.aborted));}finally{x.attached.destroy();}});
