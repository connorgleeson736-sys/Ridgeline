import {test} from 'node:test';import assert from 'node:assert/strict';import {parseHTML} from 'linkedom';
import {attachDiskCacheControls} from '../mobile/disk-cache-controls.js';
test('disk slider persists the latest value through overlapping saves and reports the restart',async()=>{
 const {document,window}=parseHTML('<input id="automatic-disk-cache" type="range" min="0" max="1000" value="256"><output id="automatic-disk-cache-value"></output><p id="automatic-disk-cache-status"></p>');
 const calls=[];let finish;const first=new Promise(r=>finish=r);const control=attachDiskCacheControls({document,settings:Promise.resolve({diskCacheMB:256}),native:{async setDiskCache(args){calls.push(args.megabytes);if(calls.length===1)await first;}}});
 await Promise.resolve();const input=document.getElementById('automatic-disk-cache');assert.equal(input.disabled,false);
 input.value='1000';input.dispatchEvent(new window.Event('input'));assert.equal(input.getAttribute('aria-valuetext'),'1 GB');input.dispatchEvent(new window.Event('change'));
 input.value='0';input.dispatchEvent(new window.Event('change'));finish();await new Promise(r=>setTimeout(r,0));
 assert.deepEqual(calls,[1000,0]);assert.match(document.getElementById('automatic-disk-cache-status').textContent,/restart/);assert.equal(input.getAttribute('aria-valuetext'),'Off');control.destroy();
});
