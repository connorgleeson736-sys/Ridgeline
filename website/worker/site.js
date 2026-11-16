// Serves the built site (dist/) from an R2 bucket on a workers.dev address, for
// testing before the site moves to Cloudflare Pages: index pages, the 404 page,
// redirects and the headers that Pages would apply from _headers/_redirects.
// HEADERS and REDIRECTS are filled in by tools/deploy-test.mjs.
import { docAlerts } from './doc-alerts.js';

const HEADERS = __HEADERS__;
const REDIRECTS = __REDIRECTS__;

function rules(text) {
  const out = [];
  let current = null;
  for (const line of text.split('\n')) {
    if (!line.trim()) continue;
    if (!/^\s/.test(line)) { current = { pattern: line.trim(), headers: [] }; out.push(current); }
    else if (current) { const i = line.indexOf(':'); current.headers.push([line.slice(0, i).trim(), line.slice(i + 1).trim()]); }
  }
  return out;
}
const headerRules = rules(HEADERS);
const matches = (pattern, path) => (pattern.endsWith('/*') ? path.startsWith(pattern.slice(0, -1)) : path === pattern);
const redirects = REDIRECTS.split('\n').map((l) => l.trim().split(/\s+/)).filter((r) => r.length >= 2);

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== 'GET' && request.method !== 'HEAD') return new Response('Method not allowed', { status: 405 });
    let path = decodeURIComponent(url.pathname);
    // DOC's current alerts (as functions/api/doc-alerts on Pages).
    const alerts = /^\/api\/doc-alerts\/([^/]+)$/.exec(path);
    if (alerts && request.method === 'GET') return docAlerts(alerts[1]);
    // The app's overview imagery, from its bucket, cached at Cloudflare's edge
    // (r2.dev is uncached and rate-limited): /tiles/<pack>/<offset>.webp.
    if (env.OVERVIEWS && /^\/tiles\/[0-9a-f]{64}\/\d+\.webp$/.test(path)) {
      const cache = caches.default, get = request.method === 'GET', hit = get ? await cache.match(request) : null;
      if (hit) return hit;
      const object = await env.OVERVIEWS.get(path.slice(1));
      if (!object) return new Response('Not found', { status: 404 });
      const response = new Response(object.body, { headers: { 'Content-Type': 'image/webp', 'Cache-Control': 'public, max-age=31536000, immutable', 'Access-Control-Allow-Origin': '*', ETag: object.httpEtag } });
      if (get) await cache.put(request, response.clone());
      return request.method === 'HEAD' ? new Response(null, { headers: response.headers }) : response;
    }
    for (const [from, to, status] of redirects) if (path === from) return Response.redirect(new URL(to, url).href, +(status || 301));
    let key = path.replace(/^\//, '');
    if (key === '' || key.endsWith('/')) key += 'index.html';
    else if (!/\.[a-z0-9]+$/i.test(key)) {
      // "/map" → "/map/" when it is a page folder.
      if (await env.SITE.head(`${key}/index.html`)) return Response.redirect(`${url.origin}/${key}/${url.search}`, 301);
    }
    let object = key.startsWith('_') ? null : await env.SITE.get(key);
    let status = 200;
    if (!object) { object = await env.SITE.get('404.html'); status = 404; path = '/404.html'; }
    if (!object) return new Response('Not found', { status: 404 });
    const headers = new Headers();
    object.writeHttpMetadata(headers);
    headers.set('ETag', object.httpEtag);
    if (!headers.has('Cache-Control')) headers.set('Cache-Control', 'public, max-age=0, must-revalidate');
    for (const rule of headerRules) if (matches(rule.pattern, path)) for (const [k, v] of rule.headers) headers.set(k, v);
    if (status === 200 && request.headers.get('If-None-Match') === object.httpEtag) return new Response(null, { status: 304, headers });
    return new Response(request.method === 'HEAD' ? null : object.body, { status, headers });
  },
};
