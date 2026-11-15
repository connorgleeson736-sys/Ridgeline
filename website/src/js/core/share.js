// Routes in a link: points as an encoded polyline (precision 5, about a metre),
// which flags each point as tapped or following a track, and the name.
// #r=<name>~<polyline>~<flags>. The Android app opens the same link.

function encodeNumber(n, out) {
  let v = n < 0 ? ~(n << 1) : n << 1;
  while (v >= 0x20) { out.push(String.fromCharCode((0x20 | (v & 0x1f)) + 63)); v >>>= 5; }
  out.push(String.fromCharCode(v + 63));
}
export function encodePolyline(points) {
  const out = [];
  let lat = 0, lon = 0;
  for (const [x, y] of points) {
    const la = Math.round(y * 1e5), lo = Math.round(x * 1e5);
    encodeNumber(la - lat, out); encodeNumber(lo - lon, out);
    lat = la; lon = lo;
  }
  return out.join('');
}
export function decodePolyline(text) {
  const out = [];
  let i = 0, lat = 0, lon = 0;
  const next = () => {
    let shift = 0, result = 0, b;
    do { if (i >= text.length) throw new Error('Bad polyline'); b = text.charCodeAt(i++) - 63; result |= (b & 0x1f) << shift; shift += 5; } while (b >= 0x20 && shift < 35);
    return result & 1 ? ~(result >> 1) : result >> 1;
  };
  while (i < text.length) { lat += next(); lon += next(); out.push([lon / 1e5, lat / 1e5]); }
  return out;
}

/** route: {name, points: [[lon,lat]], waypoints: [index of tapped points]} */
export function encodeRoute(route) {
  const tapped = new Set(route.waypoints || []);
  const flags = route.points.map((_, i) => (tapped.has(i) ? '1' : '0')).join('');
  // Flags packed 6 per character.
  let packed = '';
  for (let i = 0; i < flags.length; i += 6) packed += String.fromCharCode(48 + parseInt(flags.slice(i, i + 6).padEnd(6, '0'), 2));
  return `r=${encodeURIComponent((route.name || 'Route').slice(0, 80))}~${encodePolyline(route.points)}~${encodeURIComponent(packed)}`;
}
export function decodeRoute(hash) {
  const m = /(?:^|[#&])r=([^~]*)~([^~]+)~([^&]*)/.exec(hash);
  if (!m) return null;
  try {
    const points = decodePolyline(decodeURIComponent(m[2]));
    if (points.length < 2 || points.length > 20000) return null;
    if (points.some(([x, y]) => !(x >= 160 && x <= 185 && y >= -53 && y <= -28))) return null;
    const packed = decodeURIComponent(m[3]);
    let bits = '';
    for (const c of packed) bits += (c.charCodeAt(0) - 48).toString(2).padStart(6, '0');
    const waypoints = points.map((_, i) => i).filter((i) => bits[i] === '1');
    if (!waypoints.includes(0)) waypoints.unshift(0);
    if (!waypoints.includes(points.length - 1)) waypoints.push(points.length - 1);
    return { name: decodeURIComponent(m[1]) || 'Shared route', points, waypoints };
  } catch { return null; }
}
