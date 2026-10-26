export {PatchGeometryCache,pickHeightfield} from './terrain-gpu-common.mjs';
import {luma,webgpuAdapter,Model,Buffer,Texture as GPUTexture} from './assets/luma/luma.mjs';
import {terrainShader,terrainLayout} from './luma-terrain-shaders.mjs';
import './terrain-patches.js';
const P=globalThis.RidgePatches;
// Reuse shader/pipeline objects across imagery tiles and submit the entire mip
// chain once. The generic luma helper recreates them and submits every level.
export class ImageryMipmaps{
 constructor(device){this.device=device;this.entries=new Map();this.sampler=device.createSampler({minFilter:'linear',magFilter:'linear'});}
 generate(texture){if(texture.mipLevels<=1)return;const device=this.device;let entry=this.entries.get(texture.format);if(!entry){
  const source=`struct Out { @builtin(position) position: vec4f, @location(0) uv: vec2f }; @group(0) @binding(0) var linearSampler: sampler; @group(0) @binding(1) var sourceImage: texture_2d<f32>;
@vertex fn vertexMain(@builtin(vertex_index) i:u32)->Out { let positions=array(vec2f(-1,-1),vec2f(-1,3),vec2f(3,-1));let p=positions[i];return Out(vec4f(p,0,1),p*vec2f(.5,-.5)+vec2f(.5)); }
@fragment fn fragmentMain(input:Out)->@location(0) vec4f { return textureSampleLevel(sourceImage,linearSampler,input.uv,0); }`;
  const vs=device.createShader({source,language:'wgsl',stage:'vertex'}),fs=device.createShader({source,language:'wgsl',stage:'fragment'});
  const pipeline=device.createRenderPipeline({vs,fs,shaderLayout:{attributes:[],bindings:[{type:'sampler',name:'linearSampler',group:0,location:0},{type:'texture',name:'sourceImage',group:0,location:1,viewDimension:'2d',sampleType:'float'}]},colorAttachmentFormats:[texture.format],topology:'triangle-list'});entry={vs,fs,pipeline};this.entries.set(texture.format,entry);
 }
 const resources=[];try{for(let level=1;level<texture.mipLevels;level++){const source=texture.createView({dimension:'2d',baseMipLevel:level-1,mipLevelCount:1,baseArrayLayer:0,arrayLayerCount:1}),destination=texture.createView({dimension:'2d',baseMipLevel:level,mipLevelCount:1,baseArrayLayer:0,arrayLayerCount:1});resources.push(source,destination);const framebuffer=device.createFramebuffer({colorAttachments:[destination]});resources.push(framebuffer);const pass=device.beginRenderPass({framebuffer});try{pass.setPipeline(entry.pipeline);pass.setBindings({linearSampler:this.sampler,sourceImage:source});const width=Math.max(1,texture.width>>level),height=Math.max(1,texture.height>>level);pass.setParameters({viewport:[0,0,width,height,0,1],scissorRect:[0,0,width,height]});pass.draw({vertexCount:3});}finally{pass.end();}}device.submit();}finally{for(const resource of resources.reverse())resource.destroy();}
 }
 dispose(){for(const {vs,fs,pipeline}of this.entries.values()){pipeline.destroy();vs.destroy();fs.destroy();}this.entries.clear();this.sampler.destroy();}
}
export class Texture{
 constructor(image){this.image=image;this.handle=null;this.disposed=false;this.format='rgba8unorm-srgb';}
 dispose(){this.disposed=true;this.handle?.destroy();this.handle=null;}
 upload(device,mipmaps){if(this.disposed)throw Error('Terrain texture was already released');if(this.handle)return this.handle;const image=this.image,size=image.size;this.handle=device.createTexture({id:this.format==='r32float'?'terrain-elevation':'terrain-imagery',width:size||image.width,height:size||image.height,format:this.format,...(!size?{mipLevels:Math.floor(Math.log2(Math.max(image.width,image.height)))+1,usage:GPUTexture.SAMPLE|GPUTexture.RENDER|GPUTexture.COPY_DST}:{}),...(size?{data:image.data}:{}),sampler:{minFilter:'linear',magFilter:'linear',addressModeU:'clamp-to-edge',addressModeV:'clamp-to-edge'}});if(!size){this.handle.copyExternalImage({image});if(!mipmaps)throw Error('Imagery mipmap generator is unavailable');mipmaps.generate(this.handle);}return this.handle;}
}
export function elevationTexture(data){const t=new Texture({size:data.size,data:data.heights});t.format='r32float';return t;}
export function normalTexture(data){const t=new Texture({size:data.size,data:data.normals});t.format='rgba8unorm';return t;}
export function gpuTerrainMaterial(){return{dispose(){}};}
// One Model per shared grid level. Per-patch UBOs are distinct: queue writes
// cannot cause earlier encoded draws to observe a later patch's parameters.
export class TerrainGPU{
 constructor(options={}){this.options=options;this.backend={isWebGPUBackend:true};this.coordinateSystem='webgpu';this.models=new Map();this.patches=new Map();this.ratio=1;this.dead=false;this.error=null;this.frames=0;}
 async init(){
  if(!globalThis.navigator?.gpu&&!this.options.device)throw Error('WebGPU is unavailable on this browser or device. Use Original.');
  this.device=this.options.device||await luma.createDevice({type:'webgpu',adapters:[webgpuAdapter],createCanvasContext:{canvas:this.options.canvas,autoResize:false,useDevicePixels:false,alphaMode:'opaque'},onError:error=>{if(this.dead)return true;this.error=error;this.onError?.(error);return true;}});
  if(this.dead){this.device.destroy();throw Error('Terrain graphics startup cancelled');}
  this.context=this.options.context||this.device.getDefaultCanvasContext();this.mipmaps=new ImageryMipmaps(this.device);
  this.sampler=this.device.createSampler({minFilter:'linear',magFilter:'linear',mipmapFilter:'linear',addressModeU:'clamp-to-edge',addressModeV:'clamp-to-edge'});
  void this.device.lost.then(info=>{if(!this.dead)this.onDeviceLost?.({message:info.message||'WebGPU device lost'});});
 }
 setPixelRatio(value){this.ratio=value;}
 setSize(width,height){if(!this.context)return;this.context.setDrawingBufferSize(Math.max(1,Math.round(width*this.ratio)),Math.max(1,Math.round(height*this.ratio)));}
 getMaxAnisotropy(){return 1;}
 hasFeature(){return false;}
 model(geometry){let entry=this.models.get(geometry.cells);if(entry)return entry;const device=this.device,vertices=device.createBuffer({usage:Buffer.VERTEX,data:geometry.vertices}),indices=device.createBuffer({usage:Buffer.INDEX,data:geometry.indices,indexType:geometry.indices instanceof Uint32Array?'uint32':'uint16'});
  try{const model=new Model(device,{id:'ridgeline-terrain-'+geometry.cells,source:terrainShader,shaderLayout:terrainLayout,bufferLayout:[{name:'grid',format:'float32x3'}],attributes:{grid:vertices},indexBuffer:indices,indexCount:geometry.indices.length,parameters:{depthWriteEnabled:true,depthCompare:'less',cullMode:'back'},...(this.options.colorFormat?{colorAttachmentFormats:[this.options.colorFormat]}:{}),depthStencilAttachmentFormat:'depth24plus'});entry={model,vertices,indices};this.models.set(geometry.cells,entry);return entry;}catch(error){vertices.destroy();indices.destroy();throw error;}
 }
 drawPatch(pass,parent,quad,matrix){const p=quad.userData.ridge;let record=this.patches.get(quad);if(!record){record={uniform:this.device.createBuffer({usage:Buffer.UNIFORM|Buffer.COPY_DST,byteLength:144}),data:new Float32Array(36)};this.patches.set(quad,record);}const a=record.data;a.set(matrix,0);a.set([parent.position.x,parent.position.z,p.geo.x*parent.scale.x,parent.scale.x],16);a.set([p.geo.x,p.geo.y,p.geo.z,p.geo.w],20);a.set([p.offset.x,p.offset.y,p.sampling.z,0],24);a.set([p.imageUV.x,p.imageUV.y,p.imageUV.z,p.imageUV.w],28);a.set([p.appearance.x,p.appearance.y,0,0],32);record.uniform.write(a);
  const model=this.model(quad.geometry).model;model.setBindings({params:record.uniform,elevation:p.heightTexture.upload(this.device,this.mipmaps),normals:p.normalTexture.upload(this.device,this.mipmaps),photograph:p.photoTexture.upload(this.device,this.mipmaps),linearSampler:this.sampler});return model.draw(pass);
 }
 render(scene,camera){camera.updateMatrixWorld();if(this.error)throw this.error;if(this.device.isLost)throw Error('WebGPU device lost');const pass=this.device.beginRenderPass({...(this.options.framebuffer?{framebuffer:this.options.framebuffer}:{}),clearColor:scene.background.value,clearDepth:1}),matrix=camera.viewProjection();let complete=true,draws=0;try{for(const group of scene.children)for(const parent of group.children){if(!parent.visible)continue;for(const quad of parent.children)if(quad.visible){complete=this.drawPatch(pass,parent,quad,matrix)&&complete;draws++;}}}finally{pass.end();}this.device.submit();this.frames++;this.lastDraws=draws;this.lastComplete=complete;return complete;}
 prepareNode(n){n.heightTexture.upload(this.device,this.mipmaps);n.normalTexture.upload(this.device,this.mipmaps);n.texture.upload(this.device,this.mipmaps);}
 async compileAsync(scene,camera){const deadline=performance.now()+10000;while([...this.models.values()].some(e=>e.model.pipeline.isPending)){if(this.dead)throw Error('Terrain startup cancelled');if(performance.now()>deadline)throw Error('WebGPU terrain pipeline compilation timed out');await new Promise(r=>setTimeout(r,10));}for(const {model}of this.models.values()){for(const shader of [model.pipeline.vs,model.pipeline.fs])if(shader){const info=await shader.getCompilationInfo();const failures=(info||[]).filter(m=>m.type==='error');if(failures.length)throw Error(failures.map(m=>m.message).join('; '));}if(model.pipeline.isErrored)throw Error('WebGPU terrain shader compilation failed');}this.render(scene,camera);await this.device.handle.queue.onSubmittedWorkDone();await new Promise(r=>setTimeout(r,0));if(this.error)throw this.error;}
 releaseNode(node){for(const quad of node.mesh?.children||[]){const record=this.patches.get(quad);record?.uniform.destroy();this.patches.delete(quad);}}
 dispose(){if(this.dead)return;this.dead=true;for(const p of this.patches.values())p.uniform.destroy();this.patches.clear();for(const {model,vertices,indices}of this.models.values()){model.destroy();vertices.destroy();indices.destroy();}this.models.clear();this.mipmaps?.dispose();this.sampler?.destroy();this.device?.destroy();}
}

