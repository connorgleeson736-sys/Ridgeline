/* Worker-safe measured heightfield -> error-controlled RTIN mesh. */
(function(root){
'use strict';
const C=40075016.68557849,martinis=new Map();
function build(heights,{x,y,z,error=1,size=257}={}){
 if(heights.length!==size*size||heights.some(v=>!Number.isFinite(v)))throw Error('Invalid measured heightfield');
 const Martini=root.Martini||(typeof require==='function'?require('./assets/martini/martini.js'):null);
 if(!martinis.has(size))martinis.set(size,new Martini(size));const rtin=martinis.get(size),tile=rtin.createTile(heights),n=size-1;
 // Retain every boundary sample and propagate its importance up the RTIN tree.
 for(let i=0;i<size;i++){tile.errors[i]=Infinity;tile.errors[n*size+i]=Infinity;tile.errors[i*size]=Infinity;tile.errors[i*size+n]=Infinity;}
 for(let i=rtin.numTriangles-1;i>=0;i--){const k=i*4,a=rtin.coords[k],b=rtin.coords[k+1],c=rtin.coords[k+2],d=rtin.coords[k+3],mx=(a+c)>>1,my=(b+d)>>1,cx=mx+my-b,cy=my+a-mx;
  if(i<rtin.numParentTriangles){const local=Math.abs((heights[b*size+a]+heights[d*size+c])*.5-heights[my*size+mx]);tile.errors[my*size+mx]=Math.max(tile.errors[my*size+mx],local+Math.max(tile.errors[((b+cy)>>1)*size+((a+cx)>>1)],tile.errors[((d+cy)>>1)*size+((c+cx)>>1)]));}
 }
 const m=tile.getMesh(Math.max(.05,error)),count=m.vertices.length/2,span=C/2**z;
 const lat=Math.atan(Math.sinh(Math.PI*(1-2*(y+.5)/2**z))),vertical=1/Math.cos(lat),skirt=Math.min(500,Math.max(8,error*4,span/n/vertical*2));
 const p=[],uv=[],norm=[],indices=Array.from(m.triangles),lookup=new Map();let min=Infinity,max=-Infinity;const verticalAt=row=>Math.cosh(Math.PI*(1-2*(y+row/n)/2**z));
 for(let i=0;i<count;i++){
  const a=m.vertices[i*2],b=m.vertices[i*2+1],h=heights[b*size+a];min=Math.min(min,h);max=Math.max(max,h);
  p.push((a/n-.5)*span,h*verticalAt(b),(b/n-.5)*span);uv.push(a/n,1-b/n);
  const dx=(heights[b*size+Math.min(n,a+1)]-heights[b*size+Math.max(0,a-1)])*vertical/((a===0||a===n?1:2)*span/n),dz=(heights[Math.min(n,b+1)*size+a]-heights[Math.max(0,b-1)*size+a])*vertical/((b===0||b===n?1:2)*span/n),len=Math.hypot(dx,1,dz);
  norm.push(-dx/len,1/len,-dz/len);lookup.set(a+','+b,i);
 }
 // Martini winding is upward in east/up/south coordinates.
 const surfaceIndices=indices.length;
 const edges=[Array.from({length:size},(_,i)=>[i,0]),Array.from({length:size},(_,i)=>[n,i]),Array.from({length:size},(_,i)=>[n-i,n]),Array.from({length:size},(_,i)=>[0,n-i])];
 for(const edge of edges){let prev;for(const [a,b] of edge){const top=lookup.get(a+','+b);if(top===undefined)throw Error('RTIN boundary sample missing');const bottom=p.length/3;p.push(p[top*3],p[top*3+1]-skirt*verticalAt(b),p[top*3+2]);uv.push(uv[top*2],uv[top*2+1]);norm.push(...norm.slice(top*3,top*3+3));if(prev)indices.push(prev.top,top,bottom,prev.top,bottom,prev.bottom);prev={top,bottom};}}
 return{positions:new Float32Array(p),normals:new Float32Array(norm),uvs:new Float32Array(uv),indices:new Uint32Array(indices),heights,size,x,y,z,error,surfaceIndices,min,max,span,vertical};
}
function sample(tile,u,v){const n=tile.size-1,x=Math.min(n,Math.max(0,u*n)),y=Math.min(n,Math.max(0,v*n)),a=Math.floor(x),b=Math.floor(y),c=Math.min(n,a+1),d=Math.min(n,b+1),fx=x-a,fy=y-b,h=tile.heights;return h[b*tile.size+a]*(1-fx)*(1-fy)+h[b*tile.size+c]*fx*(1-fy)+h[d*tile.size+a]*(1-fx)*fy+h[d*tile.size+c]*fx*fy;}
const api={C,build,sample};if(typeof module!=='undefined')module.exports=api;else root.RidgeMesh=api;
})(globalThis);
