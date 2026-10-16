// 4.0 result envelope parsing. Pure at import; never throws.

function isPlain(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

/** A 3.x payload carries a top-level `success` key (wins over `ok`). */
export function isLegacyShape(payload) {
  return isPlain(payload) && Object.prototype.hasOwnProperty.call(payload, 'success');
}

function unwrap(r) {
  if (typeof r === 'string') {
    try {
      return JSON.parse(r);
    } catch {
      return null;
    }
  }
  if (!isPlain(r)) return null;
  if (isPlain(r.structuredContent)) return r.structuredContent;
  if (Array.isArray(r.content) && r.content.length > 0 && r.content[0] && typeof r.content[0].text === 'string') {
    try {
      return JSON.parse(r.content[0].text);
    } catch {
      return null;
    }
  }
  if (Object.prototype.hasOwnProperty.call(r, 'success') || typeof r.ok === 'boolean') return r;
  return null;
}

const orNull = (v) => (v === undefined ? null : v);

/** -> {ok, legacy, data, error, results, summary, reqId} or null when no envelope can be found. */
export function parseToolResult(r) {
  try {
    const payload = unwrap(r);
    if (!isPlain(payload)) return null;
    if (isLegacyShape(payload)) {
      return { ok: false, legacy: true, data: null, error: null, results: [], summary: null, reqId: null };
    }
    if (typeof payload.ok !== 'boolean') return null;
    const data = orNull(payload.data);
    const ok = payload.ok;
    let error = null;
    if (!ok) {
      const e = isPlain(payload.error) ? payload.error : {};
      const detail = isPlain(e.detail) ? e.detail : null;
      error = {
        code: orNull(e.code),
        kind: orNull(e.kind),
        message: orNull(e.message),
        retryAfterMs: e.retryAfterMs ?? (detail ? detail.retryAfterMs : undefined) ?? null,
        detail: orNull(e.detail),
        fix: orNull(e.fix),
        index: orNull(e.index),
      };
    }
    return {
      ok,
      legacy: false,
      data,
      error,
      results: isPlain(data) && Array.isArray(data.results) ? data.results : [],
      summary: isPlain(data) && isPlain(data.summary) ? data.summary : null,
      reqId: isPlain(payload.meta) ? (payload.meta.reqId ?? null) : null,
    };
  } catch {
    return null;
  }
}
