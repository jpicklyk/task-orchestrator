const SYMBOLS = { USD: '$', EUR: 'EUR ', GBP: 'GBP ' };

// Formats an amount in minor units (cents) for display, e.g. 12345 USD -> "$123.45".
export function formatAmount(cents, currency = 'USD') {
  const sign = cents < 0 ? '-' : '';
  const abs = Math.abs(cents);
  const major = Math.floor(abs / 100);
  const minor = String(abs % 100).padStart(2, '0');
  return `${sign}${SYMBOLS[currency] ?? ''}${major.toLocaleString('en-US')}.${minor}`;
}
