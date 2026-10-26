import fs from 'node:fs';import vm from 'node:vm';
const env={};for(const f of ['gap-imagery-atlas.js','south-overviews.js'])vm.runInNewContext(fs.readFileSync('public/'+f,'utf8'),env);
const south={};for(const key of Object.keys(env.RidgeSouthOverviews.tiles)){const[z,x,y]=key.split('/').map(Number),box=south[z]??=[x,y,x,y];box[0]=Math.min(box[0],x);box[1]=Math.min(box[1],y);box[2]=Math.max(box[2],x);box[3]=Math.max(box[3],y);}
const data={gapZoom:env.RidgeGapAtlas.z,gapFine:Object.keys(env.RidgeGapAtlas.entries).sort(),gapCoarse:Object.keys(env.RidgeGapAtlas.coarse).sort(),south};
const text='/* Generated conservative catalog admission index; exact source masks stay authoritative. */\nglobalThis.RidgeImageryCatalogIndex='+JSON.stringify(data)+';\n';
if(process.argv.includes('--check')){if(fs.readFileSync('public/imagery-catalog-index.js','utf8')!==text)throw Error('Stale imagery catalog index');}else fs.writeFileSync('public/imagery-catalog-index.js',text);
