// Uploads dist/ to an R2 bucket and serves it with worker/site.js on the
// account's workers.dev address: a test copy before Cloudflare Pages.
//   NOINDEX=1 SITE_URL=https://<name>.<subdomain>.workers.dev node build.mjs
//   CLOUDFLARE_ACCOUNT_ID=… CLOUDFLARE_API_TOKEN=… node tools/deploy-test.mjs
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, extname } from 'node:path';
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
    if (attempt >= 3 || r.status < 500) throw new Error(`${method} ${url.replace(api, '')}: ${r.status} ${(await r.text()).slice(0, 300)}`);
    await new Promise((s) => setTimeout(s, 1000 * 2 ** attempt));
  }
}

const buckets = await (await call('GET', `${api}/r2/buckets`)).json();
if (!buckets.result.buckets.some((b) => b.name === bucket)) await call('POST', `${api}/r2/buckets`, JSON.stringify({ name: bucket }), { 'Content-Type': 'application/json' });

const files = [];
const walk = (d) => { for (const f of readdirSync(d)) { const p = join(d, f); if (statSync(p).isDirectory()) walk(p); else files.push(relative(dist, p).split('\\').join('/')); } };
walk(dist);
let next = 0;
await Promise.all(Array.from({ length: 6 }, async () => {
  while (next < files.length) {
    const key = files[next++];
    const type = TYPES[extname(key)] || 'application/octet-stream';
    await call('PUT', `${api}/r2/buckets/${bucket}/objects/${key.split('/').map(encodeURIComponent).join('/')}`, readFileSync(join(dist, key)), { 'Content-Type': type });
  }
}));

const source = readFileSync(join(here, 'worker/site.js'), 'utf8')
  .replace('__HEADERS__', JSON.stringify(readFileSync(join(dist, '_headers'), 'utf8')))
  .replace('__REDIRECTS__', JSON.stringify(readFileSync(join(dist, '_redirects'), 'utf8')));
const form = new FormData();
form.append('metadata', new Blob([JSON.stringify({ main_module: 'site.js', compatibility_date: '2026-10-01', bindings: [{ type: 'r2_bucket', name: 'SITE', bucket_name: bucket }] })], { type: 'application/json' }));
form.append('site.js', new Blob([source], { type: 'application/javascript+module' }), 'site.js');
await call('PUT', `${api}/workers/scripts/${script}`, form);
await call('POST', `${api}/workers/scripts/${script}/subdomain`, JSON.stringify({ enabled: true, previews_enabled: false }), { 'Content-Type': 'application/json' });
const sub = (await (await call('GET', `${api}/workers/subdomain`)).json()).result.subdomain;
console.log(JSON.stringify({ files: files.length, url: `https://${script}.${sub}.workers.dev/` }));
