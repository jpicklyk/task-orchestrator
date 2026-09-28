export const meta = {
  name: 'review-wave',
  description: 'Runs independent, lane-derived review agents across a wave of MCP work items already in the review phase — validates lane coverage, aggregates verdicts, and guards reviewer independence.',
  whenToUse: 'Never invoke this workflow bare. It is called by the review step of the front door with review-wave/args-v1; a bare or malformed call returns {started:false}.',
  phases: [{ title: 'Review' }],
}
// @core-begin
const ENVELOPE_VERSION = 'envelope-v1'
const VERDICTS = ['pass', 'pass-with-observations', 'fail-blocking']
const INDEPENDENCE_MODES = ['independent', 'independent-degraded', 'not-independent', 'n/a']
const SIMPLIFY_ANGLES = ['reuse', 'simplification', 'efficiency', 'altitude']
const BRANCH_SCOPED_SKILLS = ['security-review']
const PROTOCOL_KEY = 'protocol.read-only-agent'
const SCOPING_RULE_KEY = 'review-scoping'
const REQUIRED_FEATURES = ['seats', 'dispatchBySeat', 'rules']

/** Output schema catalog, keyed by output-schema id (a lane's declared output contract). */
const REVIEW_OUTPUT_SCHEMAS = {
  'review-v1': {
    type: 'object',
    required: ['verdict', 'findings', 'independence', 'notesFilled'],
    properties: {
      verdict: { enum: VERDICTS },
      findings: {
        type: 'array',
        items: {
          type: 'object',
          required: ['severity', 'confidence', 'file', 'line', 'expected', 'found'],
          properties: {
            severity: { enum: ['blocking', 'observation'] },
            confidence: { enum: ['high', 'medium', 'low'] },
            file: { type: 'string' },
            line: { type: 'integer' },
            expected: { type: 'string' },
            found: { type: 'string' },
          },
        },
      },
      independence: {
        type: 'object',
        required: ['mode', 'violations', 'evidence'],
        properties: {
          mode: { enum: INDEPENDENCE_MODES },
          violations: {
            type: 'array',
            items: {
              type: 'object',
              required: ['key', 'constraint'],
              properties: {
                key: { type: 'string' }, seat: { type: 'string' },
                constraint: { type: 'string' }, conflictingSeat: { type: 'string' },
                waived: { type: 'boolean' },
              },
            },
          },
          evidence: { type: 'string' },
        },
      },
      notesFilled: { type: 'array', items: { type: 'string' } },
    },
  },
  'simplify-v1': {
    type: 'object',
    required: ['findings'],
    properties: {
      findings: {
        type: 'array',
        items: {
          type: 'object',
          required: ['file', 'line', 'angle', 'suggestion'],
          properties: {
            file: { type: 'string' }, line: { type: 'integer' },
            angle: { enum: SIMPLIFY_ANGLES }, suggestion: { type: 'string' },
          },
        },
      },
    },
  },
}

/** normalizePath(p) -> string. Turns `\` into `/`, strips a leading `./`, lowercases a drive letter. */
function normalizePath(p) {
  let s = String(p).replace(/\\/g, '/')
  if (s.slice(0, 2) === './') s = s.slice(2)
  const m = /^([a-zA-Z]):\//.exec(s)
  if (m) s = m[1].toLowerCase() + s.slice(1)
  return s
}

function isAbsolutePath(p) {
  return /^\//.test(p) || /^[a-z]:\//i.test(p)
}

/**
 * normalizeArgs(raw) -> {ok:true, plan} | {ok:false, reason}
 * JSON-parses a string, validates the review-wave/args-v1 schema, and applies
 * per-item/per-stage defaults (worktree normalization, optionalReviewNotes,
 * declaredExceptions, deferred).
 */
function normalizeArgs(raw) {
  let parsed = raw
  if (typeof raw === 'string') {
    try {
      parsed = JSON.parse(raw)
    } catch (e) {
      return { ok: false, reason: 'invalid args: bad JSON' }
    }
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { ok: false, reason: 'invalid args: not an object' }
  }

  const REQUIRED_TOP = ['contract', 'runId', 'rootId', 'baseSha', 'worktreeMode', 'entryMode', 'capabilities', 'items']
  for (const f of REQUIRED_TOP) {
    if (!(f in parsed)) return { ok: false, reason: `invalid args: missing ${f}` }
  }
  if (parsed.contract !== 'review-wave/args-v1') return { ok: false, reason: 'invalid args: bad contract' }
  if (typeof parsed.runId !== 'string' || !/^r-[A-Za-z0-9-]{4,40}$/.test(parsed.runId)) {
    return { ok: false, reason: 'invalid args: bad runId' }
  }
  if (typeof parsed.rootId !== 'string' || !parsed.rootId) return { ok: false, reason: 'invalid args: bad rootId' }
  if (typeof parsed.baseSha !== 'string' || !/^[0-9a-f]{7,40}$/.test(parsed.baseSha)) {
    return { ok: false, reason: 'invalid args: bad baseSha' }
  }
  if (parsed.worktreeMode !== 'shared' && parsed.worktreeMode !== 'per-item') {
    return { ok: false, reason: 'invalid args: bad worktreeMode' }
  }
  if (parsed.entryMode !== 'pre-entered') {
    return { ok: false, reason: 'invalid args: entryMode must be pre-entered' }
  }
  const caps = parsed.capabilities
  if (!caps || typeof caps !== 'object' || !Array.isArray(caps.features) || typeof caps.phase0Hooks !== 'boolean') {
    return { ok: false, reason: 'invalid args: bad capabilities' }
  }
  if (!Array.isArray(parsed.items) || parsed.items.length < 1) {
    return { ok: false, reason: 'invalid args: items must be a non-empty array' }
  }

  const ITEM_REQUIRED = ['id', 'short', 'type', 'configFingerprint', 'traits', 'worktree', 'stages', 'review']
  const REVIEW_REQUIRED = ['ownedFiles', 'commits', 'implementerActors', 'testAuthorActors', 'requiredReviewNotes']
  const STAGE_REQUIRED = ['seat', 'lane', 'phase', 'notes', 'writes', 'protocol', 'dispatch', 'output']
  const items = []
  for (const rawItem of parsed.items) {
    if (!rawItem || typeof rawItem !== 'object') return { ok: false, reason: 'invalid args: bad item' }
    for (const f of ITEM_REQUIRED) {
      if (!(f in rawItem)) return { ok: false, reason: `invalid args: item missing ${f}` }
    }
    if (typeof rawItem.short !== 'string' || !/^[0-9a-f]{8}$/.test(rawItem.short)) {
      return { ok: false, reason: 'invalid args: bad short' }
    }
    if (!Array.isArray(rawItem.traits)) return { ok: false, reason: 'invalid args: bad traits' }
    if ('itemTraits' in rawItem && rawItem.itemTraits !== undefined && !Array.isArray(rawItem.itemTraits)) {
      return { ok: false, reason: 'invalid args: bad itemTraits' }
    }
    if (!Array.isArray(rawItem.stages) || rawItem.stages.length < 1) {
      return { ok: false, reason: 'invalid args: item stages must be a non-empty array' }
    }
    const rawReview = rawItem.review
    if (!rawReview || typeof rawReview !== 'object') {
      return { ok: false, reason: `invalid args: review missing ${REVIEW_REQUIRED[0]}` }
    }
    for (const f of REVIEW_REQUIRED) {
      if (!(f in rawReview)) return { ok: false, reason: `invalid args: review missing ${f}` }
    }
    for (const st of rawItem.stages) {
      if (!st || typeof st !== 'object') return { ok: false, reason: 'invalid args: bad stage' }
      for (const f of STAGE_REQUIRED) {
        if (!(f in st)) return { ok: false, reason: `invalid args: stage missing ${f}` }
      }
      if (st.phase !== 'review') return { ok: false, reason: 'invalid args: bad stage phase' }
      if (st.writes !== false) return { ok: false, reason: 'invalid args: stage writes must be false' }
      if (st.protocol !== PROTOCOL_KEY) return { ok: false, reason: 'invalid args: bad stage protocol' }
      if (st.output !== 'review-v1' && st.output !== 'simplify-v1') {
        return { ok: false, reason: 'invalid args: bad stage output' }
      }
    }
    items.push(
      Object.assign({}, rawItem, {
        worktree: normalizePath(rawItem.worktree),
        review: Object.assign({}, rawReview, {
          optionalReviewNotes: Array.isArray(rawReview.optionalReviewNotes) ? rawReview.optionalReviewNotes : [],
          declaredExceptions: typeof rawReview.declaredExceptions === 'string' ? rawReview.declaredExceptions : '',
        }),
      })
    )
  }

  for (const f of REQUIRED_FEATURES) {
    if (!caps.features.includes(f)) {
      return { ok: false, reason: `server lacks ${f}; front door must use its fallback` }
    }
  }

  const plan = Object.assign({}, parsed, {
    items,
    deferred: Array.isArray(parsed.deferred) ? parsed.deferred : [],
  })
  return { ok: true, plan }
}

/** laneActor(stage, item, plan) -> {id, kind:'subagent', parent:'workflow:'+runId} */
function laneActor(stage, item, plan) {
  return { id: `${stage.lane}:${item.short}:${plan.runId}`, kind: 'subagent', parent: `workflow:${plan.runId}` }
}

function preflightItem(item, plan) {
  if (!isAbsolutePath(item.worktree)) return 'worktree not absolute'
  if (Array.isArray(item.waitsFor) && item.waitsFor.length > 0) return 'waitsFor not allowed in review-wave'
  for (const st of item.stages) {
    if (Array.isArray(st.extraLockKeys) && st.extraLockKeys.length > 0) {
      return 'extraLockKeys not allowed in review-wave'
    }
    if (Array.isArray(st.readsExclude) && st.readsExclude.length > 0) {
      return 'readsExclude not allowed in review-wave'
    }
  }
  const lanesSeen = new Set()
  for (const st of item.stages) {
    if (lanesSeen.has(st.lane)) return `duplicate lane ${st.lane}`
    lanesSeen.add(st.lane)
  }
  const reviewLanes = item.stages.filter((s) => s.output === 'review-v1')
  if (reviewLanes.length === 0) return 'no review lanes'
  const simplifyLaneIds = new Set(item.stages.filter((s) => s.output === 'simplify-v1').map((s) => s.lane))
  for (const st of item.stages) {
    for (const a of st.after || []) {
      if (!simplifyLaneIds.has(a)) return `lane ${st.lane} after unknown lane ${a}`
    }
  }
  for (const st of item.stages) {
    if (st.output === 'simplify-v1' && Array.isArray(st.notes) && st.notes.length > 0) {
      return `simplify lane ${st.lane} owns notes`
    }
  }
  for (const st of reviewLanes) {
    if (!st.notes || st.notes.length === 0) return `lane ${st.lane} owns no notes`
  }
  const allowed = new Set((item.review.requiredReviewNotes || []).concat(item.review.optionalReviewNotes || []))
  const owner = new Map()
  for (const st of reviewLanes) {
    for (const key of st.notes) {
      if (!allowed.has(key)) return `lane ${st.lane} note ${key} is not a review note`
      if (owner.has(key)) return `note ${key} in multiple lanes`
      owner.set(key, st.lane)
    }
  }
  const uncovered = (item.review.requiredReviewNotes || []).filter((k) => !owner.has(k))
  if (uncovered.length > 0) return `uncovered required review note(s) ${uncovered.join(', ')}`
  for (const st of item.stages) {
    const actor = laneActor(st, item, plan)
    if ((item.review.implementerActors || []).includes(actor.id)) return 'reviewer would match implementer actor'
  }
  for (const st of item.stages) {
    const actor = laneActor(st, item, plan)
    if ((item.review.testAuthorActors || []).includes(actor.id)) return 'reviewer would match test-author actor'
  }
  return null
}

/**
 * preflight(plan) -> {runnable:item[], refused:[{id, reason}]}
 * Defensive per-item checks: absolute worktree, no waitsFor/extraLockKeys/readsExclude,
 * lane uniqueness, lane coverage of required review notes, and reviewer-independence
 * of every lane actor against the implementer/test-author actor lists.
 */
function preflight(plan) {
  const runnable = []
  const refused = []
  for (const item of plan.items) {
    const reason = preflightItem(item, plan)
    if (reason) refused.push({ id: item.id, reason })
    else runnable.push(item)
  }
  return { runnable, refused }
}

/** laneSchema(outputId) -> JSON schema for the common envelope, entry limited to {alreadyInPhase, previousRole}. */
function laneSchema(outputId) {
  return {
    type: 'object',
    required: ['status', 'reason', 'notes', 'commits', 'files', 'modelReported', 'output'],
    properties: {
      status: { enum: ['done', 'stopped', 'deferred'] },
      reason: { type: 'string' },
      entry: {
        type: 'object',
        properties: { alreadyInPhase: { type: 'boolean' }, previousRole: { type: 'string' } },
      },
      notes: {
        type: 'array',
        items: {
          type: 'object',
          required: ['key', 'actor'],
          properties: {
            key: { type: 'string' }, actor: { type: 'string' },
            chars: { type: 'integer' }, warning: { type: 'string' },
          },
        },
      },
      commits: {
        type: 'object', required: ['pre', 'post'],
        properties: { pre: { type: 'string' }, post: { type: 'string' } },
      },
      files: { type: 'array', items: { type: 'string' } },
      rulesFetched: {
        type: 'array',
        items: {
          type: 'object', required: ['key', 'rulesVersion'],
          properties: { key: { type: 'string' }, rulesVersion: { type: 'string' } },
        },
      },
      modelReported: { type: 'string' },
      output: REVIEW_OUTPUT_SCHEMAS[outputId],
    },
  }
}

/** Part 1: seat line. */
function promptSeatLine(plan, item, stage) {
  const title = item.title || item.short
  return `SEAT: ${stage.seat} for item ${item.id} (${item.short}) "${title}". Lane ${stage.lane}. Run ${plan.runId}.`
}

/** Part 2: worktree scope + shell discipline + read-only reminder. */
function promptScope(plan, item) {
  const proj = plan.project || {}
  const lines = [`WORKTREE: ${item.worktree}`]
  if (proj.searchScope) lines.push(`SEARCH SCOPE: ${proj.searchScope}`)
  if (proj.scratchDir) lines.push(`SCRATCHPAD: ${proj.scratchDir}`)
  if (proj.shell) lines.push(`SHELL: ${proj.shell}`)
  lines.push('Never prefix a command with cd — use absolute paths or git -C <worktree>.')
  lines.push('Never run a bare find / or other unscoped filesystem walk.')
  lines.push('Leave no background commands running when you return.')
  lines.push('READ-ONLY lane: edit no file, commit nothing.')
  return lines.join('\n')
}

/** Part 3: tool selection. manage_notes only for a note-owning lane; advance_item/manage_items never named. */
function promptTools(stage) {
  const names = [
    'mcp__mcp-task-orchestrator__query_items',
    'mcp__mcp-task-orchestrator__query_notes',
    'mcp__mcp-task-orchestrator__get_context',
    'mcp__mcp-task-orchestrator__query_rules',
  ]
  if (stage.notes && stage.notes.length > 0) names.push('mcp__mcp-task-orchestrator__manage_notes')
  return `TOOLS: load with ToolSearch select:${names.join(',')}`
}

/** Part 4: actor. */
function promptActor(stage, item, plan) {
  const actor = laneActor(stage, item, plan)
  return `ACTOR: ${JSON.stringify(actor)} — place this actor inside every notes[] element you write.`
}

/** Part 5: owned notes / sibling notes. */
function promptOwnedNotes(item, stage) {
  const owned = (stage.notes || []).join(', ') || 'none'
  const sibling = []
  for (const st of item.stages) {
    if (st.lane === stage.lane) continue
    for (const k of st.notes || []) sibling.push(k)
  }
  const siblingStr = sibling.join(', ') || 'none'
  return (
    `NOTES: this lane owns [${owned}]. Sibling lanes own [${siblingStr}] — not yours.\n` +
    'Always pass keys= on every query_notes call.'
  )
}

/** Part 6: rule fetch by key. */
function promptRules(plan, stage) {
  const keys = [PROTOCOL_KEY, SCOPING_RULE_KEY]
  for (const k of stage.rules || []) {
    if (!keys.includes(k)) keys.push(k)
  }
  const skillKeys = Array.isArray(stage.skills) ? stage.skills : []
  const lines = [
    `RULES: fetch each key below via query_rules(operation:"get", rootId:"${plan.rootId}", key:<key>) and follow the returned body over anything paraphrased here.`,
    `Keys: ${keys.join(', ')}.`,
    'RESOURCE_NOT_FOUND for a key falls back to this stage\'s skills entry of the same name' +
      (skillKeys.length ? ` (${skillKeys.join(', ')})` : '') +
      ', then the note guidance from query_items(operation:"schema", itemId).',
    `A missing PROTOCOL key (${PROTOCOL_KEY}) is fatal: status stopped, reason "rule ${PROTOCOL_KEY} unavailable".`,
    'Record every key you fetched, with its rulesVersion, under the envelope\'s rulesFetched.',
  ]
  return lines.join('\n')
}

/** Part 7: drift pin, omitted for schema-free items and lanes with no owned notes. */
function promptDriftPin(item, stage) {
  if (item.schemaFree || !stage.notes || stage.notes.length === 0) return ''
  const lines = [
    `DRIFT PIN: before your first write, call query_items(operation:"schema", itemId:"${item.id}").`,
    `Its configFingerprint must equal ${item.configFingerprint}.`,
  ]
  if (item.itemTraits !== undefined) {
    lines.push(`get(itemId) -> properties.traits must equal ${JSON.stringify(item.itemTraits)}.`)
  } else {
    lines.push(`For reference, traits: ${JSON.stringify(item.traits || [])}.`)
  }
  lines.push('A mismatch means write nothing and return status "stopped", reason "schema-changed".')
  return lines.join('\n')
}

/** Part 8: config-unavailable retry, constant. */
function promptConfigRetry() {
  return (
    'CONFIG RETRY: on a config_unavailable failure, retry once after one intervening read call ' +
    '— no sleep. A second failure returns status "deferred", reason "config-unavailable".'
  )
}

/** Part 9: inline note bodies, only for a note-owning lane. */
function promptInlineBodies(stage) {
  if (!stage.notes || stage.notes.length === 0) return ''
  return (
    'NOTE BODIES: pass body inline on every manage_notes upsert. Never use bodyFromFile. ' +
    're-trim and re-upsert if any upsert response carries a warning.'
  )
}

/** Part 10: content diff per owned-file group, plus boundary checks and declared exceptions for review-v1 lanes. */
function promptDiff(plan, item, stage) {
  const owned = item.review.ownedFiles || {}
  const lines = []
  for (const group of ['implementer', 'testAuthor', 'docs']) {
    const files = owned[group] || []
    if (files.length === 0) continue
    lines.push(`DIFF ${group}: git -C ${item.worktree} diff ${plan.baseSha}..HEAD -- ${files.join(' ')}`)
  }
  if (stage.output === 'review-v1') {
    const commits = item.review.commits || {}
    for (const group of ['implementer', 'testAuthor', 'fixtureRepairs']) {
      for (const sha of commits[group] || []) {
        lines.push(`BOUNDARY: git -C ${item.worktree} show --stat ${sha}`)
      }
    }
    if (item.review.declaredExceptions) {
      lines.push(`DECLARED EXCEPTIONS: ${item.review.declaredExceptions}`)
    }
  }
  return lines.join('\n')
}

/** Part 11: independence context, review-v1 lanes only. */
function promptIndependence(item, stage) {
  if (stage.output !== 'review-v1') return ''
  return (
    `INDEPENDENCE: call get_context(itemId:"${item.id}") and read gateStatus.violations. ` +
    `implementerActors: ${JSON.stringify(item.review.implementerActors || [])}. ` +
    `testAuthorActors: ${JSON.stringify(item.review.testAuthorActors || [])}. ` +
    'Record independence.mode; violations carry seat names only.'
  )
}

/** Part 12: lane mode — simplify angles, branch-scoped skill filter, and hand-off from a completed simplify lane. */
function promptLaneMode(stage, outs) {
  const lines = []
  if (stage.output === 'simplify-v1') {
    lines.push(
      'SIMPLIFY LANE: apply the reuse, simplification, efficiency and altitude angles to the ' +
        'owned-file diff. Do not invoke /simplify (it edits files). Return simplify-v1 findings only.'
    )
  }
  const skillNames = Array.isArray(stage.skills) ? stage.skills : []
  const ruleNames = Array.isArray(stage.rules) ? stage.rules : []
  const branchScopedName =
    skillNames.find((s) => BRANCH_SCOPED_SKILLS.includes(s)) || ruleNames.find((r) => BRANCH_SCOPED_SKILLS.includes(r))
  if (stage.branchScoped === true || branchScopedName) {
    const name = branchScopedName || BRANCH_SCOPED_SKILLS[0]
    lines.push(
      `BRANCH-SCOPED SKILL ${name}: it reviews the whole branch diff; keep only findings whose file ` +
        'is in this item\'s owned files, and record the filter in your note (findings total, kept, dropped, dropped files).'
    )
  }
  if (Array.isArray(stage.after) && stage.after.length > 0) {
    const dep = outs && outs[stage.after[0]]
    let body = 'simplify lane produced nothing'
    if (dep && dep.status === 'done' && dep.output && Array.isArray(dep.output.findings)) {
      body = JSON.stringify(dep.output.findings)
    }
    lines.push(`SIMPLIFY LANE FINDINGS — not yours to fix; judge coverage only:\n${body}`)
  }
  return lines.join('\n\n')
}

/** Part 13: entry verification and, for review-v1 lanes, the verdict contract. */
function promptEntryVerdict(stage) {
  const lines = [
    'ENTRY: call get_context(itemId) first. Role must be "review": report entry.alreadyInPhase true. ' +
      'Otherwise write nothing and return status "stopped", reason "not in review". Never call advance_item.',
  ]
  if (stage.output === 'review-v1') {
    lines.push(
      `VERDICT: set output.verdict to one of ${JSON.stringify(VERDICTS)}. ` +
        "independence.mode === 'not-independent' forces verdict fail-blocking."
    )
  }
  return lines.join('\n')
}

/** Part 14: return contract. */
function promptReturn(stage) {
  return `RETURN: return the structured envelope (${stage.output}); notes are the report.`
}

/**
 * lanePrompt(plan, item, stage, outs) -> string
 * Joins the 14 non-empty prompt parts with "\n\n". `outs` is the item's in-flight lane
 * results keyed by lane id, consulted only for the hand-off in part 12.
 */
function lanePrompt(plan, item, stage, outs) {
  const parts = [
    promptSeatLine(plan, item, stage),
    promptScope(plan, item),
    promptTools(stage),
    promptActor(stage, item, plan),
    promptOwnedNotes(item, stage),
    promptRules(plan, stage),
    promptDriftPin(item, stage),
    promptConfigRetry(),
    promptInlineBodies(stage),
    promptDiff(plan, item, stage),
    promptIndependence(item, stage),
    promptLaneMode(stage, outs),
    promptEntryVerdict(stage),
    promptReturn(stage),
  ]
  return parts.filter((p) => p && p.length > 0).join('\n\n')
}

/**
 * mapLaneResult(stage, env) -> {status, reason, verdict}
 * Precedence: null envelope; schema-changed; config-unavailable; missing/false entry;
 * env.status stopped|deferred; simplify-v1 (never a verdict); review-v1 with a missing
 * required notesFilled key; else done, verdict forced fail-blocking on not-independent.
 */
function mapLaneResult(stage, env) {
  if (env === null || env === undefined) return { status: 'stopped', reason: 'agent returned null', verdict: null }
  if (env.reason === 'schema-changed') return { status: 'stopped', reason: 'schema-changed', verdict: null }
  if (env.reason === 'config-unavailable') return { status: 'deferred', reason: 'config-unavailable', verdict: null }
  if (!env.entry || env.entry.alreadyInPhase !== true) {
    return { status: 'stopped', reason: 'not in review', verdict: null }
  }
  if (env.status === 'stopped' || env.status === 'deferred') {
    return { status: env.status, reason: env.reason, verdict: null }
  }
  if (stage.output === 'simplify-v1') {
    return { status: 'done', reason: env.reason, verdict: null }
  }
  const notesFilled = (env.output && env.output.notesFilled) || []
  const missingKey = (stage.notes || []).find((k) => !notesFilled.includes(k))
  if (missingKey !== undefined) {
    return { status: 'stopped', reason: `note ${missingKey} missing`, verdict: null }
  }
  const mode = env.output && env.output.independence && env.output.independence.mode
  const verdict = mode === 'not-independent' ? 'fail-blocking' : env.output && env.output.verdict
  return { status: 'done', reason: env.reason, verdict }
}

/**
 * aggregateVerdict(laneResults) -> {status, verdict, reason}
 * laneResults in args order, each {lane, output, status, reason, verdict}. Only
 * output:'review-v1' lanes are considered; 'skipped' lanes are ignored.
 */
function aggregateVerdict(laneResults) {
  const considered = laneResults.filter((r) => r.output === 'review-v1' && r.status !== 'skipped')
  const failLanes = considered.filter((r) => r.verdict === 'fail-blocking').map((r) => r.lane)
  if (failLanes.length > 0) {
    return { status: 'stopped', verdict: 'fail-blocking', reason: `review-fail ${failLanes.join(',')}` }
  }
  const bad = considered.find((r) => r.status === 'stopped' || r.status === 'deferred')
  if (bad) {
    const laneReason = bad.reason
    const verbatim =
      laneReason === 'schema-changed' || laneReason === 'config-unavailable' ||
      (typeof laneReason === 'string' && laneReason.indexOf('note ') === 0)
    const reason = verbatim ? laneReason : `${bad.lane} ${laneReason}`
    return { status: bad.status, verdict: null, reason }
  }
  if (considered.length === 0) return { status: 'stopped', verdict: null, reason: 'no review lanes' }
  if (considered.some((r) => r.verdict === 'pass-with-observations')) {
    return { status: 'done', verdict: 'pass-with-observations', reason: 'review passed with observations' }
  }
  return { status: 'done', verdict: 'pass', reason: 'review passed' }
}

function laneAgentOpts(stage, item, plan) {
  const phase = 'Review' // locally bound so this key is not a free runtime-global reference
  const opts = { label: `${stage.lane}:${item.short}`, phase, schema: laneSchema(stage.output) }
  if (stage.dispatch) {
    if (stage.dispatch.model) opts.model = stage.dispatch.model
    if (stage.dispatch.effort) opts.effort = stage.dispatch.effort
    if (stage.dispatch.agent) opts.agentType = stage.dispatch.agent
  }
  return opts
}

function emptyLaneResult(stage, status, reason, agentTypeFallback) {
  return {
    lane: stage.lane, output: stage.output, status, reason, verdict: null,
    notes: [], findingsCount: 0, findings: [], modelReported: '', agentTypeFallback: !!agentTypeFallback,
    rawOutput: null,
  }
}

async function callLane(plan, item, stage, outs, deps) {
  const prompt = lanePrompt(plan, item, stage, outs)
  const opts = laneAgentOpts(stage, item, plan)
  let env
  let agentTypeFallback = false
  try {
    env = await deps.agent(prompt, opts)
  } catch (e) {
    if (opts.agentType) {
      const retryOpts = Object.assign({}, opts)
      delete retryOpts.agentType
      try {
        env = await deps.agent(prompt, retryOpts)
        agentTypeFallback = true
      } catch (e2) {
        return emptyLaneResult(stage, 'stopped', `agent threw: ${e2 && e2.message}`, true)
      }
    } else {
      return emptyLaneResult(stage, 'stopped', `agent threw: ${e && e.message}`, false)
    }
  }
  const mapped = mapLaneResult(stage, env)
  const findings = (env && env.output && env.output.findings) || []
  return {
    lane: stage.lane, output: stage.output, status: mapped.status, reason: mapped.reason,
    verdict: mapped.verdict, notes: (env && env.notes) || [], findingsCount: findings.length,
    findings, modelReported: (env && env.modelReported) || '', agentTypeFallback,
    rawOutput: (env && env.output) || null,
  }
}

function haltingLane(laneResults) {
  for (const r of laneResults) {
    if (r.output === 'review-v1' && r.status !== 'skipped' && (r.verdict === 'fail-blocking' || r.status !== 'done')) {
      return r.lane
    }
  }
  return null
}

/**
 * runItem(plan, item, deps) -> {id, short, status, verdict, reason, lanes, observations}
 * deps = {agent, log}. Lanes with no `after` start concurrently; a lane with `after`
 * awaits those lanes' settlement, then either runs or (if the item has already halted —
 * any settled review-v1 lane is fail-blocking or non-done) is marked skipped without
 * calling agent(). `lanes` and `observations` are ordered by the item's own stages.
 */
async function runItem(plan, item, deps) {
  const stages = item.stages
  const outs = {}
  const laneResults = []
  const settled = {}

  function record(stage, result) {
    outs[stage.lane] = { status: result.status, output: result.rawOutput }
    laneResults.push(result)
    return result
  }

  const noAfter = stages.filter((s) => !s.after || s.after.length === 0)
  const withAfter = stages.filter((s) => s.after && s.after.length > 0)

  for (const st of noAfter) {
    settled[st.lane] = callLane(plan, item, st, outs, deps).then((r) => record(st, r))
  }
  await Promise.all(noAfter.map((st) => settled[st.lane]))

  for (const st of withAfter) {
    settled[st.lane] = Promise.all(st.after.map((a) => settled[a]).filter(Boolean)).then(async () => {
      const haltLane = haltingLane(laneResults)
      if (haltLane) {
        return record(st, emptyLaneResult(st, 'skipped', `skipped after ${haltLane}`, false))
      }
      const r = await callLane(plan, item, st, outs, deps)
      return record(st, r)
    })
  }
  await Promise.all(withAfter.map((st) => settled[st.lane]))

  const agg = aggregateVerdict(laneResults)
  const observations = []
  for (const st of stages) {
    const r = laneResults.find((x) => x.lane === st.lane)
    if (!r || r.output !== 'review-v1') continue
    for (const f of r.findings || []) {
      if (f.severity === 'observation') {
        observations.push({
          lane: r.lane, severity: f.severity, confidence: f.confidence,
          file: f.file, line: f.line, expected: f.expected, found: f.found,
        })
      }
    }
  }

  return {
    id: item.id, short: item.short, status: agg.status, verdict: agg.verdict, reason: agg.reason,
    lanes: stages.map((st) => {
      const r = laneResults.find((x) => x.lane === st.lane)
      return {
        lane: r.lane, status: r.status, verdict: r.verdict, reason: r.reason, notes: r.notes,
        findingsCount: r.findingsCount, findings: r.findings, modelReported: r.modelReported,
        agentTypeFallback: r.agentTypeFallback,
      }
    }),
    observations,
  }
}

/**
 * runReview(plan, deps) -> {contract:'review-wave/result-v1', started:true, runId, planDocSlug, items, refused, deferred}
 * deps = {agent, parallel, log, phase?}. Runs preflight, then ONE parallel() over one
 * runItem thunk per runnable item — no cross-item edges, locks, or milestones, and
 * deps.phase is never called (review-wave has a single phase, 'Review').
 */
async function runReview(plan, deps) {
  // Locally bound so the object-literal keys below are not free runtime-global references.
  const agent = deps.agent
  const log = deps.log
  const pf = preflight(plan)
  const thunks = pf.runnable.map((item) => () => runItem(plan, item, { agent, log }))
  const items = await deps.parallel(thunks)
  return {
    contract: 'review-wave/result-v1', started: true, runId: plan.runId, planDocSlug: plan.planDocSlug,
    items, refused: pf.refused, deferred: plan.deferred || [],
  }
}
// @core-end
const R = normalizeArgs(args)
if (!R.ok) return { started: false, reason: R.reason }
return await runReview(R.plan, { agent, parallel, phase, log })
