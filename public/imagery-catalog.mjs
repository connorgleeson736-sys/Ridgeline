/* Load only authoritative routing catalogs needed by this tile. The small
   generated index is conservative: false positives are safe; false negatives
   are rejected by the source-index validation check. */
let gap,south,fine,coarse;
const loadGap=()=>gap??=Promise.all([import('./west-coast-footprint.js?v=1'),import('./gap-imagery-atlas.js?v=4')]).catch(error=>{gap=null;throw error;});
const loadSouth=()=>south??=import('./south-overviews.js?v=1').catch(error=>{south=null;throw error;});
globalThis.RidgeImageryCatalog={ensure(tile){
 const index=globalThis.RidgeImageryCatalogIndex;if(!tile||!index)return Promise.all([loadGap(),loadSouth()]);
 fine??=new Set(index.gapFine);coarse??=new Set(index.gapCoarse);
 const{z,x,y}=tile,scale=2**(z-index.gapZoom),needsGap=z<index.gapZoom?coarse.has(z+'/'+x+'/'+y):fine.has(Math.floor(x/scale)+'/'+Math.floor(y/scale)),box=index.south[z];
 const needsSouth=box&&x>=box[0]&&y>=box[1]&&x<=box[2]&&y<=box[3];
 if(needsGap&&needsSouth)return Promise.all([loadGap(),loadSouth()]);
 if(needsGap)return loadGap();if(needsSouth)return loadSouth();return Promise.resolve();
}};
