// Search in the routes drawer, as the app's: places, peaks, huts, campsites and
// DOC walks above the saved routes, searched on a worker thread. The names
// (about 800 kB) load only when the drawer is first opened.
import { el, debounce } from './ui.js';
import { search as worker } from './workers.js';
import { LABELS, zoomFor } from '../core/search.js';
import { guidOf } from '../core/walks.js';
import * as teAraroa from './te-araroa.js';

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
        el('b', {}, r.name), el('small', {}, [r.label || LABELS[r.kind] || 'Place', r.detail].filter(Boolean).join(' · '))))));
    if (!found.length && q) results.replaceChildren(el('li', { class: 'none' }, 'No places found'));
    input.setAttribute('aria-expanded', String(!!q));
    if (active >= 0) input.setAttribute('aria-activedescendant', `result-${active}`); else input.removeAttribute('aria-activedescendant');
  }
  let pending = Promise.resolve([]);
  const searchNow = () => (pending = searchFor());
  const searchFor = async () => {
    const q = input.value.trim(), t = ++token;
    lastQuery = q;
    if (!q) { found = []; show(); return []; }
    const c = ctx.map.getCenter();
    try {
      let r = await worker.call({ type: 'search', url, query: q, near: [c.lng, c.lat], limit: 30 });
      if (t !== token) return r || [];
      // Te Araroa, the whole trail, first when its name is typed.
      const typed = q.toLowerCase().replace(/[\s-]+/g, ' ');
      const ta = [];
      if (typed.length >= 4 && ('te araroa'.startsWith(typed) || typed.includes('araroa') || 'the long pathway'.startsWith(typed))) ta.push({ kind: 'te-araroa', name: 'Te Araroa', label: 'Trail', detail: 'Cape Reinga to Bluff' });
      // Its sections by name, once the trail has loaded (it does with its layer).
      const d = teAraroa.loaded(), words = typed.replace(/\bte araroa\b/, '').split(' ').filter((w) => w.length >= 3);
      if (d && words.length) {
        const plain = (t) => t.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
        for (const sec of d.sections) if (words.every((w) => plain(sec.name).includes(plain(w)))) ta.splice(ta.length && ta[ta.length - 1].slug == null ? ta.length - 1 : ta.length, 0, { kind: 'te-araroa', slug: sec.slug, name: sec.name, label: 'Te Araroa section', detail: sec.alternative ? 'Option' : `Section ${sec.n} · km ${Math.round(sec.from)}` });
      }
      // First when Te Araroa is asked for; otherwise after the places, a few.
      r = typed.includes('araroa') ? [...ta.slice(0, 8), ...(r || [])] : [...(r || []).slice(0, 5), ...ta.slice(0, 3), ...(r || []).slice(5)];
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
      // Typed faster than the results (or the names are still loading): wait for them.
      if (input.value.trim() !== lastQuery) searchNow().then((r) => { if (r?.[0]) choose(r[0]); });
      else if (found[active]) choose(found[active]);
      else pending.then((r) => { if (r?.[0] && input.value.trim() === lastQuery) choose(r[0]); });
    }
  });
  form.addEventListener('submit', (e) => e.preventDefault());
  clear.addEventListener('click', () => { input.value = ''; clear.hidden = true; found = []; show(); input.focus(); });

  function choose(r) {
    token++;
    input.blur();
    ctx.closeDrawer();
    if (r.kind === 'te-araroa') { ctx.openTeAraroa(r.slug || null); return; }
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
