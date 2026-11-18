// The bottom sheet, as the app's NativeSheet: a grip with × at the top right,
// three stops (compact, expanded and full); tap the grip to go up a stop (from full,
// back to compact), or drag it to the nearest.
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
    // Beside the title, on the right: a panel's own control (Te Araroa's direction).
    this.aside = el('div', { class: 'sheet-aside' });
    this.head = el('header', { class: 'sheet-head' }, this.photo, el('div', { class: 'sheet-heading' }, this.title, this.subtitle), this.aside);
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
    const cur = this.isOpen ? this.current : null;
    // Opened straight from another panel (a track tapped with a route open): Back
    // returns to it. The same panel shown again (a route redrawn) keeps the way back.
    const same = cur && cur.kind === panel.kind && (cur.title || '') === (panel.title || '');
    if (cur && (push || !same)) { this.stack.push(cur); if (this.stack.length > 12) this.stack.shift(); }
    else if (!cur) this.stack = [];
    this.show(panel);
  }
  /** Shows a panel with Back leading to `back` (not shown first): a Te Araroa section
   * opened from the map, Back to the trail. */
  openWithBack(panel, back) {
    this.stack = [back];
    this.show(panel);
  }
  /** `restored`: shown again by Back; panels then leave the map's view as it is. */
  show(panel, { restored = false } = {}) {
    this.cleanup?.();
    this.cleanup = null;
    this.current = panel;
    const token = ++this.token;
    this.title.textContent = panel.title || '';
    this.subtitle.textContent = panel.subtitle || '';
    this.subtitle.hidden = !panel.subtitle;
    this.head.hidden = !panel.title;
    this.photo.hidden = true; this.photo.removeAttribute('src');
    this.aside.replaceChildren();
    this.back.hidden = !this.stack.length;
    const prev = this.stack[this.stack.length - 1];
    this.back.textContent = `‹ ${prev?.backLabel || prev?.title || 'Back'}`;
    this.body.replaceChildren();
    this.body.scrollTop = 0;
    this.root.hidden = false;
    this.root.dataset.kind = panel.kind || '';
    // Left at full, the next panel stays full.
    this.size(panel.size || (this.root.dataset.size === 'full' && this.isOpen ? 'full' : 'expanded'));
    document.body.classList.add('sheet-open');
    const r = panel.render?.(this.body, () => token === this.token, { restored });
    if (typeof r === 'function') this.cleanup = r;
    requestAnimationFrame(() => this.root.focus({ preventScroll: true }));
  }
  setTitle(title, subtitle) {
    // The panel keeps its title as loaded (Back to it names it).
    if (title != null) { this.title.textContent = title; this.head.hidden = !title; if (this.current && title) this.current.title = title; }
    if (subtitle != null) { this.subtitle.textContent = subtitle; this.subtitle.hidden = !subtitle; }
  }
  /** Puts a control beside the title (replacing any there). */
  setAside(node) { this.aside.replaceChildren(...(node ? [node] : [])); }
  setPhoto(src, alt = '') {
    if (!src || !/^https:\/\//.test(src)) { this.photo.hidden = true; return; }
    this.photo.alt = alt; this.photo.src = src; this.photo.hidden = false;
  }
  pop() { const prev = this.stack.pop(); if (prev) this.show(prev, { restored: true }); else this.close(); }
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
  toggle() { this.size({ compact: 'expanded', expanded: 'full' }[this.root.dataset.size] || 'compact'); }
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
  /** Drag the sheet between its stops by its grip or its title area (the app's
   * drag zone); a tap goes up one. A swipe of 30 px goes to the next stop that way,
   * however far it went; a longer drag settles at the nearest. */
  dragging() {
    let start = null;
    const STOPS = ['compact', 'expanded', 'full'];
    const down = (e) => {
      if (e.target.closest('button, a, input, select, textarea, [role=switch], .switch') || matchMedia('(min-width: 800px)').matches) return;
      start = { y: e.clientY, h: this.root.getBoundingClientRect().height, at: STOPS.indexOf(this.root.dataset.size) };
      e.currentTarget.setPointerCapture(e.pointerId);
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
      const nearest = h < innerHeight * 0.32 ? 0 : h < innerHeight * 0.72 ? 1 : 2;
      const at = start.at < 0 ? nearest : start.at;
      if (Math.abs(moved) < 6) { if (e.type === 'pointerup') this.toggle(); }
      else if (moved < -30 && nearest <= at) this.size(STOPS[Math.min(2, at + 1)]);
      else if (moved > 30 && nearest >= at) this.size(STOPS[Math.max(0, at - 1)]);
      else this.size(STOPS[nearest]);
      start = null;
    };
    // Parts of the body marked data-drag (a route's stats) drag it as well.
    this.body.addEventListener('pointerdown', (e) => { if (e.target.closest('[data-drag]')) down(e); });
    this.body.addEventListener('pointermove', move);
    this.body.addEventListener('pointerup', up);
    this.body.addEventListener('pointercancel', up);
    for (const t of [this.grip, this.head]) {
      t.addEventListener('pointerdown', down);
      t.addEventListener('pointermove', move);
      t.addEventListener('pointerup', up);
      t.addEventListener('pointercancel', up);
    }
  }
}
