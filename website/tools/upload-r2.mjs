// Uploads a folder of WebP tiles to the overview bucket under a prefix, cached for a
// year (they never change; new versions get new keys), e.g. the corrected overview:
//   CLOUDFLARE_ACCOUNT_ID=… CLOUDFLARE_API_TOKEN=… node tools/upload-r2.mjs OUT/ctiles ctiles
// Resumable: uploaded keys are listed in <folder>/../uploaded-<prefix>.txt.
import { readFileSync, readdirSync, statSync, appendFileSync, existsSync } from 'node:fs';
import { join, relative, dirname } from 'node:path';

const [dir, prefix] = process.argv.slice(2);
const account = process.env.CLOUDFLARE_ACCOUNT_ID, token = process.env.CLOUDFLARE_API_TOKEN;
const bucket = process.env.OVERVIEW_BUCKET || 'ridgeline-overviews';
if (!dir || !prefix || !account || !token) throw new Error('usage: CLOUDFLARE_ACCOUNT_ID=… CLOUDFLARE_API_TOKEN=… node tools/upload-r2.mjs DIR PREFIX');
const api = `https://api.cloudflare.com/client/v4/accounts/${account}/r2/buckets/${bucket}/objects`;
const doneFile = join(dirname(dir), `uploaded-${prefix.replace(/\W/g, '-')}.txt`);
const done = new Set(existsSync(doneFile) ? readFileSync(doneFile, 'utf8').split('\n').filter(Boolean) : []);
const files = [];
const walk = (d) => { for (const f of readdirSync(d)) { const p = join(d, f); if (statSync(p).isDirectory()) walk(p); else if (f.endsWith('.webp')) files.push(relative(dir, p).split('\\').join('/')); } };
walk(dir);
const todo = files.filter((k) => !done.has(k));
console.log(`${todo.length} to upload, ${done.size} already there`);
async function put(k) {
  const body = readFileSync(join(dir, k));
  for (let attempt = 0; ; attempt++) {
    const r = await fetch(`${api}/${prefix}/${k}`, { method: 'PUT', body, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'image/webp', 'Cache-Control': 'public, max-age=31536000, immutable' } });
    if (r.ok) return;
    if (attempt >= 6 || (r.status < 500 && r.status !== 429)) throw new Error(`PUT ${k}: ${r.status} ${(await r.text()).slice(0, 200)}`);
    await new Promise((s) => setTimeout(s, 1000 * 2 ** attempt));
  }
}
let next = 0, count = 0;
await Promise.all(Array.from({ length: 16 }, async () => {
  while (next < todo.length) { const k = todo[next++]; await put(k); appendFileSync(doneFile, `${k}\n`); if (++count % 1000 === 0) console.log(`${count}/${todo.length}`); }
}));
console.log(`uploaded ${count} to ${prefix}/`);
