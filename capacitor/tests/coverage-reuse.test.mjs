import {test} from 'node:test';import assert from 'node:assert/strict';import {readFile} from 'node:fs/promises';import vm from 'node:vm';
test('unchanged ready imagery reuses coverage, while a hole or coordinate change invalidates it',async()=>{
 let builds=0;let source=await readFile(new URL('../www/terrain-streaming.js',import.meta.url),'utf8');source=source.replace('function completeCoverage(ids){','function completeCoverage(ids){globalThis.builds++;');
 const c={module:{exports:{}},builds:0};c.globalThis=c;vm.runInNewContext(source,c);const make=c.module.exports.imageReadiness;
 const ids=[{key:'a',canonical:{z:4,x:4,y:6}},{key:'b',canonical:{z:4,x:5,y:6}},{key:'c',canonical:{z:4,x:4,y:7}},{key:'d',canonical:{z:4,x:5,y:7}}];let ready=new Set(ids.map(v=>v.key));
 const manager={getVisibleCoordinates:()=>ids.slice(),getTile:id=>ready.has(id.key)?{texture:{}}:{}};
 const map={getZoom:()=>4,style:{_layers:{image:{type:'raster',source:'linz'}},tileManagers:{linz:manager}}};const parent={canonical:{z:3,x:2,y:3}};
 const retained=make(map);assert.equal(retained(parent),true);for(let i=0;i<100;i++)assert.equal(make(map)(parent),true);assert.equal(c.builds,1);
 ready.delete('d');assert.equal(make(map)(parent),false);assert.equal(c.builds,2);assert.equal(retained(parent),true,'Previously saved image coverage stays immutable');
 ready.add('d');assert.equal(make(map)(parent),true);assert.equal(c.builds,3);
 ids[3]={key:'new',canonical:{z:4,x:6,y:7}};ready.add('new');assert.equal(make(map)(parent),false);assert.equal(c.builds,4);
});
