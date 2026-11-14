const sourceRoot=process.env.RIDGE_PERFORMANCE_SOURCE||'public';
const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm');
const baseline=new vm.Script('(function(e){let t={};for(let n of this._inViewTiles.getAllTiles().filter(e=>e.hasData()))for(let r of e)n.tileID.isChildOf(r)&&(t[r.key]||=[],t[r.key].push(n));return t})').runInThisContext();
async function setup(){const E=await import('../'+sourceRoot+'/assets/maplibre-gl.mjs');const style=Object.assign(new E.Evented(),{map:{_maxTileCacheSize:192,_maxTileCacheZoomLevels:8},tileManagers:{},dispatcher:{},_layers:{},_checkLoaded(){},_validate:()=>false});style.on('error',()=>{});E.Style.prototype.addSource.call(style,'test',{type:'raster',tiles:['https://example.test/{z}/{x}/{y}'],tileSize:256});await new Promise(resolve=>setTimeout(resolve,0));const m=style.tileManagers.test;
 // Use native tile identities obtained through the actual manager.
 m._loadTile=()=>{};
 // Native projection and source setup expose the tile-ID constructor without network.
 let tr;E.Style.prototype._setProjectionInternal.call({map:{migrateProjection:t=>tr=t},tileManagers:{},_globalState:{}},'mercator');tr.resize(390,844);tr.setZoom(12);tr.setCenter(new E.LngLat(170,-43));
 // A native source update computes tile identities before the no-op loader.
 m._sourceLoaded=true;m.used=true;m.update(tr);const ID=m._inViewTiles.getAllTiles()[0].tileID.constructor;
 return{ID,method:m._getLoadedDescendents,retain:m._retainLoadedChildren,complete:m._areDescendentsComplete,maxOverzooming:m.constructor.maxOverzooming};}
const normalize=result=>Object.fromEntries(Object.entries(result).map(([k,rows])=>[k,rows.map(r=>r.label)]));
test('indexed native descendants match original across random zooms, wraps, overscaling and readiness',async()=>{
 const {ID,method}=await setup();let seed=781;const random=n=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed%n;};
 for(let trial=0;trial<200;trial++){
  const parents=new Set(),tiles=[];const id=()=>{const z=random(9),n=2**z;return new ID(z+random(4),random(3)-1,z,random(n),random(n));};
  const count=random(100);for(let i=0;i<count;i++)parents.add(id());
  for(let i=0;i<250;i++)tiles.push({tileID:id(),label:i,hasData:()=>i%7!==0});
  // Include same-canonical overscaled children and exact-equal IDs deliberately.
  for(const p of parents){tiles.push({tileID:new ID(p.overscaledZ+1,p.wrap,p.canonical.z,p.canonical.x,p.canonical.y),label:'over-'+p.key,hasData:()=>true});tiles.push({tileID:p,label:'equal-'+p.key,hasData:()=>true});}
  const manager={_inViewTiles:{getAllTiles:()=>tiles}};
  assert.deepEqual(normalize(method.call(manager,parents)),normalize(baseline.call(manager,parents)));
 }
});
test('large missing sets avoid quadratic ancestry comparisons; empty sets skip the scan',async()=>{
 const {ID,method}=await setup();let comparisons=0,scans=0;const parents=new Set(),tiles=[];
 for(let i=0;i<512;i++)parents.add(new ID(12,0,12,1000+i,1500));
 for(let i=0;i<1024;i++){const id=new ID(13,0,13,2000+i,3000),native=id.isChildOf;id.isChildOf=function(p){comparisons++;return native.call(this,p);};tiles.push({tileID:id,label:i,hasData:()=>true});}
 const manager={_inViewTiles:{getAllTiles(){scans++;return tiles;}}};
 const expected=normalize(baseline.call(manager,parents)),before=comparisons;comparisons=0;
 assert.deepEqual(normalize(method.call(manager,parents)),expected);assert.equal(before,524288);assert.equal(comparisons,1024);
 scans=0;method.call(manager,new Set());assert.equal(scans,0);
 console.log(JSON.stringify({baselineAncestryComparisons:before,indexedAncestryComparisons:comparisons,reduction:1-comparisons/before}));
});

test('allocation-light child selection matches native completeness, overscale and maximum-overzoom rules',async()=>{
 const {ID,retain,complete,maxOverzooming}=await setup();assert.ok(Number.isFinite(maxOverzooming));
 const original=vm.runInNewContext('(e=>function(t,n){let r=this._getLoadedDescendents(n),i=new Set;for(let a of n){let n=r[a.key];if(!n?.length){i.add(a);continue}let o=a.overscaledZ+e.maxOverzooming,s=n.filter(e=>e.tileID.overscaledZ<=o);if(!s.length){i.add(a);continue}let c=Math.min(...s.map(e=>e.tileID.overscaledZ)),l=s.filter(e=>e.tileID.overscaledZ===c).map(e=>e.tileID);for(let e of l)t[e.key]=e;this._areDescendentsComplete(l,c,a.overscaledZ)||i.add(a)}return i})')({maxOverzooming});
 let seed=107;const random=n=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed%n;};
 for(let trial=0;trial<300;trial++){
  const parent=new ID(5,0,5,10,12),parents=new Set([parent]),rows=[];
  const count=random(40);for(let i=0;i<count;i++){const z=6+random(3);rows.push({tileID:new ID(z+random(maxOverzooming+3),0,z,10*2**(z-5)+random(2**(z-5)),12*2**(z-5)+random(2**(z-5)))});}
  if(trial%3===0)rows.push({tileID:new ID(6,0,5,10,12)});
  const manager={_getLoadedDescendents:()=>({[parent.key]:rows}),_areDescendentsComplete:complete},a={},b={};
  assert.deepEqual([...retain.call(manager,a,parents)].map(x=>x.key),[...original.call(manager,b,parents)].map(x=>x.key));assert.deepEqual(Object.keys(a),Object.keys(b));for(const key of Object.keys(a))assert.equal(a[key],b[key]);
 }
});
