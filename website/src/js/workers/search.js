// Place search on its own thread: 48,000 names are read once, searched per key press.
import { PlaceSearch } from '../core/search.js';

let index = null;
let loading = null;
const load = (url) => loading || (loading = fetch(url).then((r) => { if (!r.ok) throw new Error(r.status); return r.text(); }).then((t) => { index = new PlaceSearch(t); }));
self.onmessage = async ({ data: m }) => {
  try {
    await load(m.url);
    if (m.type === 'warm') { postMessage({ id: m.id, result: index.size }); return; }
    postMessage({ id: m.id, result: index.search(m.query, m.near, m.limit || 12).map((r) => ({ ...r.place, km: r.km })) });
  } catch (e) {
    loading = null;
    postMessage({ id: m.id, error: String(e?.message || e) });
  }
};
