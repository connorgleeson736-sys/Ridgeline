import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createViewStorage} from '../mobile/view-storage.js';
test('rapid gestures coalesce into one idle write; unchanged views and lifecycle flush do not duplicate it',()=>{
 const pending=new Map(),writes=[];let id=0;
 const store=createViewStorage({write:s=>writes.push(s),requestIdle:f=>{pending.set(++id,f);return id;},cancelIdle:i=>pending.delete(i)});
 for(let x=0;x<100;x++)store.schedule({x});assert.equal(writes.length,0);assert.equal(pending.size,1);
 pending.values().next().value();assert.deepEqual(writes,['{"x":99}']);
 store.schedule({x:99});store.flush();assert.equal(writes.length,1);
 store.schedule({x:100});store.flush();assert.equal(writes.length,2);assert.equal(pending.size,0);
});
test('storage failures are contained and the timer fallback remains usable',()=>{
 let task,cleared=0;const store=createViewStorage({write(){throw Error('Storage full');},requestIdle:null,cancelIdle:null,setTimer:f=>{task=f;return 1;},clearTimer:()=>cleared++});
 store.schedule({x:1});assert.doesNotThrow(()=>task());store.schedule({x:2});assert.doesNotThrow(()=>store.flush());assert.equal(cleared,2);
});
