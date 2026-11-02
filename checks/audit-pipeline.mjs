import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const engine=fs.readFileSync('public/assets/maplibre-gl.mjs','utf8');
const helper=engine.slice(engine.indexOf('function ridgeIdealFor'),engine.indexOf('const _ridgeRegularMatrixCache'));
function replay(content){
 let now=0,draws=0,ideals=0,reconciles=0;const events={},timeouts=new Map();let serial=0;
 const env={performance:{now:()=>now},setTimeout(fn,ms){timeouts.set(++serial,{fn,at:now+ms});return serial;},clearTimeout:id=>timeouts.delete(id)};
 vm.runInNewContext(helper+fs.readFileSync('public/selection-interval.js','utf8'),env);
 const view={width:390,height:844,zoom:11,pitch:58,bearing:0,elevation:1200,center:{lng:170,lat:-43}},manager={_source:{tileSize:256,type:'raster'},used:true,update(){env.ridgeIdealFor(this,view,null,()=>{ideals++;return [1,2];});reconciles++;}};
 const map={style:{tileManagers:{linz:manager}},on(n,f){events[n]=f;},off(n){delete events[n];},_update(){},triggerRepaint(){},_render(){manager.update();draws++;}};
 const control=env.RidgeSelectionInterval.attach(map,{interval:()=>100,engine:{getVersion:()=> '6.14.0'}});
 for(let frame=0;frame<60;frame++){now=frame*1000/60;view.center.lng+=.00001;for(const [key,timer]of timeouts)if(timer.at<=now){timeouts.delete(key);timer.fn();}if(content)events.sourcedata({sourceDataType:'content',sourceId:'linz'});map._render();}
 const before=ideals;events.moveend();map._render();assert.equal(ideals,before+1,'gesture end selects exactly');
 events.sourcedata({sourceDataType:'content',tile:{dem:{}}});map._render();assert.equal(ideals,before+2,'DEM changes select immediately');control.destroy();
 assert.equal(draws,62);assert.equal(reconciles,62);assert.ok(before>=9&&before<=10);return {draws:60,reconciles:60,ideals:before};
}
const quiet=replay(false),streaming=replay(true);assert.deepEqual(streaming,quiet);
// Exercise the actual worker script and native-style RGBA DEM unpacking against
// the same profile algorithm running directly on an independent source view.
const env={self:{postMessage(m){env.result=m;}},importScripts(file){vm.runInContext(fs.readFileSync('public/'+file,'utf8'),context);}};
const context=vm.createContext(env);vm.runInContext(fs.readFileSync('public/adaptive-terrain-worker.js','utf8'),context);
const A=env.RidgeAdaptiveTerrain;const dim=32,stride=36,bytes=new Uint8Array(stride*stride*4);
for(let y=0;y<stride;y++)for(let x=0;x<stride;x++){const v=Math.round((10000+1000+x*2+y*3)*10),i=(y*stride+x)*4;bytes[i]=v>>16;bytes[i+1]=v>>8;bytes[i+2]=v;bytes[i+3]=255;}
const original=bytes.slice(),unpack=[6553.6,25.6,.1,10000],get=(x,y)=>{const i=((y+2)*stride+x+2)*4;return bytes[i]*unpack[0]+bytes[i+1]*unpack[1]+bytes[i+2]*unpack[2]-unpack[3];};
const dem={dim,data:bytes,sampleBilinear(x,y){const a=Math.floor(x),b=Math.floor(y),u=x-a,v=y-b;return get(a,b)*(1-u)*(1-v)+get(a+1,b)*u*(1-v)+get(a,b+1)*(1-u)*v+get(a+1,b+1)*u*v;}};
for(const delta of [0,1,2]){const sourceId={canonical:{z:8,x:0,y:0}},id={canonical:{z:8+delta,x:0,y:0}};const direct=A.profileJob(dem,id,sourceId);while(!A.advance(direct)){}env.self.onmessage({data:{bytes:delta===0?bytes.buffer.slice(0):null,dim,stride,unpack,id,sourceId,revision:1}});assert.equal(env.result.error,undefined);assert.equal(env.result.min,direct.min);assert.equal(env.result.max,direct.max);assert.deepEqual({...env.result.errors},{...direct.errors});}
assert.deepEqual(bytes,original);console.log(JSON.stringify({quiet,streaming,workerProfilesIdentical:3,livePixelsUnchanged:true}));
