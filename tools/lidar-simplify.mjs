/* meshoptimizer 1.3.0 official WASM; independent geometry checks remain mandatory. */
import fs from 'node:fs';
import { MeshoptSimplifier as S } from './meshopt_simplifier.mjs';
const options=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
await S.ready;
const vraw=fs.readFileSync(options.vertices),iraw=fs.readFileSync(options.indices);
const vertices=new Float32Array(vraw.buffer,vraw.byteOffset,vraw.byteLength/4).slice();
const indices=new Uint32Array(iraw.buffer,iraw.byteOffset,iraw.byteLength/4).slice();
const results=[];
const locks=options.locks?new Uint8Array(fs.readFileSync(options.locks)):null;
for(const error of options.errors){
 const start=performance.now();
 const scale=S.getScale(vertices,3);
 let result,reported;
 if(options.mode==='sloppy'){
  [result,reported]=S.simplifySloppy(indices,vertices,3,locks,3,error/scale);reported*=scale;
 }else if(options.mode==='permissive'){
  [result,reported]=S.simplifyWithAttributes(indices,vertices,3,new Float32Array(0),0,[],locks,3,error,['ErrorAbsolute','Regularize','Permissive']);
 }else{
  [result,reported]=locks?
   S.simplifyWithAttributes(indices,vertices,3,new Float32Array(0),0,[],locks,3,error,['LockBorder','ErrorAbsolute','Regularize']):
   S.simplify(indices,vertices,3,3,error,['LockBorder','ErrorAbsolute','Regularize']);
 }
 const path=options.outputPrefix+'-'+error+'.u32';fs.writeFileSync(path,new Uint8Array(result.buffer,result.byteOffset,result.byteLength));
 results.push({path,error,reported,triangles:result.length/3,milliseconds:performance.now()-start});
}
console.log(JSON.stringify({version:'1.3.0',results}));
