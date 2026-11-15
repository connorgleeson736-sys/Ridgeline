// The elevation profile, as the app's ElevationProfileView: "Elevation (m)"
// and distance axes, dashed height lines, the orange line over a light fill,
// hut marks along it. Touch or hover to inspect a point (height, distance,
// grade); drag to measure a section from A to B (dragging backwards travels
// the line in reverse, so ascent and descent swap); pinch or wheel to zoom the
// distance axis, double-tap to show it all.

const START = 'rgb(72,115,88)', END = 'rgb(186,99,53)';
const MIN_SPAN = 60;

/** Distance, ascent, descent, high and low point of samples [{d, h}] between two distances. */
export function stats(samples, from = 0, to = Infinity) {
  let ascent = 0, descent = 0, high = -Infinity, low = Infinity, prev = null, first = null, last = null;
  for (const s of samples) {
    if (s.d < from || s.d > to || s.h == null) continue;
    if (prev != null) { const d = s.h - prev; if (d > 0) ascent += d; else descent -= d; }
    prev = s.h; high = Math.max(high, s.h); low = Math.min(low, s.h);
    first ??= s; last = s;
  }
  return { distance: first && last ? last.d - first.d : 0, ascent, descent, high: isFinite(high) ? high : null, low: isFinite(low) ? low : null };
}

/** The sample at distance d, interpolated (NativeGeo.profileAt). */
function at(samples, d) {
  if (!samples.length) return null;
  if (d <= samples[0].d) return samples[0];
  let lo = 0, hi = samples.length - 1;
  if (d >= samples[hi].d) return samples[hi];
  while (hi - lo > 1) { const mid = (lo + hi) >> 1; if (samples[mid].d <= d) lo = mid; else hi = mid; }
  const a = samples[lo], b = samples[hi], t = (d - a.d) / Math.max(1e-9, b.d - a.d);
  const h = a.h == null || b.h == null ? null : a.h + (b.h - a.h) * t;
  return { d, h, p: [a.p[0] + (b.p[0] - a.p[0]) * t, a.p[1] + (b.p[1] - a.p[1]) * t] };
}
/** Section from a to b in drag order (ProfileIndex.section, directed). */
function section(samples, from, to) {
  const a = Math.min(from, to), b = Math.max(from, to);
  if (b <= a) return null;
  const pts = [at(samples, a), ...samples.filter((s) => s.d > a && s.d < b), at(samples, b)];
  let ascent = 0, descent = 0, complete = true;
  for (let i = 1; i < pts.length; i++) {
    const l = pts[i - 1].h, r = pts[i].h;
    if (l == null || r == null) { complete = false; continue; }
    if (r > l) ascent += r - l; else descent += l - r;
  }
  return to < from ? { distance: b - a, ascent: descent, descent: ascent, complete } : { distance: b - a, ascent, descent, complete };
}
function grade(samples, d) {
  const total = samples.length ? samples[samples.length - 1].d : 0;
  const lo = Math.max(0, d - 10), hi = Math.min(total, d + 10);
  if (hi <= lo) return null;
  const a = at(samples, lo)?.h, b = at(samples, hi)?.h;
  return a == null || b == null ? null : ((b - a) / (hi - lo)) * 100;
}
const fmt = (m) => (m >= 1000 ? `${(m / 1000).toFixed(2)} km` : `${Math.round(m)} m`);
const comma = (n) => Math.round(n).toLocaleString('en-NZ');

export class Chart {
  /** onInspect(sample|null), onRange(section|null, a, b): a and b the sample points of A and B. */
  constructor(canvas, { onInspect = () => {}, onRange = () => {}, message = 'Elevation will appear here' } = {}) {
    Object.assign(this, { canvas, onInspect, onRange, message });
    this.samples = []; this.markers = []; this.cursor = null; this.range = null; this.sec = null; this.picked = -1;
    this.v0 = 0; this.v1 = 0; this.y = () => 0;
    canvas.tabIndex = 0;
    canvas.setAttribute('aria-label', 'Elevation profile. Drag for section statistics, pinch or scroll to zoom, double-tap to show all; arrow keys inspect.');
    const pointers = new Map();
    let start = null, downX = 0, moved = false, pinch = null, lastTap = 0, lastTapX = 0;
    const local = (e) => e.clientX - canvas.getBoundingClientRect().left;
    canvas.addEventListener('pointerdown', (e) => {
      if (!this.measured) return;
      canvas.setPointerCapture(e.pointerId);
      pointers.set(e.pointerId, local(e));
      if (pointers.size === 2) {
        const [xa, xb] = [...pointers.values()];
        start = null; this.range = null; this.sec = null; this.cursor = null; this.picked = -1; this.onRange(null); this.onInspect(null);
        if (!this.zoomed) { this.v0 = 0; this.v1 = this.total; }
        pinch = { a: this.dAt(xa), b: this.dAt(xb) };
        this.draw();
        return;
      }
      start = this.dAt(local(e)); downX = local(e); moved = false; this.range = null; this.sec = null; this.onRange(null);
      this.inspect(start);
    });
    canvas.addEventListener('pointermove', (e) => {
      if (!this.measured) return;
      if (pointers.has(e.pointerId)) pointers.set(e.pointerId, local(e));
      if (pinch && pointers.size === 2) {
        const [xa, xb] = [...pointers.values()];
        if (Math.abs(xb - xa) < 8 || pinch.a === pinch.b) return;
        const w = this.box();
        const width = ((pinch.b - pinch.a) * (w.right - w.left)) / (xb - xa);
        if (width <= 0) return;
        const span = Math.min(this.total, Math.max(Math.min(this.total, MIN_SPAN), width));
        if (span >= this.total * 0.999) { this.v0 = 0; this.v1 = 0; } else { this.v0 = Math.min(this.total - span, Math.max(0, pinch.a - ((xa - w.left) / (w.right - w.left)) * span)); this.v1 = this.v0 + span; }
        this.draw();
        return;
      }
      const d = this.dAt(local(e));
      if (start == null) { if (e.pointerType === 'mouse' && !this.range) this.inspect(d); return; }
      moved = moved || Math.abs(local(e) - downX) >= 4;
      if (moved) this.select(start, d); else this.inspect(d);
    });
    const up = (e) => {
      pointers.delete(e.pointerId);
      if (pinch) { if (pointers.size < 2) pinch = null; return; }
      if (start == null) return;
      const from = start, d = this.dAt(local(e));
      start = null;
      if (moved) { this.picked = -1; this.select(from, d); return; }
      const now = performance.now();
      if (this.zoomed && now - lastTap < 300 && Math.abs(local(e) - lastTapX) < 30) { lastTap = 0; this.v0 = this.v1 = 0; this.cursor = null; this.picked = -1; this.onInspect(null); this.draw(); return; }
      lastTap = now; lastTapX = local(e);
      const hit = this.markerHit(local(e), e.clientY - canvas.getBoundingClientRect().top);
      if (hit >= 0) { this.picked = hit; this.cursor = null; this.range = null; this.sec = null; this.onRange(null); this.onInspect(at(this.samples, this.markers[hit][0])); this.draw(); return; }
      this.picked = -1; this.range = null; this.sec = null; this.onRange(null); this.inspect(d);
    };
    canvas.addEventListener('pointerup', up);
    canvas.addEventListener('pointercancel', (e) => { pointers.delete(e.pointerId); start = null; pinch = null; this.clear(); });
    canvas.addEventListener('pointerleave', (e) => { if (e.pointerType === 'mouse' && start == null && !this.range) { this.cursor = null; this.onInspect(null); this.draw(); } });
    canvas.addEventListener('dblclick', () => { this.v0 = this.v1 = 0; this.draw(); });
    // A mouse wheel zooms the distance axis about the pointer.
    canvas.addEventListener('wheel', (e) => {
      if (!this.measured) return;
      e.preventDefault();
      const d = this.dAt(local(e)), lo = this.lo, hi = this.hi, k = Math.exp(e.deltaY * 0.0015);
      const span = Math.min(this.total, Math.max(Math.min(this.total, MIN_SPAN), (hi - lo) * k));
      if (span >= this.total * 0.999) { this.v0 = this.v1 = 0; } else { const f = (d - lo) / (hi - lo); this.v0 = Math.min(this.total - span, Math.max(0, d - f * span)); this.v1 = this.v0 + span; }
      this.draw();
    }, { passive: false });
    canvas.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') { this.clear(); return; }
      if ((e.key === 'ArrowLeft' || e.key === 'ArrowRight') && this.samples.length) {
        e.preventDefault();
        let i = this.samples.findIndex((s) => s.d >= (this.cursor?.d ?? 0));
        i = Math.min(this.samples.length - 1, Math.max(0, Math.max(0, i) + (e.key === 'ArrowRight' ? 1 : -1)));
        this.range = null; this.sec = null; this.inspect(this.samples[i].d);
      }
    });
    new ResizeObserver(() => this.draw()).observe(canvas);
  }
  get total() { return this.samples.length ? this.samples[this.samples.length - 1].d : 0; }
  get zoomed() { return this.v1 > this.v0; }
  get lo() { return this.zoomed ? this.v0 : 0; }
  get hi() { return this.zoomed ? this.v1 : Math.max(1, this.total); }
  get measured() { return this.samples.filter((s) => s.h != null).length >= 2; }
  box() { const w = this.canvas.clientWidth, h = this.canvas.clientHeight; return { left: 46, right: Math.max(47, w - 12), top: 22, bottom: Math.max(23, h - 30), w, h }; }
  dAt(px) { const b = this.box(); return this.lo + Math.min(1, Math.max(0, (px - b.left) / (b.right - b.left))) * (this.hi - this.lo); }
  x(d) { const b = this.box(); return b.left + ((d - this.lo) / Math.max(1e-6, this.hi - this.lo)) * (b.right - b.left); }
  set(samples, markers = []) { this.samples = samples || []; this.markers = markers; this.cursor = null; this.range = null; this.sec = null; this.v0 = this.v1 = 0; this.picked = -1; this.draw(); }
  setMarkers(markers) { this.markers = markers || []; this.draw(); }
  clear() { this.cursor = null; this.range = null; this.sec = null; this.onRange(null); this.onInspect(null); this.draw(); }
  inspect(d) { this.cursor = at(this.samples, d); this.onInspect(this.cursor); this.draw(); }
  select(from, to) {
    this.range = [from, to]; this.sec = section(this.samples, from, to); this.cursor = at(this.samples, to);
    this.onRange(this.sec, at(this.samples, from), at(this.samples, to), [from, to]);
    this.draw();
  }
  markerAt(i) {
    const d = this.markers[i]?.[0];
    if (d == null || d < this.lo || d > this.hi) return null;
    const h = at(this.samples, d)?.h;
    return h == null ? null : [this.x(d), this.y(h) - 7];
  }
  markerHit(px, py) {
    let best = -1, bestD = Infinity;
    this.markers.forEach((_, i) => { const m = this.markerAt(i); if (!m) return; const dx = Math.abs(px - m[0]), dy = Math.abs(py - m[1]); if (dx < 18 && dy < 26 && dx + dy < bestD) { bestD = dx + dy; best = i; } });
    return best;
  }
  draw() {
    const c = this.canvas, dpr = Math.min(3, devicePixelRatio || 1), b = this.box();
    if (!b.w || !b.h) return;
    if (c.width !== Math.round(b.w * dpr) || c.height !== Math.round(b.h * dpr)) { c.width = Math.round(b.w * dpr); c.height = Math.round(b.h * dpr); }
    const g = c.getContext('2d');
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    const css = getComputedStyle(c), v = (n, f) => css.getPropertyValue(n).trim() || f;
    const paper = v('--paper', '#fafaf5'), ink = v('--ink', '#233d34'), muted = v('--muted', '#657369'), line = v('--line', '#d6ddd2'), surface = v('--surface', '#fff'), chip = v('--chip', '#ecf0e8');
    const font = (size, bold) => `${bold ? '700 ' : ''}${size}px Roboto, system-ui, sans-serif`;
    g.fillStyle = paper; g.fillRect(0, 0, b.w, b.h);
    g.fillStyle = muted; g.font = font(10); g.textAlign = 'left'; g.textBaseline = 'alphabetic';
    g.fillText('Elevation (m)', b.left, 13);
    g.textAlign = 'center';
    g.fillText(this.total >= 1000 ? 'Distance (km)' : 'Distance (m)', (b.left + b.right) / 2, b.h - 3);
    if (!this.measured) { g.font = font(12); g.fillText(this.samples.length ? 'No elevation here' : this.message, b.w / 2, b.h / 2); return; }
    // Heights in view, padded (8%, at least 15 m), in tens of metres.
    const lo = this.lo, hi = this.hi;
    const visible = this.samples.filter((s) => s.d >= lo && s.d <= hi && s.h != null).map((s) => s.h);
    const hs = visible.length ? visible : this.samples.filter((s) => s.h != null).map((s) => s.h);
    const min = Math.min(...hs), max = Math.max(...hs), pad = Math.max(15, (max - min) * 0.08);
    let low = Math.floor((min - pad) / 10) * 10, high = Math.ceil((max + pad) / 10) * 10;
    if (high <= low) high = low + 30;
    this.y = (h) => b.bottom - ((h - low) / (high - low)) * (b.bottom - b.top);
    g.lineWidth = 0.7; g.strokeStyle = line; g.setLineDash([3, 4]);
    g.textAlign = 'right'; g.fillStyle = muted; g.font = font(10);
    for (let i = 0; i <= 2; i++) { const h = low + ((high - low) * i) / 2, yy = this.y(h); g.beginPath(); g.moveTo(b.left, yy); g.lineTo(b.right, yy); g.stroke(); g.fillText(comma(h), b.left - 6, yy + 3.5); }
    g.setLineDash([]);
    g.textAlign = 'center';
    const span = hi - lo;
    for (let i = 0; i <= 2; i++) { const d = lo + (span * i) / 2; g.fillText(this.total >= 1000 ? (d / 1000).toFixed(span >= 20000 ? 0 : span >= 2000 ? 1 : 2) : `${Math.round(d)}`, this.x(d), b.bottom + 16); }
    g.strokeStyle = 'rgb(167,178,168)'; g.beginPath(); g.moveTo(b.left, b.bottom); g.lineTo(b.right, b.bottom); g.moveTo(b.left, b.top); g.lineTo(b.left, b.bottom); g.stroke();
    // The line and its fill, with gaps where elevation is missing.
    const path = new Path2D(), fill = new Path2D();
    let pen = false, lastX = 0;
    const step = Math.max(1, Math.floor(this.samples.length / Math.max(1, (b.right - b.left) * 4)));
    this.samples.forEach((p, i) => {
      if (i % step && i !== this.samples.length - 1) return;
      if (p.h == null) { if (pen) { fill.lineTo(lastX, b.bottom); fill.closePath(); } pen = false; return; }
      const px = this.x(p.d), py = this.y(p.h);
      if (pen) { path.lineTo(px, py); fill.lineTo(px, py); } else { path.moveTo(px, py); fill.moveTo(px, b.bottom); fill.lineTo(px, py); }
      lastX = px; pen = true;
    });
    if (pen) { fill.lineTo(lastX, b.bottom); fill.closePath(); }
    g.save(); g.beginPath(); g.rect(b.left, 0, b.right - b.left, b.bottom); g.clip();
    g.fillStyle = 'rgba(232,121,68,0.133)'; g.fill(fill);
    g.strokeStyle = END; g.lineWidth = 2; g.lineJoin = 'round'; g.stroke(path);
    g.restore();
    // The chosen section, A where the drag started and B where it is now.
    if (this.range) {
      const a = this.x(Math.min(...this.range)), bb = this.x(Math.max(...this.range));
      g.save(); g.beginPath(); g.rect(a, 0, bb - a, b.bottom); g.clip(); g.fillStyle = 'rgba(88,125,98,0.32)'; g.fill(fill); g.restore();
      for (const [d, label, color] of [[this.range[0], 'A', START], [this.range[1], 'B', END]]) {
        const px = this.x(d);
        g.strokeStyle = color; g.lineWidth = 1.5; g.beginPath(); g.moveTo(px, b.top); g.lineTo(px, b.bottom); g.stroke();
        g.fillStyle = color; g.beginPath(); g.arc(px, b.top, 8, 0, Math.PI * 2); g.fill();
        g.fillStyle = '#fff'; g.font = font(10, true); g.textAlign = 'center'; g.fillText(label, px, b.top + 3.5);
      }
    }
    // Huts along the line.
    this.markers.forEach((m, i) => {
      const p = this.markerAt(i);
      if (!p) return;
      const [mx, my] = p, r = 4.5 * (i === this.picked ? 1.35 : 1);
      const hut = new Path2D();
      hut.moveTo(mx - r, my); hut.lineTo(mx, my - r); hut.lineTo(mx + r, my); hut.lineTo(mx + r * 0.75, my); hut.lineTo(mx + r * 0.75, my + r); hut.lineTo(mx - r * 0.75, my + r); hut.lineTo(mx - r * 0.75, my); hut.closePath();
      g.fillStyle = i === this.picked ? END : ink; g.fill(hut); g.strokeStyle = paper; g.lineWidth = 1; g.stroke(hut);
    });
    if (this.picked >= 0) {
      const p = this.markerAt(this.picked);
      if (p) {
        const name = this.markers[this.picked][1];
        g.font = font(11.5, true);
        const w = g.measureText(name).width + 16, h = 21, bx = Math.min(Math.max(b.left, b.right - w), Math.max(b.left, p[0] - w / 2)), by = Math.max(2, p[1] - 6.3 - 6 - h);
        g.fillStyle = ink; roundRect(g, bx, by, w, h, h / 2); g.fill();
        g.fillStyle = paper; g.textAlign = 'center'; g.fillText(name, bx + w / 2, by + h / 2 + 4);
      }
    }
    const p = this.cursor;
    if (p && !this.range) {
      g.strokeStyle = ink; g.lineWidth = 1; g.beginPath(); g.moveTo(this.x(p.d), b.top); g.lineTo(this.x(p.d), b.bottom); g.stroke();
      if (p.h != null) { g.fillStyle = END; g.beginPath(); g.arc(this.x(p.d), this.y(p.h), 4, 0, Math.PI * 2); g.fill(); }
    }
    // The tooltip: height, distance and grade at the cursor, or the section's distance, ascent and descent.
    let lines = null;
    if (this.range && this.sec) {
      const s = this.sec, dark = matchMedia('(prefers-color-scheme: dark)').matches;
      lines = [[`Distance ${fmt(s.distance)}`, ink], [`↑ Ascent ${s.complete ? `${Math.round(s.ascent)} m` : 'unavailable'}`, dark ? 'rgb(126,196,146)' : 'rgb(60,120,83)'], [`↓ Descent ${s.complete ? `${Math.round(s.descent)} m` : 'unavailable'}`, END]];
    } else if (p && this.picked < 0) {
      const gr = grade(this.samples, p.d);
      lines = [[p.h != null ? `${comma(p.h)} m` : 'Elevation unavailable', ink], [`${fmt(p.d)} from start`, muted], [gr == null ? 'Grade unavailable' : `Grade ${gr > 0 ? '+' : ''}${gr.toFixed(1)}%`, muted]];
    }
    if (lines) {
      g.font = font(11); g.textAlign = 'left';
      const w = Math.max(...lines.map((l) => g.measureText(l[0]).width)) + 16, lh = 15, h = lines.length * lh + 8;
      const anchor = this.range ? this.x(this.range[1]) : this.x(p.d);
      const bx = Math.min(Math.max(b.left, b.right - w), Math.max(b.left, anchor + 12 + w <= b.right ? anchor + 12 : anchor - 12 - w)), by = b.top + 12;
      g.fillStyle = this.range ? chip : surface; roundRect(g, bx, by, w, h, 6); g.fill();
      g.strokeStyle = this.range ? 'rgb(128,161,138)' : line; g.lineWidth = 1; g.stroke();
      lines.forEach(([text, color], i) => { g.fillStyle = color; g.fillText(text, bx + 8, by + lh * (i + 1)); });
    }
  }
}
function roundRect(g, x, y, w, h, r) { g.beginPath(); g.moveTo(x + r, y); g.arcTo(x + w, y, x + w, y + h, r); g.arcTo(x + w, y + h, x, y + h, r); g.arcTo(x, y + h, x, y, r); g.arcTo(x, y, x + w, y, r); g.closePath(); }
export { START, END };
