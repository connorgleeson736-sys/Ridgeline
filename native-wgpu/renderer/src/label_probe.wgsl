// Visibility of every candidate label (not only the placed ones), as drawn: its
// slot's smoothed terrain state (label_fade.wgsl), so placement and drawing agree
// and a name on a ridge's edge does not take space and give it back frame to frame.
// One pixel per label in a 512 x 1 target: red = shown (0/1), green = probed.
// Read back to the CPU so labels hidden behind terrain do not take screen space
// from visible ones in collision placement (as MapLibre places symbols).
struct Scene {matrix:mat4x4<f32>,previous:mat4x4<f32>,anchor:vec4<f32>,size:vec4<f32>,time:vec4<f32>};
@group(0) @binding(0) var<uniform> scene:Scene;
@group(1) @binding(0) var state:texture_2d<f32>;
struct Out {@builtin(position) position:vec4<f32>,@location(0) @interpolate(flat) visible:f32};
fn local(a:vec3<f32>)->vec4<f32>{return vec4<f32>(scene.anchor.x+a.x*scene.anchor.z,scene.anchor.y-a.y*scene.anchor.z,(a.z+1.-scene.size.x)*scene.anchor.w,1.);}
fn project(a:vec3<f32>)->vec4<f32>{return scene.matrix*local(a);}
@vertex fn vs(@location(0) anchor:vec3<f32>,@location(1) index:f32,@location(2) slot:f32)->Out{
 var o:Out;
 o.position=vec4<f32>((index+.5)/512.*2.-1.,0.,0.,1.);
 o.visible=0.;
 let p=project(anchor);
 if(p.w<=0.||p.z<0.){return o;}
 let ndc=p.xy/p.w;
 if(abs(ndc.x)>1.||abs(ndc.y)>1.){return o;}
 let i=i32(slot);
 if(i<0||i>=i32(textureDimensions(state).x)){return o;}
 let s=textureLoad(state,vec2<i32>(i,0),0);
 o.visible=select(0.,s.g,s.a>.5);
 return o;
}
@fragment fn fs(i:Out)->@location(0) vec4<f32>{return vec4<f32>(i.visible,1.,0.,1.);}
