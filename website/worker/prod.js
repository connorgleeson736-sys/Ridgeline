// The production site (razorbackmaps.co.nz). Pages, scripts, data and pictures are
// Workers static assets: served by Cloudflare without running this Worker (free,
// with _headers and _redirects applied). The Worker runs only for what is not a
// file: the live API routes, and the overview imagery and shading corrections for
// clients still asking this host (the site itself uses tiles.razorbackmaps.co.nz,
// R2 directly). Deployed by tools/deploy-prod.mjs.
import { docAlerts } from './doc-alerts.js';
import { teAraroa } from './te-araroa.js';
import { notify } from './notify.js';

const TILE = /^\/(tiles\/[0-9a-f]{64}\/\d+|ctiles\/[0-9a-f]{64}\/\d+|shade\/v1\/\d{1,2}\/\d{1,5}\/\d{1,5})\.webp$/;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    // One address: www and the workers.dev name go to the domain.
    if (url.hostname.startsWith('www.')) return Response.redirect(`https://${url.hostname.slice(4)}${url.pathname}${url.search}`, 301);
    if (url.pathname === '/api/notify') return request.method === 'POST' ? notify(request, env) : new Response('Method not allowed', { status: 405 });
    if (request.method !== 'GET' && request.method !== 'HEAD') return new Response('Method not allowed', { status: 405 });
    const path = decodeURIComponent(url.pathname);
    const alerts = /^\/api\/doc-alerts\/([^/]+)$/.exec(path);
    if (alerts) return docAlerts(alerts[1]);
    const ta = /^\/api\/te-araroa\/(.+)$/.exec(path);
    if (ta) return teAraroa(ta[1]);
    const tile = TILE.exec(path);
    if (tile && env.OVERVIEWS) {
      const cache = caches.default, hit = request.method === 'GET' ? await cache.match(request) : null;
      if (hit) return hit;
      const object = await env.OVERVIEWS.get(tile[0].slice(1));
      if (!object) return new Response('Not found', { status: 404 });
      const response = new Response(object.body, { headers: { 'Content-Type': 'image/webp', 'Cache-Control': 'public, max-age=31536000, immutable', 'Access-Control-Allow-Origin': '*', ETag: object.httpEtag } });
      if (request.method === 'GET') await cache.put(request, response.clone());
      return request.method === 'HEAD' ? new Response(null, { headers: response.headers }) : response;
    }
    return env.ASSETS.fetch(request);
  },
};
