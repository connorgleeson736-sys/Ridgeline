// The Department of Conservation's open data (ArcGIS, CC BY 4.0), queried
// live as the app does: walks, their lines, huts, tracks and hunting areas.
import { guidOf, names, characteristics } from '../core/walks.js';

const ORG = 'https://services1.arcgis.com/3JjYDyG3oajxU6HO/ArcGIS/rest/services/';
const f6 = (v) => v.toFixed(6);
const quote = (s) => `'${String(s).replace(/'/g, "''")}'`;
const box = (b) => `&geometry=${b.map(f6).join(',')}&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects`;
function query(service, where, fields, geometry, extra = '', format = 'json') {
  return `${ORG}${service}/FeatureServer/0/query?where=${encodeURIComponent(where)}&outFields=${encodeURIComponent(fields)}&returnGeometry=${geometry}&outSR=4326${extra}&f=${format}`;
}
async function get(url, signal) {
  const r = await fetch(url, { signal });
  if (!r.ok) throw new Error(`DOC ${r.status}`);
  const j = await r.json();
  if (j.error) throw new Error(j.error.message || 'DOC error');
  return j;
}
const text = (v) => (v == null ? '' : String(v).trim());
const point = (g) => (g && isFinite(g.x) && isFinite(g.y) ? [g.x, g.y] : null);
const pathsOf = (g) => (g?.paths || []).filter((p) => p.length >= 2);

// ---- Walks ----
let walkList = null;
/** Every DOC walk (about 1,350), by line id: {guid, name, difficulty, time, page}. */
export function walks() {
  return walkList || (walkList = get(query('DOC_Walking_Experiences', '1=1', 'name,difficulty,completionTime,walkingAndTrampingWebPage', false)).then((j) => {
    const out = {};
    for (const f of j.features || []) {
      const a = f.attributes, page = text(a.walkingAndTrampingWebPage), guid = guidOf(page);
      if (guid) out[guid] = { guid, name: text(a.name), difficulty: text(a.difficulty), time: text(a.completionTime), page };
    }
    return out;
  }).catch((e) => { walkList = null; throw e; }));
}
const WALK_FIELDS = 'name,introduction,difficulty,completionTime,introductionThumbnail,walkingAndTrampingWebPage,hasAlerts';
const parseWalk = (f) => ({ name: text(f.attributes.name), introduction: text(f.attributes.introduction), difficulty: text(f.attributes.difficulty), time: text(f.attributes.completionTime), thumbnail: text(f.attributes.introductionThumbnail), page: text(f.attributes.walkingAndTrampingWebPage), alerts: text(f.attributes.hasAlerts), point: point(f.geometry) });
export async function walkByGuid(guid, signal) {
  const j = await get(query('DOC_Walking_Experiences', `walkingAndTrampingWebPage LIKE ${quote('%' + guid.replace(/-/g, '').toLowerCase() + '%')}`, WALK_FIELDS, true), signal);
  return (j.features || []).map(parseWalk)[0] || null;
}
/** DOC's walks with the tapped track's name near it. */
export async function walksNamed(name, lon, lat, signal) {
  const r = 0.35;
  const where = `UPPER(name) IN (${names(name).map((n) => quote(n.toUpperCase())).join(',')})`;
  const j = await get(query('DOC_Walking_Experiences', where, WALK_FIELDS, true, box([lon - r, lat - r, lon + r, lat + r])), signal);
  return (j.features || []).map(parseWalk);
}
/** DOC's walk lines crossing box [w,s,e,n], generalised to about 10 m. */
export async function linesNear(b, signal) {
  const j = await get(query('DOC_WebsiteWalkingandTrampingLns_DTO', '1=1', 'EPISERVERGUID,Shape__Length', true, box(b) + '&maxAllowableOffset=0.0001'), signal);
  return (j.features || []).map((f) => ({ guid: text(f.attributes.EPISERVERGUID).toLowerCase(), meters: +f.attributes.Shape__Length || 0, paths: pathsOf(f.geometry) })).filter((l) => l.guid && l.paths.length);
}
/** One walk's own line, finely (about 2 m). */
export async function walkLine(guid, signal) {
  const j = await get(query('DOC_WebsiteWalkingandTrampingLns_DTO', `EPISERVERGUID=${quote(guid)}`, 'EPISERVERGUID', true, '&maxAllowableOffset=0.00002'), signal);
  return (j.features || []).flatMap((f) => pathsOf(f.geometry));
}

// ---- Tracks (DOC's assets, by the map's name) ----
export async function tracksNamed(name, lon, lat, signal) {
  const r = 0.35;
  const where = `UPPER(TechObjectName) IN (${names(name).map((n) => quote(n.toUpperCase())).join(',')})`;
  const j = await get(query('DOC_Tracks_Open_Data', where, '*', true, box([lon - r, lat - r, lon + r, lat + r]) + '&maxAllowableOffset=0.0001'), signal);
  return (j.features || []).map((f) => {
    const a = f.attributes, c = characteristics(a);
    return { name: text(a.TechObjectName), status: c.USER_STATUS || '', category: c.WEB_CATEGORY_TRACK || '', dogs: c.WEB_DOGS_ALLOWED || '', type: c.WEB_TRACK_TYPE || '', duration: c.WEB_WALKING_DURATION || '', meters: +a.Shape__Length || 0, paths: pathsOf(f.geometry) };
  });
}

// ---- Huts ----
/** DOC's huts in box [w,s,e,n] (for the huts along a walk). */
export async function hutsIn(b, signal) {
  const j = await get(query('DOC_Huts_Open_Data', '1=1', '*', true, box(b)), signal);
  return (j.features || []).map((f) => {
    const a = f.attributes, c = characteristics(a), p = point(f.geometry);
    if (!p || /^no$/i.test(c.PUBLIC_USE || '')) return null;
    return { name: text(a.TechObjectName), category: c.WEB_CATEGORY_HUT || text(a.SubObjectType).replace(/ Hut$/, ''), bunks: c.WEB_HUT_BUNKS || '', status: c.USER_STATUS || '', point: p };
  }).filter(Boolean);
}
const HUT_ASSET_FIELDS = 'TechObjectName,SubObjectType,' + Array.from({ length: 8 }, (_, i) => `CharName${i + 1},CharValue${i + 1}`).join(',');
/** Hut records about 1 km around a tapped hut: DOC's assets and its hut pages. */
export async function hutDetails(lon, lat, signal) {
  const r = 0.012, b = [lon - r, lat - r, lon + r, lat + r];
  const [assets, pages] = await Promise.all([
    get(query('DOC_Huts_DTO', '1=1', HUT_ASSET_FIELDS, true, box(b)), signal).catch(() => ({ features: [] })),
    get(query('DOC_Huts', '1=1', 'name,place,bookable,facilities,introductionThumbnail,staticLink', true, box(b)), signal).catch(() => ({ features: [] })),
  ]);
  return {
    assets: (assets.features || []).map((f) => {
      const a = f.attributes, c = characteristics(a), p = point(f.geometry);
      if (!p || /^no$/i.test(c.PUBLIC_USE || '')) return null;
      return { name: text(a.TechObjectName), category: c.WEB_CATEGORY_HUT || text(a.SubObjectType).replace(/ Hut$/, ''), bunks: c.WEB_HUT_BUNKS || '', roadEnd: c.WEB_WALK_ROADEND || '', facilities: c.WEB_FACILITIES_HUT || '', status: c.USER_STATUS || '', point: p };
    }).filter(Boolean),
    pages: (pages.features || []).map((f) => {
      const a = f.attributes, p = point(f.geometry);
      if (!p) return null;
      return { name: text(a.name), place: text(a.place), bookable: /^yes$/i.test(text(a.bookable)), facilities: text(a.facilities).split(/,\s+(?=[A-Z])/).map((s) => s.trim()).filter(Boolean), thumbnail: text(a.introductionThumbnail), page: text(a.staticLink), point: p };
    }).filter(Boolean),
  };
}

// ---- Hunting permit areas (permitted only, as DOC asks for external use) ----
export async function huntingIn(b, tolerance, signal) {
  const url = query('DOC_Recreational_Hunting_Areas', "HuntStatus='Permitted'", 'OBJECTID,HuntBlockName,PermitArea,BlockType,HuntingWebPage,Ha', true, box(b) + `&maxAllowableOffset=${tolerance}`, 'geojson');
  const r = await fetch(url, { signal });
  if (!r.ok) throw new Error(`DOC ${r.status}`);
  const j = await r.json();
  return (j.features || []).filter((f) => f.geometry);
}

// ---- Current alerts (DOC's own alert service, relayed by the site) ----
const alertCache = new Map();
/** DOC's current alerts for a walk, track or hut page id: [{summary, description (HTML),
 * date, note}], [] for none, null when they could not be read. Kept for 5 minutes. */
export function alerts(guid) {
  const hit = alertCache.get(guid);
  if (hit && hit.until > Date.now()) return hit.promise;
  const promise = fetch(`/api/doc-alerts/${encodeURIComponent(guid)}`)
    .then((r) => (r.ok ? r.json() : null))
    .then((j) => (Array.isArray(j?.alerts) ? j.alerts : null))
    .catch(() => null);
  alertCache.set(guid, { promise, until: Date.now() + 5 * 60_000 });
  promise.then((v) => { if (v == null) alertCache.delete(guid); });
  return promise;
}
