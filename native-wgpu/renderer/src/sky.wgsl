// Clear-sky gradient behind the terrain: deep blue overhead to pale haze at
// the horizon, by each pixel's angle above the horizon (no roll in the camera).
struct Sky { params: vec4<f32>, stops: array<vec4<f32>, 7> };
@group(0) @binding(0) var<uniform> sky: Sky;
struct Out { @builtin(position) pos: vec4<f32>, @location(0) ndc: vec2<f32> };
@vertex fn vs(@builtin(vertex_index) i: u32) -> Out {
  var p = array<vec2<f32>, 3>(vec2<f32>(-1., -1.), vec2<f32>(3., -1.), vec2<f32>(-1., 3.));
  var o: Out;
  o.pos = vec4<f32>(p[i], 1., 1.);
  o.ndc = p[i];
  return o;
}
@fragment fn fs(i: Out) -> @location(0) vec4<f32> {
  // params.x: centre ray elevation (degrees); params.y: tan(vertical fov / 2).
  let e = sky.params.x + degrees(atan(i.ndc.y * sky.params.y));
  var c = sky.stops[0].rgb;
  for (var k = 1; k < 7; k++) {
    let a = sky.stops[k - 1];
    let b = sky.stops[k];
    if (e > a.w) { c = mix(a.rgb, b.rgb, clamp((e - a.w) / (b.w - a.w), 0., 1.)); }
  }
  return vec4<f32>(c, 1.);
}
