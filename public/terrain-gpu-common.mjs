import './terrain-patches.js';
const P=globalThis.RidgePatches;
export class PatchGeometryCache{
 constructor(){this.levels=new Map();this.bytes=0;}
 get(cells){if(!this.levels.has(cells)){const grid=P.grid(cells,1),count=grid.skirts.length,vertices=new Float32Array(count*3);for(let i=0;i<count;i++){vertices[i*3]=grid.uvs[i*2];vertices[i*3+1]=1-grid.uvs[i*2+1];vertices[i*3+2]=grid.skirts[i];}const indices=grid.quadrants[0].indices;this.bytes+=vertices.byteLength+indices.byteLength;this.levels.set(cells,{cells,vertices,indices,index:{count:indices.length}});}return this.levels.get(cells);}
 dispose(){this.levels.clear();this.bytes=0;}
}
export function pickHeightfield(ray,tiles,origin,cos,exaggeration){
 let best=null;
 for(const tile of tiles){const d=tile.data;if(!d)continue;const span=d.span,x0=tile.x*span-origin[0],z0=tile.y*span-origin[1],low=Math.min(-1000,d.min-1000)*cos*d.vertical,high=(d.max+100)*cos*d.vertical;let near=0,far=Infinity;
  const mins=[x0*cos,low*exaggeration,z0*cos],maxs=[(x0+span)*cos,high*exaggeration,(z0+span)*cos];
  for(let k=0;k<3;k++){const axis=['x','y','z'][k],o=ray.origin[axis],v=ray.direction[axis];if(Math.abs(v)<1e-12){if(o<mins[k]||o>maxs[k]){far=-1;break;}}else{let a=(mins[k]-o)/v,b=(maxs[k]-o)/v;if(a>b)[a,b]=[b,a];near=Math.max(near,a);far=Math.min(far,b);}}
  if(far<near||far<0||best&&near>best.distance)continue;far=Math.min(far,best?.distance??far);
  const steps=Math.min(2048,Math.max(8,Math.ceil((far-near)*Math.hypot(ray.direction.x,ray.direction.z)/(span*cos/(d.size-1)) *2))),dt=(far-near)/steps;
  const value=t=>{const x=ray.origin.x+ray.direction.x*t,z=ray.origin.z+ray.direction.z*t,u=(x/cos-x0)/span,v=(z/cos-z0)/span,h=P.sample(d.heights,d.size,u,v)*Math.cosh(Math.PI*(1-2*(tile.y+v)/2**tile.z))*cos*exaggeration;return{value:ray.origin.y+ray.direction.y*t-h,u,v};};
  let previous=value(near),last=near;for(let i=1;i<=steps;i++){const t=near+i*dt,current=value(t);if(previous.value>=0&&current.value<=0){let a=last,b=t;for(let k=0;k<22;k++){const m=(a+b)/2;if(value(m).value>0)a=m;else b=m;}const distance=(a+b)/2,hit=value(distance),q=(hit.u>=.5?1:0)+(hit.v>=.5?2:0);if(!((tile.coverage||0)&(1<<q))){best={distance,x:ray.origin.x+ray.direction.x*distance,z:ray.origin.z+ray.direction.z*distance};break;}}previous=current;last=t;}
 }
 return best;
}
