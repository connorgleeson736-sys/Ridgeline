/* Original-viewer loading preferences; active native-job caps include cached work; empty caches use all slots. */
(function(root){
'use strict';
const key='ridgeline-loading-v1';
const fields={selectionInterval:{id:'loading-selection-interval',min:0,max:200,value:0},moving:{id:'loading-moving',min:1,max:96,value:8},stationary:{id:'loading-stationary',min:1,max:96,value:12},imageryPending:{id:'loading-imagery-pending',min:0,max:24,value:4},demPending:{id:'loading-dem-pending',min:0,max:12,value:2},predictive:{id:'loading-predictive',min:0,max:12,value:2}};
function defaults(saveData=false){return {cancelWhileZooming:false,...Object.fromEntries(Object.entries(fields).map(([name,f])=>[name,saveData&&name==='stationary'?6:saveData&&name==='imageryPending'?2:saveData&&name==='demPending'?1:f.value]))};}
function normalize(raw,saveData=false){const result=defaults(saveData);for(const [name,f]of Object.entries(fields))if(Number.isFinite(raw?.[name]))result[name]=Math.max(f.min,Math.min(f.max,Math.round(raw[name])));result.cancelWhileZooming=raw?.cancelWhileZooming===true;return result;}
function applyEngine(engine,values){
 if(engine?.getVersion?.()!=='6.14.0'||!engine.config||typeof engine.setMaxParallelImageRequests!=='function')return false;
 engine.config.MAX_PARALLEL_IMAGE_REQUESTS_PER_FRAME=values.moving;engine.setMaxParallelImageRequests(values.stationary);engine.pumpImageRequestQueue?.();return true;
}
function attach({document=root.document,engine=root.maplibregl,storage,saveData=!!root.navigator?.connection?.saveData,onChange=()=>{}}={}){
 try{storage??=root.localStorage;}catch{}
 let raw;try{raw=JSON.parse(storage?.getItem(key)||'null');}catch{}
 let values=normalize(raw,saveData),dead=false;const listeners=[];
 function update(){const cancel=document?.getElementById('loading-cancel-zoom');if(cancel)cancel.checked=values.cancelWhileZooming;for(const [name,f]of Object.entries(fields)){const input=document?.getElementById(f.id),output=document?.getElementById(f.id+'-value');if(input){input.value=String(values[name]);input.setAttribute?.('aria-valuetext',name==='selectionInterval'?(values[name]===0?'Every frame':String(values[name])+' milliseconds'):values[name]===0?'Off':String(values[name])+' jobs');}if(output)output.textContent=name==='selectionInterval'?(values[name]===0?'Every frame':String(values[name])+' ms'):values[name]===0?'Off':String(values[name]);}}
 function apply(persist=true){if(dead)return;update();applyEngine(engine,values);if(persist)try{storage?.setItem(key,JSON.stringify(values));}catch{}onChange({...values});}
 for(const [name,f]of Object.entries(fields)){const input=document?.getElementById(f.id);if(!input)continue;const fn=()=>{values=normalize({...values,[name]:Number(input.value)},saveData);apply();};input.addEventListener('input',fn);listeners.push(()=>input.removeEventListener('input',fn));}
 const cancel=document?.getElementById('loading-cancel-zoom');if(cancel){const fn=()=>{values={...values,cancelWhileZooming:cancel.checked};apply();};cancel.addEventListener('change',fn);listeners.push(()=>cancel.removeEventListener('change',fn));}
 const reset=document?.getElementById('loading-reset');if(reset){const fn=()=>{values=defaults(saveData);apply();};reset.addEventListener('click',fn);listeners.push(()=>reset.removeEventListener('click',fn));}
 apply(false);
 return{values:()=>({...values}),applyEngine:()=>applyEngine(engine,values),destroy(){if(dead)return;dead=true;for(const off of listeners)off();}};
}
const api={fields,defaults,normalize,applyEngine,attach};if(typeof module!=='undefined')module.exports=api;else root.RidgeLoadingControls=api;
})(globalThis);
