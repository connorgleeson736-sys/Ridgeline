// Topographic lines rasterised into one image tile's overlay texture (like
// MapLibre's terrain render-to-texture drape). Coordinates are texture pixels.
const SIZE:f32=512.;
struct Out {
 @builtin(position) position:vec4<f32>,
 @location(0) p:vec2<f32>,
 @location(1) @interpolate(flat) a:vec2<f32>,
 @location(2) @interpolate(flat) b:vec2<f32>,
 @location(3) @interpolate(flat) prev:vec2<f32>,
 @location(4) @interpolate(flat) next:vec2<f32>,
 @location(5) @interpolate(flat) color:vec4<f32>,
 @location(6) @interpolate(flat) params:vec4<f32>,
};
fn direction(d:vec2<f32>)->vec2<f32>{let l=length(d);if(l>1e-6){return d/l;}return vec2<f32>(1.,0.);}
@vertex fn vs(@builtin(vertex_index) v:u32,@location(0) a:vec2<f32>,@location(1) b:vec2<f32>,@location(2) prev:vec2<f32>,@location(3) next:vec2<f32>,@location(4) color:vec4<f32>,@location(5) params:vec4<f32>)->Out{
 let corners=array<vec2<f32>,6>(vec2<f32>(0.,-1.),vec2<f32>(1.,-1.),vec2<f32>(0.,1.),vec2<f32>(0.,1.),vec2<f32>(1.,-1.),vec2<f32>(1.,1.));
 let c=corners[v];
 let len=length(b-a);let dir=direction(b-a);let n=vec2<f32>(-dir.y,dir.x);
 let r=params.x+1.5;
 let p=a+dir*mix(-r,len+r,c.x)+n*(c.y*r);
 var o:Out;
 o.position=vec4<f32>(p.x/SIZE*2.-1.,1.-p.y/SIZE*2.,0.,1.);
 o.p=p;o.a=a;o.b=b;o.prev=prev;o.next=next;o.color=color;o.params=params;
 return o;
}
@fragment fn fs(i:Out)->@location(0) vec4<f32>{
 let len=length(i.b-i.a);let dir=direction(i.b-i.a);
 // Neighbouring segments split the plane at their joint's bisector, so every
 // pixel belongs to exactly one segment: round joins without double-blending
 // translucent lines. A hairpin falls back to the later segment's direction.
 if(any(i.prev!=i.a)){var h=direction(i.a-i.prev)+dir;if(length(h)<1e-4){h=dir;}if(dot(i.p-i.a,h)<0.){discard;}}
 if(any(i.next!=i.b)){let nd=direction(i.next-i.b);var h=dir+nd;if(length(h)<1e-4){h=nd;}if(dot(i.p-i.b,h)>=0.){discard;}}
 let t=clamp(dot(i.p-i.a,dir),0.,len);
 let dist=length(i.p-(i.a+dir*t));
 var alpha=clamp(i.params.x+0.5-dist,0.,1.);
 if(i.params.y>0.&&i.params.z>0.){
  // Dash pattern measured along the whole line, continuous across joints.
  let cycle=i.params.y+i.params.z;let along=i.params.w+t;let m=along-floor(along/cycle)*cycle;
  alpha*=clamp(clamp(i.params.y+0.5-m,0.,1.)+clamp(m-(cycle-0.5),0.,1.),0.,1.);
 }
 if(alpha<=0.){discard;}
 return vec4<f32>(i.color.rgb*i.color.a*alpha,i.color.a*alpha);
}
