struct Scene { matrix: mat4x4<f32>, grade: vec4<f32>, metrics: vec4<f32> };
@group(0) @binding(0) var<uniform> scene: Scene;
@group(1) @binding(0) var photo: texture_2d<f32>;
@group(1) @binding(1) var photo_sampler: sampler;
struct Out { @builtin(position) position: vec4<f32>, @location(0) uv: vec2<f32>, @location(1) @interpolate(flat) clip: vec4<f32> };
@vertex fn vs(@location(0) vertex: vec3<f32>, @location(1) tile: vec4<f32>, @location(2) clip: vec4<f32>) -> Out {
 var o:Out;
 o.position=scene.matrix*vec4<f32>(tile.x+vertex.x*tile.z,tile.y-vertex.y*tile.z,vertex.z*scene.metrics.x,1.);
 o.uv=vertex.xy;o.clip=clip;return o;
}
@fragment fn fs(i:Out)->@location(0) vec4<f32>{
 if(any(i.uv<i.clip.xy)||any(i.uv>=i.clip.zw)){discard;}
 var p=textureSample(photo,photo_sampler,i.uv);
 if(p.a>0.){p=vec4<f32>(p.rgb/p.a,p.a);}
 let average=(p.r+p.g+p.b)/3.;
 var rgb=p.rgb+(vec3<f32>(average)-p.rgb)*scene.grade.z;
 rgb=(rgb-vec3<f32>(.5))*scene.grade.w+vec3<f32>(.5);
 rgb=mix(vec3<f32>(scene.grade.x),vec3<f32>(scene.grade.y),rgb);
 return clamp(vec4<f32>(rgb*p.a,p.a),vec4<f32>(0.),vec4<f32>(1.));
}
