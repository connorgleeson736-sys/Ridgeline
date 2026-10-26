const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const data=require('../public/alpine-data.js'),labels=require('../public/alpine-labels.js');
assert.equal(data.peaks.length,18);assert.equal(data.routes,undefined,'Mountain route catalogue removed');
for(const p of data.peaks.filter(p=>p.source)){assert.equal(new URL(p.source).hostname,'climbnz.org.nz');assert.ok(p.height>3000&&p.height<3800);}
const overlapping=[{short:'First',priority:1,minZoom:9},{short:'Second',priority:2,minZoom:9}];assert.deepEqual([...labels.visible(overlapping,1,11,()=>({x:100,y:100}),1000,600)],[1]);
process.env.TEST_FIXTURE='1';const f=require('./base-viewer.cjs');delete process.env.TEST_FIXTURE;
for(const file of ['geo.js','alpine-data.js','alpine-labels.js','imagery-preload.js','performance.js','native-residency.js','tile-cache.js','imagery.js','terrain-core.js','app.js'])vm.runInContext(fs.readFileSync('public/'+file,'utf8'),f.context);
const map=f.getMap();map.trigger('style.load');map.trigger('load');assert.equal(map.getSource('alpine-routes'),undefined);assert.equal(map.getLayer('alpine-route-lines'),undefined);assert.equal(f.nodes['peak-list'].children.length,18);assert.equal(f.nodes.hillshade.checked,false);assert.equal(map.getLayer('terrain-shading').layout.visibility,'none');
f.nodes['plan-shortcut'].onclick();f.nodes.draw.onclick();map.trigger('click',{lngLat:{lng:170.14,lat:-43.595}});map.trigger('click',{lngLat:{lng:170.145,lat:-43.59}});f.nodes.finish.onclick();assert.equal(map.getSource('route').data.features.length,1,'User sketch retained');f.nodes['save-route'].onclick();assert.equal(f.nodes['saved-count'].textContent,1);
f.nodes.hillshade.checked=true;f.nodes.hillshade.onchange();assert.equal(map.getLayer('terrain-shading').layout.visibility,'visible','Shading remains opt-in');
f.nodes.search.value='Hicks';f.nodes.search.oninput();assert.equal(f.nodes['search-results'].children.length,1,'Peak-only search has no old routes');
console.log('PASS: 18 peak labels / directory, selected-label priority, mountain catalogue and map overlays removed, shading off by default / opt-in works, user sketch + local save preserved.');
