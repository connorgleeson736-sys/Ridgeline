import {Capacitor,registerPlugin} from '@capacitor/core';
import {attachSheet} from './bottom-sheet.js';
import {attachRegionSelection} from './region-selection.js';
import {attachRoutes} from './routes.js';
import {createViewStorage} from './view-storage.js';
import {pageFrame,paperSizes} from './page-layout.js';
const Native=registerPlugin('RidgelineOffline');
const viewStorage=createViewStorage({write:stamp=>localStorage.setItem('ridgeline-mobile-view-v1',stamp)});
globalThis.addEventListener('pagehide',()=>viewStorage.flush());
document.addEventListener('visibilitychange',()=>{if(document.hidden)viewStorage.flush();});
const $=id=>document.getElementById(id);
let api,selecting=false,corners=[],gps=false,gpsMarker,refreshTimer,polling=false,selectionBounds,selectionMarkers=[],regionMarkers=[],regionDragging=false,routeSheet,offlineSheet,regionOverlay,routeWorkspace,lastMapSize,selectionFrame;
const el=(tag,text,cls)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(cls)node.className=cls;return node;};
const button=(text,action)=>{const b=el('button',text);b.type='button';b.onclick=async()=>{if(b.disabled)return;b.disabled=true;try{await action();}catch(e){showError(e);}finally{b.disabled=false;}};return b;};
const showError=e=>api?.toast(e?.message||'The operation could not finish. Please try again.');
const native=()=>{if(!Capacitor.isNativePlatform())throw Error('Offline downloads and GPS are available in the Android app.');};
function regionBounds(){const values=selectionBounds&&!$('offline-advanced')?.open?selectionBounds.slice():['west','south','east','north'].map(id=>Number($('offline-'+id).value));if(values.some(v=>!Number.isFinite(v))||values[0]<165||values[2]>180||values[1]<-49||values[3]>-33||values[0]>=values[2]||values[1]>=values[3])throw Error('Enter valid region coordinates.');return values;}
function updateBounds(bounds){selectionBounds=bounds.slice();$('offline-estimate').textContent='';['west','south','east','north'].forEach((id,i)=>{$('offline-'+id).value=bounds[i].toFixed(5);});}
function setBounds(bounds){updateBounds(bounds);drawSelection(bounds);}
function drawSelection(bounds){if(!api?.isReady())return;if(!regionOverlay)regionOverlay=attachRegionSelection(api.getMap(),{onChange:updateBounds,onCommit:bounds=>{updateBounds(bounds);},onDrag:value=>{regionDragging=value;}});regionOverlay.set(bounds,!!bounds&&!selecting&&!$('offline-panel').hidden);}
function syncPanels(){
 const offline=$('offline-panel')&&!$('offline-panel').hidden;
 const route=!$('profile-card').hidden&&!offline&&!selecting;
 const height=offline?offlineSheet?.height:route?routeSheet?.height:0;
 const workspace=document.querySelector('.workspace'),dragging=!!document.querySelector('.sheet-dragging');
 if(dragging){
  // Only clipping and transforms change during movement. Layout and map resize
  // happen at the boundaries, never on every pointer frame.
  if(!document.body.classList.contains('map-sheet-dragging')){
   const viewport=workspace.clientHeight;
   document.body.classList.add('map-sheet-dragging');
   $('map').style.height=viewport+'px';
   api?.getMap()?.resize();
  }
  workspace.style.setProperty('--drag-sheet-inset',Math.max(0,(height||0)-24)+'px');
  return;
 }
 const wasDragging=document.body.classList.contains('map-sheet-dragging');
 document.body.classList.remove('map-sheet-dragging');
 const value=(height||0)+'px';
 if(workspace.style.getPropertyValue('--active-sheet-height')!==value)workspace.style.setProperty('--active-sheet-height',value);
 document.body.classList.toggle('bottom-panel-open',!!(offline||route));
 document.body.classList.toggle('offline-panel-open',!!offline);
 document.body.classList.toggle('region-selecting',selecting);
 $('map').style.height='';
 if(lastMapSize!==value||wasDragging){lastMapSize=value;api?.getMap()?.resize();regionOverlay?.refresh();if(route)requestAnimationFrame(()=>api?.redrawProfile());}
}

function updateRegionHandles(bounds){drawSelection(bounds);}
function stopSelection(){selecting=false;corners=[];selectionMarkers.forEach(m=>m.remove());selectionMarkers=[];$('offline-select-hint')?.remove();if(api?.getMap())api.getMap().getCanvas().style.cursor='';}
function cancelSelection(){stopSelection();drawSelection(selectionBounds);void openOffline().catch(showError);}

function closeOffline(clear=true){$('offline-settings')?.close();$('offline-panel').hidden=true;($('sidebar').classList.contains('open')?$('open-offline'):$('planner-toggle')).focus();if(clear){stopSelection();drawSelection(null);}else updateRegionHandles(null);clearTimeout(refreshTimer);syncPanels();}
function framePage(){if(!api.isReady())return;const area=$('map-area');regionOverlay?.hide();if(!regionOverlay)drawSelection(null);regionOverlay.begin(pageFrame($('offline-page-size').value,$('offline-orientation').value,area.clientWidth,Math.max(60,area.clientHeight-24)),true);}
async function openOffline(){stopSelection();$('offline-advanced').open=false;api.stopDrawing();if(api.isReady()){$('view-2d').click();api.getMap().jumpTo({pitch:0,bearing:0});}$('offline-settings')?.close();$('layers-close').click();if($('sidebar').classList.contains('open'))$('sidebar-close').click();$('offline-panel').hidden=false;offlineSheet?.snap(0);syncPanels();framePage();$('offline-page-size').focus();}
async function showDownloadSettings(){
 const bounds=regionBounds();regionOverlay?.hide();$('offline-panel').hidden=true;syncPanels();updateBounds(bounds);$('offline-settings').showModal();await refreshRegions();
}

async function refreshRegions(){clearTimeout(refreshTimer);if(polling||($('offline-panel').hidden&&!$('offline-settings').open&&!$('sidebar').classList.contains('open')))return;polling=true;let watch=true;try{native();const {regions}=await Native.listRegions();watch=regions.some(r=>r.state==='downloading'||r.state==='queued');renderSavedRegions(regions);const list=$('offline-list');const stamp=JSON.stringify(regions);if(list.dataset.stamp===stamp)return;list.dataset.stamp=stamp;list.replaceChildren();if(!regions.length)list.append(el('p','No regions downloaded yet.'));for(const r of regions){const item=el('article',undefined,'offline-region');item.append(el('strong',r.name),el('p',`${r.state} · ${r.done}/${r.total} files · ${(r.bytes/1048576).toFixed(1)} MB`));if(r.error)item.append(el('p',r.error,'offline-error'));const actions=el('div',undefined,'offline-actions');actions.append(button('View',()=>{closeOffline(false);setBounds(r.bounds);api.getMap().fitBounds([[r.bounds[0],r.bounds[1]],[r.bounds[2],r.bounds[3]]],{padding:40,maxZoom:14});}));if(r.state==='downloading'||r.state==='queued')actions.append(button('Pause',async()=>{await Native.pauseRegion({id:r.id});await refreshRegions();}));else if(r.state!=='complete')actions.append(button('Resume',async()=>{await Native.resumeRegion({id:r.id});await refreshRegions();}));actions.append(button('Remove',async()=>{await Native.removeRegion({id:r.id});await refreshRegions();}));item.append(actions);list.append(item);}}catch(e){delete $('offline-list').dataset.stamp;$('offline-list').textContent=e.message;}finally{polling=false;if(watch&&(!$('offline-panel').hidden||$('offline-settings').open||$('sidebar').classList.contains('open')))refreshTimer=setTimeout(refreshRegions,1800);}}
function downloadSettings(){return {bounds:regionBounds(),zoom:Number($('offline-zoom').value),imagery:$('offline-imagery').value,topo:$('offline-topo').checked};}
async function estimate(){native();const settings=downloadSettings(),stamp=JSON.stringify(settings);$('offline-estimate').textContent='Sampling map tiles…';const result=await Native.estimateRegion({...settings,key:api.getKey()});if(stamp!==JSON.stringify(downloadSettings()))throw Error('Region settings changed. Estimate again.');$('offline-estimate').textContent=`About ${result.estimatedMiB} MiB · ${result.total.toLocaleString()} files · ${result.samples||0} tile samples${result.samples?'':' (planning estimate)'}. Allow ${result.highMiB} MiB of space.`;return result;}
function createOfflinePanel(){const section=el('section',undefined,'mobile-panel');section.id='offline-panel';section.hidden=true;section.setAttribute('aria-labelledby','offline-heading');section.innerHTML=`<div class="panel-heading"><h2 id="offline-heading">Offline regions</h2><button id="offline-close" aria-label="Close offline regions">×</button></div><div id="offline-choose" class="offline-actions"></div><form id="offline-form"><label>Region name<input id="offline-name" maxlength="60" value="My region" required></label><details id="offline-advanced"><summary>Advanced</summary><div class="coordinate-grid">${['west','south','east','north'].map(id=>`<label>${id}<input id="offline-${id}" type="number" step="any" required></label>`).join('')}</div></details><label>Download detail<select id="offline-zoom"><option value="13">Overview · zoom 13</option><option value="14">Standard · zoom 14</option><option value="15" selected>Detailed · zoom 15</option><option value="16">Very detailed · zoom 16</option><option value="17">Maximum imagery · zoom 17</option></select></label><label>Map content<select id="offline-imagery"><option value="aerial">LINZ aerial</option><option value="satellite">Satellite</option><option value="topo">NZ Topo only · maximum detail</option></select></label><label class="setting">Include NZ Topo and mapped overlays<input id="offline-topo" type="checkbox" checked></label><div class="offline-actions"><button id="offline-estimate-button" type="button">Estimate size</button><button class="primary" type="submit">Download region</button></div><p id="offline-estimate" role="status"></p></form><label class="setting">Use downloaded maps only<input id="offline-only" type="checkbox"></label><div id="offline-list" aria-live="polite"></div>`;
 const oldContent=section.innerHTML,formStart=oldContent.indexOf('<form');
 section.innerHTML='<div class="panel-heading"><h2 id="offline-heading">Choose region</h2><button id="offline-close" aria-label="Close offline regions">×</button></div><div class="page-selection-controls"><label>Page size<select id="offline-page-size">'+Object.keys(paperSizes).map(name=>'<option'+(name==='A4'?' selected':'')+'>'+name+'</option>').join('')+'</select></label><label>Orientation<select id="offline-orientation"><option value="portrait">Portrait</option><option value="landscape">Landscape</option></select></label><button id="offline-save-view" class="primary" type="button">Save current view</button></div>';
 const settings=el('dialog',undefined,'region-dialog');settings.id='offline-settings';settings.setAttribute('aria-labelledby','offline-settings-title');settings.innerHTML='<div class="panel-heading"><h2 id="offline-settings-title">Download region</h2><button id="offline-settings-close" aria-label="Close download settings">×</button></div><div class="dialog-content"><button id="offline-change-area" type="button">Change area</button>'+oldContent.slice(formStart)+'</div>';
 document.querySelector('.workspace').append(section,settings);
 $('offline-save-view').onclick=()=>showDownloadSettings().catch(showError);$('offline-change-area').onclick=()=>openOffline().catch(showError);$('offline-settings-close').onclick=()=>closeOffline();
 settings.addEventListener('cancel',e=>{e.preventDefault();closeOffline();});
 $('offline-page-size').onchange=framePage;$('offline-orientation').onchange=framePage;

 const pack=el('section',undefined,'offline-region');pack.id='overview-pack';pack.innerHTML='<h3>South Island overview imagery</h3><p>Precomputed zooms 0–12 for South Island, Rakiura and nearby islands. Zooms 0–8 are already included. Import all six prepared overview ZIPs once for the remaining imagery; about 365 MB of storage. Elevation is separate.</p><p><a href="https://github.com/connorgleeson736-sys/Ridgeline/tree/android-preview-download/overview-packs" target="_blank" rel="noopener noreferrer">Download the six overview ZIPs</a></p><div class="offline-actions"><button id="overview-import" type="button">Import overview ZIPs</button><button id="overview-remove" type="button">Remove overview pack</button></div><p id="overview-status" role="status">Checking overview pack…</p>';const storage=el('details');storage.innerHTML='<summary>Included imagery</summary>';storage.append(pack);settings.querySelector('.dialog-content').append(storage);
 const showPack=value=>{if(value.bundled){$('overview-pack').querySelector('p').textContent='South Island · Zooms 0–12';$('overview-pack').querySelector('a').parentElement.hidden=true;$('overview-import').hidden=true;$('overview-remove').textContent='Remove old imported copies';$('overview-remove').hidden=!value.importedBytes;$('overview-status').textContent='Included in app · Ready offline';return;}$('overview-status').textContent=(value.complete?'Ready for offline use':value.done?'Partly imported':'Not imported')+` · ${value.done}/${value.total} packs · ${(value.bytes/1e6).toFixed(1)} MB`;};
 if(document.documentElement.dataset.overviewsBundled==='true')showPack({bundled:true,importedBytes:0});
 if(Capacitor.isNativePlatform()){Native.overviewStatus().then(showPack).catch(showError);Native.addListener('overviewProgress',showPack).catch(showError);}
 $('overview-import').onclick=async()=>{const b=$('overview-import');b.disabled=true;try{native();$('overview-status').textContent='Importing and verifying… Keep Ridgeline open.';const result=await Native.importOverviews();if(!result.cancelled){showPack(result);if(result.complete)location.reload();}else showPack(await Native.overviewStatus());}catch(e){showError(e);if(Capacitor.isNativePlatform())showPack(await Native.overviewStatus());}finally{b.disabled=false;}};
 $('overview-remove').onclick=async()=>{try{native();await Native.removeOverviews();location.reload();}catch(e){showError(e);}};
$('offline-close').onclick=()=>closeOffline();$('open-offline').onclick=()=>openOffline().catch(showError);
 for(const id of ['west','south','east','north'])$('offline-'+id).onchange=()=>{try{const bounds=regionBounds();api.getMap().fitBounds([[bounds[0],bounds[1]],[bounds[2],bounds[3]]],{padding:45,duration:0,maxZoom:16});setBounds(bounds);}catch(e){showError(e);}};
 $('offline-imagery').onchange=()=>{const topo=$('offline-imagery').value==='topo';$('offline-zoom').disabled=topo;$('offline-zoom').closest('label').hidden=topo;if(topo){$('offline-zoom').value='16';$('offline-topo').checked=true;}$('offline-topo').disabled=topo;$('offline-estimate').textContent='';};
 $('offline-estimate-button').onclick=async()=>{const b=$('offline-estimate-button');b.disabled=true;try{await estimate();}catch(e){showError(e);}finally{b.disabled=false;}};
 $('offline-form').onsubmit=async e=>{e.preventDefault();const submit=e.submitter||e.currentTarget.querySelector('button[type="submit"]');submit.disabled=true;try{await estimate();await Native.downloadRegion({name:$('offline-name').value,bounds:regionBounds(),zoom:Number($('offline-zoom').value),imagery:$('offline-imagery').value,topo:$('offline-topo').checked,key:api.getKey(),pageSize:$('offline-page-size').value,orientation:$('offline-orientation').value});api.toast('Region queued. Download progress appears below.');await refreshRegions();}catch(error){showError(error);}finally{submit.disabled=false;}};
 $('offline-only').onchange=async e=>{try{native();await Native.setOfflineOnly({enabled:e.target.checked});if(globalThis.caches)for(const key of await caches.keys())if(key.startsWith('ridgeline-'))await caches.delete(key);location.reload();}catch(error){e.target.checked=!e.target.checked;showError(error);}};
 if(Capacitor.isNativePlatform())Native.getSettings().then(s=>{$('offline-only').checked=s.offlineOnly;}).catch(showError);
}
function reloadTiles(){const map=api.getMap();if(!map)return;for(const id of ['linz','satellite','topo','terrain','shade-dem','terrain-fallback']){const s=map.getSource(id);if(s?.setTiles&&s.tiles)s.setTiles([...s.tiles]);}const topo=map.getSource('ridgeline-topography');if(topo?.setTiles&&topo.tiles)topo.setTiles([...topo.tiles]);}
function setupTabs(){const buttons=[$('basic-tab'),$('advanced-tab')];const select=i=>{buttons.forEach((b,j)=>{b.setAttribute('aria-selected',String(i===j));b.tabIndex=i===j?0:-1;});$('layers-basic').hidden=i!==0;$('layers-advanced').hidden=i!==1;};buttons.forEach((b,i)=>{b.onclick=()=>select(i);b.onkeydown=e=>{if(['ArrowLeft','ArrowRight','Home','End'].includes(e.key)){e.preventDefault();const next=e.key==='Home'?0:e.key==='End'?1:1-i;select(next);buttons[next].focus();}};});}
function renderSavedRegions(regions){
 const list=$('saved-region-list');if(!list)return;const stamp=JSON.stringify(regions);if(list.dataset.stamp===stamp)return;list.dataset.stamp=stamp;list.replaceChildren();
 if(!regions.length){list.append(el('p','No saved regions.','empty-saved'));return;}
 for(const region of regions){
  const item=el('article',undefined,'saved-item'),actions=el('div',undefined,'saved-region-actions');item.append(el('strong',region.name),el('p',`${region.state} · ${(region.bytes/1048576).toFixed(1)} MB`));
  actions.append(button('Open',async()=>{
   $('offline-name').value=region.name;if(Number.isFinite(region.zoom))$('offline-zoom').value=String(region.zoom);if(region.imagery)$('offline-imagery').value=region.imagery;if(typeof region.topo==='boolean')$('offline-topo').checked=region.topo;
   $('offline-page-size').value=paperSizes[region.pageSize]?region.pageSize:'A4';$('offline-orientation').value=region.orientation==='landscape'?'landscape':'portrait';$('offline-imagery').onchange();
   api.stopDrawing();$('view-2d').click();if($('sidebar').classList.contains('open'))$('sidebar-close').click();setBounds(region.bounds);await showDownloadSettings();api.getMap().fitBounds([[region.bounds[0],region.bounds[1]],[region.bounds[2],region.bounds[3]]],{padding:45,maxZoom:14,duration:0});
  }));
  if(region.state==='complete'&&(region.topo||region.imagery==='topo'))actions.append(button('Export Topo PDF',()=>openPdfOptions(region)));
  item.append(actions);list.append(item);
 }

}
function openPdfOptions(region){
 let dialog=$('pdf-options');if(!dialog){
  dialog=el('dialog',undefined,'region-dialog');dialog.id='pdf-options';dialog.setAttribute('aria-labelledby','pdf-options-title');
  dialog.innerHTML=`<div class="panel-heading"><h2 id="pdf-options-title">Export Topo PDF</h2><button type="button" id="pdf-options-close" aria-label="Close PDF options">×</button></div><form id="pdf-options-form" class="dialog-content"><label>Page size<select id="pdf-page-size"><option value="auto">Full-detail custom page</option>${Object.keys(paperSizes).map(name=>`<option>${name}</option>`).join('')}</select></label><label>Orientation<select id="pdf-orientation"><option value="portrait">Portrait</option><option value="landscape">Landscape</option></select></label><label>NZTM grid references<select id="pdf-grid"><option value="0">None</option><option value="100">100 m</option><option value="500">500 m</option><option value="1000" selected>1 km</option><option value="5000">5 km</option></select></label><label class="setting">True north arrow<input id="pdf-north" type="checkbox" checked></label><label class="setting">Magnetic north<input id="pdf-magnetic" type="checkbox"></label><div id="pdf-declination-row" hidden><label>Declination ° east (optional)<input id="pdf-declination" type="number" min="-180" max="180" step="0.1" placeholder="Automatic estimate"></label><p>Automatic uses Android’s magnetic model at the map centre. Enter a current local value to override it.</p></div><label class="setting">Scale bar<input id="pdf-scale" type="checkbox" checked></label><div class="offline-actions"><button type="submit" class="primary">Export PDF</button><button type="button" id="pdf-cancel">Cancel</button></div></form>`;
  document.querySelector('.workspace').append(dialog);$('pdf-options-close').onclick=$('pdf-cancel').onclick=()=>dialog.close();$('pdf-magnetic').onchange=()=>{$('pdf-declination-row').hidden=!$('pdf-magnetic').checked;};$('pdf-page-size').onchange=()=>{$('pdf-orientation').disabled=$('pdf-page-size').value==='auto';};
 }
 $('pdf-page-size').value=paperSizes[region.pageSize]?region.pageSize:'auto';$('pdf-orientation').value=region.orientation==='landscape'?'landscape':'portrait';$('pdf-page-size').onchange();
 $('pdf-options-form').onsubmit=async e=>{e.preventDefault();const submit=e.submitter||e.target.querySelector('[type=submit]');submit.disabled=true;try{native();api.toast('Preparing offline Topo PDF…');const options={id:region.id,pageSize:$('pdf-page-size').value,orientation:$('pdf-orientation').value,grid:Number($('pdf-grid').value),north:$('pdf-north').checked,magnetic:$('pdf-magnetic').checked,scaleBar:$('pdf-scale').checked};if($('pdf-declination').value!=='')options.declination=Number($('pdf-declination').value);const result=await Native.exportRegionPdf(options);if(!result.cancelled){dialog.close();api.toast('Topo PDF saved.');}}catch(error){showError(error);}finally{submit.disabled=false;}};
 dialog.showModal();
}
function setupRegionSidebar(){
 const section=el('section',undefined,'saved-regions');section.innerHTML='<h2>Saved regions</h2><div id="saved-region-list"><p class="empty-saved">No saved regions.</p></div>';
 $('saved-panel').append(section);
 const footer=el('div',undefined,'offline-sidebar-footer');footer.append($('open-offline'));$('sidebar').append(footer);
}
function setupPanels(){
 const card=$('profile-card');document.querySelector('.workspace').append(card);
 $('profile-drag-handle').hidden=true;$('profile-resize-handle').hidden=true;
 $('route-close').setAttribute('aria-label','Close route');$('route-close').textContent='×';$('route-close').onclick=()=>{if(api.unloadRoute()){routeWorkspace?.closed();routeWorkspace?.render(api.routes(),api.activeRoute());}};
 $('profile-toggle').hidden=true;$('route-save-status').hidden=true;document.querySelector('.route-more').hidden=true;
 routeSheet=attachSheet(card,{initial:0,onChange:syncPanels});
 offlineSheet=attachSheet($('offline-panel'),{initial:1,onChange:syncPanels});
 const layersDismiss=button('',()=>$('layers-close').click());layersDismiss.className='layers-map-dismiss';layersDismiss.setAttribute('aria-label','Close layers by returning to map');document.querySelector('.workspace').append(layersDismiss);
 new MutationObserver(()=>document.body.classList.toggle('layers-open',!$('layers-panel').hidden)).observe($('layers-panel'),{attributes:true,attributeFilter:['hidden']});
 const dismiss=button('',()=>$('sidebar-close').click());dismiss.className='sidebar-map-dismiss';dismiss.setAttribute('aria-label','Close sidebar by returning to map');document.querySelector('.workspace').append(dismiss);
 $('labels').checked=true;try{const value=localStorage.getItem('ridgeline-linz-peak-labels-v1');if(value!==null)$('labels').checked=value==='1';}catch{}$('labels').addEventListener('change',()=>{try{localStorage.setItem('ridgeline-linz-peak-labels-v1',$('labels').checked?'1':'0');}catch{}});

 const create=$('plan-shortcut');create.id='plan-shortcut';create.className='saved-create';create.innerHTML='<span aria-hidden="true">＋</span> Create new';create.setAttribute('aria-label','Create new route');create.title='Create new route';
 document.querySelector('.saved-import').after(create);
 create.onclick=()=>{if(!api.isReady()){api.toast('Wait for the map to start.');return;}$('clear').click();if($('sidebar').classList.contains('open'))$('sidebar-close').click();};
 $('planner-toggle').addEventListener('click',()=>{if($('sidebar').classList.contains('open')){if(!$('offline-panel').hidden)closeOffline();void refreshRegions();}});
 new MutationObserver(syncPanels).observe(card,{attributes:true,attributeFilter:['hidden']});
 $('layers-toggle').addEventListener('click',()=>{if(!$('layers-panel').hidden&&!$('offline-panel').hidden)closeOffline();});
 document.addEventListener('keydown',e=>{if(e.key==='Escape'){if(selecting)cancelSelection();else if(!$('offline-panel').hidden)closeOffline();else if(!card.hidden)$('route-close').click();}});
 api.getMap().on('mousemove',e=>{if(selecting&&corners.length===1){const a=corners[0],b=[e.lngLat.lng,e.lngLat.lat];drawSelection([Math.min(a[0],b[0]),Math.min(a[1],b[1]),Math.max(a[0],b[0]),Math.max(a[1],b[1])]);}});
 syncPanels();
}
function setupGps(){const b=button('',async()=>{native();if(gps){await Native.stopLocation();gps=false;gpsMarker?.remove();gpsMarker=null;b.setAttribute('aria-pressed','false');return;}await Native.startLocation();gps=true;b.setAttribute('aria-pressed','true');api.toast('Waiting for a GPS fix…');});b.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><circle cx="12" cy="12" r="7"/><circle cx="12" cy="12" r="2"/><path d="M12 2v3m0 14v3M2 12h3m14 0h3"/></svg>';b.id='mobile-gps';b.title='GPS location';b.setAttribute('aria-label','Show GPS location');b.setAttribute('aria-pressed','false');document.querySelector('.map-launcher').append(b);
 if(Capacitor.isNativePlatform())Native.addListener('location',p=>{if(!gps||!api.isReady())return;if(p.error){api.toast(p.error);return;}const position=[p.longitude,p.latitude];if(!gpsMarker){const dot=el('span',undefined,'gps-dot');dot.setAttribute('aria-label','Your GPS location');gpsMarker=new maplibregl.Marker({element:dot}).setLngLat(position).addTo(api.getMap());api.getMap().easeTo({center:position,zoom:Math.max(13,api.getMap().getZoom())});}else gpsMarker.setLngLat(position);b.title=`GPS accuracy approximately ${Math.round(p.accuracy)} m`;}).catch(showError);
}
globalThis.RidgeMobile={loadingStats:()=>Native.loadingStats(),attach(value){api=value;setupTabs();createOfflinePanel();setupRegionSidebar();setupPanels();setupGps();routeWorkspace=attachRoutes(api,{button,el,showError});routeWorkspace.render(api.routes(),api.activeRoute());api.getMap()?.on('moveend',()=>{const m=api.getMap();viewStorage.schedule({center:m.getCenter().toArray(),zoom:m.getZoom(),pitch:m.getPitch(),bearing:m.getBearing()});});const importInput=$('import'),importLink=importInput.closest('label');importLink.addEventListener('click',e=>{if(!Capacitor.isNativePlatform())return;e.preventDefault();Native.importGpx().then(file=>{if(file.text!==undefined)return api.importText(file.name,file.text);}).catch(showError);});},
 lastView(){try{const v=JSON.parse(localStorage.getItem('ridgeline-mobile-view-v1'));if(v&&Array.isArray(v.center)&&v.center.length===2&&v.center.every(Number.isFinite)&&v.center[0]>=165&&v.center[0]<=180&&v.center[1]>=-49&&v.center[1]<=-33&&Number.isFinite(v.zoom)&&v.zoom>=4&&v.zoom<=19&&Number.isFinite(v.pitch)&&v.pitch>=0&&v.pitch<=85&&Number.isFinite(v.bearing))return v;}catch{}return undefined;},
 renderRoutes:(saved,active)=>{routeWorkspace?.render(saved,active);return true;},
 status:()=>({...api?.renderStatus(),openedRoutes:routeWorkspace?.opened()}),
 selecting:()=>selecting||regionDragging||!!($('offline-panel')&&!$('offline-panel').hidden)||!!$('offline-settings')?.open,
 canDrawProfile:()=>!document.body.classList.contains('map-sheet-dragging')&&$('profile-card').dataset.snap==='expanded'&&!$('profile-card').hidden&&!document.body.classList.contains('offline-panel-open'),
 syncPanels,
 mapClick(e){if($('offline-settings')?.open)return true;if(!selecting)return !!($('offline-panel')&&!$('offline-panel').hidden)||regionDragging;const point=[e.lngLat.lng,e.lngLat.lat];if(point[0]<165||point[0]>180||point[1]<-49||point[1]>-33){api.toast('Choose a region within New Zealand.');return true;}if(!corners.length){corners.push(point);const dot=el('span',undefined,'offline-corner');selectionMarkers.push(new maplibregl.Marker({element:dot}).setLngLat(point).addTo(api.getMap()));$('offline-select-hint').querySelector('span').textContent='Tap the opposite corner';return true;}const a=corners[0],b=point;if(Math.abs(a[0]-b[0])<.0001||Math.abs(a[1]-b[1])<.0001){api.toast('Choose a larger rectangle.');return true;}setBounds([Math.min(a[0],b[0]),Math.min(a[1],b[1]),Math.max(a[0],b[0]),Math.max(a[1],b[1])]);stopSelection();void openOffline().catch(showError);return true;},
 async exportGpx(name,text){try{native();const result=await Native.exportGpx({name,text});if(!result.cancelled)api.toast('GPX saved.');}catch(e){showError(e);}},
 back(){if($('pdf-options')?.open){$('pdf-options').close();return true;}if($('offline-settings')?.open){closeOffline();return true;}if(selecting){cancelSelection();return true;}if($('offline-panel')&&!$('offline-panel').hidden){closeOffline();return true;}if(!$('layers-panel').hidden){$('layers-close').click();return true;}if($('sidebar').classList.contains('open')){$('sidebar-close').click();return true;}if(!$('draw-banner').hidden){$('finish').click();return true;}if(!$('profile-card').hidden){$('route-close').click();return true;}return false;}
};
await import('/viewer.mjs?v=113-mobile');
