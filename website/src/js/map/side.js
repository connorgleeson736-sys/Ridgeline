// A second panel joined to the side panel, on computers: what is picked in a list (a
// Te Araroa section, a food stop, a place to stay) opens here, and the list beside it
// narrows. Back (top left) steps back through what was opened, then to the list.
// On phones there is no room beside it: the caller opens it in the sheet instead.
import { el } from './ui.js';

const WIDE = matchMedia('(min-width: 800px)');

export class SidePanel {
  constructor(sheet) {
    this.sheet = sheet;
    this.title = el('h2', { class: 'sheet-title' });
    this.subtitle = el('p', { class: 'sheet-subtitle' });
    this.body = el('div', { class: 'sheet-body' });
    this.backButton = el('button', { class: 'back', type: 'button', onclick: () => this.back() }, '‹ Te Araroa');
    this.root = el('aside', { class: 'side-panel', hidden: true, 'aria-label': 'Details' },
      el('div', { class: 'side-top' }, this.backButton, el('button', { class: 'close', type: 'button', 'aria-label': 'Close', onclick: () => this.close() }, '×')),
      el('header', { class: 'sheet-head' }, el('div', { class: 'sheet-heading' }, this.title, this.subtitle), this.aside = el('div', { class: 'sheet-aside' })),
      this.body);
    this.root.addEventListener('keydown', (e) => { if (e.key === 'Escape') this.back(); });
    document.body.append(this.root);
    this.token = 0;
    this.stack = [];
    const place = () => this.place();
    new ResizeObserver(place).observe(sheet.root);
    new MutationObserver(place).observe(sheet.root, { attributes: true, attributeFilter: ['style', 'hidden'] });
    addEventListener('resize', place);
    WIDE.addEventListener('change', () => { if (!WIDE.matches) this.close(); });
  }
  /** Whether there is room for it (a computer). */
  get available() { return WIDE.matches; }
  get isOpen() { return !this.root.hidden; }
  get key() { return this.current?.key ?? null; }
  /** {key, title, subtitle, backLabel, render(body, alive, {restored}) → cleanup}.
   * `push`: Back returns to what is open now; otherwise it replaces it. */
  open(panel, { push = false } = {}) {
    if (push && this.current) this.stack.push(this.current); else this.stack = [];
    this.show(panel);
  }
  show(panel, { restored = false } = {}) {
    this.cleanup?.();
    this.cleanup = null;
    this.current = panel;
    const token = ++this.token;
    this.title.textContent = panel.title || '';
    this.subtitle.textContent = panel.subtitle || '';
    this.subtitle.hidden = !panel.subtitle;
    this.backButton.textContent = `‹ ${this.stack.length ? this.stack[this.stack.length - 1].backLabel || 'Back' : this.home || 'Back'}`;
    this.aside.replaceChildren();
    this.body.replaceChildren();
    this.body.scrollTop = 0;
    this.root.hidden = false;
    this.sheet.root.classList.add('joined');
    this.place();
    const r = panel.render?.(this.body, () => token === this.token && !this.root.hidden, { restored });
    if (typeof r === 'function') this.cleanup = r;
    this.onChange?.(this.key);
  }
  back() { const prev = this.stack.pop(); if (prev) this.show(prev, { restored: true }); else this.close(); }
  setAside(node) { this.aside.replaceChildren(...(node ? [node] : [])); }
  setTitle(title, subtitle) {
    if (title != null) this.title.textContent = title;
    if (subtitle != null) { this.subtitle.textContent = subtitle; this.subtitle.hidden = !subtitle; }
  }
  /** Moves the joined list sideways (only when it changes: its style is watched). */
  shift(px) {
    const t = px ? `translateX(${Math.round(px)}px)` : '';
    if (this.sheet.root.style.transform !== t) this.sheet.root.style.transform = t;
  }
  close() {
    if (this.root.hidden) return;
    this.shift(0);
    this.token++;
    this.cleanup?.();
    this.cleanup = null;
    this.current = null;
    this.stack = [];
    this.root.hidden = true;
    this.sheet.root.classList.remove('joined', 'joined-left');
    this.onChange?.(null);
  }
  /** Joined to the side panel's right edge, the same height; to its left if there is no room. */
  place() {
    if (this.root.hidden) return;
    if (this.sheet.root.hidden) { this.close(); return; }
    // A list moved to the right half keeps its right edge as it narrows, and this panel
    // opens on its left; elsewhere the list keeps its left edge and this opens on its right.
    const st = this.sheet.root.style, cardX = parseFloat(st.left), cardW = parseFloat(st.width);
    const onRight = Number.isFinite(cardX + cardW) && cardX + cardW / 2 > innerWidth / 2;
    const narrow = this.sheet.root.offsetWidth;
    this.shift(onRight ? Math.max(0, cardW - narrow) : 0);
    const r = this.sheet.root.getBoundingClientRect(), w = Math.min(520, innerWidth - r.width - 24);
    const right = !onRight && r.right + w <= innerWidth - 10;
    // The corners where the two meet are square, on whichever side that is.
    this.root.classList.toggle('left', !right);
    this.sheet.root.classList.toggle('joined-left', !right);
    Object.assign(this.root.style, { left: `${right ? r.right : Math.max(10, r.left - w)}px`, top: `${r.top}px`, width: `${w}px`, height: `${r.height}px` });
  }
}
