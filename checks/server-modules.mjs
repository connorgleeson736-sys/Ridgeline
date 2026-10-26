import {readdir,readFile,access} from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import {init,parse} from 'es-module-lexer';
await init;
let files=0,imports=0;const missing=[];
async function walk(dir){
 for(const entry of await readdir(dir,{withFileTypes:true})){
  const file=path.join(dir,entry.name);
  if(entry.isDirectory())await walk(file);
  else if(/\.m?js$/.test(file)){
   files++;const[entries]=parse(await readFile(file,'utf8'));
   for(const entry of entries){const ref=entry.n;if(!ref?.startsWith('.'))continue;imports++;const target=path.resolve(path.dirname(file),ref.split(/[?#]/)[0]);try{await access(target);}catch{missing.push({from:file,ref});}}
  }
 }
}
await walk('dist/server');
assert.deepEqual(missing,[],'Every Worker module import must exist in the deployment tree');
console.log(JSON.stringify({serverModuleFiles:files,relativeImportsVerified:imports,missingModules:0}));
