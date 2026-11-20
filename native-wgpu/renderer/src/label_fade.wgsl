// Per-label terrain visibility, kept on the GPU from frame to frame.
// One point per label slot writes that slot's texel of a SLOTS x 1 state
// texture from the previous frame's state and the terrain depth:
//   r: the raw visibility, filtered over ~0.12 s
//   g: shown (1) or hidden (0), changed only once r clearly passes 0.7 or 0.3
//   b: the opacity drawn, moving towards g over 0.2 s
//   a: 1 once the slot holds a known state
// The raw test samples the depth smoothly (2 x 2 comparison filtering at the
// anchor's exact place, at four heights above it), so a name on a ridge's edge
// changes gradually as the view moves instead of flipping with the pixel its
// anchor falls in. With the hysteresis and fades, a tilted map panning a pixel
// or two a frame never makes names blink.
struct Scene {matrix:mat4x4<f32>,previous:mat4x4<f32>,anchor:vec4<f32>,size:vec4<f32>,time:vec4<f32>};
@group(0) @binding(0) var<uniform> scene:Scene;
// The PREVIOUS frame's terrain depth, read through that frame's camera.
@group(1) @binding(0) var terrain:texture_depth_2d;
@group(1) @binding(1) var terrain_compare:sampler_comparison;
@group(2) @binding(0) var previous_state:texture_2d<f32>;
struct Out {@builtin(position) position:vec4<f32>,@location(0) @interpolate(flat) state:vec4<f32>};
fn local(a:vec3<f32>)->vec4<f32>{return vec4<f32>(scene.anchor.x+a.x*scene.anchor.z,scene.anchor.y-a.y*scene.anchor.z,(a.z+1.-scene.size.x)*scene.anchor.w,1.);}
fn project(a:vec3<f32>)->vec4<f32>{return scene.matrix*local(a);}
fn before(a:vec3<f32>)->vec4<f32>{return scene.previous*local(a);}
// The anchor's visibility now (0..1); -1 when it cannot be told this frame (no
// previous depth, as at a drawing-resolution switch, or just off the previous
// frame's screen, where the depth read would be an edge pixel's, not the terrain
// in front of it): the state is kept; -2 when well off screen or behind the
// camera: the state starts afresh once back.
fn raw(anchor:vec3<f32>)->f32{
 let p=project(anchor);
 if(p.w<=0.||p.z<0.){return -2.;}
 if(scene.time.y<.5){return -1.;}
 let q=before(anchor);
 if(q.w<=0.){return -2.;}
 let ndc=q.xy/q.w;
 if(abs(ndc.x)>1.2||abs(ndc.y)>1.2){return -2.;}
 if(abs(ndc.x)>1.||abs(ndc.y)>1.){return -1.;}
 let size=vec2<f32>(textureDimensions(terrain));
 let at=clamp(vec2<f32>(ndc.x*.5+.5,.5-ndc.y*.5),.5/size,1.-.5/size);
 // Raised by 20 m plus 1.5 px of ground (mesh detail and depth precision).
 let centre=(scene.matrix*vec4<f32>(0.,0.,0.,1.)).w;
 let metres=20.+1.5/max(scene.anchor.w,1e-6)*clamp(p.w/max(centre,1e-6),.25,16.);
 let near=before(anchor+vec3<f32>(0.,0.,metres));let nearer=before(anchor+vec3<f32>(0.,0.,metres*2.));
 let d0=near.z/near.w;let d1=nearer.z/nearer.w;
 var v=0.;
 for(var k=0;k<4;k++){v+=textureSampleCompareLevel(terrain,terrain_compare,at,mix(d0,d1,f32(k)/3.))*.25;}
 return v;
}
@vertex fn vs(@location(0) anchor:vec3<f32>,@location(1) slot:f32,@location(2) fresh:f32)->Out{
 var o:Out;
 let width=f32(textureDimensions(previous_state).x);
 o.position=vec4<f32>((slot+.5)/width*2.-1.,0.,0.,1.);
 var old=vec4<f32>(0.);
 if(fresh<.5){old=textureLoad(previous_state,vec2<i32>(i32(slot),0),0);}
 let known=old.a>.5;
 let dt=scene.time.z;
 let r=raw(anchor);
 if(r<0.){
  // Unknown this frame: the state is kept (its fade still runs). Well off
  // screen, or never known yet: unknown, so hidden until known.
  if(known&&r>-1.5){o.state=vec4<f32>(old.r,old.g,old.b+clamp(old.g-old.b,-dt/.2,dt/.2),1.);}else{o.state=vec4<f32>(0.);}
  return o;
 }
 // First known (new, or back on screen): its side at once (a newly placed
 // label fades in by itself, labels.wgsl).
 if(!known){let shown=step(.5,r);o.state=vec4<f32>(r,shown,shown,1.);return o;}
 let f=mix(old.r,r,1.-exp(-dt/.12));
 let shown=select(step(.7,f),step(.3,f),old.g>.5);
 o.state=vec4<f32>(f,shown,old.b+clamp(shown-old.b,-dt/.2,dt/.2),1.);
 return o;
}
@fragment fn fs(i:Out)->@location(0) vec4<f32>{return i.state;}
