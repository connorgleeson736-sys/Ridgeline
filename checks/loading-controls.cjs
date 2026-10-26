const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const C=require('../public/loading-controls.js');
(async()=>{
 const E=await import('../public/assets/maplibre-gl.mjs');const oldMoving=E.config.MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME,oldStationary=E.getMaxParallelImageRequests();
 assert.ok(C.applyEngine(E,{moving:12,stationary:20}));assert.equal(E.config.MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME,12);assert.equal(E.getMaxParallelImageRequests(),20);
 E.config.MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME=oldMoving;E.setMaxParallelImageRequests(oldStationary);
 assert.equal(C.normalize({moving:1000,stationary:-10,predictive:NaN}).moving,96);assert.equal(C.normalize({stationary:-10}).stationary,1);assert.equal(C.normalize({predictive:0}).predictive,0);assert.equal(C.defaults(true).stationary,6);
 // Actual app startup and UI callbacks in the existing DOM/engine fixture.
 process.env.TEST_FIXTURE='1';const f=require('./base-viewer.cjs'),ctx=f.context;delete process.env.TEST_FIXTURE;
 let stored='{"moving":12,"stationary":20,"imageryPending":1,"demPending":0,"predictive":0}',writes=0;
 ctx.localStorage={getItem:key=>key==='ridgeline-loading-v1'?stored:null,setItem(key,value){if(key==='ridgeline-loading-v1'){stored=value;writes++;}}};
 ctx.maplibregl.setMaxParallelImageRequests=n=>ctx.maplibregl.config.MAX_PARALLEL_IMAGE_REQUESTS=n;
 for(const file of ['loading-controls.js','geo.js','alpine-data.js','alpine-labels.js','surface-focus.js','performance.js','terrain-streaming.js','raster-residency.js','dem-revision.js','terrain-visibility.js','terrain-composition.js','tile-retry.js','native-residency.js','imagery-diagnostics.js','imagery.js','imagery-preload.js','prepare-ahead.js','tile-cache.js','terrain-core.js'])vm.runInContext(fs.readFileSync('public/'+file,'utf8'),ctx);
 vm.runInContext(fs.readFileSync('public/app.js','utf8'),ctx);const map=f.getMap();map.trigger('style.load');
 assert.equal(ctx.maplibregl.config.MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME,12);assert.equal(ctx.maplibregl.config.MAX_PARALLEL_IMAGE_REQUESTS,20,'initMap must preserve saved stationary preference');
 assert.equal(f.nodes['loading-predictive-value'].textContent,'Off');
 let applied;vm.runInContext('renderQuality.setLoadingLimits=values=>globalThis.lastLimits=values',ctx);
 f.nodes['loading-moving'].value='96';f.nodes['loading-moving'].events.input();assert.equal(ctx.maplibregl.config.MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME,96);assert.equal(JSON.parse(stored).moving,96);assert.equal(ctx.lastLimits.moving,96);
 // The new controls do not recreate maps, flush caches, or invoke detail refresh.
 assert.equal(f.getMap(),map);assert.ok(writes>0);assert.equal(ctx.maplibregl.config.MAX_PARALLEL_IMAGE_REQUESTS,20);
 f.nodes['loading-reset'].events.click();assert.equal(ctx.maplibregl.config.MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME,8);assert.equal(ctx.maplibregl.config.MAX_PARALLEL_IMAGE_REQUESTS,12);assert.deepEqual(JSON.parse(stored),C.defaults());
 vm.runInContext("renderer='luma';updateRendererUI()",ctx);assert.equal(f.nodes['loading-controls'].disabled,false);vm.runInContext("renderer='maplibre';updateRendererUI()",ctx);assert.equal(f.nodes['loading-controls'].disabled,false);
 f.listeners.pagehide({persisted:false});assert.ok(!f.nodes['loading-moving'].events.input);assert.ok(!f.nodes['loading-reset'].events.click);
 const fakeDoc={getElementById:()=>null},badStorage={getItem(){throw Error('blocked');},setItem(){throw Error('blocked');}};const a=C.attach({document:fakeDoc,storage:badStorage,engine:E});assert.equal(a.values().moving,8);a.destroy();E.config.MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME=oldMoving;E.setMaxParallelImageRequests(oldStationary);
 const result={actualPinnedEngineConfig:true,savedPreferencesSurviveMapStartup:true,UIUpdatesAndReset:true,boundedAndClamped:true,zeroPredictionSupported:true,rendererScope:true,storageFailureSafe:true,noMapRecreation:true,teardown:true,caveat:'Actual configuration export and app/DOM fixtures; not browser pixels or measured loading/FPS improvement.'};fs.writeFileSync('LOADING_CONTROLS_VALIDATION.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify(result));
})().catch(e=>{console.error(e);process.exitCode=1;});
