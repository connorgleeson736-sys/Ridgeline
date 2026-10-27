// Screen overlay: no GeoJSON worker round-trips while resizing a rectangle.
export function attachRegionSelection(map,{onChange,onCommit,onDrag=()=>{}}){
 const area=document.getElementById('map-area'),ns='http://www.w3.org/2000/svg';
 const svg=document.createElementNS(ns,'svg');svg.classList.add('region-outline');const polygon=document.createElementNS(ns,'polygon');svg.append(polygon);area.append(svg);
 let bounds,visible=false,handles=false,frame,drag,pending;
 const nodes=['southwest','southeast','northeast','northwest'].map((name,index)=>{
  const node=document.createElement('button');node.type='button';node.className='offline-region-corner';node.setAttribute('aria-label','Resize region '+name+' corner');area.append(node);
  function change(p){const b=bounds.slice(),east=index===1||index===2,north=index>=2;b[east?2:0]=east?Math.max(b[0]+.0001,Math.min(180,p.lng)):Math.min(b[2]-.0001,Math.max(165,p.lng));b[north?3:1]=north?Math.max(b[1]+.0001,Math.min(-33,p.lat)):Math.min(b[3]-.0001,Math.max(-49,p.lat));bounds=b;onChange(b);draw();}
  function flush(){if(pending){const p=pending;pending=undefined;change(p);}}
  function end(event,cancelled=false){if(!drag||event.pointerId!==drag.id||drag.node!==node)return;flush();const old=drag;drag=undefined;if(cancelled){bounds=old.bounds;onChange(bounds);}if(old.pan)map.dragPan.enable();onDrag(false);if(node.hasPointerCapture(old.id))node.releasePointerCapture(old.id);draw();onCommit(bounds);}
  node.addEventListener('pointerdown',event=>{if(event.button>0||event.isPrimary===false||drag)return;event.preventDefault();event.stopPropagation();drag={node,id:event.pointerId,bounds:bounds.slice(),pan:map.dragPan.isEnabled()};if(drag.pan)map.dragPan.disable();onDrag(true);node.setPointerCapture(event.pointerId);});
  node.addEventListener('pointermove',event=>{if(!drag||drag.node!==node||event.pointerId!==drag.id)return;event.preventDefault();event.stopPropagation();const rect=map.getCanvas().getBoundingClientRect();pending=map.unproject([event.clientX-rect.left,event.clientY-rect.top]);schedule();});
  node.addEventListener('pointerup',e=>end(e));node.addEventListener('pointercancel',e=>end(e,true));node.addEventListener('lostpointercapture',e=>end(e,true));
  for(const name of ['mousedown','touchstart','click'])node.addEventListener(name,e=>e.stopPropagation());
  node.addEventListener('keydown',e=>{const step=e.shiftKey?.01:.001,d={ArrowLeft:[-step,0],ArrowRight:[step,0],ArrowUp:[0,step],ArrowDown:[0,-step]}[e.key];if(!d)return;e.preventDefault();const p=corners()[index];change({lng:p[0]+d[0],lat:p[1]+d[1]});onCommit(bounds);});
  return node;
 });
 function corners(){return [[bounds[0],bounds[1]],[bounds[2],bounds[1]],[bounds[2],bounds[3]],[bounds[0],bounds[3]]];}
 function draw(){svg.style.display=visible&&bounds?'':'none';nodes.forEach(n=>n.hidden=!visible||!handles||!bounds);if(!visible||!bounds)return;const points=corners().map(p=>map.project(p));polygon.setAttribute('points',points.map(p=>p.x+','+p.y).join(' '));points.forEach((p,i)=>{nodes[i].style.transform=`translate3d(${p.x-16}px,${p.y-16}px,0)`;});}
 function schedule(){if(frame!==undefined)return;frame=requestAnimationFrame(()=>{frame=undefined;if(pending){const p=pending;pending=undefined;const index=nodes.indexOf(drag?.node);if(index>=0){const b=bounds.slice(),east=index===1||index===2,north=index>=2;b[east?2:0]=east?Math.max(b[0]+.0001,Math.min(180,p.lng)):Math.min(b[2]-.0001,Math.max(165,p.lng));b[north?3:1]=north?Math.max(b[1]+.0001,Math.min(-33,p.lat)):Math.min(b[3]-.0001,Math.max(-49,p.lat));bounds=b;onChange(b);}}draw();});}
 map.on('move',schedule);map.on('resize',schedule);
 return {set(value,showHandles=false){bounds=value?.slice();visible=!!value;handles=showHandles;draw();},hide(){visible=false;draw();}};
}
