// meters.mjs — the one transcript-meter measure. Pure: no imports, no I/O, no clock, so hooks and
// run-exec-lib.mjs call the same code.
//
// A seat's usage is the four meters of message.usage (input_tokens, cache_creation_input_tokens,
// cache_read_input_tokens, output_tokens) summed over its transcript, one count per message.id.
// tokens is the NON-CACHED measure: input + cacheWrite + output. cacheRead is kept as its own meter
// and never enters tokens.

/**
 * sumUsage(usages) -> {input, cacheWrite, cacheRead, output, tokens} over message.usage objects;
 * a missing or non-numeric meter counts 0. tokens = input + cacheWrite + output.
 */
export function sumUsage(usages) {
  const n = (v) => (Number.isFinite(v) ? v : 0)
  const out = { input: 0, cacheWrite: 0, cacheRead: 0, output: 0, tokens: 0 }
  for (const u of usages || []) {
    if (!u || typeof u !== 'object') continue
    out.input += n(u.input_tokens)
    out.cacheWrite += n(u.cache_creation_input_tokens)
    out.cacheRead += n(u.cache_read_input_tokens)
    out.output += n(u.output_tokens)
  }
  out.tokens = out.input + out.cacheWrite + out.output
  return out
}

/**
 * transcriptMeters(text) -> sumUsage(...) of a subagent transcript's JSONL text, or undefined when
 * no assistant line carries usage. Claude Code repeats a message's usage on each of its
 * content-block lines, so one count per message.id and the last line of an id wins; a line without
 * an id counts on its own.
 */
export function transcriptMeters(text) {
  const usageById = new Map()
  let anon = 0
  for (const line of String(text || '').split('\n')) {
    if (!line.includes('"assistant"')) continue
    let rec
    try { rec = JSON.parse(line) } catch (e) { continue }
    if (!rec || rec.type !== 'assistant') continue
    const u = rec.message && rec.message.usage
    if (!u || typeof u !== 'object') continue
    const id = typeof rec.message.id === 'string' && rec.message.id ? rec.message.id : `#${anon++}`
    usageById.set(id, u)
  }
  if (usageById.size === 0) return undefined
  return sumUsage(usageById.values())
}
