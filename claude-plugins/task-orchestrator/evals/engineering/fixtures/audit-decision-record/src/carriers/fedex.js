export async function bookFedex(http, label) {
  const res = await http.post('https://api.fedex.example/ship/v1', { to: label.to, line1: label.line1, city: label.city, weight: label.weightGrams });
  if (!res.ok) throw new Error(`fedex booking failed: ${res.status}`);
  return { trackingId: res.body.trackingId };
}
