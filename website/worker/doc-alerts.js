// DOC's current alerts for a walk, track or hut, by its DOC page id (a GUID), from
// the service DOC's own pages use (www.doc.govt.nz/api/alerts/guid/<id>). Browsers
// cannot read it from another site, so the site relays it at /api/doc-alerts/<id>,
// cached at Cloudflare's edge for a few minutes. Used by worker/site.js (the test
// copy) and functions/api/doc-alerts/[guid].js (Cloudflare Pages).
const GUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const MAX_AGE = 600;

const text = (v, max) => (typeof v === 'string' ? v.slice(0, max) : '');
const json = (body, status, maxAge = 0) => new Response(JSON.stringify(body), {
  status,
  headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': maxAge ? `public, max-age=${maxAge}` : 'no-store' },
});

export async function docAlerts(id) {
  const guid = String(id || '').toLowerCase();
  if (!GUID.test(guid)) return json({ error: 'Not a DOC page id' }, 400);
  const cache = caches.default, key = new Request(`https://doc-alerts.internal/${guid}`);
  const hit = await cache.match(key);
  if (hit) return hit;
  let r;
  try {
    r = await fetch(`https://www.doc.govt.nz/api/alerts/guid/${guid}`, { headers: { Accept: 'application/json' } });
  } catch {
    return json({ error: 'DOC did not answer' }, 502);
  }
  let list = [];
  // 204: no alerts. Anything that is not JSON (DOC's error page for an unknown id): none.
  if (r.status === 200 && /json/i.test(r.headers.get('Content-Type') || '')) {
    try { list = await r.json(); } catch { list = []; }
  } else if (r.status >= 500) return json({ error: `DOC ${r.status}` }, 502);
  const alerts = (Array.isArray(list) ? list : []).slice(0, 30).map((a) => ({
    summary: text(a?.summary, 400),
    description: text(a?.description, 30000),
    date: text(a?.displayDate, 80),
    note: text(a?.subText, 300),
  })).filter((a) => a.summary || a.description);
  const response = json({ alerts }, 200, MAX_AGE);
  await cache.put(key, response.clone());
  return response;
}
