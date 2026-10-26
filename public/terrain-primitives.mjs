/* Small terrain-specific camera/math layer. No Three.js dependency. */
import {Matrix4 as MathMatrix} from './assets/luma/luma.mjs';
export class Vector2{constructor(x=0,y=0){this.x=x;this.y=y;}set(x,y){this.x=x;this.y=y;return this;}copy(v){return this.set(v.x,v.y);}clone(){return new Vector2(this.x,this.y);}}
export class Vector4 extends Vector2{constructor(x=0,y=0,z=0,w=0){super(x,y);this.z=z;this.w=w;}set(x,y,z,w){super.set(x,y);this.z=z;this.w=w;return this;}copy(v){return this.set(v.x,v.y,v.z,v.w);}clone(){return new Vector4(this.x,this.y,this.z,this.w);}}
export class Vector3{constructor(x=0,y=0,z=0){this.set(x,y,z);}set(x,y,z){this.x=x;this.y=y;this.z=z;return this;}setScalar(s){return this.set(s,s,s);}copy(v){return this.set(v.x,v.y,v.z);}clone(){return new Vector3(this.x,this.y,this.z);}add(v){this.x+=v.x;this.y+=v.y;this.z+=v.z;return this;}sub(v){this.x-=v.x;this.y-=v.y;this.z-=v.z;return this;}multiplyScalar(s){this.x*=s;this.y*=s;this.z*=s;return this;}length(){return Math.hypot(this.x,this.y,this.z);}normalize(){return this.multiplyScalar(1/(this.length()||1));}distanceTo(v){return Math.hypot(this.x-v.x,this.y-v.y,this.z-v.z);}dot(v){return this.x*v.x+this.y*v.y+this.z*v.z;}project(camera){const p=camera.viewProjection().transform([this.x,this.y,this.z,1]);return this.set(p[0]/p[3],p[1]/p[3],p[2]/p[3]);}}
export class Matrix4 extends MathMatrix{multiplyMatrices(a,b){this.copy(a);return this.multiplyRight(b);}}
export class PerspectiveCamera{
 constructor(fov=45,aspect=1,near=.5,far=20000000){Object.assign(this,{fov,aspect,near,far});this.position=new Vector3();this.target=new Vector3(0,0,-1);this.up=new Vector3(0,1,0);this.projectionMatrix=new Matrix4();this.matrixWorldInverse=new Matrix4();this.updateProjectionMatrix();}
 lookAt(target){this.target.copy(target);this.updateMatrixWorld();}
 updateMatrixWorld(){const d=this.position.clone().sub(this.target),up=Math.hypot(d.x,d.z)<Math.abs(d.y)*1e-8?[0,0,-1]:[this.up.x,this.up.y,this.up.z];this.matrixWorldInverse.lookAt({eye:[this.position.x,this.position.y,this.position.z],center:[this.target.x,this.target.y,this.target.z],up});}
 updateProjectionMatrix(){const f=1/Math.tan(this.fov*Math.PI/360),n=this.near,a=this.far;this.projectionMatrix.copy([f/this.aspect,0,0,0,0,f,0,0,0,0,a/(n-a),-1,0,0,n*a/(n-a),0]);}
 viewProjection(){return new Matrix4().multiplyMatrices(this.projectionMatrix,this.matrixWorldInverse);}
 getWorldDirection(out){return out.copy(this.target).sub(this.position).normalize();}
 clone(){const c=new PerspectiveCamera(this.fov,this.aspect,this.near,this.far);c.position.copy(this.position);c.up.copy(this.up);c.lookAt(this.target);return c;}
}
export class Box3{constructor(){this.min=new Vector3();this.max=new Vector3();}distanceToPoint(v){return Math.hypot(Math.max(this.min.x-v.x,0,v.x-this.max.x),Math.max(this.min.y-v.y,0,v.y-this.max.y),Math.max(this.min.z-v.z,0,v.z-this.max.z));}}
export class Frustum{
 setFromProjectionMatrix(m){const row=i=>[m[i],m[i+4],m[i+8],m[i+12]],r=[0,1,2,3].map(row),combine=(a,b,k)=>a.map((v,i)=>v+k*b[i]);this.planes=[combine(r[3],r[0],1),combine(r[3],r[0],-1),combine(r[3],r[1],1),combine(r[3],r[1],-1),r[2],combine(r[3],r[2],-1)];return this;}
 intersectsBox(b){return this.planes.every(p=>p[0]*(p[0]>=0?b.max.x:b.min.x)+p[1]*(p[1]>=0?b.max.y:b.min.y)+p[2]*(p[2]>=0?b.max.z:b.min.z)+p[3]>=0);}
}
export class Raycaster{setFromCamera(p,camera){const q=camera.viewProjection().invert().transform([p.x,p.y,1,1]),origin=camera.position.clone();this.ray={origin,direction:new Vector3(q[0]/q[3],q[1]/q[3],q[2]/q[3]).sub(origin).normalize()};}}
export class Group{constructor(){this.children=[];this.position=new Vector3();this.scale=new Vector3(1,1,1);this.userData={};this.visible=true;}add(item){this.children.push(item);}remove(item){this.children=this.children.filter(c=>c!==item);}}
export class Scene extends Group{}
export class Mesh extends Group{constructor(geometry,material){super();this.geometry=geometry;this.material=material;}}
export class Color{constructor(value){this.set(value);}set(value){const h=value.replace('#','');this.value=[parseInt(h.slice(0,2),16)/255,parseInt(h.slice(2,4),16)/255,parseInt(h.slice(4,6),16)/255,1];return this;}}
/* Pointer capture makes drags reliable outside the canvas. Touch: one finger
   pans; two fingers pan/pinch and twist. Right-drag or Shift-drag orbits. */
export class MapControls{
 constructor(camera,canvas){this.camera=camera;this.canvas=canvas;this.target=new Vector3();this.events=new Map();this.listeners=[];this.pointers=new Map();this.minDistance=3;this.maxDistance=4000000;this.maxPolarAngle=85*Math.PI/180;canvas.style.touchAction='none';
  const on=(name,fn,options)=>{canvas.addEventListener(name,fn,options);this.listeners.push(()=>canvas.removeEventListener(name,fn,options));};
  on('contextmenu',e=>e.preventDefault());
  on('pointerdown',e=>{if(![0,2].includes(e.button)&&e.pointerType!=='touch')return;e.preventDefault();canvas.focus();canvas.setPointerCapture?.(e.pointerId);this.pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});this.emit('start');});
  on('pointermove',e=>{if(!this.pointers.has(e.pointerId))return;e.preventDefault();const old=[...this.pointers.values()],previous=this.pointers.get(e.pointerId),dx=e.clientX-previous.x,dy=e.clientY-previous.y;this.pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});const next=[...this.pointers.values()];if(next.length>=2){const a=Math.hypot(old[0].x-old[1].x,old[0].y-old[1].y),b=Math.hypot(next[0].x-next[1].x,next[0].y-next[1].y);if(a>2&&b>2)this.zoom(a/b);this.pan(dx*.5,0);const angle=Math.atan2(next[1].y-next[0].y,next[1].x-next[0].x)-Math.atan2(old[1].y-old[0].y,old[1].x-old[0].x);this.orbit(-angle,dy*.004);}else if(e.buttons===2||e.shiftKey)this.orbit(dx*.006,dy*.006);else this.pan(dx,dy);this.update();this.emit('change');});
  const release=e=>{if(!this.pointers.delete(e.pointerId))return;canvas.releasePointerCapture?.(e.pointerId);this.emit('end');};on('pointerup',release);on('pointercancel',release);on('lostpointercapture',release);
  on('wheel',e=>{e.preventDefault();this.emit('start');const anchor=this.anchor(e.clientX,e.clientY);const delta=e.deltaY*(e.deltaMode===1?16:e.deltaMode===2?canvas.clientHeight:1);this.zoom(Math.exp(Math.max(-2,Math.min(2,delta*.0015))));this.update();if(anchor){const next=this.anchor(e.clientX,e.clientY);if(next){const shift=anchor.sub(next);this.target.add(shift);this.camera.position.add(shift);this.update();}}this.emit('change');},{passive:false});
  on('keydown',e=>{const directions={ArrowLeft:[35,0],ArrowRight:[-35,0],ArrowUp:[0,35],ArrowDown:[0,-35]};if(!directions[e.key])return;e.preventDefault();this.emit('start');this.pan(...directions[e.key]);this.update();this.emit('change');});
 }
 addEventListener(name,fn){if(!this.events.has(name))this.events.set(name,new Set());this.events.get(name).add(fn);}emit(name){for(const fn of this.events.get(name)||[])fn();}
 update(){this.camera.lookAt(this.target);}listenToKeyEvents(){}
 anchor(x,y){const rect=this.canvas.getBoundingClientRect?.();if(!rect||!Number.isFinite(x)||!Number.isFinite(y))return null;const ray=new Raycaster();ray.setFromCamera({x:(x-rect.left)/rect.width*2-1,y:1-(y-rect.top)/rect.height*2},this.camera);if(Math.abs(ray.ray.direction.y)<1e-8)return null;const t=(this.target.y-ray.ray.origin.y)/ray.ray.direction.y;if(t<=0)return null;return ray.ray.origin.clone().add(ray.ray.direction.multiplyScalar(t));}
 zoom(factor){const d=this.camera.position.clone().sub(this.target),distance=Math.max(this.minDistance,Math.min(this.maxDistance,d.length()*factor));this.camera.position.copy(this.target).add(d.normalize().multiplyScalar(distance));}
 orbit(dx,dy){const d=this.camera.position.clone().sub(this.target),r=d.length(),yaw=Math.atan2(d.x,d.z)-dx,pitch=Math.max(.001,Math.min(this.maxPolarAngle,Math.acos(d.y/r)-dy));this.camera.position.copy(this.target).add(new Vector3(Math.sin(yaw)*Math.sin(pitch),Math.cos(pitch),Math.cos(yaw)*Math.sin(pitch)).multiplyScalar(r));}
 pan(dx,dy){const d=this.camera.position.clone().sub(this.target),yaw=Math.atan2(d.x,d.z),scale=d.length()*2*Math.tan(this.camera.fov*Math.PI/360)/Math.max(1,this.canvas.clientHeight),shift=new Vector3(-dx*Math.cos(yaw)-dy*Math.sin(yaw),0,dx*Math.sin(yaw)-dy*Math.cos(yaw)).multiplyScalar(scale);this.target.add(shift);this.camera.position.add(shift);}
 dispose(){this.listeners.forEach(fn=>fn());this.events.clear();this.pointers.clear();}
}
