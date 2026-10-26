/* Decode the actual browser codec, then independently compare content hashes. */
import fs from 'node:fs';
import crypto from 'node:crypto';
import {MeshoptDecoder as D} from '../public/assets/vendor/meshoptimizer/meshopt_decoder.mjs';
const options=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
const description=JSON.parse(fs.readFileSync(options.description,'utf8'));
const source=fs.readFileSync(options.buffer);
const sha=data=>crypto.createHash('sha256').update(data).digest('hex');
await D.ready;
const started=performance.now();
const decoded=description.views.map(view=>{
 const result=new Uint8Array(view.count*view.byteStride);
 D.decodeGltfBuffer(result,view.count,view.byteStride,
   source.subarray(view.byteOffset,view.byteOffset+view.byteLength),view.mode,'NONE');
 return result;
});
const milliseconds=performance.now()-started;
if(sha(decoded[0])!==description.position_sha256)throw Error('Position bytes changed after browser-codec decoding');
// Triangle compression may rotate corners cyclically but must preserve winding.
const indices=new Uint32Array(decoded[1].buffer),canonical=new Uint32Array(indices.length);
for(let i=0;i<indices.length;i+=3){
 let first=0;if(indices[i+1]<indices[i+first])first=1;if(indices[i+2]<indices[i+first])first=2;
 for(let k=0;k<3;k++)canonical[i+k]=indices[i+(first+k)%3];
}
if(sha(new Uint8Array(canonical.buffer))!==description.cyclic_index_sha256)throw Error('Triangle geometry/winding changed after browser-codec decoding');
console.log(JSON.stringify({verified:true,position_bytes_identical:true,triangle_corners_and_winding_identical:true,
 milliseconds,decoded_bytes:decoded.reduce((sum,v)=>sum+v.length,0)}));
