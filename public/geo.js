/* Geographic calculations; independent of rendering and terrain exaggeration. */
(function(root){
const rad=x=>x*Math.PI/180;
function distance(a,b){const p=rad(b[1]-a[1]),q=rad(b[0]-a[0]);const h=Math.sin(p/2)**2+Math.cos(rad(a[1]))*Math.cos(rad(b[1]))*Math.sin(q/2)**2;return 6371008.8*2*Math.atan2(Math.sqrt(h),Math.sqrt(Math.max(0,1-h)));}
function length(points){return points.slice(1).reduce((s,p,i)=>s+distance(points[i],p),0);}
// Build once per route edit; hover queries only search cumulative distances.
function locator(points){const route=points.map(p=>p.slice()),ds=[0];for(let i=1;i<route.length;i++)ds.push(ds[i-1]+distance(route[i-1],route[i]));return d=>{if(!route.length||!Number.isFinite(d))return null;if(route.length===1)return route[0].slice();d=Math.max(0,Math.min(ds.at(-1),d));let lo=1,hi=route.length-1;while(lo<hi){const mid=(lo+hi)>>>1;if(ds[mid]<d)lo=mid+1;else hi=mid;}const t=(d-ds[lo-1])/(ds[lo]-ds[lo-1]||1),a=route[lo-1],b=route[lo];return[a[0]+t*(b[0]-a[0]),a[1]+t*(b[1]-a[1])];};}
function samples(points,limit=240,spacing=100){
 if(points.length<2)return points.map(p=>({p,d:0}));const ds=[0];for(let i=1;i<points.length;i++)ds.push(ds[i-1]+distance(points[i-1],points[i]));const total=ds.at(-1);if(!total)return[{p:points[0],d:0}];
 const n=Math.max(2,Math.min(limit,Math.ceil(total/spacing)+1));let seg=1;
 const sampled=Array.from({length:n},(_,i)=>{const d=i*total/(n-1);while(seg<ds.length-1&&ds[seg]<d)seg++;const t=(d-ds[seg-1])/(ds[seg]-ds[seg-1]||1);return{p:[points[seg-1][0]+t*(points[seg][0]-points[seg-1][0]),points[seg-1][1]+t*(points[seg][1]-points[seg-1][1])],d};});
 // Keep exact waypoints as well as the uniform samples, preserving switchbacks and corners.
 points.forEach((p,i)=>sampled.push({p,d:ds[i]}));sampled.sort((a,b)=>a.d-b.d);return sampled.filter((s,i)=>!i||s.d-sampled[i-1].d>.01);
}
function focusSamples(points,bounds,limit=400,spacing=2){
 const segments=[];let d=0,total=0;
 for(let i=1;i<points.length;i++){
  const a=points[i-1],b=points[i],len=distance(a,b),dx=b[0]-a[0],dy=b[1]-a[1];let lo=0,hi=1,valid=true;
  for(const [p,q] of [[-dx,a[0]-bounds[0]],[dx,bounds[2]-a[0]],[-dy,a[1]-bounds[1]],[dy,bounds[3]-a[1]]]){if(p===0){if(q<0)valid=false;continue;}const t=q/p;if(p<0)lo=Math.max(lo,t);else hi=Math.min(hi,t);}
  if(valid&&hi>=lo&&len>0){const visible=(hi-lo)*len;segments.push({a,b,lo,hi,len,d,visible});total+=visible;}d+=len;
 }
 if(!total)return[];const step=Math.max(spacing,total/Math.max(1,limit-segments.length*2)),result=[];
 for(const s of segments){const n=Math.max(1,Math.ceil(s.visible/step));for(let i=0;i<=n;i++){const t=s.lo+(s.hi-s.lo)*i/n;result.push({p:[s.a[0]+(s.b[0]-s.a[0])*t,s.a[1]+(s.b[1]-s.a[1])*t],d:s.d+s.len*t});}}
 return result;
}
function tile(p,z){const n=2**z,x=(p[0]+180)/360*n,y=(1-Math.asinh(Math.tan(rad(p[1])))/Math.PI)/2*n,fx=(x-Math.floor(x))*256,fy=(y-Math.floor(y))*256;return{x:Math.floor(x),y:Math.floor(y),fx,fy,px:Math.min(255,Math.max(0,Math.floor(fx))),py:Math.min(255,Math.max(0,Math.floor(fy)))};}
function chartSamples(profile,width=650){
 if(profile.length<=width*2)return profile;const total=profile.at(-1).d||1,result=[];let bucket=[],column=-1;
 const flush=()=>{if(!bucket.length)return;const selected=[bucket[0],bucket.at(-1),bucket.reduce((a,b)=>a.h<b.h?a:b),bucket.reduce((a,b)=>a.h>b.h?a:b)].sort((a,b)=>a.d-b.d);selected.forEach((p,i)=>{if(!i||p!==selected[i-1])result.push(p);});bucket=[];};
 for(const p of profile){if(p.h===null){flush();result.push(p);column=-1;continue;}const next=Math.floor(p.d/total*width);if(next!==column){flush();column=next;}bucket.push(p);}flush();return result;
}
function terrainRGB(r,g,b){return -10000+(r*65536+g*256+b)*0.1;}
function terrarium(r,g,b){return r*256+g+b/256-32768;}
function totals(values){let gain=0,loss=0;const clean=values.map((v,i)=>v===null?null:values.slice(Math.max(0,i-1),Math.min(values.length,i+2)).filter(x=>x!==null).reduce((a,b)=>a+b,0)/values.slice(Math.max(0,i-1),Math.min(values.length,i+2)).filter(x=>x!==null).length);let anchor=clean[0];for(let i=1;i<clean.length;i++){if(clean[i]===null){anchor=null;continue;}if(anchor===null){anchor=clean[i];continue;}const d=clean[i]-anchor;if(Math.abs(d)>=5){if(d>0)gain+=d;else loss-=d;anchor=clean[i];}}const valid=values.filter(v=>v!==null);return{gain,loss,high:valid.length?Math.max(...valid):null,complete:valid.length===values.length};}
const api={distance,length,locator,samples,focusSamples,chartSamples,tile,terrainRGB,terrarium,totals};if(typeof module!=='undefined')module.exports=api;else root.RidgeGeo=api;
})(globalThis);
