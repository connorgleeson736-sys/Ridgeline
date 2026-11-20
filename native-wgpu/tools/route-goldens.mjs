// Build-time outputs of exact authoritative geographic helpers, not runtime JS.
import fs from 'node:fs';import path from 'node:path';import vm from 'node:vm';
const root=path.resolve(import.meta.dirname,'../..'),c={};vm.runInNewContext(fs.readFileSync(path.join(root,'public/geo.js'),'utf8'),c);const G=c.RidgeGeo;
let seed=418;const rand=()=>((seed=(Math.imul(seed,1664525)+1013904223)>>>0)/2**32);
const cases=[];
for(let i=0;i<100;i++){
 const points=Array.from({length:2+i%17},()=>[167+rand()*10,-47+rand()*12]);if(i%7===0)points[1]=points[0].slice();
 const length=G.length(points),loc=G.locator(points),heights=Array.from({length:i%23},()=>rand()<.15?null:Math.round(rand()*4000));
 cases.push({points,length,locations:[-.5,0,.25,.5,1,2].map(t=>({distance:t*length,point:loc(t*length)})),heights,totals:G.totals(heights),samples:G.samples(points,25,10000)});
}
const rounded=[170.0000005,170.0000015,-43.0000005,-43.0000015,0.0000005,-0.0000005].map(value=>({value,result:Number(value.toFixed(6))}));
const out=path.join(root,'native-wgpu/android/app/src/test/fixtures');fs.writeFileSync(path.join(out,'route-goldens.json'),JSON.stringify({reference:'d880143418ac46ece98adff0b3b2fbaedf044d46',cases,rounded}));console.log('100 route/profile helper cases and binary rounding boundaries');
