import {test} from 'node:test';
import assert from 'node:assert/strict';
import {combineRoutes} from '../mobile/routes.js';
test('combines selected order/direction, removing only identical join points',()=>{
 const a={points:[[170,-43],[171,-44]]},b={points:[[172,-45],[171,-44]],reverse:true};assert.deepEqual(combineRoutes([a,b]),[[170,-43],[171,-44],[172,-45]]);assert.deepEqual(a.points,[[170,-43],[171,-44]]);
 assert.deepEqual(combineRoutes([a,{points:[[173,-45],[174,-46]]}]),[[170,-43],[171,-44],[173,-45],[174,-46]]);
});
test('combination keeps originals and enforces the existing route point limit',()=>{assert.throws(()=>combineRoutes([{points:[[170,-43]]}]));assert.throws(()=>combineRoutes([{points:Array.from({length:2000},(_,i)=>[170+i/10000,-43])},{points:[[171,-44],[172,-45]]}]),/2,000/);});
import {routeLength,routePreview} from '../mobile/routes.js';
test('route summaries measure distance and previews fit horizontal, vertical and single-point routes',()=>{
 assert.ok(Math.abs(routeLength([[170,-43],[170,-44]])-111195)<10);
 for(const points of [[[170,-43]],[[170,-43],[171,-43]],[[170,-43],[170,-44]],[[170,-43],[170.5,-43.5],[171,-43]]]){
  const preview=routePreview(points);assert.equal(preview.length,points.length);for(const p of preview){assert.ok(p.every(Number.isFinite));assert.ok(p[0]>=7.99&&p[0]<=64.01&&p[1]>=7.99&&p[1]<=48.01);}
 }
});

import {parseHTML} from 'linkedom';
import {attachRoutes} from '../mobile/routes.js';
test('route cards reuse nodes and geometry, defer hidden work, and skip unchanged overlay updates',async()=>{
 const {document,window}=parseHTML('<aside id="sidebar"><div id="saved-list"></div></aside>');
 const oldDocument=globalThis.document,oldObserver=globalThis.MutationObserver;
 globalThis.document=document;globalThis.MutationObserver=window.MutationObserver;
 try{
  let updates=0,source;const map={on(){},getSource:()=>source,addSource:()=>{source={setData(){updates++;}};},addLayer(){}};
  let routes=Array.from({length:50},(_,i)=>({id:String(i),name:'Route '+i,points:[[170,-43],[170+i/1000,-44]]}));
  const api={getMap:()=>map,isReady:()=>true,renameRoute:(id,name)=>{routes=routes.map(r=>r.id===id?{...r,name}:r);return true;},routes:()=>routes,activeRoute:()=>null};
  const el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;},button=(text,fn)=>{const n=el('button',text);n.onclick=fn;return n;};
  const workspace=attachRoutes(api,{el,button,showError:e=>{throw e;}});workspace.render(routes,null);
  assert.equal(document.querySelectorAll('.saved-item').length,0,'No hidden route cards built');assert.equal(source,undefined,'No empty passive route source allocated');
  document.getElementById('sidebar').classList.add('open');await new Promise(r=>setImmediate(r));
  const cards=[...document.querySelectorAll('.saved-item')],previews=cards.map(n=>n.querySelector('svg'));assert.equal(cards.length,50);
  let created=0;const create=document.createElement.bind(document),createNS=document.createElementNS.bind(document);document.createElement=(...args)=>{created++;return create(...args);};document.createElementNS=(...args)=>{created++;return createNS(...args);};
  workspace.render(routes.map(r=>({...r,points:r.points.map(p=>p.slice())})),null);assert.equal(created,0,'An unchanged 50-route refresh creates zero elements');document.createElement=create;document.createElementNS=createNS;
  assert.deepEqual([...document.querySelectorAll('.saved-item')],cards);assert.deepEqual(cards.map(n=>n.querySelector('svg')),previews);assert.equal(updates,0);
  routes=routes.map(r=>r.id==='0'?{...r,name:'Renamed'}:r);workspace.render(routes,null);assert.equal(cards[0].querySelector('strong').textContent,'Renamed');assert.equal(cards[0].querySelector('svg'),previews[0]);
  document.getElementById('sidebar').classList.remove('open');routes=routes.map(r=>r.id==='1'?{...r,points:[[170,-43],[172,-45]]}:r);workspace.render(routes,null);assert.equal(cards[1].querySelector('svg'),previews[1]);
  document.getElementById('sidebar').classList.add('open');await new Promise(r=>setImmediate(r));assert.notEqual(cards[1].querySelector('svg'),previews[1]);assert.equal(cards[0].querySelector('svg'),previews[0]);
  routes=routes.slice(1);workspace.render(routes,null);assert.equal(cards[0].isConnected,false);assert.equal(document.querySelectorAll('.saved-item').length,49);
 }finally{globalThis.document=oldDocument;globalThis.MutationObserver=oldObserver;}
});
