struct Scene { matrix: mat4x4<f32>, grade: vec4<f32>, metrics: vec4<f32>, style:vec4<f32> };
@group(0) @binding(0) var<uniform> scene: Scene;
@group(1) @binding(0) var photo: texture_2d<f32>;
@group(1) @binding(1) var photo_sampler: sampler;
@group(2) @binding(0) var heights: texture_2d<f32>;
struct Out { @builtin(position) position: vec4<f32>, @location(0) uv: vec2<f32>, @location(1) @interpolate(flat) clip: vec4<f32>, @location(2) @interpolate(flat) image: vec4<f32>, @location(4) ground:vec3<f32>, @location(5) @interpolate(flat) demzoom:f32 };
@vertex fn vs(@builtin(vertex_index) vertex_index:u32, @location(0) vertex: vec2<f32>, @location(1) tile: vec4<f32>, @location(2) clip: vec4<f32>, @location(3) image: vec4<f32>) -> Out {
 var o:Out;
 let height=textureLoad(heights,vec2<i32>(round(vertex*128.)),0).r - select(0.,scene.metrics.z,vertex_index>=16641u);
 let world=vec2<f32>(tile.x+vertex.x*tile.z,tile.y-vertex.y*tile.z);
 o.position=scene.matrix*vec4<f32>(world,(height*scene.metrics.w-scene.metrics.y)*scene.metrics.x,1.);
 o.ground=vec3<f32>(world,height*scene.metrics.x);
 o.demzoom=min(18.,max(0.,log2(scene.style.y/tile.z)-1.));
 o.uv=vertex.xy;o.clip=clip;o.image=image;return o;
}
fn shade(i:Out,dx:vec2<f32>,dy:vec2<f32>,normal:vec3<f32>)->vec4<f32>{
 var p=textureSampleGrad(photo,photo_sampler,clamp(i.uv*i.image.xy+i.image.zw,vec2<f32>(0.),vec2<f32>(1.)),dx,dy);
 if(scene.style.z>0.5){
  let lod=clamp(i32(floor(log2(max(max(length(dx),length(dy))*256.,1.)))),0,8);
  let dimensions=textureDimensions(photo,lod);
  let coord=clamp(vec2<i32>(floor((i.uv*i.image.xy+i.image.zw)*vec2<f32>(dimensions))),vec2<i32>(0),vec2<i32>(dimensions)-1);
  p=textureLoad(photo,coord,lod);
 }
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

// Fine photo subdivisions can be smaller than one terrain cell. Only those
// rounded-out rectangles need fragment clipping; exact rectangles allow early
// depth rejection without a discard instruction, including their skirt faces.
@fragment fn fs(i:Out)->@location(0) vec4<f32>{
 let dx=dpdx(i.uv)*i.image.xy;let dy=dpdy(i.uv)*i.image.xy;
 var normal=vec3<f32>(0.,0.,1.);if(scene.style.x>0.5){normal=cross(dpdx(i.ground),dpdy(i.ground));}
 if(any(i.uv<i.clip.xy)||any(i.uv>i.clip.zw)){discard;}
 return shade(i,dx,dy,normal);
}
@fragment fn fs_unclipped(i:Out)->@location(0) vec4<f32>{
 var normal=vec3<f32>(0.,0.,1.);if(scene.style.x>0.5){normal=cross(dpdx(i.ground),dpdy(i.ground));}
 return shade(i,dpdx(i.uv)*i.image.xy,dpdy(i.uv)*i.image.xy,normal);
}
