// WebGPU clip depth [0,1]. Elevation/normal rows increase south; imagery is
// decoded north-up. All uniforms occupy aligned vec4/matrix fields (144 bytes).
export const terrainShader=`
struct Params {
  viewProjection: mat4x4<f32>,
  placement: vec4<f32>,
  geo: vec4<f32>,
  gridInfo: vec4<f32>,
  imageUV: vec4<f32>,
  appearance: vec4<f32>
};
@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var elevation: texture_2d<f32>;
@group(0) @binding(2) var normals: texture_2d<f32>;
@group(0) @binding(3) var photograph: texture_2d<f32>;
@group(0) @binding(4) var linearSampler: sampler;
struct VertexOutput {
 @builtin(position) position: vec4<f32>,
 @location(0) tileUV: vec2<f32>
};
// Every selected power-of-two grid aligns exactly with a measured DEM node.
// A single unfiltered read avoids four redundant bilinear fetches per vertex.
fn heightAt(uv: vec2<f32>) -> f32 {
 let size=vec2<i32>(textureDimensions(elevation));
 let pixel=vec2<i32>(round(clamp(uv,vec2<f32>(0.),vec2<f32>(1.))*vec2<f32>(size-vec2<i32>(1))));
 return textureLoad(elevation,pixel,0).r;
}
@vertex fn vertexMain(@location(0) grid:vec3<f32>) -> VertexOutput {
 let uv=grid.xy+params.gridInfo.xy;
 let latitude=3.141592653589793*(1.-2.*(params.geo.y+uv.y*params.geo.z));
 let conformal=(exp(latitude)+exp(-latitude))*.5;
 let height=(heightAt(uv)-grid.z*params.gridInfo.z)*conformal*params.placement.w*params.geo.w;
 let position=vec3<f32>(params.placement.x+(uv.x-.5)*params.placement.z,height,params.placement.y+(uv.y-.5)*params.placement.z);
 var out:VertexOutput;out.position=params.viewProjection*vec4<f32>(position,1.);out.tileUV=uv;return out;
}
fn encodeSRGB(c:vec3<f32>) -> vec3<f32> {return select(1.055*pow(max(c,vec3<f32>(0.)),vec3<f32>(1./2.4))-vec3<f32>(.055),c*12.92,c<=vec3<f32>(.0031308));}
@fragment fn fragmentMain(in:VertexOutput) -> @location(0) vec4<f32> {
 let uv=in.tileUV;
 let photoUV=vec2<f32>(uv.x*params.imageUV.x+params.imageUV.z,1.-((1.-uv.y)*params.imageUV.y+params.imageUV.w));
 let photo=textureSample(photograph,linearSampler,photoUV).rgb;
 let size=vec2<f32>(textureDimensions(normals));
 let normalUV=(uv*(size-vec2<f32>(1.))+vec2<f32>(.5))/size;
 let normal=textureSample(normals,linearSampler,normalUV).rgb*2.-vec3<f32>(1.);
 let adjusted=normalize(vec3<f32>(normal.x*params.geo.w,normal.y,normal.z*params.geo.w));
 let light=max(0.,dot(adjusted,normalize(vec3<f32>(-.35,.82,.45))))*.16+.84;
 let lum=dot(photo,vec3<f32>(.2126,.7152,.0722));
 let natural=mix(vec3<f32>(lum),photo,.92);
 return vec4<f32>(encodeSRGB(mix(photo,natural,params.appearance.x)*mix(1.,light,params.appearance.y)),1.);
}`;
export const terrainLayout={attributes:[{name:'grid',location:0,type:'vec3<f32>'}],bindings:[{name:'params',type:'uniform',group:0,location:0,minBindingSize:144},{name:'elevation',type:'texture',group:0,location:1,sampleType:'unfilterable-float'},{name:'normals',type:'texture',group:0,location:2},{name:'photograph',type:'texture',group:0,location:3},{name:'linearSampler',type:'sampler',group:0,location:4}]};
