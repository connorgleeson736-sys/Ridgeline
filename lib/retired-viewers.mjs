const retired=new Set(['cook-model','cook-model-notes','cook-comparison','lidar-summit','terrain-lab','mesh-lab']);
export function retiredViewerResponse(url){
 const name=url.pathname.slice(1).replace(/\.html$/,'').replace(/\/$/,'');
 return retired.has(name)?Response.redirect(new URL('/',url),308):null;
}
