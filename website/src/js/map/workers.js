// Calls into the map's worker threads, as promises.
import config from './config.js';

function client(url, { key = false } = {}) {
  let worker = null, next = 1;
  const waiting = new Map();
  const start = () => {
    if (worker) return worker;
    worker = new Worker(new URL(url, import.meta.url), { type: 'module' });
    worker.onmessage = ({ data }) => {
      const w = waiting.get(data.id);
      if (!w) return;
      waiting.delete(data.id);
      if (data.superseded) w.resolve(undefined); else if (data.error) w.reject(new Error(data.error)); else w.resolve(data.result);
    };
    worker.onerror = () => { for (const w of waiting.values()) w.reject(new Error('Worker failed')); waiting.clear(); worker = null; };
    if (key && config.linzKey) worker.postMessage({ type: 'key', key: config.linzKey });
    return worker;
  };
  return {
    /** With a signal, aborting it tells the worker to drop the call (`cancel`). */
    call(message, signal) {
      const id = next++;
      return new Promise((resolve, reject) => {
        if (signal?.aborted) return reject(new DOMException('Aborted', 'AbortError'));
        waiting.set(id, { resolve, reject });
        start().postMessage({ ...message, id });
        signal?.addEventListener('abort', () => {
          if (!waiting.delete(id)) return;
          worker?.postMessage({ type: 'cancel', target: id });
          reject(new DOMException('Aborted', 'AbortError'));
        }, { once: true });
      });
    },
    post(message) { start().postMessage(message); },
  };
}
export const data = client('../workers/data.js', { key: true });
export const search = client('../workers/search.js');
export const imagery = client('../workers/imagery.js');
