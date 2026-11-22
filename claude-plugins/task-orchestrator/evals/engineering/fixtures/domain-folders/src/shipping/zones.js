const ZONES = { domestic: ['US'], nearby: ['CA', 'MX'] };

export function zoneFor(countryCode) {
  for (const [zone, countries] of Object.entries(ZONES)) {
    if (countries.includes(countryCode)) return zone;
  }
  return 'international';
}
