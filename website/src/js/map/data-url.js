// The site's data files by version (build.mjs): /data/<version>/…, cached for good;
// a changed file has a new URL, so it is fetched afresh.
import config from './config.js';

export const dataUrl = (path) => (config.dataVersion ? path.replace(/^\/data\//, `/data/${config.dataVersion}/`) : path);
