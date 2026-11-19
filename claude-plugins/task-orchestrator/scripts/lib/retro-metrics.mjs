// retro-metrics.mjs - the session-retrospective's numbers, computed once from data a caller has already
// gathered. Pure: no fs, no network, no clock, no process. The gathering (REST reads, transcripts, the
// config file) lives in run-planner.mjs's `retro-metrics` subcommand, which passes the result here.
//
// Output contract 'run-wave/retro-metrics-v1'. The grammar it reads is documented in
// skills/session-retrospective/references/metrics.md; the unit of every token figure is the
// non-cached measure (input + cacheWrite + output), cacheRead kept apart, and 'unknown' is a gap, never zero.

import { createHash } from 'node:crypto';
import { parseProvenance, parseExtraSeats, parseLayerMeters, STRUCTURED_RE, FIX_CYCLE_SEAT_RE } from '../provenance-lib.mjs';
import { TOOLS } from '../../hooks/lib/tool-names.mjs';
import { readSection, childBlock } from '../../hooks/yaml-lite.mjs';

export const CONTRACT = 'run-wave/retro-metrics-v1';

const METER_KEYS = ['input', 'cacheWrite', 'cacheRead', 'output'];
const COVERAGE_CLASSES = ['seat', 'lane', 'red-proofs', 'retrospective', 'coordinator', 'research', 'unlabelled'];

const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const round4 = (v) => Math.round(v * 10000) / 10000;
const lf = (text) => String(text || '').replace(/\r\n/g, '\n').replace(/\r/g, '\n');
const shortOf = (id) => String(id || '').slice(0, 8).toLowerCase();

// ---------------------------------------------------------------------------------------------
// Defects

/**
 * normaliseMessage(text) -> the message with its volatile parts replaced, for fingerprinting:
 * lowercased; a token holding a slash or backslash -> '<path>'; a UUID or a hex run of 8 or more
 * characters -> '<id>'; a digit run -> '<n>'; whitespace collapsed and trimmed.
 */
export function normaliseMessage(text) {
  const lower = String(text === undefined || text === null ? '' : text).toLowerCase();
  const tokens = lower.split(/\s+/).filter((t) => t.length > 0).map((t) => (/[\\/]/.test(t) ? '<path>' : t));
  return tokens
    .join(' ')
    .replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/g, '<id>')
    .replace(/(?<![0-9a-z])[0-9a-f]{8,}(?![0-9a-z])/g, '<id>')
    .replace(/\d+/g, '<n>')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * defectFingerprint({component, tool, op, code, message}) -> 'to-bug-' plus the first 8 hex characters
 * of the sha256 of the lowercased 'component|tool|op|code|<normalised message>'.
 */
export function defectFingerprint({ component, tool, op, code, message } = {}) {
  const key = [component, tool, op, code, normaliseMessage(message)].map((p) => String(p === undefined || p === null ? '' : p)).join('|').toLowerCase();
  return `to-bug-${createHash('sha256').update(key).digest('hex').slice(0, 8)}`;
}

/**
 * parseDefects(body) -> [{tool, op, code, message}] for every 'defect: <tool>, <op>, <code>, <message>'
 * entry of a session-tracking body. The message ends at the next 'defect: ', at '; ' or at the end of
 * the line; an entry with fewer than four comma-separated parts is skipped.
 */
export function parseDefects(body) {
  const out = [];
  for (const line of lf(body).split('\n')) {
    const starts = [];
    const re = /\bdefect:\s*/g;
    let m;
    while ((m = re.exec(line)) !== null) starts.push({ at: m.index, from: m.index + m[0].length });
    for (let i = 0; i < starts.length; i += 1) {
      let end = i + 1 < starts.length ? starts[i + 1].at : line.length;
      const semi = line.indexOf('; ', starts[i].from);
      if (semi !== -1 && semi < end) end = semi;
      const parts = line.slice(starts[i].from, end).trim().split(/,\s*/);
      if (parts.length < 4) continue;
      const [tool, op, code] = parts;
      const message = parts.slice(3).join(', ').trim();
      if (!tool || !op || !code) continue;
      out.push({ tool, op, code, message });
    }
  }
  return out;
}

// ---------------------------------------------------------------------------------------------
// Baselines

function pairsOf(text) {
  const out = {};
  let depth = 0;
  let start = 0;
  const segs = [];
  for (let i = 0; i < text.length; i += 1) {
    const c = text[i];
    if (c === '{') depth += 1;
    else if (c === '}') depth = Math.max(0, depth - 1);
    else if (c === ',' && depth === 0) {
      segs.push(text.slice(start, i));
      start = i + 1;
    }
  }
  segs.push(text.slice(start));
  for (const seg of segs) {
    const m = seg.trim().match(/^([A-Za-z0-9_.-]+)\s*:\s*(.*?)\s*$/);
    if (!m) continue;
    const raw = m[2].replace(/\s+#.*$/, '').replace(/^["']|["']$/g, '');
    out[m[1]] = raw !== '' && Number.isFinite(Number(raw)) ? Number(raw) : raw;
  }
  return out;
}

/**
 * parseBaselines(configText) -> {<name>: {<key>: scalar}} from `extensions.claude-code.retrospective.baselines`
 * of a config.yaml text (each baseline an inline `{ k: v, ... }` map or a nested block), or null when the
 * path is absent. A numeric value is a Number, anything else its text.
 */
export function parseBaselines(configText) {
  const ext = readSection(lf(configText), 'extensions', { blockOnly: true });
  if (!ext) return null;
  const cc = childBlock(ext.lines, 'claude-code');
  const retro = cc && childBlock(cc.lines, 'retrospective');
  const base = retro && childBlock(retro.lines, 'baselines');
  if (!base) return null;
  const out = {};
  const names = new Set();
  for (const line of base.inline !== null ? [] : base.lines) {
    const m = line.match(/^\s*([A-Za-z0-9_.-]+)\s*:/);
    if (m) names.add(m[1]);
  }
  for (const name of names) {
    const block = childBlock(base.lines, name);
    if (!block) continue;
    if (block.inline !== null) out[name] = pairsOf(block.inline);
    else {
      const o = {};
      for (const line of block.lines) {
        const t = line.trim();
        if (t === '' || t.startsWith('#')) continue;
        Object.assign(o, pairsOf(t));
      }
      out[name] = o;
    }
  }
  return Object.keys(out).length > 0 ? out : null;
}

// ---------------------------------------------------------------------------------------------
// Sections

/** sumKnown(values) -> {sum, gaps, any}: the Number values summed; every other value is a gap. */
function sumKnown(values) {
  let sum = 0;
  let gaps = 0;
  let any = false;
  for (const v of values) {
    if (isNum(v)) {
      sum += v;
      any = true;
    } else gaps += 1;
  }
  return { sum, gaps, any };
}

function analyseItem(item) {
  const text = lf(item && item.delegationMetadata);
  const hasText = text.trim() !== '';
  const first = text.split('\n')[0];
  const structured = hasText && STRUCTURED_RE.test(first);
  const form = !hasText ? 'none' : structured ? 'structured' : 'legacy';
  const fields = structured ? parseProvenance(text) : null;
  const extra = hasText
    ? parseExtraSeats(text).map((r) => {
        const fix = FIX_CYCLE_SEAT_RE.exec(r.seat);
        const fieldsCount = r.meters ? 6 : r.effort !== undefined || r.cause !== undefined ? 5 : 3;
        return {
          seat: r.seat,
          type: fix ? fix[1] : r.seat,
          model: r.model === undefined ? null : r.model,
          tokens: r.tokens,
          effort: r.effort === undefined || r.effort === '' ? null : r.effort,
          cause: r.cause === undefined || r.cause === '' ? null : r.cause,
          meters: r.meters || null,
          fields: fieldsCount,
          legacyBasis: fieldsCount !== 6
        };
      })
    : [];
  return { item, text, form, fields, extra };
}

function delegationOf(a) {
  const f = a.fields;
  const efforts = (f && f.efforts) || [];
  const seats = f && Array.isArray(f.seats)
    ? f.seats.map((s) => {
        const fix = FIX_CYCLE_SEAT_RE.exec(s.seat);
        const eff = efforts.find((e) => e.seat === s.seat);
        return { seat: s.seat, type: fix ? fix[1] : s.seat, model: s.model === undefined ? null : s.model, effort: eff && eff.effort ? eff.effort : null };
      })
    : [];
  let isolation = null;
  if (f && f.isolation) isolation = f.isolation;
  else if (a.form === 'legacy') isolation = /worktree/i.test(a.text) ? 'worktree' : /inline/i.test(a.text) ? 'inline' : null;
  return { item: a.item.id, form: a.form, seats, isolation, extraSeats: a.extra };
}

function runOf(a) {
  return (a.fields && a.fields.run) || (a.item && a.item.runId) || null;
}

function perRunRows(analysed) {
  const byRun = new Map();
  for (const a of analysed) {
    if (a.form !== 'structured') continue;
    const run = runOf(a) || 'unknown';
    if (!byRun.has(run)) byRun.set(run, []);
    byRun.get(run).push(a.fields);
  }
  const rows = [];
  for (const [run, lines] of byRun) {
    const field = (key) => {
      const s = sumKnown(lines.map((l) => l[key]));
      return s.any ? s.sum : 'unknown';
    };
    const cache = { known: 0, unknown: 0 };
    for (const l of lines) {
      if (isObj(l.meters)) cache.known += isNum(l.meters.cacheRead) ? l.meters.cacheRead : 0;
      else cache.unknown += 1;
    }
    // The turns are a run-level figure that provenance writes unchanged on every item line, so the run
    // takes it once (the largest numeric value), never the sum over its lines.
    const turns = lines.map((l) => l.orchestratorTurns).filter(isNum);
    rows.push({
      run,
      adapter: lines[0].adapter || null,
      agents: field('agents'),
      tokens: field('tokens'),
      cacheRead: cache,
      duration: field('duration'),
      orchestratorTurns: turns.length > 0 ? Math.max(...turns) : 'unknown'
    });
  }
  return rows;
}

function layerLinesOf(analysed, extraLines) {
  const seen = new Set();
  const out = [];
  const lines = [...(Array.isArray(extraLines) ? extraLines : [])];
  for (const a of analysed) for (const l of a.text.split('\n')) if (l.startsWith('layer-meters=')) lines.push(l);
  for (const line of lines) {
    const parsed = parseLayerMeters(line);
    if (!parsed || !parsed.run) continue;
    const key = `${parsed.run}\u0000${Object.keys(parsed.layers).sort().join(',')}\u0000${JSON.stringify(parsed.layers)}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(parsed);
  }
  return out;
}

const layerRow = (run, layer, m) =>
  isObj(m)
    ? { run, layer, input: m.input, cacheWrite: m.cacheWrite, cacheRead: m.cacheRead, output: m.output, tokens: m.input + m.cacheWrite + m.output, unknown: 0 }
    : { run, layer, input: null, cacheWrite: null, cacheRead: null, output: null, tokens: null, unknown: 1 };

function perLayerRows(analysed, layerLines) {
  const byRun = new Map();
  for (const l of layerLines) {
    if (!byRun.has(l.run)) byRun.set(l.run, {});
    const acc = byRun.get(l.run);
    for (const [name, m] of Object.entries(l.layers)) if (!(name in acc)) acc[name] = m;
  }
  const rows = [];
  for (const [run, layers] of byRun) {
    const seats = { run, layer: 'seats', input: 0, cacheWrite: 0, cacheRead: 0, output: 0, tokens: 0, unknown: 0, legacyBasis: 0 };
    const add = (m) => {
      for (const k of METER_KEYS) seats[k] += isNum(m[k]) ? m[k] : 0;
    };
    for (const a of analysed) {
      if (runOf(a) !== run) continue;
      if (a.form === 'structured') {
        if (isObj(a.fields.meters)) add(a.fields.meters);
        else seats.unknown += 1;
      }
      for (const r of a.extra) {
        if (r.fields === 6 && isObj(r.meters)) add(r.meters);
        else seats.legacyBasis += 1;
      }
    }
    seats.tokens = seats.input + seats.cacheWrite + seats.output;
    rows.push(seats);
    for (const name of ['coordinator', 'session']) if (name in layers) rows.push(layerRow(run, name, layers[name]));
  }
  return rows;
}

function fixCycleOf(analysed, perRun) {
  const verdicts = new Map();
  const loop = new Map();
  const other = [];
  let unknownTokenSeats = 0;
  const allExtraTokens = [];
  for (const a of analysed) {
    const tracking = lf(a.item && a.item.sessionTracking);
    for (const m of tracking.matchAll(/Review:\s*(\S+)\s+after\s+A(\d+)/g)) verdicts.set(`${a.item.id}\u0000${Number(m[2])}`, m[1]);
    for (const r of a.extra) {
      allExtraTokens.push(r.tokens);
      if (!isNum(r.tokens)) unknownTokenSeats += 1;
      if (r.cause === 'carry-in' || r.cause === 'breach') {
        other.push({ item: a.item.id, seat: r.seat, cause: r.cause, tokens: r.tokens });
        continue;
      }
      const fix = FIX_CYCLE_SEAT_RE.exec(r.seat);
      if (!fix || (r.cause !== 'review' && r.cause !== 'test')) continue;
      const n = Number(fix[2]);
      const key = `${a.item.id}\u0000${n}`;
      if (!loop.has(key)) loop.set(key, { item: a.item.id, cycle: n, rows: [] });
      loop.get(key).rows.push(r);
    }
  }
  const cycles = [...loop.values()].map((c) => {
    const s = sumKnown(c.rows.map((r) => r.tokens));
    return {
      item: c.item,
      cycle: c.cycle,
      cause: c.rows[0].cause,
      seats: c.rows.map((r) => r.seat),
      model: c.rows[0].model,
      effort: c.rows[0].effort,
      tokens: s.any ? s.sum : 'unknown',
      verdict: verdicts.get(`${c.item}\u0000${c.cycle}`) || null
    };
  });
  const loopTokens = sumKnown([...loop.values()].flatMap((c) => c.rows.map((r) => r.tokens))).sum;
  const runTokens = sumKnown(perRun.map((r) => r.tokens)).sum + sumKnown(allExtraTokens).sum;
  const findings = [];
  if (runTokens > 0 && loopTokens * 4 > runTokens) findings.push('loop-share-over-25pct');
  return { cycles, other, loopTokens, runTokens, loopShare: runTokens > 0 ? round4(loopTokens / runTokens) : null, unknownTokenSeats, findings };
}

function coverageOf(doc, analysed) {
  if (!isObj(doc) || !Array.isArray(doc.agents)) return { measured: false, reason: 'no session meters' };
  const rows = COVERAGE_CLASSES.map((cls) => {
    const c = isObj(doc.byClass) && isObj(doc.byClass[cls]) ? doc.byClass[cls] : {};
    const n = (k) => (isNum(c[k]) ? c[k] : 0);
    return { class: cls, agents: n('agents'), input: n('input'), cacheWrite: n('cacheWrite'), cacheRead: n('cacheRead'), output: n('output'), tokens: n('tokens') };
  });
  const names = new Map();
  for (const a of analysed) {
    const set = new Set((a.fields && a.fields.seats ? a.fields.seats : []).map((s) => s.seat));
    for (const r of a.extra) set.add(r.seat);
    const key = shortOf(a.item.id);
    names.set(key, new Set([...(names.get(key) || []), ...set]));
  }
  let recorded = 0;
  let total = 0;
  const unlabelledAgents = [];
  for (const row of doc.agents) {
    const tokens = isNum(row.tokens) ? row.tokens : 0;
    total += tokens;
    const label = typeof row.label === 'string' ? row.label : null;
    const m = label === null ? null : /^([^:]+):([0-9a-f]{8})$/.exec(label);
    if (m && names.has(m[2]) && names.get(m[2]).has(m[1])) recorded += tokens;
    if (row.class === 'unlabelled') unlabelledAgents.push({ label: label === null ? '(no description)' : label, tokens });
  }
  return {
    rows,
    main: isObj(doc.main) ? doc.main : null,
    recorded,
    total,
    pct: total > 0 ? Math.round((recorded / total) * 1000) / 10 : null,
    unlabelledAgents
  };
}

function noteVolumeOf(nv, baselines) {
  if (!isObj(nv)) return { measured: false, reason: 'not measured' };
  if (nv.measured === false) return { measured: false, reason: nv.reason || 'unknown' };
  const byRole = Array.isArray(nv.byRole) ? nv.byRole : [];
  const byKey = Array.isArray(nv.byNoteKey) ? nv.byNoteKey : [];
  const lengthsByType = isObj(nv.maxLengthsByType) ? nv.maxLengthsByType : {};
  // The report groups by one dimension, so a key's largest write has no type: judge it against the caps of
  // every type that declares the key (capsOf -> {<type>: n}).
  const capsOf = (noteKey) => {
    const caps = {};
    for (const [type, keys] of Object.entries(lengthsByType)) if (isObj(keys) && isNum(keys[noteKey])) caps[type] = keys[noteKey];
    return caps;
  };
  const roleOf = (r) => (isObj(r.key) ? r.key.role : undefined);
  const keyOf = (r) => (isObj(r.key) ? r.key.noteKey : undefined);
  const bytes = (r) => (isNum(r.bytes) ? r.bytes : 0);
  const totalBytes = byRole.reduce((s, r) => s + bytes(r), 0);
  const review = byRole.filter((r) => roleOf(r) === 'review').reduce((s, r) => s + bytes(r), 0);
  const work = byRole.find((r) => roleOf(r) === 'work');
  const checklist = byKey.find((r) => keyOf(r) === 'review-checklist');
  let largest = null;
  for (const r of byKey) if (keyOf(r) !== undefined && isNum(r.maxBytes) && (largest === null || r.maxBytes > largest.maxBytes)) largest = { noteKey: keyOf(r), maxBytes: r.maxBytes };
  const overruns = [];
  for (const r of byKey) {
    const k = keyOf(r);
    if (k === undefined || !isNum(r.maxBytes)) continue;
    const caps = capsOf(k);
    const values = Object.values(caps);
    if (values.length === 0) continue;
    const smallest = Math.min(...values);
    if (r.maxBytes <= smallest) continue;
    const entry = { noteKey: k, maxBytes: r.maxBytes, maxLength: smallest };
    if (new Set(values).size > 1) entry.capsByType = caps;
    overruns.push(entry);
  }
  return {
    window: nv.window || null,
    reviewShare: totalBytes > 0 ? round4(review / totalBytes) : null,
    reviewChecklistAvgBytes: checklist && isNum(checklist.avgBytes) ? checklist.avgBytes : null,
    largest,
    workAvgBytes: work && isNum(work.avgBytes) ? work.avgBytes : null,
    overruns,
    rewrites: byRole.reduce((s, r) => s + (isNum(r.rewrites) ? r.rewrites : 0), 0),
    baselines: baselines || null
  };
}

function selfOf(self, runTokens) {
  if (!isObj(self)) return { measured: false, reason: 'no self label' };
  if (self.measured === false) return { measured: false, reason: self.reason || 'unknown' };
  const row = self.row;
  if (!isObj(row)) return { measured: false, reason: self.reason || 'no transcript carries the label' };
  const n = (k) => (isNum(row[k]) ? row[k] : 0);
  const tokens = isNum(row.tokens) ? row.tokens : n('input') + n('cacheWrite') + n('output');
  return {
    label: self.label || row.label || null,
    tokens,
    input: n('input'),
    cacheWrite: n('cacheWrite'),
    cacheRead: n('cacheRead'),
    output: n('output'),
    wallMs: isNum(self.wallMs) ? self.wallMs : null,
    analysedShare: runTokens > 0 ? round4(tokens / runTokens) : null,
    partial: true
  };
}

/**
 * retroMetrics(input) -> the 'run-wave/retro-metrics-v1' document.
 * input = {
 *   items: [{id, type?, runId?, delegationMetadata?: string|null, sessionTracking?: string|null}],
 *   layerLines?: string[]                  extra 'layer-meters=' lines (the run report's); lines inside a
 *                                          delegationMetadata body are found too,
 *   sessionMeters?: session-meters-v1 doc  (run-planner session-meters output) or null,
 *   noteVolume?: {window, byRole: [report notes rows], byNoteKey: [rows], maxLengthsByType: {<type>: {<key>: n}}}
 *                or {measured: false, reason}; an overrun is a key's maxBytes above the smallest cap among the
 *                types declaring it, with capsByType {<type>: n} added when those caps differ,
 *   baselines?: object|null,               parseBaselines() output, echoed in noteVolume.baselines,
 *   self?: {label, row: agents row|null, wallMs?} | {measured: false, reason} | null
 * }
 * -> {contract, delegations, perRun, perLayer, fixCycle, sessionCoverage, noteVolume, defects, self}.
 * A section whose input is absent reads {measured: false, reason}; no section throws on a missing field.
 */
export function retroMetrics(input) {
  const inp = isObj(input) ? input : {};
  const items = (Array.isArray(inp.items) ? inp.items : []).filter(isObj);
  const analysed = items.map(analyseItem);
  const perRun = perRunRows(analysed);
  const fixCycle = fixCycleOf(analysed, perRun);
  const defects = [];
  for (const it of items) {
    for (const d of parseDefects(it.sessionTracking)) {
      const component = TOOLS.includes(d.tool) ? 'server' : 'plugin';
      defects.push({ item: it.id, component, tool: d.tool, op: d.op, code: d.code, message: d.message, fingerprint: defectFingerprint({ component, ...d }) });
    }
  }
  return {
    contract: CONTRACT,
    delegations: analysed.map(delegationOf),
    perRun,
    perLayer: perLayerRows(analysed, layerLinesOf(analysed, inp.layerLines)),
    fixCycle,
    sessionCoverage: coverageOf(inp.sessionMeters, analysed),
    noteVolume: noteVolumeOf(inp.noteVolume, inp.baselines),
    defects,
    self: selfOf(inp.self, fixCycle.runTokens)
  };
}
