struct Scene {matrix:mat4x4<f32>,anchor:vec4<f32>,size:vec4<f32>,time:vec4<f32>};
@group(0) @binding(0) var<uniform> scene:Scene;
@group(1) @binding(0) var atlas:texture_2d<f32>;
@group(1) @binding(1) var atlas_sampler:sampler;
// Terrain depth from the main pass. Labels are drawn afterwards without depth
// testing; each label reads the depth at its own anchor instead.
@group(2) @binding(0) var terrain:texture_depth_2d;
// Comparison sampling: depth textures cannot be read directly on GLES.
@group(2) @binding(1) var terrain_compare:sampler_comparison;
struct Out {@builtin(position) position:vec4<f32>,@location(0) uv:vec2<f32>,@location(1) @interpolate(flat) alpha:f32};
fn project(a:vec3<f32>)->vec4<f32>{return scene.matrix*vec4<f32>(scene.anchor.x+a.x*scene.anchor.z,scene.anchor.y-a.y*scene.anchor.z,(a.z+1.-scene.size.x)*scene.anchor.w,1.);}
@vertex fn vs(@location(0) anchor:vec3<f32>,@location(1) offset:vec2<f32>,@location(2) uv:vec2<f32>,@location(3) born:f32)->Out{
 var o:Out;o.uv=uv;o.alpha=0.;
 var p=project(anchor);
 if(p.w<=0.||p.z<0.){o.position=vec4<f32>(2.,2.,2.,1.);return o;}
 // MapLibre-style whole-symbol occlusion, on the GPU: compare the terrain
 // depth at the anchor's pixel with the anchor raised by a small margin
 // (20 m plus 1.5 px of ground, for mesh detail and depth precision). Every vertex of a label reads the
 // same texel, so a label is hidden or shown whole, never cut by a slope.
 let size=vec2<f32>(textureDimensions(terrain));
 let ndc=p.xy/p.w;
 let pixel=clamp(floor(vec2<f32>((ndc.x*.5+.5)*size.x,(.5-ndc.y*.5)*size.y)),vec2<f32>(0.),size-1.);
 let at=(pixel+.5)/size;
 let centre=(scene.matrix*vec4<f32>(0.,0.,0.,1.)).w;
 let metres=20.+1.5/max(scene.anchor.w,1e-6)*clamp(p.w/max(centre,1e-6),.25,16.);
 let near=project(anchor+vec3<f32>(0.,0.,metres));let nearer=project(anchor+vec3<f32>(0.,0.,metres*2.));
 let d0=near.z/near.w;let d1=nearer.z/nearer.w;
 // Fully visible when the terrain at the anchor is no nearer than d0, hidden
 // at d1 and nearer, faded in four steps between: no popping as the camera moves.
 var visible=0.;
 for(var k=0;k<4;k++){visible+=textureSampleCompareLevel(terrain,terrain_compare,at,mix(d0,d1,f32(k)/3.))*.25;}
 // New labels fade in over 0.2 s instead of popping.
 visible*=clamp((scene.time.x-born)/.2,0.,1.);
 o.alpha=visible;
 if(visible<=0.){o.position=vec4<f32>(2.,2.,2.,1.);return o;}
 p=vec4<f32>(p.xy+offset*vec2<f32>(1.,-1.)*scene.size.w*2./scene.size.yz*p.w,p.zw);o.position=p;return o;
}
@fragment fn fs(i:Out)->@location(0) vec4<f32>{let p=textureSample(atlas,atlas_sampler,i.uv)*i.alpha;if(p.a<0.005){discard;}return p;}
