import {test} from 'node:test';
import assert from 'node:assert/strict';
import {resetWebsiteRenderPreferences} from '../mobile/render-preferences.js';
test('website renderer migration resets rendering choices once and preserves app data',()=>{
 const values=new Map([['ridgeline-phone-mode-v1','1'],['ridgeline-detail-v1','old'],['ridgeline-routes-v1','saved routes'],['ridgeline-linz-peak-labels-v1','0'],['ridgeline-mobile-view-v1','camera']]);
 const storage={getItem:k=>values.get(k),setItem:(k,v)=>values.set(k,v),removeItem:k=>values.delete(k)};
 resetWebsiteRenderPreferences(storage);assert.equal(values.has('ridgeline-phone-mode-v1'),false);assert.equal(values.has('ridgeline-detail-v1'),false);assert.equal(values.get('ridgeline-routes-v1'),'saved routes');assert.equal(values.get('ridgeline-linz-peak-labels-v1'),'0');assert.equal(values.get('ridgeline-mobile-view-v1'),'camera');
 values.set('ridgeline-phone-mode-v1','1');resetWebsiteRenderPreferences(storage);assert.equal(values.get('ridgeline-phone-mode-v1'),'1');
});
