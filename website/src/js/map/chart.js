// The elevation profile: distance across, height up. Hover or touch to see a
// point on the map; drag across to measure a section (A to B), as in the app.
import { distance as fmtDistance, height as fmtHeight } from './ui.js';

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

export class Chart {
  constructor(canvas, { onPoint = () => {}, onSection = () => {} } = {}) {
    this.canvas = canvas; this.onPoint = onPoint; this.onSection = onSection;
    this.samples = []; this.hover = null; this.section = null;
    const pos = (e) => { const r = canvas.getBoundingClientRect(); return this.dAt(e.clientX - r.left); };
    let down = null;
    canvas.addEventListener('pointerdown', (e) => { if (!this.samples.length) return; canvas.setPointerCapture(e.pointerId); down = pos(e); this.section = null; this.hover = down; this.draw(); this.emit(); });
    canvas.addEventListener('pointermove', (e) => {
      if (!this.samples.length) return;
      const d = pos(e);
      this.hover = d;
      if (down != null && Math.abs(this.x(d) - this.x(down)) > 6) this.section = [Math.min(down, d), Math.max(down, d)];
      this.draw(); this.emit();
    });
    const end = () => { down = null; if (!this.section) { this.hover = null; this.draw(); this.emit(); } };
    canvas.addEventListener('pointerup', end);
    canvas.addEventListener('pointercancel', end);
    canvas.addEventListener('pointerleave', (e) => { if (e.pointerType === 'mouse' && down == null && !this.section) { this.hover = null; this.draw(); this.emit(); } });
    new ResizeObserver(() => this.draw()).observe(canvas);
  }
  emit() {
    this.onPoint(this.hover == null ? null : this.pointAt(this.hover));
    this.onSection(this.section ? stats(this.samples, this.section[0], this.section[1]) : null, this.section && this.samples.filter((s) => s.d >= this.section[0] && s.d <= this.section[1]).map((s) => s.p));
  }
  clearSection() { this.section = null; this.hover = null; this.draw(); this.emit(); }
  set(samples) { this.samples = samples || []; this.section = null; this.hover = null; this.draw(); }
  get total() { return this.samples.length ? this.samples[this.samples.length - 1].d : 0; }
  pad() { return { l: 44, r: 10, t: 10, b: 22 }; }
  x(d) { const w = this.canvas.clientWidth, p = this.pad(); return p.l + ((w - p.l - p.r) * d) / (this.total || 1); }
  dAt(px) { const w = this.canvas.clientWidth, p = this.pad(); return Math.max(0, Math.min(this.total, ((px - p.l) / Math.max(1, w - p.l - p.r)) * this.total)); }
  pointAt(d) {
    const s = this.samples;
    let i = s.findIndex((q) => q.d >= d);
    if (i < 0) i = s.length - 1;
    return s[i];
  }
  draw() {
    const c = this.canvas, dpr = Math.min(2, devicePixelRatio || 1), w = c.clientWidth, h = c.clientHeight;
    if (!w || !h) return;
    if (c.width !== Math.round(w * dpr) || c.height !== Math.round(h * dpr)) { c.width = Math.round(w * dpr); c.height = Math.round(h * dpr); }
    const g = c.getContext('2d');
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, h);
    const css = getComputedStyle(c), ink = css.getPropertyValue('--ink').trim() || '#233d34', muted = css.getPropertyValue('--muted').trim() || '#65736a', line = css.getPropertyValue('--line').trim() || '#d6ddd2';
    const valid = this.samples.filter((s) => s.h != null);
    if (valid.length < 2) {
      g.fillStyle = muted; g.font = '13px system-ui, sans-serif'; g.textAlign = 'center';
      g.fillText(this.samples.length ? 'No elevation here' : 'Loading elevation…', w / 2, h / 2);
      return;
    }
    const p = this.pad();
    let lo = Math.min(...valid.map((s) => s.h)), hi = Math.max(...valid.map((s) => s.h));
    const span = Math.max(50, hi - lo), stepH = niceStep(span / 3);
    lo = Math.floor(lo / stepH) * stepH; hi = Math.max(lo + stepH, Math.ceil(hi / stepH) * stepH);
    const y = (v) => p.t + (h - p.t - p.b) * (1 - (v - lo) / (hi - lo));
    g.font = '11px system-ui, sans-serif'; g.lineWidth = 1;
    g.strokeStyle = line; g.fillStyle = muted; g.textAlign = 'right'; g.textBaseline = 'middle';
    for (let v = lo; v <= hi + 1e-6; v += stepH) { g.beginPath(); g.moveTo(p.l, Math.round(y(v)) + 0.5); g.lineTo(w - p.r, Math.round(y(v)) + 0.5); g.stroke(); g.fillText(`${Math.round(v)} m`, p.l - 6, y(v)); }
    const stepD = niceStep(this.total / Math.max(2, Math.floor((w - p.l - p.r) / 90)));
    g.textAlign = 'center'; g.textBaseline = 'top';
    for (let d = 0; d <= this.total + 1e-6; d += stepD) g.fillText(stepD >= 1000 ? `${+(d / 1000).toFixed(stepD < 1000 ? 1 : 0)} km` : `${Math.round(d)} m`, this.x(d), h - p.b + 5);
    if (this.section) { g.fillStyle = 'rgba(232,121,68,.14)'; g.fillRect(this.x(this.section[0]), p.t, this.x(this.section[1]) - this.x(this.section[0]), h - p.t - p.b); }
    const grad = g.createLinearGradient(0, p.t, 0, h - p.b);
    grad.addColorStop(0, 'rgba(232,121,68,.45)'); grad.addColorStop(1, 'rgba(232,121,68,.04)');
    g.beginPath();
    let started = false;
    for (const s of valid) { const px = this.x(s.d), py = y(s.h); if (!started) { g.moveTo(px, py); started = true; } else g.lineTo(px, py); }
    g.lineTo(this.x(valid[valid.length - 1].d), h - p.b); g.lineTo(this.x(valid[0].d), h - p.b); g.closePath();
    g.fillStyle = grad; g.fill();
    g.beginPath();
    started = false;
    for (const s of valid) { const px = this.x(s.d), py = y(s.h); if (!started) { g.moveTo(px, py); started = true; } else g.lineTo(px, py); }
    g.strokeStyle = '#e87944'; g.lineWidth = 2; g.lineJoin = 'round'; g.stroke();
    const mark = (d, label) => {
      const s = this.pointAt(d);
      if (!s || s.h == null) return;
      g.strokeStyle = ink; g.lineWidth = 1; g.setLineDash([3, 3]);
      g.beginPath(); g.moveTo(this.x(s.d), p.t); g.lineTo(this.x(s.d), h - p.b); g.stroke(); g.setLineDash([]);
      g.beginPath(); g.arc(this.x(s.d), y(s.h), 4.5, 0, Math.PI * 2); g.fillStyle = '#e87944'; g.fill(); g.strokeStyle = '#fff'; g.lineWidth = 2; g.stroke();
      if (label) { g.fillStyle = ink; g.font = 'bold 11px system-ui, sans-serif'; g.textAlign = 'center'; g.textBaseline = 'bottom'; g.fillText(label, this.x(s.d), p.t + 11); }
    };
    if (this.section) { mark(this.section[0], 'A'); mark(this.section[1], 'B'); }
    else if (this.hover != null) {
      mark(this.hover);
      const s = this.pointAt(this.hover);
      if (s?.h != null) {
        const text = `${fmtDistance(s.d)} · ${fmtHeight(s.h)}`;
        g.font = '600 12px system-ui, sans-serif';
        const tw = g.measureText(text).width + 14, tx = Math.min(w - p.r - tw, Math.max(p.l, this.x(s.d) - tw / 2));
        g.fillStyle = 'rgba(20,30,26,.86)'; roundRect(g, tx, p.t, tw, 22, 6); g.fill();
        g.fillStyle = '#fff'; g.textAlign = 'left'; g.textBaseline = 'middle'; g.fillText(text, tx + 7, p.t + 11);
      }
    }
  }
}
function niceStep(raw) {
  const p = 10 ** Math.floor(Math.log10(Math.max(1e-9, raw))), n = raw / p;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * p;
}
function roundRect(g, x, y, w, h, r) { g.beginPath(); g.moveTo(x + r, y); g.arcTo(x + w, y, x + w, y + h, r); g.arcTo(x + w, y + h, x, y + h, r); g.arcTo(x, y + h, x, y, r); g.arcTo(x, y, x + w, y, r); g.closePath(); }
