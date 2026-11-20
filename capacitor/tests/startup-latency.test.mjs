import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

const www=new URL('../www/',import.meta.url);

// Mirrors regional-imagery.js: the exact URL/pack/offset/length used for each grade.
function resolve(south,key,compensate){
 const entry=south.tiles[key];if(!(entry&&(entry.url||entry[compensate?'natural':'raw']?.url)))return null;
 const row=Array.isArray(entry)||entry.url?entry:entry[compensate?'natural':'raw'],r=row.range||row;
 return [row.url,Array.isArray(r)?south.packs[r[0]]:r.file,Array.isArray(r)?r[1]:r.offset,Array.isArray(r)?r[2]:r.bytes];
}

test('South Island catalogue is compact, JSON-parsed and resolves every catalogued range for both grades',async()=>{
 const text=await readFile(new URL('south-overviews.js',www),'utf8');
 assert.match(text,/^\(function\(r\)\{r\.RidgeSouthOverviews=JSON\.parse\("/);
 assert.ok(text.length<1.6e6,'catalogue regressed to the expanded object literal');
 const holder={};vm.runInNewContext(text,holder);const south=holder.RidgeSouthOverviews;
 const catalog=JSON.parse(await readFile(new URL('../overview-catalog.json',import.meta.url),'utf8'));
 let checked=0;
 for(const [key,variants] of Object.entries(catalog.tiles))for(const [mode,compensate] of [['natural',true],['raw',false]]){
  const r=variants[mode]??Object.values(variants)[0];
  assert.deepEqual(resolve(south,key,compensate),[`https://localhost/_ridge/overviews/${r.file}/${r.offset}/${r.bytes}`,r.file,r.offset,r.bytes],key+' '+mode);checked++;
 }
 assert.equal(checked,2*Object.keys(catalog.tiles).length);
});

test('app page opens no unused WebView provider connections',async()=>{
 const html=await readFile(new URL('index.html',www),'utf8');
 assert.doesNotMatch(html,/rel="?preconnect/);
});
