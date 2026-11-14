/* One explicitly owned DEM snapshot, never the renderer's live/transferred buffer. */
importScripts('adaptive-terrain.js');
let snapshot=null;
self.onmessage=({data:m})=>{
 try{
  if(m.bytes)snapshot=new Uint8Array(m.bytes);
  if(!snapshot)throw Error('Missing DEM snapshot');
  const bytes=snapshot,[red,green,blue,base]=m.unpack;
  const get=(x,y)=>{const i=((y+2)*m.stride+x+2)*4;return bytes[i]*red+bytes[i+1]*green+bytes[i+2]*blue-base;};
  const dem={dim:m.dim,data:bytes,sampleBilinear(x,y){const ix=Math.floor(x),iy=Math.floor(y),fx=x-ix,fy=y-iy;if(ix< -1||iy< -1||ix>=m.dim||iy>=m.dim)throw Error('Invalid DEM coordinates');return get(ix,iy)*(1-fx)*(1-fy)+get(ix+1,iy)*fx*(1-fy)+get(ix,iy+1)*(1-fx)*fy+get(ix+1,iy+1)*fx*fy;}};
  const job=RidgeAdaptiveTerrain.profileJob(dem,m.id,m.sourceId,m.revision);
  if(!job)throw Error('Invalid profile input');while(!RidgeAdaptiveTerrain.advance(job)){}
  self.postMessage({min:job.min,max:job.max,errors:job.errors});
 }catch(error){self.postMessage({error:String(error)});}
};
