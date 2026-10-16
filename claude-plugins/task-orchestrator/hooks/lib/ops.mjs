// Helpers over the `ops` array of an ops-style tool input and its result. Pure at import; never throws.

function isPlain(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

/** Index-aligned copy of `ops`; non-object entries become {}. [] when absent. */
export function opsOf(toolInput) {
  if (!isPlain(toolInput) || !Array.isArray(toolInput.ops)) return [];
  return toolInput.ops.map((o) => (isPlain(o) ? { ...o } : {}));
}

export function selectOf(toolInput) {
  return isPlain(toolInput) && typeof toolInput.select === 'string' ? toolInput.select : null;
}

function uniq(list) {
  return [...new Set(list)];
}

export function opItemIds(toolInput) {
  return uniq(opsOf(toolInput).map((o) => o.itemId).filter((v) => typeof v === 'string'));
}

/** itemIds of results with ok === true. Never `id` (a note id for notes_write). */
export function resultItemIds(parsed) {
  if (!isPlain(parsed) || !Array.isArray(parsed.results)) return [];
  return uniq(
    parsed.results.filter((r) => isPlain(r) && r.ok === true && typeof r.itemId === 'string').map((r) => r.itemId),
  );
}
