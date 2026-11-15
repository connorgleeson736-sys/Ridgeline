// The bottom sheet, as the app's NativeSheet: a grip with × at the top right,
// two stops (compact and expanded); tap the grip to switch, or drag it.
import { el, store } from './ui.js';

export class Sheet {
  constructor(root, { onClose = () => {} } = {}) {
    this.root = root;
    this.onClose = onClose;
    this.back = el('button', { class: 'back', type: 'button', hidden: true }, '‹ Back');
    this.closeButton = el('button', { class: 'close', type: 'button', 'aria-label': 'Close' }, '×');
    this.grip = el('div', { class: 'grip', role: 'button', tabindex: 0, 'aria-label': 'Resize panel' }, this.back, this.closeButton);
    this.title = el('h2', { class: 'sheet-title', id: 'sheet-title' });
    this.subtitle = el('p', { class: 'sheet-subtitle' });
    // A small photo in the corner (the app's 56 dp thumbnail).
    this.photo = el('img', { class: 'thumb', alt: '', hidden: true, decoding: 'async', referrerpolicy: 'no-referrer', onerror: () => { this.photo.hidden = true; } });
    this.head = el('header', { class: 'sheet-head' }, this.photo, el('div', { class: 'sheet-heading' }, this.title, this.subtitle));
    this.body = el('div', { class: 'sheet-body' });
    root.append(this.grip, this.head, this.body);
    root.setAttribute('role', 'dialog');
    root.setAttribute('aria-labelledby', 'sheet-title');
    root.tabIndex = -1;
    this.closeButton.addEventListener('click', (e) => { e.stopPropagation(); this.close(); });
    this.back.addEventListener('click', (e) => { e.stopPropagation(); this.pop(); });
    this.grip.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); this.toggle(); } });
    root.addEventListener('keydown', (e) => { if (e.key === 'Escape') this.close(); });
    this.stack = [];
    this.token = 0;
    this.dragging();
    this.card();
  }
  get isOpen() { return !this.root.hidden; }
  /** Shows a panel: {kind, title, subtitle, size, render(body, alive) → cleanup}. `push`: Back returns here. */
  open(panel, { push = false } = {}) {
    if (push && this.current) this.stack.push(this.current); else this.stack = [];
    this.show(panel);
  }
  show(panel) {
    this.cleanup?.();
    this.cleanup = null;
    this.current = panel;
    const token = ++this.token;
    this.title.textContent = panel.title || '';
    this.subtitle.textContent = panel.subtitle || '';
    this.subtitle.hidden = !panel.subtitle;
    this.head.hidden = !panel.title;
    this.photo.hidden = true; this.photo.removeAttribute('src');
    this.back.hidden = !this.stack.length;
    this.body.replaceChildren();
    this.body.scrollTop = 0;
    this.root.hidden = false;
    this.root.dataset.kind = panel.kind || '';
    this.size(panel.size || 'expanded');
    document.body.classList.add('sheet-open');
    const r = panel.render?.(this.body, () => token === this.token);
    if (typeof r === 'function') this.cleanup = r;
    requestAnimationFrame(() => this.root.focus({ preventScroll: true }));
  }
  setTitle(title, subtitle) {
    if (title != null) { this.title.textContent = title; this.head.hidden = !title; }
    if (subtitle != null) { this.subtitle.textContent = subtitle; this.subtitle.hidden = !subtitle; }
  }
  setPhoto(src, alt = '') {
    if (!src || !/^https:\/\//.test(src)) { this.photo.hidden = true; return; }
    this.photo.alt = alt; this.photo.src = src; this.photo.hidden = false;
  }
  pop() { const prev = this.stack.pop(); if (prev) this.show(prev); else this.close(); }
  close() {
    if (this.root.hidden) return;
    this.token++;
    this.cleanup?.();
    this.cleanup = null;
    this.current = null;
    this.stack = [];
    this.root.hidden = true;
    document.body.classList.remove('sheet-open');
    this.onClose();
  }
  size(s) { this.root.dataset.size = s; this.onSize?.(s); }
  toggle() { this.size(this.root.dataset.size === 'compact' ? 'expanded' : 'compact'); }
  /** On computers: a floating card moved by its top bar and resized from its
   * edges and corners, kept where it was left (position and size remembered). */
  card() {
    const wide = matchMedia('(min-width: 800px)');
    const root = this.root;
    const handles = ['e', 'w', 's', 'n', 'se', 'sw', 'ne', 'nw'].map((d) => el('div', { class: `resize resize-${d}`, 'data-dir': d, 'aria-hidden': 'true' }));
    root.append(...handles);
    let box = store.get('card', null);
    const clamp = (b) => {
      const w = Math.min(Math.max(320, b.w), innerWidth - 24), h = b.h == null ? null : Math.min(Math.max(220, b.h), innerHeight - 24);
      return { x: Math.min(Math.max(12, b.x), innerWidth - w - 12), y: Math.min(Math.max(12, b.y), innerHeight - (h ?? 220) - 12), w, h };
    };
    const apply = () => {
      if (!wide.matches || !box) { for (const k of ['left', 'top', 'width', 'height', 'bottom', 'maxHeight']) root.style[k] = ''; return; }
      box = clamp(box);
      Object.assign(root.style, { left: `${box.x}px`, top: `${box.y}px`, bottom: 'auto', width: `${box.w}px`, height: box.h == null ? '' : `${box.h}px`, maxHeight: box.h == null ? `${innerHeight - box.y - 12}px` : '' });
    };
    this.applyCard = apply;
    addEventListener('resize', apply);
    wide.addEventListener('change', apply);
    let start = null;
    const down = (e, mode) => {
      if (!wide.matches || e.button !== 0) return;
      const r = root.getBoundingClientRect();
      start = { mode, x: e.clientX, y: e.clientY, r: { x: r.left, y: r.top, w: r.width, h: r.height } };
      e.target.setPointerCapture(e.pointerId);
      e.preventDefault();
      document.body.classList.add(mode === 'move' ? 'card-moving' : 'card-resizing');
    };
    const move = (e) => {
      if (!start) return;
      const dx = e.clientX - start.x, dy = e.clientY - start.y, r = start.r;
      if (start.mode === 'move') box = { x: r.x + dx, y: r.y + dy, w: r.w, h: box?.h ?? null };
      else {
        const d = start.mode;
        let { x, y, w, h } = r;
        if (d.includes('e')) w = r.w + dx;
        if (d.includes('w')) { w = r.w - dx; x = r.x + dx; }
        if (d.includes('s')) h = r.h + dy;
        if (d.includes('n')) { h = r.h - dy; y = r.y + dy; }
        // Only a vertical resize fixes the height; widening alone keeps it fitting its content.
        box = { x, y, w, h: /[ns]/.test(d) ? h : box?.h ?? null };
      }
      apply();
    };
    const up = () => { if (!start) return; start = null; document.body.classList.remove('card-moving', 'card-resizing'); store.set('card', box); };
    for (const t of [this.grip, this.head]) t.addEventListener('pointerdown', (e) => { if (!e.target.closest('button')) down(e, 'move'); });
    for (const h of handles) h.addEventListener('pointerdown', (e) => down(e, h.dataset.dir));
    root.addEventListener('pointermove', move);
    root.addEventListener('pointerup', up);
    root.addEventListener('pointercancel', up);
    // Double-click the top bar: back to the default place and size.
    this.grip.addEventListener('dblclick', () => { if (!wide.matches) return; box = null; store.set('card', null); apply(); });
    apply();
  }
  /** Drag the grip between the two stops; a tap switches. */
  dragging() {
    let start = null;
    const down = (e) => {
      if (e.target.closest('button') || matchMedia('(min-width: 800px)').matches) return;
      start = { y: e.clientY, h: this.root.getBoundingClientRect().height };
      this.grip.setPointerCapture(e.pointerId);
      this.root.classList.add('dragging');
    };
    const move = (e) => {
      if (!start) return;
      this.root.style.height = `${Math.max(90, Math.min(innerHeight - 60, start.h - (e.clientY - start.y)))}px`;
    };
    const up = (e) => {
      if (!start) return;
      const moved = e.clientY - start.y, h = start.h - moved;
      this.root.classList.remove('dragging');
      this.root.style.height = '';
      if (Math.abs(moved) < 6) this.toggle();
      else this.size(h < innerHeight * 0.32 ? 'compact' : 'expanded');
      start = null;
    };
    this.grip.addEventListener('pointerdown', down);
    this.grip.addEventListener('pointermove', move);
    this.grip.addEventListener('pointerup', up);
    this.grip.addEventListener('pointercancel', up);
  }
}
