// Uploads the shading corrections (tools/shade-corrections.py) to the overview
// bucket, where worker/site.js serves them edge-cached at /shade/v1/<z>/<bx>/<by>.webp,
// then writes their index to data/shade-index.json for the build.
//   CLOUDFLARE_ACCOUNT_ID=… CLOUDFLARE_API_TOKEN=… node tools/upload-shade.mjs OUT_DIR
// Resumable: uploaded files are listed in OUT_DIR/uploaded-cached.txt.
import { readFileSync, writeFileSync, appendFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = fileURLToPath(new URL('..', import.meta.url));
const out = process.argv[2];
const account = process.env.CLOUDFLARE_ACCOUNT_ID, token = process.env.CLOUDFLARE_API_TOKEN;
const bucket = process.env.OVERVIEW_BUCKET || 'ridgeline-overviews';
if (!out || !account || !token) throw new Error('usage: CLOUDFLARE_ACCOUNT_ID=… CLOUDFLARE_API_TOKEN=… node tools/upload-shade.mjs OUT_DIR');
const api = `https://api.cloudflare.com/client/v4/accounts/${account}/r2/buckets/${bucket}/objects`;

const index = JSON.parse(readFileSync(join(out, 'index.json'), 'utf8'));
// (With a year's Cache-Control, for the bucket's own domain; earlier uploads had none.)
const doneFile = join(out, 'uploaded-cached.txt');
const done = new Set(existsSync(doneFile) ? readFileSync(doneFile, 'utf8').split('\n').filter(Boolean) : []);
const todo = Object.entries(index.zooms).flatMap(([z, blocks]) => blocks.map((b) => `${z}/${b}`)).filter((k) => !done.has(k));
console.log(`${todo.length} to upload, ${done.size} already there`);

async function put(k) {
  const body = readFileSync(join(out, `${k}.webp`));
  for (let attempt = 0; ; attempt++) {
    const r = await fetch(`${api}/shade/v1/${k}.webp`, { method: 'PUT', body, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'image/webp', 'Cache-Control': 'public, max-age=31536000, immutable' } });
    if (r.ok) return;
    if (attempt >= 6 || (r.status < 500 && r.status !== 429)) throw new Error(`PUT ${k}: ${r.status} ${(await r.text()).slice(0, 200)}`);
    await new Promise((s) => setTimeout(s, 1000 * 2 ** attempt));
  }
}
let next = 0, count = 0;
await Promise.all(Array.from({ length: 16 }, async () => {
  while (next < todo.length) {
    const k = todo[next++];
    await put(k);
    appendFileSync(doneFile, `${k}\n`);
    if (++count % 500 === 0) console.log(`${count}/${todo.length}`);
  }
}));
writeFileSync(join(here, 'data/shade-index.json'), JSON.stringify(index));
console.log(`uploaded ${count}; index for zooms ${Object.keys(index.zooms).join(',')}`);
