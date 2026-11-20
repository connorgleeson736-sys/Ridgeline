// Run the authoritative 0.18 source algorithms at build time to produce native-port goldens.
import fs from 'node:fs';import path from 'node:path';import vm from 'node:vm';import crypto from 'node:crypto';
const root=path.resolve(import.meta.dirname,'../..'),ctx={};
for(const name of ['gap-imagery-atlas.js','regional-imagery.js','regional-blend.js'])vm.runInNewContext(fs.readFileSync(path.join(root,'public',name),'utf8'),ctx);
const cases=[];let i=0;
for(const [key,entry]of Object.entries(ctx.RidgeGapAtlas.entries)){
 for(const d of [0,1,3,6]){
  const f=2**d,z=entry.z+d,x=entry.x*f+(i*17+3)%f,y=entry.y*f+(i*7+1)%f;
  const mode=ctx.RidgeRegionalImagery.mode(z,x,y,entry),row={key,z,x,y,mode};
  if(i%7===0){const rgba=ctx.RidgeRegionalBlend.vectorAlpha({...entry,rings:entry.outer},z,x,y,256,256);const mask=Uint8Array.from({length:65536},(_,i)=>rgba[i*4+3]);row.mask=crypto.createHash('sha256').update(mask).digest('hex');}
  cases.push(row);
 }i++;
}
const out=path.join(root,'native-wgpu/android/app/src/test/fixtures');fs.mkdirSync(out,{recursive:true});
fs.writeFileSync(path.join(out,'source-goldens.json'),JSON.stringify({reference:'d880143418ac46ece98adff0b3b2fbaedf044d46',cases}));
console.log(`${cases.length} routing cases, ${cases.filter(r=>r.mask).length} exact masks`);
