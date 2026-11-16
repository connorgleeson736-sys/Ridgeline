// Cloudflare Pages: DOC's current alerts for a walk, track or hut (worker/doc-alerts.js).
import { docAlerts } from '../../../worker/doc-alerts.js';

export const onRequestGet = ({ params }) => docAlerts(params.guid);
