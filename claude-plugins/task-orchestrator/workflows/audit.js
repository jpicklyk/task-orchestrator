export const meta = {
  name: 'audit',
  description: 'Barrier-per-phase audit: reviewers, gap critic, adversarial verify, synthesis report, and a triage-derived findings proposal.',
  whenToUse: 'Presets quick|standard|full trade reviewer/verifier fan-out for cost; runId, date, and reportPath are required args. quick fits the default maxAgents (60); standard and full project well past 60 agents and need allowLarge:true or a raised maxAgents. full runs roughly 90 minutes and about 30M tokens — prefer quick or standard for routine sweeps.',
  phases: [
    { title: 'Scope' }, { title: 'Review' }, { title: 'Gaps' }, { title: 'Merge' },
    { title: 'Verify' }, { title: 'Synthesize' }, { title: 'Triage' },
  ],
}

// @core-begin

const SEVERITY_RANK = { critical: 4, high: 3, medium: 2, low: 1 }
const LENS_KEYS = ['evidence', 'significance', 'consequence', 'combined']

const PRESETS = {
  quick: { reviewerCount: 4, gaps: { enabled: false, max: 0 }, verify: { highLenses: ['combined'], defaultLenses: ['combined'], maxFindings: 20 } },
  standard: { reviewerCount: 8, gaps: { enabled: true, max: 2 }, verify: { highLenses: ['evidence', 'significance', 'consequence'], defaultLenses: ['combined'], maxFindings: 40 } },
  full: { reviewerCount: 16, gaps: { enabled: true, max: 4 }, verify: { highLenses: ['evidence', 'significance', 'consequence'], defaultLenses: ['combined'], maxFindings: 100 } },
}

const CATEGORIES = [
  'layering', 'coupling', 'cohesion', 'duplication', 'consistency', 'concurrency', 'transactions',
  'error-handling', 'extensibility', 'security-architecture', 'performance-architecture', 'testability',
  'operability', 'api-design', 'data-model', 'documentation-drift', 'dead-code',
]

const DEFAULT_CLASS_MAP = {
  concurrency: 'bug', transactions: 'bug', 'security-architecture': 'bug', 'error-handling': 'bug', 'data-model': 'bug',
  'documentation-drift': 'tech-debt', 'dead-code': 'tech-debt', duplication: 'tech-debt', coupling: 'tech-debt',
  cohesion: 'tech-debt', layering: 'tech-debt', consistency: 'tech-debt', extensibility: 'tech-debt',
  testability: 'tech-debt', 'api-design': 'tech-debt', 'performance-architecture': 'tech-debt', operability: 'tech-debt',
}

const TYPE_MAP_KEY = { bug: 'bug', 'tech-debt': 'techDebt' }

const VERIFY_LENS_INSTRUCTIONS = {
  evidence: 'LENS = EVIDENCE. Open every cited location and check each factual claim in the evidence (what calls what, what is duplicated where and in both places, sizes, missing guards or boundaries). Actively search for counter-evidence elsewhere: a shared helper that already centralizes the logic, a check performed higher in the call stack, a guard in another layer, a test that pins the invariant. Set refuted=true if the CORE claim is false. If it is partly wrong, set refuted=false, evidenceAccurate=false, and give correctedEvidence.',
  significance: 'LENS = SIGNIFICANCE. Argue that this is NOT a real problem worth reporting: an intentional, documented design; severity overstated per the rubric; the recommended change not worth its cost; or already tracked. Set refuted=true only if it is not a genuine problem worth reporting. Otherwise set adjustedSeverity per the rubric and explain.',
  consequence: 'LENS = CONSEQUENCE. Try to demonstrate a concrete consequence: for correctness/concurrency/security findings, a realistic sequence of calls or configuration that produces an observable failure, traced through the code; for maintainability findings, concrete change-amplification evidence (history showing past changes or fixes that had to touch the duplicated places). Set refuted=true if no concrete consequence can be established and the claim is purely theoretical.',
  combined: 'LENS = COMBINED. (1) Open every cited location and verify the factual claims; search for counter-evidence (shared helpers, checks performed elsewhere, guards, tests). (2) Consider whether it is intentional documented design, overstated per the rubric, or already tracked. Set refuted=true if the core claim is false or it is not a genuine problem worth reporting; if partly wrong set evidenceAccurate=false with correctedEvidence.',
}

/** agentOpts(plan, phaseKey, base) -> base merged with model/effort from plan.models[phaseKey]/plan.efforts[phaseKey] when set */
function agentOpts(plan, phaseKey, base) {
  const opts = Object.assign({}, base)
  if (plan.models && plan.models[phaseKey]) opts.model = plan.models[phaseKey]
  if (plan.efforts && plan.efforts[phaseKey]) opts.effort = plan.efforts[phaseKey]
  return opts
}

/** normalizeArgs(raw) -> {ok:true, plan} | {ok:false, reason} */
function normalizeArgs(raw) {
  let A = raw
  if (typeof A === 'string') {
    try { A = JSON.parse(A) } catch (e) { return { ok: false, reason: 'invalid args: bad JSON' } }
  }
  if (!A || typeof A !== 'object' || A.contract !== 'audit/args-v1') {
    return { ok: false, reason: 'invalid args: contract must be audit/args-v1' }
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
  }
  for (const k of verify.highLenses.concat(verify.defaultLenses)) {
    if (LENS_KEYS.indexOf(k) === -1) return { ok: false, reason: 'invalid args: unknown verify lens ' + k }
  }

  const items = Object.assign({ enabled: false, rootId: '', materializeMin: 'high', typeMap: {} }, A.items || {})
  if (items.enabled && !items.rootId) return { ok: false, reason: 'invalid args: items.rootId required when items.enabled' }
  if (items.materializeMin !== 'high' && items.materializeMin !== 'medium') {
    return { ok: false, reason: 'invalid args: items.materializeMin must be high or medium' }
  }

  const gaps = Object.assign({}, preset.gaps, A.gaps || {})
  const plan = {
    contract: 'audit/args-v1', runId: A.runId, date: A.date, repo: A.repo, preset: presetName,
    scope: { paths: scopeIn.paths, reviewers: scopeIn.reviewers || null, lenses: scopeIn.lenses || [], reviewerCount: scopeIn.reviewerCount || preset.reviewerCount },
    rubric: A.rubric || null, categories: A.categories || CATEGORIES, backlog: A.backlog || [], gaps, verify,
    models: A.models || {}, efforts: A.efforts || {}, reportPath: A.reportPath, items,
    maxAgents: A.maxAgents != null ? A.maxAgents : 60, allowLarge: !!A.allowLarge, title: A.title || A.runId,
    classMap: Object.assign({}, DEFAULT_CLASS_MAP, A.classMap || {}), observationPaths: A.observationPaths || [],
  }
  return { ok: true, plan }
}

/** projectAgents(plan) -> {total, scope, review, gaps, merge, verify, synth, triage} (upper-bound cost projection) */
function projectAgents(plan) {
  const reviewersGiven = !!(plan.scope.reviewers && plan.scope.reviewers.length)
  const R = reviewersGiven ? plan.scope.reviewers.length + (plan.scope.lenses || []).length : plan.scope.reviewerCount
  const scope = reviewersGiven ? 0 : 1
  const review = R
  const gapsEnabled = !!plan.gaps.enabled
  const gaps = gapsEnabled ? 1 + plan.gaps.max : 0
  const est = (R + (gapsEnabled ? plan.gaps.max : 0)) * 8
  const nV = Math.min(est, plan.verify.maxFindings)
  const verify = nV * Math.max(plan.verify.highLenses.length, plan.verify.defaultLenses.length)
  const merge = 1
  const synth = 1
  const triage = plan.items.enabled ? Math.ceil(nV / 10) : 0
  return { total: scope + review + gaps + merge + verify + synth + triage, scope, review, gaps, merge, verify, synth, triage }
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
    const locations = []
    const locSeen = new Set()
    members.forEach((m) => (m.locations || []).forEach((l) => { if (!locSeen.has(l)) { locSeen.add(l); locations.push(l) } }))
    const otherIds = members.slice(1).map((m) => m.id)
    const evidence = canon.evidence + (otherIds.length ? '\n\nCorroborating evidence from: ' + otherIds.join(', ') : '')
    merged.push({
      id: canon.id, title: g.mergedTitle || canon.title, severity: maxSev, category: canon.category, locations, evidence,
      impact: canon.impact, recommendation: canon.recommendation, effort: canon.effort, confidence: canon.confidence,
      sources: Array.from(new Set(members.map((m) => m.source))), memberIds: ids,
    })
  }
  all.forEach((f) => {
    if (!seen.has(f.id)) {
      seen.add(f.id)
      merged.push({
        id: f.id, title: f.title, severity: f.severity, category: f.category, locations: f.locations, evidence: f.evidence,
        impact: f.impact, recommendation: f.recommendation, effort: f.effort, confidence: f.confidence,
        sources: [f.source], memberIds: [f.id],
      })
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

/** capVerification(merged, maxFindings) -> {verify, unverified} (stable severity-desc sort; first maxFindings -> verify) */
function capVerification(merged, maxFindings) {
  const indexed = merged.map((f, i) => ({ f, i }))
  indexed.sort((a, b) => { const d = SEVERITY_RANK[b.f.severity] - SEVERITY_RANK[a.f.severity]; return d !== 0 ? d : a.i - b.i })
  return { verify: indexed.slice(0, maxFindings).map((x) => x.f), unverified: indexed.slice(maxFindings).map((x) => x.f) }
}

/** reportIds(synth) -> {[findingId]: reportId}, or {} when synth is null */
function reportIds(synth) {
  if (!synth) return {}
  const out = {}
  for (const fi of synth.findingIndex || []) out[fi.findingId] = fi.reportId
  return out
}

/** schemas(plan) -> {SCOPE, FINDINGS, CRITIC, DEDUP, VERDICT, SYNTH, TRIAGE} StructuredOutput JSON Schemas; FINDINGS.category enum = plan.categories */
function schemas(plan) {
  const SCOPE = {
    type: 'object',
    properties: {
      reviewers: { type: 'array', items: { type: 'object', properties: { key: { type: 'string' }, label: { type: 'string' }, focus: { type: 'string' }, paths: { type: 'array', items: { type: 'string' } } }, required: ['key', 'label', 'focus', 'paths'] } },
      lenses: { type: 'array', items: { type: 'object', properties: { key: { type: 'string' }, label: { type: 'string' }, focus: { type: 'string' } }, required: ['key', 'label', 'focus'] } },
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
          },
          required: ['title', 'severity', 'category', 'locations', 'evidence', 'impact', 'recommendation', 'effort', 'confidence'],
        },
      },
    },
    required: ['sliceSummary', 'strengths', 'findings'],
  }
  const CRITIC = {
    type: 'object',
    properties: { assessment: { type: 'string' }, gaps: { type: 'array', items: { type: 'object', properties: { label: { type: 'string' }, prompt: { type: 'string' } }, required: ['label', 'prompt'] } } },
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
  const SYNTH = {
    type: 'object',
    properties: {
      reportPath: { type: 'string' }, healthVerdict: { type: 'string' }, executiveSummary: { type: 'string' },
      themes: { type: 'array', items: { type: 'object', properties: { name: { type: 'string' }, summary: { type: 'string' }, reportIds: { type: 'array', items: { type: 'string' } } }, required: ['name', 'summary', 'reportIds'] } },
      findingIndex: { type: 'array', items: { type: 'object', properties: { reportId: { type: 'string' }, findingId: { type: 'string' }, title: { type: 'string' }, severity: { type: 'string' }, effort: { type: 'string' }, verdict: { type: 'string' }, alreadyTrackedId: { type: 'string' } }, required: ['reportId', 'findingId', 'title', 'severity', 'effort', 'verdict', 'alreadyTrackedId'] } },
      roadmap: { type: 'object', properties: { now: { type: 'array', items: { type: 'string' } }, next: { type: 'array', items: { type: 'string' } }, later: { type: 'array', items: { type: 'string' } } }, required: ['now', 'next', 'later'] },
    },
    required: ['reportPath', 'healthVerdict', 'executiveSummary', 'themes', 'findingIndex', 'roadmap'],
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
  return { SCOPE, FINDINGS, CRITIC, DEDUP, VERDICT, SYNTH, TRIAGE }
}

/** reviewerPreamble(plan) -> string (shared READ-ONLY preamble; all repo facts come from plan.repo/plan.rubric/plan.backlog) */
function reviewerPreamble(plan) {
  const backlog = (plan.backlog || []).map((b) => '- ' + b.id + ' — ' + b.title).join('\n')
  const rubric = plan.rubric ? JSON.stringify(plan.rubric) : 'critical/high/medium/low, standard severity practice'
  let out = 'You are performing ONE slice of an audit of the repository at ' + plan.repo.root
  if (plan.repo.description) out += ' (' + plan.repo.description + ')'
  out += '.\n\nHARD RULES: READ-ONLY. Do not create, edit, or delete any file. Do not run any command that mutates repository or environment state. Do not call any write tool. Do not prefix bash commands with cd.\n\n'
  out += 'Severity rubric: ' + rubric + '\n\n'
  if (backlog) out += 'ALREADY-TRACKED BACKLOG (do not re-report these as-is):\n' + backlog + '\n\n'
  return out + 'YOUR SLICE:\n'
}

/** verifierPreamble(plan) -> string (shared READ-ONLY preamble for adversarial verifiers: backlog, rubric, and VERDICT field semantics incl. alreadyTrackedId derivation) */
function verifierPreamble(plan) {
  const backlog = (plan.backlog || []).map((b) => '- ' + b.id + ' — ' + b.title).join('\n')
  const rubric = plan.rubric ? JSON.stringify(plan.rubric) : 'critical/high/medium/low, standard severity practice'
  let out = 'You are an ADVERSARIAL VERIFIER for ONE finding from an audit of the repository at ' + plan.repo.root
  if (plan.repo.description) out += ' (' + plan.repo.description + ')'
  out += '.\n\nHARD RULES: READ-ONLY. Do not create, edit, or delete any file. Do not run any command that mutates repository or environment state. Do not call any write tool. Do not prefix bash commands with cd. Default stance: skeptical — the finding must earn its place.\n\n'
  out += 'Severity rubric: ' + rubric + '\n\n'
  if (backlog) out += 'ALREADY-TRACKED BACKLOG (open items; use these, and a search, to judge alreadyTrackedId):\n' + backlog + '\n\n'
  out += 'Always fill every verdict field. refuted = true iff, under your lens, this finding should be dropped. confidence = your confidence in refuted. evidenceAccurate = false only when a cited fact is actually wrong (then explain the correction in correctedEvidence; leave correctedEvidence empty when the evidence is accurate). adjustedSeverity = your own severity per the rubric (may equal, raise, or lower the reported one). alreadyTrackedId = the id of a backlog entry that substantially already covers this finding — if the backlog list above is not conclusive, run a query_items search (operation="search") by the finding\'s title keywords to check; leave it the empty string when nothing already covers this finding. rationale = a short justification for your verdict.\n\n'
  return out
}

/** buildReviewerPrompt(plan, reviewer) -> string */
function buildReviewerPrompt(plan, r) {
  return reviewerPreamble(plan) + 'FOCUS: ' + r.focus + '. PATHS: ' + (r.paths || []).join(', ') + '.'
}

/** buildLensPrompt(plan, lens) -> string */
function buildLensPrompt(plan, l) {
  return reviewerPreamble(plan) + 'CROSS-CUTTING LENS — ' + l.label + '. FOCUS: ' + l.focus + '. Examine across all scoped paths: ' + plan.scope.paths.join(', ') + '.'
}

/** buildGapPrompt(plan, gap) -> string */
function buildGapPrompt(plan, g) {
  return reviewerPreamble(plan) + 'FOCUS: ' + g.prompt
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

/** buildProposal(kept, triage, plan) -> proposal | null; kept = result-v1 kept[]; triage = {[findingId]:{candidates, likelyDuplicate}}; null when !plan.items.enabled */
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

    let materialize = true
    let reason = 'ok'
    if (SEVERITY_RANK[k.severity] < minRank) { materialize = false; reason = 'severity below ' + plan.items.materializeMin }
    else if (k.verdict !== 'CONFIRMED' && k.verdict !== 'PLAUSIBLE') { materialize = false; reason = 'verdict ' + k.verdict }
    else if (k.trackedId) { materialize = false; reason = 'tracked ' + k.trackedId }
    else if (t.likelyDuplicate === 'strong' || t.likelyDuplicate === 'unknown') { materialize = false; reason = 'likely duplicate ' + t.likelyDuplicate }

    const entry = { findingId: k.findingId, reportId: k.reportId, class: cls, title: k.title, summary, description, priority, tags, materialize, reason, candidates: t.candidates, likelyDuplicate: t.likelyDuplicate }
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

/** runAudit(plan, deps{agent, parallel, pipeline, phase, log}) -> audit/result-v1; Scope(opt) -> Review -> Gaps -> Merge -> Verify -> Synthesize -> Triage(opt) */
async function runAudit(plan, deps) {
  const { agent, parallel, pipeline, phase, log } = deps
  const S = schemas(plan)
  const projected = projectAgents(plan)
  log('projected ' + projected.total + ' agents')

  phase('Scope')
  let reviewers = plan.scope.reviewers
  let lenses = plan.scope.lenses || []
  if (!reviewers || !reviewers.length) {
    const scopeResult = await agent(
      'READ-ONLY. Determine the review scope for an audit. Repository root: ' + plan.repo.root + '. Candidate paths: ' + plan.scope.paths.join(', ') +
        '. Propose up to ' + plan.scope.reviewerCount + ' reviewer slices (key, label, focus, paths) and any cross-cutting lenses (key, label, focus) worth a dedicated pass.',
      agentOpts(plan, 'scope', { label: 'scope', phase: 'Scope', schema: S.SCOPE }),
    )
    if (!scopeResult) {
      return {
        contract: 'audit/result-v1', started: true, runId: plan.runId,
        stats: { reviewers: 0, gaps: 0, raw: 0, merged: 0, kept: 0, confirmed: 0, plausible: 0, unverified: 0, refuted: 0, missing: ['scope'], projectedAgents: projected.total, droppedGaps: [], unverifiedByCap: 0 },
        report: null, kept: [], refuted: [], proposal: null,
      }
    }
    reviewers = scopeResult.reviewers || []
    if (reviewers.length > plan.scope.reviewerCount) {
      const droppedReviewers = reviewers.slice(plan.scope.reviewerCount).map((r) => r.key)
      reviewers = reviewers.slice(0, plan.scope.reviewerCount)
      log('scope proposed ' + (reviewers.length + droppedReviewers.length) + ' reviewers; keeping first ' + plan.scope.reviewerCount + ', dropped: ' + droppedReviewers.join(', '))
    }
    lenses = scopeResult.lenses || []
  }

  phase('Review')
  const reviewItems = reviewers.map((r) => ({ key: r.key, label: r.label, prompt: buildReviewerPrompt(plan, r) }))
    .concat(lenses.map((l) => ({ key: l.key, label: l.label, prompt: buildLensPrompt(plan, l) })))
  const reviewed = await parallel(reviewItems.map((it) => () =>
    agent(it.prompt, agentOpts(plan, 'review', { label: 'review:' + it.key, phase: 'Review', schema: S.FINDINGS })).then((r) => (r ? Object.assign({}, r, { key: it.key, label: it.label }) : null))))
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
        ' MATERIAL gaps — subsystems or concerns not covered — each with a label and a prompt (paths and questions) for a focused follow-up reviewer. Return an empty gaps array if coverage is adequate.\n\nCOVERAGE:\n' + coverage,
      agentOpts(plan, 'critic', { label: 'critic', phase: 'Gaps', schema: S.CRITIC }),
    )
    const gapList = (critic && critic.gaps) || []
    const runGaps = gapList.slice(0, plan.gaps.max)
    droppedGaps = gapList.slice(plan.gaps.max).map((g) => g.label)
    if (droppedGaps.length) log('critic proposed ' + gapList.length + ' gaps; running first ' + plan.gaps.max + ', dropped: ' + droppedGaps.join('; '))
    const gapRun = await parallel(runGaps.map((g, i) => () =>
      agent(buildGapPrompt(plan, g), agentOpts(plan, 'gap', { label: 'gap:' + (i + 1), phase: 'Gaps', schema: S.FINDINGS })).then((r) => (r ? Object.assign({}, r, { key: 'gap' + (i + 1), label: 'Gap: ' + g.label }) : null))))
    gapResults = gapRun.filter(Boolean)
    log('gap reviews: ' + gapResults.length + ' run')
  }
  const allSlices = slices.concat(gapResults)

  phase('Merge')
  const all = flattenFindings(allSlices)
  let merged = []
  if (all.length > 0) {
    const digest = all.map((f) => f.id + ' | ' + f.severity + ' | ' + f.category + ' | ' + f.title + ' | ' + (f.locations || []).slice(0, 4).join(', ') + ' | ' + f.evidence.slice(0, 300).replace(/\n/g, ' ')).join('\n')
    const dd = await agent(
      'READ-ONLY. You are deduplicating findings from an audit of ' + plan.repo.root + '. Group findings describing the SAME underlying problem (same root cause and remedy); every id below must appear in exactly one group (singletons are groups with an empty duplicateIds). canonicalId = the best-evidenced member. mergedTitle = a precise merged title. Do not read files; work only from the digest below.\n\nDIGEST (id | severity | category | title | locations | evidence excerpt):\n' + digest,
      agentOpts(plan, 'merge', { label: 'merge', phase: 'Merge', schema: S.DEDUP }),
    )
    merged = mergeFindings(all, (dd && dd.groups) || [])
    log(all.length + ' raw findings -> ' + merged.length + ' after dedup')
  }

  phase('Verify')
  let capResult = { verify: [], unverified: [] }
  let verdicts = []
  if (all.length > 0) {
    capResult = capVerification(merged, plan.verify.maxFindings)
    if (capResult.unverified.length) log(capResult.unverified.length + ' findings unverified by cap')
    verdicts = await parallel(capResult.verify.map((f) => async () => {
      const lensKeys = verifyPlan(f, plan.verify)
      const findingText = JSON.stringify({ id: f.id, title: f.title, severity: f.severity, category: f.category, locations: f.locations, evidence: f.evidence, impact: f.impact, recommendation: f.recommendation })
      const votes = await parallel(lensKeys.map((l) => () =>
        agent(verifierPreamble(plan) + VERIFY_LENS_INSTRUCTIONS[l] + '\n\nFINDING:\n' + findingText,
          agentOpts(plan, 'verify', { label: 'verify:' + f.id + ':' + l, phase: 'Verify', schema: S.VERDICT }))))
      return Object.assign({}, f, decideVerdict(votes, lensKeys, f.severity))
    }))
  }
  const cappedUnverified = capResult.unverified.map((f) => Object.assign({}, f, { verdict: 'UNVERIFIED', dropped: false, finalSeverity: f.severity, trackedId: '', correctedEvidence: '' }))
  const allVerdicts = verdicts.concat(cappedUnverified)
  const keptRaw = allVerdicts.filter((v) => !v.dropped)
  const refutedRaw = allVerdicts.filter((v) => v.dropped)
  log('verification: ' + keptRaw.length + ' kept, ' + refutedRaw.length + ' refuted')

  phase('Synthesize')
  const keptPayload = keptRaw.map((k) => ({ findingId: k.id, title: k.title, severity: k.finalSeverity, category: k.category, locations: k.locations, evidence: k.evidence, correctedEvidence: k.correctedEvidence, impact: k.impact, recommendation: k.recommendation, effort: k.effort, verdict: k.verdict, trackedId: k.trackedId }))
  const refutedPayload = refutedRaw.map((d) => ({ findingId: d.id, title: d.title }))
  const slicePayload = allSlices.map((s) => ({ key: s.key, label: s.label, summary: s.sliceSummary, strengths: s.strengths }))
  const synth = await agent(
    'You are the lead architect writing the FINAL REPORT of an audit of ' + plan.repo.root + ' run on ' + plan.date + '. ' + slices.length +
      ' reviewer(s) examined subsystem slices and cross-cutting lenses' + (gapResults.length ? ' plus ' + gapResults.length + ' gap follow-up(s)' : '') +
      '; their findings were deduplicated and adversarially verified. Verdicts: CONFIRMED = survived every verifier; PLAUSIBLE = kept, but at least one verifier objected (say so in the finding); UNVERIFIED = no verifier returned, or the finding fell beyond the verification cap. Use finalSeverity (verifier-adjusted), not the originally reported severity. Apply correctedEvidence: drop or fix any evidence a verifier showed to be wrong. You may open source files to make the architecture map accurate, but do NOT re-litigate verdicts and do NOT add new findings.\n\n' +
      'Write the report to reportPath = ' + plan.reportPath + ' (this is the only file you may create; no other writes, no command that mutates repository or environment state).\n\n' +
      'REPORT (Markdown) must contain, in order: (1) Title; date ' + plan.date + '; scope and method (reviewers, lenses, dedup, verification lenses, counts). (2) Executive summary: overall health verdict in one sentence, then balanced bullets (strengths and problems). (3) Strengths to preserve, deduplicated and specific. (4) Systemic themes grouping findings by root cause, each naming the findings it explains (report ids) and the structural remedy. (5) Findings: assign report ids ordered by finalSeverity (critical -> low) then leverage; a summary table, then one subsection per finding with severity/verdict/effort/category, locations, evidence, impact, recommendation, and "Already tracked: <id>" where applicable. (6) Recommended roadmap: now/next/later, each entry referencing report ids. (7) Appendix: refuted findings (one line each with the reason) and coverage (reviewers and gap follow-ups run).\n\n' +
      'Then return the structured summary: reportPath, healthVerdict (one sentence), executiveSummary (<=150 words), themes, findingIndex (every report id mapped to its findingId), roadmap (short strings referencing report ids).\n\n' +
      'SLICE SUMMARIES & STRENGTHS:\n' + JSON.stringify(slicePayload) + '\n\nKEPT FINDINGS (' + keptPayload.length + '):\n' + JSON.stringify(keptPayload) +
      '\n\nREFUTED FINDINGS (' + refutedPayload.length + '):\n' + JSON.stringify(refutedPayload) +
      '\n\nGAP CRITIC ASSESSMENT:\n' + ((critic && critic.assessment) || '(no critic result)'),
    agentOpts(plan, 'synthesis', { label: 'synthesis', phase: 'Synthesize', schema: S.SYNTH }),
  )
  const ridMap = reportIds(synth)
  const keptOut = keptRaw.map((k) => ({ findingId: k.id, reportId: ridMap[k.id] || '', title: k.title, severity: k.finalSeverity, verdict: k.verdict, category: k.category, effort: k.effort, locations: (k.locations || []).slice(0, 4), trackedId: k.trackedId || '' }))
  const refutedOut = refutedRaw.map((d) => ({ findingId: d.id, title: d.title }))
  const report = synth ? { path: synth.reportPath, healthVerdict: synth.healthVerdict, executiveSummary: synth.executiveSummary, themes: synth.themes } : null

  phase('Triage')
  let triageMap = {}
  const cappedIds = new Set(capResult.unverified.map((f) => f.id))
  const triageTargets = keptOut.filter((k) => !cappedIds.has(k.findingId))
  if (plan.items.enabled && triageTargets.length) {
    const chunks = []
    for (let i = 0; i < triageTargets.length; i += 10) chunks.push(triageTargets.slice(i, i + 10))
    const triageResults = await parallel(chunks.map((chunk, i) => () =>
      agent('READ-ONLY. For each finding below, run an UNSCOPED query_items search (operation="search", no scope filter, limit=5) using key terms from its title to look for likely duplicate existing items; report up to a few candidates each (id, short, role, title) and a likelyDuplicate verdict of none, weak, or strong.\n\nFINDINGS:\n' +
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
    reviewers: slices.length, gaps: gapResults.length, raw: all.length, merged: merged.length, kept: keptOut.length,
    confirmed: keptRaw.filter((k) => k.verdict === 'CONFIRMED').length, plausible: keptRaw.filter((k) => k.verdict === 'PLAUSIBLE').length,
    unverified: keptRaw.filter((k) => k.verdict === 'UNVERIFIED').length, refuted: refutedOut.length, missing,
    projectedAgents: projected.total, droppedGaps, unverifiedByCap: capResult.unverified.length,
  }
  return { contract: 'audit/result-v1', started: true, runId: plan.runId, stats, report, kept: keptOut, refuted: refutedOut, proposal }
}

// @core-end

const R = normalizeArgs(args)
if (!R.ok) return { started: false, reason: R.reason }
const G = sizeGuard(R.plan)
if (G) return { started: false, reason: G.reason }
return await runAudit(R.plan, { agent, parallel, pipeline, phase, log })
