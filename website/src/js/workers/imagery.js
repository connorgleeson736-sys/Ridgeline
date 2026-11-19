// The aerial imagery's background thread (the app's NativeImagery): layers drawn
// in order, each optionally cropped (a coarser tile's quarter), clipped (even-odd
// outline in tile pixels) and de-shaded. Returns a lossless PNG for MapLibre.
//
// De-shading: LINZ draws an 8 m hillshade and a texture shade (black, with alpha)
// over its aerial tiles to zoom 14 and not beyond, so zoom 15 suddenly turned
// brighter and whiter. Drawing black at alpha a over a colour c gives c·(1−a), so
// dividing by the product of (1−a) for both layers gives back the photograph's own
// colour at every zoom (checked against zoom 15: within about 1%). The app's
// overview imagery was averaged from these shaded tiles, so it is de-shaded too.
// The two layers come combined, made ahead, from our own host (blocks below);
// LINZ's own only for a zoom not made yet.
const SIZE = 256;

async function bitmap(url, signal) {
  const r = await fetch(url, { signal });
  if (!r.ok) throw new Error(`Tile ${r.status}`);
  return createImageBitmap(await r.blob());
}

/** 1/((1−a₁)(1−a₂)) per pixel, or null where neither layer shades the tile. */
async function shading(urls, signal) {
  const images = await Promise.all(urls.map(async (url) => {
    const r = await fetch(url, { signal });
    if (r.status === 204 || r.status === 404) return null; // nothing there (sea, outside NZ)
    if (!r.ok) throw new Error(`Shade ${r.status}`);
    const b = await r.blob();
    return b.size ? createImageBitmap(b) : null;
  }));
  if (!images.some(Boolean)) return null;
  const c = new OffscreenCanvas(SIZE, SIZE), g = c.getContext('2d', { willReadFrequently: true });
  const keep = new Float32Array(SIZE * SIZE).fill(1);
  let any = false;
  for (const img of images) {
    if (!img) continue;
    g.clearRect(0, 0, SIZE, SIZE);
    g.drawImage(img, 0, 0, SIZE, SIZE);
    img.close();
    const a = g.getImageData(0, 0, SIZE, SIZE).data;
    for (let i = 0, j = 3; i < keep.length; i++, j += 4) if (a[j]) { keep[i] *= 1 - a[j] / 255; any = true; }
  }
  if (!any) return null;
  for (let i = 0; i < keep.length; i++) keep[i] = 1 / keep[i];
  return keep;
}

// Correction blocks (tools/shade-corrections.py): 4×4 tiles of keep·255 as greyscale,
// decoded once and shared by the tiles in them (recently used kept).
const BLOCK = SIZE * 4, BLOCKS_KEPT = 24;
const blocks = new Map(); // url → Promise<Uint8Array>
function block(url) {
  let p = blocks.get(url);
  if (p) { blocks.delete(url); blocks.set(url, p); return p; }
  p = (async () => {
    const img = await bitmap(url); // not cancelled with one tile: its neighbours want it too
    const c = new OffscreenCanvas(BLOCK, BLOCK), g = c.getContext('2d', { willReadFrequently: true });
    g.drawImage(img, 0, 0, BLOCK, BLOCK);
    img.close();
    const rgba = g.getImageData(0, 0, BLOCK, BLOCK).data, v = new Uint8Array(BLOCK * BLOCK);
    for (let i = 0, j = 0; i < v.length; i++, j += 4) v[i] = rgba[j];
    return v;
  })();
  p.catch(() => blocks.delete(url));
  blocks.set(url, p);
  while (blocks.size > BLOCKS_KEPT) blocks.delete(blocks.keys().next().value);
  return p;
}
const GAIN = Float32Array.from({ length: 256 }, (_, v) => 255 / Math.max(v, 1));
async function blockGain({ block: url, x, y }, signal) {
  const v = await block(url);
  if (signal.aborted) throw new DOMException('Aborted', 'AbortError');
  const gain = new Float32Array(SIZE * SIZE);
  let any = false;
  for (let r = 0; r < SIZE; r++) {
    const row = (y + r) * BLOCK + x;
    for (let c = 0; c < SIZE; c++) { const k = v[row + c]; gain[r * SIZE + c] = GAIN[k]; if (k !== 255) any = true; }
  }
  return any ? gain : null;
}

/** The photograph with the shading taken out: the photo and its correction fetched together. */
async function deshade(photo, shade, signal) {
  const gainP = Array.isArray(shade) ? shading(shade, signal) : blockGain(shade, signal);
  gainP.catch(() => {}); // awaited below; not unhandled if the photo fails first
  const img = await photo;
  let gain;
  try { gain = await gainP; } catch (e) { img.close(); throw e; }
  if (!gain) return img;
  const c = new OffscreenCanvas(SIZE, SIZE), g = c.getContext('2d', { willReadFrequently: true });
  g.drawImage(img, 0, 0, SIZE, SIZE);
  img.close();
  const d = g.getImageData(0, 0, SIZE, SIZE), p = d.data; // Uint8Clamped: rounds and caps at 255
  for (let i = 0, j = 0; i < gain.length; i++, j += 4) {
    const k = gain[i];
    if (k !== 1) { p[j] *= k; p[j + 1] *= k; p[j + 2] *= k; }
  }
  g.putImageData(d, 0, 0);
  return c;
}

async function layerImage(l, signal) {
  let shade = l.shade;
  const photo = bitmap(l.url, signal).catch((e) => {
    if (!l.fallback || signal.aborted) throw e;
    if (!shade && l.fallbackShade) throw Object.assign(new Error('fallback'), { fallback: true });
    return bitmap(l.fallback, signal); // a missing overview tile: LINZ's own
  });
  if (!shade && l.fallbackShade) {
    // A corrected overview tile is drawn as it is; LINZ's in its place is corrected.
    try { return await photo; } catch (e) {
      if (!e.fallback) throw e;
      shade = l.fallbackShade;
      return deshade(bitmap(l.fallback, signal), shade, signal);
    }
  }
  return shade ? deshade(photo, shade, signal) : photo;
}

async function compose({ layers }, signal) {
  const canvas = new OffscreenCanvas(SIZE, SIZE);
  const g = canvas.getContext('2d');
  const settled = await Promise.allSettled(layers.map((l) => layerImage(l, signal)));
  const failed = settled.find((r) => r.status === 'rejected');
  if (failed) { for (const r of settled) r.value?.close?.(); throw failed.reason; }
  const images = settled.map((r) => r.value);
  layers.forEach((l, i) => {
    const img = images[i];
    g.save();
    if (l.clip) {
      g.beginPath();
      for (const ring of l.clip) ring.forEach(([x, y], k) => (k ? g.lineTo(x, y) : g.moveTo(x, y)));
      g.clip('evenodd');
    }
    if (l.crop) {
      const [sx, sy, size] = l.crop, k = img.width / SIZE;
      g.drawImage(img, sx * k, sy * k, size * k, size * k, 0, 0, SIZE, SIZE);
    } else g.drawImage(img, 0, 0, SIZE, SIZE);
    g.restore();
    img.close?.();
  });
  return (await canvas.convertToBlob({ type: 'image/png' })).arrayBuffer();
}

// Calls in flight, so a tile MapLibre no longer wants stops fetching (`cancel`).
const running = new Map();
onmessage = async ({ data: m }) => {
  if (m.type === 'cancel') { running.get(m.target)?.abort(); return; }
  const control = new AbortController();
  running.set(m.id, control);
  try {
    const result = m.type === 'compose' ? await compose(m, control.signal) : null;
    if (!control.signal.aborted) postMessage({ id: m.id, result }, result ? [result] : []);
  } catch (error) {
    if (!control.signal.aborted) postMessage({ id: m.id, error: String(error?.message || error) });
  } finally {
    running.delete(m.id);
  }
};
