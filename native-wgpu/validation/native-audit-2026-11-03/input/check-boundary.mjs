// Run from the Ridgeline checkout root. Node 20+. No external dependencies.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
const file = readFileSync('native-wgpu/android/app/src/main/assets/imagery-boundary.geojson');
const hash = createHash('sha1').update('blob ' + file.length + '\0').update(file).digest('hex');
assert.equal(hash, '5a2d23f8bbbcfa5bc788538e9858b7c098bdaef1');
const mercator = ([lon,lat]) => [(lon+180)/360,(1-Math.asinh(Math.tan(lat*Math.PI/180))/Math.PI)/2];
const paths = JSON.parse(file).features.flatMap(f => f.geometry.coordinates.map(path => path.map(mercator)));
const chunkSize = 32;
function bounds(points,lo,hi) {
  let x0=Infinity,y0=Infinity,x1=-Infinity,y1=-Infinity;
  for(let i=lo;i<=hi;i++){const [x,y]=points[i];x0=Math.min(x0,x);y0=Math.min(y0,y);x1=Math.max(x1,x);y1=Math.max(y1,y);}
  return [x0,y0,x1,y1];
}
const indexed = paths.map(points => {
  const chunks=[];
  for(let i=1;i<points.length;i+=chunkSize){
    const end=Math.min(i+chunkSize,points.length);
    chunks.push({first:i,end,bounds:bounds(points,i-1,end-1)});
  }
  return {points,chunks,bounds:bounds(points,0,points.length-1)};
});
const overlaps = (a,b) => !(a[2]<b[0] || a[0]>b[2] || a[3]<b[1] || a[1]>b[3]);
const same = (a,b) => a[0]===b[0] && a[1]===b[1];
// Direct port of MapLayers.clip, including fragment termination and exact endpoint arithmetic.
function clip(path,box,useIndex,counter) {
  if(useIndex && !overlaps(path.bounds,box))return [];
  const output=[];let part=[];
  function flush(){if(part.length>1)output.push(part);part=[];}
  function run(first,end){
    for(let i=first;i<end;i++) {
      counter.count++;
      const a=path.points[i-1], b=path.points[i], dx=b[0]-a[0],dy=b[1]-a[1];
      let t0=0,t1=1;
      function edge(p,q){if(p===0)return q>=0;const t=q/p;
        if(p<0){if(t>t1)return false;t0=Math.max(t0,t);}
        else{if(t<t0)return false;t1=Math.min(t1,t);}
        return true;
      }
      if(edge(-dx,a[0]-box[0])&&edge(dx,box[2]-a[0])&&edge(-dy,a[1]-box[1])&&edge(dy,box[3]-a[1])){
        const p=[a[0]+dx*t0,a[1]+dy*t0],q=[a[0]+dx*t1,a[1]+dy*t1];
        if(part.length&&!same(part.at(-1),p))flush();
        if(!part.length)part.push(p);
        part.push(q);
      }else if(part.length)flush();
    }
  }
  if(!useIndex)run(1,path.points.length);
  else for(const chunk of path.chunks){
    if(overlaps(chunk.bounds,box))run(chunk.first,chunk.end);
    else if(part.length)flush();
  }
  flush();return output;
}
const sourceEdges = paths.reduce((sum,p)=>sum+p.length-1,0);
function cover(z) {
  const n=2**z,[cx,cy]=mercator([170.14219572,-43.5955671]);
  const x=Math.floor(cx*n),y=Math.floor(cy*n);const boxes=[];
  for(let j=-2;j<2;j++)for(let i=-3;i<3;i++)boxes.push([(x+i)/n,(y+j)/n,(x+i+1)/n,(y+j+1)/n]);
  return boxes;
}
function publish(boxes,useIndex){
  const counter={count:0},rows=[];
  for(const box of boxes){const pathsForTile=[];
    for(const path of indexed)pathsForTile.push(...clip(path,box,useIndex,counter));
    rows.push(pathsForTile);
  }
  return {rows,tests:counter.count};
}
const median = times => [...times].sort((a,b)=>a-b)[times.length>>1];
for(const zoom of [10,12,14]){
  const boxes=cover(zoom);
  const baseline=publish(boxes,false), proposed=publish(boxes,true);
  assert.deepEqual(baseline.rows,proposed.rows);
  const fragments=baseline.rows.flat().length;
  const timings=[[],[]];let consumed=0;
  for(let round=0;round<16;round++){
    for(const mode of (round%2?[1,0]:[0,1])){
      const start=performance.now();const r=publish(boxes,!!mode);
      const elapsed=performance.now()-start;consumed+=r.tests;
      if(round>=5)timings[mode].push(elapsed);
    }
  }
  assert.ok(consumed>0);
  console.log({zoom,sourceEdges,sourcePaths:paths.length,tiles:boxes.length,fragments,
    beforeTests:baseline.tests,indexedTests:proposed.tests,medianHostMs:timings.map(t=>+median(t).toFixed(3))});
}
