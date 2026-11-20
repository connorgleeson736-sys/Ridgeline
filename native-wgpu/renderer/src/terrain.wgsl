struct Scene { matrix: mat4x4<f32>, grade: vec4<f32>, metrics: vec4<f32>, style:vec4<f32>, coverage:vec4<f32> };
@group(0) @binding(0) var<uniform> scene: Scene;
@group(0) @binding(1) var coverage_tex: texture_2d<f32>;
@group(0) @binding(2) var coverage_sampler: sampler;
@group(1) @binding(0) var photo: texture_2d<f32>;
@group(1) @binding(1) var photo_sampler: sampler;
@group(2) @binding(0) var heights: texture_2d<f32>;
@group(3) @binding(0) var overlay: texture_2d_array<f32>;
@group(3) @binding(1) var overlay_sampler: sampler;
@group(3) @binding(2) var overlay_small: texture_2d_array<f32>;
struct Out { @builtin(position) position: vec4<f32>, @location(0) uv: vec2<f32>, @location(1) @interpolate(flat) clip: vec4<f32>, @location(2) @interpolate(flat) image: vec4<f32>, @location(4) ground:vec3<f32>, @location(5) @interpolate(flat) demzoom:f32, @location(6) @interpolate(flat) overlay:f32 };
@vertex fn vs(@builtin(vertex_index) vertex_index:u32, @location(0) vertex: vec2<f32>, @location(1) tile: vec4<f32>, @location(2) clip: vec4<f32>, @location(3) image: vec4<f32>) -> Out {
 var o:Out;
 let height=textureLoad(heights,vec2<i32>(round(vertex*128.)),0).r - select(0.,scene.metrics.z,vertex_index>=16641u);
 let world=vec2<f32>(tile.x+vertex.x*tile.z,tile.y-vertex.y*tile.z);
 o.position=scene.matrix*vec4<f32>(world,(height*scene.metrics.w-scene.metrics.y)*scene.metrics.x,1.);
 o.ground=vec3<f32>(world,height*scene.metrics.x);
 o.demzoom=min(18.,max(0.,log2(scene.style.y/tile.z)-1.));
 o.overlay=tile.w;o.uv=vertex.xy;o.clip=clip;o.image=image;return o;
}
// NZ Topo (printed maps), as MapLibre draws raster-resampling "nearest":
// shrunk tiles are bilinear from the nearest mip level (LINEAR_MIPMAP_NEAREST),
// never point-sampled (which drops texel rows: broken lines, shimmer).
// Enlarged tiles keep crisp texel edges, antialiased over one screen pixel
// ("sharp bilinear") instead of blocky nearest pixels.
fn printed(i:Out,dx:vec2<f32>,dy:vec2<f32>)->vec4<f32>{
 let uv=clamp(i.uv*i.image.xy+i.image.zw,vec2<f32>(0.),vec2<f32>(1.));
 let size=vec2<f32>(textureDimensions(photo,0));
 let rho=max(length(dx*size),length(dy*size));
 if(rho<1.){
  let t=uv*size-.5;let cell=floor(t);
  let g=clamp((t-cell-.5)/max(rho,1e-3)+.5,vec2<f32>(0.),vec2<f32>(1.));
  return photo_level(i,(cell+g+.5)/size,0.);
 }
 return photo_level(i,uv,min(floor(log2(rho)+.5),8.));
}
fn photo_level(i:Out,uv:vec2<f32>,lod:f32)->vec4<f32>{return textureSampleLevel(photo,photo_sampler,uv,lod);}
fn shade(i:Out,dx:vec2<f32>,dy:vec2<f32>,normal:vec3<f32>)->vec4<f32>{
 var p:vec4<f32>;
 if(scene.style.z>0.5){p=printed(i,dx,dy);}
 else{p=textureSampleGrad(photo,photo_sampler,clamp(i.uv*i.image.xy+i.image.zw,vec2<f32>(0.),vec2<f32>(1.)),dx,dy);}
 if(p.a>0.){p=vec4<f32>(p.rgb/p.a,p.a);}
 let average=(p.r+p.g+p.b)/3.;
 var rgb=p.rgb+(vec3<f32>(average)-p.rgb)*scene.grade.z;
 rgb=(rgb-vec3<f32>(.5))*scene.grade.w+vec3<f32>(.5);
 rgb=mix(vec3<f32>(scene.grade.x),vec3<f32>(scene.grade.y),rgb);
 // Reference Igor model (315deg, strength .16), using existing terrain gradients.
 // Disabled by default. No duplicate hillshade source, decoder, texture or draw pass.
 if(scene.style.x>0.5&&abs(normal.z)>0.000001){
  let zoomFactor=select(0.3,select(0.35,0.4,i.demzoom<2.),i.demzoom<4.5);
  let amplification=exp2(-min(0.,i.demzoom-15.)*zoomFactor);
  let deriv=vec2<f32>(-normal.x,normal.y)/normal.z*(0.16*2.*amplification);
  var aspect=1.57079632679*select(-1.,1.,deriv.y>0.);
  if(deriv.x!=0.){aspect=atan2(deriv.y,-deriv.x);}
  let slope=atan(length(deriv))*2./3.14159265359;
  let angle=(aspect+8.63937979737)/3.14159265359+0.5;
  let strength=1.-abs((angle-floor(angle/2.)*2.)-1.);
  rgb=rgb*(1.-slope)+vec3<f32>(52./255.,64./255.,75./255.)*(slope*strength)+vec3<f32>(1.)*(slope*(1.-strength));
 }
 return clamp(vec4<f32>(rgb*p.a,p.a),vec4<f32>(0.),vec4<f32>(1.));
}

// Topographic lines painted for this image tile (overlay.rs), laid over the
// graded photo like MapLibre's terrain drape. Slot 0 means no overlay.
// Positive slots are 512 px textures, negative ones the 256 px tier.
fn drape(i:Out,c:vec4<f32>,dx:vec2<f32>,dy:vec2<f32>)->vec4<f32>{
 if(abs(i.overlay)<0.5){return c;}
 let uv=clamp(i.uv*i.image.xy+i.image.zw,vec2<f32>(0.),vec2<f32>(1.));
 var o:vec4<f32>;
 if(i.overlay>0.){o=textureSampleGrad(overlay,overlay_sampler,uv,i32(i.overlay-0.5),dx,dy);}
 else{o=textureSampleGrad(overlay_small,overlay_sampler,uv,i32(-i.overlay-0.5),dx,dy);}
 return o+c*(1.-o.a);
}

// Fine photo subdivisions can be smaller than one terrain cell. Only those
// rounded-out rectangles need fragment clipping; exact rectangles allow early
// depth rejection without a discard instruction, including their skirt faces.
// Advanced view: disk-cache coverage colours over the terrain (red: little or
// no detail stored, through yellow, to green: detailed imagery stored).
fn coverage(i:Out,c:vec4<f32>)->vec4<f32>{
 if(scene.style.w<0.5){return c;}
 let uv=vec2<f32>((i.ground.x-scene.coverage.x)*scene.coverage.z,(scene.coverage.y-i.ground.y)*scene.coverage.w);
 if(any(uv<vec2<f32>(0.))||any(uv>vec2<f32>(1.))){return c;}
 let t=textureSampleLevel(coverage_tex,coverage_sampler,uv,0.);
 return vec4<f32>(mix(c.rgb,t.rgb*c.a,t.a),c.a);
}
@fragment fn fs(i:Out)->@location(0) vec4<f32>{
 let dx=dpdx(i.uv)*i.image.xy;let dy=dpdy(i.uv)*i.image.xy;
 var normal=vec3<f32>(0.,0.,1.);if(scene.style.x>0.5){normal=cross(dpdx(i.ground),dpdy(i.ground));}
 if(any(i.uv<i.clip.xy)||any(i.uv>i.clip.zw)){discard;}
 return coverage(i,drape(i,shade(i,dx,dy,normal),dx,dy));
}
@fragment fn fs_unclipped(i:Out)->@location(0) vec4<f32>{
 var normal=vec3<f32>(0.,0.,1.);if(scene.style.x>0.5){normal=cross(dpdx(i.ground),dpdy(i.ground));}
 let dx=dpdx(i.uv)*i.image.xy;let dy=dpdy(i.uv)*i.image.xy;
 return coverage(i,drape(i,shade(i,dx,dy,normal),dx,dy));
}
