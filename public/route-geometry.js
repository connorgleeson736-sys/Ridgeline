/* Stable small line blocks let MapLibre's diff path reload affected tiles only. */
(function(root){'use strict';
const blockSize=points=>Math.max(1,Math.min(32,Math.floor(points.length/64))),collection=features=>({type:'FeatureCollection',features});
function line(points,block){const size=blockSize(points),coordinates=points.slice(block*size,Math.min(points.length,(block+1)*size+1)).map(p=>p.slice());return coordinates.length<2?null:{type:'Feature',id:block,properties:{},geometry:{type:'LineString',coordinates}};}
function point(points,index,selected){return{type:'Feature',id:index,properties:{number:index+1,selected:index===selected},geometry:{type:'Point',coordinates:points[index].slice()}};}
function full(points,selected){return{route:collection(Array.from({length:Math.ceil(Math.max(0,points.length-1)/blockSize(points))},(_,i)=>line(points,i)).filter(Boolean)),waypoints:collection(points.map((_,i)=>point(points,i,selected)))};}
function diff(points,index){const size=blockSize(points),blocks=new Set([Math.floor(index/size)]);if(index>0&&index%size===0)blocks.add(index/size-1);return{route:{update:[...blocks].map(b=>line(points,b)).filter(Boolean).map(f=>({id:f.id,newGeometry:f.geometry}))},waypoints:{update:[{id:index,newGeometry:{type:'Point',coordinates:points[index].slice()}}]}};}
function create(map,{getPoints,getSelected=()=>-1,clock=()=>root.performance.now(),setTimer=root.setTimeout.bind(root),clearTimer=root.clearTimeout.bind(root),interval=50}={}){
 let dead=false,busy=false,pending=null,timer=null,last=-Infinity,generation=0;const metrics={fullUpdates:0,diffUpdates:0,coalesced:0,fallbacks:0};
 function reset(){generation++;pending=null;if(timer!==null)clearTimer(timer);timer=null;}
 function render(){reset();if(dead)return;const data=full(getPoints(),getSelected());map.getSource('route')?.setData(data.route);map.getSource('waypoints')?.setData(data.waypoints);metrics.fullUpdates++;}
 function pump(){if(dead||busy||pending===null)return;const wait=interval-(clock()-last);if(wait>0){if(timer===null)timer=setTimer(()=>{timer=null;pump();},wait);return;}const index=pending;pending=null;const points=getPoints();if(!points[index])return;const route=map.getSource('route'),waypoints=map.getSource('waypoints');if(!route?.updateData||!waypoints?.updateData){metrics.fallbacks++;render();return;}busy=true;last=clock();const stamp=generation,data=diff(points,index);metrics.diffUpdates++;
  Promise.allSettled([Promise.resolve().then(()=>route.updateData(data.route)),Promise.resolve().then(()=>waypoints.updateData(data.waypoints))]).then(results=>{if(!dead&&stamp===generation&&results.some(r=>r.status==='rejected')){metrics.fallbacks++;render();}}).finally(()=>{busy=false;pump();});
 }
 function preview({index}={}){if(dead||!Number.isInteger(index))return;if(pending!==null)metrics.coalesced++;pending=index;pump();}
 function destroy(){if(dead)return;reset();dead=true;map.off('remove',destroy);}
 map.on('remove',destroy);return{render,preview,destroy,stats:()=>({...metrics,busy,pending:pending!==null})};
}
const api={blockSize,full,diff,create};root.RidgeRouteGeometry=api;if(typeof module!=='undefined')module.exports=api;
})(globalThis);
