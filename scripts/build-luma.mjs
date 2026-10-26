import {build} from 'esbuild';
import {readFile,writeFile,copyFile,mkdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const directory='public/assets/luma';await mkdir(directory,{recursive:true});
await build({stdin:{contents:"export {luma,Buffer,Texture} from '@luma.gl/core';export {webgpuAdapter} from '@luma.gl/webgpu';export {Model} from '@luma.gl/engine';export {Matrix4} from '@math.gl/core';",resolveDir:process.cwd()},bundle:true,minify:true,format:'esm',platform:'browser',target:'es2022',outfile:directory+'/luma.mjs',legalComments:'eof',metafile:true});
const files={};files['luma.mjs']=createHash('sha256').update(await readFile(directory+'/luma.mjs')).digest('hex');
for(const [name,module]of [['luma-license.txt','@luma.gl/core'],['math-license.txt','@math.gl/core']]){await copyFile('node_modules/'+module+'/LICENSE',directory+'/'+name);files[name]=createHash('sha256').update(await readFile(directory+'/'+name)).digest('hex');}
await writeFile(directory+'/provenance.json',JSON.stringify({lumaVersion:'9.4.2',mathVersion:'4.1.0',source:'Pinned npm packages in package-lock.json; rebuild with node scripts/build-luma.mjs',files},null,2)+'\n');console.log('Built pinned luma.gl WebGPU runtime');
