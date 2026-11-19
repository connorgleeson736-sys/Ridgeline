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

async function deshade(img, urls, signal) {
  let gain;
  try { gain = await shading(urls, signal); } catch (e) { img.close(); throw e; }
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
  let img;
  try { img = await bitmap(l.url, signal); } catch (e) {
    if (!l.fallback || signal.aborted) throw e;
    img = await bitmap(l.fallback, signal); // a missing overview tile: LINZ's own
  }
  return l.shade ? deshade(img, l.shade, signal) : img;
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
