// A viewport-anchored selection frame. The map moves freely underneath it.
export function attachRegionSelection(map,{onChange,onCommit,onDrag=()=>{}}){
 const area=document.getElementById('map-area'),ns='http://www.w3.org/2000/svg';
 const svg=document.createElementNS(ns,'svg');svg.classList.add('region-outline');const polygon=document.createElementNS(ns,'polygon');svg.append(polygon);area.append(svg);
 let bounds,visible=false,handles=false,frame,drag,pending,box=[.15,.16,.85,.82],aspect=0,lastDimensions;
 function dimensions(){return {w:area.clientWidth,h:Math.max(60,area.clientHeight-(document.body.classList.contains('bottom-panel-open')?24:0))};}
 function pixels(){const {w,h}=dimensions();if(aspect&&lastDimensions!==w+'/'+h){const cx=(box[0]+box[2])/2,cy=(box[1]+box[3])/2,fw=Math.min((box[2]-box[0])*w,(box[3]-box[1])*h*aspect),fh=fw/aspect;box=[cx-fw/w/2,cy-fh/h/2,cx+fw/w/2,cy+fh/h/2];lastDimensions=w+'/'+h;}return [[box[0]*w,box[3]*h],[box[2]*w,box[3]*h],[box[2]*w,box[1]*h],[box[0]*w,box[1]*h]];}
 function report(commit=false){if(!visible||!handles)return;const points=pixels(),sw=map.unproject(points[0]),ne=map.unproject(points[2]);const next=[sw.lng,sw.lat,ne.lng,ne.lat];if(!bounds||next.some((n,i)=>Math.abs(n-bounds[i])>1e-10)){bounds=next;onChange(bounds.slice());}if(commit)onCommit(bounds.slice());}
 function resize(index,x,y){pixels();const {w,h}=dimensions(),right=index===1||index===2,lower=index<2;if(aspect){const ax=box[right?0:2]*w,ay=box[lower?1:3]*h,maxW=right?w*.96-ax:ax-w*.04,maxH=lower?h*.94-ay:ay-h*.06,desired=(Math.abs(x-ax)+Math.abs(y-ay)/aspect)/(1+1/(aspect*aspect)),fw=Math.min(maxW,maxH*aspect,Math.max(32,32*aspect,desired));x=ax+(right?fw:-fw);y=ay+(lower?fw/aspect:-fw/aspect);}box[right?2:0]=right?Math.min(.96,Math.max(box[0]+32/w,x/w)):Math.max(.04,Math.min(box[2]-32/w,x/w));box[lower?3:1]=lower?Math.min(.94,Math.max(box[1]+32/h,y/h)):Math.max(.06,Math.min(box[3]-32/h,y/h));draw();report();}
 const nodes=['southwest','southeast','northeast','northwest'].map((name,index)=>{
  const node=document.createElement('button');node.type='button';node.className='offline-region-corner';node.setAttribute('aria-label','Resize region '+name+' corner');area.append(node);
  function end(event,cancelled=false){if(!drag||event.pointerId!==drag.id||drag.node!==node)return;if(pending){resize(index,...pending);pending=undefined;}const old=drag;drag=undefined;if(cancelled)box=old.box;if(old.pan)map.dragPan.enable();onDrag(false);if(node.hasPointerCapture(old.id))node.releasePointerCapture(old.id);draw();report(true);}
  node.addEventListener('pointerdown',event=>{if(event.button>0||event.isPrimary===false||drag)return;event.preventDefault();event.stopPropagation();const rect=area.getBoundingClientRect(),p=pixels()[index];drag={node,index,id:event.pointerId,box:box.slice(),pan:map.dragPan.isEnabled(),dx:event.clientX-rect.left-p[0],dy:event.clientY-rect.top-p[1]};if(drag.pan)map.dragPan.disable();onDrag(true);node.setPointerCapture(event.pointerId);});
  node.addEventListener('pointermove',event=>{if(!drag||drag.node!==node||event.pointerId!==drag.id)return;event.preventDefault();event.stopPropagation();const rect=area.getBoundingClientRect();pending=[event.clientX-rect.left-drag.dx,event.clientY-rect.top-drag.dy];schedule();});
  node.addEventListener('pointerup',e=>end(e));node.addEventListener('pointercancel',e=>end(e,true));node.addEventListener('lostpointercapture',e=>end(e,true));
  for(const name of ['mousedown','touchstart','click'])node.addEventListener(name,e=>e.stopPropagation());
  node.addEventListener('keydown',e=>{const step=e.shiftKey?16:4,d={ArrowLeft:[-step,0],ArrowRight:[step,0],ArrowUp:[0,-step],ArrowDown:[0,step]}[e.key];if(!d)return;e.preventDefault();const p=pixels()[index];resize(index,p[0]+d[0],p[1]+d[1]);report(true);});
  return node;
 });
 function draw(){svg.style.display=visible&&bounds?'':'none';nodes.forEach(n=>n.hidden=!visible||!handles||!bounds);if(!visible||!bounds)return;const points=handles?pixels():[[bounds[0],bounds[1]],[bounds[2],bounds[1]],[bounds[2],bounds[3]],[bounds[0],bounds[3]]].map(p=>{const s=map.project(p);return [s.x,s.y];});polygon.setAttribute('points',points.map(p=>p.join(',')).join(' '));points.forEach((p,i)=>nodes[i].style.transform=`translate3d(${p[0]-16}px,${p[1]-16}px,0)`);}
 function refresh(){if(document.body.classList.contains('map-sheet-dragging'))return;draw();report();}
 function schedule(){if(frame!==undefined)return;frame=requestAnimationFrame(()=>{frame=undefined;if(pending&&drag){resize(drag.index,...pending);pending=undefined;}else refresh();});}
 map.on('move',schedule);map.on('resize',schedule);map.on('moveend',()=>{refresh();if(visible&&handles)report(true);});
 return {
  set(value,showHandles=false){aspect=0;lastDimensions=undefined;bounds=value?.slice();visible=!!value;handles=showHandles;if(bounds&&handles){const {w,h}=dimensions(),sw=map.project([bounds[0],bounds[1]]),ne=map.project([bounds[2],bounds[3]]);if(ne.x-sw.x>=32&&sw.y-ne.y>=32&&sw.x>=0&&ne.x<=w&&ne.y>=0&&sw.y<=h)box=[sw.x/w,ne.y/h,ne.x/w,sw.y/h];}refresh();},
  begin(value=[.15,.16,.85,.82],lockAspect=false){box=value.slice();const {w,h}=dimensions();aspect=lockAspect?(box[2]-box[0])*w/((box[3]-box[1])*h):0;lastDimensions=w+'/'+h;visible=true;handles=true;bounds=undefined;report(true);draw();},
  refresh,hide(){visible=false;draw();}
 };
}
