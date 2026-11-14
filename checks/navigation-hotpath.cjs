const sourceRoot=process.env.RIDGE_PERFORMANCE_SOURCE||'public';
const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
test('view ranking indexes only requested sources and reuses parsed addresses across movement',()=>{
 const events={},scans={linz:0,terrain:0,route:0,satellite:0,topo:0};let parses=0,now=1000,rank,focus=0;
 const context={URL:class extends URL{constructor(...args){super(...args);parses++;}},performance:{now:()=>now},RidgeTerrainStreaming:{priority:()=>focus},module:{exports:{}}};context.globalThis=context;
 vm.runInNewContext(fs.readFileSync(sourceRoot+'/navigation-policy.js','utf8'),context);
 const tile={tileID:{canonical:{z:12,x:10,y:20}}},map={style:{tileManagers:Object.fromEntries(Object.keys(scans).map(source=>[source,{_inViewTiles:{getAllTiles(){scans[source]++;return[tile];}}}]))},on(n,f){events[n]=f;},off(n){delete events[n];}};
 const c=context.module.exports.attach(map,{cache:{setViewPolicy(fn){rank=fn;}},quality:{surfaceFocus:()=>null},residency:{},engine:{pumpImageRequestQueue(){}}});
 const url='https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/12/10/20.webp';
 for(let i=0;i<10;i++){focus=i;now+=150;events.move();assert.equal(rank(url),i);}
 assert.equal(parses,1);assert.equal(scans.linz,10);assert.equal(scans.route,0);assert.equal(scans.terrain,0);
 assert.equal(rank('https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/12/11/20.webp'),109);
 focus=0;assert.equal(rank('ridge://esri/imagery/12/20/10'),0);assert.equal(scans.satellite,1);
 assert.equal(rank('https://basemaps.linz.govt.nz/v1/tiles/elevation/WebMercatorQuad/12/10/20.png'),0);
 assert.equal(rank('https://example.test/unknown'),1000);
 map.style.tileManagers.linz._inViewTiles.getAllTiles=()=>[];events.sourcedataloading();assert.equal(rank(url),100);
 c.destroy();assert.equal(Object.keys(events).length,0);assert.equal(rank,undefined);
});
