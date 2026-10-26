// Fresh production output and exact source-to-package verification.
import {cp,rm,readdir,readFile} from 'node:fs/promises';
import path from 'node:path';
const root=process.cwd(),output=path.join(root,'dist');
if(process.argv.includes('--clean')){await rm(output,{recursive:true,force:true});process.exit(0);}
const source=path.join(root,'public'),target=path.join(output,'client');
if(!process.argv.includes('--verify'))await cp(source,target,{recursive:true,force:true});
let files=0;
async function verify(folder=''){
 for(const item of await readdir(path.join(source,folder),{withFileTypes:true})){
  const name=path.join(folder,item.name);
  if(item.isDirectory())await verify(name);
  else if(item.isFile()){
   const [a,b]=await Promise.all([readFile(path.join(source,name)),readFile(path.join(target,name))]);
   if(!a.equals(b))throw Error('Stale packaged public file: '+name);files++;
  }
 }
}
await verify();
const html=await readFile(path.join(target,'index.html'),'utf8');
if((html.match(/data-layer="topo"/g)||[]).length!==1)throw Error('Topographic basemap control missing or duplicated in production HTML');
console.log(JSON.stringify({publicFilesMatchedToSource:files,topographicBasemapControl:true}));
