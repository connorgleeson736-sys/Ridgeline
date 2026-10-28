// Persist the latest view off the gesture's synchronous completion path.
export function createViewStorage({write,requestIdle=globalThis.requestIdleCallback?.bind(globalThis),cancelIdle=globalThis.cancelIdleCallback?.bind(globalThis),setTimer=setTimeout,clearTimer=clearTimeout}){
 let pending=null,handle=null,idle=false,last;
 function cancel(){if(handle!==null){if(idle)cancelIdle?.(handle);else clearTimer(handle);handle=null;}}
 function flush(){cancel();if(!pending)return;const value=pending;pending=null;try{const stamp=JSON.stringify(value);if(stamp!==last){write(stamp);last=stamp;}}catch{}}
 function schedule(value){pending=value;cancel();idle=!!(requestIdle&&cancelIdle);handle=idle?requestIdle(flush,{timeout:1000}):setTimer(flush,250);}
 return{schedule,flush};
}
