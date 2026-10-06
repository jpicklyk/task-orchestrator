export const meta = {
  name: 'retro-analysis',
  description: 'Two-phase retrospective trend matcher: shards trends, observations, and retrospectives across Match agents, then adjudicates ambiguous and orphan findings into matched trends or new-trend candidates.',
  whenToUse: 'Two modes: audit runs a standalone sweep over trends, observations, and retrospectives; deep matches findings the session-retrospective skill supplies via its --deep flag. runId and date are required. User-invoked only, never launched from a hook or a background agent. Every phase is read-only -- the skill performs all writes afterward.',
  phases: [
    { title: 'Match' }, { title: 'Adjudicate' },
  ],
}

// @core-begin

const DIMENSIONS = ['schema-effectiveness', 'delegation', 'note-quality', 'plan-to-execution', 'friction', 'extension-candidate']
const KINDS = [{ key: 'trends', kind: 'trend' }, { key: 'observations', kind: 'observation' }, { key: 'retros', kind: 'retro' }]
const CAPS = { findings: 40, trends: 200, observations: 200, retros: 30 }
const DEFAULTS = { shardSize: 15, maxAgents: 30, allowLarge: false, maxAdjudicate: 12, staleDays: 60 }

/** kebab(s) -> string (lowercase, non-alphanumeric runs -> '-', trimmed) */
function kebab(s) {
  if (s === null || s === undefined) return ''
  return String(s).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
}

/** trendKey(title) -> string | null (leading "trend: <kebab-key>" prefix, lowercased) */
function trendKey(title) {
  const m = /^trend:\s*([a-z0-9]+(?:-[a-z0-9]+)*)/i.exec(title || '')
  return m ? m[1].toLowerCase() : null
}

/** daysBetween(a, b) -> integer | null (b - a in whole days via Date.UTC; null unless both YYYY-MM-DD) */
function daysBetween(a, b) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(a) || !/^\d{4}-\d{2}-\d{2}$/.test(b)) return null
  const [ay, am, ad] = a.split('-').map(Number)
  const [by, bm, bd] = b.split('-').map(Number)
  const msA = Date.UTC(ay, am - 1, ad)
  const msB = Date.UTC(by, bm - 1, bd)
  return Math.round((msB - msA) / 86400000)
}

/** pickCandidate(cands) -> trendId (first strong candidate, else the first candidate) */
function pickCandidate(cands) {
  const strong = cands.find((c) => c.strength === 'strong')
  return (strong || cands[0]).trendId
}

/** agentOpts(plan, key, base) -> base merged with model/effort from plan.models[key]/plan.efforts[key] when set */
function agentOpts(plan, key, base) {
  const opts = Object.assign({}, base)
  if (plan.models && plan.models[key]) opts.model = plan.models[key]
  if (plan.efforts && plan.efforts[key]) opts.effort = plan.efforts[key]
  return opts
}

/** normalizeArgs(raw) -> {ok:true, plan} | {ok:false, reason} */
function normalizeArgs(raw) {
  let A = raw
  if (typeof A === 'string') {
    try { A = JSON.parse(A) } catch (e) { return { ok: false, reason: 'invalid args: bad JSON' } }
  }
  if (!A || typeof A !== 'object' || A.contract !== 'retro-analysis/args-v1') {
    return { ok: false, reason: 'invalid args: contract must be retro-analysis/args-v1' }
  }
  for (const f of ['runId', 'date', 'mode']) {
    if (!A[f]) return { ok: false, reason: 'invalid args: missing ' + f }
  }
  if (String(A.runId).indexOf('ra-') !== 0) return { ok: false, reason: 'invalid args: runId must start with ra-' }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(A.date)) return { ok: false, reason: 'invalid args: date must be YYYY-MM-DD' }
  if (A.mode !== 'audit' && A.mode !== 'deep') return { ok: false, reason: 'invalid args: unknown mode ' + A.mode }

  const shardSize = A.shardSize != null ? A.shardSize : DEFAULTS.shardSize
  if (!Number.isInteger(shardSize) || shardSize < 5 || shardSize > 30) {
    return { ok: false, reason: 'invalid args: shardSize must be 5-30' }
  }
  if (A.maxAgents != null && (!Number.isInteger(A.maxAgents) || A.maxAgents < 1)) {
    return { ok: false, reason: 'invalid args: maxAgents must be a positive integer' }
  }
  if (A.staleDays != null && (!Number.isInteger(A.staleDays) || A.staleDays < 1)) {
    return { ok: false, reason: 'invalid args: staleDays must be a positive integer' }
  }
  if (A.maxAdjudicate != null && (!Number.isInteger(A.maxAdjudicate) || A.maxAdjudicate < 0)) {
    return { ok: false, reason: 'invalid args: maxAdjudicate must be an integer, 0 or more' }
  }

  let findingsIn = A.findings
  if (findingsIn === undefined || findingsIn === null) findingsIn = []
  if (!Array.isArray(findingsIn)) return { ok: false, reason: 'invalid args: findings must be an array' }
  if (findingsIn.length > CAPS.findings) {
    return { ok: false, reason: 'invalid args: too many findings (' + findingsIn.length + ' > ' + CAPS.findings + ')' }
  }

  const fidSeen = new Set()
  const findings = []
  for (let i = 0; i < findingsIn.length; i++) {
    const f = findingsIn[i]
    const bad = !f || typeof f !== 'object' || !f.fid || !f.text ||
      DIMENSIONS.indexOf(f.dimension) === -1 || (f.keywords !== undefined && !Array.isArray(f.keywords))
    if (bad) return { ok: false, reason: 'invalid args: bad finding ' + i }
    if (fidSeen.has(f.fid)) return { ok: false, reason: 'invalid args: duplicate fid ' + f.fid }
    fidSeen.add(f.fid)
    findings.push({ fid: f.fid, dimension: f.dimension, text: f.text, keywords: f.keywords || [] })
  }

  if (A.mode === 'deep' && findings.length === 0) return { ok: false, reason: 'invalid args: deep mode needs findings' }
  if (A.mode === 'audit' && findings.length > 0) return { ok: false, reason: 'invalid args: audit mode takes no findings' }

  const kindArrays = {}
  for (const { key } of KINDS) {
    let arr = A[key]
    if (arr === undefined || arr === null) arr = []
    if (!Array.isArray(arr)) return { ok: false, reason: 'invalid args: ' + key + ' must be an array' }
    if (arr.length > CAPS[key]) {
      return { ok: false, reason: 'invalid args: too many ' + key + ' (' + arr.length + ' > ' + CAPS[key] + ')' }
    }
    const idSeen = new Set()
    const out = []
    for (let i = 0; i < arr.length; i++) {
      const e = arr[i]
      if (!e || typeof e !== 'object' || !e.id || !e.title) return { ok: false, reason: 'invalid args: bad ' + key + ' entry ' + i }
      if (idSeen.has(e.id)) return { ok: false, reason: 'invalid args: duplicate ' + key + ' id ' + e.id }
      idSeen.add(e.id)
      out.push({ id: e.id, short: e.short || String(e.id).slice(0, 8), title: e.title })
    }
    kindArrays[key] = out
  }

  if (!kindArrays.trends.length && !kindArrays.observations.length && !kindArrays.retros.length) {
    return { ok: false, reason: 'invalid args: nothing to match' }
  }

  const rootId = A.rootId || null
  const planDocSlug = A.planDocSlug || null
  if (planDocSlug !== null) {
    if (planDocSlug !== 'retro/' + A.runId) return { ok: false, reason: 'invalid args: planDocSlug must be retro/' + A.runId }
    if (!rootId) return { ok: false, reason: 'invalid args: planDocSlug requires rootId' }
  }

  const plan = {
    contract: 'retro-analysis/args-v1', runId: A.runId, date: A.date, mode: A.mode, rootId, planDocSlug,
    findings, trends: kindArrays.trends, observations: kindArrays.observations, retros: kindArrays.retros,
    shardSize, maxAgents: A.maxAgents != null ? A.maxAgents : DEFAULTS.maxAgents, allowLarge: !!A.allowLarge,
    maxAdjudicate: A.maxAdjudicate != null ? A.maxAdjudicate : DEFAULTS.maxAdjudicate,
    staleDays: A.staleDays != null ? A.staleDays : DEFAULTS.staleDays,
    models: A.models || {}, efforts: A.efforts || {},
  }
  return { ok: true, plan }
}

/** projectAgents(plan) -> {total, match, adjudicate} (upper-bound cost projection) */
function projectAgents(plan) {
  let match = 0
  for (const { key } of KINDS) {
    const n = plan[key].length
    if (n > 0) match += Math.ceil(n / plan.shardSize)
  }
  const adjudicate = (plan.mode === 'deep' ? Math.min(plan.findings.length, plan.maxAdjudicate) : 0) + 1
  return { total: match + adjudicate, match, adjudicate }
}

/** sizeGuard(plan) -> null | {reason} (refuses when projection > maxAgents and !allowLarge) */
function sizeGuard(plan) {
  const projected = projectAgents(plan)
  if (projected.total > plan.maxAgents && !plan.allowLarge) {
    return { reason: 'projected ' + projected.total + ' agents > maxAgents; pass allowLarge or a larger shardSize' }
  }
  return null
}

/** makeShards(plan) -> [{label:'match:<kind>:<n>', kind, targets}] (kinds in KINDS order, exact partition) */
function makeShards(plan) {
  const shards = []
  for (const { key, kind } of KINDS) {
    const arr = plan[key]
    for (let i = 0; i < arr.length; i += plan.shardSize) {
      const n = Math.floor(i / plan.shardSize) + 1
      shards.push({ label: 'match:' + kind + ':' + n, kind, targets: arr.slice(i, i + plan.shardSize) })
    }
  }
  return shards
}

/** schemas(plan) -> {MATCH, ADJUDICATE, CLUSTER} StructuredOutput JSON Schemas */
function schemas(plan) {
  const MATCH = {
    type: 'object',
    properties: {
      matches: {
        type: 'array',
        items: {
          type: 'object',
          properties: {
            fid: { type: 'string' }, targetId: { type: 'string' },
            strength: { type: 'string', enum: ['strong', 'weak'] },
            evidence: { type: 'string' }, sessionsSeen: { type: 'integer' },
          },
          required: ['fid', 'targetId', 'strength', 'evidence', 'sessionsSeen'],
        },
      },
      clusters: {
        type: 'array',
        items: {
          type: 'object',
          properties: {
            label: { type: 'string' }, claim: { type: 'string' },
            dimension: { type: 'string', enum: DIMENSIONS },
            targetIds: { type: 'array', items: { type: 'string' } },
          },
          required: ['label', 'claim', 'dimension', 'targetIds'],
        },
      },
      trendState: {
        type: 'array',
        items: {
          type: 'object',
          properties: { trendId: { type: 'string' }, sessions: { type: 'integer' }, lastSeen: { type: 'string' } },
          required: ['trendId', 'sessions', 'lastSeen'],
        },
      },
    },
    required: ['matches', 'clusters', 'trendState'],
  }
  const ADJUDICATE = {
    type: 'object',
    properties: {
      decision: { type: 'string', enum: ['match', 'new'] }, trendId: { type: 'string' },
      kebabKey: { type: 'string' }, claim: { type: 'string' }, rationale: { type: 'string' },
    },
    required: ['decision', 'trendId', 'kebabKey', 'claim', 'rationale'],
  }
  const CLUSTER = {
    type: 'object',
    properties: {
      newTrends: {
        type: 'array',
        items: {
          type: 'object',
          properties: {
            kebabKey: { type: 'string' }, claim: { type: 'string' }, dimension: { type: 'string', enum: DIMENSIONS },
            fids: { type: 'array', items: { type: 'string' } }, observationIds: { type: 'array', items: { type: 'string' } },
            evidenceRefs: { type: 'array', items: { type: 'string' } },
          },
          required: ['kebabKey', 'claim', 'dimension', 'fids', 'observationIds', 'evidenceRefs'],
        },
      },
      observationLinks: {
        type: 'array',
        items: {
          type: 'object',
          properties: { observationId: { type: 'string' }, trendId: { type: 'string' } },
          required: ['observationId', 'trendId'],
        },
      },
    },
    required: ['newTrends', 'observationLinks'],
  }
  return { MATCH, ADJUDICATE, CLUSTER }
}

/**
 * collectMatches(plan, shards, results) -> {byFid, clusters, trendState, unmatchedShards, dropped, returned}
 * results is aligned with shards; a null entry means that shard's agent returned nothing.
 */
function collectMatches(plan, shards, results) {
  const knownFids = new Set(plan.findings.map((f) => f.fid))
  const byFid = {}
  const clusters = []
  const trendState = {}
  const unmatchedShards = []
  const seenPairs = new Set()
  let dropped = 0
  let returned = 0

  for (let si = 0; si < shards.length; si++) {
    const shard = shards[si]
    const result = results[si]
    const shardIds = new Set(shard.targets.map((t) => t.id))

    if (!result) {
      unmatchedShards.push({ label: shard.label, kind: shard.kind, targetIds: shard.targets.map((t) => t.id) })
      continue
    }
    returned += 1

    for (const m of (result.matches || [])) {
      if (plan.mode === 'audit') {
        if (!m.fid) continue
      } else if (!knownFids.has(m.fid) || !shardIds.has(m.targetId)) {
        dropped += 1
        continue
      }
      const pairKey = m.fid + '\u0000' + m.targetId
      if (seenPairs.has(pairKey)) continue
      seenPairs.add(pairKey)
      if (!byFid[m.fid]) byFid[m.fid] = []
      byFid[m.fid].push({ targetId: m.targetId, kind: shard.kind, strength: m.strength, evidence: m.evidence, sessionsSeen: m.sessionsSeen, label: shard.label })
    }

    for (const c of (result.clusters || [])) {
      const ids = (c.targetIds || []).filter((id) => shardIds.has(id))
      if (ids.length === 0) continue
      clusters.push(Object.assign({}, c, { targetIds: ids }))
    }

    if (shard.kind === 'trend') {
      for (const ts of (result.trendState || [])) {
        if (!shardIds.has(ts.trendId)) continue
        if (!(ts.trendId in trendState)) trendState[ts.trendId] = ts
      }
    }
  }

  return { byFid, clusters, trendState, unmatchedShards, dropped, returned }
}

/**
 * routeFindings(plan, collected) -> {direct, adjudicate, capped, orphans, uncoveredObservations}
 * findings processed in input order; trend candidates per finding are distinct/first-seen.
 */
function routeFindings(plan, collected) {
  const direct = []
  const adjudicate = []
  const capped = []
  const orphans = []
  let adjudicateCount = 0

  for (const finding of plan.findings) {
    const matches = collected.byFid[finding.fid] || []
    const byTrend = new Map()
    for (const m of matches) {
      if (m.kind !== 'trend') continue
      const existing = byTrend.get(m.targetId)
      if (!existing) byTrend.set(m.targetId, { trendId: m.targetId, strength: m.strength, evidence: m.evidence })
      else if (m.strength === 'strong') existing.strength = 'strong'
    }
    const candidates = Array.from(byTrend.values())
    if (candidates.length === 0) {
      orphans.push(finding)
    } else if (candidates.length === 1) {
      direct.push({ fid: finding.fid, trendId: candidates[0].trendId })
    } else if (adjudicateCount < plan.maxAdjudicate) {
      adjudicate.push({ finding, candidates })
      adjudicateCount += 1
    } else {
      capped.push({ fid: finding.fid, trendId: pickCandidate(candidates) })
    }
  }

  const matchedObservationIds = new Set()
  for (const fid in collected.byFid) {
    for (const m of collected.byFid[fid]) {
      if (m.kind === 'observation') matchedObservationIds.add(m.targetId)
    }
  }
  const uncoveredObservations = plan.mode === 'audit'
    ? plan.observations.slice()
    : plan.observations.filter((o) => !matchedObservationIds.has(o.id))

  return { direct, adjudicate, capped, orphans, uncoveredObservations }
}

/** matchPrompt(plan, shard) -> string; READ-ONLY; names query_items(get) and query_notes(list) */
function matchPrompt(plan, shard) {
  let out = 'READ-ONLY. You are matching findings from a retrospective run against a shard of ' + shard.kind + ' items. '
  out += 'Use query_items(operation="get", itemId=<id>) to read each target’s summary (look for lines such as "Sessions: N" and "Last seen:" to report trendState for trend targets), and query_notes(operation="list", itemId=<id>) to read its notes when useful. Do not write anything.\n\n'
  if (plan.mode === 'audit') {
    out += 'AUDIT MODE: fid is \'\' -- you are not matching against specific findings. Instead, report clusters of at least two targets in your shard that share one recurring pattern (label, claim, dimension, targetIds).\n\n'
  } else {
    out += 'FINDINGS:\n' + JSON.stringify(plan.findings) + '\n\n'
    out += 'DEEP MODE: besides matches, report clusters: 2 or more shard targets that share a pattern no finding covers (clusters may be an empty array).\n\n'
  }
  out += 'strength: "strong" means the target states the same recurring pattern in its own words; "weak" means only topical or keyword overlap. '
  out += 'sessionsSeen is the number from a "Sessions: N" line on a trend target, else 0. lastSeen is the date from a "Last seen:" line, formatted YYYY-MM-DD.\n\n'
  out += 'YOUR SHARD (' + shard.kind + '):\n' + JSON.stringify(shard.targets.map((t) => ({ id: t.id, short: t.short, title: t.title })))
  return out
}

/** adjudicatePrompt(plan, finding, candidates) -> string; READ-ONLY; names query_items(get) */
function adjudicatePrompt(plan, finding, candidates) {
  let out = 'READ-ONLY. You are adjudicating one retrospective finding with several candidate trend matches. '
  out += 'Use query_items(operation="get", itemId=<id>) to read each candidate trend before deciding. Do not write anything.\n\n'
  out += 'FINDING:\n' + JSON.stringify(finding) + '\n\n'
  out += 'CANDIDATE TRENDS: ' + candidates.map((c) => c.trendId).join(', ') + '\n\n'
  out += 'Decide whether this finding is a recurrence of exactly one candidate trend (decision="match", trendId = the canonical candidate) or a genuinely new pattern (decision="new", with a kebabKey and a claim). Give a short rationale.'
  return out
}

/** clusterPrompt(plan, {orphans, uncovered, clusters}) -> string; READ-ONLY; names DIMENSIONS */
function clusterPrompt(plan, ctx) {
  let out = 'READ-ONLY. You are clustering unmatched retrospective findings and uncovered observations into candidate new trends. Do not write anything.\n\n'
  out += 'DIMENSIONS: ' + DIMENSIONS.join(', ') + '\n\n'
  out += 'ORPHAN FINDINGS (no matching trend):\n' + JSON.stringify(ctx.orphans) + '\n\n'
  out += 'UNCOVERED OBSERVATIONS:\n' + JSON.stringify(ctx.uncovered) + '\n\n'
  out += 'SHARD-REPORTED CLUSTERS (from the Match phase, for reference):\n' + JSON.stringify(ctx.clusters) + '\n\n'
  out += 'Group these into newTrends (kebabKey, claim, dimension, fids, observationIds, evidenceRefs) and observationLinks (observationId, trendId, or \'\' when an observation does not belong to an existing trend).'
  return out
}

/** buildMatchedEntry(collected, fid, trendId, via) -> {fid, trendId, sessionsBefore, evidence, confidence, evidenceRefs, via} */
function buildMatchedEntry(collected, fid, trendId, via) {
  const all = collected.byFid[fid] || []
  const trendEntries = all.filter((m) => m.kind === 'trend' && m.targetId === trendId)
  const ts = collected.trendState[trendId]
  let sessionsBefore = null
  if (ts && ts.sessions != null) sessionsBefore = ts.sessions
  else if (trendEntries.length && trendEntries[0].sessionsSeen != null) sessionsBefore = trendEntries[0].sessionsSeen
  const strongEntry = trendEntries.find((m) => m.strength === 'strong')
  const evidence = strongEntry ? strongEntry.evidence : (trendEntries.length ? trendEntries[0].evidence : '')
  const confidence = trendEntries.some((m) => m.strength === 'strong') ? 'strong' : 'weak'
  const evidenceRefs = []
  const seenRef = new Set()
  for (const m of all) {
    if (m.kind === 'trend') continue
    if (!seenRef.has(m.targetId)) { seenRef.add(m.targetId); evidenceRefs.push(m.targetId) }
  }
  return { fid, trendId, sessionsBefore, evidence, confidence, evidenceRefs, via }
}

/**
 * assembleResult(plan, ctx{shards, collected, routed, adjResults, clusterResult, projected}) -> retro-analysis/result-v1
 */
function assembleResult(plan, ctx) {
  const { shards, collected, routed, adjResults, clusterResult, projected } = ctx
  const missing = []
  const fidOrder = plan.findings.map((f) => f.fid)

  const orphanFidSet = new Set(routed.orphans.map((f) => f.fid))
  const knownObservationIds = new Set(plan.observations.map((o) => o.id))
  const knownTargetIds = new Set(
    plan.trends.map((t) => t.id).concat(plan.observations.map((o) => o.id)).concat(plan.retros.map((r) => r.id))
  )

  const matched = []
  const matchedFidSet = new Set()
  function pushMatched(fid, trendId, via) {
    matched.push(buildMatchedEntry(collected, fid, trendId, via))
    matchedFidSet.add(fid)
  }

  for (const d of routed.direct) pushMatched(d.fid, d.trendId, 'match')
  for (const c of routed.capped) pushMatched(c.fid, c.trendId, 'cap')

  const rawNewTrends = []
  for (let i = 0; i < routed.adjudicate.length; i++) {
    const { finding, candidates } = routed.adjudicate[i]
    const result = adjResults[i]
    if (!result) {
      missing.push('adjudicate:' + finding.fid)
      pushMatched(finding.fid, pickCandidate(candidates), 'fallback')
      continue
    }
    if (result.decision === 'new') {
      const all = collected.byFid[finding.fid] || []
      rawNewTrends.push({
        kebabKey: kebab(result.kebabKey) || 'finding-' + finding.fid,
        claim: result.claim, dimension: finding.dimension, fids: [finding.fid],
        observationIds: all.filter((m) => m.kind === 'observation').map((m) => m.targetId),
        evidenceRefs: all.filter((m) => m.kind === 'retro').map((m) => m.targetId),
      })
      continue
    }
    const hit = candidates.find((c) => c.trendId === result.trendId)
    if (hit) pushMatched(finding.fid, hit.trendId, 'adjudicate')
    else pushMatched(finding.fid, pickCandidate(candidates), 'fallback')
  }

  let clusterNewTrends = []
  let observationLinksFromCluster = []
  if (!clusterResult) {
    missing.push('cluster')
  } else {
    clusterNewTrends = (clusterResult.newTrends || []).map((c, i) => ({
      kebabKey: kebab(c.kebabKey) || 'cluster-' + (i + 1),
      claim: c.claim, dimension: c.dimension,
      fids: (c.fids || []).filter((fid) => orphanFidSet.has(fid)),
      observationIds: (c.observationIds || []).filter((id) => knownObservationIds.has(id)),
      evidenceRefs: (c.evidenceRefs || []).filter((id) => knownTargetIds.has(id)),
    }))
    observationLinksFromCluster = clusterResult.observationLinks || []
  }

  const mergedOrder = []
  const mergedByKey = new Map()
  const unionInto = (dst, src) => { for (const v of src) if (dst.indexOf(v) === -1) dst.push(v) }
  for (const e of clusterNewTrends.concat(rawNewTrends)) {
    let m = mergedByKey.get(e.kebabKey)
    if (!m) {
      m = { kebabKey: e.kebabKey, claim: e.claim, dimension: e.dimension, fids: [], observationIds: [], evidenceRefs: [] }
      mergedByKey.set(e.kebabKey, m)
      mergedOrder.push(m)
    }
    unionInto(m.fids, e.fids)
    unionInto(m.observationIds, e.observationIds)
    unionInto(m.evidenceRefs, e.evidenceRefs)
  }

  const usedFids = new Set(matchedFidSet)
  for (const m of mergedOrder) {
    m.fids = m.fids.filter((fid) => !usedFids.has(fid))
    m.fids.forEach((fid) => usedFids.add(fid))
  }

  const trendKeyMap = new Map()
  for (const t of plan.trends) {
    const k = trendKey(t.title)
    if (k && !trendKeyMap.has(k)) trendKeyMap.set(k, t.id)
  }

  const newTrends = []
  const collisionObsLinks = new Map()
  for (const m of mergedOrder) {
    const collideTrendId = trendKeyMap.get(m.kebabKey)
    if (collideTrendId) {
      for (const oid of m.observationIds) if (!collisionObsLinks.has(oid)) collisionObsLinks.set(oid, collideTrendId)
      for (const fid of m.fids) {
        const ts = collected.trendState[collideTrendId]
        const refs = []
        for (const e of (collected.byFid[fid] || [])) {
          if (e.kind !== 'trend' && refs.indexOf(e.targetId) === -1) refs.push(e.targetId)
        }
        for (const r of m.evidenceRefs) if (refs.indexOf(r) === -1) refs.push(r)
        matched.push({
          fid, trendId: collideTrendId,
          sessionsBefore: ts && ts.sessions != null ? ts.sessions : null,
          evidence: '', confidence: 'weak', evidenceRefs: refs, via: 'key-collision',
        })
        matchedFidSet.add(fid)
      }
      continue
    }
    if (m.fids.length === 0 && m.observationIds.length === 0 && m.evidenceRefs.length === 0) continue
    newTrends.push(m)
  }

  matched.sort((a, b) => fidOrder.indexOf(a.fid) - fidOrder.indexOf(b.fid))

  const newTrendFidSet = new Set()
  for (const nt of newTrends) for (const fid of nt.fids) newTrendFidSet.add(fid)
  const unresolved = fidOrder.filter((fid) => !matchedFidSet.has(fid) && !newTrendFidSet.has(fid))

  const knownTrendIds = new Set(plan.trends.map((t) => t.id))
  const observationLinks = plan.observations.map((o) => {
    let trendId = null
    const m = matched.find((mm) => mm.evidenceRefs && mm.evidenceRefs.indexOf(o.id) !== -1)
    if (m) {
      trendId = m.trendId
    } else if (collisionObsLinks.has(o.id)) {
      trendId = collisionObsLinks.get(o.id)
    } else {
      const link = observationLinksFromCluster.find((l) => l.observationId === o.id && l.trendId && knownTrendIds.has(l.trendId))
      if (link) trendId = link.trendId
    }
    const nt = newTrends.find((n) => n.observationIds.indexOf(o.id) !== -1)
    return { observationId: o.id, trendId, newTrendKey: nt ? nt.kebabKey : null }
  })

  const matchedTrendIds = new Set(matched.map((m) => m.trendId))
  const staleTrends = plan.trends
    .filter((t) => {
      const ts = collected.trendState[t.id]
      if (!ts || matchedTrendIds.has(t.id)) return false
      const days = daysBetween(ts.lastSeen, plan.date)
      return days !== null && days > plan.staleDays
    })
    .map((t) => ({ trendId: t.id, lastSeen: collected.trendState[t.id].lastSeen }))

  return {
    contract: 'retro-analysis/result-v1', started: true, runId: plan.runId, mode: plan.mode,
    rootId: plan.rootId, planDocSlug: plan.planDocSlug, findings: plan.findings,
    matched, newTrends, observationLinks, staleTrends, unresolved,
    stats: {
      mode: plan.mode, projectedAgents: projected.total, shards: shards.length,
      matchReturned: collected.returned, unmatchedShards: collected.unmatchedShards,
      droppedMatches: collected.dropped, adjudicators: routed.adjudicate.length,
      adjudicationCapped: routed.capped.map((c) => c.fid), missing,
    },
  }
}

/**
 * runRetroAnalysis(plan, deps{agent, parallel, phase, log}) -> retro-analysis/result-v1
 * Match (barrier) -> collectMatches/routeFindings -> Adjudicate (adjudicators + cluster agent).
 */
async function runRetroAnalysis(plan, deps) {
  const { agent, parallel, phase, log } = deps
  const S = schemas(plan)
  const projected = projectAgents(plan)
  log('projected ' + projected.total + ' agents')

  phase('Match')
  const shards = makeShards(plan)
  const matchResults = await parallel(shards.map((shard) => () =>
    agent(matchPrompt(plan, shard), agentOpts(plan, 'match', { label: shard.label, phase: 'Match', schema: S.MATCH }))))

  const collected = collectMatches(plan, shards, matchResults)
  if (collected.unmatchedShards.length) {
    log('WARNING: no result from ' + collected.unmatchedShards.map((s) => s.label).join(', '))
  }

  const routed = routeFindings(plan, collected)
  if (routed.capped.length) {
    log('adjudication capped: ' + routed.capped.map((c) => c.fid).join(', '))
  }

  phase('Adjudicate')
  const tasks = routed.adjudicate.map((a) => () =>
    agent(adjudicatePrompt(plan, a.finding, a.candidates), agentOpts(plan, 'adjudicate', { label: 'adjudicate:' + a.finding.fid, phase: 'Adjudicate', schema: S.ADJUDICATE })))
  tasks.push(() =>
    agent(clusterPrompt(plan, { orphans: routed.orphans, uncovered: routed.uncoveredObservations, clusters: collected.clusters }),
      agentOpts(plan, 'cluster', { label: 'cluster', phase: 'Adjudicate', schema: S.CLUSTER })))
  const adjudicateResults = await parallel(tasks)
  const adjResults = adjudicateResults.slice(0, routed.adjudicate.length)
  const clusterResult = adjudicateResults[adjudicateResults.length - 1]

  return assembleResult(plan, { shards, collected, routed, adjResults, clusterResult, projected })
}

// @core-end

const R = normalizeArgs(args)
if (!R.ok) return { started: false, reason: R.reason }
const G = sizeGuard(R.plan)
if (G) return { started: false, reason: G.reason }
return await runRetroAnalysis(R.plan, { agent, parallel, phase, log })
