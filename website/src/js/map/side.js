// A second panel beside the side panel, on computers: what is picked in a list (a
// Te Araroa section, a food stop) opens here, one at a time, so the list stays put.
// On phones there is no room beside it: the caller opens it in the sheet instead.
import { el } from './ui.js';

const WIDE = matchMedia('(min-width: 800px)');

export class SidePanel {
  constructor(sheet) {
    this.sheet = sheet;
    this.title = el('h2', { class: 'sheet-title' });
    this.subtitle = el('p', { class: 'sheet-subtitle' });
    this.body = el('div', { class: 'sheet-body' });
    this.root = el('aside', { class: 'side-panel', hidden: true, 'aria-label': 'Details' },
      el('div', { class: 'side-top' }, el('button', { class: 'close', type: 'button', 'aria-label': 'Close', onclick: () => this.close() }, '×')),
      el('header', { class: 'sheet-head' }, el('div', { class: 'sheet-heading' }, this.title, this.subtitle)),
      this.body);
    document.body.append(this.root);
    this.token = 0;
    const place = () => this.place();
    new ResizeObserver(place).observe(sheet.root);
    new MutationObserver(place).observe(sheet.root, { attributes: true, attributeFilter: ['style', 'hidden'] });
    addEventListener('resize', place);
    WIDE.addEventListener('change', () => { if (!WIDE.matches) this.close(); });
  }
  /** Whether there is room for it (a computer). */
  get available() { return WIDE.matches; }
  get isOpen() { return !this.root.hidden; }
  /** {key, title, subtitle, render(body, alive) → cleanup}; replaces what was open. */
  open(panel) {
    this.cleanup?.();
    this.cleanup = null;
    const token = ++this.token;
    this.key = panel.key;
    this.title.textContent = panel.title || '';
    this.subtitle.textContent = panel.subtitle || '';
    this.subtitle.hidden = !panel.subtitle;
    this.body.replaceChildren();
    this.body.scrollTop = 0;
    this.root.hidden = false;
    this.place();
    const r = panel.render?.(this.body, () => token === this.token && !this.root.hidden);
    if (typeof r === 'function') this.cleanup = r;
    this.onChange?.(this.key);
  }
  setTitle(title, subtitle) {
    if (title != null) this.title.textContent = title;
    if (subtitle != null) { this.subtitle.textContent = subtitle; this.subtitle.hidden = !subtitle; }
  }
  close() {
    if (this.root.hidden) return;
    this.token++;
    this.cleanup?.();
    this.cleanup = null;
    this.key = null;
    this.root.hidden = true;
    this.onChange?.(null);
  }
  /** Beside the side panel: to its right, or its left if there is no room. */
  place() {
    if (this.root.hidden) return;
    if (this.sheet.root.hidden) { this.close(); return; }
    const r = this.sheet.root.getBoundingClientRect(), w = Math.min(400, innerWidth - 24);
    const right = r.right + 10 + w <= innerWidth - 10;
    const left = right ? r.right + 10 : Math.max(10, r.left - 10 - w);
    Object.assign(this.root.style, { left: `${left}px`, top: `${Math.max(10, r.top)}px`, width: `${w}px`, maxHeight: `${innerHeight - Math.max(10, r.top) - 12}px` });
  }
}
