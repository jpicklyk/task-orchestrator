export const meta = {
  name: 'implement-wave',
  description: 'Schedules queue/work seats across a wave of MCP work items — milestones, per-file locks, entry mapping, and rerun-safe replay.',
  whenToUse: 'Never invoke this workflow bare. It is called by the implement-wave front door, which builds its args plan (items, stages, dispatch); a bare or malformed call returns {started:false}.',
  phases: [{ title: 'Queue' }, { title: 'Work' }],
}
// @core-begin
const ENVELOPE_VERSION = 'envelope-v1'

/** Output schema catalog, keyed by output-schema id (versioned seat contracts, never item types). */
const OUTPUT_SCHEMAS = {
  'planner-v1': {
    type: 'object',
    required: [
      'proceed', 'blockReason', 'diagnosisCorrections', 'defectClassSiblings', 'decisions',
      'missingApiOrSeam', 'testPlanStatus', 'mainFiles', 'docFiles', 'testFiles',
      'existingTestEdits', 'redProofShape',
    ],
    properties: {
      proceed: { type: 'boolean' },
      blockReason: { type: 'string' },
      diagnosisCorrections: { type: 'string' },
      defectClassSiblings: { type: 'string' },
      decisions: { type: 'string' },
      missingApiOrSeam: { type: 'string' },
      testPlanStatus: { type: 'string' },
      mainFiles: { type: 'array', items: { type: 'string' } },
      docFiles: { type: 'array', items: { type: 'string' } },
      testFiles: { type: 'array', items: { type: 'string' } },
      existingTestEdits: {
        type: 'array',
        items: {
          type: 'object', required: ['file', 'edit'],
          properties: { file: { type: 'string' }, edit: { type: 'string' } },
        },
      },
      redProofShape: { type: 'string' },
    },
  },
  'implementer-v1': {
    type: 'object',
    required: [
      'mainFilesChanged', 'docFilesChanged', 'verify', 'failingExistingTests',
      'preExistingFailures', 'publicSurface', 'deviations',
    ],
    properties: {
      mainFilesChanged: { type: 'array', items: { type: 'string' } },
      docFilesChanged: { type: 'array', items: { type: 'string' } },
      verify: {
        type: 'array',
        items: {
          type: 'object', required: ['name', 'exit', 'summary'],
          properties: { name: { type: 'string' }, exit: { type: 'integer' }, summary: { type: 'string' } },
        },
      },
      failingExistingTests: {
        type: 'array',
        items: {
          type: 'object', required: ['test', 'reason'],
          properties: { test: { type: 'string' }, reason: { type: 'string' } },
        },
      },
      preExistingFailures: { type: 'array', items: { type: 'string' } },
      publicSurface: { type: 'string' },
      deviations: { type: 'string' },
    },
  },
  'declarations-v1': {
    type: 'object',
    required: ['declarations', 'harnessPointers', 'gaps'],
    properties: {
      declarations: { type: 'string' },
      harnessPointers: { type: 'string' },
      gaps: { type: 'string' },
    },
  },
  'test-author-v1': {
    type: 'object',
    required: [
      'returnLine', 'testFiles', 'scenariosCovered', 'verify', 'redAuthorTests',
      'missingDeclaration', 'breachDisclosure',
    ],
    properties: {
      returnLine: { type: 'string' },
      testFiles: { type: 'array', items: { type: 'string' } },
      scenariosCovered: { type: 'string' },
      verify: {
        type: 'array',
        items: {
          type: 'object', required: ['name', 'exit', 'summary'],
          properties: { name: { type: 'string' }, exit: { type: 'integer' }, summary: { type: 'string' } },
        },
      },
      redAuthorTests: {
        type: 'array',
        items: {
          type: 'object', required: ['test', 'scenario', 'asserted', 'observed'],
          properties: {
            test: { type: 'string' }, scenario: { type: 'string' },
            asserted: { type: 'string' }, observed: { type: 'string' },
          },
        },
      },
      missingDeclaration: { type: 'string' },
      breachDisclosure: { type: 'string' },
    },
  },
  'generic-v1': {
    type: 'object',
    required: ['summary'],
    properties: { summary: { type: 'string' } },
  },
}

/**
 * normalizePath(p) -> string
 * Turns `\` into `/`, strips a leading `./`, lowercases a drive letter.
 */
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
 * JSON-parses a string, validates the args-v1 schema plus itemTraits, and applies
 * the run-level guards. Reasons for schema/bare/bad-JSON failures start 'invalid args'.
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
  if (parsed.contract !== 'implement-wave/args-v1') return { ok: false, reason: 'invalid args: bad contract' }
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
  if (parsed.entryMode !== 'seat' && parsed.entryMode !== 'pre-entered') {
    return { ok: false, reason: 'invalid args: bad entryMode' }
  }
  const caps = parsed.capabilities
  if (!caps || typeof caps !== 'object' || !Array.isArray(caps.features) || typeof caps.phase0Hooks !== 'boolean') {
    return { ok: false, reason: 'invalid args: bad capabilities' }
  }
  if (!Array.isArray(parsed.items) || parsed.items.length < 1) {
    return { ok: false, reason: 'invalid args: items must be a non-empty array' }
  }

  const ITEM_REQUIRED = ['id', 'short', 'type', 'configFingerprint', 'traits', 'worktree', 'stages']
  const STAGE_REQUIRED = ['seat', 'phase', 'notes', 'writes', 'dispatch', 'output']
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
    for (const st of rawItem.stages) {
      if (!st || typeof st !== 'object') return { ok: false, reason: 'invalid args: bad stage' }
      for (const f of STAGE_REQUIRED) {
        if (!(f in st)) return { ok: false, reason: `invalid args: stage missing ${f}` }
      }
      if (st.phase !== 'queue' && st.phase !== 'work') return { ok: false, reason: 'invalid args: bad stage phase' }
    }
    items.push(
      Object.assign({}, rawItem, {
        worktree: normalizePath(rawItem.worktree),
        waitsFor: Array.isArray(rawItem.waitsFor) ? rawItem.waitsFor : [],
      })
    )
  }

  const FEATURE_ORDER = ['seats', 'dispatchBySeat', 'rules']
  for (const f of FEATURE_ORDER) {
    if (!caps.features.includes(f)) {
      return { ok: false, reason: `server lacks ${f}; front door must use its fallback` }
    }
  }
  if (parsed.entryMode === 'seat' && caps.phase0Hooks === false) {
    return { ok: false, reason: 'seat entry requires phase0Hooks' }
  }
  if (parsed.entryMode === 'pre-entered' && items.some((it) => it.waitsFor.length > 0)) {
    return { ok: false, reason: 'pre-entered mode forbids waitsFor' }
  }
  if (parsed.worktreeMode === 'per-item' && items.some((it) => it.waitsFor.length > 0)) {
    return { ok: false, reason: 'per-item mode forbids waitsFor' }
  }

  const plan = Object.assign({}, parsed, {
    items,
    deferred: Array.isArray(parsed.deferred) ? parsed.deferred : [],
    outputSchemas: parsed.outputSchemas && typeof parsed.outputSchemas === 'object' ? parsed.outputSchemas : {},
  })
  return { ok: true, plan }
}

function detectWaitsForCycle(items) {
  const graph = new Map(items.map((it) => [it.id, (it.waitsFor || []).map((w) => w.item)]))
  const state = new Map()
  const inCycle = new Set()
  function visit(id, stack) {
    if (stack.has(id)) {
      for (const s of stack) inCycle.add(s)
      inCycle.add(id)
      return
    }
    if (state.get(id) === 'done') return
    stack.add(id)
    state.set(id, 'visiting')
    for (const next of graph.get(id) || []) {
      if (graph.has(next)) visit(next, stack)
    }
    stack.delete(id)
    state.set(id, 'done')
  }
  for (const id of graph.keys()) visit(id, new Set())
  return inCycle
}

function preflightItem(item, byId) {
  if (Array.isArray(item.unownedRequired) && item.unownedRequired.length > 0) {
    return `unowned required note(s) ${item.unownedRequired.join(', ')}`
  }
  if (!isAbsolutePath(item.worktree)) return 'worktree not absolute'
  const seatsSeen = new Set()
  const entersByPhase = {}
  for (const st of item.stages) {
    if (seatsSeen.has(st.seat)) return `duplicate seat ${st.seat}`
    seatsSeen.add(st.seat)
    if (st.enters) {
      entersByPhase[st.phase] = (entersByPhase[st.phase] || 0) + 1
      if (entersByPhase[st.phase] > 1) return `multiple entry stages in phase ${st.phase}`
    }
  }
  for (const w of item.waitsFor || []) {
    const target = byId.get(w.item)
    if (!target) return `waitsFor unknown item ${w.item}`
    if (!target.stages.some((s) => s.seat === w.milestone)) return `waitsFor unknown seat ${w.milestone}`
  }
  return null
}

/**
 * preflight(plan) -> {runnable:item[], refused:[{id, reason}]}
 * Defensive per-item checks (§3.4): unowned notes, duplicate seats, multiple
 * entries per phase, unknown/cyclic waitsFor, non-absolute worktree.
 */
function preflight(plan) {
  const runnable = []
  const refused = []
  const byId = new Map(plan.items.map((it) => [it.id, it]))
  for (const item of plan.items) {
    const reason = preflightItem(item, byId)
    if (reason) refused.push({ id: item.id, reason })
    else runnable.push(item)
  }
  const cycleIds = detectWaitsForCycle(runnable)
  if (cycleIds.size) {
    for (let i = runnable.length - 1; i >= 0; i--) {
      if (cycleIds.has(runnable[i].id)) {
        refused.push({ id: runnable[i].id, reason: 'waitsFor cycle' })
        runnable.splice(i, 1)
      }
    }
  }
  return { runnable, refused }
}

/**
 * makeMilestones(items) -> {get(itemId, seat), settle(itemId, seat, v), releaseFrom(item, i)}
 * A settle-once promise per (itemId, seat), created lazily on first get/settle.
 */
function makeMilestones(items) {
  const store = new Map()
  function keyFor(itemId, seat) {
    return `${itemId}:${seat}`
  }
  function ensure(itemId, seat) {
    const key = keyFor(itemId, seat)
    let entry = store.get(key)
    if (!entry) {
      let resolveFn
      const promise = new Promise((resolve) => {
        resolveFn = resolve
      })
      entry = { promise, resolve: resolveFn, settled: false }
      store.set(key, entry)
    }
    return entry
  }
  return {
    get(itemId, seat) {
      return ensure(itemId, seat).promise
    },
    settle(itemId, seat, v) {
      const entry = ensure(itemId, seat)
      if (entry.settled) return
      entry.settled = true
      entry.resolve(v)
    },
    releaseFrom(item, i) {
      for (let idx = i; idx < item.stages.length; idx++) {
        const entry = ensure(item.id, item.stages[idx].seat)
        if (!entry.settled) {
          entry.settled = true
          entry.resolve(null)
        }
      }
    },
  }
}

/**
 * makeLocks() -> {withLocks(keys, fn) -> fn's promise}
 * Per-key promise chains; sorted, deduped, all-or-wait acquisition; released
 * (success or rejection) once fn settles.
 */
function makeLocks() {
  const chains = new Map()
  return {
    withLocks(keys, fn) {
      const sorted = Array.from(new Set(keys)).sort()
      if (sorted.length === 0) return fn()
      const waits = sorted.map((k) => chains.get(k) || Promise.resolve())
      const gate = Promise.all(waits)
      let releaseResolve
      const releasePromise = new Promise((resolve) => {
        releaseResolve = resolve
      })
      for (const k of sorted) {
        chains.set(
          k,
          gate.then(() => releasePromise).catch(() => {})
        )
      }
      const run = gate.then(() => fn())
      run.then(
        () => releaseResolve(),
        () => releaseResolve()
      )
      return run
    },
  }
}

function findPlannerOutput(item, outs) {
  const plannerStage = item.stages.find((s) => s.output === 'planner-v1')
  if (!plannerStage) return null
  return outs[plannerStage.seat]
}

/**
 * lockKeysFor(item, stage, outs, plan) -> string[]
 * Shared mode + writes:true: file:<normalizePath(p)> over the implementer's
 * mainFiles∪docFiles or the test-author's testFiles∪existingTestEdits[].file,
 * plus extraLockKeys always. Sorted, deduped.
 */
function lockKeysFor(item, stage, outs, plan) {
  const keys = new Set()
  for (const k of stage.extraLockKeys || []) keys.add(k)
  if (plan.worktreeMode === 'shared' && stage.writes) {
    const plannerOut = findPlannerOutput(item, outs)
    let files = []
    if (stage.output === 'implementer-v1') {
      files = files.concat((plannerOut && plannerOut.mainFiles) || [], (plannerOut && plannerOut.docFiles) || [])
    } else if (stage.output === 'test-author-v1') {
      files = files.concat((plannerOut && plannerOut.testFiles) || [])
      for (const e of (plannerOut && plannerOut.existingTestEdits) || []) {
        if (e && e.file) files.push(e.file)
      }
    }
    for (const f of files) keys.add(`file:${normalizePath(f)}`)
  }
  return Array.from(keys).sort()
}

function collectFiles(output) {
  if (!output) return []
  const files = []
  for (const f of output.mainFiles || []) files.push(normalizePath(f))
  for (const f of output.docFiles || []) files.push(normalizePath(f))
  for (const f of output.testFiles || []) files.push(normalizePath(f))
  for (const e of output.existingTestEdits || []) {
    if (e && e.file) files.push(normalizePath(e.file))
  }
  return files
}

/**
 * overlapDeferral(mine, higher, mode) -> null | {reason}
 * mine = {short, output}; higher = [{short, output|null}] in args order.
 * mode 'shared' -> always null. Else: first file intersection -> {reason:'overlap <short>'}.
 */
function overlapDeferral(mine, higher, mode) {
  if (mode === 'shared') return null
  const mineFiles = collectFiles(mine.output)
  for (const h of higher) {
    if (!h.output) continue
    const hFiles = collectFiles(h.output)
    if (mineFiles.some((f) => hFiles.includes(f))) return { reason: `overlap ${h.short}` }
  }
  return null
}

/**
 * envelopeSchema(outputId, extra={}) -> JSON schema for the common envelope.
 * A built-in output id is never overridden by extra.
 */
function envelopeSchema(outputId, extra = {}) {
  const output = OUTPUT_SCHEMAS[outputId] || extra[outputId] || OUTPUT_SCHEMAS['generic-v1']
  return {
    type: 'object',
    required: ['status', 'reason', 'notes', 'commits', 'files', 'modelReported', 'output'],
    properties: {
      status: { enum: ['done', 'stopped', 'deferred'] },
      reason: { type: 'string' },
      entry: {
        type: 'object',
        properties: {
          applied: { type: 'boolean' },
          alreadyInPhase: { type: 'boolean' },
          newRole: { type: 'string' },
          previousRole: { type: 'string' },
          errorCode: { type: 'string' },
          blockers: { type: 'array', items: { type: 'string' } },
          contendedResources: { type: 'array', items: { type: 'string' } },
          missingNotes: { type: 'array', items: { type: 'string' } },
          unblockedItems: { type: 'array', items: { type: 'string' } },
          retried: { type: 'boolean' },
        },
      },
      notes: {
        type: 'array',
        items: {
          type: 'object', required: ['key', 'actor'],
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
      output,
    },
  }
}

/**
 * mapEntry(env, entryMode='seat') -> {status, reason}
 * Maps a seat's entry report per §4.3's precedence table.
 */
function mapEntry(env, entryMode = 'seat') {
  const entry = env && env.entry
  if (entryMode === 'pre-entered') {
    if (entry && entry.alreadyInPhase) return { status: 'done', reason: 'alreadyInPhase' }
    return { status: 'stopped', reason: 'not pre-entered' }
  }
  if (!entry) return { status: 'stopped', reason: 'no entry report' }
  if (entry.applied === true && entry.newRole === 'work') return { status: 'done', reason: 'applied' }
  if (entry.alreadyInPhase) return { status: 'done', reason: 'alreadyInPhase' }
  if (entry.errorCode === 'gate_blocked' && entry.previousRole === 'work') {
    return { status: 'done', reason: 'alreadyInPhase' }
  }
  if (entry.errorCode === 'dependency_blocked' || (Array.isArray(entry.blockers) && entry.blockers.length > 0)) {
    return { status: 'deferred', reason: `blocked by ${(entry.blockers || []).join(',')}` }
  }
  if (entry.errorCode === 'resource_unavailable') {
    return { status: 'deferred', reason: `resource ${(entry.contendedResources || []).join(',')}` }
  }
  if (entry.errorCode === 'config_unavailable') {
    return { status: 'deferred', reason: 'config-unavailable' }
  }
  if (entry.errorCode === 'gate_blocked' && entry.previousRole === 'queue') {
    return { status: 'stopped', reason: `queue gap ${(entry.missingNotes || []).join(',')}` }
  }
  if (entry.errorCode) return { status: 'stopped', reason: entry.errorCode }
  return { status: 'stopped', reason: 'no entry report' }
}

/**
 * mapStageResult(stage, env, entryMode='seat') -> {status, reason}
 * Precedence: null envelope; schema-changed; config-unavailable; entry (when
 * stage.enters and not done); planner proceed===false; test-author
 * missingDeclaration/breachDisclosure; else env.status when stopped|deferred; else done.
 */
function mapStageResult(stage, env, entryMode = 'seat') {
  if (env === null || env === undefined) return { status: 'stopped', reason: 'agent returned null' }
  if (env.reason === 'schema-changed') return { status: 'stopped', reason: 'schema-changed' }
  if (env.reason === 'config-unavailable') return { status: 'deferred', reason: 'config-unavailable' }
  if (stage.enters) {
    const entryResult = mapEntry(env, entryMode)
    if (entryResult.status !== 'done') return entryResult
  }
  if (stage.output === 'planner-v1' && env.output && env.output.proceed === false) {
    return { status: 'stopped', reason: `planner: ${env.output.blockReason}` }
  }
  if (stage.output === 'test-author-v1' && env.output) {
    if (env.output.missingDeclaration && env.output.missingDeclaration !== 'none') {
      return { status: 'stopped', reason: `missing declaration: ${env.output.missingDeclaration}` }
    }
    if (env.output.breachDisclosure && env.output.breachDisclosure !== 'none') {
      return { status: 'stopped', reason: `breach: ${env.output.breachDisclosure}` }
    }
  }
  if (env.status === 'stopped' || env.status === 'deferred') return { status: env.status, reason: env.reason }
  return { status: 'done', reason: env.reason }
}

/**
 * scanDeclarations(text) -> {text, stripped:string[]}
 * Splits text into lines and removes every line containing a behaviour word as a
 * whole word (case-insensitive): returns, throws, falls back, catches, calls, if,
 * when, otherwise, instead — except a line whose trimmed form starts with
 * 'runtime call order:', which is always kept. `text` is the kept lines rejoined
 * with "\n"; `stripped` is the removed lines in order.
 */
function scanDeclarations(text) {
  const BEHAVIOUR_WORDS = ['returns', 'throws', 'falls back', 'catches', 'calls', 'if', 'when', 'otherwise', 'instead']
  const lines = String(text).split('\n')
  const kept = []
  const stripped = []
  for (const line of lines) {
    if (line.trim().toLowerCase().startsWith('runtime call order:')) {
      kept.push(line)
      continue
    }
    let hit = false
    for (const w of BEHAVIOUR_WORDS) {
      const re = new RegExp(`\\b${w.replace(/ /g, '\\s+')}\\b`, 'i')
      if (re.test(line)) {
        hit = true
        break
      }
    }
    if (hit) stripped.push(line)
    else kept.push(line)
  }
  return { text: kept.join('\n'), stripped }
}

/** seatActor(stage, item, plan) -> {id, kind:'subagent', parent:'workflow:'+runId} */
function seatActor(stage, item, plan) {
  return { id: `${stage.seat}:${item.short}:${plan.runId}`, kind: 'subagent', parent: `workflow:${plan.runId}` }
}

function isReadOnlyStage(stage) {
  return !stage.writes && (!stage.notes || stage.notes.length === 0)
}

/** Part 1: seat line. */
function promptSeatLine(plan, item, stage) {
  const title = item.title || item.short
  return `SEAT: ${stage.seat} for item ${item.id} (${item.short}) "${title}". Run ${plan.runId}.`
}

/** Part 2: worktree scope + shell discipline. */
function promptScope(plan, item) {
  const proj = plan.project || {}
  const lines = [`WORKTREE: ${item.worktree}`]
  if (proj.searchScope) lines.push(`SEARCH SCOPE: ${proj.searchScope}`)
  if (proj.scratchDir) lines.push(`SCRATCHPAD: ${proj.scratchDir}`)
  if (proj.shell) lines.push(`SHELL: ${proj.shell}`)
  lines.push('Never prefix a command with cd — use absolute paths or git -C <worktree>.')
  lines.push('Never run a bare find / or other unscoped filesystem walk.')
  lines.push('Leave no background commands running when you return.')
  return lines.join('\n')
}

/** Part 3: tool selection. */
function promptTools(stage) {
  const names = [
    'mcp__mcp-task-orchestrator__query_items',
    'mcp__mcp-task-orchestrator__query_notes',
    'mcp__mcp-task-orchestrator__query_dependencies',
    'mcp__mcp-task-orchestrator__get_context',
    'mcp__mcp-task-orchestrator__query_rules',
  ]
  if (!isReadOnlyStage(stage)) names.push('mcp__mcp-task-orchestrator__manage_notes')
  if (stage.enters) names.push('mcp__mcp-task-orchestrator__advance_item')
  return `TOOLS: load with ToolSearch select:${names.join(',')}`
}

/** Part 4: actor. */
function promptActor(stage, item, plan) {
  const actor = seatActor(stage, item, plan)
  return (
    `ACTOR: ${JSON.stringify(actor)} — place this actor inside every notes[]/transitions[] ` +
    'element you write (enforce-actor-attribution.mjs checks per element; phase-guard-record.mjs ' +
    'filters on parent starting with "workflow:").'
  )
}

/** Part 5: owned notes / excluded reads. */
function promptOwnedNotes(stage) {
  const owned = (stage.notes || []).join(', ') || 'none'
  const excluded = (stage.readsExclude || []).join(', ') || 'none'
  return (
    `NOTES: you own [${owned}]. The phase's other required notes belong to other seats — ` +
    `do not fill them. Excluded from your reads: [${excluded}]. Always pass keys= on every ` +
    'query_notes call.'
  )
}

/** Part 6: rule fetch by key. */
function protocolKeyFor(stage) {
  if (stage.enters) return 'protocol.entry-seat'
  if (isReadOnlyStage(stage)) return 'protocol.read-only-agent'
  return 'protocol.in-phase-seat'
}

function promptRules(plan, stage) {
  const protocolKey = protocolKeyFor(stage)
  const keys = [protocolKey]
  if (stage.writes) keys.push('commit-discipline')
  for (const k of stage.rules || []) {
    if (!keys.includes(k)) keys.push(k)
  }
  const skillKeys = stage.skills ? Object.keys(stage.skills) : []
  const lines = [
    `RULES: fetch each key below via query_rules(operation:"get", rootId:"${plan.rootId}", key:<key>) and follow the returned body over anything paraphrased here.`,
    `Keys: ${keys.join(', ')}.`,
    `RESOURCE_NOT_FOUND for a key falls back to this stage's skills entry of the same name${skillKeys.length ? ` (${skillKeys.join(', ')})` : ''}.`,
    `A missing PROTOCOL key (${protocolKey}) is fatal: status stopped, reason "rule ${protocolKey} unavailable".`,
    'Record every key you fetched, with its rulesVersion, under the envelope\'s rulesFetched.',
  ]
  return lines.join('\n')
}

/** Part 7: drift pin, omitted for schema-free items and read-only seats. */
function promptDriftPin(item, stage) {
  if (item.schemaFree || isReadOnlyStage(stage)) return ''
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

/** Part 9: inline note bodies, constant. */
function promptInlineBodies() {
  return (
    'NOTE BODIES: pass body inline on every manage_notes upsert. Never use bodyFromFile. ' +
    're-trim and re-upsert if any upsert response carries a warning.'
  )
}

/** Part 10: commit form, writers only. */
function promptCommitForm(item, stage) {
  if (!stage.writes) return ''
  return [
    'COMMIT: stage only your own paths, then commit with the trailer:',
    `git -C ${item.worktree} commit --only -m "<type>(<scope>): <title> [${item.short}]" -m "<why>" -m "Seat: ${stage.seat}" -m "Co-Authored-By: <your model attribution line>" -- <paths>`,
  ].join('\n')
}

/** Part 11: verify commands whose seats include this seat, verbatim. */
function promptVerify(plan, stage) {
  const proj = plan.project || {}
  const entries = (proj.verify || []).filter((v) => Array.isArray(v.seats) && v.seats.includes(stage.seat))
  if (entries.length === 0) return ''
  const lines = ['VERIFY:']
  for (const v of entries) lines.push(v.command || JSON.stringify(v))
  return lines.join('\n')
}

/**
 * handoff(stage, outs) -> string
 * outs is keyed by OUTPUT-SCHEMA id (not seat name). Builds the hand-off block for
 * stage.output from its declared upstream outputs; empty string when none apply.
 */
function handoff(stage, outs) {
  const lines = []
  if (stage.output === 'implementer-v1') {
    const p = outs['planner-v1']
    if (p) {
      lines.push('HANDOFF from planner:')
      lines.push(`decisions: ${p.decisions || 'none'}`)
      lines.push(`mainFiles: ${(p.mainFiles || []).join(', ') || 'none'}`)
      lines.push(`docFiles: ${(p.docFiles || []).join(', ') || 'none'}`)
      lines.push(`missingApiOrSeam: ${p.missingApiOrSeam || 'none'}`)
      lines.push(`diagnosisCorrections: ${p.diagnosisCorrections || 'none'}`)
    }
  } else if (stage.output === 'declarations-v1') {
    const i = outs['implementer-v1']
    if (i) {
      lines.push('HANDOFF from implementer:')
      lines.push(`publicSurface: ${i.publicSurface || 'none'}`)
      lines.push(`mainFilesChanged: ${(i.mainFilesChanged || []).join(', ') || 'none'}`)
      lines.push(`docFilesChanged: ${(i.docFilesChanged || []).join(', ') || 'none'}`)
    }
  } else if (stage.output === 'test-author-v1') {
    const d = outs['declarations-v1']
    const p = outs['planner-v1']
    const i = outs['implementer-v1']
    if (d || p || i) lines.push('HANDOFF:')
    if (d) {
      lines.push(`declarations: ${d.declarations || 'none'}`)
      lines.push(`harnessPointers: ${d.harnessPointers || 'none'}`)
      lines.push(`gaps: ${d.gaps || 'none'}`)
    }
    if (p) {
      lines.push(`testFiles: ${(p.testFiles || []).join(', ') || 'none'}`)
      lines.push(`existingTestEdits: ${JSON.stringify(p.existingTestEdits || [])}`)
    }
    if (i) {
      lines.push(`failingExistingTests (declared edits): ${JSON.stringify(i.failingExistingTests || [])}`)
    }
  }
  return lines.join('\n')
}

/** Part 13: rerun-safe check + entry protocol. */
function promptRerunAndEntry(plan, item, stage, actor) {
  if (!stage.writes && !stage.enters) return ''
  const lines = []
  if (stage.writes) {
    lines.push(
      `RERUN CHECK: before doing any work, run git -C ${item.worktree} log --format=%H%x09%s%x09%b ${plan.baseSha}..HEAD ` +
        `and look for a commit subject containing "[${item.short}]" with trailer "Seat: ${stage.seat}". If found, your ` +
        `own notes are already filled under actor ${actor.id} (verify via query_notes with includeBody:false) — stop, ` +
        'do not redo the work.'
    )
  }
  if (stage.enters) {
    lines.push('ENTRY: call get_context(itemId) first.')
    lines.push('role "work" -> do not advance; report entry.alreadyInPhase.')
    lines.push('role "queue" -> call advance_item(transitions:[{itemId, trigger:"start", actor}]) exactly once.')
    lines.push('Rerun-safe entry: never call start from work — only from queue.')
    if (plan.entryMode === 'pre-entered') {
      lines.push('entryMode is pre-entered: verify role is already "work" and never call advance_item.')
    }
  }
  return lines.join('\n')
}

/** Part 14: return contract. */
function promptReturn(stage) {
  return `RETURN: return the structured envelope (${stage.output}); notes are the report.`
}

function reKeyOutsByOutputId(item, outsBySeat) {
  const byOutput = {}
  for (const s of item.stages) {
    if (outsBySeat[s.seat] !== undefined) byOutput[s.output] = outsBySeat[s.seat]
  }
  return byOutput
}

/**
 * seatPrompt(plan, item, stage, outs) -> string
 * Joins the 14 non-empty prompt parts (§5.3) with "\n\n"; each part is a pure
 * function of plan/item/stage/outs. No rule text lives here — rules are fetched by
 * key at runtime via query_rules (part 6).
 */
function seatPrompt(plan, item, stage, outs) {
  const actor = seatActor(stage, item, plan)
  const outsByOutput = reKeyOutsByOutputId(item, outs || {})
  if (
    stage.output === 'test-author-v1' &&
    outsByOutput['declarations-v1'] &&
    typeof outsByOutput['declarations-v1'].declarations === 'string'
  ) {
    outsByOutput['declarations-v1'] = Object.assign({}, outsByOutput['declarations-v1'], {
      declarations: scanDeclarations(outsByOutput['declarations-v1'].declarations).text,
    })
  }
  const parts = [
    promptSeatLine(plan, item, stage),
    promptScope(plan, item),
    promptTools(stage),
    promptActor(stage, item, plan),
    promptOwnedNotes(stage),
    promptRules(plan, stage),
    promptDriftPin(item, stage),
    promptConfigRetry(),
    promptInlineBodies(),
    promptCommitForm(item, stage),
    promptVerify(plan, stage),
    handoff(stage, outsByOutput),
    promptRerunAndEntry(plan, item, stage, actor),
    promptReturn(stage),
  ]
  return parts.filter((p) => p && p.length > 0).join('\n\n')
}

function shortOf(plan, itemId) {
  const found = plan.items.find((it) => it.id === itemId)
  return found ? found.short : itemId
}

async function higherPriorityOutputs(plan, item, milestones) {
  const idx = plan.items.findIndex((it) => it.id === item.id)
  const higher = plan.items.slice(0, idx)
  const results = []
  for (const h of higher) {
    const plannerStage = h.stages.find((s) => s.output === 'planner-v1')
    if (!plannerStage) {
      results.push({ short: h.short, output: null })
      continue
    }
    const v = await milestones.get(h.id, plannerStage.seat)
    results.push({ short: h.short, output: v && v.status === 'done' ? v.output : null })
  }
  return results
}

async function callSeat(plan, item, stage, outs, deps) {
  const prompt = seatPrompt(plan, item, stage, outs)
  const schema = envelopeSchema(stage.output, plan.outputSchemas)
  const opts = {
    label: `${stage.seat}:${item.short}`,
    phase: stage.phase === 'queue' ? 'Queue' : 'Work',
    schema,
  }
  if (stage.dispatch) {
    if (stage.dispatch.model) opts.model = stage.dispatch.model
    if (stage.dispatch.effort) opts.effort = stage.dispatch.effort
    if (stage.dispatch.agent) opts.agentType = stage.dispatch.agent
  }
  let env
  let agentTypeUsed = opts.agentType || null
  let agentTypeFallback = false
  try {
    env = await deps.agent(prompt, opts)
  } catch (e) {
    if (opts.agentType) {
      const retryOpts = Object.assign({}, opts)
      delete retryOpts.agentType
      try {
        env = await deps.agent(prompt, retryOpts)
        agentTypeUsed = null
        agentTypeFallback = true
      } catch (e2) {
        return {
          env: {
            status: 'stopped', reason: `agent threw: ${e2 && e2.message}`, notes: [],
            commits: { pre: '', post: '' }, files: [], modelReported: '',
          },
          modelReported: '', agentTypeUsed: null, agentTypeFallback,
        }
      }
    } else {
      return {
        env: {
          status: 'stopped', reason: `agent threw: ${e && e.message}`, notes: [],
          commits: { pre: '', post: '' }, files: [], modelReported: '',
        },
        modelReported: '', agentTypeUsed: null, agentTypeFallback: false,
      }
    }
  }
  return { env, modelReported: env ? env.modelReported : '', agentTypeUsed, agentTypeFallback }
}

/**
 * runItem(plan, item, deps) -> item result
 * deps = {agent, milestones, locks, log}. Awaits waitsFor, then runs each stage
 * under withLocks(lockKeysFor), settles its milestone, and stops on the first
 * non-done stage. Per-item mode runs overlapDeferral right after the item's own
 * planner-v1 stage.
 */
async function runItem(plan, item, deps) {
  const outs = {}
  const stages = []
  const result = { id: item.id, short: item.short, status: 'done', reason: '', stages, outputs: outs }
  if (item.schemaFree) result.schemaFree = true

  for (const w of item.waitsFor || []) {
    const v = await deps.milestones.get(w.item, w.milestone)
    if (!v || v.status !== 'done') {
      result.status = 'deferred'
      result.reason = `in-run blocker ${shortOf(plan, w.item)} did not reach ${w.milestone}`
      deps.milestones.releaseFrom(item, 0)
      return result
    }
  }

  for (let i = 0; i < item.stages.length; i++) {
    const stage = item.stages[i]
    const keys = lockKeysFor(item, stage, outs, plan)
    const stageResult = await deps.locks.withLocks(keys, () => callSeat(plan, item, stage, outs, deps))
    const env = stageResult.env
    outs[stage.seat] = env ? env.output : undefined

    const mapped = mapStageResult(stage, env, plan.entryMode)
    stages.push({
      seat: stage.seat,
      status: mapped.status,
      reason: mapped.reason,
      modelReported: stageResult.modelReported || '',
      agentTypeUsed: stageResult.agentTypeUsed || null,
      agentTypeFallback: !!stageResult.agentTypeFallback,
      notes: (env && env.notes) || [],
      commits: (env && env.commits) || { pre: '', post: '' },
      files: (env && env.files) || [],
    })
    deps.milestones.settle(item.id, stage.seat, { status: mapped.status, reason: mapped.reason, output: env ? env.output : null })

    if (mapped.status !== 'done') {
      result.status = mapped.status
      result.reason = mapped.reason
      deps.milestones.releaseFrom(item, i + 1)
      return result
    }

    if (plan.worktreeMode === 'per-item' && stage.output === 'planner-v1') {
      const higher = await higherPriorityOutputs(plan, item, deps.milestones)
      const overlap = overlapDeferral({ short: item.short, output: env.output }, higher, plan.worktreeMode)
      if (overlap) {
        result.status = 'deferred'
        result.reason = overlap.reason
        deps.milestones.releaseFrom(item, i + 1)
        return result
      }
    }
  }

  result.status = 'done'
  result.reason = 'all stages done'
  return result
}

/**
 * runPlan(plan, deps) -> {contract, started:true, runId, planDocSlug, items, refused, deferred}
 * deps = {agent, parallel, log, phase?, milestones?, locks?}. Runs preflight, releases
 * refused items' milestones, and starts one task per runnable item inside one parallel().
 */
async function runPlan(plan, deps) {
  const milestones = deps.milestones || makeMilestones(plan.items)
  const locks = deps.locks || makeLocks()
  const pf = preflight(plan)
  for (const r of pf.refused) {
    const item = plan.items.find((it) => it.id === r.id)
    if (item) milestones.releaseFrom(item, 0)
  }
  const runDeps = { agent: deps.agent, milestones, locks, log: deps.log }
  const thunks = pf.runnable.map((item) => () => runItem(plan, item, runDeps))
  const results = await deps.parallel(thunks)
  return {
    contract: 'implement-wave/result-v1',
    started: true,
    runId: plan.runId,
    planDocSlug: plan.planDocSlug,
    items: results,
    refused: pf.refused,
    deferred: plan.deferred || [],
  }
}
// @core-end
const R = normalizeArgs(args)
if (!R.ok) return { started: false, reason: R.reason }
return await runPlan(R.plan, { agent, parallel, phase, log })
