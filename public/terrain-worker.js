'use strict';
importScripts('/geo.js?v=4','/terrain-core.js?v=6');
const engine=RidgeTerrain.createEngine({G:RidgeGeo});let active;
self.onmessage=async({data})=>{
 active?.abort();if(data.type==='cancel')return;
 const controller=new AbortController();active=controller;
 try{await engine.run(data.options,controller.signal,result=>{if(!controller.signal.aborted)self.postMessage({type:'progress',id:data.id,result});});if(!controller.signal.aborted)self.postMessage({type:'done',id:data.id});}
 catch(error){if(!controller.signal.aborted)self.postMessage({type:'error',id:data.id,message:'Elevation sampling could not complete.'});}
};
