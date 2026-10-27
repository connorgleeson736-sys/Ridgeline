/* Worker-only decode reuse by exact encoded-content hash. Drawing and pixel operations stay unchanged. */
(function(root){'use strict';
function create({decode=root.createImageBitmap.bind(root),digest=bytes=>root.crypto.subtle.digest('SHA-256',bytes),maxBytes=2*1048576}={}){
 const records=new Map();let bytes=0,decodes=0,hits=0,shared=0,closed=false;
 function dispose(row){if(!row.retained&&row.refs===0&&!row.disposed&&row.bitmap){row.disposed=true;row.close();}}
 function trim(){for(const [key,row]of records){if(bytes<=maxBytes&&records.size<=64)break;if(!row.bitmap)continue;records.delete(key);row.retained=false;bytes-=row.bytes;dispose(row);}}
 async function bitmap(blob,...args){
  if(closed||args.length||!(blob instanceof Blob))return decode(blob,...args);
  let key;try{key=Array.from(new Uint8Array(await digest(await blob.arrayBuffer())),n=>n.toString(16).padStart(2,'0')).join('');}catch{return decode(blob);}
  let row=records.get(key);
  if(row){records.delete(key);records.set(key,row);if(row.bitmap)hits++;else shared++;}
  else{
   row={refs:0,bytes:0,retained:false,bitmap:null,disposed:false};records.set(key,row);
   row.promise=(async()=>{decodes++;const b=await decode(blob);row.bitmap=b;row.bytes=b.width*b.height*4;row.close=b.close.bind(b);
    try{b.close=()=>{row.refs=Math.max(0,row.refs-1);dispose(row);};}catch{if(records.get(key)===row)records.delete(key);row.close();return null;}
    if(!closed&&row.bytes<=maxBytes&&records.get(key)===row){row.retained=true;bytes+=row.bytes;trim();}
    else if(records.get(key)===row)records.delete(key);
    return b;
   })().catch(error=>{if(records.get(key)===row)records.delete(key);throw error;});
  }
  row.refs++;
  try{return (await row.promise)||decode(blob);}catch(error){row.refs--;throw error;}
 }
 function clear(){for(const row of records.values()){if(row.retained)bytes-=row.bytes;row.retained=false;dispose(row);}records.clear();}
 return{bitmap,clear,destroy(){closed=true;clear();},stats:()=>({decodedBytes:bytes,decodes,hits,shared,entries:records.size})};
}
function install(){if(!root.createImageBitmap||!root.crypto?.subtle)return;const cache=create();root.createImageBitmap=cache.bitmap;root.RidgeDecodedImages=cache;root.addEventListener?.('message',e=>{if(e.data?.__ridgeTrimImages){e.stopImmediatePropagation();cache.clear();}},{capture:true});return cache;}
const api={create,install};if(typeof module!=='undefined')module.exports=api;else root.RidgeBitmapCache=api;
})(globalThis);
