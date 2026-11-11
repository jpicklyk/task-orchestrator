// provenance-lib.mjs — formats and parses the §8 provenance grammar (line 1 is the grammar line,
// the first line of a delegation-metadata note body; an optional line 2 is 'extra-seats=...'). Pure: no Date, Math.random, fs, process, git. Shared by
// run-exec-lib.mjs's provenance() and the session-retrospective doc-sync test.
//
// Token measure: every figure comes from a seat's transcript meters (input, cache write, cache
// read, output). tokens = input + cache write + output (non-cached); the meters= and seat-meters=
// tails carry the four meters themselves. A seat without meters is 'unknown', never zero.

/** Field order for the structured line's key=value tokens (before any tail). */
export const FIELD_ORDER = [
  'adapter', 'run', 'seats', 'model', 'isolation', 'agents', 'tokens', 'duration',
  'deferred', 'in-run-edges', 'orchestrator-turns',
]

/** Optional trailing tokens, in this order when present. */
export const TAIL_ORDER = ['substituted', 'model-source', 'journal', 'efforts', 'effort-source', 'effort-substituted', 'meters', 'seat-meters']

/** Matches a structured provenance line: 'adapter=<x>' followed by one or more 'key=value' tokens. */
export const STRUCTURED_RE = /^adapter=\S+( [a-z-]+=\S+)+$/

function escapeValue(v) {
  return String(v).replace(/\\/g, '/').replace(/ /g, '%20')
}

function unescapeValue(v) {
  return String(v).replace(/%20/g, ' ')
}

const METERS_RE = /^(\d+)\/(\d+)\/(\d+)\/(\d+)$/

function formatMeters(m) {
  if (!m || m === 'unknown') return 'unknown'
  const n = (v) => (Number.isFinite(v) ? v : 0)
  return `${n(m.input)}/${n(m.cacheWrite)}/${n(m.cacheRead)}/${n(m.output)}`
}

function parseMeters(raw) {
  const m = METERS_RE.exec(raw)
  if (!m) return 'unknown'
  return { input: Number(m[1]), cacheWrite: Number(m[2]), cacheRead: Number(m[3]), output: Number(m[4]) }
}

function numberOrUnknown(v) {
  if (v === undefined || v === null || v === 'unknown') return 'unknown'
  return String(v)
}

/**
 * formatProvenance(fields) -> one grammar line.
 * fields = {adapter, run, seats:[{seat, model}], model, isolation, agents, tokens, duration,
 * deferred, inRunEdges, orchestratorTurns, substituted?:[{seat, requested}], modelSource?,
 * journal?, efforts?:[{seat, effort}], effortSource?, effortSubstituted?:[{seat, requested}],
 * meters?:'unknown'|{input, cacheWrite, cacheRead, output}, seatMeters?:[{seat, meters}],
 * extra?:{}}. FIELD_ORDER tokens first, then present
 * TAIL_ORDER tokens, then extra keys in insertion order. Values contain no spaces (backslashes
 * -> '/', spaces -> '%20'); seat:model, seat:requested and seat:effort pairs joined by ','.
 * meters is '<input>/<cacheWrite>/<cacheRead>/<output>' or 'unknown'; seat-meters is
 * '<seat>:<i>/<cw>/<cr>/<o>' pairs (a seat without meters is '<seat>:unknown').
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
  if (Array.isArray(f.efforts) && f.efforts.length > 0) {
    const eff = f.efforts.map((s) => `${escapeValue(s.seat)}:${escapeValue(s.effort)}`).join(',')
    parts.push(`efforts=${eff}`)
  }
  if (f.effortSource) {
    parts.push(`effort-source=${escapeValue(f.effortSource)}`)
  }
  if (Array.isArray(f.effortSubstituted) && f.effortSubstituted.length > 0) {
    const sub = f.effortSubstituted.map((s) => `${escapeValue(s.seat)}:${escapeValue(s.requested)}`).join(',')
    parts.push(`effort-substituted=${sub}`)
  }
  if (f.meters !== undefined) {
    parts.push(`meters=${formatMeters(f.meters)}`)
  }
  if (Array.isArray(f.seatMeters) && f.seatMeters.length > 0) {
    parts.push(`seat-meters=${f.seatMeters.map((s) => `${escapeValue(s.seat)}:${formatMeters(s.meters)}`).join(',')}`)
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
 * seats=/substituted=/efforts= split on ',' then first ':' -> {seat, model|requested|effort}. Numeric fields
 * (agents/tokens/duration/deferred/in-run-edges/orchestrator-turns) parsed to Number unless
 * 'unknown'; a 'see:<short>' value (notes written before 4.0.1) is kept as text. meters= parses to
 * 'unknown' or {input, cacheWrite, cacheRead, output}; seat-meters= to [{seat, meters}].
 * Unrecognized key=value tokens collect into fields.extra.
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
      case 'efforts':
        fields.efforts = parsePairList(raw, 'effort')
        break
      case 'effort-source':
        fields.effortSource = unescapeValue(raw)
        break
      case 'effort-substituted':
        fields.effortSubstituted = parsePairList(raw, 'requested')
        break
      case 'meters':
        fields.meters = parseMeters(raw)
        break
      case 'seat-meters':
        fields.seatMeters = parsePairList(raw, 'meters').map((p) => ({ seat: p.seat, meters: parseMeters(p.meters || '') }))
        break
      default:
        if (key in NUMERIC_KEYS) {
          const outKey = NUMERIC_KEYS[key]
          fields[outKey] = (raw === 'unknown' || raw.startsWith('see:')) ? unescapeValue(raw) : Number(raw)
        } else {
          extra[key] = unescapeValue(raw)
        }
        break
    }
  }
  if (Object.keys(extra).length > 0) fields.extra = extra
  return fields
}

/**
 * formatExtraSeats(list) -> 'extra-seats=<row>,...' ('' for an empty/absent list). list =
 * [{seat, model, tokens, effort?, cause?, meters?}]; missing tokens -> 'unknown'. A row with meters
 * gets six fields '<seat>:<model>:<tokens>:<effort>:<cause>:<i>/<cw>/<cr>/<o>' (a missing effort or
 * cause -> 'unknown'). A row without meters keeps the five-field form when it has an effort or a
 * cause, else the three-field form. Goes on line 2 of a note.
 */
export function formatExtraSeats(list) {
  if (!Array.isArray(list) || list.length === 0) return ''
  return 'extra-seats=' + list
    .map((s) => {
      const base = `${escapeValue(s.seat)}:${escapeValue(s.model)}:${numberOrUnknown(s.tokens)}`
      const hasMeters = s.meters && s.meters !== 'unknown'
      if (!hasMeters && !s.effort && !s.cause) return base
      const five = `${base}:${escapeValue(s.effort || 'unknown')}:${escapeValue(s.cause || 'unknown')}`
      return hasMeters ? `${five}:${formatMeters(s.meters)}` : five
    })
    .join(',')
}

/** A fix-cycle seat name: '<seat>-a<n>' with n >= 1. */
export const FIX_CYCLE_SEAT_RE = /^(.+)-a([1-9][0-9]*)$/

/**
 * parseExtraSeats(text) -> [{seat, model, tokens, effort?, cause?, meters?}]. Scans the lines AFTER
 * the first for one starting 'extra-seats='; [] when absent or text is not a string. tokens is a
 * Number unless 'unknown'. A six-field row is seat:model:tokens:effort:cause:<i>/<cw>/<cr>/<o> (the
 * row gains meters {input, cacheWrite, cacheRead, output}); a five-field row is
 * seat:model:tokens:effort:cause (notes before 4.0.1); any other row is the three-field form:
 * seat = before the first ':', tokens = after the last ':', model = between (effort and cause absent).
 */
export function parseExtraSeats(text) {
  if (typeof text !== 'string') return []
  const lines = text.replace(/\r\n/g, '\n').split('\n').slice(1)
  const line = lines.find((l) => l.startsWith('extra-seats='))
  if (!line) return []
  return line
    .slice('extra-seats='.length)
    .split(',')
    .filter((s) => s.length > 0)
    .map((s) => {
      const parts = s.split(':')
      // Six-field form: the last part is the four meters, with tokens, effort and cause before it.
      const k = parts.length
      if (k >= 6 && METERS_RE.test(parts[k - 1]) && /^(\d+|unknown)$/.test(parts[k - 4]) && /^[a-z]*$/.test(parts[k - 3]) && /^[a-z-]*$/.test(parts[k - 2])) {
        return {
          seat: unescapeValue(parts[0]),
          model: unescapeValue(parts.slice(1, k - 4).join(':')),
          tokens: parts[k - 4] === 'unknown' ? 'unknown' : Number(parts[k - 4]),
          effort: unescapeValue(parts[k - 3]),
          cause: unescapeValue(parts[k - 2]),
          meters: parseMeters(parts[k - 1]),
        }
      }
      // Five-field form, read from the right so a model id containing ':' still parses: the last
      // three parts must be tokens (digits or 'unknown'), an effort ('' allowed) and a cause ('' allowed).
      const n = parts.length
      if (n >= 5 && /^(\d+|unknown)$/.test(parts[n - 3]) && /^[a-z]*$/.test(parts[n - 2]) && /^[a-z-]*$/.test(parts[n - 1])) {
        return {
          seat: unescapeValue(parts[0]),
          model: unescapeValue(parts.slice(1, n - 3).join(':')),
          tokens: parts[n - 3] === 'unknown' ? 'unknown' : Number(parts[n - 3]),
          effort: unescapeValue(parts[n - 2]),
          cause: unescapeValue(parts[n - 1]),
        }
      }
      const first = s.indexOf(':')
      const last = s.lastIndexOf(':')
      if (first === -1 || first === last) {
        return {
          seat: unescapeValue(first === -1 ? s : s.slice(0, first)),
          model: first === -1 ? undefined : unescapeValue(s.slice(first + 1)),
          tokens: 'unknown',
        }
      }
      const tok = s.slice(last + 1)
      return {
        seat: unescapeValue(s.slice(0, first)),
        model: unescapeValue(s.slice(first + 1, last)),
        tokens: tok === 'unknown' ? 'unknown' : Number(tok),
      }
    })
}
