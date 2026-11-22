export function writeJson(rows, { pretty = false } = {}) {
  return JSON.stringify(rows, null, pretty ? 2 : 0);
}
