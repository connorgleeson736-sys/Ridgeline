// Mathematical port of the finite same-resolution source path in DemSampling.kt.
// The real Kotlin/Android renderer is not executed. Run from the repository root.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
const file=readFileSync('native-wgpu/android/app/src/main/java/nz/ridgeline/nativeapp/DemSampling.kt');
const blob=createHash('sha1').update('blob '+file.length+'\0').update(file).digest('hex');
const text=file.toString();
assert.ok(text.includes('(geometryZoom-1).coerceIn(0,18)'));
assert.ok(text.includes('(x*256.0+i*2.0)/factor-.5'));
console.log('DemSampling git blob',blob);
function source(z,x,y) {
  const n=2**z,mx=Math.floor(170.14219572/360*n+n/2),my=Math.floor((1-Math.asinh(Math.tan(-43.5955671*Math.PI/180))/Math.PI)/2*n);
  return [mx&~1 | x,my&~1 | y];
}
const key=(x,y)=>x+','+y;
function makeReader(srcZ,mainX,mainY,available) {
  const limit=256*2**srcZ;
  return (gx,gy)=>{
    gx=(gx%limit+limit)%limit;gy=Math.min(limit-1,Math.max(0,gy));
    const tx=Math.floor(gx/256),ty=Math.floor(gy/256);
    if(available && !available.has(key(tx-mainX,ty-mainY)))return undefined;
    const h=(Math.imul(gx,2654435761)^Math.imul(gy,1597334677))>>>0;
    return Math.fround((h%65001)/10-200);
  };
}
function grid(z,x,y,onlyMain) {
  const srcZ=Math.min(18,Math.max(0,z-1)),factor=2**(z-srcZ);
  const read=makeReader(srcZ,Math.floor(x/factor),Math.floor(y/factor),onlyMain?new Set(['0,0']):null);
  const out=new Float32Array(129*129);
  for(let j=0;j<=128;j++)for(let i=0;i<=128;i++){
    const px=(x*256+i*2)/factor-.5,py=(y*256+j*2)/factor-.5;
    const ix=Math.floor(px),iy=Math.floor(py),fx=px-ix,fy=py-iy;
    const a=read(ix,iy),b=read(ix+1,iy),c=read(ix,iy+1),d=read(ix+1,iy+1);
    out[j*129+i]=a===undefined||b===undefined||c===undefined||d===undefined?NaN:
      a*(1-fx)*(1-fy)+b*fx*(1-fy)+c*(1-fx)*fy+d*fx*fy;
  }
  return out;
}
let cases=0,matched=0;
for(const z of [8,12,16,19]){
  for(const quadrantX of [0,1])for(const quadrantY of [0,1]){
    const [x,y]=source(z,quadrantX,quadrantY),reference=grid(z,x,y,false),partial=grid(z,x,y,true);
    const rb=new Uint32Array(reference.buffer),pb=new Uint32Array(partial.buffer);
    let vertices=0,cells=0;
    for(let j=0;j<=128;j++)for(let i=0;i<=128;i++){
      const k=j*129+i;
      if(!Number.isFinite(partial[k]))continue;
      assert.equal(pb[k],rb[k]);vertices++;
      if(i<128&&j<128&&[1,129,130].every(offset=>Number.isFinite(partial[k+offset])))cells++;
    }
    assert.equal(vertices,128*128);assert.equal(cells,127*127);cases++;matched+=vertices;
  }
}
console.log({cases,matchedVertexBits:matched,exactCellsPerPatch:127*127,totalCells:128*128,
  exactFraction:(127/128)**2,completeVerticesPerPatch:128**2,totalVertices:129**2});
