struct Scene { matrix:mat4x4<f32>, anchor:vec4<f32>, size:vec4<f32> };
@group(0) @binding(0) var<uniform> scene:Scene;
struct Out { @builtin(position) position:vec4<f32>, @location(0) color:vec4<f32>, @location(1) uv:vec2<f32>, @location(2) @interpolate(flat) radius:f32, @location(3) @interpolate(flat) length:f32, @location(4) @interpolate(flat) dash:vec3<f32> };
fn project(p:vec3<f32>)->vec4<f32>{return scene.matrix*vec4<f32>(scene.anchor.x+p.x*scene.anchor.z,scene.anchor.y-p.y*scene.anchor.z,(p.z+1.-scene.size.x)*scene.anchor.w,1.);}
fn build(start:vec3<f32>,end:vec3<f32>,corner:vec3<f32>,color:vec4<f32>,style:vec3<f32>)->Out {
 var a=project(start);var b=project(end);var o:Out;o.color=color;
 let radius=max(abs(corner.z),0.1)*scene.size.w;o.radius=radius;o.length=0.;o.dash=vec3<f32>(0.);o.uv=vec2<f32>(0.);
 if(a.z<0.&&b.z<0.){o.position=vec4<f32>(2.,2.,2.,1.);return o;}
 if(a.z<0.){a=mix(a,b,-a.z/(b.z-a.z));}if(b.z<0.){b=mix(b,a,-b.z/(a.z-b.z));}
 var p=mix(a,b,corner.x);var offset:vec2<f32>;
 if(corner.z>0.){o.uv=vec2<f32>(corner.x*2.-1.,corner.y)*radius;offset=o.uv;}
 else {let delta=(b.xy/b.w-a.xy/a.w)*scene.size.yz*0.5;let len=max(length(delta),0.00001);let direction=delta/len;let norm=vec2<f32>(-direction.y,direction.x);o.length=len;o.uv=vec2<f32>((corner.x-0.5)*(len+2.*radius),corner.y*radius);offset=norm*corner.y*radius+direction*(corner.x*2.-1.)*radius;o.dash=vec3<f32>(style.xy*scene.size.w,style.z/max(length(end.xy-start.xy),0.000000000001)*len);}
 p=vec4<f32>(p.xy+offset*2./scene.size.yz*p.w,p.zw);o.position=p;return o;
}
@fragment fn fs(i:Out)->@location(0) vec4<f32>{
 let edge=length(vec2<f32>(max(abs(i.uv.x)-i.length*0.5,0.),i.uv.y))/i.radius;let aa=max(fwidth(edge),.01);var alpha=1.-smoothstep(1.-aa,1.,edge);
 if(i.dash.x>0.&&i.dash.y>0.){let along=i.uv.x+i.length*0.5+i.dash.z;let cycle=i.dash.x+i.dash.y;let position=along-floor(along/cycle)*cycle;alpha*=1.-smoothstep(i.dash.x-0.5,i.dash.x+0.5,position);}
 if(alpha<=0.){discard;}return vec4<f32>(i.color.rgb*i.color.a*alpha,i.color.a*alpha);
}

@vertex fn vs(@location(0) start:vec3<f32>,@location(1) end:vec3<f32>,@location(2) corner:vec3<f32>,@location(3) color:vec4<f32>,@location(4) style:vec3<f32>)->Out{return build(start,end,corner,color,style);}
@vertex fn vs_instanced(@builtin(vertex_index) vertex:u32,@location(0) start:vec3<f32>,@location(1) end:vec3<f32>,@location(2) corner:vec3<f32>,@location(3) color:vec4<f32>,@location(4) style:vec3<f32>)->Out{
 let corners=array<vec2<f32>,6>(vec2<f32>(0.,-1.),vec2<f32>(1.,-1.),vec2<f32>(0.,1.),vec2<f32>(0.,1.),vec2<f32>(1.,-1.),vec2<f32>(1.,1.));return build(start,end,vec3<f32>(corners[vertex],corner.z),color,style);
}
