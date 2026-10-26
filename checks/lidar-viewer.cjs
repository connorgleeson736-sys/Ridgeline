/* Controller regression only: no browser, WebGL, or fabricated FPS result. */
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const core=require('../public/lidar-pilot-core.js');
class Event{constructor(){this.handlers=[];}addEventListener(fn){this.handlers.push(fn);return()=>this.handlers=this.handlers.filter(f=>f!==fn);}raise(...args){this.handlers.forEach(fn=>fn(...args));}}
const elements=new Map(),listeners={},globalListeners={},frames=new Map();let nextFrame=0,now=0,controller;
function element(id){if(!elements.has(id))elements.set(id,{id,value:id==='quality'?'adaptive':'balanced',hidden:false,disabled:false,textContent:'',
 classList:{toggle(){}},setAttribute(){},querySelector(){return element(id+'-strong');},addEventListener(type,fn){listeners[id+':'+type]=fn;},removeEventListener(){}});return elements.get(id);}
const tile={content:{trianglesLength:123}},tileset={boundingSphere:{center:{x:1,y:2,z:3},radius:70},tilesLoaded:true,totalMemoryUsageInBytes:1024,
 tileVisible:new Event(),tileLoad:new Event(),tileUnload:new Event(),loadProgress:new Event(),tileFailed:new Event(),allTilesLoaded:new Event()};
let options,tilesetOptions;
class Viewer{constructor(id,o){options=o;controller=this;this.clock={};this.scene={canvas:element('canvas'),globe:{show:true},fog:{},
 postProcessStages:{fxaa:{}},screenSpaceCameraController:{},renderError:new Event(),primitives:{add(){}}};
 this.camera={changed:new Event(),frustum:{},positionWC:{x:1,y:2,z:3},lookAt(){}};}
 resize(){}render(){tileset.tileVisible.raise(tile);if(!this.first){this.first=true;tileset.loadProgress.raise(0,0);}}isDestroyed(){return false;}destroy(){this.destroyed=true;}}
const context={LidarPilotCore:core,console,Map,Set,Blob,URL,AbortSignal:{timeout(){}},
 Cesium:{Viewer,EllipsoidTerrainProvider:class{},Cesium3DTileset:{async fromUrl(url,o){assert.equal(url,core.assetURL('a'.repeat(64)));tilesetOptions=o;return tileset;}},
 Color:{fromCssColorString:x=>x},HeadingPitchRange:class{},Math:{toRadians:x=>x*Math.PI/180},JulianDate:{fromIso8601:x=>x},Cartesian3:{pack:(v,out)=>[v.x,v.y,v.z],fromArray:x=>({x:x[0],y:x[1],z:x[2]})}},
 document:{getElementById:element,body:element('body'),hidden:false,addEventListener:(type,fn)=>listeners['document:'+type]=fn,createElement:()=>({click(){}})},
 fetch:async()=>({ok:true,json:async()=>({status:'pilot',patch_metres:100,finest_triangles:815390,tileset_sha256:'a'.repeat(64),
 inspection_camera:{target_ecef:[1,2,3],heading_degrees:20,pitch_degrees:-20,range_metres:25},
 validation:{all_assets_verified:true,original_face_coverage:true}})}),
 requestAnimationFrame:fn=>{frames.set(++nextFrame,fn);return nextFrame;},cancelAnimationFrame:id=>frames.delete(id),setTimeout(){},
 performance:{now:()=>now,getEntriesByType:()=>[]},devicePixelRatio:2,innerWidth:1200,innerHeight:800,navigator:{userAgent:'controller-fixture'},
 location:{reload(){}},addEventListener:(type,fn)=>globalListeners[type]=fn};
vm.createContext(context);vm.runInContext(fs.readFileSync('public/lidar-summit.js','utf8'),context);
async function flush(){await new Promise(setImmediate);}
function render(time){now=time;const batch=[...frames.values()];frames.clear();batch.forEach(fn=>fn(time));}
(async()=>{
 await flush();assert.ok(controller);assert.equal(options.baseLayer,false);assert.equal(options.useDefaultRenderLoop,false);
 assert.equal(controller.scene.globe.show,false);assert.equal(tilesetOptions.cacheBytes,128*1048576);assert.equal(tilesetOptions.skipLevelOfDetail,false);
 render(1001);assert.equal(element('loading').hidden,true);assert.equal(element('triangles').textContent,'123');
 assert.equal(frames.size,1,'Load callbacks during render must not start duplicate frame loops');render(2000);
 assert.equal(frames.size,0,'Settled idle view must stop rendering');
 element('quality').value='coarse';element('quality').onchange();assert.equal(tileset.maximumScreenSpaceError,1e9);render(3000);
 element('benchmark').onclick();await flush();assert.equal(element('stop').hidden,false);
 context.document.hidden=true;listeners['document:visibilitychange']();await flush();
 assert.equal(frames.size,0);assert.match(element('result').textContent,/hidden/);assert.equal(element('download').hidden,true);
 context.document.hidden=false;listeners['document:visibilitychange']();render(5000);
 controller.scene.renderError.raise(null,Error('test loss'));assert.equal(element('retry').hidden,false);assert.equal(frames.size,0);
 globalListeners.pagehide();assert.equal(controller.destroyed,true);
 console.log('LiDAR viewer idle/cancellation/fallback controller checks passed; no browser/GPU timing measured.');
})().catch(error=>{console.error(error);process.exitCode=1;});
