// Standard bottom-sheet drag handle with three stable positions.
export function sheetStops(height) {
  const compact = Math.min(136, height * .33);
  const middle = Math.max(compact + 40, height * .4);
  return [compact, middle, Math.min(height - 80, Math.max(middle + 40, height * .64))];
}
export function nearestStop(stops, height) {
  return stops.reduce((best, value, index) => Math.abs(value - height) < Math.abs(stops[best] - height) ? index : best, 0);
}
export function attachSheet(panel, {initial = 0, onChange = () => {}} = {}) {
  const content = document.createElement('div');
  content.className = 'sheet-content';
  while (panel.firstChild) content.append(panel.firstChild);
  const handle = document.createElement('button');
  handle.type = 'button'; handle.className = 'sheet-handle';
  handle.setAttribute('aria-label', 'Resize ' + (panel.id === 'profile-card' ? 'route panel' : 'offline regions panel'));
  handle.innerHTML = '<span aria-hidden="true"></span>';
  panel.append(handle, content);
  let index = initial, height, drag, frame, pending;
  const viewport = () => panel.parentElement.clientHeight;
  function apply(value) {
    height = value;
    panel.style.setProperty('--sheet-height', value + 'px');
    onChange(value, index);
  }
  function snap(next) {
    index = Math.max(0, Math.min(2, next));
    panel.dataset.snap = ['compact', 'middle', 'expanded'][index];
    handle.setAttribute('aria-expanded', String(index > 0));
    handle.setAttribute('aria-description', panel.dataset.snap + '. Drag, tap, or use up and down arrow keys to resize.');
    apply(sheetStops(viewport())[index]);
  }
  function flush() { frame = undefined; if (pending !== undefined) { apply(pending); pending = undefined; } }
  function end(event, cancelled = false) {
    if (!drag || event.pointerId !== undefined && event.pointerId !== drag.id) return;
    const old = drag; drag = undefined;
    if (frame !== undefined) cancelAnimationFrame(frame);
    flush(); panel.classList.remove('sheet-dragging');
    if (handle.hasPointerCapture(old.id)) handle.releasePointerCapture(old.id);
    // A deliberate swipe advances at least one stop, even if released near its origin.
    const delta = old.y - (event.clientY ?? old.y);
    let next = nearestStop(sheetStops(viewport()), height);
    if (!cancelled && Math.abs(delta) > 32 && next === index) next += delta > 0 ? 1 : -1;
    snap(cancelled ? index : old.moved ? next : index === 2 ? 0 : index + 1);
  }
  handle.addEventListener('pointerdown', event => {
    if (event.button > 0 || event.isPrimary === false || drag) return;
    event.preventDefault(); drag = {id:event.pointerId, y:event.clientY, height, moved:false};
    handle.setPointerCapture(event.pointerId); panel.classList.add('sheet-dragging');onChange(height,index);
  });
  handle.addEventListener('pointermove', event => {
    if (!drag || event.pointerId !== drag.id) return;
    event.preventDefault();
    const delta = drag.y - event.clientY;
    if (Math.abs(delta) > 4) drag.moved = true;
    const stops = sheetStops(viewport()); pending = Math.max(stops[0], Math.min(stops[2], drag.height + delta));
    if (frame === undefined) frame = requestAnimationFrame(flush);
  });
  handle.addEventListener('pointerup', event => end(event));
  handle.addEventListener('pointercancel', event => end(event, true));
  handle.addEventListener('lostpointercapture', event => end(event, true));
  handle.addEventListener('click', event => { if (event.detail === 0) snap(index === 2 ? 0 : index + 1); });
  handle.addEventListener('keydown', event => {
    const next = {ArrowUp:index + 1, ArrowDown:index - 1, Home:0, End:2}[event.key];
    if (next !== undefined) { event.preventDefault(); snap(next); }
  });
  addEventListener('resize', () => { if (drag) end({pointerId:drag.id}, true); else snap(index); });
  snap(index);
  return {snap, get height() { return height; }};
}
