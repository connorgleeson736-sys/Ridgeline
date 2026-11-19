// The pages' small behaviour: app links that do not exist yet read "coming soon",
// and the "Get notified" form (worker/notify.js).
for (const link of document.querySelectorAll('[data-beta], [data-play]')) {
  const href = link.getAttribute('href');
  if (!href) {
    link.setAttribute('aria-disabled', 'true');
    link.removeAttribute('href');
    link.textContent = link.hasAttribute('data-play') ? 'Google Play – coming soon' : 'Beta – opening soon';
  }
}
for (const form of document.querySelectorAll('[data-notify]')) {
  const status = form.querySelector('.notify-status');
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const data = Object.fromEntries(new FormData(form));
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(String(data.email || '').trim())) { status.textContent = 'Enter your email address.'; return; }
    const button = form.querySelector('button'); button.disabled = true; status.textContent = 'Sending…';
    try {
      const r = await fetch('/api/notify', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) });
      if (r.ok) { form.reset(); status.textContent = "Thanks – we'll email you when it's ready."; }
      else status.textContent = (await r.json().catch(() => null))?.error || 'That did not work. Try again later.';
    } catch { status.textContent = 'That did not work. Check your connection.'; }
    button.disabled = false;
  });
}

