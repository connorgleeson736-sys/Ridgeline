struct Scene { matrix:mat4x4<f32>, anchor:vec4<f32>, size:vec4<f32> };
@group(0) @binding(0) var<uniform> scene:Scene;
struct Out { @builtin(position) position:vec4<f32>, @location(0) color:vec4<f32>, @location(1) uv:vec2<f32>, @location(2) @interpolate(flat) radius:f32, @location(3) @interpolate(flat) length:f32, @location(4) @interpolate(flat) dash:vec3<f32>, @location(5) @interpolate(flat) worldLength:f32 };
fn project(p:vec3<f32>)->vec4<f32>{return scene.matrix*vec4<f32>(scene.anchor.x+p.x*scene.anchor.z,scene.anchor.y-p.y*scene.anchor.z,(p.z+1.-scene.size.x)*scene.anchor.w,1.);}
fn build(start:vec3<f32>,end:vec3<f32>,corner:vec3<f32>,color:vec4<f32>,style:vec3<f32>)->Out {
 var a=project(start);var b=project(end);var o:Out;o.color=color;
 let radius=max(abs(corner.z),0.1)*scene.size.w;let extent=radius+1.;o.radius=radius;o.length=0.;o.dash=vec3<f32>(0.);o.worldLength=length(end.xy-start.xy);o.uv=vec2<f32>(0.);
 if(a.z<0.&&b.z<0.){o.position=vec4<f32>(2.,2.,2.,1.);return o;}
 if(a.z<0.){a=mix(a,b,-a.z/(b.z-a.z));}if(b.z<0.){b=mix(b,a,-b.z/(a.z-b.z));}
 var p=mix(a,b,corner.x);var offset:vec2<f32>;
 if(corner.z>0.){o.uv=vec2<f32>(corner.x*2.-1.,corner.y)*extent;offset=o.uv;}
 else {let delta=(b.xy/b.w-a.xy/a.w)*scene.size.yz*0.5;let len=max(length(delta),0.00001);let direction=delta/len;let norm=vec2<f32>(-direction.y,direction.x);o.length=len;o.uv=vec2<f32>((corner.x-0.5)*(len+2.*extent),corner.y*extent);offset=norm*corner.y*extent+direction*(corner.x*2.-1.)*extent;o.dash=vec3<f32>(style.xy*scene.size.w,style.z);}
 p=vec4<f32>(p.xy+offset*2./scene.size.yz*p.w,p.zw);o.position=p;o.uv*=p.w;return o;
}
// Coverage of one dash cycle at a reference scale; [pixel] is one screen pixel
// in reference units, so the antialiased edge stays one screen pixel wide.
fn dashCoverage(along:f32,dash:vec2<f32>,pixel:f32)->f32{let cycle=dash.x+dash.y;let position=along-floor(along/cycle)*cycle;return 1.-smoothstep(dash.x-0.5*pixel,dash.x+0.5*pixel,position);}
@fragment fn fs(i:Out)->@location(0) vec4<f32>{
 // Recover linear screen coordinates with no noperspective GLES extension.
 let uv=i.uv*i.position.w;
 let edge=length(vec2<f32>(max(abs(uv.x)-i.length*0.5,0.),uv.y))/i.radius;let aa=max(fwidth(edge),.01);var alpha=1.-smoothstep(1.-aa*0.5,1.+aa*0.5,edge);
 if(i.dash.x>0.&&i.dash.y>0.){
  // Like MapLibre line-dasharray: the pattern is fixed to the ground at whole
  // logical zoom levels and scales with the map in between, crossfading to the
  // next level. Dashes no longer slide along the line while zooming.
  let ground=(uv.x+i.length*0.5)/max(i.length,0.00001)*i.worldLength+i.dash.z;
  let logical=log2(scene.anchor.z/512.)-log2(scene.size.w);let base=floor(logical);let blend=logical-base;
  let reference=512.*exp2(base)*scene.size.w;let pixel=reference/scene.anchor.z;
  alpha*=mix(dashCoverage(ground*reference,i.dash.xy,pixel),dashCoverage(ground*reference*2.,i.dash.xy,pixel*2.),blend);
 }
 if(alpha<=0.){discard;}return vec4<f32>(i.color.rgb*i.color.a*alpha,i.color.a*alpha);
}

@vertex fn vs(@location(0) start:vec3<f32>,@location(1) end:vec3<f32>,@location(2) corner:vec3<f32>,@location(3) color:vec4<f32>,@location(4) style:vec3<f32>)->Out{return build(start,end,corner,color,style);}
@vertex fn vs_instanced(@builtin(vertex_index) vertex:u32,@location(0) start:vec3<f32>,@location(1) end:vec3<f32>,@location(2) corner:vec3<f32>,@location(3) color:vec4<f32>,@location(4) style:vec3<f32>)->Out{
 let corners=array<vec2<f32>,6>(vec2<f32>(0.,-1.),vec2<f32>(1.,-1.),vec2<f32>(0.,1.),vec2<f32>(0.,1.),vec2<f32>(1.,-1.),vec2<f32>(1.,1.));return build(start,end,vec3<f32>(corners[vertex],corner.z),color,style);
}
