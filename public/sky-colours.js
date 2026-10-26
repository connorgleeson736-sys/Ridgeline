/* A saved display preference; no imagery or terrain data changes. */
(function(root){'use strict';
const presets=[['Mist','#bdcbd0'],['Alpine blue','#adc9db'],['Clear blue','#88c6fc'],['Warm haze','#e8d9c4'],['Slate','#697b89'],['Night','#17242f']];let colour=presets[0][1];
const valid=v=>typeof v==='string'&&/^#[0-9a-f]{6}$/i.test(v);
function paint(value){return{'sky-color':value,'horizon-color':value,'fog-color':value,'fog-ground-blend':1,'horizon-fog-blend':0,'sky-horizon-blend':0,'atmosphere-blend':0};}
function apply(map,renderer,value=colour){if(!map||!valid(value))return;if(renderer!=='maplibre')map.setSkyColour?.(value);else{map.setSky?.(paint(value));if(map.getLayer('background'))map.setPaintProperty('background','background-color',value);}}
function createUI({document,storage,onChange}){
 try{const value=storage?.getItem('ridgeline-sky-v1');if(valid(value))colour=value.toLowerCase();}catch{}
 const picker=document.getElementById('sky-colour'),select=document.getElementById('sky-preset');
 function sync(){picker.value=colour;select.value=presets.some(p=>p[1]===colour)?colour:'custom';}
 function set(value){if(!valid(value))return;colour=value.toLowerCase();sync();try{storage?.setItem('ridgeline-sky-v1',colour);}catch{}onChange(colour);}
 const input=()=>set(picker.value),change=()=>{if(select.value==='custom'){picker.focus();return;}set(select.value);};picker.addEventListener('input',input);select.addEventListener('change',change);sync();
 return{value:()=>colour,apply:()=>onChange(colour),destroy(){picker.removeEventListener('input',input);select.removeEventListener('change',change);}};
}
root.RidgeSky={presets,valid,paint,apply,createUI,current:()=>colour};
})(globalThis);
