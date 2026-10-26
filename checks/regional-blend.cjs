const assert=require('node:assert/strict'),{composite,vectorAlpha}=require('../public/regional-blend.js');
let hardSampling=0;
class Canvas{
 constructor(w,h){this.width=w;this.height=h;this.pixels=new Float64Array(w*h*4);}
 getContext(){const canvas=this;return{createImageData(w,h){return{data:new Uint8ClampedArray(w*h*4)};},set imageSmoothingEnabled(value){if(value===false)hardSampling++;},getImageData(){return{data:Uint8ClampedArray.from(canvas.pixels)}} ,putImageData(image){canvas.pixels.set(image.data);},globalCompositeOperation:'source-over',drawImage(source,...args){
  let sx0=0,sy0=0,sw=source.width,sh=source.height,dx=0,dy=0,dw=source.width,dh=source.height;if(args.length===8)[sx0,sy0,sw,sh,dx,dy,dw,dh]=args;else [dx=0,dy=0,dw=source.width,dh=source.height]=args;
  for(let y=0;y<canvas.height;y++)for(let x=0;x<canvas.width;x++){
   const sx=Math.floor(sx0+(x+.5-dx)/dw*sw),sy=Math.floor(sy0+(y+.5-dy)/dh*sh),i=(y*canvas.width+x)*4;
   if(sx<0||sy<0||sx>=source.width||sy>=source.height)continue;const j=(sy*source.width+sx)*4,a=source.pixels[j+3]/255;
   if(this.globalCompositeOperation==='destination-in'){canvas.pixels[i+3]*=a;continue;}
   const previous=canvas.pixels[i+3]/255,out=a+previous*(1-a);
   for(let k=0;k<3;k++)canvas.pixels[i+k]=out?(source.pixels[j+k]*a+canvas.pixels[i+k]*previous*(1-a))/out:0;canvas.pixels[i+3]=out*255;
  }
 }};}
 async convertToBlob(options){assert.equal(options.type,'image/png');return new Blob([Uint8Array.from(this.pixels,v=>Math.round(v))]);}
}
(async()=>{
 let closes=0;const a={width:2,height:2,pixels:new Uint8Array([10,20,30,255,10,20,30,255,10,20,30,255,10,20,30,255]),close(){closes++;}},b={width:2,height:2,pixels:new Uint8Array([110,120,130,255,110,120,130,255,110,120,130,255,110,120,130,255]),close(){closes++;}};
 const mask=new Canvas(256,256);for(let y=0;y<256;y++)for(let x=0;x<256;x++){const i=(y*256+x)*4;mask.pixels.set([255,255,255,y<128?(x<128?0:128):(x<128?255:0)],i);}
 const environment={OffscreenCanvas:Canvas,createImageBitmap:async blob=>(new Uint8Array(await blob.arrayBuffer()))[0]===1?a:b};
 const data=await composite({linz:new Uint8Array([1]),esri:new Uint8Array([2]),z:13,x:5,y:6,meta:{z:13,x:5,y:6,width:256,height:256},mask:Promise.resolve(mask)},environment);
 assert.deepEqual([...new Uint8Array(data)],[10,20,30,255,60,70,80,255,110,120,130,255,10,20,30,255]);assert.equal(closes,2,'Close image bitmaps, retain reusable mask');
 await composite({linz:new Uint8Array([1]),esri:new Uint8Array([2]),z:13,x:5,y:6,hardMask:true,meta:{z:13,x:5,y:6,width:256,height:256},mask:Promise.resolve(mask)},environment);assert.equal(hardSampling,1,'Hard source masks must disable mask interpolation');
 const polygon={z:13,x:5,y:6,width:256,height:256,rings:[[[128,0],[256,0],[256,256],[128,256],[128,0]]]};
 const exact=await composite({linz:new Uint8Array([1]),esri:new Uint8Array([2]),z:13,x:5,y:6,hardMask:true,meta:polygon,mask:Promise.resolve(null)},environment);
 assert.deepEqual([...new Uint8Array(exact)],[10,20,30,255,110,120,130,255,10,20,30,255,110,120,130,255],'Official vectors choose pixels directly and preserve original imagery outside');
 const hole={z:13,x:0,y:0,rings:[[[0,0],[256,0],[256,256],[0,256],[0,0]],[[64,64],[192,64],[192,192],[64,192],[64,64]]]};
 const alpha=vectorAlpha(hole,13,0,0,4,4);assert.equal([...alpha].filter((v,i)=>i%4===3&&v===255).length,12,'Even/odd fill must preserve interior aerial islands');
 const close=vectorAlpha({z:13,x:0,y:0,rings:[[[128.1,0],[256,0],[256,256],[128.1,256],[128.1,0]]]},19,32,0,256,256);
 assert.equal(close[5*4+3],0);assert.equal(close[7*4+3],255,'High zoom must use vector precision, not an enlarged native z13 mask');
 const fineBitmap={width:2,height:2,pixels:new Uint8Array([0,0,0,255,0,0,0,0,0,0,0,1,0,0,0,0]),close(){}};
 const fineEnv={OffscreenCanvas:Canvas,createImageBitmap:async blob=>{const v=new Uint8Array(await blob.arrayBuffer());return v[0]===1?a:v[0]===3?fineBitmap:b;}};
 const whole={z:13,x:5,y:6,rings:[[[0,0],[256,0],[256,256],[0,256],[0,0]]]};
 const protectedPixels=await composite({linz:new Uint8Array([1]),esri:new Uint8Array([2]),fine:[{data:new Uint8Array([3]),z:13}],z:13,x:5,y:6,meta:whole,hardMask:true},fineEnv);
 assert.deepEqual([...new Uint8Array(protectedPixels)],[10,20,30,255,110,120,130,255,10,20,30,255,110,120,130,255],'Every nonzero fine alpha must preserve LINZ while zero alpha gets Esri up to the edge');
 const overlay=await composite({linz:new Uint8Array([1]),esri:new Uint8Array([2]),z:13,x:5,y:6,overlay:true},environment);assert.deepEqual([...new Uint8Array(overlay)],[...b.pixels],'Saved overlay path must not load or apply an unrelated mask');
 const quadrants={width:2,height:2,pixels:new Uint8Array([10,0,0,255,20,0,0,255,30,0,0,255,40,0,0,255]),close(){}};
 const cropped=await composite({linz:null,esri:new Uint8Array([2]),z:14,x:11,y:12,esriZ:13},{OffscreenCanvas:Canvas,createImageBitmap:async()=>quadrants});
 assert.deepEqual([...new Uint8Array(cropped)],[20,0,0,255,20,0,0,255,20,0,0,255,20,0,0,255],'Overzoom must crop the correct geographic quadrant');
 const protectedEsri=await composite({linz:null,esri:new Uint8Array([2]),z:13,x:5,y:6,compensate:true},{OffscreenCanvas:Canvas,createImageBitmap:async()=>b,RidgeImagery:require('../public/imagery.js')});
 assert.notDeepEqual([...new Uint8Array(protectedEsri)], [...b.pixels],'Esri alone must be compensated before the stronger LINZ shader');
 let firstClosed=0;const broken={OffscreenCanvas:Canvas,createImageBitmap:async blob=>{if((new Uint8Array(await blob.arrayBuffer()))[0]===2)throw Error('decode');return{...a,close(){firstClosed++;}};}};
 await assert.rejects(composite({linz:new Uint8Array([1]),esri:new Uint8Array([2]),mask:Promise.resolve(mask)},broken));assert.equal(firstClosed,1,'Failed decode must not leak another decoded bitmap');
 console.log('PASS: actual compositor preserves original pixels outside mask, uses Esri only inside, feathers inward, correct north/south orientation, lossless PNG requested and bitmap cleanup on failure');
})().catch(e=>{console.error(e);process.exitCode=1});
