import {readdir,stat,readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
import path from 'node:path';
const root=path.resolve('dist/client');
const files=[];
async function walk(dir){for(const entry of await readdir(dir,{withFileTypes:true})){const file=path.join(dir,entry.name);if(entry.isDirectory())await walk(file);else files.push(file)}}
await walk(root);
assert.ok(!files.some(f=>!f.endsWith('/cook-reference-overviews.js')&&!/\/assets\/cook-reference-v1\/[a-f0-9]{24}\.webp$/.test(f)&&/\/(?:cook-|mesh-|lidar-summit|terrain-lab)|\/assets\/(?:mt-cook|terrain-lab|vendor)\//.test(f)),'Retired viewers/assets must not deploy');
assert.ok(!files.some(f=>/\/qa\//.test(f)),'QA sample imagery must not deploy');
const html=await readFile(path.join(root,'index.html'),'utf8');
assert.ok(html.includes('/viewer.mjs?v=51'));assert.ok(!/aoraki-mode|cook-model|terrain-lab|lidar-summit/.test(html));
const provenance=JSON.parse(await readFile(path.join(root,'assets/maplibre-provenance.json')));
assert.equal(provenance.version,'6.14.0');
for(const [file,hash] of Object.entries(provenance.files))assert.equal(createHash('sha256').update(await readFile(path.join(root,'assets',file))).digest('hex'),hash);
const bootstrap=await readFile(path.join(root,'viewer.mjs'),'utf8');
for(const match of bootstrap.matchAll(/(?:from |import\s*|import\()['"]\.\/([^'"?]+)(?:\?[^'"]*)?['"]/g))await stat(path.join(root,match[1]));
await stat(path.join(root,'assets/maplibre-gl-worker.mjs'));
assert.ok(!files.some(f=>/cesium|nzgeoid/i.test(f)),'Retired Cesium assets must not deploy');
const luma=JSON.parse(await readFile(path.join(root,'assets/luma/provenance.json')));assert.equal(luma.lumaVersion,'9.4.2');assert.ok(!files.some(f=>f.includes('/assets/three/')),'Three.js assets must not deploy');
for(const [file,hash] of Object.entries(luma.files))assert.equal(createHash('sha256').update(await readFile(path.join(root,'assets/luma',file))).digest('hex'),hash);
for(const file of ['luma-renderer.mjs','terrain-scheduler.mjs','terrain-view-worker.js','terrain-primitives.mjs','luma-terrain-shaders.mjs','luma-terrain.mjs','terrain-data.js','tile-cache.js','native-residency.js','terrain-visibility.js','terrain-patches.js','response-cache.js','provider-health.js','navigation-policy.js','imagery-catalog-index.js','imagery-catalog.mjs','regional-imagery.js','regional-blend.js','regional-imagery-worker.js','west-coast-footprint.js','terrain-streaming.js','assets/west-coast-mask.png','south-overviews.js','imagery-preload.js','prepare-ahead.js','alpine-data.js','alpine-labels.js','topographic-overlays.js','gap-imagery-atlas.js','gap-imagery.js','sky-colours.js'])await stat(path.join(root,file));
const overviewScript=await readFile(path.join(root,'south-overviews.js'),'utf8');
const overviews=JSON.parse(overviewScript.match(/const data=(.*);if\(typeof module/)[1]);
assert.equal(overviews.grade,'snow-v3');assert.equal(overviews.sourceZoom,13);assert.equal(overviews.maximumZoom,12);
assert.equal(Object.keys(overviews.tiles).length,5313,'Only completed South Island coverage can publish');
assert.ok(overviews.packs.length>0);for(const p of overviews.packs)assert.match(p,/^[a-f0-9]{64}$/);
for(const [key,variants]of Object.entries(overviews.tiles)){
 const [z,x,y]=key.split('/').map(Number);assert.ok(z>=0&&z<=12&&x>=0&&x<2**z&&y>=0&&y<2**z);
 for(const item of Array.isArray(variants)||variants.url?[variants]:[variants.raw,variants.natural]){
  const row=item.range||item;
  if(item.url){const data=await readFile(path.join(root,item.url.slice(1)));assert.equal(createHash('sha256').update(data).digest('hex').slice(0,24),path.basename(item.url,'.webp'));}
  assert.ok(Array.isArray(row)&&row.length===3);assert.ok(overviews.packs[row[0]]);assert.ok(Number.isSafeInteger(row[1])&&row[1]>=0);assert.ok(Number.isSafeInteger(row[2])&&row[2]>=20&&row[2]<500000);
 }
}
const release=JSON.parse(await readFile('SOUTH_ISLAND_IMAGERY_VALIDATION.json','utf8'));assert.equal(release.run,overviews.run);assert.equal(release.overviewCoordinates,5313);assert.equal(release.allOutputsPersistent,true);
assert.ok(!html.includes('alpine-route-list')&&!html.includes('alpine-legend'));assert.ok(!/id="hillshade"[^>]* checked/.test(html));
const app=await readFile(path.join(root,'app.js'),'utf8');assert.ok(!app.includes('alpine-routes'));assert.ok(app.includes('RidgeImageryPreload.attach'));assert.ok(app.includes('ridge-grade=snow-v3'));
assert.ok(!files.some(f=>f.includes('/assets/alpine-overviews-v1/')),'Retired Cook-only assets must not ship');
assert.ok(!bootstrap.includes('alpine-overviews.js'),'Retired Cook-only manifest must not shadow South Island coverage');
assert.ok(app.includes('maxPitch:85'));
for(const file of ['bottleneck-diagnostics.js','viewer.mjs','app.js','native-residency.js','raster-residency.js','imagery-diagnostics.js','luma-renderer.mjs','tile-cache.js','prepare-ahead.js','terrain-composition.js'])assert.equal(createHash('sha256').update(await readFile(path.join(root,file))).digest('hex'),createHash('sha256').update(await readFile(path.join('public',file))).digest('hex'),'Build must contain latest '+file);
for(const entry of ['bottleneck-diagnostics.js?v=3','native-residency.js?v=22','raster-residency.js?v=13','imagery-diagnostics.js?v=23','app.js?v=47','tile-cache.js?v=13','loading-controls.js?v=4','imagery-preload.js?v=5'])assert.ok(bootstrap.includes(entry),'Fresh module URL required: '+entry);
const continuity=JSON.parse(await readFile('LOADING_CONTINUITY_VALIDATION.json'));assert.equal(continuity.protectedDEMHeightAfterPressure,3200);assert.equal(continuity.oldCachedDEMHeightAfterPressure,0);assert.equal(continuity.maxPitch,85);
const aheadReport=JSON.parse(await readFile('PREPARE_AHEAD_VALIDATION.json'));assert.ok(aheadReport.actualNativeTerrainGPUPreparation);assert.equal(aheadReport.revisitFetchDecodeAndGPUUpload,0);assert.ok(JSON.parse(await readFile('TERRAIN_COMPOSITION_VALIDATION.json')).finishedTextureAdoptedWithoutRecomposition);assert.ok(JSON.parse(await readFile('COMPOSITION_SCHEDULER_VALIDATION.json')).perViewAndMinuteBudgets);assert.ok(html.includes('id="prepare-ahead"'));assert.ok(bootstrap.includes('prepare-ahead.js?v=10'));
const reference=JSON.parse(await readFile('COOK_REFERENCE_VALIDATION.json'));assert.ok(reference.noRuntimeImageProcessing&&reference.fineOriginalsPreserved&&reference.DEMAndOutsideRegionPreserved);await stat(path.join(root,'cook-reference-overviews.js'));for(const file of Object.keys(reference.sha256))assert.equal(createHash('sha256').update(await readFile(path.join(root,file.slice(1)))).digest('hex'),reference.sha256[file]);const referencePixels=JSON.parse(await readFile('COOK_REFERENCE_PIXEL_VALIDATION.json'));for(const s of referencePixels.samples)assert.ok(s.matchedRawMAEAfter255<.8&&s.matchedRawMAEAfter255<s.matchedRawMAEBefore255*.05);
const fast=JSON.parse(await readFile('AUDIT_FAST_VALIDATION.json'));assert.ok(fast.allPassed);const cpu=JSON.parse(await readFile('AUDIT_COORDINATE_VALIDATION.json'));assert.ok(cpu.identicalFloat32Matrices);
const identitySource=await readFile(path.join(root,'build-info.js'),'utf8'),identity=JSON.parse(identitySource.match(/RidgeBuildInfo=(.*);/)[1]);for(const [file,hash]of Object.entries(identity.hashes))assert.equal(createHash('sha256').update(await readFile(path.join(root,file))).digest('hex'),hash,'Build identity mismatch: '+file);
const returnReport=JSON.parse(await readFile('CACHE_RETURN_VALIDATION.json'));assert.equal(returnReport.roundtripBefore.recompositions,40);assert.equal(returnReport.roundtripAfter.recompositions,0);assert.ok(returnReport.coarsenedAfter.sameFinishedTexture&&returnReport.coarsenedAfter.partialFineCoverRetained);assert.ok(returnReport.actualContentAndRevisionRefresh&&returnReport.simultaneousContentAndZoomRefreshesAllTiles);
const expandedReport=JSON.parse(await readFile('EXPANDED_VIEW_VALIDATION.json'));assert.ok(expandedReport.cameraUnchanged&&expandedReport.outsideVisibleFrustum);assert.ok(!html.includes('id="prepare-radius"')&&!html.includes('id="view-buffer"'));assert.ok(html.includes('Load beyond screen'));
const sliderReport=JSON.parse(await readFile('CACHE_SLIDER_VALIDATION.json'));assert.equal(sliderReport.rangeMiB[1],3000);assert.ok(sliderReport.detailAndColourUnchanged);assert.ok(html.includes('id="cache-budget"'));
const cacheReport=JSON.parse(await readFile('CACHE_LOADING_VALIDATION.json'));assert.equal(cacheReport.profiles.find(p=>p.memory===4&&!p.saveData).totalMiB,352);assert.equal(cacheReport.completeCachedFrames.after.coverageBuilds,0);
assert.ok(html.includes('data-renderer="luma"')&&html.includes('data-renderer="maplibre"'));assert.ok(!html.includes('type="importmap"'));assert.ok(!html.includes('id="temporal-upscaling" type="checkbox" checked'));assert.ok(!files.some(f=>/martini|terrain-mesh/.test(f)),'Retired CPU meshing assets must not ship');assert.ok(!html.toLowerCase().includes('cesium'));
let bytes=0;for(const file of files)bytes+=(await stat(file)).size;
assert.ok(bytes+3*1024*1024<256*1024*1024,'Leave room for archive headers and server output');
console.log(JSON.stringify({productionClientFiles:files.length,productionClientBytes:bytes,overviewCoordinates:Object.keys(overviews.tiles).length,overviewRangePacks:overviews.packs.length,overviewPersistence:'verified release manifest',retiredViewers:'absent',nativeEngine:'6.14.0',alternativeEngine:'luma.gl '+luma.lumaVersion,engineAssetHashes:'verified',bootstrapImports:'present',cesiumAssets:'absent'}));

const gapReport=JSON.parse(await readFile('GAP_MASK_VALIDATION.json'));assert.equal(gapReport.measuredFinePixelsChanged,0);assert.equal(gapReport.oldFeatherPixelsLeftUncovered,0);assert.equal(Object.keys(gapReport.sampleAlpha).length,14);for(const alpha of Object.values(gapReport.sampleAlpha))assert.equal(alpha,255);
const atlasText=await readFile(path.join(root,'gap-imagery-atlas.js'),'utf8'),atlas=JSON.parse(atlasText.match(/const data=(.*);if\(typeof module/)[1]);for(const row of [...Object.values(atlas.entries),...Object.values(atlas.coarse)]){const url=row.maskURL||row.url;const data=await readFile(path.join(root,url.slice(1)));assert.equal(createHash('sha256').update(data).digest('hex').slice(0,24),path.basename(url,'.png'));}assert.ok(html.includes('id="sky-colour"'));assert.ok(bootstrap.includes('gap-imagery.js?v=3')&&bootstrap.includes('regional-imagery.js?v=11'));
