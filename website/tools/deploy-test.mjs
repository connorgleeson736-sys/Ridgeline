// Uploads dist/ to an R2 bucket and serves it with worker/site.js on the
// account's workers.dev address: a test copy before Cloudflare Pages.
//   NOINDEX=1 SITE_URL=https://<name>.<subdomain>.workers.dev OVERVIEW_URL=$SITE_URL node build.mjs
// (the Worker also serves the app's overview tiles from their bucket, edge-cached)
//   CLOUDFLARE_ACCOUNT_ID=… CLOUDFLARE_API_TOKEN=… node tools/deploy-test.mjs
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, extname } from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const here = fileURLToPath(new URL('..', import.meta.url));
const dist = join(here, 'dist');
const account = process.env.CLOUDFLARE_ACCOUNT_ID, token = process.env.CLOUDFLARE_API_TOKEN;
const bucket = process.env.SITE_BUCKET || 'bushline-site', script = process.env.SITE_WORKER || 'bushline-maps';
const api = `https://api.cloudflare.com/client/v4/accounts/${account}`;
const auth = { Authorization: `Bearer ${token}` };
const TYPES = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8', '.json': 'application/json', '.tsv': 'text/tab-separated-values; charset=utf-8', '.png': 'image/png', '.jpg': 'image/jpeg', '.webp': 'image/webp', '.svg': 'image/svg+xml', '.xml': 'application/xml', '.txt': 'text/plain; charset=utf-8', '.webmanifest': 'application/manifest+json' };
async function call(method, url, body, headers = {}) {
  for (let attempt = 0; ; attempt++) {
    const r = await fetch(url, { method, body, headers: { ...auth, ...headers } });
    if (r.ok) return r;
    // Retry server errors and rate limits (429), backing off.
    if (attempt >= 5 || (r.status < 500 && r.status !== 429)) throw new Error(`${method} ${url.replace(api, '')}: ${r.status} ${(await r.text()).slice(0, 300)}`);
    await new Promise((s) => setTimeout(s, 1500 * 2 ** attempt));
  }
}

const buckets = await (await call('GET', `${api}/r2/buckets`)).json();
if (!buckets.result.buckets.some((b) => b.name === bucket)) await call('POST', `${api}/r2/buckets`, JSON.stringify({ name: bucket }), { 'Content-Type': 'application/json' });

const files = [];
const walk = (d) => { for (const f of readdirSync(d)) { const p = join(d, f); if (statSync(p).isDirectory()) walk(p); else files.push(relative(dist, p).split('\\').join('/')); } };
walk(dist);
// Only files that changed since the last deploy (a manifest of hashes in the bucket).
const objectUrl = (key) => `${api}/r2/buckets/${bucket}/objects/${key.split('/').map(encodeURIComponent).join('/')}`;
let previous = {};
try { const r = await fetch(objectUrl('_deploy-manifest.json'), { headers: auth }); if (r.ok) previous = await r.json(); } catch { previous = {}; }
const manifest = Object.fromEntries(files.map((key) => [key, createHash('sha256').update(readFileSync(join(dist, key))).digest('hex')]));
const changed = files.filter((key) => previous[key] !== manifest[key]);
let next = 0;
await Promise.all(Array.from({ length: 4 }, async () => {
  while (next < changed.length) {
    const key = changed[next++];
    const type = TYPES[extname(key)] || 'application/octet-stream';
    await call('PUT', objectUrl(key), readFileSync(join(dist, key)), { 'Content-Type': type });
  }
}));
await call('PUT', objectUrl('_deploy-manifest.json'), JSON.stringify(manifest), { 'Content-Type': 'application/json' });

const source = readFileSync(join(here, 'worker/site.js'), 'utf8')
  .replace('__HEADERS__', JSON.stringify(readFileSync(join(dist, '_headers'), 'utf8')))
  .replace('__REDIRECTS__', JSON.stringify(readFileSync(join(dist, '_redirects'), 'utf8')));
const form = new FormData();
form.append('metadata', new Blob([JSON.stringify({ main_module: 'site.js', compatibility_date: '2026-10-01', bindings: [{ type: 'r2_bucket', name: 'SITE', bucket_name: bucket }, { type: 'r2_bucket', name: 'OVERVIEWS', bucket_name: process.env.OVERVIEW_BUCKET || 'ridgeline-overviews' }] })], { type: 'application/json' }));
form.append('site.js', new Blob([source], { type: 'application/javascript+module' }), 'site.js');
form.append('doc-alerts.js', new Blob([readFileSync(join(here, 'worker/doc-alerts.js'), 'utf8')], { type: 'application/javascript+module' }), 'doc-alerts.js');
await call('PUT', `${api}/workers/scripts/${script}`, form);
await call('POST', `${api}/workers/scripts/${script}/subdomain`, JSON.stringify({ enabled: true, previews_enabled: false }), { 'Content-Type': 'application/json' });
const sub = (await (await call('GET', `${api}/workers/subdomain`)).json()).result.subdomain;
console.log(JSON.stringify({ files: files.length, uploaded: changed.length, url: `https://${script}.${sub}.workers.dev/` }));
