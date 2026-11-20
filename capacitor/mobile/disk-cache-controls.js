export function attachDiskCacheControls({document,native,settings,isNative=true,onError=()=>{}}){
 const input=document.getElementById('automatic-disk-cache'),output=document.getElementById('automatic-disk-cache-value'),status=document.getElementById('automatic-disk-cache-status');
 if(!input)return;
 let pending=null,saving=false,closed=false;
 const show=()=>{const mb=Number(input.value),text=mb===0?'Off':mb===1000?'1 GB':mb+' MB';output.textContent=text;input.setAttribute('aria-valuetext',text);};
 input.disabled=true;
 if(!isNative){status.textContent='Available in the Android app.';return;}
 settings.then(value=>{if(closed)return;input.value=String(value.diskCacheMB??256);input.disabled=false;show();status.textContent=value.diskCacheRestartRequired?'Saved · restart the app to apply.':'';}).catch(onError);
 async function save(){
  if(saving)return;saving=true;
  try{while(pending!==null&&!closed){const megabytes=pending;pending=null;
   try{await native.setDiskCache({megabytes});if(pending===null)status.textContent='Saved · restart the app to apply.';}
   catch(error){status.textContent='Could not save. Try adjusting the slider again.';onError(error);}
  }}finally{saving=false;}
 }
 const changed=()=>{show();pending=Math.max(0,Math.min(1000,Math.round(Number(input.value)||0)));void save();};
 input.addEventListener('input',show);input.addEventListener('change',changed);
 show();
 return{destroy(){closed=true;pending=null;input.removeEventListener('input',show);input.removeEventListener('change',changed);}};
}
