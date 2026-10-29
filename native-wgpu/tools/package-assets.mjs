// Build-time extraction only. The APK contains JSON/data, never this JS runtime.
import {execFileSync} from 'node:child_process';import fs from 'node:fs';import path from 'node:path';import vm from 'node:vm';import crypto from 'node:crypto';
const root=path.resolve(import.meta.dirname,'../..'),out=path.join(root,'native-wgpu/android/app/src/main/assets');fs.mkdirSync(out,{recursive:true});
const load=name=>{const c={};vm.runInNewContext(fs.readFileSync(path.join(root,'public',name),'utf8'),c);return c;};
const gap=load('gap-imagery-atlas.js').RidgeGapAtlas,south=load('south-overviews.js').RidgeSouthOverviews;
if(Object.values(gap.entries).some(e=>!e.vector))throw Error('Non-vector gap atlas requires legacy mask support');
const catalog=JSON.parse(fs.readFileSync(path.join(root,'capacitor/overview-catalog.json'),'utf8'));
const overview={...catalog,coarse:{}};const copied=new Set();
for(const [key,entry]of Object.entries(south.tiles)){
 if(+key.split('/')[0]>8)continue;
 const select=row=>{if(!row?.url)return null;const name=row.url.replace(/^\//,'');if(!/^assets\/south-overviews-v1\/[a-f0-9]+\.webp$/.test(name))throw Error('Invalid overview path');const dest=path.join(out,name);fs.mkdirSync(path.dirname(dest),{recursive:true});fs.copyFileSync(path.join(root,'public',name),dest);copied.add(name);return name;};
 const raw=select(entry.raw||entry),natural=select(entry.natural||entry);if(raw&&natural)overview.coarse[key]={raw,natural};
}
// Capacitor0.18 explicitly excludes website-only Cook substitutions.
fs.rmSync(path.join(out,'assets/cook-reference-v1'),{recursive:true,force:true});
fs.writeFileSync(path.join(out,'gap-atlas.json'),JSON.stringify(gap));
for(const entry of Object.values(gap.coarse||{})){const name=entry.url?.replace(/^\//,'');if(!name?.startsWith('assets/'))continue;const dest=path.join(out,name);fs.mkdirSync(path.dirname(dest),{recursive:true});fs.copyFileSync(path.join(root,'public',name),dest);}
const full=process.env.RIDGELINE_BUNDLE_OVERVIEWS!=='0';
const packOutput=path.join(out,'overview-packs');fs.rmSync(packOutput,{recursive:true,force:true});
overview.archives={};
if(full){const source=process.env.RIDGELINE_OVERVIEW_ZIPS||path.join(root,'overview-packs');
 overview.archives=JSON.parse(execFileSync('python3',[path.join(root,'native-wgpu/tools/package-archives.py'),source,packOutput],{encoding:'utf8',maxBuffer:1024*1024}));
}
fs.writeFileSync(path.join(out,'overview-index.json'),JSON.stringify(overview));
console.log(JSON.stringify({full,packCount:full?catalog.files.length:0,coarseImages:copied.size,gapEntries:Object.keys(gap.entries).length,overviewCoordinates:Object.keys(catalog.tiles).length}));
