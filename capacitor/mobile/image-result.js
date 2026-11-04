/* Owned visible composite output. Persistent reuse stays with validated input
   responses; bitmaps are never stored in a byte cache or shared by subscribers. */
(function(root){'use strict';
function fallback(headers){
 const address=headers?.get('X-Ridge-Fallback');if(!address||!/^\d+\/\d+\/\d+$/.test(address))return null;
 const [z,x,y]=address.split('/').map(Number);if(z>22||x>=2**z||y>=2**z)return null;
 const value=headers.get('Retry-After'),seconds=value===null?0:Number(value),delay=Number.isFinite(seconds)?seconds*1000:Date.parse(value)-Date.now();
 return{z,x,y,retry:headers.get('X-Ridge-Fallback-Retry')==='1',retryAfterMs:Math.max(0,Math.min(2147483647,delay||0))};
}
function headers(responses,ttl){
 const now=Date.now();let until=now+ttl*1000;
 for(const r of responses){const value=Number(r?.headers?.get('X-Ridge-Expires-At'));if(value>0&&Number.isFinite(value))until=Math.min(until,value);}
 ttl=Math.max(0,Math.floor((until-now)/1000));
 const h=new Headers({'Content-Type':'image/png','Cache-Control':ttl?'private, max-age='+ttl:'no-store','X-Ridge-Expires-At':String(until)});
 const rows=responses.map(r=>({r,f:fallback(r?.headers)})).filter(v=>v.f);
 if(rows.length){const chosen=rows.reduce((a,b)=>b.f.z<a.f.z?b:a);h.set('Cache-Control','no-store');h.set('X-Ridge-Expires-At','0');h.set('X-Ridge-Fallback',chosen.f.z+'/'+chosen.f.x+'/'+chosen.f.y);h.set('X-Ridge-Fallback-Retry',rows.some(v=>v.f.retry)?'1':'0');const delay=Math.max(...rows.map(v=>v.f.retryAfterMs));if(delay)h.set('Retry-After',String(Math.ceil(delay/1000)));}
 return h;
}
function result(data,h){return typeof root.ImageBitmap==='function'&&data instanceof root.ImageBitmap?{ridgeBitmap:data,headers:h,ok:true,status:200}:new Response(data,{headers:h});}
root.RidgeImageResult={fallback,headers,result};
})(globalThis);
