export const meta = {
  name: 'deep-codebase-audit',
  description: 'Barrier-per-phase codebase audit: reviewers that each follow the method skill of their own slice, gap critic, stratified adversarial verify, structured synthesis, and a triage-derived findings proposal.',
  whenToUse: 'Rare and expensive: a broad multi-agent pass over existing code and documentation, by slice (focus picks which). Presets quick|standard|full trade reviewer/verifier fan-out for cost; runId, date, and reportPath are required args; measured (optional: an audit/digest-v1 object the run-audit skill measured before launch) puts the measured structure into reviewer prompts, and items.policy (gated, bugs or all) picks which findings the proposal selects; dispatchClasses (required: the classes of dispatch-policy.json) gives each phase key its model and effort, and models and efforts override them per phase key; focus (optional: code, docs or all; default all) limits the audit to code slices, documentation slices or both; methodSkill (optional) replaces the method skill of code slices, and a slice or lens may carry its own methodSkill. quick and standard fit the default maxAgents (60); full projects past it and needs allowLarge:true or a raised maxAgents. scope.candidateCount sets how many measured candidates get their own reviewer, and verify.groupSize how many findings one verifier call judges. full runs roughly 90 minutes and about 30M tokens — prefer quick or standard for routine sweeps.',
  phases: [
    { title: 'Scope' }, { title: 'Review' }, { title: 'Gaps' }, { title: 'Merge' },
    { title: 'Verify' }, { title: 'Synthesize' }, { title: 'Triage' },
  ],
}

// @core-begin

const SEVERITY_RANK = { critical: 4, high: 3, medium: 2, low: 1 }
const DEFAULT_METHOD_SKILL = 'task-orchestrator:auditing-codebase'
const DOCS_METHOD_SKILL = 'task-orchestrator:auditing-documentation'
const FOCUS_VALUES = ['code', 'docs', 'all']
const LENS_KEYS = ['evidence', 'significance', 'consequence', 'combined']
const RATINGS = ['act-now', 'plan-it', 'watch']
const RATING_RANK = { 'act-now': 3, 'plan-it': 2, watch: 1 }
const ITEM_POLICIES = ['gated', 'bugs', 'all']

const DISPATCH_CLASSES = {
  scope: 'research',
  review: 'audit-review',
  'review-candidate': 'audit-review',
  'review-architecture': 'review',
  critic: 'research',
  merge: 'extraction',
  'verify-group': 'research',
  'verify-group-high': 'audit-verify-high',
  synth: 'audit-synthesis',
  triage: 'extraction',
}
const FAN_OUT_KEYS = ['review', 'review-candidate', 'verify-group', 'verify-group-high', 'triage', 'merge']

const ARCHITECTURE_LENS = {
  key: 'architecture', label: 'Architecture', kind: 'code',
  focus: 'State the intended architecture from the project' + "'" + 's own records (its conventions, decision records and guard tests), then check the dependency structure and cycles against it and report each divergence. Take a pattern inventory: each recurring pattern, its instances, and where the instances diverge; two patterns for one job is a finding.',
}

const PRESETS = {
  quick: { reviewerCount: 4, candidateCount: 3, gaps: { enabled: true, max: 1 }, verify: { highLenses: ['combined'], defaultLenses: ['combined'], maxFindings: 20, groupSize: 5 } },
  standard: { reviewerCount: 8, candidateCount: 6, gaps: { enabled: true, max: 2 }, verify: { highLenses: ['evidence', 'significance', 'consequence'], defaultLenses: ['combined'], maxFindings: 40, groupSize: 5 } },
  full: { reviewerCount: 16, candidateCount: 12, gaps: { enabled: true, max: 4 }, verify: { highLenses: ['evidence', 'significance', 'consequence'], defaultLenses: ['combined'], maxFindings: 100, groupSize: 5 } },
}

const CATEGORIES = [
  'layering', 'coupling', 'cohesion', 'duplication', 'consistency', 'concurrency', 'transactions',
  'error-handling', 'extensibility', 'security-architecture', 'performance-architecture', 'testability',
  'operability', 'api-design', 'data-model', 'documentation-scope', 'documentation-drift', 'dead-code',
  'complexity', 'architecture-conformance', 'test-quality',
]

const DEFAULT_CLASS_MAP = {
  concurrency: 'bug', transactions: 'bug', 'security-architecture': 'bug', 'error-handling': 'bug', 'data-model': 'bug',
  'documentation-scope': 'tech-debt', 'documentation-drift': 'tech-debt', 'dead-code': 'tech-debt', duplication: 'tech-debt', coupling: 'tech-debt',
  cohesion: 'tech-debt', layering: 'tech-debt', consistency: 'tech-debt', extensibility: 'tech-debt',
  testability: 'tech-debt', 'api-design': 'tech-debt', 'performance-architecture': 'tech-debt', operability: 'tech-debt',
  complexity: 'tech-debt', 'architecture-conformance': 'tech-debt', 'test-quality': 'tech-debt',
}

const TYPE_MAP_KEY = { bug: 'bug', 'tech-debt': 'techDebt' }

const VERIFY_LENS_INSTRUCTIONS = {
  evidence: 'LENS = EVIDENCE. Open every cited location and check each factual claim in the evidence (what calls what, what is duplicated where and in both places, sizes, missing guards or boundaries). Actively search for counter-evidence elsewhere: a shared helper that already centralizes the logic, a check performed higher in the call stack, a guard in another layer, a test that pins the invariant. Set refuted=true if the CORE claim is false. If it is partly wrong, set refuted=false, evidenceAccurate=false, and give correctedEvidence.',
  significance: 'LENS = SIGNIFICANCE. Argue that this is NOT a real problem worth reporting: an intentional, documented design; severity overstated per the rubric; the recommended change not worth its cost; or already tracked. Set refuted=true only if it is not a genuine problem worth reporting. Otherwise set adjustedSeverity per the rubric and explain.',
  consequence: 'LENS = CONSEQUENCE. Try to demonstrate a concrete consequence: for correctness/concurrency/security findings, a realistic sequence of calls or configuration that produces an observable failure, traced through the code; for maintainability findings, concrete change-amplification evidence (history showing past changes or fixes that had to touch the duplicated places). Set refuted=true if no concrete consequence can be established and the claim is purely theoretical.',
  combined: 'LENS = COMBINED. (1) Open every cited location and verify the factual claims; search for counter-evidence (shared helpers, checks performed elsewhere, guards, tests). (2) Consider whether it is intentional documented design, overstated per the rubric, or already tracked. Set refuted=true if the core claim is false or it is not a genuine problem worth reporting; if partly wrong set evidenceAccurate=false with correctedEvidence.',
}

const DOCS_CATEGORIES = ['documentation-scope', 'documentation-drift']

const DOCS_VERIFY_RULE = 'DOCUMENTATION FINDING: judge this finding by the documentation method, not by the code reading of the lens above. A document owes accuracy where it makes a claim and never owes coverage, and a cut needs no proof that the text is wrong. A passage that is accurate today but restates what the code or a search shows, copies a default, limit or other value that has one defining file, or does not serve the reader of its document is still a valid finding: do not refute it because the text is true, because no concrete consequence can be shown, or because the choice looks intentional. Refute it only when the cited text is not there; when the document claims something the code cannot show (a reason, a rejected option, a trap, an invariant or an absence), so the passage is not a restatement; when the finding claims drift in a record (a changelog, release notes, an accepted decision record), where an old name or value is history; or when the passage is a block a tool generates from the code and a check keeps current. Check the cited facts as before.'

/** fromDocsSlice(finding, docsKeys) -> true when any slice the finding came from (its sources, else its source) is a docs slice */
function fromDocsSlice(f, docsKeys) {
  const keys = f.sources || (f.source ? [f.source] : [])
  return keys.some((k) => docsKeys.has(k))
}

/** verifyLensText(lensKey, category, fromDocs = false) -> the lens instruction, followed by the documentation rule when the finding came from a docs slice or is in a documentation category */
function verifyLensText(lensKey, category, fromDocs = false) {
  const lens = VERIFY_LENS_INSTRUCTIONS[lensKey]
  return !fromDocs && DOCS_CATEGORIES.indexOf(category) === -1 ? lens : lens + '\n\n' + DOCS_VERIFY_RULE
}

/**
 * dispatchTag({model?, effort?}) -> '[<model>/<effort>]' | '[<model>]' | '[inherit]'. The label
 * suffix that shows the assigned model and effort wherever the label is displayed (the workflow
 * pane, the background-task list); 'inherit' makes an unset model visible instead of silent.
 */
function dispatchTag(d) {
  const model = d && d.model ? d.model : 'inherit'
  return d && d.effort ? `[${model}/${d.effort}]` : `[${model}]`
}

/** agentOpts(plan, dispatchKey, base) -> base with model and effort from plan.dispatch[dispatchKey] and the label suffixed by dispatchTag; never leaves a model unset */
function agentOpts(plan, dispatchKey, base) {
  const d = plan.dispatch[dispatchKey]
  const opts = Object.assign({}, base, { model: d.model, effort: d.effort })
  opts.label = `${base.label} ${dispatchTag(opts)}`
  return opts
}

/** testPathsOf(paths) -> the paths (backslash -> slash) with a segment named test, tests, __tests__, spec or specs, compared lowercased */
function testPathsOf(paths) {
  const names = ['test', 'tests', '__tests__', 'spec', 'specs']
  return (paths || []).map((p) => String(p).split(String.fromCharCode(92)).join('/')).filter((p) => p.split('/').some((seg) => names.indexOf(seg.toLowerCase()) !== -1))
}

const DIGEST_MAX_CHARS = 8000
const SLICE_DIGEST_MAX_CHARS = 3000
const MERGE_BATCH_MAX = 40

/** normPathText(p) -> p with backslashes as '/', no leading './' and no trailing '/'; '.' and '' both give '' (the whole tree) */
function normPathText(p) {
  let out = String(p == null ? '' : p).split(String.fromCharCode(92)).join('/')
  while (out.indexOf('./') === 0) out = out.slice(2)
  while (out.length && out.charAt(out.length - 1) === '/') out = out.slice(0, -1)
  return out === '.' ? '' : out
}

/** underPath(x, p) -> true when p is the whole tree (''), x equals p, or x starts with p + '/' (both already through normPathText) */
function underPath(x, p) {
  return p === '' || x === p || x.indexOf(p + '/') === 0
}

/** primaryPath(locations) -> string: the first location, backslashes to '/', no leading './', a trailing ':<digits>[-<digits>]' stripped; '' with no location */
function primaryPath(locations) {
  const first = Array.isArray(locations) && locations.length ? locations[0] : null
  if (typeof first !== 'string' || !first) return ''
  let out = first.split(String.fromCharCode(92)).join('/')
  out = out.replace(/:\d+(-\d+)?$/, '')
  while (out.indexOf('./') === 0) out = out.slice(2)
  return out
}

/** fnv1a32(text) -> 8 lowercase hex digits: FNV-1a 32-bit over the UTF-8 bytes of text (offset 0x811c9dc5, prime 0x01000193) */
function fnv1a32(text) {
  const bytes = []
  for (const ch of String(text)) {
    const c = ch.codePointAt(0)
    if (c < 0x80) bytes.push(c)
    else if (c < 0x800) bytes.push(0xc0 | (c >> 6), 0x80 | (c & 63))
    else if (c < 0x10000) bytes.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63))
    else bytes.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63))
  }
  let h = 0x811c9dc5
  for (const b of bytes) {
    h ^= b
    h = Math.imul(h, 0x01000193)
  }
  return (h >>> 0).toString(16).padStart(8, '0')
}

/**
 * stableIds(rows) -> string[] aligned with rows: 'F-' + fnv1a32(category + '|' + primaryPath(locations)). Rows that share an id take
 * '-2', '-3' and so on in the order of the line of their first location (a row with no line first), then of the raw first-location string in code-unit order,
 * then by position, so the ids do not depend on row order unless two rows are identical in all three.
 */
function stableIds(rows) {
  const list = rows || []
  const lineOf = (r) => {
    const m = /:(\d+)(-\d+)?$/.exec(Array.isArray(r.locations) && typeof r.locations[0] === 'string' ? r.locations[0] : '')
    return m ? Number(m[1]) : 0
  }
  const rawOf = (r) => (Array.isArray(r.locations) && typeof r.locations[0] === 'string' ? r.locations[0] : '')
  const bases = list.map((r) => 'F-' + fnv1a32(r.category + '|' + primaryPath(r.locations)))
  const out = new Array(list.length)
  const byBase = new Map()
  bases.forEach((b, i) => { if (!byBase.has(b)) byBase.set(b, []); byBase.get(b).push(i) })
  for (const [b, idxs] of byBase) {
    idxs.sort((x, y) => (lineOf(list[x]) - lineOf(list[y])) || (rawOf(list[x]) < rawOf(list[y]) ? -1 : rawOf(list[x]) > rawOf(list[y]) ? 1 : 0) || (x - y))
    idxs.forEach((i, n) => { out[i] = n === 0 ? b : b + '-' + (n + 1) })
  }
  return out
}

/**
 * formatMetricsDigest(m, ctx = {}) -> the 'MEASURED STRUCTURE' text injected into prompts: first 15 hot-set paths, 10 top-complexity paths,
 * 10 cycles (8 ids each), 10 least stable modules, 5 coupling entries, 5 complexity trends; the text is capped at ctx.maxChars (default
 * DIGEST_MAX_CHARS) at a line break, then ctx.since/ctx.recent and ctx.metricsPath add the window and file lines after the cap
 */
function formatMetricsDigest(m, ctx = {}) {
  const cap = typeof ctx.maxChars === 'number' && ctx.maxChars > 0 ? ctx.maxChars : DIGEST_MAX_CHARS
  const list = (a, n, f) => (a || []).slice(0, n).map(f)
  const section = (title, lines) => title + ':\n' + (lines.length ? lines.join('\n') : '- (none)')
  const num = (v) => (typeof v === 'number' && isFinite(v) ? String(v) : '-')
  const cycleLine = (c) => {
    const ids = c || []
    return '- ' + ids.slice(0, 8).join(' -> ') + (ids.length > 8 ? ' (+' + (ids.length - 8) + ' more)' : '')
  }
  const trendLine = (t) => {
    const xs = (t.complexities || []).filter((x) => typeof x === 'number')
    if (!xs.length) return '- ' + t.path + ': -'
    const first = xs[0]
    const last = xs[xs.length - 1]
    return '- ' + t.path + ': ' + xs.join(' -> ') + ' (' + (last > first ? 'rising' : last < first ? 'falling' : 'flat') + ')'
  }
  let text = [
    'MEASURED STRUCTURE (approximate; churn from version history, complexity from the source text):',
    section('Hot set (churn x complexity)', list(m.hotSet, 15, (h) => '- ' + h.path + ' (churn ' + num(h.churn) + ', recent ' + num(h.churnRecent) + ', complexity ' + num(h.complexity) + ', score ' + num(h.hotspotScore) + ')')),
    section('Highest complexity', list(m.topComplexity, 10, (c) => '- ' + c.path + ' (complexity ' + c.complexity + ')')),
    section('Dependency cycles', list(m.cycles, 10, cycleLine)),
    section('Least stable modules', list(m.unstableModules, 10, (u) => '- ' + u.id + ' (instability ' + u.instability + ')')),
    section('Change coupling', list(m.coupling, 5, (c) => {
      const partners = (c.partners || []).slice(0, 3).map((p) => p.path + ' (' + p.count + ')')
      return '- ' + c.path + ': ' + (partners.length ? partners.join(', ') : 'none')
    })),
    section('Complexity trend', list(m.trend, 5, trendLine)),
  ].join('\n')
  if (text.length > cap) {
    const cut = text.lastIndexOf('\n', cap)
    text = text.slice(0, cut > 0 ? cut : cap) + '\n(digest cut at ' + cap + ' characters; the full numbers are in the metrics file)'
  }
  if (ctx.since) text += '\nWindows: long since ' + ctx.since + ', recent since ' + ctx.recent
  if (ctx.metricsPath) text += '\nMetrics file: ' + ctx.metricsPath + ' (every number above comes from it, and it holds the numbers for every other file; read it instead of running the metrics script)'
  return text
}

/** measuredCtx(measured) -> {since, recent, metricsPath} for formatMetricsDigest, from measured.window and measured.metricsPath (a missing part stays out) */
function measuredCtx(measured) {
  const w = measured && measured.window && typeof measured.window === 'object' ? measured.window : {}
  const ctx = {}
  if (w.since) { ctx.since = w.since; ctx.recent = w.recent }
  if (measured && typeof measured.metricsPath === 'string' && measured.metricsPath) ctx.metricsPath = measured.metricsPath
  return ctx
}

/**
 * sliceDigest(measured, paths) -> string: formatMetricsDigest over the part of the measured structure under paths, capped at
 * SLICE_DIGEST_MAX_CHARS. A path is normPathText'd; '' or '.' is the whole tree. Kept: hot-set, top-complexity and trend rows under a path;
 * cycles and unstable modules whose module id M is under a path or has a path under it; coupling entries whose path or a partner is under a path.
 */
function sliceDigest(measured, paths) {
  const ps = (paths || []).map(normPathText)
  const keepPath = (x) => ps.some((p) => underPath(normPathText(x), p))
  const keepModule = (id) => ps.some((p) => underPath(normPathText(id), p) || underPath(p, normPathText(id)))
  const m = measured || {}
  const filtered = {
    hotSet: (m.hotSet || []).filter((h) => keepPath(h.path)),
    topComplexity: (m.topComplexity || []).filter((c) => keepPath(c.path)),
    trend: (m.trend || []).filter((t) => keepPath(t.path)),
    cycles: (m.cycles || []).filter((c) => (c || []).some(keepModule)),
    unstableModules: (m.unstableModules || []).filter((u) => keepModule(u.id)),
    coupling: (m.coupling || []).filter((c) => keepPath(c.path) || (c.partners || []).some((pt) => keepPath(pt.path))),
  }
  return formatMetricsDigest(filtered, Object.assign({ maxChars: SLICE_DIGEST_MAX_CHARS }, measuredCtx(m)))
}

const CLAIM_FIELDS = ['churn', 'churnRecent', 'complexity', 'hotspotScore', 'cycle']

/** validClaim(c) -> true for a measuredClaim: field in CLAIM_FIELDS; a finite numeric value, or for 'cycle' a non-empty modules array of strings */
function validClaim(c) {
  if (!c || typeof c !== 'object' || Array.isArray(c) || CLAIM_FIELDS.indexOf(c.field) === -1) return false
  if (c.field === 'cycle') return Array.isArray(c.modules) && c.modules.length > 0 && c.modules.every((x) => typeof x === 'string')
  return typeof c.value === 'number' && isFinite(c.value)
}

/**
 * markMetricsPending(rows) -> {pending, rest}. A row with basis 'measured' and a valid measuredClaim is pending: verdict UNVERIFIED,
 * unverifiedReason 'metrics-pending', verifiedBy 'none', finalSeverity = severity, dropped false. Every other row goes to rest with basis
 * 'judgement' and no measuredClaim. No returned row carries metrics. Order inside each list is the input order.
 */
function markMetricsPending(rows) {
  const pending = []
  const rest = []
  for (const r of rows || []) {
    const row = Object.assign({}, r)
    delete row.metrics
    if (row.basis === 'measured' && validClaim(row.measuredClaim)) {
      const c = row.measuredClaim
      row.measuredClaim = c.field === 'cycle' ? { field: 'cycle', modules: c.modules.slice() } : { field: c.field, value: c.value }
      pending.push(Object.assign(row, { verdict: 'UNVERIFIED', unverifiedReason: 'metrics-pending', verifiedBy: 'none', finalSeverity: r.severity, dropped: false, trackedId: '', correctedEvidence: '' }))
    } else {
      row.basis = 'judgement'
      delete row.measuredClaim
      rest.push(row)
    }
  }
  return { pending, rest }
}

/**
 * preCluster(all, measured = null) -> {autoGroups, clusters, batches}, all in input order.
 * autoGroups: [{canonicalId, duplicateIds, mergedTitle: ''}] for 2 or more findings with an equal category and an equal first location
 * (normPathText'd, line kept); the canonical is the earliest. A unit is an auto group or an ungrouped finding; units are linked (union-find)
 * by an equal non-empty primaryPath, by both primaryPaths in the subject of one 'co-change' candidate of measured, and by both modules in the
 * subject of one 'cycle' candidate (a unit's module is the longest known module id M, from the hot set, top complexity, cycles and unstable modules,
 * with its primaryPath under M). clusters: [{k (1-based), ids (every finding id in the cluster), units: [{canonicalId, ids}], needsAgent (2 or more units)}].
 * batches: arrays of the needsAgent clusters, packed in order while their findings total at most MERGE_BATCH_MAX; a cluster is never split, and
 * a cluster over the limit is a batch of its own.
 */
function preCluster(all, measured = null) {
  const rows = all || []
  const order = new Map(rows.map((f, j) => [f.id, j]))
  const byKey = new Map()
  rows.forEach((f, i) => {
    if (!f.locations || !f.locations.length) return
    const key = f.category + '|' + normPathText(f.locations[0])
    if (!byKey.has(key)) byKey.set(key, [])
    byKey.get(key).push(i)
  })
  const grouped = new Map()
  const autoGroups = []
  for (const idxs of byKey.values()) {
    if (idxs.length < 2) continue
    autoGroups.push({ canonicalId: rows[idxs[0]].id, duplicateIds: idxs.slice(1).map((i) => rows[i].id), mergedTitle: '', first: idxs[0] })
    idxs.forEach((i) => grouped.set(i, idxs[0]))
  }
  autoGroups.sort((a, b) => a.first - b.first)
  const units = []
  rows.forEach((f, i) => {
    if (grouped.has(i) && grouped.get(i) !== i) return
    const g = autoGroups.find((x) => x.first === i)
    units.push({ canonicalId: f.id, ids: g ? [f.id].concat(g.duplicateIds) : [f.id], path: primaryPath(f.locations), first: i })
  })
  const outGroups = autoGroups.map((g) => ({ canonicalId: g.canonicalId, duplicateIds: g.duplicateIds, mergedTitle: g.mergedTitle }))
  const parent = units.map((u, i) => i)
  const find = (x) => { while (parent[x] !== x) { parent[x] = parent[parent[x]]; x = parent[x] } return x }
  const union = (a, b) => { const ra = find(a); const rb = find(b); if (ra !== rb) parent[Math.max(ra, rb)] = Math.min(ra, rb) }
  const firstByPath = new Map()
  units.forEach((u, i) => {
    if (!u.path) return
    if (firstByPath.has(u.path)) union(firstByPath.get(u.path), i)
    else firstByPath.set(u.path, i)
  })
  const mz = measured && typeof measured === 'object' ? measured : {}
  const candidates = Array.isArray(mz.candidates) ? mz.candidates : []
  const known = new Set()
  ;(mz.hotSet || []).concat(mz.topComplexity || []).forEach((x) => { if (x && x.module) known.add(normPathText(x.module)) })
  ;(mz.cycles || []).forEach((c) => (c || []).forEach((id) => known.add(normPathText(id))))
  ;(mz.unstableModules || []).forEach((u) => { if (u && u.id) known.add(normPathText(u.id)) })
  known.delete('')
  const moduleOf = (path) => {
    let best = ''
    for (const id of known) if (underPath(path, id) && id.length > best.length) best = id
    return best
  }
  units.forEach((u) => { u.module = u.path ? moduleOf(normPathText(u.path)) : '' })
  for (const c of candidates) {
    if (!c || !Array.isArray(c.subject)) continue
    const subject = new Set(c.subject.map(normPathText))
    const key = c.kind === 'co-change' ? 'path' : c.kind === 'cycle' ? 'module' : null
    if (!key) continue
    let head = -1
    units.forEach((u, i) => {
      if (!u[key] || !subject.has(key === 'path' ? normPathText(u.path) : u.module)) return
      if (head === -1) head = i
      else union(head, i)
    })
  }
  const byRoot = new Map()
  units.forEach((u, i) => {
    const r = find(i)
    if (!byRoot.has(r)) byRoot.set(r, [])
    byRoot.get(r).push(u)
  })
  const clusters = Array.from(byRoot.values()).sort((a, b) => a[0].first - b[0].first).map((us, i) => {
    const ids = []
    us.forEach((u) => u.ids.forEach((id) => ids.push(id)))
    ids.sort((a, b) => order.get(a) - order.get(b))
    return { k: i + 1, ids, units: us.map((u) => ({ canonicalId: u.canonicalId, ids: u.ids })), needsAgent: us.length >= 2 }
  })
  const batches = []
  let cur = []
  let count = 0
  const flush = () => { if (cur.length) batches.push(cur); cur = []; count = 0 }
  for (const c of clusters) {
    if (!c.needsAgent) continue
    if (c.ids.length > MERGE_BATCH_MAX) { flush(); batches.push([c]); continue }
    if (count + c.ids.length > MERGE_BATCH_MAX) flush()
    cur.push(c)
    count += c.ids.length
  }
  flush()
  return { autoGroups: outGroups, clusters, batches }
}

/** buildMergePrompt(plan, rowsById, batch) -> string: the merge agent's prompt for one batch; its units are listed under CLUSTER <k>, an auto group by its canonical id only */
function buildMergePrompt(plan, rowsById, batch) {
  const line = (f) => f.id + ' | ' + f.severity + ' | ' + f.category + ' | ' + f.title + ' | ' + (f.locations || []).slice(0, 4).join(', ') + ' | ' + f.evidence.slice(0, 300).replace(/\n/g, ' ')
  const text = batch.map((c) => 'CLUSTER ' + c.k + ':\n' + c.units.map((u) => line(rowsById[u.canonicalId])).join('\n')).join('\n\n')
  return 'READ-ONLY. You are deduplicating findings from an audit of ' + plan.repo.root + '. The findings are already clustered by shared files and measured relations; group findings describing the SAME underlying problem (same root cause and remedy), and group only findings listed under the same CLUSTER: a group never spans two clusters. Every id below must appear in exactly one group (singletons are groups with an empty duplicateIds). canonicalId = the best-evidenced member. mergedTitle = a precise merged title. Do not read files; work only from the digest below.\n\nDIGEST by cluster (id | severity | category | title | locations | evidence excerpt):\n' + text
}

/**
 * applyMergeGroups(pre, batchResults) -> groups for mergeFindings. batchResults[i] is the merge agent's {groups} for pre.batches[i], or null.
 * An agent group keeps only ids that sit in its canonical's cluster, each id standing for its whole unit (an auto group comes along);
 * an auto group no agent group touched is added as it is, so a null result leaves its batch to the auto groups.
 */
function applyMergeGroups(pre, batchResults) {
  const clusterOf = new Map()
  const unitOf = new Map()
  pre.clusters.forEach((c) => c.units.forEach((u) => { u.ids.forEach((id) => { clusterOf.set(id, c); unitOf.set(id, u) }) }))
  const out = []
  const used = new Set()
  ;(batchResults || []).forEach((res) => {
    for (const g of (res && res.groups) || []) {
      const home = clusterOf.get(g.canonicalId)
      if (!home) continue
      const ids = []
      for (const id of [g.canonicalId].concat(g.duplicateIds || [])) {
        if (clusterOf.get(id) !== home) continue
        const u = unitOf.get(id)
        if (used.has(u.canonicalId)) continue
        used.add(u.canonicalId)
        u.ids.forEach((x) => ids.push(x))
      }
      if (!ids.length) continue
      out.push({ canonicalId: ids[0], duplicateIds: ids.slice(1), mergedTitle: g.mergedTitle || '' })
    }
  })
  for (const g of pre.autoGroups) if (!used.has(g.canonicalId)) out.push(g)
  return out
}

/** sliceKind(s) -> 'docs' | 'code' (a slice, lens or gap without a kind is code) */
function sliceKind(s) {
  return s && s.kind === 'docs' ? 'docs' : 'code'
}

/** inFocus(focus, s) -> boolean (focus 'all' keeps every slice; 'code' or 'docs' keeps only that kind) */
function inFocus(focus, s) {
  return focus === 'all' || sliceKind(s) === focus
}

/**
 * methodSkillOf(plan, s) -> string, the one place the method policy lives: the slice's own methodSkill when it
 * is a non-blank string, else DOCS_METHOD_SKILL for a docs slice, else the run-level plan.methodSkill.
 */
function methodSkillOf(plan, s) {
  if (s && typeof s.methodSkill === 'string' && s.methodSkill.trim()) return s.methodSkill.trim()
  if (sliceKind(s) === 'docs') return DOCS_METHOD_SKILL
  return plan.methodSkill
}

/** normalizeArgs(raw) -> {ok:true, plan} | {ok:false, reason} */
function normalizeArgs(raw) {
  let A = raw
  if (typeof A === 'string') {
    try { A = JSON.parse(A) } catch (e) { return { ok: false, reason: 'invalid args: bad JSON' } }
  }
  if (!A || typeof A !== 'object' || A.contract !== 'audit/args-v2') {
    return { ok: false, reason: 'invalid args: contract must be audit/args-v2' }
  }
  for (const f of ['runId', 'date', 'repo.root', 'reportPath']) {
    const v = f === 'repo.root' ? (A.repo && A.repo.root) : A[f]
    if (!v) return { ok: false, reason: 'invalid args: missing ' + f }
  }
  if (String(A.runId).indexOf('a-') !== 0) return { ok: false, reason: 'invalid args: runId must start with a-' }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(A.date)) return { ok: false, reason: 'invalid args: date must be YYYY-MM-DD' }

  const presetName = A.preset || 'quick'
  const preset = PRESETS[presetName]
  if (!preset) return { ok: false, reason: 'invalid args: unknown preset ' + presetName }

  const scopeIn = A.scope || {}
  if (!Array.isArray(scopeIn.paths) || scopeIn.paths.length === 0) return { ok: false, reason: 'invalid args: empty scope' }

  const verifyIn = A.verify || {}
  const verify = {
    highLenses: verifyIn.highLenses || preset.verify.highLenses,
    defaultLenses: verifyIn.defaultLenses || preset.verify.defaultLenses,
    maxFindings: verifyIn.maxFindings != null ? verifyIn.maxFindings : preset.verify.maxFindings,
    groupSize: verifyIn.groupSize != null ? verifyIn.groupSize : preset.verify.groupSize,
  }
  for (const k of verify.highLenses.concat(verify.defaultLenses)) {
    if (LENS_KEYS.indexOf(k) === -1) return { ok: false, reason: 'invalid args: unknown verify lens ' + k }
  }
  if (!Number.isInteger(verify.groupSize) || verify.groupSize < 1) return { ok: false, reason: 'invalid args: verify.groupSize must be a positive integer' }
  const candidateCount = scopeIn.candidateCount != null ? scopeIn.candidateCount : preset.candidateCount
  if (!Number.isInteger(candidateCount) || candidateCount < 0) return { ok: false, reason: 'invalid args: scope.candidateCount must be a non-negative integer' }

  const itemsIn = A.items || {}
  const items = Object.assign({ enabled: false, rootId: '', materializeMin: 'high', typeMap: {}, policy: 'gated' }, itemsIn)
  if (itemsIn.enabled == null) items.enabled = !!items.rootId
  if (items.enabled && !items.rootId) return { ok: false, reason: 'invalid args: items.rootId required when items.enabled' }
  if (items.materializeMin !== 'high' && items.materializeMin !== 'medium') {
    return { ok: false, reason: 'invalid args: items.materializeMin must be high or medium' }
  }

  if (A.methodSkill != null && (typeof A.methodSkill !== 'string' || !A.methodSkill.trim())) {
    return { ok: false, reason: 'invalid args: methodSkill must be a non-empty string' }
  }

  const focus = A.focus != null ? A.focus : 'all'
  if (FOCUS_VALUES.indexOf(focus) === -1) return { ok: false, reason: 'invalid args: focus must be code, docs or all' }
  for (const f of ['reviewers', 'lenses']) {
    const list = scopeIn[f]
    if (list == null) continue
    if (!Array.isArray(list)) return { ok: false, reason: 'invalid args: scope.' + f + ' must be an array' }
    for (let i = 0; i < list.length; i++) {
      if (!list[i] || typeof list[i] !== 'object' || Array.isArray(list[i])) {
        return { ok: false, reason: 'invalid args: scope.' + f + '[' + i + '] must be an object' }
      }
    }
  }
  const given = (scopeIn.reviewers || []).concat(scopeIn.lenses || [])
  for (const s of given) {
    if (s.kind != null && s.kind !== 'code' && s.kind !== 'docs') return { ok: false, reason: 'invalid args: slice ' + s.key + ' kind must be code or docs' }
    if (s.methodSkill != null && (typeof s.methodSkill !== 'string' || !s.methodSkill.trim())) {
      return { ok: false, reason: 'invalid args: slice ' + s.key + ' methodSkill must be a non-empty string' }
    }
    if (!inFocus(focus, s)) return { ok: false, reason: 'invalid args: slice ' + s.key + ' is kind ' + sliceKind(s) + ', outside focus ' + focus }
  }

  for (const f of ['scriptsDir', 'workDir']) {
    if (A[f] != null && (typeof A[f] !== 'string' || !A[f].trim())) return { ok: false, reason: 'invalid args: ' + f + ' must be a non-empty string' }
  }
  if (A.scriptsDir != null && A.workDir == null) return { ok: false, reason: 'invalid args: workDir required when scriptsDir is set' }
  if (ITEM_POLICIES.indexOf(items.policy) === -1) return { ok: false, reason: 'invalid args: items.policy must be gated, bugs or all' }

  if (A.measured != null && (typeof A.measured !== 'object' || Array.isArray(A.measured) || A.measured.contract !== 'audit/digest-v1')) {
    return { ok: false, reason: 'invalid args: measured must be an audit/digest-v1 object' }
  }

  const dc = A.dispatchClasses
  if (!dc || typeof dc !== 'object' || Array.isArray(dc)) return { ok: false, reason: 'invalid args: dispatchClasses required' }
  const dispatch = {}
  for (const k of Object.keys(DISPATCH_CLASSES)) {
    const cls = DISPATCH_CLASSES[k]
    const c = dc[cls]
    if (!c || typeof c !== 'object' || typeof c.model !== 'string' || !c.model || typeof c.effort !== 'string' || !c.effort) {
      return { ok: false, reason: 'invalid args: dispatchClasses.' + cls + ' must have a model and an effort' }
    }
    dispatch[k] = { model: c.model, effort: c.effort }
  }
  for (const field of ['models', 'efforts']) {
    const given = A[field]
    if (given == null || typeof given !== 'object') continue
    const prop = field === 'models' ? 'model' : 'effort'
    for (const k of Object.keys(given)) {
      if (!Object.prototype.hasOwnProperty.call(DISPATCH_CLASSES, k)) return { ok: false, reason: 'invalid args: unknown dispatch key ' + field + '.' + k }
      const v = given[k]
      if (typeof v !== 'string' || !v.trim()) return { ok: false, reason: 'invalid args: ' + field + '.' + k + ' must be a non-empty string' }
      if (field === 'models' && FAN_OUT_KEYS.indexOf(k) !== -1 && /fable/i.test(v)) return { ok: false, reason: 'invalid args: fable is not allowed for fan-out phase ' + k }
      dispatch[k][prop] = v
    }
  }
  for (const k of FAN_OUT_KEYS) {
    if (/fable/i.test(dispatch[k].model)) return { ok: false, reason: 'invalid args: fable is not allowed for fan-out phase ' + k }
  }

  const gaps = Object.assign({}, preset.gaps, A.gaps || {})
  const plan = {
    contract: A.contract, runId: A.runId, date: A.date, repo: A.repo, preset: presetName,
    scope: { paths: scopeIn.paths, reviewers: scopeIn.reviewers || null, lenses: scopeIn.lenses || [], reviewerCount: scopeIn.reviewerCount || preset.reviewerCount, candidateCount },
    rubric: A.rubric || null, categories: A.categories || CATEGORIES, backlog: A.backlog || [], gaps, verify,
    dispatch, measured: A.measured != null ? A.measured : null, scriptsDir: A.scriptsDir != null ? A.scriptsDir : null, workDir: A.workDir != null ? A.workDir : null, reportPath: A.reportPath, items,
    maxAgents: A.maxAgents != null ? A.maxAgents : 60, allowLarge: !!A.allowLarge, title: A.title || A.runId, focus,
    classMap: Object.assign({}, DEFAULT_CLASS_MAP, A.classMap || {}), observationPaths: A.observationPaths || [],
    methodSkill: A.methodSkill != null ? A.methodSkill.trim() : DEFAULT_METHOD_SKILL,
  }
  return { ok: true, plan }
}

/** addsArchitectureLens(plan, lenses) -> true when focus is not docs and no lens in lenses is keyed architecture */
function addsArchitectureLens(plan, lenses) {
  return plan.focus !== 'docs' && !(lenses || []).some((l) => l && l.key === ARCHITECTURE_LENS.key)
}

/**
 * assignedCandidates(plan) -> candidate[]: the measured candidates that get their own reviewer, the one source for the run and the projection.
 * [] when plan.measured is null, focus is docs or candidateCount is 0; else measured.candidates in order, skipping a non-object, a candidate whose
 * candidateId is not a non-empty string and a repeated candidateId, cut to candidateCount. Each is returned with its subject as an array ([] when it was not one).
 */
function assignedCandidates(plan) {
  const m = plan.measured
  if (!m || plan.focus === 'docs' || !(plan.scope.candidateCount > 0) || !Array.isArray(m.candidates)) return []
  const seen = new Set()
  const out = []
  for (const c of m.candidates) {
    if (out.length >= plan.scope.candidateCount) break
    if (!c || typeof c !== 'object' || Array.isArray(c) || typeof c.candidateId !== 'string' || !c.candidateId || seen.has(c.candidateId)) continue
    seen.add(c.candidateId)
    out.push(Object.assign({}, c, { subject: Array.isArray(c.subject) ? c.subject : [] }))
  }
  return out
}

/**
 * projectAgents(plan) -> {total, scope, review, candidates, gaps, merge, verify, synth, triage} (upper-bound cost projection; est = (R + gaps.max + candidates) * 8;
 * merge = max(1, ceil(est / 40)); verify = ceil(min(est, maxFindings) / groupSize) * the larger lens count)
 */
function projectAgents(plan) {
  const reviewersGiven = !!(plan.scope.reviewers && plan.scope.reviewers.length)
  const lensesGiven = plan.scope.lenses || []
  const R = reviewersGiven ? plan.scope.reviewers.length + lensesGiven.length + (addsArchitectureLens(plan, lensesGiven) ? 1 : 0) : plan.scope.reviewerCount
  const scope = reviewersGiven ? 0 : 1
  const review = R
  const candidates = assignedCandidates(plan).length
  const gapsEnabled = !!plan.gaps.enabled
  const gaps = gapsEnabled ? 1 + plan.gaps.max : 0
  const est = (R + (gapsEnabled ? plan.gaps.max : 0) + candidates) * 8
  const nV = Math.min(est, plan.verify.maxFindings)
  const verify = Math.ceil(nV / plan.verify.groupSize) * Math.max(plan.verify.highLenses.length, plan.verify.defaultLenses.length)
  const merge = Math.max(1, Math.ceil(est / MERGE_BATCH_MAX))
  const synth = 1
  const triage = plan.items.enabled ? Math.ceil(est / 10) : 0
  return { total: scope + review + candidates + gaps + merge + verify + synth + triage, scope, review, candidates, gaps, merge, verify, synth, triage }
}

/** sizeGuard(plan) -> null | {reason} (refuses when projection > maxAgents and !allowLarge) */
function sizeGuard(plan) {
  const projected = projectAgents(plan)
  if (projected.total > plan.maxAgents && !plan.allowLarge) {
    return { reason: 'projected ' + projected.total + ' agents > maxAgents; pass allowLarge or a smaller preset' }
  }
  return null
}

/** flattenFindings(slices) -> finding[] (reviewer order, then lenses, then gap1..n; id=key+'-'+(i+1)) */
function flattenFindings(slices) {
  const all = []
  for (const s of slices) (s.findings || []).forEach((f, i) => all.push(Object.assign({}, f, { id: s.key + '-' + (i + 1), source: s.key })))
  return all
}

/** mergeFindings(all, groups) -> merged[] (grouped elements in groups order, then ungrouped singletons in input order) */
function mergeFindings(all, groups) {
  const byId = {}
  all.forEach((f, i) => { byId[f.id] = { f, idx: i } })
  const seen = new Set()
  const merged = []
  for (const g of groups || []) {
    const ids = [g.canonicalId].concat(g.duplicateIds || []).filter((id) => byId[id] && !seen.has(id))
    if (!ids.length) continue
    ids.forEach((id) => seen.add(id))
    ids.sort((a, b) => byId[a].idx - byId[b].idx)
    const members = ids.map((id) => byId[id].f)
    const canon = members[0]
    const maxSev = members.reduce((acc, m) => (SEVERITY_RANK[m.severity] > SEVERITY_RANK[acc] ? m.severity : acc), canon.severity)
    const rating = members.reduce((acc, m) => (RATING_RANK[m.rating || 'watch'] > RATING_RANK[acc] ? m.rating : acc), 'watch')
    const locations = []
    const locSeen = new Set()
    members.forEach((m) => (m.locations || []).forEach((l) => { if (!locSeen.has(l)) { locSeen.add(l); locations.push(l) } }))
    const otherIds = members.slice(1).map((m) => m.id)
    const evidence = canon.evidence + (otherIds.length ? '\n\nCorroborating evidence from: ' + otherIds.join(', ') : '')
    const row = {
      id: canon.id, title: g.mergedTitle || canon.title, severity: maxSev, category: canon.category, locations, evidence,
      impact: canon.impact, recommendation: canon.recommendation, effort: canon.effort, confidence: canon.confidence, rating,
      sources: Array.from(new Set(members.map((m) => m.source))), memberIds: ids,
    }
    if (canon.basis) row.basis = canon.basis
    if (canon.measuredClaim) row.measuredClaim = canon.measuredClaim
    merged.push(row)
  }
  all.forEach((f) => {
    if (!seen.has(f.id)) {
      seen.add(f.id)
      const row = {
        id: f.id, title: f.title, severity: f.severity, category: f.category, locations: f.locations, evidence: f.evidence,
        impact: f.impact, recommendation: f.recommendation, effort: f.effort, confidence: f.confidence, rating: f.rating || 'watch',
        sources: [f.source], memberIds: [f.id],
      }
      if (f.basis) row.basis = f.basis
      if (f.measuredClaim) row.measuredClaim = f.measuredClaim
      merged.push(row)
    }
  })
  return merged
}

/** verifyPlan(finding, verifyCfg) -> lens key[] (SEVERITY_RANK >= 3 -> highLenses, else defaultLenses) */
function verifyPlan(finding, verifyCfg) {
  return SEVERITY_RANK[finding.severity] >= 3 ? verifyCfg.highLenses : verifyCfg.defaultLenses
}

/** decideVerdict(votes, lenses, severity) -> {verdict, dropped, finalSeverity, trackedId, correctedEvidence}; votes[i] aligns with lenses[i], may be null */
function decideVerdict(votes, lenses, severity) {
  const pairs = (lenses || []).map((l, i) => ({ lens: l, v: votes ? votes[i] : null })).filter((p) => p.v)
  if (!pairs.length) return { verdict: 'UNVERIFIED', dropped: false, finalSeverity: severity, trackedId: '', correctedEvidence: '' }
  const isStrong = (p) => p.v.refuted && p.v.confidence !== 'low'
  let dropped
  if (pairs.length === 1) {
    dropped = isStrong(pairs[0])
  } else {
    const strongFromEvOrCombined = pairs.some((p) => (p.lens === 'evidence' || p.lens === 'combined') && isStrong(p))
    dropped = strongFromEvOrCombined || pairs.filter(isStrong).length >= 2
  }
  const anyRefuted = pairs.some((p) => p.v.refuted)
  const verdict = dropped ? 'REFUTED' : anyRefuted ? 'PLAUSIBLE' : 'CONFIRMED'
  const sevSrc = pairs.find((p) => p.lens === 'significance') || pairs.find((p) => p.lens === 'combined') || pairs[0]
  const finalSeverity = (sevSrc && sevSrc.v.adjustedSeverity) || severity
  const trackedId = pairs.map((p) => (p.v.alreadyTrackedId || '').trim()).find(Boolean) || ''
  const correctedEvidence = pairs.filter((p) => p.v.evidenceAccurate === false && p.v.correctedEvidence).map((p) => '[' + p.lens + '] ' + p.v.correctedEvidence).join('\n')
  return { verdict, dropped, finalSeverity, trackedId, correctedEvidence }
}

/**
 * capVerification(merged, maxFindings) -> {verify, unverified}. Findings group by category and rating (no rating = watch); groups
 * order by their highest severity, then by their first member's input index; inside a group severity desc, then input index.
 * verify takes one finding per group per round, in group order, until maxFindings; unverified is the rest in input order.
 */
function capVerification(merged, maxFindings) {
  const groups = new Map()
  merged.forEach((f, i) => {
    const key = f.category + '|' + (f.rating || 'watch')
    if (!groups.has(key)) groups.set(key, { members: [], top: 0, first: i })
    const g = groups.get(key)
    g.members.push({ f, i })
    g.top = Math.max(g.top, SEVERITY_RANK[f.severity] || 0)
  })
  const ordered = Array.from(groups.values()).sort((a, b) => (b.top - a.top) || (a.first - b.first))
  ordered.forEach((g) => g.members.sort((a, b) => ((SEVERITY_RANK[b.f.severity] || 0) - (SEVERITY_RANK[a.f.severity] || 0)) || (a.i - b.i)))
  const selected = []
  for (let round = 0; selected.length < maxFindings; round++) {
    let any = false
    for (const g of ordered) {
      if (selected.length >= maxFindings) break
      if (g.members[round]) { selected.push(g.members[round]); any = true }
    }
    if (!any) break
  }
  const chosen = new Set(selected.map((x) => x.i))
  return { verify: selected.map((x) => x.f), unverified: merged.filter((f, i) => !chosen.has(i)) }
}

/**
 * packVerifyGroups(rows, clusters, groupSize) -> rows[][]: rows (the capVerification selection) in a stable order, then consecutive chunks of groupSize
 * (exactly ceil(n / groupSize) groups). The order is the k of the preCluster cluster holding row.id (none: after every cluster), then the first index in rows of the
 * row's primaryPath, then the index in rows, so a path's rows stay together and a cluster's paths stay together. rows is not mutated.
 */
function packVerifyGroups(rows, clusters, groupSize) {
  const list = rows || []
  const kOf = new Map()
  for (const c of clusters || []) for (const id of c.ids || []) if (!kOf.has(id)) kOf.set(id, c.k)
  const firstOfPath = new Map()
  const paths = list.map((r, i) => {
    const p = primaryPath(r.locations)
    if (!firstOfPath.has(p)) firstOfPath.set(p, i)
    return p
  })
  const order = list.map((r, i) => i).sort((a, b) => {
    const ka = kOf.has(list[a].id) ? kOf.get(list[a].id) : Infinity
    const kb = kOf.has(list[b].id) ? kOf.get(list[b].id) : Infinity
    if (ka !== kb) return ka < kb ? -1 : 1
    return (firstOfPath.get(paths[a]) - firstOfPath.get(paths[b])) || (a - b)
  })
  const size = Math.max(1, groupSize)
  const groups = []
  for (let i = 0; i < order.length; i += size) groups.push(order.slice(i, i + size).map((j) => list[j]))
  return groups
}

/** groupVotes(answer, group) -> (vote | null)[] aligned with group: the first answer.verdicts entry whose findingId is the member's id; null for a missing id, a null answer or a non-array verdicts */
function groupVotes(answer, group) {
  const list = answer && Array.isArray(answer.verdicts) ? answer.verdicts : []
  return group.map((f) => list.find((v) => v && v.findingId === f.id) || null)
}

/** criticState(plan, critic) -> 'disabled' | 'no-result' | 'ran' (disabled unless gaps are enabled with max > 0; no-result when the critic returned null) */
function criticState(plan, critic) {
  if (!(plan.gaps.enabled && plan.gaps.max > 0)) return 'disabled'
  return critic ? 'ran' : 'no-result'
}

/** reportIds(synth) -> {[findingId]: reportId}, or {} when synth is null */
function reportIds(synth) {
  if (!synth) return {}
  const out = {}
  for (const fi of synth.findingIndex || []) out[fi.findingId] = fi.reportId
  return out
}

/** schemas(plan) -> {SCOPE, FINDINGS, CRITIC, DEDUP, VERDICT, VERDICTS, SYNTH, TRIAGE} StructuredOutput JSON Schemas; FINDINGS.category enum = plan.categories */
function schemas(plan) {
  const str = { type: 'string' }
  const SCOPE = {
    type: 'object',
    properties: {
      reviewers: { type: 'array', items: { type: 'object', properties: { key: { type: 'string' }, label: { type: 'string' }, focus: { type: 'string' }, kind: { type: 'string', enum: ['code', 'docs'] }, paths: { type: 'array', items: { type: 'string' } } }, required: ['key', 'label', 'focus', 'kind', 'paths'] } },
      lenses: { type: 'array', items: { type: 'object', properties: { key: { type: 'string' }, label: { type: 'string' }, focus: { type: 'string' }, kind: { type: 'string', enum: ['code', 'docs'] } }, required: ['key', 'label', 'focus', 'kind'] } },
    },
    required: ['reviewers', 'lenses'],
  }
  const FINDINGS = {
    type: 'object',
    properties: {
      sliceSummary: { type: 'string' },
      strengths: { type: 'array', items: { type: 'string' } },
      findings: {
        type: 'array',
        items: {
          type: 'object',
          properties: {
            title: { type: 'string' }, severity: { type: 'string', enum: ['critical', 'high', 'medium', 'low'] }, category: { type: 'string', enum: plan.categories },
            locations: { type: 'array', items: { type: 'string' } }, evidence: { type: 'string' }, impact: { type: 'string' }, recommendation: { type: 'string' },
            effort: { type: 'string', enum: ['S', 'M', 'L', 'XL'] }, confidence: { type: 'string', enum: ['high', 'medium', 'low'] },
            rating: { type: 'string', enum: RATINGS },
            basis: { type: 'string', enum: ['measured', 'judgement'] },
            measuredClaim: {
              type: 'object',
              properties: { field: { type: 'string', enum: CLAIM_FIELDS }, value: { type: 'number' }, modules: { type: 'array', items: { type: 'string' } } },
              required: ['field'],
            },
          },
          required: ['title', 'severity', 'category', 'locations', 'evidence', 'impact', 'recommendation', 'effort', 'confidence', 'rating', 'basis'],
        },
      },
    },
    required: ['sliceSummary', 'strengths', 'findings'],
  }
  const CRITIC = {
    type: 'object',
    properties: { assessment: { type: 'string' }, gaps: { type: 'array', items: { type: 'object', properties: { label: { type: 'string' }, kind: { type: 'string', enum: ['code', 'docs'] }, prompt: { type: 'string' } }, required: ['label', 'kind', 'prompt'] } } },
    required: ['assessment', 'gaps'],
  }
  const DEDUP = {
    type: 'object',
    properties: { groups: { type: 'array', items: { type: 'object', properties: { canonicalId: { type: 'string' }, duplicateIds: { type: 'array', items: { type: 'string' } }, mergedTitle: { type: 'string' } }, required: ['canonicalId', 'duplicateIds', 'mergedTitle'] } } },
    required: ['groups'],
  }
  const VERDICT = {
    type: 'object',
    properties: {
      refuted: { type: 'boolean' }, confidence: { type: 'string', enum: ['high', 'medium', 'low'] }, evidenceAccurate: { type: 'boolean' },
      correctedEvidence: { type: 'string' }, adjustedSeverity: { type: 'string', enum: ['critical', 'high', 'medium', 'low'] },
      alreadyTrackedId: { type: 'string' }, rationale: { type: 'string' },
    },
    required: ['refuted', 'confidence', 'evidenceAccurate', 'correctedEvidence', 'adjustedSeverity', 'alreadyTrackedId', 'rationale'],
  }
  const VERDICTS = {
    type: 'object',
    properties: { verdicts: { type: 'array', items: { type: 'object', properties: Object.assign({ findingId: { type: 'string' } }, VERDICT.properties), required: ['findingId'].concat(VERDICT.required) } } },
    required: ['verdicts'],
  }
  const SYNTH = {
    type: 'object',
    properties: {
      healthVerdict: { type: 'string' }, executiveSummary: { type: 'string' },
      themes: { type: 'array', items: { type: 'object', properties: { name: { type: 'string' }, summary: { type: 'string' }, reportIds: { type: 'array', items: { type: 'string' } } }, required: ['name', 'summary', 'reportIds'] } },
      findingIndex: { type: 'array', items: { type: 'object', properties: { reportId: { type: 'string' }, findingId: { type: 'string' }, title: { type: 'string' }, severity: { type: 'string' }, effort: { type: 'string' }, verdict: { type: 'string' }, alreadyTrackedId: { type: 'string' } }, required: ['reportId', 'findingId', 'title', 'severity', 'effort', 'verdict', 'alreadyTrackedId'] } },
      roadmap: { type: 'object', properties: { now: { type: 'array', items: { type: 'string' } }, next: { type: 'array', items: { type: 'string' } }, later: { type: 'array', items: { type: 'string' } } }, required: ['now', 'next', 'later'] },
      strengths: { type: 'array', items: { type: 'string' } }, method: { type: 'string' }, notExamined: { type: 'array', items: { type: 'string' } },
    },
    required: ['healthVerdict', 'executiveSummary', 'themes', 'strengths', 'findingIndex', 'roadmap', 'method', 'notExamined'],
  }
  const TRIAGE = {
    type: 'object',
    properties: {
      results: {
        type: 'array',
        items: {
          type: 'object',
          properties: {
            findingId: { type: 'string' },
            candidates: { type: 'array', items: { type: 'object', properties: { id: { type: 'string' }, short: { type: 'string' }, role: { type: 'string' }, title: { type: 'string' } }, required: ['id', 'short', 'role', 'title'] } },
            likelyDuplicate: { type: 'string', enum: ['none', 'weak', 'strong'] },
          },
          required: ['findingId', 'candidates', 'likelyDuplicate'],
        },
      },
    },
    required: ['results'],
  }
  return { SCOPE, FINDINGS, CRITIC, DEDUP, VERDICT, VERDICTS, SYNTH, TRIAGE }
}

/**
 * reviewerPreamble(plan, slice = {}, docsInRun = false, digest = '') -> string (shared READ-ONLY preamble for reviewer, lens and gap prompts; all repo facts
 * come from plan.repo/plan.rubric/plan.backlog). The METHOD paragraph names methodSkillOf(plan, slice), the one place the
 * method skill's name enters a prompt: the reviewer applies that skill's method inside its slice, and the slice
 * (paths and focus), the HARD RULES and the FINDINGS shape take precedence over it. The slice bounds coverage:
 * a hot-spot ranking never narrows it, and the categories plan.classMap maps to 'bug' stay in scope. A non-empty digest (the measured
 * structure) is placed just before YOUR SLICE.
 */
function reviewerPreamble(plan, slice = {}, docsInRun = false, digest = '') {
  const backlog = (plan.backlog || []).map((b) => '- ' + b.id + ' — ' + b.title).join('\n')
  const rubric = plan.rubric ? JSON.stringify(plan.rubric) : 'critical/high/medium/low, standard severity practice'
  const bugCategories = (plan.categories || []).filter((c) => plan.classMap && plan.classMap[c] === 'bug')
  let out = 'You are performing ONE slice of an audit of the repository at ' + plan.repo.root
  if (plan.repo.description) out += ' (' + plan.repo.description + ')'
  out += '.\n\nHARD RULES: READ-ONLY. Do not create, edit, or delete any file. Do not run any command that mutates repository or environment state. Do not call any write tool. Do not prefix bash commands with cd.\n\n'
  out += 'METHOD: before reading any code, invoke the skill ' + methodSkillOf(plan, slice) + ' with the Skill tool and audit by its method, confined to YOUR SLICE below. ' +
    'The slice is your scope (its paths; for a cross-cutting lens, every scoped path) and its focus is the lens you apply the method through. ' +
    'Together they set what you cover: any ranking of hot spots the method makes only orders your attention inside the slice and never drops cold code from it' +
    (bugCategories.length
      ? ', and defects in the categories ' + bugCategories.join(', ') + ' stay in scope for a slice whose focus carries them, whatever the method says about bug hunts or security reviews, so report them as findings, not as asides. '
      : '. ') +
    'Where the method and this prompt disagree, this prompt wins: the HARD RULES, the slice, and the structured findings you return in place of the method\'s own report. ' +
    'Write each candidate as one finding: its move as recommendation, its evidence and any decision record it touches in evidence, its confidence as confidence, its severity per the rubric below, and as category the one from the output schema\'s list that fits it best. ' +
    'Rate each finding by payback: act-now when the cost recurs on every change and the fix is cheap or urgent, plan-it when it is worth a scheduled change, watch when it is worth recording but not acting on yet. ' +
    (digest
      ? 'Set basis on each finding: measured when the finding rests on a number from the MEASURED STRUCTURE below (churn, complexity, hotspot score or a dependency cycle), judgement when it rests on your reading of the code or documents. For a measured finding give measuredClaim: the field (churn, churnRecent, complexity, hotspotScore or cycle) and the number you rely on, or for cycle the module ids; a script checks that claim against the measurements after the run, so quote the figure as it appears there and never estimate one.\n\n'
      : 'Set basis to judgement on every finding and give no measuredClaim: no measurements come with this slice.\n\n')
  if (sliceKind(slice) === 'docs') {
    out += 'DOCUMENTATION SLICE: audit only the document files in your scope; comments inside source files belong to the code slices.\n\n'
  } else if (docsInRun) {
    out += 'Document files in your scope belong to the documentation slices; comments inside source files stay yours.\n\n'
  }
  out += 'Severity rubric: ' + rubric + '\n\n'
  if (backlog) out += 'ALREADY-TRACKED BACKLOG (do not re-report these as-is):\n' + backlog + '\n\n'
  if (digest) out += digest + '\n\n'
  return out + 'YOUR SLICE:\n'
}

/** verifierPreamble(plan, metricsPath = '') -> string (shared READ-ONLY preamble for adversarial verifiers of a group of findings: backlog, rubric, the metrics file line when metricsPath is set, and VERDICT field semantics incl. alreadyTrackedId derivation) */
function verifierPreamble(plan, metricsPath = '') {
  const backlog = (plan.backlog || []).map((b) => '- ' + b.id + ' — ' + b.title).join('\n')
  const rubric = plan.rubric ? JSON.stringify(plan.rubric) : 'critical/high/medium/low, standard severity practice'
  let out = 'You are an ADVERSARIAL VERIFIER for a GROUP of findings from an audit of the repository at ' + plan.repo.root
  if (plan.repo.description) out += ' (' + plan.repo.description + ')'
  out += '.\n\nHARD RULES: READ-ONLY. Do not create, edit, or delete any file. Do not run any command that mutates repository or environment state. Do not call any write tool. Do not prefix bash commands with cd. Default stance: skeptical — each finding must earn its place. Judge every finding on its own.\n\n'
  out += 'Severity rubric: ' + rubric + '\n\n'
  if (backlog) out += 'ALREADY-TRACKED BACKLOG (open items; use these, and a search, to judge alreadyTrackedId):\n' + backlog + '\n\n'
  if (metricsPath) out += 'Metrics file: ' + metricsPath + ' (it holds the measured churn, complexity and cycles for every file; read it instead of running the metrics script)\n\n'
  out += 'Return a verdicts list with one entry per finding in the FINDINGS list below. Each entry carries findingId (the id given for that finding) and every verdict field named here; a finding with no entry counts as unverified. Always fill every verdict field. refuted = true iff, under your lens, this finding should be dropped. confidence = your confidence in refuted. evidenceAccurate = false only when a cited fact is actually wrong (then explain the correction in correctedEvidence; leave correctedEvidence empty when the evidence is accurate). adjustedSeverity = your own severity per the rubric (may equal, raise, or lower the reported one). alreadyTrackedId = the id of a backlog entry that substantially already covers this finding — if the backlog list above is not conclusive, run an items_read search (select="search", query=<the finding\'s title keywords>) to check; leave it the empty string when nothing already covers this finding. rationale = a short justification for your verdict.\n\n'
  return out
}

/** buildReviewerPrompt(plan, reviewer, docsInRun = false, digest = '') -> string */
function buildReviewerPrompt(plan, r, docsInRun = false, digest = '') {
  return reviewerPreamble(plan, r, docsInRun, digest) + 'FOCUS: ' + r.focus + '. PATHS: ' + (r.paths || []).join(', ') + '.'
}

/** buildLensPrompt(plan, lens, docsInRun = false, digest = '') -> string */
function buildLensPrompt(plan, l, docsInRun = false, digest = '') {
  return reviewerPreamble(plan, l, docsInRun, digest) + 'CROSS-CUTTING LENS — ' + l.label + '. FOCUS: ' + l.focus + '. Examine across all scoped paths: ' + plan.scope.paths.join(', ') + '.'
}

/** buildGapPrompt(plan, gap, docsInRun = false, digest = '') -> string */
function buildGapPrompt(plan, g, docsInRun = false, digest = '') {
  return reviewerPreamble(plan, g, docsInRun, digest) + 'FOCUS: ' + g.prompt
}

/** buildCandidatePrompt(plan, candidate, docsInRun = false, digest = '') -> string: the reviewer preamble, then one measured candidate (id, kind, subject, facts) and the cost-of-change question */
function buildCandidatePrompt(plan, c, docsInRun = false, digest = '') {
  const subject = Array.isArray(c.subject) ? c.subject : []
  return reviewerPreamble(plan, { kind: 'code' }, docsInRun, digest) + 'MEASURED CANDIDATE ' + c.candidateId + ' (kind ' + c.kind + ').\nSUBJECT: ' + subject.join(', ') +
    '.\nFACTS: ' + JSON.stringify(c.facts) +
    '\n\nQuestion: is this evidence a cost-of-change problem in the code, why, and what should be done about it? Read the subject and what surrounds it before you answer. Return no finding when it is not a problem worth the cost of changing.'
}

/** isDocsFinding(f, docsKeys) -> true when the finding came from a docs slice or is in a documentation category */
function isDocsFinding(f, docsKeys) {
  return fromDocsSlice(f, docsKeys) || DOCS_CATEGORIES.indexOf(f.category) !== -1
}

/** buildVerifyGroupPrompt(plan, group, lensKey, docsKeys, metricsPath = '') -> string: the verifier preamble, the lens, the documentation rule once when any member is a docs finding, then FINDINGS: a JSON array in group order */
function buildVerifyGroupPrompt(plan, group, lensKey, docsKeys, metricsPath = '') {
  const anyDocs = group.some((f) => isDocsFinding(f, docsKeys))
  const lens = verifyLensText(lensKey, '', anyDocs) + (anyDocs ? '\n\nThe documentation rule applies to the findings marked documentation: true below, and to no other.' : '')
  const list = group.map((f) => ({ id: f.id, title: f.title, severity: f.severity, category: f.category, locations: f.locations, evidence: f.evidence, impact: f.impact, recommendation: f.recommendation, documentation: isDocsFinding(f, docsKeys) }))
  return verifierPreamble(plan, metricsPath) + lens + '\n\nFINDINGS:\n' + JSON.stringify(list)
}

/** normalizeObsPath(location) -> string (strips trailing ':line', backslash -> forward slash) */
function normalizeObsPath(p) {
  const idx = p.indexOf(':')
  return (idx >= 0 ? p.slice(0, idx) : p).replace(/\\/g, '/')
}

/** isObservation(locations, observationPaths) -> boolean (true iff every location starts with an observationPaths prefix) */
function isObservation(locations, observationPaths) {
  if (!observationPaths || !observationPaths.length || !locations || !locations.length) return false
  const prefixes = observationPaths.map((p) => p.replace(/\\/g, '/'))
  return locations.every((l) => { const norm = normalizeObsPath(l); return prefixes.some((p) => norm.indexOf(p) === 0) })
}

/** buildProposal(kept, triage, plan) -> proposal | null; kept = result-v2 kept[]; triage = {[findingId]:{candidates, likelyDuplicate}}; null when !plan.items.enabled */
function buildProposal(kept, triage, plan) {
  if (!plan.items.enabled) return null
  const container = { title: 'Audit — ' + plan.title + ' — ' + plan.date, type: 'container', tags: 'container,audit' }
  const minRank = SEVERITY_RANK[plan.items.materializeMin]
  const items = kept.map((k) => {
    const t = (triage && triage[k.findingId]) || { candidates: [], likelyDuplicate: 'unknown' }
    const observation = isObservation(k.locations, plan.observationPaths)
    const cls = observation ? 'observation' : plan.classMap[k.category] || 'tech-debt'
    const priority = k.severity === 'critical' || k.severity === 'high' ? 'high' : k.severity === 'medium' ? 'medium' : 'low'
    const tagBase = 'audit,' + k.category + ',audit-' + plan.runId
    const tags = observation ? 'agent-observation,' + tagBase : tagBase
    const summary = k.reportId ? k.title + ' [' + k.reportId + ']' : k.title
    const anchor = k.reportId ? '#' + k.reportId.toLowerCase() : ''
    const description = 'Report: ' + plan.reportPath + anchor + '\n' + (k.locations || []).slice(0, 4).join('\n')

    let gated = true
    let gatedReason = 'ok'
    if (SEVERITY_RANK[k.severity] < minRank) { gated = false; gatedReason = 'severity below ' + plan.items.materializeMin }
    else if (k.verdict !== 'CONFIRMED' && k.verdict !== 'PLAUSIBLE') { gated = false; gatedReason = 'verdict ' + k.verdict }
    else if (k.trackedId) { gated = false; gatedReason = 'tracked ' + k.trackedId }
    else if (t.likelyDuplicate === 'strong' || t.likelyDuplicate === 'unknown') { gated = false; gatedReason = 'likely duplicate ' + t.likelyDuplicate }
    const notTracked = !k.trackedId && t.likelyDuplicate !== 'strong'
    const trackedReason = k.trackedId ? 'tracked ' + k.trackedId : t.likelyDuplicate === 'strong' ? 'likely duplicate strong' : 'ok'
    const selectedBy = { gated, bugs: cls === 'bug' && notTracked, all: cls !== 'observation' && notTracked }
    const policy = plan.items.policy
    const materialize = selectedBy[policy]
    const reason = policy === 'gated' ? gatedReason
      : policy === 'bugs' ? (cls !== 'bug' ? 'class ' + cls : trackedReason)
        : (cls === 'observation' ? 'observation' : trackedReason)
    const tagged = k.verdict === 'UNVERIFIED' && (selectedBy.gated || selectedBy.bugs || selectedBy.all) ? tags + ',unverified' : tags

    const entry = { findingId: k.findingId, reportId: k.reportId, class: cls, title: k.title, summary, description, priority, tags: tagged, severity: k.severity, verdict: k.verdict, selectedBy, materialize, reason, candidates: t.candidates, likelyDuplicate: t.likelyDuplicate }
    if (!observation) {
      const typeKey = TYPE_MAP_KEY[cls]
      const type = typeKey ? plan.items.typeMap[typeKey] : undefined
      if (type) entry.type = type
    }
    return entry
  })

  const materializable = items.filter((it) => it.materialize && it.class !== 'observation')
  const batches = []
  for (let i = 0; i < materializable.length; i += 25) {
    const chunk = materializable.slice(i, i + 25)
    batches.push({ root: i === 0 ? container : { id: '<container-id>' }, childRefs: chunk.map((it) => it.findingId) })
  }
  const observations = items.filter((it) => it.class === 'observation').map((it) => it.findingId)
  return { rootId: plan.items.rootId, container, items, batches, observations }
}

/** runAudit(plan, deps{agent, parallel, pipeline, phase, log}) -> audit/result-v2; Scope(opt) -> Review -> Gaps -> Merge -> Verify -> Synthesize -> Triage(opt) */
async function runAudit(plan, deps) {
  const { agent, parallel, pipeline, phase, log } = deps
  const S = schemas(plan)
  const projected = projectAgents(plan)
  log('projected ' + projected.total + ' agents')
  const candidates = assignedCandidates(plan)

  const measured = plan.measured
  const metricsPath = measured && typeof measured.metricsPath === 'string' && measured.metricsPath ? measured.metricsPath : null
  const fullDigest = measured ? formatMetricsDigest(measured, measuredCtx(measured)) : ''
  if (!measured) log('no measured input')

  phase('Scope')
  let reviewers = plan.scope.reviewers
  let lenses = plan.scope.lenses || []
  const focusSentence = plan.focus === 'all' ? '' : ' Return only kind ' + plan.focus + ' slices: only kind ' + plan.focus + ' belongs in this audit.'
  if (!reviewers || !reviewers.length) {
    const reserveLens = plan.focus !== 'docs'
    const promptBudget = plan.scope.reviewerCount - (reserveLens ? 1 : 0)
    const testPaths = testPathsOf(plan.scope.paths)
    const bugCats = (plan.categories || []).filter((c) => plan.classMap && plan.classMap[c] === 'bug')
    const scopeResult = await agent(
      'READ-ONLY. Determine the review scope for an audit. Repository root: ' + plan.repo.root + '. Candidate paths: ' + plan.scope.paths.join(', ') + '.\n\n' +
        (fullDigest ? fullDigest + '\n\n' : '') +
        'Cut the slices where the code costs the most to change: where complexity, coupling, duplication or drift concentrate, using any measurements above to place them. Frame every slice focus as a question about that cost, not as a defect hunt' +
        (bugCats.length ? '; defects in the categories ' + bugCats.join(', ') + ' stay reportable inside any slice, but no slice is built around hunting them' : '') + '. ' +
        (candidates.length ? 'MEASURED CANDIDATES ALREADY ASSIGNED (each has its own reviewer, so build no slice around them):\n' + candidates.map((c) => c.candidateId + ' (' + c.subject.join(', ') + ')').join('\n') + '\n\n' : '') +
        'Propose up to ' + promptBudget + ' reviewer slices (key, label, focus, paths) and cross-cutting lenses (key, label, focus) in total: lenses count against that number' + (reserveLens ? '; an architecture lens is added automatically, so do not propose one' : '') + '. ' +
        'Give every slice and lens a kind: docs for document files (READMEs, guides, user docs, agent instruction files about the project such as CLAUDE.md or AGENTS.md, generated reference pages), code for source files, their comments included, and for text shipped to drive an agent (skill files and their references, agent definitions, rules, prompt strings); a docs slice and a code slice never share a path.' + focusSentence +
        (testPaths.length ? '\n\nTEST CODE: these scoped paths are test code: ' + testPaths.join(', ') + '. Make one code slice focused on test quality: what the tests pin, setup copied between tests, and tests that could not fail.' : ''),
      agentOpts(plan, 'scope', { label: 'scope', phase: 'Scope', schema: S.SCOPE }),
    )
    if (!scopeResult) {
      return {
        contract: 'audit/result-v2', started: true, runId: plan.runId,
        stats: { reviewers: 0, candidateReviewers: 0, verifyAgents: 0, gaps: 0, raw: 0, merged: 0, kept: 0, confirmed: 0, plausible: 0, unverified: 0, refuted: 0, missing: ['scope'], projectedAgents: projected.total, droppedGaps: [], unverifiedByCap: 0, metricsPending: 0, mergeAgents: 0 },
        report: null, kept: [], refuted: [], proposal: null, metricsPath, date: plan.date,
      }
    }
    const proposedR = scopeResult.reviewers || []
    const proposedL = scopeResult.lenses || []
    const outR = proposedR.filter((r) => !inFocus(plan.focus, r))
    const outL = proposedL.filter((l) => !inFocus(plan.focus, l))
    if (outR.length || outL.length) log('scope proposed slices outside focus ' + plan.focus + '; dropped: ' + outR.concat(outL).map((x) => x.key).join(', '))
    const inR = proposedR.filter((r) => inFocus(plan.focus, r))
    const inL = proposedL.filter((l) => inFocus(plan.focus, l))
    const budget = plan.scope.reviewerCount - (addsArchitectureLens(plan, inL) ? 1 : 0)
    reviewers = inR.slice(0, budget)
    lenses = inL.slice(0, Math.max(0, budget - reviewers.length))
    const droppedKeys = inR.slice(reviewers.length).concat(inL.slice(lenses.length)).map((x) => x.key)
    if (droppedKeys.length) log('scope proposed ' + (inR.length + inL.length) + ' slices and lenses; keeping first ' + budget + ', dropped: ' + droppedKeys.join(', '))
  }
  if (addsArchitectureLens(plan, lenses)) lenses = lenses.concat([ARCHITECTURE_LENS])

  phase('Review')
  const docsInRun = reviewers.concat(lenses).some((x) => sliceKind(x) === 'docs')
  const scopeDigest = measured ? sliceDigest(measured, plan.scope.paths) : ''
  const lensDigest = (l) => (!measured ? '' : l.key === ARCHITECTURE_LENS.key ? fullDigest : scopeDigest)
  const reviewItems = reviewers.map((r) => ({ key: r.key, label: r.label, kind: sliceKind(r), origin: 'territory', agentLabel: 'review:' + r.key, dispatchKey: 'review', prompt: buildReviewerPrompt(plan, r, docsInRun, measured ? sliceDigest(measured, r.paths) : '') }))
    .concat(lenses.map((l) => ({ key: l.key, label: l.label, kind: sliceKind(l), origin: 'lens', agentLabel: 'review:' + l.key, dispatchKey: l.key === ARCHITECTURE_LENS.key ? 'review-architecture' : 'review', prompt: buildLensPrompt(plan, l, docsInRun, lensDigest(l)) })))
    .concat(candidates.map((c) => ({ key: c.candidateId, label: 'Candidate: ' + c.candidateId, kind: 'code', origin: 'candidate', agentLabel: 'candidate:' + c.candidateId, dispatchKey: 'review-candidate', prompt: buildCandidatePrompt(plan, c, docsInRun, sliceDigest(measured, c.subject)) })))
  const reviewed = await parallel(reviewItems.map((it) => () =>
    agent(it.prompt, agentOpts(plan, it.dispatchKey, { label: it.agentLabel, phase: 'Review', schema: S.FINDINGS })).then((r) => (r ? Object.assign({}, r, { key: it.key, label: it.label, kind: it.kind, origin: it.origin }) : null))))
  const slices = reviewed.filter(Boolean)
  const missing = reviewItems.filter((it) => !slices.find((s) => s.key === it.key)).map((it) => it.key)
  if (missing.length) log('WARNING: reviewers with no result: ' + missing.join(', '))
  log(slices.length + '/' + reviewItems.length + ' reviewers returned')

  phase('Gaps')
  let droppedGaps = []
  let gapResults = []
  let critic = null
  if (plan.gaps.enabled && plan.gaps.max > 0) {
    const coverage = slices.map((s) => '## ' + s.key + ' (' + s.label + ')\nSummary: ' + s.sliceSummary + '\nFindings:\n' +
      s.findings.map((f) => '- [' + f.severity + '] ' + f.title + ' (' + (f.locations || []).slice(0, 3).join(', ') + ')').join('\n')).join('\n\n')
    critic = await agent(
      'READ-ONLY. You are the completeness critic for an audit of ' + plan.repo.root + '. Below is what the reviewers covered. Identify up to ' + plan.gaps.max +
        ' MATERIAL gaps — subsystems or concerns not covered — each with a label, a kind (docs for document files, code for source files and their comments and for text shipped to drive an agent) and a prompt (paths and questions) for a focused follow-up reviewer.' + (plan.focus === 'all' ? '' : ' Propose only kind ' + plan.focus + ' gaps: only kind ' + plan.focus + ' belongs in this audit.') + ' Return an empty gaps array if coverage is adequate.\n\nCOVERAGE:\n' + coverage,
      agentOpts(plan, 'critic', { label: 'critic', phase: 'Gaps', schema: S.CRITIC }),
    )
    const allGaps = (critic && critic.gaps) || []
    const gapList = allGaps.filter((g) => inFocus(plan.focus, g))
    if (gapList.length < allGaps.length) log('critic proposed gaps outside focus ' + plan.focus + '; dropped: ' + allGaps.filter((g) => !inFocus(plan.focus, g)).map((g) => g.label).join('; '))
    const runGaps = gapList.slice(0, plan.gaps.max)
    droppedGaps = gapList.slice(plan.gaps.max).map((g) => g.label)
    if (droppedGaps.length) log('critic proposed ' + gapList.length + ' gaps; running first ' + plan.gaps.max + ', dropped: ' + droppedGaps.join('; '))
    const gapRun = await parallel(runGaps.map((g, i) => () =>
      agent(buildGapPrompt(plan, g, docsInRun, scopeDigest), agentOpts(plan, 'review', { label: 'gap:' + (i + 1), phase: 'Gaps', schema: S.FINDINGS })).then((r) => (r ? Object.assign({}, r, { key: 'gap' + (i + 1), label: 'Gap: ' + g.label, kind: sliceKind(g), origin: 'gap' }) : null))))
    gapResults = gapRun.filter(Boolean)
    log('gap reviews: ' + gapResults.length + ' run')
  }
  const allSlices = slices.concat(gapResults)
  const docsKeys = new Set(allSlices.filter((s) => s.kind === 'docs').map((s) => s.key))

  phase('Merge')
  const all = flattenFindings(allSlices)
  let merged = []
  let mergeAgents = 0
  let pre = { clusters: [] }
  if (all.length > 0) {
    pre = preCluster(all, measured)
    const rowsById = {}
    all.forEach((f) => { rowsById[f.id] = f })
    mergeAgents = pre.batches.length
    const batchResults = await parallel(pre.batches.map((batch, i) => () =>
      agent(buildMergePrompt(plan, rowsById, batch), agentOpts(plan, 'merge', { label: 'merge:' + (i + 1), phase: 'Merge', schema: S.DEDUP }))))
    merged = mergeFindings(all, applyMergeGroups(pre, batchResults))
    log(all.length + ' raw findings -> ' + merged.length + ' after dedup')
  }

  phase('Verify')
  let capResult = { verify: [], unverified: [] }
  let verdicts = []
  let metricsPendingRows = []
  let verifyAgents = 0
  if (all.length > 0) {
    const marked = markMetricsPending(merged)
    metricsPendingRows = marked.pending
    capResult = capVerification(marked.rest, plan.verify.maxFindings)
    if (capResult.unverified.length) log(capResult.unverified.length + ' findings unverified by cap')
    const groups = packVerifyGroups(capResult.verify, pre.clusters, plan.verify.groupSize)
    const groupRuns = await parallel(groups.map((group, gi) => async () => {
      const top = group.reduce((acc, f) => (SEVERITY_RANK[f.severity] > SEVERITY_RANK[acc] ? f.severity : acc), group[0].severity)
      const lensKeys = verifyPlan({ severity: top }, plan.verify)
      const verifyKey = SEVERITY_RANK[top] >= 3 ? 'verify-group-high' : 'verify-group'
      const answers = await parallel(lensKeys.map((l) => () =>
        agent(buildVerifyGroupPrompt(plan, group, l, docsKeys, metricsPath || ''),
          agentOpts(plan, verifyKey, { label: 'verify:g' + (gi + 1) + ':' + l, phase: 'Verify', schema: S.VERDICTS }))))
      verifyAgents += lensKeys.length
      const byLens = answers.map((a) => groupVotes(a, group))
      return group.map((f, mi) => {
        const votes = lensKeys.map((l, li) => byLens[li][mi])
        return Object.assign({}, f, decideVerdict(votes, lensKeys, f.severity), { verifiedBy: votes.some(Boolean) ? 'agents' : 'none' })
      })
    }))
    verdicts = groupRuns.reduce((acc, rows) => acc.concat(rows), [])
  }
  const cappedUnverified = capResult.unverified.map((f) => Object.assign({}, f, { verdict: 'UNVERIFIED', dropped: false, finalSeverity: f.severity, trackedId: '', correctedEvidence: '', verifiedBy: 'none' }))
  const cappedIds = new Set(capResult.unverified.map((f) => f.id))
  const allVerdicts = verdicts.concat(cappedUnverified)
  const keptVerified = allVerdicts.filter((v) => !v.dropped)
  const refutedRaw = allVerdicts.filter((v) => v.dropped)
  const keptRaw = keptVerified.concat(metricsPendingRows)
  const stable = stableIds(keptRaw.concat(refutedRaw))
  log('verification: ' + keptRaw.length + ' kept, ' + refutedRaw.length + ' refuted')

  phase('Synthesize')
  const keptPayload = keptRaw.map((k) => ({ findingId: k.id, title: k.title, severity: k.finalSeverity, rating: k.rating, category: k.category, locations: k.locations, evidence: k.evidence, correctedEvidence: k.correctedEvidence, impact: k.impact, recommendation: k.recommendation, effort: k.effort, verdict: k.verdict, trackedId: k.trackedId, basis: k.basis, measuredClaim: k.measuredClaim }))
  const refutedPayload = refutedRaw.map((d) => ({ findingId: d.id, title: d.title }))
  const slicePayload = allSlices.map((s) => ({ key: s.key, label: s.label, summary: s.sliceSummary, strengths: s.strengths }))
  const criticLine = criticState(plan, critic) === 'disabled' ? 'GAP CRITIC: disabled for this run.'
    : criticState(plan, critic) === 'no-result' ? 'GAP CRITIC: enabled, but it returned no result.'
      : 'GAP CRITIC ASSESSMENT:\n' + critic.assessment
  const synth = await agent(
    'You are the lead architect summarising the results of an audit of ' + plan.repo.root + ' run on ' + plan.date + '. ' + slices.length +
      ' reviewer(s) examined subsystem slices and cross-cutting lenses' + (gapResults.length ? ' plus ' + gapResults.length + ' gap follow-up(s)' : '') +
      '; their findings were deduplicated and adversarially verified. Verdicts: CONFIRMED = survived every verifier; PLAUSIBLE = kept, but at least one verifier objected (say so in the finding); UNVERIFIED = no verifier returned, the finding fell beyond the verification cap, or (unverifiedReason metrics-pending) it rests on a measured claim a script checks after this run; keep it, do not judge that claim yourself. Use finalSeverity (verifier-adjusted), not the originally reported severity. Apply correctedEvidence: drop or fix any evidence a verifier showed to be wrong. You may open source files to make the architecture map accurate, but do NOT re-litigate verdicts and do NOT add new findings.\n\n' +
      'HARD RULES: READ-ONLY. Do not create, edit, or delete any file. Do not run any command that mutates repository or environment state. Do not call any write tool. Do not prefix bash commands with cd. You write no report file; the report is the structured data you return.\n\n' +
      'Return: healthVerdict (one sentence); executiveSummary (<=150 words, balanced: strengths and problems); themes (systemic themes grouping findings by root cause, each with the report ids it explains and the structural remedy in its summary); strengths (the strengths to preserve, deduplicated and specific); findingIndex (assign report ids R01, R02, ... ordered by finalSeverity, critical first, then by leverage, and map every report id to its findingId); roadmap (now/next/later, short strings referencing report ids); method (one short paragraph: reviewers, lenses, dedup, verification, counts); notExamined (areas the audit did not cover, one string each).\n\n' +
      'SLICE SUMMARIES & STRENGTHS:\n' + JSON.stringify(slicePayload) + '\n\nKEPT FINDINGS (' + keptPayload.length + '):\n' + JSON.stringify(keptPayload) +
      '\n\nREFUTED FINDINGS (' + refutedPayload.length + '):\n' + JSON.stringify(refutedPayload) +
      '\n\n' + criticLine,
    agentOpts(plan, 'synth', { label: 'synthesis', phase: 'Synthesize', schema: S.SYNTH }),
  )
  const ridMap = reportIds(synth)
  const keptOut = keptRaw.map((k, ki) => {
    const row = {
      findingId: k.id, reportId: ridMap[k.id] || '', title: k.title, severity: k.finalSeverity, verdict: k.verdict, category: k.category, effort: k.effort,
      locations: (k.locations || []).slice(0, 4), trackedId: k.trackedId || '',
      evidence: k.evidence + (k.correctedEvidence ? '\n\nVerifier correction: ' + k.correctedEvidence : ''),
      impact: k.impact, recommendation: k.recommendation, confidence: k.confidence, rating: k.rating || 'watch', sliceKey: (k.sources || [])[0],
      stableId: stable[ki], basis: k.basis || 'judgement', verifiedBy: k.verifiedBy,
    }
    if (k.basis === 'measured' && k.measuredClaim) row.measuredClaim = k.measuredClaim
    if (k.verdict === 'UNVERIFIED') row.unverifiedReason = k.unverifiedReason || (cappedIds.has(k.id) ? 'cap' : 'no-verifier')
    return row
  })
  const refutedOut = refutedRaw.map((d, ri) => ({ findingId: d.id, title: d.title, category: d.category, locations: (d.locations || []).slice(0, 4), stableId: stable[keptRaw.length + ri], basis: d.basis || 'judgement', verifiedBy: d.verifiedBy }))
  const report = synth ? {
    reportPath: plan.reportPath, healthVerdict: synth.healthVerdict, executiveSummary: synth.executiveSummary, themes: synth.themes,
    strengths: synth.strengths, roadmap: synth.roadmap, method: synth.method,
    coverage: {
      reviewers: allSlices.map((s) => ({ key: s.key, label: s.label, kind: s.kind, origin: s.origin })),
      notExamined: missing.map((k) => 'slice ' + k + ': no result').concat(droppedGaps.map((l) => 'gap not run: ' + l)).concat(synth.notExamined || []),
      critic: criticState(plan, critic),
      verification: {
        confirmed: keptRaw.filter((k) => k.verdict === 'CONFIRMED').length, refuted: refutedOut.length,
        unverifiedByCap: capResult.unverified.length, unverifiedNoVerifier: verdicts.filter((v) => v.verdict === 'UNVERIFIED').length,
      },
    },
  } : null

  phase('Triage')
  let triageMap = {}
  if (plan.items.enabled && keptOut.length) {
    const chunks = []
    for (let i = 0; i < keptOut.length; i += 10) chunks.push(keptOut.slice(i, i + 10))
    const triageResults = await parallel(chunks.map((chunk, i) => () =>
      agent('READ-ONLY. For each finding below, run an UNSCOPED items_read search (select="search", no ancestorId or itemId filter, limit=5) using key terms from its title to look for likely duplicate existing items; report up to a few candidates each (id, short, role, title) and a likelyDuplicate verdict of none, weak, or strong.\n\nFINDINGS:\n' +
        JSON.stringify(chunk.map((k) => ({ findingId: k.findingId, title: k.title, category: k.category }))),
        agentOpts(plan, 'triage', { label: 'triage:' + (i + 1), phase: 'Triage', schema: S.TRIAGE }))))
    triageResults.forEach((tr, i) => {
      const chunk = chunks[i]
      if (tr && tr.results) tr.results.forEach((r) => { triageMap[r.findingId] = { candidates: r.candidates || [], likelyDuplicate: r.likelyDuplicate } })
      chunk.forEach((k) => { if (!triageMap[k.findingId]) triageMap[k.findingId] = { candidates: [], likelyDuplicate: 'unknown' } })
    })
  }
  keptOut.forEach((k) => { if (!triageMap[k.findingId]) triageMap[k.findingId] = { candidates: [], likelyDuplicate: 'unknown' } })

  const proposal = buildProposal(keptOut, triageMap, plan)
  const stats = {
    reviewers: slices.filter((s) => s.origin !== 'candidate').length, candidateReviewers: slices.filter((s) => s.origin === 'candidate').length, verifyAgents, gaps: gapResults.length, raw: all.length, merged: merged.length, kept: keptOut.length,
    confirmed: keptRaw.filter((k) => k.verdict === 'CONFIRMED').length, plausible: keptRaw.filter((k) => k.verdict === 'PLAUSIBLE').length,
    unverified: keptRaw.filter((k) => k.verdict === 'UNVERIFIED').length, refuted: refutedOut.length, missing,
    projectedAgents: projected.total, droppedGaps, unverifiedByCap: capResult.unverified.length,
    metricsPending: metricsPendingRows.length, mergeAgents,
  }
  return { contract: 'audit/result-v2', started: true, runId: plan.runId, stats, report, kept: keptOut, refuted: refutedOut, proposal, metricsPath, date: plan.date }
}

// @core-end

const R = normalizeArgs(args)
if (!R.ok) return { started: false, reason: R.reason }
const G = sizeGuard(R.plan)
if (G) return { started: false, reason: G.reason }
return await runAudit(R.plan, { agent, parallel, pipeline, phase, log })
