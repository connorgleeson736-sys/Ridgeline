struct Scene { matrix: mat4x4<f32>, grade: vec4<f32>, metrics: vec4<f32> };
@group(0) @binding(0) var<uniform> scene: Scene;
@group(1) @binding(0) var photo: texture_2d<f32>;
@group(1) @binding(1) var photo_sampler: sampler;
@group(2) @binding(0) var heights: texture_2d<f32>;
struct Out { @builtin(position) position: vec4<f32>, @location(0) uv: vec2<f32>, @location(1) @interpolate(flat) clip: vec4<f32>, @location(2) @interpolate(flat) image: vec4<f32> };
@vertex fn vs(@location(0) vertex: vec2<f32>, @location(1) tile: vec4<f32>, @location(2) clip: vec4<f32>, @location(3) image: vec4<f32>) -> Out {
 var o:Out;
 let height=textureLoad(heights,vec2<i32>(round(vertex*128.)),0).r;
 o.position=scene.matrix*vec4<f32>(tile.x+vertex.x*tile.z,tile.y-vertex.y*tile.z,(height-scene.metrics.y)*scene.metrics.x,1.);
 o.uv=vertex.xy;o.clip=clip;o.image=image;return o;
}
@fragment fn fs(i:Out)->@location(0) vec4<f32>{
 let dx=dpdx(i.uv)*i.image.xy;let dy=dpdy(i.uv)*i.image.xy;
 if(any(i.uv<i.clip.xy)||any(i.uv>i.clip.zw)){discard;}
 var p=textureSampleGrad(photo,photo_sampler,clamp(i.uv*i.image.xy+i.image.zw,vec2<f32>(0.),vec2<f32>(1.)),dx,dy);
 if(p.a>0.){p=vec4<f32>(p.rgb/p.a,p.a);}
 let average=(p.r+p.g+p.b)/3.;
 var rgb=p.rgb+(vec3<f32>(average)-p.rgb)*scene.grade.z;
 rgb=(rgb-vec3<f32>(.5))*scene.grade.w+vec3<f32>(.5);
 rgb=mix(vec3<f32>(scene.grade.x),vec3<f32>(scene.grade.y),rgb);
 return clamp(vec4<f32>(rgb*p.a,p.a),vec4<f32>(0.),vec4<f32>(1.));
}
