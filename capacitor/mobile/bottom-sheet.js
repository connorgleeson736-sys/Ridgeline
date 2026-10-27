// Standard bottom-sheet drag handle with two stable positions.
export function sheetStops(height) {
  const compact = Math.min(136, height * .33);
  return [compact, Math.min(height - 80, Math.max(compact + 80, height * .64))];
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
    if(drag)panel.style.transform=`translate3d(0,${drag.stops[1]-value}px,0)`;
    else panel.style.setProperty('--sheet-height', value + 'px');
    onChange(value, index);
  }
  function snap(next) {
    index = Math.max(0, Math.min(1, next));
    panel.dataset.snap = ['compact', 'expanded'][index];
    handle.setAttribute('aria-expanded', String(index > 0));
    handle.setAttribute('aria-description', panel.dataset.snap + '. Drag, tap, or use up and down arrow keys to resize.');
    apply(sheetStops(viewport())[index]);
  }
  function flush() { frame = undefined; if (pending !== undefined) { apply(pending); pending = undefined; } }
  function end(event, cancelled = false) {
    if (!drag || event.pointerId !== undefined && event.pointerId !== drag.id) return;
    const old = drag;
    if (frame !== undefined) cancelAnimationFrame(frame);
    flush(); drag = undefined; panel.classList.remove('sheet-dragging');panel.style.transform='';
    if (handle.hasPointerCapture(old.id)) handle.releasePointerCapture(old.id);
    // A deliberate swipe advances at least one stop, even if released near its origin.
    const delta = old.y - (event.clientY ?? old.y);
    let next = nearestStop(sheetStops(viewport()), height);
    if (!cancelled && Math.abs(delta) > 32 && next === index) next += delta > 0 ? 1 : -1;
    snap(cancelled ? index : old.moved ? next : index === 1 ? 0 : index + 1);
  }
  handle.addEventListener('pointerdown', event => {
    if (event.button > 0 || event.isPrimary === false || drag) return;
    event.preventDefault(); drag = {id:event.pointerId, y:event.clientY, height, moved:false, stops:sheetStops(viewport())};
    handle.setPointerCapture(event.pointerId); panel.style.setProperty('--sheet-height',drag.stops[1]+'px');panel.classList.add('sheet-dragging');apply(height);
  });
  handle.addEventListener('pointermove', event => {
    if (!drag || event.pointerId !== drag.id) return;
    event.preventDefault();
    const delta = drag.y - event.clientY;
    if (Math.abs(delta) > 4) drag.moved = true;
    const stops = drag.stops; pending = Math.max(stops[0], Math.min(stops[1], drag.height + delta));
    if (frame === undefined) frame = requestAnimationFrame(flush);
  });
  handle.addEventListener('pointerup', event => end(event));
  handle.addEventListener('pointercancel', event => end(event, true));
  handle.addEventListener('lostpointercapture', event => end(event, true));
  handle.addEventListener('click', event => { if (event.detail === 0) snap(index === 1 ? 0 : index + 1); });
  handle.addEventListener('keydown', event => {
    const next = {ArrowUp:index + 1, ArrowDown:index - 1, Home:0, End:1}[event.key];
    if (next !== undefined) { event.preventDefault(); snap(next); }
  });
  addEventListener('resize', () => { if (drag) end({pointerId:drag.id}, true); else snap(index); });
  snap(index);
  return {snap, get height() { return height; }};
}
