import {cp, readFile, writeFile, mkdir, rm, readdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {parseHTML} from 'linkedom';
import {build} from 'esbuild';
import {createReadStream} from 'node:fs';
import {createHash} from 'node:crypto';

const base=fileURLToPath(new URL('../',import.meta.url)), source=path.resolve(base,'../public'), out=path.join(base,'www');
await rm(out,{recursive:true,force:true});await mkdir(out,{recursive:true});
// Preserve the original engine and static dependencies. Never ship the Cook
// reference substitution or the alternate WebGPU renderer in this application.
await cp(source,out,{recursive:true,filter:p=>!/(?:cook-reference|luma-renderer|luma-terrain|\/assets\/luma(?:\/|$)|\/assets\/gap-imagery-v[123](?:\/|$)|\/assets\/imagery-boundary-v[23](?:\/|$))/.test(p)});
await mkdir(path.join(out,'licenses'),{recursive:true});
await cp(path.join(base,'CAPACITOR-LICENSE.txt'),path.join(out,'licenses/capacitor.txt'));
await cp(path.join(base,'THIRD_PARTY_NOTICES.md'),path.join(out,'licenses/NOTICE.txt'));
// Only the verified catalogue is bundled; large data ZIPs are imported once.
const overview=JSON.parse(await readFile(path.join(base,'overview-catalog.json'),'utf8'));
const expectedIndex=JSON.parse(await readFile(path.join(base,'../SOUTH_ISLAND_IMAGERY_VALIDATION.json'),'utf8')).persistentIndex.sha256;
if(overview.sourceIndexSHA256!==expectedIndex)throw Error('Overview catalogue does not match the original source index');
const {default:vm}=await import('node:vm');const holder={};vm.runInNewContext(await readFile(path.join(out,'south-overviews.js'),'utf8'),holder);
const south=holder.RidgeSouthOverviews,ranges=new Map();
for(const [coord,variants] of Object.entries(overview.tiles)){
 const converted={};for(const [mode,r] of Object.entries(variants)){const url=`https://localhost/_ridge/overviews/${r.file}/${r.offset}/${r.bytes}`;converted[mode]={url,range:r};ranges.set(url,[r.file,r.offset,r.bytes]);}
 south.tiles[coord]=converted;
}
await writeFile(path.join(out,'south-overviews.js'),`(function(r){r.RidgeSouthOverviews=${JSON.stringify(south)};})(globalThis);\n`);
const bundled=process.env.RIDGELINE_BUNDLE_OVERVIEWS!=='0';
if(bundled){
 const data=process.env.RIDGELINE_OVERVIEW_DATA||path.join(base,'overview-data');await mkdir(path.join(out,'overview-packs'),{recursive:true});
 for(const file of overview.files){const sourceFile=path.join(data,file.sha256+'.bin'),digest=createHash('sha256');let bytes=0;for await(const chunk of createReadStream(sourceFile)){bytes+=chunk.length;digest.update(chunk);}if(bytes!==file.bytes||digest.digest('hex')!==file.sha256)throw Error('Bundled overview failed verification: '+file.sha256);await cp(sourceFile,path.join(out,'overview-packs',file.sha256+'.bin'));}
}
await writeFile(path.join(out,'overview-manifest.json'),JSON.stringify({bundled,files:overview.files,ranges:[...ranges.values()]}));
const replace=(text,old,value)=>{if(!text.includes(old))throw Error('Website integration changed: '+old.slice(0,70));return text.replace(old,value);};
let app=await readFile(path.join(out,'app.js'),'utf8');
app=replace(app,'let directSampling=false','let directSampling=true');
app=app.replaceAll('Saved automatically in this browser','Saved automatically on this device').replaceAll('this browser','this device').replaceAll('Browser storage','Device storage').replaceAll('This prototype','This app');
app=replace(app,'&ridge-reference=cook-reference-v1','');
const start=app.indexOf('async function switchRenderer('),end=app.indexOf('let lastTerrainError=0;',start);
if(start<0||end<0)throw Error('Renderer integration changed');
app=app.slice(0,start)+app.slice(end);
app=replace(app,"const url=URL.createObjectURL(new Blob([xml],{type:'application/gpx+xml'}))", "if(globalThis.RidgeMobile){void RidgeMobile.exportGpx(name,xml);return;}const url=URL.createObjectURL(new Blob([xml],{type:'application/gpx+xml'}))");
app=replace(app,"active.on('click',e=>{if(map!==active)return;", "active.on('click',e=>{if(map!==active)return;if(globalThis.RidgeMobile?.mapClick(e))return;");
app=replace(app,"selectable:()=>map===active&&ready&&!switching", "selectable:()=>map===active&&drawing&&ready&&!switching&&!globalThis.RidgeMobile?.selecting()");
app=replace(app,"enabled:()=>map===active&&drawing&&ready", "enabled:()=>map===active&&drawing&&ready&&!globalThis.RidgeMobile?.selecting()");
app=replace(app,'updateRendererUI();initMap();',`updateRendererUI();initMap(globalThis.RidgeMobile?.lastView());
globalThis.RidgeMobile?.attach({getMap:()=>map,isReady:()=>ready,getKey:()=>activeLinzKey,getPoints:()=>points.map(p=>p.slice()),stopDrawing:()=>setDrawing(false),
 renameRoute:(id,name)=>{name=name.trim();if(!name||name.length>80)throw Error('Use a route name of 1–80 characters.');if(id===activeSaved){$('route-name').value=name;return autoSaveRoute();}const old=saved;saved=saved.map(r=>r.id===id?{...r,name,updated:new Date().toISOString()}:r);if(!storageWrite()){saved=old;return false;}return true;},
 routes:()=>saved.slice(),redrawProfile:()=>drawProfile(),activeRoute:()=>activeSaved,
 openRoute:id=>{const r=saved.find(r=>r.id===id);if(!r||!autoSaveRoute())return false;setDrawing(false);popupDismissed=false;resetRouteHistory();points=r.points.map(p=>p.slice());activeSaved=r.id;$('route-name').value=r.name;setSidebar(false);updateRoute();fitRoute();return true;},
 unloadRoute:()=>{if(!autoSaveRoute())return false;setDrawing(false);routeEditor?.cancel();points=[];activeSaved=null;selectedRoutePoint=-1;popupDismissed=true;resetRouteHistory();profileClient.cancel();profile=[];updateRoute();return true;},
 deleteRoute:id=>{if(id===activeSaved&&!autoSaveRoute())return false;const old=saved;saved=saved.filter(r=>r.id!==id);if(!storageWrite()){saved=old;return false;}if(id===activeSaved){setDrawing(false);points=[];activeSaved=null;popupDismissed=true;resetRouteHistory();updateRoute();}renderSaved();return true;},
 createCombined:value=>{if(!autoSaveRoute())return false;if(saved.length>=50){toast('Delete or export an old route before combining (50 route limit).');return false;}setDrawing(false);points=value;activeSaved=null;selectedRoutePoint=-1;popupDismissed=false;resetRouteHistory();$('route-name').value='Combined route';updateRoute();setSidebar(false);fitRoute();return true;},importText:(name,text)=>importRoute(new File([text],name,{type:'application/gpx+xml'})),refresh:()=>{profileClient.cancel();profileStamp='';queueProfile();},toast,goPeak,renderStatus:()=>({ready,terrain:!!map?.getTerrain(),direct:directRaster?.stats(),points:points.length,profile:profile.map(p=>({h:p.h,provider:p.provider}))})});`);
app=replace(app,"function renderSaved(){","function renderSaved(){if(globalThis.RidgeMobile?.renderRoutes(saved,activeSaved))return;");
// Consolidate successful saves into one card refresh, after activeSaved is set.
app=replace(app,"updateSavedCount();return true;","return true;");
app=replace(app,"function drawProfile(){","function drawProfile(){if(globalThis.RidgeMobile?.canDrawProfile&&!globalThis.RidgeMobile.canDrawProfile())return;");
// App panels occupy their own row so map hit testing and camera fitting use the visible viewport.
app=replace(app,"routePopup=RidgeRouteInteractions.attachPopup({element:$('profile-card'),handle:$('profile-drag-handle'),bounds:$('map-area'),resizeHandle:$('profile-resize-handle')})","routePopup={constrain(){}}");
app=replace(app,"if($('profile-card').hidden)chartInteraction?.clear();","globalThis.RidgeMobile?.syncPanels();if($('profile-card').hidden)chartInteraction?.clear();");
app=replace(app,"$('undo').hidden=!drawing;","$('undo').hidden=false;");
app=replace(app,"$('route-popup-delete').hidden=!selected;","$('route-popup-delete').hidden=false;");
app=replace(app,"padding:{top:110,left:70,right:70,bottom:260}","padding:{top:80,left:35,right:35,bottom:40}");
app=replace(app,"const show=basemap!=='topo'&&$('labels').checked&&!topoOverlays?.usesPeakNames();","const show=false;");
await writeFile(path.join(out,'app.js'),app);
let viewer=await readFile(path.join(out,'viewer.mjs'),'utf8');
viewer=replace(viewer,"import './cook-reference-overviews.js?v=1';",'');
await writeFile(path.join(out,'viewer.mjs'),viewer);
// Route ALL provider reads (including worker elevation/glyph/vector requests)
// through the native archive layer. No base64 tile bridge or JS tile copies.
const hosts=[['https://basemaps.linz.govt.nz','https://localhost/_ridge/linz'],['https://services.arcgisonline.com','https://localhost/_ridge/esri'],['https://s3.amazonaws.com/elevation-tiles-prod','https://localhost/_ridge/mapzen']];
for(const name of await readdir(out))if(/\.(?:m?js)$/.test(name)){
 let text=await readFile(path.join(out,name),'utf8');for(const [from,to] of hosts)text=text.replaceAll(from,to);
 text=text.replaceAll("u.hostname==='basemaps.linz.govt.nz'&&u.pathname.match(","u.hostname==='localhost'&&u.pathname.replace(/^\\/_ridge\\/linz/,'').match(");
 if(name==='gap-imagery.js')text=text.replace("if(fail)throw fail.reason;","if(fail&&fineRequests.length&&a?.ok&&b?.ok)return base(url,options);if(fail)throw fail.reason;");
 // Server-side packed overviews are not part of a static Capacitor app.
 // Keep bundled overviews; read the provider for other tiles.
 if(name==='topographic-overlays.js')text=replace(text,'maxzoom:15','maxzoom:14');
 if(name==='topographic-overlays.js'){
  text=replace(text,"const show=!!s[group(l.id)]&&!rasterTopo&&(!l.id.startsWith('nz-peaks')||peaks)","const show=(l.id.startsWith('nz-peaks')?peaks:!!s[group(l.id)])&&!rasterTopo");
 }
 if(name==='linz-topo.js'){text=replace(text,'maxzoom:16','maxzoom:18');text=replace(text,'tileSize:256','tileSize:(root.devicePixelRatio||1)>1?128:256');}
 if(name==='performance.js')text=replace(text,"setRatio(lowPower()?1:native);return;", "const source=map.getSource('topo'),size=(lowPower()?1:native)>1?128:256;if(source&&source.tileSize!==size){source.tileSize=size;map._update?.(true);}setRatio(lowPower()?1:native);return;");
 if(name==='regional-imagery.js'){
  text=replace(text,'if(entry){',"if(entry&&(entry.url||entry[compensate?'natural':'raw']?.url)){");
  text=replace(text,"if(!/^\\/assets\\/south-overviews-v1\\/[a-f0-9]{24}\\.webp$/.test(row.url))", "if(!/^\\/assets\\/south-overviews-v1\\/[a-f0-9]{24}\\.webp$/.test(row.url)&&!/^https:\\/\\/localhost\\/_ridge\\/overviews\\/[a-f0-9]{64}\\/\\d+\\/\\d+$/.test(row.url))");
  const start=text.indexOf('   const headers=new Headers(options.headers);'),end=text.indexOf('  const asset=baked',start);
  if(start<0||end<0)throw Error('Overview fallback integration changed');
  text=text.slice(0,start)+'   return fetcher(url,options);\n  }\n'+text.slice(end);
 }
 await writeFile(path.join(out,name),text);
}
const {document}=parseHTML(await readFile(path.join(out,'index.html'),'utf8'));
document.documentElement.setAttribute('data-overviews-bundled',String(bundled));
const $=id=>{const el=document.getElementById(id);if(!el)throw Error('Missing website control '+id);return el;};
document.querySelector('meta[name=viewport]').setAttribute('content','width=device-width,initial-scale=1,viewport-fit=cover');
const panel=$('layers-panel'),heading=panel.firstElementChild,advanced=document.createElement('section'),basic=document.createElement('section');
advanced.id='layers-advanced';advanced.hidden=true;advanced.setAttribute('role','tabpanel');advanced.setAttribute('aria-labelledby','advanced-tab');
basic.id='layers-basic';basic.setAttribute('role','tabpanel');basic.setAttribute('aria-labelledby','basic-tab');
for(const node of [...panel.childNodes])if(node!==heading)advanced.append(node);
panel.append(basic,advanced);
// The renderer is fixed, with no alternative renderer controls.
advanced.querySelector('.renderer-choices')?.remove();advanced.querySelector('.renderer-label')?.remove();
const rendererSection=advanced.querySelector('.renderer-section');if(rendererSection)rendererSection.hidden=true;
basic.append(advanced.querySelector('.layer-choices'));
const details=$('detail-controls'),quick=document.createElement('fieldset');quick.className='detail-controls';quick.innerHTML='<legend>Map detail</legend>';
for(const id of ['imagery-detail','terrain-detail']){const input=$(id),label=details.querySelector(`label[for="${id}"]`);quick.append(label,input);}
basic.append(quick);
$('labels').removeAttribute('checked');
for(const id of ['labels','hillshade'])advanced.append($(id).closest('label'));
const topoSection=$('topo-names').closest('fieldset,details');if(topoSection)basic.append(topoSection);
$('topographic-note').hidden=true;$('topo-status').hidden=true;
const offline=document.createElement('button');offline.id='open-offline';offline.className='primary';offline.textContent='Download offline region';$('sidebar').append(offline);
const note=document.createElement('p');note.textContent='3D terrain · Original + Direct';note.className='topo-basemap-note';basic.append(note);
const tabs=document.createElement('div');tabs.className='layers-tabs';tabs.setAttribute('role','tablist');tabs.setAttribute('aria-label','Layer settings');
tabs.innerHTML='<button id="basic-tab" role="tab" aria-controls="layers-basic" aria-selected="true">Layers</button><button id="advanced-tab" role="tab" aria-controls="layers-advanced" aria-selected="false" tabindex="-1">Advanced</button>';
panel.append(tabs,basic,advanced);
const about=document.createElement('details');about.innerHTML='<summary>Licences &amp; data sources</summary><p>Summits adapted from NZAC / ClimbNZ; Aoraki Tai Poutini (Rob Frost, 2018). CC BY-NC-SA 3.0 NZ. Imagery and terrain attribution appears on the map.</p>';
for(const [name,file] of [['Capacitor','licenses/capacitor.txt'],['MapLibre','assets/maplibre-LICENSE.txt'],['Third-party notices','licenses/NOTICE.txt']]){const row=document.createElement('details'),title=document.createElement('summary'),text=document.createElement('pre');title.textContent=name;text.textContent=await readFile(path.join(out,file),'utf8');text.style.cssText='white-space:pre-wrap;overflow-wrap:anywhere;font-size:11px;max-height:300px;overflow:auto';row.append(title,text);about.append(row);}advanced.append(about);
const css=document.createElement('link');css.rel='stylesheet';css.href='/mobile.css';document.head.append(css);
const bootstrap=document.createElement('script');bootstrap.type='module';bootstrap.src='/mobile.js';document.head.append(bootstrap);
bootstrap.setAttribute('onerror',"document.getElementById('load-state').textContent='The map could not start. Update Android System WebView and Chrome, then reopen Ridgeline.'");
const legacy=document.createElement('script');legacy.setAttribute('nomodule','');legacy.textContent="addEventListener('DOMContentLoaded',()=>{document.getElementById('load-state').textContent='Update Android System WebView and Chrome to use Ridgeline 3D.';});";document.head.append(legacy);
document.querySelector('script[src^="/viewer.mjs"]')?.remove();
await writeFile(path.join(out,'index.html'),'<!doctype html>\n'+document.documentElement.outerHTML);
await cp(path.join(base,'mobile/mobile.css'),path.join(out,'mobile.css'));
await build({entryPoints:[path.join(base,'mobile/mobile.js')],outfile:path.join(out,'mobile.js'),bundle:true,external:['/viewer.mjs*'],format:'esm',target:'chrome100',minify:false});
console.log('Packaged original direct 3D renderer and mobile controls.');
