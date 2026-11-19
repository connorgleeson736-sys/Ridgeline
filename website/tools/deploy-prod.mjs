// Deploys dist/ to the production site: Workers static assets plus worker/prod.js,
// on the custom domains, with the overview imagery's bucket on its own domain.
//   NODE_ENV=production SITE_URL=https://razorbackmaps.co.nz OVERVIEW_URL=https://tiles.razorbackmaps.co.nz node build.mjs
//   CLOUDFLARE_ACCOUNT_ID=… CLOUDFLARE_API_TOKEN=… node tools/deploy-prod.mjs
// The test copy (tools/deploy-test.mjs, workers.dev) is separate and unaffected.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, extname } from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const here = fileURLToPath(new URL('..', import.meta.url));
const dist = join(here, 'dist');
const account = process.env.CLOUDFLARE_ACCOUNT_ID, token = process.env.CLOUDFLARE_API_TOKEN;
if (!account || !token) throw new Error('Set CLOUDFLARE_ACCOUNT_ID and CLOUDFLARE_API_TOKEN');
const script = process.env.SITE_WORKER || 'razorback-maps';
const domain = process.env.SITE_DOMAIN || 'razorbackmaps.co.nz';
const tilesDomain = process.env.TILES_DOMAIN || `tiles.${domain}`;
const overviewBucket = process.env.OVERVIEW_BUCKET || 'ridgeline-overviews';
const signups = process.env.SIGNUP_BUCKET || 'razorback-signups';
const api = `https://api.cloudflare.com/client/v4/accounts/${account}`;
const auth = { Authorization: `Bearer ${token}` };

async function call(method, url, body, headers = {}, bearer = auth) {
  for (let attempt = 0; ; attempt++) {
    let r;
    try { r = await fetch(url, { method, body, headers: { ...bearer, ...headers } });
    } catch (e) { if (attempt >= 5) throw e; await new Promise((s) => setTimeout(s, 1500 * 2 ** attempt)); continue; } // dropped connection: retry
    if (r.ok) return r;
    if (attempt >= 5 || (r.status < 500 && r.status !== 429)) throw new Error(`${method} ${url.replace(api, '')}: ${r.status} ${(await r.text()).slice(0, 400)}`);
    await new Promise((s) => setTimeout(s, 1500 * 2 ** attempt));
  }
}
const json = async (r) => (await r.json()).result;

// Guard: a production build (its own address, indexable).
const robots = readFileSync(join(dist, 'robots.txt'), 'utf8');
if (!robots.includes(`${domain}/sitemap.xml`)) throw new Error(`dist/ is not a production build for ${domain} (robots.txt)`);

// SITE_FROM=r2: the site is already deployed as the test copy is (tools/deploy-test.mjs
// with SITE_BUCKET/SITE_WORKER/SIGNUP_BUCKET: pages through the Worker, from R2); only
// the domains are attached here, and the Worker's workers.dev address is turned off.
// (Static assets need Cloudflare's one-time upload key, which a proxy that injects
// the account token replaces.)
const fromR2 = process.env.SITE_FROM === 'r2';
let fileCount = 'from R2';
if (!fromR2) {
// ---- Static assets: a manifest of hashes; only files Cloudflare lacks are sent ----
const files = [];
const walk = (d) => { for (const f of readdirSync(d)) { const p = join(d, f); if (statSync(p).isDirectory()) walk(p); else files.push(relative(dist, p).split('\\').join('/')); } };
walk(dist);
fileCount = files.length;
const manifest = {}, byHash = new Map();
for (const f of files) {
  const bytes = readFileSync(join(dist, f));
  const hash = createHash('sha256').update(bytes).update(extname(f)).digest('hex').slice(0, 32);
  manifest[`/${f}`] = { hash, size: bytes.length };
  byHash.set(hash, f);
}
const session = await json(await call('POST', `${api}/workers/scripts/${script}/assets-upload-session`, JSON.stringify({ manifest }), { 'Content-Type': 'application/json' }));
let completion = session.jwt, uploaded = 0;
for (const bucket of session.buckets || []) {
  const form = new FormData();
  for (const hash of bucket) {
    const f = byHash.get(hash);
    form.append(hash, new Blob([readFileSync(join(dist, f)).toString('base64')], { type: 'application/octet-stream' }), hash);
    uploaded++;
  }
  const r = await call('POST', `${api}/workers/assets/upload?base64=true`, form, {}, { Authorization: `Bearer ${session.jwt}` });
  const body = await r.json();
  if (body.result?.jwt) completion = body.result.jwt;
}

// ---- The Worker ----
const buckets = (await json(await call('GET', `${api}/r2/buckets`))).buckets;
if (!buckets.some((b) => b.name === signups)) await call('POST', `${api}/r2/buckets`, JSON.stringify({ name: signups }), { 'Content-Type': 'application/json' });
const form = new FormData();
form.append('metadata', new Blob([JSON.stringify({
  main_module: 'prod.js', compatibility_date: '2026-10-01',
  assets: { jwt: completion, config: { html_handling: 'auto-trailing-slash', not_found_handling: '404-page' } },
  bindings: [{ type: 'assets', name: 'ASSETS' }, { type: 'r2_bucket', name: 'OVERVIEWS', bucket_name: overviewBucket }, { type: 'r2_bucket', name: 'SIGNUPS', bucket_name: signups }],
})], { type: 'application/json' }));
for (const m of ['prod.js', 'doc-alerts.js', 'te-araroa.js', 'notify.js']) form.append(m, new Blob([readFileSync(join(here, 'worker', m), 'utf8')], { type: 'application/javascript+module' }), m);
await call('PUT', `${api}/workers/scripts/${script}`, form);
} else await call('POST', `${api}/workers/scripts/${script}/subdomain`, JSON.stringify({ enabled: false, previews_enabled: false }), { 'Content-Type': 'application/json' });

// ---- Domains ----
const zone = (await json(await call('GET', `https://api.cloudflare.com/client/v4/zones?name=${domain}`)))[0];
if (!zone) throw new Error(`${domain} is not on this Cloudflare account`);
const attached = await json(await call('GET', `${api}/workers/domains`));
for (const hostname of [domain, `www.${domain}`]) {
  if (attached.some((d) => d.hostname === hostname && d.service === script)) continue;
  await call('PUT', `${api}/workers/domains`, JSON.stringify({ environment: 'production', hostname, service: script, zone_id: zone.id }), { 'Content-Type': 'application/json' });
}
const r2domains = (await json(await call('GET', `${api}/r2/buckets/${overviewBucket}/domains/custom`))).domains || [];
if (!r2domains.some((d) => d.domain === tilesDomain)) await call('POST', `${api}/r2/buckets/${overviewBucket}/domains/custom`, JSON.stringify({ domain: tilesDomain, zoneId: zone.id, enabled: true, minTLS: '1.2' }), { 'Content-Type': 'application/json' });
console.log(JSON.stringify({ files: fileCount, site: `https://${domain}/`, tiles: `https://${tilesDomain}/` }));
