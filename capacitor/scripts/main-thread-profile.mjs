// Opt-in synchronous submission timings; nested/inclusive, never summed as total CPU.
export function profileMainThread(map,clock){
 const rows=new Map(),undo=[];
 function hook(owner,key,name){const original=owner?.[key];if(typeof original!=='function')return;
  const samples=[];let calls=0,total=0;
  rows.set(name,{samples,get calls(){return calls;},get total(){return total;}});
  function wrapped(...args){const before=clock();try{return original.apply(this,args);}finally{const ms=Math.max(0,clock()-before);calls++;total+=ms;samples.push(ms);if(samples.length>256)samples.shift();}}
  owner[key]=wrapped;undo.push(()=>{if(owner[key]===wrapped)owner[key]=original;});
 }
 for(const [id,manager]of Object.entries(map.style?.tileManagers||{}))hook(manager,'update','source-selection:'+id);
 hook(map.terrain?.tileManager,'update','terrain-selection');
 hook(map.painter?.renderToTexture,'prepareForRender','texture-preparation');
 hook(map.painter?._ridgeDirectSampling,'prepare','direct-planning');
 return{snapshot(){return{note:'Opt-in synchronous, inclusive timings. Nested stages overlap; do not sum. GPU execution and worker time excluded. Targets captured when enabled.',components:Object.fromEntries([...rows].map(([name,row])=>{const sorted=row.samples.slice().sort((a,b)=>a-b);return[name,{calls:row.calls,totalMs:row.total,recentSamples:sorted.length,p95Ms:sorted.length?sorted[Math.floor((sorted.length-1)*.95)]:null}];}))};},destroy(){for(const restore of undo.reverse())restore();undo.length=0;}};
}
