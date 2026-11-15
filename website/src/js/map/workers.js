// Calls into the map's two worker threads, as promises.
import config from './config.js';

function client(url) {
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
    if (config.linzKey) worker.postMessage({ type: 'key', key: config.linzKey });
    return worker;
  };
  return {
    call(message) { const id = next++; return new Promise((resolve, reject) => { waiting.set(id, { resolve, reject }); start().postMessage({ ...message, id }); }); },
    post(message) { start().postMessage(message); },
  };
}
export const data = client('../workers/data.js');
export const search = client('../workers/search.js');
