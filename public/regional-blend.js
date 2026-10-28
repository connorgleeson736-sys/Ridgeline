/* Exact geographic source selection; only boundary tiles need a lossless
   canvas composite. No learned pixels, sharpening or geographic synthesis. */
(function(root){
'use strict';
const cancelled=()=>Object.assign(Error('Cancelled'),{name:'AbortError'});
// Rasterise official survey polygons at the requested tile resolution. Pixel
// centres use even/odd filling, so holes remain original and edges never feather.
function vectorAlpha(meta,z,x,y,w,h){
 const data=new Uint8ClampedArray(w*h*4),factor=2**(z-meta.z),dx=(meta.x*factor-x)*w,dy=(meta.y*factor-y)*h;
 const edges=[];for(const ring of meta.rings)for(let i=1;i<ring.length;i++){
  const a=ring[i-1],b=ring[i],ax=a[0]*factor*w/256+dx,ay=a[1]*factor*h/256+dy,bx=b[0]*factor*w/256+dx,by=b[1]*factor*h/256+dy;
  if(ay!==by&&Math.max(ay,by)>.5&&Math.min(ay,by)<h-.5)edges.push([ax,ay,bx,by]);
 }
 for(let row=0;row<h;row++){
  const py=row+.5,xs=[];for(const [ax,ay,bx,by]of edges)if((ay>py)!==(by>py))xs.push(ax+(py-ay)*(bx-ax)/(by-ay));xs.sort((a,b)=>a-b);
  for(let j=0;j+1<xs.length;j+=2){const lo=Math.max(0,Math.ceil(xs[j]-.5)),hi=Math.min(w,Math.ceil(xs[j+1]-.5));for(let col=lo;col<hi;col++)data[(row*w+col)*4+3]=255;}
 }
 return data;
}
async function composite({linz,esri,fine=[],z,x,y,esriZ=z,meta,mask,overlay=false,hardMask=false,compensate=false,checkCancelled=()=>{}},environment=root){
 checkCancelled();let disposed=false;const owned=[];
 const decode=async data=>{const bitmap=await environment.createImageBitmap(new Blob([data]));
  try{checkCancelled();if(disposed)throw cancelled();}catch(error){bitmap.close();throw error;}
  owned.push(bitmap);return bitmap;
 };
 let a,b,m,decoded;
 try{
  decoded=await Promise.all([linz?decode(linz):null,decode(esri),linz&&!overlay&&!meta?.rings?mask:null,...fine.map(row=>decode(row.data))]);
  [a,b,m]=decoded;checkCancelled();
  if(a&&(a.width!==b.width||a.height!==b.height))throw Error('Imagery dimensions differ');
  const w=a?.width||b.width,h=a?.height||b.height,canvas=new environment.OffscreenCanvas(w,h),ctx=canvas.getContext('2d',compensate?{willReadFrequently:true}:undefined),factor=2**(z-esriZ);
  if(w>512||h>512)throw Error('Unexpected imagery dimensions');
  // Esri's verified local ceiling is z18. At z19, crop the correct z18 parent
  // rather than accepting its HTTP-200 "not available" placeholder image.
  ctx.drawImage(b,(x%factor)*b.width/factor,(y%factor)*b.height/factor,b.width/factor,b.height/factor,0,0,w,h);
  if(compensate){const pixels=ctx.getImageData(0,0,w,h);environment.RidgeImagery.compensateEsri(pixels.data);ctx.putImageData(pixels,0,0);}
  let result=canvas;
  if(a&&overlay){const out=new environment.OffscreenCanvas(w,h),oc=out.getContext('2d');oc.drawImage(a,0,0,w,h);oc.drawImage(canvas,0,0);result=out;}
  else if(a){
   const alpha=new environment.OffscreenCanvas(w,h),out=new environment.OffscreenCanvas(w,h),ac=alpha.getContext('2d'),oc=out.getContext('2d');
   const scale=2**(z-meta.z)*w/256,dx=(meta.x/2**meta.z-x/2**z)*2**z*w,dy=(meta.y/2**meta.z-y/2**z)*2**z*h;
   if(meta.rings){const pixels=ac.createImageData(w,h);pixels.data.set(vectorAlpha(meta,z,x,y,w,h));
    if(fine.length){const survey=new environment.OffscreenCanvas(w,h),sc=survey.getContext('2d',{willReadFrequently:true});
     for(let i=0;i<fine.length;i++){const bitmap=decoded[i+3],ff=2**(z-fine[i].z);sc.drawImage(bitmap,(x%ff)*bitmap.width/ff,(y%ff)*bitmap.height/ff,bitmap.width/ff,bitmap.height/ff,0,0,w,h);}
     const valid=sc.getImageData(0,0,w,h).data;for(let i=3;i<valid.length;i+=4)if(valid[i]>0)pixels.data[i]=0;
    }
    ac.putImageData(pixels,0,0);}else{if(hardMask)ac.imageSmoothingEnabled=false;ac.drawImage(m,dx,dy,meta.width*scale,meta.height*scale);}
   ctx.globalCompositeOperation='destination-in';ctx.drawImage(alpha,0,0);
   oc.drawImage(a,0,0,w,h);oc.drawImage(canvas,0,0);result=out;
  }
  checkCancelled();const blob=await result.convertToBlob({type:'image/png'});checkCancelled();const data=await blob.arrayBuffer();checkCancelled();return data;
 }finally{disposed=true;for(const bitmap of owned)bitmap.close();owned.length=0;}
}

const api={composite,cancelled,vectorAlpha};if(typeof module!=='undefined')module.exports=api;else root.RidgeRegionalBlend=api;
})(globalThis);
