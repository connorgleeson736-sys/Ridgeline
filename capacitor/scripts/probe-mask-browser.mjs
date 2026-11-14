// Real Chromium alpha/composition checks and a shared 5 Mbps HTTP fixture.
// Provider fixtures come from probe-survey-mask.py. No native bridge/phone timing.
import {chromium} from 'playwright-core';
import {readFile,writeFile} from 'node:fs/promises';
import {createServer} from 'node:http';
import path from 'node:path';
import assert from 'node:assert/strict';
const directory=path.resolve(process.argv[2]),summary=JSON.parse(await readFile(path.join(directory,'survey-mask-results.json'),'utf8'));
const files=new Map();
for(const row of summary.cases)for(const format of ['png','webp']){
 const name=row.tile.join('-')+'-'+row.survey+'.'+format;
 files.set(name,await readFile(path.join(directory,name)));
}
const blend=await readFile(new URL('../../public/regional-blend.js',import.meta.url));
let queue=[],last=performance.now(),bytesSent=0;
const rate=625000; // Exactly 5,000,000 bits/s shared across all fixture requests.
const pacing=setInterval(()=>{
 const now=performance.now();let budget=Math.floor(rate*(now-last)/1000);last=now;
 while(budget>0&&queue.length){const job=queue.shift();if(job.res.destroyed)continue;
  const count=Math.min(budget,4096,job.data.length-job.offset);job.res.write(job.data.subarray(job.offset,job.offset+count));job.offset+=count;budget-=count;bytesSent+=count;
  if(job.offset===job.data.length)job.res.end();else queue.push(job);
 }
},5);
const server=createServer((req,res)=>{
 const url=new URL(req.url,'http://local'),name=url.pathname.split('/').pop();
 if(url.pathname==='/'){res.setHeader('content-type','text/html');res.end('<!doctype html><html></html>');return;}
 if(name==='blend.js'){res.setHeader('content-type','text/javascript');res.end(blend);return;}
 const data=files.get(name);if(!data){res.writeHead(404);res.end();return;}
 res.writeHead(200,{'Content-Type':name.endsWith('.png')?'image/png':'image/webp','Cache-Control':'no-store'});
 if(url.pathname.startsWith('/paced/'))queue.push({res,data,offset:0});else res.end(data);
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
try{
 const page=await browser.newPage(),errors=[];page.on('pageerror',error=>errors.push(error.message));
 await page.goto('http://127.0.0.1:'+server.address().port);await page.addScriptTag({url:'/blend.js'});
 const result=await page.evaluate(async cases=>{
  const rgba=async data=>{const b=data instanceof ImageBitmap?data:await createImageBitmap(new Blob([data]));const c=new OffscreenCanvas(256,256),ctx=c.getContext('2d');ctx.drawImage(b,0,0);b.close();return ctx.getImageData(0,0,256,256).data;};
  const image=async colour=>{const c=new OffscreenCanvas(256,256),ctx=c.getContext('2d');ctx.fillStyle=colour;ctx.fillRect(0,0,256,256);return(await c.convertToBlob()).arrayBuffer();};
  const linz=await image('#f7d281'),esri=await image('#133b67');
  let alphaChecks=0,compositeChecks=0;
  for(const row of cases){const name=row.tile.join('-')+'-'+row.survey;
   const [a,b]=await Promise.all(['png','webp'].map(f=>fetch('/raw/'+name+'.'+f).then(r=>r.arrayBuffer())));
   const [pa,pb]=await Promise.all([rgba(a),rgba(b)]);
   for(let i=3;i<pa.length;i+=4)if(pa[i]!==pb[i])throw Error('Browser alpha differs');alphaChecks++;
   for(const delta of [0,1,2,4])for(const offset of [...new Set([0,2**delta-1])]){
    const z=17,x=126960+offset,y=83584+offset;
    const meta={z,x,y,rings:[[[0,0],[256,0],[256,256],[0,256],[0,0]]]};
    const compose=data=>RidgeRegionalBlend.composite({linz,esri,fine:[{z:z-delta,data}],z,x,y,meta,bitmap:true});
    const [one,two]=await Promise.all([compose(a).then(rgba),compose(b).then(rgba)]);
    if(!one.every((v,i)=>v===two[i]))throw Error('Composite pixels differ');compositeChecks++;
   }
  }
  const replay=[];
  for(let trial=0;trial<3;trial++)for(const format of trial%2?['webp','png']:['png','webp']){
   const start=performance.now();let bytes=0;
   await Promise.all(cases.map(async row=>{const name=row.tile.join('-')+'-'+row.survey;
    const data=await fetch('/paced/'+name+'.'+format+'?trial='+trial).then(r=>r.arrayBuffer());bytes+=data.byteLength;
    const [z,x,y]=row.tile,meta={z,x,y,rings:[[[0,0],[256,0],[256,256],[0,256],[0,0]]]};
    const output=await RidgeRegionalBlend.composite({linz,esri,fine:[{z,data}],z,x,y,meta,bitmap:true});output.close();
   }));
   replay.push({trial,format,bytes,imageReadyMs:performance.now()-start});
  }
  return{alphaChecks,compositeChecks,replay};
 },summary.cases);
 assert.deepEqual(errors,[]);
 const median=format=>result.replay.filter(r=>r.format===format).map(r=>r.imageReadyMs).sort((a,b)=>a-b)[1];
 const output={fixture:'Shared paced HTTP at 5 Mbps, original photo/Esri already resident, 8 survey inputs, Chromium Canvas; not handset/end-to-end timing',
  ...result,pngMedianMs:median('png'),webpMedianMs:median('webp'),medianSavingMs:median('png')-median('webp'),totalPacedBytes:bytesSent};
 await writeFile(path.join(directory,'browser-mask-results.json'),JSON.stringify(output,null,2)+'\n');console.log(JSON.stringify(output,null,2));
}finally{await browser.close();clearInterval(pacing);for(const job of queue)job.res.destroy();await new Promise(r=>server.close(r));}
