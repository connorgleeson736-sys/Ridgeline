const fs=require('node:fs'),Module=require('node:module'),path=require('node:path'),os=require('node:os');
const root=path.resolve(__dirname,'../..'),baseline=process.argv.includes('--baseline');process.chdir(root);
const nativePath=baseline?'public/native-residency.js':'capacitor/www/native-residency.js';
const app=new Module(path.join(root,'public/__app-residency.cjs'));app.filename=path.join(root,'public/__app-residency.cjs');app.paths=Module._nodeModulePaths(path.join(root,'public'));app._compile(fs.readFileSync(nativePath,'utf8'),app.filename);
const load=Module._load;Module._load=function(request,parent,isMain){if(request==='../public/native-residency.js')return app.exports;return load.apply(this,arguments);};
const report=path.join(os.tmpdir(),'ridgeline-residency-'+process.pid+'.json');
let source=fs.readFileSync('checks/native-residency.cjs','utf8').replaceAll("fs.readFileSync('public/native-residency.js'",`fs.readFileSync('${nativePath}'`).replace("'NATIVE_RESIDENCY_VALIDATION.json'",JSON.stringify(report));
const needle='let tile=manager.getRenderableTiles()[0];assert.ok(tile);';
if(!source.includes(needle))throw Error('Native lifecycle fixture changed');
source=source.replace(needle,needle+`
 const observed=new Set();let currentRelease=tile.releaseRTT;observed.add(currentRelease);
 Object.defineProperty(tile,'releaseRTT',{configurable:true,get(){return currentRelease;},set(fn){observed.add(fn);currentRelease=fn;}});
 for(let i=0;i<100;i++)manager.update(transform,map.terrain);
 Object.defineProperty(tile,'releaseRTT',{configurable:true,writable:true,value:currentRelease});
 console.log(JSON.stringify({releaseHookFunctions:observed.size,baseline:${baseline}}));
`);
const check=new Module(path.join(root,'checks/__app-lifecycle.cjs'));check.filename=path.join(root,'checks/__app-lifecycle.cjs');check.paths=Module._nodeModulePaths(path.join(root,'checks'));check._compile(source,check.filename);
process.on('exit',()=>{try{fs.unlinkSync(report);}catch{}});
