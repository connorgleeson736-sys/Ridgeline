import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';
const root=path.resolve(process.argv[2]||'audit');
globalThis.window={devicePixelRatio:1};
const source=fs.readFileSync(path.join(root,'public/assets/maplibre-gl.mjs'),'utf8');
const symbol=source.match(/var (\w+)=class\{constructor\(e,t\)\{this\._map=e,this\._clickTolerance=t.clickTolerance/)[1];
const temp=fs.mkdtempSync(path.join(os.tmpdir(),'ridge-touch-'));
const file=path.join(temp,'engine.mjs');
fs.writeFileSync(file,source+`\nexport {${symbol} as NativeMapEventHandler};`);
let E;try{E=await import(pathToFileURL(file));}finally{fs.rmSync(temp,{recursive:true});}
let tr;E.Style.prototype._setProjectionInternal.call({map:{migrateProjection:t=>tr=t},tileManagers:{},_globalState:{}},'mercator');
tr.resize(390,844);tr.setZoom(13);tr.setPitch(65);tr.setCenter(new E.LngLat(170.141,-43.595));tr.setElevation(2300);
let unprojects=0,samples=0;
// Controlled resident DEM at one zoom, sampled through the actual shipped
// screen-to-terrain raycast. This is a host CPU fixture, not phone terrain data.
const mc=E.MercatorCoordinate.fromLngLat(tr.center),samplers=new Map();
for(let x=Math.floor(mc.x*8192)-32;x<=Math.floor(mc.x*8192)+32;x++)for(let y=Math.floor(mc.y*8192)-32;y<=Math.floor(mc.y*8192)+32;y++)samplers.set(`0/13/${x}/${y}`,(x,y,dim)=>{samples++;return 1800+600*Math.sin(x/dim*3)*Math.cos(y/dim*2);});
const index={zooms:[13],samplerPerTile:samplers,minElevation:1100,maxElevation:2500};
const terrain={exaggeration:1,getCoverageIndex:()=>index};
const container={getBoundingClientRect:()=>({left:0,top:0,width:390,height:844}),offsetWidth:390,offsetHeight:844,clientLeft:0,clientTop:0};
const map=Object.assign(new E.Evented(),{getCanvasContainer:()=>container,unproject(p){unprojects++;return tr.screenPointToLocation(p,terrain);}});
const handler=new E.NativeMapEventHandler(map,{clickTolerance:3});
const events=Array.from({length:120},(_,i)=>({type:'touchmove',touches:[{clientX:130+i%70,clientY:250+i%200,identifier:1}],changedTouches:[]}));
function run(guard){unprojects=samples=0;const start=performance.now();for(const event of events){if(!guard||map.listens('touchmove'))handler.touchmove(event);}return {unprojects,terrainSamples:samples,ms:performance.now()-start};}
const baseline=run(false),guarded=run(true);
assert.equal(baseline.unprojects,240);assert.ok(baseline.terrainSamples>0);assert.equal(guarded.unprojects,0);
let delivered=0;const listener=e=>{delivered++;assert.equal(e.points.length,1);assert.ok(Number.isFinite(e.lngLat.lng));};
map.on('touchmove',listener);run(true);assert.equal(delivered,120);map.off('touchmove',listener);
const parent=new E.Evented();map.setEventedParent(parent);parent.once('touchmove',listener);handler.touchmove(events[0]);assert.equal(delivered,121);map.setEventedParent(null);
map.once('touchmove',listener);run(true);assert.equal(delivered,122);
for(let i=0;i<8;i++){run(false);run(true);}
const times=[];for(let i=0;i<31;i++)times.push(run(false).ms/events.length);times.sort((a,b)=>a-b);
console.log(JSON.stringify({events:events.length,baseline,guarded,medianHostMsPerEvent:times[15],listenersAndParentPreserved:true},null,2));
