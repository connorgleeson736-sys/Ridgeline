// The site's data files by version (build.mjs): a changed file has a new URL, so it is
// fetched afresh, while unchanged ones are served from the browser's cache.
import config from './config.js';

export const dataUrl = (path) => (config.dataVersion ? `${path}?v=${config.dataVersion}` : path);
