/* Decode real GLB buffers with the website codec and validate DECODED glTF. */
import fs from 'node:fs';
import crypto from 'node:crypto';
import Validator from './lidar_pipeline/node_modules/gltf-validator/index.js';
import {MeshoptDecoder as D} from '../public/assets/vendor/meshoptimizer/meshopt_decoder.mjs';
const options=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
const file=fs.readFileSync(options.glb),sha=b=>crypto.createHash('sha256').update(b).digest('hex');
if(file.readUInt32LE(0)!==0x46546c67||file.readUInt32LE(4)!==2||file.readUInt32LE(8)!==file.length)throw Error('Invalid GLB header');
const jsonLength=file.readUInt32LE(12),binHeader=20+jsonLength;
if(file.readUInt32LE(16)!==0x4e4f534a||file.readUInt32LE(binHeader+4)!==0x004e4942||binHeader+8+file.readUInt32LE(binHeader)!==file.length)throw Error('Invalid GLB chunks');
const doc=JSON.parse(file.subarray(20,binHeader).toString()),bin=file.subarray(binHeader+8),ext='EXT_meshopt_compression';
if(!doc.extensionsRequired.includes(ext)||!doc.buffers[1].extensions[ext].fallback)throw Error('Compressed-only fallback contract missing');
await D.ready;
const started=performance.now();
const decoded=doc.bufferViews.slice(0,2).map(view=>{
 const e=view.extensions[ext];if(e.buffer!==0||e.byteOffset+e.byteLength>bin.length)throw Error('Compressed view out of bounds');
 const output=new Uint8Array(e.count*e.byteStride);
 D.decodeGltfBuffer(output,e.count,e.byteStride,bin.subarray(e.byteOffset,e.byteOffset+e.byteLength),e.mode,e.filter||'NONE');
 return Buffer.from(output);
});
const decodeMs=performance.now()-started;
if(sha(decoded[0])!==options.attributes_sha256)throw Error('Decoded complete attributes changed');
const indices=new Uint32Array(decoded[1].buffer,decoded[1].byteOffset,decoded[1].length/4),canonical=new Uint32Array(indices.length);
for(let i=0;i<indices.length;i+=3){
 let first=0;if(indices[i+1]<indices[i+first])first=1;if(indices[i+2]<indices[i+first])first=2;
 for(let k=0;k<3;k++)canonical[i+k]=indices[i+(first+k)%3];
}
if(sha(new Uint8Array(canonical.buffer))!==options.cyclic_index_sha256)throw Error('Decoded triangle corners/winding changed');
const imageView=doc.bufferViews[2],image=bin.subarray(imageView.byteOffset,imageView.byteOffset+imageView.byteLength);
if(sha(image)!==options.image_sha256)throw Error('Embedded source imagery changed');
const compressed=await Validator.validateBytes(new Uint8Array(file),{uri:'terrain.glb',maxIssues:100});
if(compressed.issues.numErrors)throw Error(JSON.stringify(compressed.issues));
// The standard validator does not understand meshopt. Validate its decoded
// core representation too, so attribute/index/normal bounds are really checked.
const raw=structuredClone(doc);
for(const key of ['extensionsUsed','extensionsRequired']){
 raw[key]=raw[key].filter(name=>name!==ext);if(!raw[key].length)delete raw[key];
}
const rawBin=Buffer.concat([...decoded,image]);raw.buffers=[{byteLength:rawBin.length}];
raw.bufferViews=raw.bufferViews.map((v,i)=>{
 const q={...v,buffer:0};delete q.extensions;
 q.byteOffset=i===0?0:i===1?decoded[0].length:decoded[0].length+decoded[1].length;
 return q;
});
const padded=(b,pad)=>Buffer.concat([b,Buffer.alloc((-b.length)&3,pad)]);
const js=padded(Buffer.from(JSON.stringify(raw)),32),binary=padded(rawBin,0);
const header=Buffer.alloc(20);header.writeUInt32LE(0x46546c67);header.writeUInt32LE(2,4);header.writeUInt32LE(28+js.length+binary.length,8);header.writeUInt32LE(js.length,12);header.writeUInt32LE(0x4e4f534a,16);
const bh=Buffer.alloc(8);bh.writeUInt32LE(binary.length);bh.writeUInt32LE(0x004e4942,4);
const uncompressed=await Validator.validateBytes(new Uint8Array(Buffer.concat([header,js,bh,binary])),{uri:'decoded.glb',maxIssues:100});
if(uncompressed.issues.numErrors||uncompressed.issues.numWarnings)throw Error(JSON.stringify(uncompressed.issues));
console.log(JSON.stringify({verified:true,complete_attributes_bits_identical:true,triangle_geometry_winding_identical:true,
 image_bytes_identical:true,browser_decoder_node_ms:decodeMs,khronos_compressed_issues:compressed.issues,
 khronos_decoded_issues:uncompressed.issues,validator_version:Validator.version(),
 limitation:'Node/WASM decode and glTF validation are not rendered/browser/GPU benchmarks'}));
