struct Scene {matrix:mat4x4<f32>,previous:mat4x4<f32>,anchor:vec4<f32>,size:vec4<f32>,time:vec4<f32>};
@group(0) @binding(0) var<uniform> scene:Scene;
@group(1) @binding(0) var atlas:texture_2d<f32>;
@group(1) @binding(1) var atlas_sampler:sampler;
// Each label's terrain visibility, smoothed over time on the GPU (label_fade.wgsl,
// written earlier in this frame): r filtered visibility, g shown (0/1), b opacity,
// a 1 once known. A label is never hidden or shown by a single frame's depth.
@group(2) @binding(0) var state:texture_2d<f32>;
struct Out {@builtin(position) position:vec4<f32>,@location(0) uv:vec2<f32>,@location(1) @interpolate(flat) alpha:f32};
fn local(a:vec3<f32>)->vec4<f32>{return vec4<f32>(scene.anchor.x+a.x*scene.anchor.z,scene.anchor.y-a.y*scene.anchor.z,(a.z+1.-scene.size.x)*scene.anchor.w,1.);}
fn project(a:vec3<f32>)->vec4<f32>{return scene.matrix*local(a);}
@vertex fn vs(@location(0) anchor:vec3<f32>,@location(1) offset:vec2<f32>,@location(2) uv:vec2<f32>,@location(3) born:f32,@location(4) slot:f32,@location(5) dies:f32)->Out{
 var o:Out;o.uv=uv;o.alpha=0.;
 var p=project(anchor);
 if(p.w<=0.||p.z<0.){o.position=vec4<f32>(2.,2.,2.,1.);return o;}
 // MapLibre-style whole-symbol occlusion: the label shows or hides whole, and
 // fades between (its slot's smoothed state), never cut by a slope.
 var visible=0.;
 let i=i32(slot);
 if(i>=0&&i<i32(textureDimensions(state).x)){let s=textureLoad(state,vec2<i32>(i,0),0);visible=select(0.,s.b,s.a>.5);}
 // New labels fade in over 0.2 s; labels dropped by placement fade out over 0.25 s.
 visible*=clamp((scene.time.x-born)/.2,0.,1.)*clamp((dies-scene.time.x)/.25,0.,1.);
 o.alpha=visible;
 if(visible<=0.){o.position=vec4<f32>(2.,2.,2.,1.);return o;}
 p=vec4<f32>(p.xy+offset*vec2<f32>(1.,-1.)*scene.size.w*2./scene.size.yz*p.w,p.zw);o.position=p;return o;
}
@fragment fn fs(i:Out)->@location(0) vec4<f32>{let p=textureSample(atlas,atlas_sampler,i.uv)*i.alpha;if(p.a<0.005){discard;}return p;}
