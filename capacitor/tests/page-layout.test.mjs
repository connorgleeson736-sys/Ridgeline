import {test} from 'node:test';
import assert from 'node:assert/strict';
import {paperSizes,pageFrame} from '../mobile/page-layout.js';
test('paper frames fit portrait and landscape phone viewports and match PDF margins',()=>{
 for(const [size,mm]of Object.entries(paperSizes))for(const orientation of ['portrait','landscape'])for(const [w,h]of [[412,779],[915,276]]){
  const box=pageFrame(size,orientation,w,h);assert.ok(box[0]>=.079&&box[1]>=.079&&box[2]<=.921&&box[3]<=.921);
  let [pw,ph]=mm.map(n=>n*72/25.4);if(orientation==='landscape')[pw,ph]=[ph,pw];assert.ok(Math.abs(((box[2]-box[0])*w/((box[3]-box[1])*h))-(pw-60)/(ph-94))<1e-10);
 }
 assert.throws(()=>pageFrame('invalid','portrait',400,700));
});
