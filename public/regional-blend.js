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
// Preparation owns its decoded inputs until finish/close. A mask-only first
// stage can finish a LINZ tile without ever consuming the optional Esri input.
async function prepare(settings,environment=root){
 const {linz,fine=[],z,x,y,esriZ=z,meta,mask,overlay=false,hardMask=false,compensate=false,bitmap=false,checkCancelled=()=>{}}=settings;
 let disposed=false,finished=false;const owned=[];
 const close=()=>{if(disposed)return;disposed=true;for(const b of owned)b.close();owned.length=0;};
 const check=()=>{checkCancelled();if(disposed)throw cancelled();};
 const decode=async data=>{const b=await environment.createImageBitmap(new Blob([data]));try{check();}catch(e){b.close();throw e;}owned.push(b);return b;};
 try{
  check();
  const decoded=await Promise.all([linz?decode(linz):null,linz&&!overlay&&!meta?.rings?mask:null,...fine.map(row=>decode(row.data))]);
  const [a,m]=decoded;check();let alpha=null,needsEsri=true;
  if(a&&(a.width>512||a.height>512))throw Error('Unexpected imagery dimensions');
  if(a&&!overlay){
   const w=a.width,h=a.height;alpha=new environment.OffscreenCanvas(w,h);const ac=alpha.getContext('2d',{willReadFrequently:true});
   if(meta.rings){const pixels=ac.createImageData(w,h);pixels.data.set(vectorAlpha(meta,z,x,y,w,h));
    if(fine.length){const survey=new environment.OffscreenCanvas(w,h),sc=survey.getContext('2d',{willReadFrequently:true});
     for(let i=0;i<fine.length;i++){const b=decoded[i+2],ff=2**(z-fine[i].z);sc.drawImage(b,(x%ff)*b.width/ff,(y%ff)*b.height/ff,b.width/ff,b.height/ff,0,0,w,h);}
     const valid=sc.getImageData(0,0,w,h).data;for(let i=3;i<valid.length;i+=4)if(valid[i]>0)pixels.data[i]=0;
    }
    ac.putImageData(pixels,0,0);
   }else{
    const scale=2**(z-meta.z)*w/256,dx=(meta.x/2**meta.z-x/2**z)*2**z*w,dy=(meta.y/2**meta.z-y/2**z)*2**z*h;
    if(hardMask)ac.imageSmoothingEnabled=false;ac.drawImage(m,dx,dy,meta.width*scale,meta.height*scale);
   }
   const pixels=ac.getImageData(0,0,w,h).data;needsEsri=false;for(let i=3;i<pixels.length;i+=4)if(pixels[i]){needsEsri=true;break;}
  }
  // Only the original photograph and prepared mask survive a network wait.
  for(let i=decoded.length-1;i>=2;i--){const b=decoded[i];owned.splice(owned.lastIndexOf(b),1);b.close();}
  return{needsEsri,close,async finish(esri=settings.esri){
   if(finished)throw Error('Composition already consumed');finished=true;
   try{
    check();const b=needsEsri?await decode(esri):null;check();
    if(a&&b&&(a.width!==b.width||a.height!==b.height))throw Error('Imagery dimensions differ');
    const w=a?.width||b.width,h=a?.height||b.height;if(w>512||h>512)throw Error('Unexpected imagery dimensions');
    const canvas=new environment.OffscreenCanvas(w,h),ctx=canvas.getContext('2d',compensate?{willReadFrequently:true}:undefined);let result=canvas;
    if(!needsEsri)ctx.drawImage(a,0,0,w,h);
    else{
     const factor=2**(z-esriZ);ctx.drawImage(b,(x%factor)*b.width/factor,(y%factor)*b.height/factor,b.width/factor,b.height/factor,0,0,w,h);
     if(compensate){const p=ctx.getImageData(0,0,w,h);environment.RidgeImagery.compensateEsri(p.data);ctx.putImageData(p,0,0);}
     if(a){const out=new environment.OffscreenCanvas(w,h),oc=out.getContext('2d');
      if(!overlay){ctx.globalCompositeOperation='destination-in';ctx.drawImage(alpha,0,0);}
      oc.drawImage(a,0,0,w,h);oc.drawImage(canvas,0,0);result=out;
     }
    }
    check();if(bitmap&&typeof result.transferToImageBitmap==='function')return result.transferToImageBitmap();
    const blob=await result.convertToBlob({type:'image/png'});check();const data=await blob.arrayBuffer();check();return data;
   }finally{close();}
  }};
 }catch(error){close();throw error;}
}
async function composite(settings,environment=root){const prepared=await prepare(settings,environment);return prepared.finish(settings.esri);}

const api={prepare,composite,cancelled,vectorAlpha};if(typeof module!=='undefined')module.exports=api;else root.RidgeRegionalBlend=api;
})(globalThis);
