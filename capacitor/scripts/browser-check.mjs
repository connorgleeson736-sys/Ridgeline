import {chromium} from 'playwright-core';
import {createServer} from 'node:http';
import {readFile,stat} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {mkdir} from 'node:fs/promises';
import {deflateSync} from 'node:zlib';
import assert from 'node:assert/strict';
const diagnostics=process.env.RIDGELINE_DIAGNOSTICS||'/tmp/ridgeline-capacitor-check';await mkdir(diagnostics,{recursive:true});
function png(){const chunk=(type,data)=>{const head=Buffer.from(type),b=Buffer.concat([head,data]);let crc=0xffffffff;for(const n of b){crc^=n;for(let i=0;i<8;i++)crc=(crc>>>1)^((crc&1)?0xedb88320:0);}const len=Buffer.alloc(4),tail=Buffer.alloc(4);len.writeUInt32BE(data.length);tail.writeUInt32BE((crc^0xffffffff)>>>0);return Buffer.concat([len,b,tail]);};const header=Buffer.alloc(13);header.writeUInt32BE(256,0);header.writeUInt32BE(256,4);header[8]=8;header[9]=2;const data=Buffer.alloc(256*(256*3+1));for(let y=0;y<256;y++)for(let x=0;x<256;x++){let i=y*769+1+x*3;data[i]=1;data[i+1]=173;data[i+2]=176;}return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',header),chunk('IDAT',deflateSync(data)),chunk('IEND',Buffer.alloc(0))]);}
const base=fileURLToPath(new URL('../www/',import.meta.url));
const server=createServer(async(req,res)=>{try{const p=path.resolve(base,'.'+decodeURIComponent(new URL(req.url,'http://localhost').pathname));if(p!==path.resolve(base)&&!p.startsWith(base))throw Error('Forbidden');const file=(await stat(p)).isDirectory()?path.join(p,'index.html'):p;const type={'.js':'text/javascript','.mjs':'text/javascript','.css':'text/css','.html':'text/html','.png':'image/png','.webp':'image/webp','.json':'application/json'}[path.extname(file)]||'application/octet-stream';res.writeHead(200,{'content-type':type});const data=await readFile(file);res.end(path.basename(file)==='app.js'?Buffer.concat([data,Buffer.from(";globalThis.__testMap=map;map.on('error',e=>console.error(e.error?.message||'Map error'));")]):data);}catch{res.writeHead(404);res.end();}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox','--use-angle=swiftshader','--enable-unsafe-swiftshader']});
const errors=[],consoleErrors=[],requests=[];let page;
try{
 const context=await browser.newContext({viewport:{width:412,height:915},deviceScaleFactor:1,isMobile:true,hasTouch:true});

 const dem=png();
 const imagery=await readFile(new URL('../../public/assets/cook-reference-v1/b872335eddfb7fc3c554d59a.webp',import.meta.url));
 await context.route('https://localhost/_ridge/**',async route=>{const url=route.request().url();requests.push(url);if(url.includes('.pbf'))return route.fulfill({status:200,headers:{'access-control-allow-origin':'*'},contentType:'application/x-protobuf',body:Buffer.alloc(0)});return route.fulfill({status:200,headers:{'access-control-allow-origin':'*'},contentType:url.includes('/elevation/')||url.includes('/mapzen/')?'image/png':'image/webp',body:url.includes('/elevation/')||url.includes('/mapzen/')?dem:imagery});});
 page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error')consoleErrors.push(m.text());});
 await page.goto(`http://127.0.0.1:${server.address().port}/`,{waitUntil:'load'});
 console.log('Website loaded');
 await page.waitForFunction(()=>globalThis.RidgeMobile?.status()?.ready,null,{timeout:60000});
 console.log('Renderer ready',JSON.stringify(await page.evaluate(()=>RidgeMobile.status())));await page.waitForTimeout(1000);
 await page.waitForFunction(()=>globalThis.RidgeMobile?.status()?.direct?.directPatches>0,null,{timeout:60000});
 let status=await page.evaluate(()=>RidgeMobile.status());assert.equal(status.terrain,true);assert.equal(status.direct.enabled,true);assert.equal(status.direct.supported,true);
 await page.locator('#layers-toggle').click();await page.locator('#imagery-detail').waitFor({state:'visible'});assert.equal(await page.locator('#loading-controls').isVisible(),false);
 await page.locator('#advanced-tab').click();assert.equal(await page.locator('#phone-mode').isVisible(),true);assert.equal(await page.locator('#imagery-detail').isVisible(),false);await page.locator('#basic-tab').click();await page.locator('#layers-close').click();
 await page.locator('#plan-shortcut').click();await page.mouse.click(160,280);await page.waitForFunction(()=>RidgeMobile.status().points===1,null,{timeout:20000});await page.mouse.click(270,220);await page.locator('#finish').click();
 await page.waitForFunction(()=>globalThis.RidgeMobile.status().points===2,null,{timeout:20000});
 await page.waitForFunction(()=>globalThis.RidgeMobile.status().profile.some(p=>Number.isFinite(p.h)),null,{timeout:60000});
 await page.locator('#route-close').click();
 await page.locator('#layers-toggle').click();await page.locator('#open-offline').click();await page.getByRole('button',{name:'Draw rectangle',exact:true}).click();await page.mouse.click(140,350);await page.mouse.click(270,510);await page.locator('#offline-panel').waitFor({state:'visible'});assert.equal(await page.evaluate(()=>RidgeMobile.status().points),2);assert.ok(Number(await page.locator('#offline-west').inputValue())>165);
 await page.locator('#offline-close').click();await page.locator('#layers-toggle').click();await page.screenshot({path:path.join(diagnostics,'layers-mobile.png')});await page.locator('#layers-close').click();await page.screenshot({path:path.join(diagnostics,'map-mobile.png')});
 assert.deepEqual(errors,[]);assert.equal(requests.some(u=>u.includes('/elevation/')),true);
 console.log(JSON.stringify({renderer:status.direct,terrain:status.terrain,profile:true,routeEditing:true,rectangleSelection:true,layerTabs:true,providerFixtureRequests:requests.length,pageErrors:errors,consoleErrors},null,2));
}catch(error){const status=await Promise.race([page?.evaluate(()=>RidgeMobile.status()),new Promise(r=>setTimeout(()=>r('Unavailable'),2000))]);console.error(JSON.stringify({errors,consoleErrors,requests:requests.length,status,tiles:await Promise.race([page?.evaluate(()=>({linz:__testMap?.isSourceLoaded('linz'),tileCache:RidgeTiles.install(maplibregl).stats(),terrain:__testMap?.isSourceLoaded('terrain')})),new Promise(r=>setTimeout(()=>r('Unavailable'),2000))]),failure:error.message}));throw error;}finally{await browser.close();await new Promise(r=>server.close(r));}
