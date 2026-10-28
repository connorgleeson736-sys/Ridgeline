import {test} from 'node:test';import assert from 'node:assert/strict';import {profileMainThread} from '../scripts/main-thread-profile.mjs';
test('component timings preserve receivers, results, exceptions and restore original hooks',()=>{
 let now=0;const original=function(x){assert.equal(this,source);now+=3;return x+1;},source={update:original};
 const terrain={update(){now+=4;throw Error('expected');}};const map={style:{tileManagers:{linz:source}},terrain:{tileManager:terrain},painter:{renderToTexture:{prepareForRender(){now+=2;}}}};
 const p=profileMainThread(map,()=>now);assert.equal(source.update(2),3);assert.throws(()=>terrain.update(),/expected/);map.painter.renderToTexture.prepareForRender();
 const out=p.snapshot().components;assert.equal(out['source-selection:linz'].totalMs,3);assert.equal(out['terrain-selection'].calls,1);assert.equal(out['texture-preparation'].p95Ms,2);
 p.destroy();assert.equal(source.update,original);
});
test('bounded timing samples and cleanup preserve a later owner wrapper',()=>{
 let now=0;const owner={update(){now++;}};const p=profileMainThread({style:{tileManagers:{linz:owner}}},()=>now);for(let i=0;i<1000;i++)owner.update();assert.equal(p.snapshot().components['source-selection:linz'].recentSamples,256);const later=()=>{};owner.update=later;p.destroy();assert.equal(owner.update,later);
});
