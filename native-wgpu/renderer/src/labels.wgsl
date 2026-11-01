struct Scene {matrix:mat4x4<f32>,anchor:vec4<f32>,size:vec4<f32>};
@group(0) @binding(0) var<uniform> scene:Scene;
@group(1) @binding(0) var atlas:texture_2d<f32>;
@group(1) @binding(1) var atlas_sampler:sampler;
struct Out {@builtin(position) position:vec4<f32>,@location(0) uv:vec2<f32>};
@vertex fn vs(@location(0) anchor:vec3<f32>,@location(1) offset:vec2<f32>,@location(2) uv:vec2<f32>)->Out{
 var o:Out;var p=scene.matrix*vec4<f32>(scene.anchor.x+anchor.x*scene.anchor.z,scene.anchor.y-anchor.y*scene.anchor.z,(anchor.z+1.-scene.size.x)*scene.anchor.w,1.);
 if(p.w<=0.||p.z<0.){o.position=vec4<f32>(2.,2.,2.,1.);o.uv=uv;return o;}
 p=vec4<f32>(p.xy+offset*vec2<f32>(1.,-1.)*scene.size.w*2./scene.size.yz*p.w,p.zw);o.position=p;o.uv=uv;return o;
}
@fragment fn fs(i:Out)->@location(0) vec4<f32>{let p=textureSample(atlas,atlas_sampler,i.uv);if(p.a<0.005){discard;}return p;}
