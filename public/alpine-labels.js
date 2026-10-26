/* Small screen-space label budget shared by both renderers. No glyph downloads. */
(function(root){
'use strict';
function visible(peaks,selected,zoom,project,width,height){
 const placed=[],result=new Set();
 const order=peaks.map((p,i)=>({p,i})).sort((a,b)=>(a.i===selected?-1:b.i===selected?1:(a.p.priority??2)-(b.p.priority??2)||a.i-b.i));
 for(const {p,i} of order){
  if(zoom<(i===selected?8:p.minZoom??8))continue;
  const q=project(p,i);if(!q||!Number.isFinite(q.x)||!Number.isFinite(q.y)||q.hidden)continue;
  const w=Math.max(90,(p.short.length+10)*6.3),box={l:q.x-w/2-5,r:q.x+w/2+5,t:q.y-27,b:q.y+15};
  if(box.r<0||box.l>width||box.b<0||box.t>height)continue;
  if(placed.some(a=>box.l<a.r&&box.r>a.l&&box.t<a.b&&box.b>a.t))continue;
  placed.push(box);result.add(i);
 }return result;
}
const api={visible};if(typeof module!=='undefined')module.exports=api;else root.RidgeAlpineLabels=api;
})(globalThis);
