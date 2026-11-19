// "Get notified" sign-ups (the app's launch and its beta): POST /api/notify with
// { email, about: 'android' | 'iphone', website: '' }. Kept in the SIGNUPS bucket
// (private, never served), one object per address (signing up again only updates it).
// The "website" field is a trap left empty by people; an address sends at most a few
// sign-ups an hour from one IP address.
const ABOUT = new Set(['android', 'iphone']);
const ok = (status, body) => new Response(body ? JSON.stringify(body) : null, { status, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' } });

export async function notify(request, env) {
  if (!env.SIGNUPS) return ok(503, { error: 'Sign-ups are not open yet.' });
  if (!(request.headers.get('Content-Type') || '').startsWith('application/json')) return ok(415, { error: 'Expected JSON.' });
  const text = await request.text();
  if (text.length > 2000) return ok(413, { error: 'Too large.' });
  let data;
  try { data = JSON.parse(text); } catch { return ok(400, { error: 'Unreadable.' }); }
  if (data.website) return ok(204);
  const email = String(data.email || '').trim().toLowerCase();
  if (email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(email)) return ok(400, { error: 'That email address does not look right.' });
  const about = ABOUT.has(data.about) ? data.about : 'android';
  // A few sign-ups an hour per IP address (Cloudflare's cache as a small counter).
  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
  const key = new Request(`https://notify-limit.invalid/${encodeURIComponent(ip)}`);
  const seen = await caches.default.match(key);
  const count = seen ? +(await seen.text()) || 0 : 0;
  if (count >= 5) return ok(429, { error: 'Too many sign-ups from here. Try again later.' });
  await caches.default.put(key, new Response(String(count + 1), { headers: { 'Cache-Control': 'max-age=3600' } }));
  const id = [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(email)))].map((b) => b.toString(16).padStart(2, '0')).join('');
  await env.SIGNUPS.put(`signups/${id}.json`, JSON.stringify({ email, about, at: new Date().toISOString() }), { httpMetadata: { contentType: 'application/json' } });
  return ok(204);
}
