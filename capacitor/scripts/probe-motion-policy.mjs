// Deterministic camera-event/clock replay of the shipped motion controller.
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const base=path.resolve(process.argv[2]||'capacitor/www');
function replay(rotation){
 let now=0,serial=0,center={lng:170,lat:-43},bearing=0,pitch=58;
 const timers=new Map(),events=new Map(),changes=[];
 const env={performance:{now:()=>now},document:{hidden:false,addEventListener(){},removeEventListener(){}},
  setTimeout(fn,delay){const id=++serial;timers.set(id,{at:now+delay,fn});return id;},clearTimeout:id=>timers.delete(id)};
 vm.runInNewContext(fs.readFileSync(path.join(base,'motion-detail.js'),'utf8'),env);
 const transform={height:820,worldSize:512*2**13,fov:36.87};
 const map={_camera:{transform},getCenter:()=>center,getZoom:()=>13,getBearing:()=>bearing,getPitch:()=>pitch,
  _update(){},triggerRepaint(){},on(n,f){if(!events.has(n))events.set(n,new Set());events.get(n).add(f);},off(n,f){events.get(n)?.delete(f);}};
 const control=env.RidgeMotionDetail.attach(map,{clock:()=>now,onChange:state=>changes.push({at:now,...state})}),emit=n=>{for(const f of events.get(n)||[])f();};
 emit('movestart');
 for(let i=1;i<=20;i++){now=i*100;if(rotation){bearing=i*6;pitch=58+Math.sin(i/4)*12;}else center={lng:170+i*.03,lat:-43};emit('move');emit('render');}
 const atStop=control.stats();emit('moveend');
 const advance=t=>{now=t;for(const [id,timer]of timers)if(timer.at<=now){timers.delete(id);timer.fn();}};
 advance(2349);const after349ms=control.reduction();advance(2350);const after350ms=control.reduction();control.destroy();
 return{gesture:rotation?'120 degree orbit with pitch changes, fixed centre/zoom':'translation at fixed zoom',changes,atStop,after349ms,after350ms};
}
const translation=replay(false),orbit=replay(true);
assert.equal(translation.atStop.motionDetailReduction,2);assert.equal(translation.after349ms,2);assert.equal(translation.after350ms,0);
assert.equal(orbit.atStop.motionDetailReduction,0);assert.equal(orbit.changes.length,0);
console.log(JSON.stringify({translation,orbit,findings:{fullDetailRequestPolicyHeldAfterTranslationMs:350,rotationDoesNotTriggerMotionReduction:true},
 caveat:'Deterministic policy/event timing, not image-ready timing or measured phone FPS. Request rescheduling/rendering adds its own time.'},null,2));
