const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),{execFileSync}=require('node:child_process');
function run(source){
 let time=0,x=.97,focusX=x,lastFocus=0,nextTimer=null;const events={},changes=[];
 const range=.0001,span=2*range*Math.tan(36.87*Math.PI/360);
 const ctx={performance:{now:()=>time},document:{hidden:false},setTimeout:fn=>(nextTimer=fn,1),clearTimeout:()=>nextTimer=null};vm.createContext(ctx);vm.runInContext(source,ctx);
 const map={_camera:{transform:{fov:36.87,height:800,worldSize:512*2**13}},getCenter:()=>({lng:x*360-180,lat:-43}),getZoom:()=>13,on(n,f){(events[n]??=[]).push(f)},off(){},_update(){},triggerRepaint(){}};
 const speed=ctx.RidgeMotionDetail.attach(map,{focus:()=>({hit:{x:focusX,y:.63},range}),onChange:v=>changes.push({time,drop:v.reduction})});
 const emit=n=>events[n]?.forEach(f=>f());emit('movestart');
 for(time=16;time<=10000;time+=16){x+=span*.9*.016;emit('move');emit('render');if(time-lastFocus>=240){focusX=x;lastFocus=time;}}
 const steady=changes.filter(v=>v.time>2000);assert.ok(speed.reduction()>0,'Fast movement should still reduce detail');
 emit('moveend');assert.ok(nextTimer);nextTimer();assert.equal(speed.reduction(),0,'Settling must restore full detail');speed.destroy();return steady;
}
const baseline=execFileSync('git',['show','f5451a6a018d0fcb4a845e9573ee7edd842e2264:public/motion-detail.js'],{encoding:'utf8'});
const old=run(baseline),fixed=run(fs.readFileSync('public/motion-detail.js','utf8'));
assert.ok(old.length>20,'Fixture must reproduce the shipped stale-focus oscillation');assert.equal(fixed.length,0,'Steady camera movement must not generate repeated LOD changes');
console.log(`PASS: steady-pan LOD changes after warmup ${old.length} -> ${fixed.length}; movement reduction and settled refinement preserved. Controlled camera/focus fixture, not browser FPS.`);
