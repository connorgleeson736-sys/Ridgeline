// The pages' small behaviour: app links that do not exist yet read "coming soon".
for (const link of document.querySelectorAll('[data-beta], [data-play]')) {
  const href = link.getAttribute('href');
  if (!href) {
    link.setAttribute('aria-disabled', 'true');
    link.removeAttribute('href');
    link.textContent = link.hasAttribute('data-play') ? 'Google Play – coming soon' : 'Beta – opening soon';
  }
}
