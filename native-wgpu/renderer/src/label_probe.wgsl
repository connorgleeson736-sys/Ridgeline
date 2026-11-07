// Visibility of every candidate label (not only the placed ones), from this
// frame's terrain depth, with the same anchor test the labels are drawn with.
// One pixel per label in a 512 x 1 target: red = visible (0..1), green = probed.
// Read back to the CPU so labels hidden behind terrain do not take screen space
// from visible ones in collision placement (as MapLibre places symbols).
struct Scene {matrix:mat4x4<f32>,previous:mat4x4<f32>,anchor:vec4<f32>,size:vec4<f32>,time:vec4<f32>};
@group(0) @binding(0) var<uniform> scene:Scene;
@group(1) @binding(0) var terrain:texture_depth_2d;
@group(1) @binding(1) var terrain_compare:sampler_comparison;
struct Out {@builtin(position) position:vec4<f32>,@location(0) @interpolate(flat) visible:f32};
fn local(a:vec3<f32>)->vec4<f32>{return vec4<f32>(scene.anchor.x+a.x*scene.anchor.z,scene.anchor.y-a.y*scene.anchor.z,(a.z+1.-scene.size.x)*scene.anchor.w,1.);}
fn project(a:vec3<f32>)->vec4<f32>{return scene.matrix*local(a);}
@vertex fn vs(@location(0) anchor:vec3<f32>,@location(1) index:f32)->Out{
 var o:Out;
 o.position=vec4<f32>((index+.5)/512.*2.-1.,0.,0.,1.);
 o.visible=0.;
 let p=project(anchor);
 if(p.w<=0.||p.z<0.){return o;}
 let ndc=p.xy/p.w;
 if(abs(ndc.x)>1.||abs(ndc.y)>1.){return o;}
 let size=vec2<f32>(textureDimensions(terrain));
 let pixel=clamp(floor(vec2<f32>((ndc.x*.5+.5)*size.x,(.5-ndc.y*.5)*size.y)),vec2<f32>(0.),size-1.);
 let at=(pixel+.5)/size;
 let centre=(scene.matrix*vec4<f32>(0.,0.,0.,1.)).w;
 let metres=20.+1.5/max(scene.anchor.w,1e-6)*clamp(p.w/max(centre,1e-6),.25,16.);
 let near=project(anchor+vec3<f32>(0.,0.,metres));let nearer=project(anchor+vec3<f32>(0.,0.,metres*2.));
 let d0=near.z/near.w;let d1=nearer.z/nearer.w;
 var visible=0.;
 for(var k=0;k<4;k++){visible+=textureSampleCompareLevel(terrain,terrain_compare,at,mix(d0,d1,f32(k)/3.))*.25;}
 o.visible=visible;
 return o;
}
@fragment fn fs(i:Out)->@location(0) vec4<f32>{return vec4<f32>(i.visible,1.,0.,1.);}
