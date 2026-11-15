// Search in the routes drawer, as the app's: places, peaks, huts, campsites and
// DOC walks above the saved routes, searched on a worker thread. The names
// (about 800 kB) load only when the drawer is first opened.
import { el, debounce } from './ui.js';
import { search as worker } from './workers.js';
import { LABELS, zoomFor } from '../core/search.js';
import { guidOf } from '../core/walks.js';

export function attach(ctx, { form, results, routes }) {
  const input = form.querySelector('input');
  const clear = form.querySelector('.search-clear');
  results.id = 'search-results';
  results.setAttribute('role', 'listbox');
  input.setAttribute('aria-controls', 'search-results');
  let found = [], active = -1, token = 0, lastQuery = '';
  const url = '/data/places.tsv';
  const warm = () => worker.call({ type: 'warm', url }).catch(() => {});

  function show() {
    const q = input.value.trim();
    routes.hidden = !!q;
    results.hidden = !q;
    results.replaceChildren(...found.map((r, i) => el('li', { role: 'option', id: `result-${i}`, 'aria-selected': i === active ? 'true' : 'false' },
      el('button', { type: 'button', tabindex: -1, onmousedown: (e) => e.preventDefault(), onclick: () => choose(r) },
        el('b', {}, r.name), el('small', {}, [LABELS[r.kind] || 'Place', r.detail].filter(Boolean).join(' · '))))));
    if (!found.length && q) results.replaceChildren(el('li', { class: 'none' }, 'No places found'));
    input.setAttribute('aria-expanded', String(!!q));
    if (active >= 0) input.setAttribute('aria-activedescendant', `result-${active}`); else input.removeAttribute('aria-activedescendant');
  }
  const searchNow = async () => {
    const q = input.value.trim(), t = ++token;
    lastQuery = q;
    if (!q) { found = []; show(); return []; }
    const c = ctx.map.getCenter();
    try {
      const r = await worker.call({ type: 'search', url, query: q, near: [c.lng, c.lat], limit: 30 });
      if (t !== token) return r || [];
      found = r || []; active = found.length ? 0 : -1;
      show();
      return found;
    } catch {
      if (t === token) { found = []; results.hidden = false; routes.hidden = true; results.replaceChildren(el('li', { class: 'none' }, 'Search is unavailable right now. Check your connection.')); }
      return [];
    }
  };
  const run = debounce(searchNow, 120);
  input.addEventListener('input', () => { clear.hidden = !input.value; run(); });
  input.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') { e.preventDefault(); if (!found.length) return; active = (active + (e.key === 'ArrowDown' ? 1 : -1) + found.length) % found.length; show(); }
    else if (e.key === 'Enter') {
      e.preventDefault();
      if (input.value.trim() !== lastQuery) searchNow().then((r) => { if (r?.[0]) choose(r[0]); });
      else if (found[active]) choose(found[active]);
    }
  });
  form.addEventListener('submit', (e) => e.preventDefault());
  clear.addEventListener('click', () => { input.value = ''; clear.hidden = true; found = []; show(); input.focus(); });

  function choose(r) {
    token++;
    input.blur();
    ctx.closeDrawer();
    const p = [r.lon, r.lat];
    const guid = r.kind === 'walk' ? guidOf(r.link) : null;
    // The sheet first, so the flight leaves room for it.
    if (guid) ctx.openWalk(guid);
    else if (r.kind === 'hut') ctx.openHut(r.name, p);
    else ctx.marker(p);
    requestAnimationFrame(() => ctx.flyTo(p, zoomFor(r.kind)));
  }
  return { warm, focus: () => input.focus({ preventScroll: true }), get query() { return input.value.trim(); } };
}
