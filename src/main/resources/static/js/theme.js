// Runs before first paint (classic script, not a module) so a stored theme never flashes the other one.
try {
  const t = localStorage.getItem('shelved.theme');
  if (t === 'light' || t === 'dark') document.documentElement.dataset.theme = t;
} catch { /* storage blocked: follow the system */ }
