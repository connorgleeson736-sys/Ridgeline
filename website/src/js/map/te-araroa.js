// Te Araroa, the trail the length of the country (data/te-araroa.json, from the
// Te Araroa Trust's official GPS files; see tools/te-araroa.mjs): its pieces in
// order, each with its kilometre range, and the kilometre markers. Loaded the
// first time the layer is on (about 120 kB).
let data = null;

export function load() {
  return data || (data = fetch('/data/te-araroa.json')
    .then((r) => { if (!r.ok) throw new Error(`Te Araroa ${r.status}`); return r.json(); })
    .then(decode)
    .catch((e) => { data = null; throw e; }));
}

function decode(j) {
  const pieces = j.pieces.map(([order, name, from, to, c], i) => {
    const line = [];
    let x = 0, y = 0;
    for (let k = 0; k + 1 < c.length; k += 2) { x += c[k]; y += c[k + 1]; line.push([x / 1e5, y / 1e5]); }
    return { i, order, name, from, to, line };
  });
  const km = [];
  for (let k = 0; k + 2 < j.km.length; k += 3) km.push([j.km[k], j.km[k + 1], j.km[k + 2]]);
  return { name: j.name, season: /(\d{4}-\d{2})/.exec(j.name || '')?.[1] || '', updated: j.updated, pieces, km, total: Math.max(0, ...pieces.map((p) => p.to || 0)) };
}

/** The trail's lines and kilometre markers, for the map's sources. */
export function features(d) {
  return {
    lines: { type: 'FeatureCollection', features: d.pieces.map((p) => ({ type: 'Feature', properties: { i: p.i, name: p.name, from: p.from, to: p.to }, geometry: { type: 'LineString', coordinates: p.line } })) },
    km: { type: 'FeatureCollection', features: d.km.map(([n, lon, lat]) => ({ type: 'Feature', properties: { km: n, label: `${n} km` }, geometry: { type: 'Point', coordinates: [lon, lat] } })) },
  };
}

export const PAGE = 'https://www.teararoa.org.nz/';
export const NOTES = 'https://www.teararoa.org.nz/notes-and-maps/';
export const ALERTS = 'https://www.teararoa.org.nz/trail-alerts/';
