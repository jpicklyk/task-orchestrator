export async function bookUps(http, label) {
  const res = await http.post('https://api.ups.example/v2/ship', { to: label.to, line1: label.line1, city: label.city, weight: label.weightGrams });
  if (!res.ok) throw new Error(`ups booking failed: ${res.status}`);
  return { trackingId: res.body.trackingNumber };
}
