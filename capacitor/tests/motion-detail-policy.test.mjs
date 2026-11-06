// Deterministic camera/clock replay of the generated app motion controller.
import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
const source=await readFile(new URL('../www/motion-detail.js',import.meta.url),'utf8');
function replay(step){
 let now=0,serial=0;const cam={center:{lng:170,lat:-43},bearing:0,pitch:58,zoom:13};
 const timers=new Map(),events=new Map(),changes=[];
 const env={performance:{now:()=>now},document:{hidden:false,addEventListener(){},removeEventListener(){}},
  setTimeout(fn,delay){const id=++serial;timers.set(id,{at:now+delay,fn});return id;},clearTimeout:id=>timers.delete(id)};
 vm.runInNewContext(source,env);
 const map={_camera:{transform:{height:820,worldSize:512*2**13,fov:36.87}},getCenter:()=>cam.center,getZoom:()=>cam.zoom,getBearing:()=>cam.bearing,getPitch:()=>cam.pitch,
  _update(){},triggerRepaint(){},on(n,f){if(!events.has(n))events.set(n,new Set());events.get(n).add(f);},off(n,f){events.get(n)?.delete(f);}};
 const control=env.RidgeMotionDetail.attach(map,{clock:()=>now,onChange:s=>changes.push({at:now,...s})}),emit=n=>{for(const f of events.get(n)||[])f();};
 emit('movestart');for(let i=1;i<=20;i++){now=i*100;step(cam,i);emit('move');emit('render');}
 const atStop=control.reduction();emit('moveend');const atEnd=control.reduction();
 return {atStop,atEnd,changes,pendingTimers:timers.size};
}
test('fast translation still reduces detail while moving, and full detail returns at moveend',()=>{
 const r=replay((c,i)=>{c.center={lng:170+i*.03,lat:-43};});
 assert.equal(r.atStop,2);assert.equal(r.atEnd,0);assert.equal(r.pendingTimers,0);
});
test('orbiting keeps the website policy: full detail, no reduction',()=>{
 const r=replay((c,i)=>{c.bearing=i*6;c.pitch=58+Math.sin(i/4)*12;});
 assert.equal(r.atStop,0);assert.equal(r.changes.length,0);
});
