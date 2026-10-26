import fs from 'node:fs';import vm from 'node:vm';import assert from 'node:assert/strict';
// Same V8 execution context for both algorithms; warm medians, no browser/FPS claim.
for(const f of ['checks/fixtures/martini-baseline.js','checks/fixtures/rtin-baseline.js','public/terrain-data.js','public/terrain-patches.js'])vm.runInThisContext(fs.readFileSync(f,'utf8'),{filename:f});
const root=process.env.THREE_QA_TILES||'/workspace/scratch/3dd2ae8bd0a2/cesium-source-qa',inventory=JSON.parse(fs.readFileSync(root+'/tiles.json')),results=[];
const median=a=>a.sort((x,y)=>x-y)[Math.floor(a.length/2)];
for(const z of [16,18]){const tiles=inventory.filter(t=>t.z===z),xs=[...new Set(tiles.map(t=>t.x))].sort(),ys=[...new Set(tiles.map(t=>t.y))].sort(),records=new Map(tiles.map(t=>{const b=fs.readFileSync(`${root}/${z}-${t.x}-${t.y}.f32`);return[t.x+'/'+t.y,{...t,heights:new Float32Array(b.buffer.slice(b.byteOffset,b.byteOffset+b.byteLength))}];}));
 for(const x of [xs[1],xs[2]]){const y=ys[1],h=RidgeTerrainData.assemble(records,x,y,z),options={x,y,z,error:z===18?.15:1};let old,patch;const previous=[],current=[];
  for(let i=0;i<10;i++){let t=performance.now();old=RidgeMesh.build(h,options);if(i>=3)previous.push(performance.now()-t);t=performance.now();patch=RidgePatches.prepare(h,options);if(i>=3)current.push(performance.now()-t);}
  const before=old.positions.byteLength+old.normals.byteLength+old.uvs.byteLength+old.indices.byteLength+old.heights.byteLength,after=patch.heights.byteLength+patch.normals.byteLength;assert.ok(after<before*.5);
  results.push({z,x,y,previousCPUMedianMilliseconds:+median(previous).toFixed(3),gpuPatchPreparationMedianMilliseconds:+median(current).toFixed(3),preparationSpeedRatio:+(median(previous)/median(current)).toFixed(2),previousTransferBytes:before,newTransferBytes:after,transferReductionPercent:+((1-after/before)*100).toFixed(1),finestPatchMeasuredNodeErrorMetres:patch.errors.at(-1).error});
 }}
const report={benchmark:'Warm CPU preprocessing medians, seven samples per algorithm after three warmups; same V8 process',tiles:results,caveat:'Excludes network, image decode, GPU work and browser overhead. Does not establish FPS or user-device speedup.'};console.log(JSON.stringify(report));
