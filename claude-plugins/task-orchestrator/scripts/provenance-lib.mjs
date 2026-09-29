// provenance-lib.mjs — formats and parses the §8 provenance grammar (one line, first line of
// a delegation-metadata note body). Pure: no Date, Math.random, fs, process, git. Shared by
// run-exec-lib.mjs's provenance() and the session-retrospective doc-sync test.

/** Field order for the structured line's key=value tokens (before any tail). */
export const FIELD_ORDER = [
  'adapter', 'run', 'seats', 'model', 'isolation', 'agents', 'tokens', 'duration',
  'deferred', 'in-run-edges', 'orchestrator-turns',
]

/** Optional trailing tokens, in this order when present. */
export const TAIL_ORDER = ['substituted', 'model-source', 'journal']

/** Matches a structured provenance line: 'adapter=<x>' followed by one or more 'key=value' tokens. */
export const STRUCTURED_RE = /^adapter=\S+( [a-z-]+=\S+)+$/

function escapeValue(v) {
  return String(v).replace(/\\/g, '/').replace(/ /g, '%20')
}

function unescapeValue(v) {
  return String(v).replace(/%20/g, ' ')
}

function numberOrUnknown(v) {
  if (v === undefined || v === null || v === 'unknown') return 'unknown'
  return String(v)
}

/**
 * formatProvenance(fields) -> one grammar line.
 * fields = {adapter, run, seats:[{seat, model}], model, isolation, agents, tokens, duration,
 * deferred, inRunEdges, orchestratorTurns, substituted?:[{seat, requested}], modelSource?,
 * journal?, extra?:{}}. FIELD_ORDER tokens first, then present TAIL_ORDER tokens, then
 * extra keys in insertion order. Values contain no spaces (backslashes -> '/', spaces ->
 * '%20'); seat:model pairs joined by ','.
 */
export function formatProvenance(fields) {
  const f = fields || {}
  const parts = []

  for (const key of FIELD_ORDER) {
    switch (key) {
      case 'adapter':
        parts.push(`adapter=${escapeValue(f.adapter)}`)
        break
      case 'run':
        parts.push(`run=${escapeValue(f.run)}`)
        break
      case 'seats': {
        const seats = (f.seats || []).map((s) => `${escapeValue(s.seat)}:${escapeValue(s.model)}`).join(',')
        parts.push(`seats=${seats}`)
        break
      }
      case 'model':
        parts.push(`model=${escapeValue(f.model)}`)
        break
      case 'isolation':
        parts.push(`isolation=${escapeValue(f.isolation)}`)
        break
      case 'agents':
        parts.push(`agents=${numberOrUnknown(f.agents)}`)
        break
      case 'tokens':
        parts.push(`tokens=${numberOrUnknown(f.tokens)}`)
        break
      case 'duration':
        parts.push(`duration=${numberOrUnknown(f.duration)}`)
        break
      case 'deferred':
        parts.push(`deferred=${numberOrUnknown(f.deferred)}`)
        break
      case 'in-run-edges':
        parts.push(`in-run-edges=${numberOrUnknown(f.inRunEdges)}`)
        break
      case 'orchestrator-turns':
        parts.push(`orchestrator-turns=${numberOrUnknown(f.orchestratorTurns)}`)
        break
      default:
        break
    }
  }

  if (Array.isArray(f.substituted) && f.substituted.length > 0) {
    const sub = f.substituted.map((s) => `${escapeValue(s.seat)}:${escapeValue(s.requested)}`).join(',')
    parts.push(`substituted=${sub}`)
  }
  if (f.modelSource) {
    parts.push(`model-source=${escapeValue(f.modelSource)}`)
  }
  if (f.journal) {
    parts.push(`journal=${escapeValue(f.journal)}`)
  }

  if (f.extra) {
    for (const [k, v] of Object.entries(f.extra)) {
      parts.push(`${k}=${escapeValue(v)}`)
    }
  }

  return parts.join(' ')
}

const NUMERIC_KEYS = {
  agents: 'agents',
  tokens: 'tokens',
  duration: 'duration',
  deferred: 'deferred',
  'in-run-edges': 'inRunEdges',
  'orchestrator-turns': 'orchestratorTurns',
}

function parsePairList(value, valueKey) {
  return value
    .split(',')
    .filter((s) => s.length > 0)
    .map((s) => {
      const idx = s.indexOf(':')
      if (idx === -1) return { seat: unescapeValue(s), [valueKey]: undefined }
      return { seat: unescapeValue(s.slice(0, idx)), [valueKey]: unescapeValue(s.slice(idx + 1)) }
    })
}

/**
 * parseProvenance(text) -> fields object.
 * Reads the FIRST line of text. Non-string/empty/non-matching (STRUCTURED_RE) -> {legacy:true}.
 * seats=/substituted= split on ',' then first ':' -> {seat, model|requested}. Numeric fields
 * (agents/tokens/duration/deferred/in-run-edges/orchestrator-turns) parsed to Number unless
 * 'unknown'. Unrecognized key=value tokens collect into fields.extra.
 */
export function parseProvenance(text) {
  if (typeof text !== 'string' || text.length === 0) return { legacy: true }
  const normalized = text.replace(/\r\n/g, '\n')
  const firstLine = normalized.split('\n')[0]
  if (!STRUCTURED_RE.test(firstLine)) return { legacy: true }

  const fields = {}
  const extra = {}
  const tokens = firstLine.split(' ')
  for (const tok of tokens) {
    const eq = tok.indexOf('=')
    if (eq === -1) continue
    const key = tok.slice(0, eq)
    const raw = tok.slice(eq + 1)
    switch (key) {
      case 'adapter':
        fields.adapter = unescapeValue(raw)
        break
      case 'run':
        fields.run = unescapeValue(raw)
        break
      case 'seats':
        fields.seats = parsePairList(raw, 'model')
        break
      case 'model':
        fields.model = unescapeValue(raw)
        break
      case 'isolation':
        fields.isolation = unescapeValue(raw)
        break
      case 'substituted':
        fields.substituted = parsePairList(raw, 'requested')
        break
      case 'model-source':
        fields.modelSource = unescapeValue(raw)
        break
      case 'journal':
        fields.journal = unescapeValue(raw)
        break
      default:
        if (key in NUMERIC_KEYS) {
          const outKey = NUMERIC_KEYS[key]
          fields[outKey] = raw === 'unknown' ? 'unknown' : Number(raw)
        } else {
          extra[key] = unescapeValue(raw)
        }
        break
    }
  }
  if (Object.keys(extra).length > 0) fields.extra = extra
  return fields
}
