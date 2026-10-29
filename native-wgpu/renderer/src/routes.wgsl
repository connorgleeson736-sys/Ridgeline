struct Scene { matrix:mat4x4<f32>, anchor:vec4<f32>, size:vec4<f32> };
@group(0) @binding(0) var<uniform> scene:Scene;
struct Out { @builtin(position) position:vec4<f32>, @location(0) color:vec4<f32>, @location(1) uv:vec2<f32>, @location(2) @interpolate(flat) marker:u32 };
fn project(p:vec3<f32>)->vec4<f32>{return scene.matrix*vec4<f32>(scene.anchor.x+p.x*scene.anchor.z,scene.anchor.y-p.y*scene.anchor.z,(p.z+1.-scene.size.x)*scene.anchor.w,1.);}
@vertex fn vs(@location(0) start:vec3<f32>,@location(1) end:vec3<f32>,@location(2) corner:vec3<f32>,@location(3) color:vec4<f32>)->Out {
 var a=project(start);var b=project(end);var o:Out;o.color=color;o.uv=vec2<f32>(corner.x*2.-1.,corner.y);o.marker=select(0u,1u,corner.z>0.);
 if(a.z<0.&&b.z<0.){o.position=vec4<f32>(2.,2.,2.,1.);return o;}
 if(a.z<0.){a=mix(a,b,-a.z/(b.z-a.z));}if(b.z<0.){b=mix(b,a,-b.z/(a.z-b.z));}
 var p=mix(a,b,corner.x);var offset:vec2<f32>;
 if(corner.z>0.){offset=o.uv*corner.z*scene.size.w;}
 else {let delta=(b.xy/b.w-a.xy/a.w)*scene.size.yz;let norm=vec2<f32>(-delta.y,delta.x)/max(length(delta),.00001);offset=norm*corner.y*1.6*scene.size.w;}
 p=vec4<f32>(p.xy+offset*2./scene.size.yz*p.w,p.zw);o.position=p;return o;
}
@fragment fn fs(i:Out)->@location(0) vec4<f32>{
 let edge=select(abs(i.uv.y),length(i.uv),i.marker==1u);let aa=max(fwidth(edge),.01);let alpha=1.-smoothstep(1.-aa,1.,edge);
 if(alpha<=0.){discard;}return vec4<f32>(i.color.rgb*i.color.a*alpha,i.color.a*alpha);
}
