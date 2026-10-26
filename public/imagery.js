/* Colour grading stays in the existing raster shader; no replacement images. */
(function(root){
'use strict';
function paint(layer,{natural=true,reducedMotion=false}={}){
 // Use one consistent grade at every camera zoom. Cached terrain drapes
 // cannot retain a different, more saturated overview grade after an orbit.
 return{'raster-fade-duration':reducedMotion?0:420,'raster-resampling':'linear',
  'raster-saturation':natural?(layer==='linz'?-.25:-.12):0,
  'raster-contrast':natural?(layer==='linz'?.26:.015):0,
  'raster-brightness-min':natural&&layer==='linz'?.058:0,
  'raster-brightness-max':natural&&layer!=='linz'?.99:1};
}
function apply(map,natural){for(const id of ['linz','satellite'])if(map.getLayer(id)){
 const p=paint(id,{natural});for(const key of ['raster-saturation','raster-contrast','raster-brightness-min','raster-brightness-max'])map.setPaintProperty(id,key,p[key]);
 if(id==='linz'){
  const source=map.getSource(id),tiles=source?.tiles;
  if(Array.isArray(tiles)&&source.setTiles){const next=tiles.map(url=>{if(!url.startsWith('ridge://linz/'))return url;const u=new URL(url);natural?u.searchParams.set('ridge-grade','snow-v3'):u.searchParams.delete('ridge-grade');return u.toString().replaceAll('%7B','{').replaceAll('%7D','}');});if(next.some((url,i)=>url!==tiles[i]))source.setTiles(next);}
 }
}}
// Counter-transform only the small Esri gap images before they enter the LINZ
// shader. The final displayed pixels retain the Satellite reference grade.
// LINZ photos themselves use shader uniforms only: no per-tile image processing.
function compensateEsri(pixels){
 // Invert the current LINZ affine raster grade against the Esri reference.
 // Derive coefficients from paint() so future small adjustments stay aligned.
 const linz=paint('linz'),esri=paint('satellite');
 const slope=p=>(p['raster-brightness-max']-p['raster-brightness-min'])/(1-p['raster-contrast']);
 const bias=p=>p['raster-brightness-min']+(p['raster-brightness-max']-p['raster-brightness-min'])*(1-1/(1-p['raster-contrast']))/2;
 const scale=slope(esri)/slope(linz),ls=-linz['raster-saturation'],es=-esri['raster-saturation'],
  gain=(1-es)*scale/(1-ls),neutral=(es-ls)*scale/(1-ls),lift=(bias(esri)-bias(linz))/slope(linz)*255;
 for(let i=0;i<pixels.length;i+=4){const offset=(pixels[i]+pixels[i+1]+pixels[i+2])/3*neutral+lift;
  for(let k=0;k<3;k++)pixels[i+k]=Math.round(Math.max(0,Math.min(255,pixels[i+k]*gain+offset)));
 }return pixels;
}

function shading(){return{'hillshade-method':'igor','hillshade-illumination-anchor':'map','hillshade-illumination-direction':315,
 'hillshade-illumination-altitude':45,'hillshade-exaggeration':.16,'hillshade-shadow-color':'#34404b',
 'hillshade-highlight-color':'#ffffff','hillshade-accent-color':'#606972'};}
const api={paint,apply,shading,compensateEsri};if(typeof module!=='undefined')module.exports=api;else root.RidgeImagery=api;
})(globalThis);
