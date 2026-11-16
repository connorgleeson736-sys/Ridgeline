// Te Araroa's current section status and alerts, from the Te Araroa Trust's own site
// data (www.teararoa.org.nz/wp-json/ta/v1/…, CC BY 4.0), which browsers cannot read
// from another site. Relayed at /api/te-araroa/status (every section's status and
// alert count) and /api/te-araroa/alerts/<section> (a section's alerts), cached at
// Cloudflare's edge for 10 minutes. Used by worker/site.js (the test copy) and
// functions/api/te-araroa/[[path]].js (Cloudflare Pages).
const API = 'https://www.teararoa.org.nz/wp-json/ta/v1';
const MAX_AGE = 600;
const SLUG = /^[a-z0-9-]{1,120}$/;

const text = (v, max) => (typeof v === 'string' ? v.slice(0, max) : '');
const json = (body, status, maxAge = 0) => new Response(JSON.stringify(body), {
  status,
  headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': maxAge ? `public, max-age=${maxAge}` : 'no-store' },
});

async function cached(key, make) {
  const cache = caches.default, request = new Request(`https://te-araroa.internal/${key}`);
  const hit = await cache.match(request);
  if (hit) return hit;
  const response = await make();
  if (response.status === 200) await cache.put(request, response.clone());
  return response;
}

async function read(url) {
  const r = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!r.ok) throw new Error(`Te Araroa ${r.status}`);
  return r.json();
}

export async function teAraroa(path) {
  if (path === 'status') {
    return cached('status', async () => {
      try {
        const o = await read(`${API}/overview`);
        const sections = {};
        for (const t of o?.tracks || []) if (SLUG.test(t?.slug || '')) sections[t.slug] = { status: text(t.status, 40), alerts: +t.alerts || 0 };
        return json({ sections }, 200, MAX_AGE);
      } catch { return json({ error: 'Te Araroa did not answer' }, 502); }
    });
  }
  const m = /^alerts\/([a-z0-9-]+)$/.exec(path || '');
  if (!m || !SLUG.test(m[1])) return json({ error: 'Not found' }, 404);
  return cached(`alerts-v2/${m[1]}`, async () => {
    try {
      const d = (await read(`${API}/track/${m[1]}`))?.data || {};
      const alerts = (Array.isArray(d.alerts) ? d.alerts : []).slice(0, 30).map((a) => ({
        title: text(a?.title, 300), status: text(a?.status, 40), type: text(a?.type, 80),
        from: Number.isFinite(+a?.fromKm) && a?.fromKm != null ? +a.fromKm : null, to: Number.isFinite(+a?.toKm) && a?.toKm != null ? +a.toKm : null,
        start: text(a?.startDate, 40), end: text(a?.endDate, 40),
        // Pictures in alerts are embedded images (often large): left out.
        description: (typeof a?.description === 'string' ? a.description : '').replace(/<img\b[^>]*>/gi, '').slice(0, 40000),
      }));
      return json({ alerts }, 200, MAX_AGE);
    } catch { return json({ error: 'Te Araroa did not answer' }, 502); }
  });
}
