// Research-only quadtree index meshes over the original 129x129 vertex grid.
// Uses conservative affine residual ranges, not a visual error guess.
// No renderer integration, production quality change or GPU timing claim.
import fs from 'node:fs';
import path from 'node:path';
import {createRequire} from 'node:module';
import assert from 'node:assert/strict';
const require=createRequire(import.meta.url),A=require('../../public/adaptive-terrain.js');
const directory=path.resolve(process.argv[2]),fixtures=JSON.parse(fs.readFileSync(path.join(directory,'dem-fixtures.json')));
const native=A.geometry(128),nativeSkirts=native.indices.slice(native.surfaceTriangles*3);
const W=129,at=(h,x,y)=>h[y*W+x];
function residual(h,x,y,size){
 const origin=at(h,x,y),dx=(at(h,x+size,y)-origin)/size,dy=(at(h,x,y+size)-origin)/size;
 let min=Infinity,max=-Infinity;
 for(let j=0;j<=size;j++)for(let i=0;i<=size;i++){const r=at(h,x+i,y+j)-(origin+dx*i+dy*j);min=Math.min(min,r);max=Math.max(max,r);}
 return max-min;
}
function metadata(h){
 const cells=new Float64Array(21845);
 function visit(x,y,size,node){cells[node]=size===1?0:residual(h,x,y,size);if(size>1)for(let i=0;i<4;i++)visit(x+i%2*size/2,y+(i>>1)*size/2,size/2,node*4+1+i);}
 visit(0,0,128,0);return cells;
}
function build(h,cells,metres){
 const leaves=[],points=new Uint8Array(W*W);
 function visit(x,y,size,node){const error=cells[node];
  if(size===1||error+.02<=metres){leaves.push({x,y,size,error});return;}
  for(let i=0;i<4;i++)visit(x+i%2*size/2,y+(i>>1)*size/2,size/2,node*4+1+i);
 }
 visit(0,0,128,0);
 for(const {x,y,size:s}of leaves)for(const dx of [0,s])for(const dy of [0,s])points[(y+dy)*W+x+dx]=1;
 // Every neighbour tile still meets the exact original 128-segment border.
 for(let i=0;i<=128;i++){points[i]=points[128*W+i]=points[i*W]=points[i*W+128]=1;}
 const data=new Uint16Array(32768*3),vertex=(x,y)=>y*W+x,owners=new Uint16Array(128*128);let count=0,leafIndex=0;
 for(const leaf of leaves){const {x,y,size:s}=leaf;leaf.start=count;
  const triangle=(a,b,c)=>{data[count++]=a;data[count++]=b;data[count++]=c;};
  if(s===1){triangle(vertex(x,y),vertex(x,y+1),vertex(x+1,y+1));triangle(vertex(x,y),vertex(x+1,y+1),vertex(x+1,y));}
  else{
   // Construct clockwise perimeter explicitly so all fan triangles match native winding.
   const ring=[];
   for(let v=y;v<y+s;v++)if(points[vertex(x,v)])ring.push(vertex(x,v));
   for(let v=x;v<x+s;v++)if(points[vertex(v,y+s)])ring.push(vertex(v,y+s));
   for(let v=y+s;v>y;v--)if(points[vertex(x+s,v)])ring.push(vertex(x+s,v));
   for(let v=x+s;v>x;v--)if(points[vertex(v,y)])ring.push(vertex(v,y));
   const centre=vertex(x+s/2,y+s/2);for(let i=0;i<ring.length;i++)triangle(centre,ring[i],ring[(i+1)%ring.length]);
  }
  leaf.end=count;for(let j=y;j<y+s;j++)for(let i=x;i<x+s;i++)owners[j*128+i]=leafIndex;
  leafIndex++;
 }
 assert.ok(count<=data.length);
 return{leaves,owners,indices:data.subarray(0,count),triangles:count/3+1024};
}
function verify(mesh,h,tolerance){
 const edges=new Map();let area=0;
 for(let i=0;i<mesh.indices.length;i+=3){const ids=Array.from(mesh.indices.subarray(i,i+3)),p=ids.map(v=>[v%W,Math.floor(v/W)]);
  const cross=(p[1][0]-p[0][0])*(p[2][1]-p[0][1])-(p[2][0]-p[0][0])*(p[1][1]-p[0][1]);assert.ok(cross<0);area-=cross/2;
  for(let j=0;j<3;j++){const a=ids[j],b=ids[(j+1)%3],key=Math.min(a,b)+'/'+Math.max(a,b);edges.set(key,(edges.get(key)||0)+1);}
 }
 assert.equal(area,128*128);let boundary=0;
 for(const [key,count]of edges){assert.ok(count===1||count===2,'Manifold, no overlapping faces');if(count===1){boundary++;const [a,b]=key.split('/').map(Number),ax=a%W,ay=Math.floor(a/W),bx=b%W,by=Math.floor(b/W);assert.ok(ax===bx&&(ax===0||ax===128)||ay===by&&(ay===0||ay===128));assert.equal(Math.hypot(ax-bx,ay-by),1);}}
 assert.equal(boundary,512,'Fine boundary must match original terrain');
 let seed=7919,maxError=0;
 const random=()=>{seed=(1664525*seed+1013904223)>>>0;return seed/2**32;};
 for(let sample=0;sample<2000;sample++){
  const x=random()*128,y=random()*128,ix=Math.floor(x),iy=Math.floor(y),u=x-ix,v=y-iy;
  const a=at(h,ix,iy),b=at(h,ix,iy+1),c=at(h,ix+1,iy+1),d=at(h,ix+1,iy);
  const fine=v>=u?a*(1-v)+b*(v-u)+c*u:a*(1-u)+c*v+d*(u-v);
  const leaf=mesh.leaves[mesh.owners[iy*128+ix]];let coarse=null;
  for(let index=leaf.start;index<leaf.end;index+=3){const ids=Array.from(mesh.indices.subarray(index,index+3)),[[ax,ay],[bx,by],[cx,cy]]=ids.map(v=>[v%W,Math.floor(v/W)]),det=(by-cy)*(ax-cx)+(cx-bx)*(ay-cy);
   const r=((by-cy)*(x-cx)+(cx-bx)*(y-cy))/det,s=((cy-ay)*(x-cx)+(ax-cx)*(y-cy))/det;
   if(r>=-1e-9&&s>=-1e-9&&r+s<=1+1e-9){coarse=r*h[ids[0]]+s*h[ids[1]]+(1-r-s)*h[ids[2]];break;}
  }
  assert.notEqual(coarse,null);const error=Math.abs(coarse-fine);maxError=Math.max(maxError,error);
  assert.ok(error<=tolerance+1e-7,'Measured geometric error stays within tolerance');
 }
 return{maxSampledErrorMetres:maxError,sampledPoints:2000,boundaryEdges:boundary};
}
const rows=[];
for(const fixture of fixtures){if(fixture.error)continue;const bytes=fs.readFileSync(path.join(directory,fixture.file)),h=new Float64Array(bytes.buffer.slice(bytes.byteOffset,bytes.byteOffset+bytes.byteLength));assert.equal(h.length,W*W);
 const started=performance.now(),cells=metadata(h),metadataMs=performance.now()-started;
 const errors={};for(const level of [32,64]){const step=128/level;let error=0;for(let y=0;y<128;y+=step)for(let x=0;x<128;x+=step)error=Math.max(error,residual(h,x,y,step));errors[level]=error+Math.max(.01,Math.max(Math.abs(fixture.min),Math.abs(fixture.max))*2e-6);}
 for(const heightPixels of [.05,.2,.8]){
  const tolerance=.6/heightPixels,level=A.levelFor({heightPixels,span:256},{errors}),baseline=A.geometry(level);
  const timings=[];let mesh;for(let trial=0;trial<5;trial++){const before=performance.now();mesh=build(h,cells,tolerance);timings.push(performance.now()-before);}
  const indexBuildMs=timings.slice(2).sort((a,b)=>a-b)[1],validation=verify(mesh,h,tolerance);
  // All original skirts are appended verbatim; they use the original vertex buffer.
  assert.equal(nativeSkirts.length/3,1024);
  rows.push({name:fixture.name,tile:fixture.tile,heightPixels,toleranceMetres:tolerance,uniformGrid:level,uniformTriangles:baseline.triangles,
   localTriangles:mesh.triangles,metadataMs,indexBuildMs,indexBytes:(mesh.indices.length+nativeSkirts.length)*2,leaves:mesh.leaves.length,
   conservativeErrorLimitPixels:.6,...validation});
 }
}
const result={purpose:'Research-only local adaptive terrain, same 0.6 physical-pixel height-error budget, original fine borders/skirts',
 caveat:'Real provider DEM fields, explicit projection sensitivities, duplicated input borders. Host CPU and triangle counts only; no Android/GPU/FPS measurement. Does not reduce terrain downloads.',
 fields:fixtures.filter(r=>!r.error).length,scenes:rows.length,uniformTriangles:rows.reduce((s,r)=>s+r.uniformTriangles,0),localTriangles:rows.reduce((s,r)=>s+r.localTriangles,0),rows};
fs.writeFileSync(path.join(directory,'local-terrain-results.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result,null,2));
