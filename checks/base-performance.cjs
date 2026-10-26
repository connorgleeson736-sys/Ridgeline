const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
function fixture(source,settings={}){
 let now=1000,nextTimer=0,timers=new Map(),calls=[],settled=0;const handlers={};
 const classes=new Set(),document={hidden:false,body:{classList:{add:x=>classes.add(x),remove:x=>classes.delete(x)}}};
 const map={ratio:2,zoom:17,lod:[],sources:{linz:{},satellite:{},topo:{},terrain:{}},
  getPixelRatio(){return this.ratio},setPixelRatio(value){this.ratio=value;calls.push(value)},
  getZoom(){return this.zoom},getContainer:()=>({clientWidth:1200,clientHeight:800}),getSource(id){return this.sources[id]},
  setSourceTileLodParams(...args){this.lod.push(args)},on:(name,fn)=>(handlers[name]??=[]).push(fn),
  off(name,fn){handlers[name]=(handlers[name]||[]).filter(f=>f!==fn)}};
 const context=vm.createContext({devicePixelRatio:2,document,performance:{now:()=>now},
  setTimeout:fn=>{const id=++nextTimer;timers.set(id,fn);return id},clearTimeout:id=>timers.delete(id)});
 vm.runInContext(source,context);const api=context.RidgePerformance,control=api.attach(map,{lowPower:()=>!!settings.lowPower,phoneMode:()=>!!settings.phoneMode,onSettled:()=>settled++});
 const emit=(name,dt=0)=>{now+=dt;for(const fn of (handlers[name]||[]).slice())fn()};
 const flush=()=>{const batch=timers;timers=new Map();batch.forEach(fn=>fn())};
 return{map,api,control,emit,flush,calls,classes,document,handlers,get settled(){return settled}};
}
const source=fs.readFileSync('public/performance.js','utf8'),f=fixture(source);
for(let i=0;i<100;i++){f.emit('movestart');for(let j=0;j<10;j++)f.emit('render',16.67);f.emit('moveend');f.flush()}
assert.equal(f.calls.length,0,'Ordinary fast pans must not reallocate the drawing buffer');
assert.equal(f.settled,100);assert.equal(f.classes.has('map-moving'),false);
const installed=f.map.lod.length;f.control.refresh();f.control.refresh();assert.equal(f.map.lod.length,installed,'Refresh must not reset unchanged source LOD');
assert.ok(f.map.lod.every(([levels,ratio,id])=>id==='topo'?(levels===1&&ratio===1):(levels===(id.startsWith('terrain')?8:6)&&ratio===3.5)));
assert.equal(f.map.sources.terrain.calculateTileZoom,undefined,'Use the native engine LOD');
const slow=fixture(source);slow.emit('movestart');for(let i=0;i<120;i++)slow.emit('render',33.34);
assert.equal(slow.calls.length,1,'Only one adaptive change per long slow gesture');assert.equal(slow.map.ratio,1.5);
slow.emit('moveend');slow.flush();assert.equal(slow.map.ratio,2,'Restore close-up sharpness');
const sparse=fixture(source);sparse.emit('movestart');sparse.document.hidden=true;
for(let i=0;i<100;i++)sparse.emit('render',33);assert.equal(sparse.calls.length,0);
f.emit('movestart');f.emit('moveend');f.emit('remove');f.flush();assert.equal(f.settled,100,'No late work after disposal');
assert.ok(Object.values(f.handlers).every(list=>list.length===0));
assert.equal(f.api.cacheSize({memory:4}),128);assert.equal(f.api.cacheSize({memory:8}),192);assert.equal(f.api.cacheSize({saveData:true}),80);
// Phone mode retains a measured reduction across short and long gestures,
// settlement, quality refresh, resize and temporary memory/low-power changes.
const settings={phoneMode:true},phone=fixture(source,settings);
assert.equal(phone.map.ratio,1.25);
phone.emit('movestart');for(let i=0;i<60;i++)phone.emit('render',33.34);
assert.equal(phone.map.ratio,1);phone.emit('moveend');phone.flush();assert.equal(phone.map.ratio,1);
const changes=phone.calls.length;
for(let i=0;i<20;i++){phone.emit('movestart');for(let j=0;j<10;j++)phone.emit('render',16.67);phone.emit('moveend');phone.flush();}
phone.control.refresh();phone.emit('resize');assert.equal(phone.map.ratio,1);assert.equal(phone.calls.length,changes,'No repeated gesture buffer reallocations');
phone.control.setMemoryPressure(true);phone.control.setMemoryPressure(false);assert.equal(phone.map.ratio,1,'Pressure recovery must respect learned cap');
settings.lowPower=true;phone.control.refresh();settings.lowPower=false;phone.control.refresh();assert.equal(phone.map.ratio,1);
settings.phoneMode=false;phone.control.refresh();assert.equal(phone.map.ratio,2,'Disabling restores ordinary policy');
settings.phoneMode=true;phone.control.refresh();assert.equal(phone.map.ratio,1.25,'Explicit re-enable resets learned cap');
phone.control.destroy();assert.ok(Object.values(phone.handlers).every(list=>list.length===0));
assert.equal(phone.api.pixelRatio({dpr:1,phoneMode:true,zoom:17}),1,'Do not supersample a 1x display');
let baseline;
if(process.argv[2]){const old=fixture(fs.readFileSync(process.argv[2],'utf8'));for(let i=0;i<100;i++){old.emit('movestart',100);old.emit('moveend');old.flush()}baseline=old.calls.length;assert.equal(baseline,200)}
console.log(JSON.stringify({quickPanGestures:100,fixtureDPR:2,fixtureZoom:17,previousBufferResizes:baseline,currentBufferResizes:f.calls.length,
 sustainedSlowGestureDownshifts:slow.control.stats().adaptiveDownshifts,closeUpPixelRatioRestored:slow.map.ratio,phoneModeRetainsReduction:true,phoneModeToggleRestoresPolicy:true,
 caveat:'Controlled event/frame-time fixture; not a browser FPS benchmark'}));
