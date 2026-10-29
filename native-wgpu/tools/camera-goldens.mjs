// Numeric camera fixture from the shipped 0.18 MapLibre engine; build-time only.
import fs from 'node:fs';import path from 'node:path';
import * as E from '../../public/assets/maplibre-gl.mjs';
let tr;E.Style.prototype._setProjectionInternal.call({map:{migrateProjection:t=>tr=t},tileManagers:{},_globalState:{}},'mercator');tr.setMaxPitch(85);
const rows=['# width height mx my zoom bearing pitch elevation dx dy height expected_x expected_y near far ppm'];
for(const [width,height]of [[390,844],[1080,1920],[1200,800]])for(const [lon,lat]of [[170.141,-43.595],[178,-35],[166,-47]])for(const zoom of [5,11.9,16,19])for(const bearing of [-135,-24,0,90])for(const pitch of [0,45,58,80]){
 tr.setPitch(0);tr.resize(width,height);tr.setZoom(zoom);tr.setCenter(new E.LngLat(lon,lat));tr.setBearing(bearing);tr.setPitch(pitch);const elevation=zoom>=11?2500:0;tr.setElevation(elevation);
 const mc=E.MercatorCoordinate.fromLngLat(tr.center),m=tr.modelViewProjectionMatrix,w=tr.worldSize;
 for(const [dx,dy,dh]of [[0,0,0],[width*.21,height*.17,0],[-width*.19,height*.13,137]]){
  const h=elevation+dh,x=mc.x*w+dx,y=mc.y*w-dy;
  const c=Array.from({length:4},(_,r)=>m[r]*x+m[r+4]*y+m[r+8]*h+m[r+12]);if(c[3]<=0)continue;
  rows.push([width,height,mc.x,mc.y,tr.zoom,tr.bearing,tr.pitch,tr.elevation,dx,dy,h,(c[0]/c[3]+1)*width/2,(1-c[1]/c[3])*height/2,tr.nearZ,tr.farZ,tr.pixelsPerMeter].join(' '));
 }
}
const out=path.resolve(import.meta.dirname,'../renderer/tests');fs.mkdirSync(out,{recursive:true});fs.writeFileSync(path.join(out,'camera-goldens.txt'),rows.join('\n')+'\n');console.log(`${rows.length-1} authoritative screen projections`);
