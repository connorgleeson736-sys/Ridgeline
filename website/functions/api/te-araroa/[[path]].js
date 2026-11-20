// Cloudflare Pages: Te Araroa's current section status and alerts (worker/te-araroa.js).
import { teAraroa } from '../../../worker/te-araroa.js';

export const onRequestGet = ({ params }) => teAraroa([].concat(params.path || []).join('/'));
