export const meta = {
  name: 'implement-wave',
  description: 'Schedules queue/work seats across a wave of MCP work items — milestones, per-file locks, entry mapping, and rerun-safe replay.',
  whenToUse: 'Never invoke this workflow bare. It is called by the implement-wave front door, which builds its args plan (items, stages, dispatch); a bare or malformed call returns {started:false}.',
  phases: [{ title: 'Queue' }, { title: 'Work' }],
}
// @core-begin
// @generated:tool-names-begin (scripts/gen-call-shapes.mjs; do not edit, run it to refresh)
const TOOL_NAMES = Object.freeze({
  ADVANCE: 'advance',
  CONFIG: 'config',
  DEPS_READ: 'deps_read',
  DEPS_WRITE: 'deps_write',
  DOCS_READ: 'docs_read',
  DOCS_WRITE: 'docs_write',
  ITEMS_READ: 'items_read',
  ITEMS_WRITE: 'items_write',
  NOTES_READ: 'notes_read',
  NOTES_WRITE: 'notes_write',
  REPORT: 'report',
  RUNS_READ: 'runs_read',
  RUNS_WRITE: 'runs_write',
})
// @generated:tool-names-end
const MCP_TOOL_PREFIX = 'mcp__mcp-task-orchestrator__'
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
      declarationModules: { type: 'array', items: { type: 'string' } },
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
      modules: { type: 'array', items: { type: 'string' } },
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
      missingDeclaration: {
        type: 'string',
        description: 'exactly "none" (optionally followed by a dash and a note) when there is nothing to disclose',
      },
      breachDisclosure: {
        type: 'string',
        description: 'exactly "none" (optionally followed by a dash and a note) when there is nothing to disclose',
      },
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
 * relativizePath(p, roots) -> canonical repo-relative POSIX path ('' = the whole worktree).
 * Canonicalization (applied to p and to every root):
 *   1. every '\' becomes '/';
 *   2. an absolute prefix is kept ('/' or a drive 'x:/'); the remainder is split on '/', empty
 *      segments (from '//' runs, leading/trailing '/') and '.' segments are dropped, and '..'
 *      segments are kept verbatim (never resolved); the pieces are rejoined with '/';
 *   3. a drive letter is lowercased (normalizePath).
 *   So '.', './', './/' and '' -> ''; 'src/.' -> 'src'; 'src/./x.js' and 'src//x.js' ->
 *   'src/x.js'; '././x' -> 'x'; '//h/s/x' -> '/h/s/x'; 'src/../x' is unchanged; the filesystem
 *   roots '/' and 'x:/' stay '/' and 'x:/' (absolute, never '' or 'x:').
 * Root stripping: falsy roots and roots that canonicalize to '' or to a filesystem root ('/' or
 * 'x:/') are ignored. Roots are tried longest first; the comparison is case-insensitive when the
 * canonical path is a drive-letter path, exact otherwise. A path equal to a root returns ''; a
 * path under a root ('<root>/...') returns the remainder after '<root>/'. The first match wins.
 * A path under no root is returned in canonical form (an absolute path stays absolute, so callers
 * treat it as unrooted — see isUnrootedPath).
 * Kept byte-for-byte in parity with scripts/run-exec-lib.mjs's relativizePath (verify has no core).
 */
function relativizePath(p, roots) {
  const canon = (x) => {
    const t = String(x).replace(/\\/g, '/')
    const m = /^[a-zA-Z]:\//.exec(t) || /^\//.exec(t)
    const prefix = m ? m[0] : ''
    const segs = t.slice(prefix.length).split('/').filter((g) => g !== '' && g !== '.')
    return normalizePath(prefix + segs.join('/'))
  }
  const s = canon(p)
  const normRoots = (roots || [])
    .filter((r) => r)
    .map(canon)
    .filter((r) => r && !r.endsWith('/'))
    .sort((a, b) => b.length - a.length)
  const drive = /^[a-z]:\//i.test(s)
  const fold = (x) => (drive ? x.toLowerCase() : x)
  for (const r of normRoots) {
    const fs = fold(s)
    const fr = fold(r)
    if (fs === fr) return ''
    if (fs.startsWith(fr + '/')) return s.slice(r.length + 1)
  }
  return s
}

function isGlobPath(entry) {
  return entry.includes('*') || entry.includes('?')
}

/** The roots a declared path of this item is relativized against: its worktree, then the repo root. */
function itemRoots(plan, item) {
  return [item && item.worktree, plan && plan.repoRoot].filter((r) => typeof r === 'string' && r)
}

/**
 * True when a relativized path cannot name a repo-relative location: empty, still absolute, or
 * containing a '..' segment. Such an entry claims the whole worktree for locking (D3).
 */
function isUnrootedPath(rel) {
  return !rel || isAbsolutePath(rel) || rel.split('/').includes('..')
}

/** Leading wildcard-free segments of a repo-relative path ('' for '*.md'). */
function literalBase(rel) {
  const segs = rel.split('/')
  const out = []
  for (const s of segs) {
    if (isGlobPath(s)) break
    out.push(s)
  }
  return out.join('/')
}

function basesOverlap(a, b) {
  const x = a.toLowerCase()
  const y = b.toLowerCase()
  if (x === y || x === '' || y === '') return true
  return y.startsWith(x + '/') || x.startsWith(y + '/')
}

/**
 * lockKeysConflict(a, b) -> bool (D3). Two file: keys conflict iff their literal bases are equal or
 * one is a segment-ancestor of the other (case-insensitive). A worktree: key conflicts with every
 * file: key and with an equal worktree: key. Every other pair needs exact equality.
 */
function lockKeysConflict(a, b) {
  const sa = String(a)
  const sb = String(b)
  const fa = sa.startsWith('file:')
  const fb = sb.startsWith('file:')
  const wa = sa.startsWith('worktree:')
  const wb = sb.startsWith('worktree:')
  if (fa && fb) return basesOverlap(literalBase(sa.slice(5)), literalBase(sb.slice(5)))
  if ((wa && fb) || (fa && wb)) return true
  return sa === sb
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
  // repoRoot is optional and additive (the main checkout root, a second relativization root).
  if (parsed.repoRoot !== undefined && parsed.repoRoot !== null && typeof parsed.repoRoot !== 'string') {
    return { ok: false, reason: 'invalid args: bad repoRoot' }
  }
  if (parsed.project && parsed.project.appendOnlyFiles !== undefined &&
      (!Array.isArray(parsed.project.appendOnlyFiles) || parsed.project.appendOnlyFiles.some((e) => typeof e !== 'string'))) {
    return { ok: false, reason: 'invalid args: bad appendOnlyFiles' }
  }
  // altitudeSafe is optional: true scrubs the result to counts and pointers (altitudeSafeResult),
  // so a director session that makes the Workflow call never receives seat prose.
  if ('altitudeSafe' in parsed && parsed.altitudeSafe !== undefined && typeof parsed.altitudeSafe !== 'boolean') {
    return { ok: false, reason: 'invalid args: bad altitudeSafe' }
  }
  // serverRunId is optional (set by run-planner bind-run once runs_write open answered): the server
  // run UUID. Seats of a bound run read their seat token from a file under project.scratchDir.
  if (parsed.serverRunId !== undefined && parsed.serverRunId !== null) {
    if (typeof parsed.serverRunId !== 'string' ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(parsed.serverRunId)) {
      return { ok: false, reason: 'invalid args: bad serverRunId' }
    }
    const pj = parsed.project
    if (!pj || typeof pj !== 'object' || typeof pj.scratchDir !== 'string' || pj.scratchDir === '') {
      return { ok: false, reason: 'invalid args: serverRunId requires project.scratchDir' }
    }
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
 * Each call registers its sorted, deduped keys and waits on every earlier, still-unreleased
 * registration holding a key that lockKeysConflict()s with one of its own (so a directory or glob
 * contends with a file under it). FIFO and deadlock-free: a registration only ever waits on
 * earlier ones. Released (success or rejection) once fn settles.
 */
function makeLocks() {
  let active = []
  return {
    withLocks(keys, fn) {
      const sorted = Array.from(new Set(keys)).sort()
      if (sorted.length === 0) return fn()
      const waits = active
        .filter((reg) => reg.keys.some((held) => sorted.some((k) => lockKeysConflict(held, k))))
        .map((reg) => reg.done)
      let releaseResolve
      const done = new Promise((resolve) => {
        releaseResolve = resolve
      })
      const reg = { keys: sorted, done }
      active.push(reg)
      const release = () => {
        active = active.filter((r) => r !== reg)
        releaseResolve()
      }
      const run = Promise.all(waits).then(() => fn())
      run.then(release, release)
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
 * Shared mode + writes:true: file:<relativizePath(p, [item.worktree, plan.repoRoot])> over the
 * implementer's mainFiles∪docFiles or the test-author's testFiles∪existingTestEdits[].file,
 * plus extraLockKeys always. Sorted, deduped. Paths are canonicalized by relativizePath, so every
 * spelling of one path ('src/x.js', 'src/./x.js', 'src//x.js', '<worktree>/src/x.js',
 * '<repoRoot>/src/x.js') yields the same file:src/x.js key. A declared path that is unrooted per
 * isUnrootedPath — it relativizes to '' ('.', './', the worktree or repo root itself), stays
 * absolute (outside both roots), or contains a '..' segment — takes
 * worktree:<normalizePath(item.worktree)> instead, which lockKeysConflict() treats as contending
 * with every file: key.
 * An implementer stage on an item with no test-author-v1 stage additionally locks the
 * planner's testFiles∪existingTestEdits[].file (D5 — those tests are "unowned" by anyone else,
 * so the implementer's write locks must cover them too).
 * When the derived file set ends up empty (no planner output at all, or the planner reported no
 * files for this stage's role), the stage takes the single key worktree:<normalizePath(item.worktree)>
 * instead of no key at all (D4a — an empty lock set must not disable locking altogether).
 */
function lockKeysFor(item, stage, outs, plan) {
  const keys = new Set()
  for (const k of stage.extraLockKeys || []) keys.add(k)
  if (plan.worktreeMode === 'shared' && stage.writes) {
    const plannerOut = findPlannerOutput(item, outs)
    let files = []
    if (stage.output === 'implementer-v1') {
      files = files.concat((plannerOut && plannerOut.mainFiles) || [], (plannerOut && plannerOut.docFiles) || [])
      const hasTestAuthorStage = item.stages.some((s) => s.output === 'test-author-v1')
      if (!hasTestAuthorStage) {
        files = files.concat((plannerOut && plannerOut.testFiles) || [])
        for (const e of (plannerOut && plannerOut.existingTestEdits) || []) {
          if (e && e.file) files.push(e.file)
        }
      }
    } else if (stage.output === 'test-author-v1') {
      files = files.concat((plannerOut && plannerOut.testFiles) || [])
      for (const e of (plannerOut && plannerOut.existingTestEdits) || []) {
        if (e && e.file) files.push(e.file)
      }
    }
    const wholeTree = `worktree:${normalizePath(item.worktree)}`
    if (files.length === 0) {
      keys.add(wholeTree)
    } else {
      const roots = itemRoots(plan, item)
      for (const f of files) {
        const rel = relativizePath(f, roots)
        if (isUnrootedPath(rel)) keys.add(wholeTree)
        else keys.add(`file:${rel}`)
      }
    }
  }
  return Array.from(keys).sort()
}

/**
 * collectFiles(output, roots) -> string[] of overlap claims for per-item deferral.
 * Takes output.mainFiles ∪ docFiles ∪ testFiles ∪ existingTestEdits[].file (in that order),
 * relativizes each against roots, and returns one claim per entry: the repo-relative path, or
 * '**' (a whole-tree claim whose literal base '' overlaps every path) when the entry is unrooted
 * per isUnrootedPath — it canonicalizes to '' (e.g. '.', './', the worktree or repo root itself),
 * stays absolute (outside every root), or contains a '..' segment. A null output, or one that
 * declares no entries at all, yields [] (claims nothing — D6).
 */
function collectFiles(output, roots) {
  if (!output) return []
  const raw = [].concat(output.mainFiles || [], output.docFiles || [], output.testFiles || [])
  for (const e of output.existingTestEdits || []) {
    if (e && e.file) raw.push(e.file)
  }
  const files = []
  for (const f of raw) {
    const rel = relativizePath(f, roots || [])
    files.push(isUnrootedPath(rel) ? '**' : rel)
  }
  return files
}

/**
 * appendOnlyMatch(claim, patterns) -> bool
 * True when the repo-relative claim matches at least one appendOnlyFiles pattern. Both sides are
 * canonicalised (backslash to '/', a leading './' removed, repeated '/' collapsed) and matched
 * whole, anchored at the repo root, segment by segment and case-insensitively. A '**' segment
 * matches zero or more whole segments; inside a segment '*' matches any run of characters and '?'
 * exactly one; every other character is literal.
 */
function appendOnlyMatch(claim, patterns) {
  const canon = (x) => String(x).replace(/\\/g, '/').replace(/^\.\//, '').replace(/\/{2,}/g, '/')
  const segs = canon(claim).toLowerCase().split('/')
  const segMatch = (pat, text) => {
    let re = ''
    for (const ch of pat) re += ch === '*' ? '[^/]*' : ch === '?' ? '[^/]' : ch.replace(/[.+^${}()|[\]\\]/g, '\\$&')
    return new RegExp('^' + re + '$').test(text)
  }
  const walk = (ps, pi, ti) => {
    if (pi === ps.length) return ti === segs.length
    if (ps[pi] === '**') {
      for (let k = ti; k <= segs.length; k++) if (walk(ps, pi + 1, k)) return true
      return false
    }
    return ti < segs.length && segMatch(ps[pi], segs[ti]) && walk(ps, pi + 1, ti + 1)
  }
  return (patterns || []).some((pat) => walk(canon(pat).toLowerCase().split('/'), 0, 0))
}

/**
 * overlapDeferral(mine, higher, mode, appendOnlyFiles = []) -> null | {reason}
 * mine = {short, output, roots?}; higher = [{short, output|null, roots?}] in args order. Each
 * entry's declared paths are relativized against its own roots (its worktree, then the repo root)
 * and turned into file:<claim> keys by collectFiles.
 * mode 'shared' -> always null. Else: the first higher item (skipping those with a null output)
 * with a non-exempt claim pair that overlaps under the lockKeysConflict predicate (equal, or a
 * directory/glob base containing the other, case-insensitive) -> {reason:'overlap <short> on
 * <f1>[, <f2>...]'}, the files being the distinct mine claims in those pairs, in collectFiles
 * order (an unrooted claim prints as '**'); none -> null.
 * A pair is exempt (a merge-time conflict, not a deferral) when both claims are glob-free, not
 * '**', equal case-insensitively, and match an appendOnlyFiles pattern (appendOnlyMatch).
 * Whole-tree claims (D6): an unrooted entry on either side — one that relativizes to '' ('.',
 * './', a root itself), stays absolute, or contains '..' — overlaps every entry on the other side,
 * including another whole-tree claim, and is never exempt. A side whose declared set is entirely
 * empty claims nothing, so it never causes or suffers an overlap.
 */
function overlapDeferral(mine, higher, mode, appendOnlyFiles) {
  if (mode === 'shared') return null
  const patterns = Array.isArray(appendOnlyFiles) ? appendOnlyFiles : []
  const mineFiles = collectFiles(mine.output, mine.roots)
  const exempt = (a, b) =>
    a !== '**' && b !== '**' && !isGlobPath(a) && !isGlobPath(b) &&
    a.toLowerCase() === b.toLowerCase() && appendOnlyMatch(a, patterns)
  for (const h of higher) {
    if (!h.output) continue
    const hFiles = collectFiles(h.output, h.roots)
    const files = []
    for (const a of mineFiles) {
      if (files.includes(a)) continue
      if (hFiles.some((b) => lockKeysConflict(`file:${a}`, `file:${b}`) && !exempt(a, b))) files.push(a)
    }
    if (files.length) return { reason: `overlap ${h.short} on ${files.join(', ')}` }
  }
  return null
}

/**
 * envelopeSchema(outputId, extra={}, opts={}) -> JSON schema for the common envelope.
 * A built-in output id is never overridden by extra. A truthy opts.enters appends 'entry' to
 * required (the entering stage must report the advance result); otherwise the schema is unchanged.
 */
function envelopeSchema(outputId, extra = {}, opts = {}) {
  const output = OUTPUT_SCHEMAS[outputId] || extra[outputId] || OUTPUT_SCHEMAS['generic-v1']
  return {
    type: 'object',
    required: opts && opts.enters
      ? ['status', 'reason', 'notes', 'commits', 'files', 'modelReported', 'output', 'entry']
      : ['status', 'reason', 'notes', 'commits', 'files', 'modelReported', 'output'],
    properties: {
      status: { enum: ['done', 'stopped', 'deferred'] },
      reason: { type: 'string' },
      interruptedAttempt: { type: 'boolean' },
      wipRef: { type: 'string' },
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
          type: 'object', required: ['key'],
          properties: {
            key: { type: 'string' }, seat: { type: 'string' },
            chars: { type: 'integer' }, warning: { type: 'string' },
          },
        },
      },
      seatEnded: { type: 'boolean' },
      decisionId: { type: 'string' },
      paused: {
        type: 'object',
        properties: { decisionId: { type: 'string' } },
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
  if (entry.errorCode === 'dependency_unmet' || (Array.isArray(entry.blockers) && entry.blockers.length > 0)) {
    return { status: 'deferred', reason: `blocked by ${(entry.blockers || []).join(',')}` }
  }
  if (entry.errorCode === 'resource_unavailable') {
    return { status: 'deferred', reason: `resource ${(entry.contendedResources || []).join(',')}` }
  }
  if (entry.errorCode === 'unavailable') {
    return { status: 'deferred', reason: 'config-unavailable' }
  }
  if (entry.errorCode === 'unauthenticated') {
    return { status: 'stopped', reason: 'seat-token-rejected' }
  }
  if (entry.errorCode === 'gate_blocked' && entry.previousRole === 'queue') {
    return { status: 'stopped', reason: `queue gap ${(entry.missingNotes || []).join(',')}` }
  }
  if (entry.errorCode) return { status: 'stopped', reason: entry.errorCode }
  return { status: 'stopped', reason: 'no entry report' }
}

/**
 * isNoneSentinel(s) -> boolean
 * True for undefined, null, '', and any string whose trimmed form matches /^none\b/i (so
 * "none - pre-fix tree extracted via git archive" and "None." are both "nothing to disclose",
 * while "nonexistent file opened" is not — \b fails right after "none" when the next character
 * is a word character, as in "nonexistent").
 */
function isNoneSentinel(s) {
  if (s === undefined || s === null || s === '') return true
  return typeof s === 'string' && /^none\b/i.test(s.trim())
}

/**
 * mapStageResult(stage, env, entryMode='seat', item?) -> {status, reason}
 * Precedence: null envelope; schema-changed; config-unavailable; entry (when
 * stage.enters and not done); planner proceed===false; planner envelope-mismatch (proceed:true
 * with an empty mainFiles/docFiles/testFiles set while stage.notes is non-empty); no-test-owner (only
 * when item is given: planner proceed:true with testFiles, no test-author-v1 stage on the item, and the
 * item's implementer-v1 stage not implicitOwner); test-author
 * missingDeclaration/breachDisclosure; else env.status when stopped|deferred; else done.
 */
function mapStageResult(stage, env, entryMode = 'seat', item) {
  if (env === null || env === undefined) return { status: 'stopped', reason: 'agent returned null' }
  if (env.reason === 'schema-changed') return { status: 'stopped', reason: 'schema-changed' }
  if (env.reason === 'config-unavailable') return { status: 'deferred', reason: 'config-unavailable' }
  if (stage.enters) {
    const entryResult = mapEntry(env, entryMode)
    if (entryResult.status !== 'done') {
      if (
        entryResult.reason === 'no entry report' &&
        (env.status === 'stopped' || env.status === 'deferred') &&
        typeof env.reason === 'string' && env.reason !== ''
      ) {
        return { status: env.status, reason: env.reason }
      }
      return entryResult
    }
  }
  if (stage.output === 'planner-v1' && env.output) {
    if (env.output.proceed === false) {
      return { status: 'stopped', reason: `planner: ${env.output.blockReason}` }
    }
    if (
      env.output.proceed === true &&
      (env.output.mainFiles || []).length === 0 &&
      (env.output.docFiles || []).length === 0 &&
      (env.output.testFiles || []).length === 0 &&
      Array.isArray(stage.notes) && stage.notes.length > 0
    ) {
      return {
        status: 'stopped',
        reason: `envelope-mismatch: planner reported no files for owned notes ${stage.notes.join(', ')}`,
      }
    }
    if (item && env.output.proceed === true && (env.output.testFiles || []).length > 0) {
      const stages = item.stages || []
      const impl = stages.find((s) => s.output === 'implementer-v1')
      if (!stages.some((s) => s.output === 'test-author-v1') && !(impl && impl.implicitOwner === true)) {
        return {
          status: 'stopped',
          reason: `no-test-owner: add needs-test-author or clear testFiles (${env.output.testFiles.join(', ')})`,
        }
      }
    }
  }
  if (stage.output === 'test-author-v1' && env.output) {
    if (!isNoneSentinel(env.output.missingDeclaration)) {
      return { status: 'stopped', reason: `missing declaration: ${env.output.missingDeclaration}` }
    }
    if (!isNoneSentinel(env.output.breachDisclosure)) {
      return { status: 'stopped', reason: `breach: ${env.output.breachDisclosure}` }
    }
  }
  if (env.status === 'stopped' || env.status === 'deferred') return { status: env.status, reason: env.reason }
  return { status: 'done', reason: env.reason }
}

/**
 * stripTokens(value, knownTokens = []) -> structurally equal copy of value with (a) every
 * property named exactly seatToken or coordinatorToken removed at any depth, and (b) every
 * occurrence of each known token (longest first) replaced by '[redacted-token]' in string
 * values and object keys; every key, including __proto__, is copied as an own data property.
 * Non-string/empty tokens are ignored; input is never mutated.
 * Method A can only strip by key (no token value is knowable inside the Workflow sandbox).
 */
function stripTokens(value, knownTokens = []) {
  const REDACTED = '[redacted-token]'
  const tokens = (Array.isArray(knownTokens) ? knownTokens : [])
    .filter((t) => typeof t === 'string' && t.length > 0)
    .sort((a, b) => b.length - a.length)
  // Single left-to-right scan, longest token first at each position, so a shorter token never
  // matches inside the redaction literal left by a longer one.
  const redact = (str) => {
    if (tokens.length === 0) return str
    let out = ''
    let i = 0
    while (i < str.length) {
      const hit = tokens.find((t) => str.startsWith(t, i))
      if (hit) {
        out += REDACTED
        i += hit.length
      } else {
        out += str[i]
        i += 1
      }
    }
    return out
  }
  const walk = (v) => {
    if (typeof v === 'string') return redact(v)
    if (Array.isArray(v)) return v.map(walk)
    if (v && typeof v === 'object') {
      const out = {}
      for (const k of Object.keys(v)) {
        if (k === 'seatToken' || k === 'coordinatorToken') continue
        Object.defineProperty(out, redact(k), {
          value: walk(v[k]),
          enumerable: true,
          writable: true,
          configurable: true,
        })
      }
      return out
    }
    return v
  }
  return walk(value)
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

/**
 * seatTokenFile(plan, item, stage) -> string | null
 * The file a seat of a bound server run reads its seat token from:
 * <project.scratchDir>/tokens/<short>.<seat>.token. null when the plan is not bound to a server run
 * (no serverRunId), when the stage is an inserted (non-schema) stage, which runs token-less, or when
 * no scratchDir is set. Only the path is ever rendered; the token itself never enters the plan.
 */
function seatTokenFile(plan, item, stage) {
  if (!plan || !plan.serverRunId || !stage || stage.inserted) return null
  const dir = plan.project && plan.project.scratchDir
  if (typeof dir !== 'string' || dir === '') return null
  return `${normalizePath(dir).replace(/\/+$/, '')}/tokens/${item.short}.${stage.seat}.token`
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

/**
 * Part 3: tool selection. Pre-entered mode never advances, so advance is withheld; runs_write is
 * named only for a seat that holds a seat token (end_seat, raise).
 */
function promptTools(plan, item, stage) {
  const names = [TOOL_NAMES.ITEMS_READ, TOOL_NAMES.NOTES_READ, TOOL_NAMES.DEPS_READ, TOOL_NAMES.CONFIG]
  if (!isReadOnlyStage(stage)) names.push(TOOL_NAMES.NOTES_WRITE)
  if (stage.enters && plan.entryMode !== 'pre-entered') names.push(TOOL_NAMES.ADVANCE)
  if (seatTokenFile(plan, item, stage)) names.push(TOOL_NAMES.RUNS_WRITE)
  return `TOOLS: load with ToolSearch select:${names.map((n) => MCP_TOOL_PREFIX + n).join(',')}`
}

/**
 * Part 4: seat token. Names the token FILE, never the token: the seat reads it itself. Omitted when
 * the plan is not bound to a server run.
 */
function promptSeatToken(plan, item, stage) {
  if (!plan.serverRunId) return ''
  const file = seatTokenFile(plan, item, stage)
  if (!file) {
    return (
      `SEAT TOKEN: none. Run ${plan.serverRunId}: this stage is not a schema seat, so it runs without a seat token. ` +
      'Read only: never write a note, never transition, never call runs_write.'
    )
  }
  return [
    `SEAT TOKEN: run ${plan.serverRunId}. Your seat token is the single line in the file ${file}. Read that file with your ` +
      'file tool, trim the line, and pass it as the top-level seatToken field of every task-orchestrator call you make, reads included.',
    'Never print, quote, echo, log or commit the token or the file content, and never copy it into a note, a commit message, ' +
      'a file or your final message. Refer to it only as "the seat token".',
    'A call refused unauthenticated ("The seat token is not accepted.") means this seat was superseded or ended: stop at once, ' +
      'write nothing more, and return status "stopped", reason "seat-token-rejected".',
  ].join('\n')
}

/** Part 5: owned notes / excluded reads. */
function promptOwnedNotes(stage, item) {
  const owned = (stage.notes || []).join(', ') || 'none'
  const excluded = (stage.readsExclude || []).join(', ') || 'none'
  let out =
    `NOTES: you own [${owned}]. The phase's other required notes belong to other seats — ` +
    `do not fill them. Excluded from your reads: [${excluded}]. Always pass keys:[...] on every ` +
    'notes_read list call.'
  const optional = (stage.optionalNotes || []).filter((k) => (stage.notes || []).includes(k))
  if (optional.length) {
    out += ` Optional among yours: [${optional.join(', ')}] — fill only with real content, never a placeholder.`
  }
  const orch = (item && item.orchestratorNotes) || []
  if (orch.length) {
    out += ` Orchestrator-owned notes [${orch.join(', ')}]: never write them - the orchestrator records them after you return.`
  }
  return out
}

/**
 * Carry-in part (after the owned-notes part). A planner-v1 stage with carry-in keys gets a
 * CARRY-INS block; an implementer-v1 stage with late carry-in keys gets a LATE CARRY-INS block;
 * every other stage gets nothing. Plan args carry key names only, never note bodies.
 */
function promptCarryIns(stage, item) {
  const keys = (item && item.carryIns) || []
  const late = (item && item.lateCarryIns) || []
  const quoted = (list) => list.map((k) => `"${k}"`).join(', ')
  if (stage.output === 'planner-v1' && keys.length) {
    const target = (stage.notes || []).includes('carry-in-dispositions')
      ? 'the carry-in-dispositions note'
      : 'a "## Carry-in dispositions" section of your own queue note'
    const rows = keys.map((k) => `  ${k}: in scope (AC n) | deferred to <item-id> | rejected because <reason>`).join('\n')
    return (
      'CARRY-INS: this item has carry-in notes. Read each body before you plan with ' +
      `notes_read(select:"get", itemId:"${item.id}", key:"<key>"), one get per key in keys:[${quoted(keys)}] (a list returns no bodies). ` +
      `Then record exactly one disposition row per key in ${target}, choosing one alternative per row, ` +
      'and end the record with the count line:\n' +
      rows +
      `\n${keys.length} of ${keys.length}`
    )
  }
  if (stage.output === 'implementer-v1' && late.length) {
    return (
      'LATE CARRY-INS: these carry-in notes were written after planning and have no planner disposition: ' +
      `[${late.join(', ')}]. Before writing code, read each body with ` +
      `notes_read(select:"get", itemId:"${item.id}", key:"<key>"), one get per key in keys:[${quoted(late)}] (a list returns no bodies). ` +
      'The orchestrator records the disposition for each in its orchestrator-carry-in-dispositions note; a key ' +
      'marked "in scope" binds you the same way task-scope does, and a conflict with it goes under Deviations requested.'
    )
  }
  return ''
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
  const skillKeys = Array.isArray(stage.skills) ? stage.skills : []
  const lines = [
    `RULES: fetch each key below via config(ops:[{op:"rules", rootId:"${plan.rootId}", key:<key>}]) and follow the returned body over anything paraphrased here.`,
    `Keys: ${keys.join(', ')}.`,
    `A not_found result for a key falls back to this stage's skills entry of the same name${skillKeys.length ? ` (${skillKeys.join(', ')})` : ''}.`,
    `A missing PROTOCOL key (${protocolKey}) is fatal: status stopped, reason "rule ${protocolKey} unavailable".`,
    'Record every key you fetched, with its rulesVersion, under the envelope\'s rulesFetched.',
  ]
  return lines.join('\n')
}

/** Part 7: drift pin, omitted for schema-free items and read-only seats. */
function promptDriftPin(item, stage) {
  if (item.schemaFree || isReadOnlyStage(stage)) return ''
  const lines = [
    `DRIFT PIN: before your first write, call items_read(select:"schema", itemId:"${item.id}").`,
    `Its configFingerprint must equal ${item.configFingerprint}.`,
  ]
  if (item.itemTraits !== undefined) {
    lines.push(`items_read(select:"get", itemId) -> traits (the item's top-level traits array) must equal ${JSON.stringify(item.itemTraits)}.`)
  } else {
    lines.push(`For reference, traits: ${JSON.stringify(item.traits || [])}.`)
  }
  lines.push('A mismatch means write nothing and return status "stopped", reason "schema-changed".')
  return lines.join('\n')
}

/** Part 8: per-element results, constant. */
function promptResults() {
  return (
    'RESULTS: a write tool answers data.results[i], one entry per ops[] element; read each element\'s own ok and ' +
    'data.results[i].error (code, detail, fix). A call-level partial_failure only means "an element failed, look per ' +
    'element": it carries no detail. The exception is an atomic batch (atomic: true): it answers only the failing ' +
    'element\'s own error with error.index, and nothing was written.'
  )
}

/** Part 9: config-unavailable retry, constant. */
function promptConfigRetry() {
  return (
    'CONFIG RETRY: on an unavailable failure (kind shedding), retry once after one intervening read call ' +
    '— no sleep. A second failure returns status "deferred", reason "config-unavailable".'
  )
}

/** Part 10: error vocabulary and retry classes, constant. */
function promptErrors() {
  return [
    'ERRORS: branch on the error code (per element, see RESULTS), never on message text.',
    'Retried: unavailable, once (CONFIG RETRY). Every other code here is never retried.',
    'run_paused {pauseReason, pauseDecisionId}: the run is paused on a budget, attempt-cap or manual pause. Never retry it; ' +
      'a director or a human answers the decision. Write nothing more and return status "stopped", reason "run_paused", ' +
      'decisionId = detail.pauseDecisionId when present. A withdrawn pause decision leaves the run paused until it closes.',
    'A successful result carrying paused:{decisionId}: your call landed and the run then paused. Make no further write; ' +
      'return status "stopped", reason "run_paused", with paused:{decisionId} in the envelope.',
    'run_at_capacity {active, max}: transient and coordinator-only (assign at maxActiveSeats); the coordinator backs off until a seat ends.',
    'Permanent, never retried: note_owned_by_other {ownerSeat} (the key belongs to another seat), seat_forbidden, ' +
      'not_claim_holder, decision_closed {decisionId, status}, forbidden (detail.required names who may), not_found ' +
      '(detail.kind seat or decision included), invalid_transition. Stop and put the code in reason. At ENTRY, follow the ' +
      'ENTRY REPORT instead (gate_blocked, dependency_unmet and resource_unavailable are reported there, not retried).',
    'A blocked item allows only resume and cancel, and answering a decision never resumes it: never resume or cancel an item yourself.',
  ].join('\n')
}

/** Part 11: inline note bodies, constant. */
function promptInlineBodies() {
  return (
    'NOTE BODIES: pass body inline on every notes_write upsert (ops:[{op:"upsert", itemId, key, role, body}]). ' +
    'Never use bodyFromFile. Re-trim and re-upsert if a result element carries a warning.'
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

/**
 * ownedTestsFor(item, stage, outsByOutput) -> string[]
 * The test paths a writer seat owns, for the <ownedTests> verify placeholder. test-author-v1 and an
 * implementer-v1 with no test-author stage own planner testFiles + existingTestEdits[].file; an
 * implementer-v1 WITH a test-author stage owns existingTestEdits[].file only. Other seats own none.
 */
function ownedTestsFor(item, stage, outsByOutput) {
  const p = outsByOutput['planner-v1']
  if (!p) return []
  const edits = []
  for (const e of p.existingTestEdits || []) if (e && e.file) edits.push(e.file)
  const files = (p.testFiles || []).concat(edits)
  if (stage.output === 'test-author-v1') return files
  if (stage.output === 'implementer-v1') {
    const hasTestAuthorStage = item.stages.some((s) => s.output === 'test-author-v1')
    return hasTestAuthorStage ? edits : files
  }
  return []
}

/** Part 11: verify commands whose seats include this seat; <ownedTests> is substituted per seat. */
function promptVerify(plan, item, stage, outsByOutput) {
  const proj = plan.project || {}
  const entries = (proj.verify || []).filter((v) => Array.isArray(v.seats) && v.seats.includes(stage.seat))
  if (entries.length === 0) return ''
  const lines = ['VERIFY:']
  for (const v of entries) {
    const command = v.command || JSON.stringify(v)
    if (!command.includes('<ownedTests>')) {
      lines.push(command)
      continue
    }
    let re = null
    if (typeof v.ownedTestsPattern === 'string') {
      try {
        re = new RegExp(v.ownedTestsPattern)
      } catch (e) {
        // Never drop the filter: an unfiltered owned set can hand the wrong files (e.g. .kt) to the runner.
        lines.push('SKIP ' + (v.name || 'verify') + ': invalid ownedTestsPattern ' + JSON.stringify(v.ownedTestsPattern))
        continue
      }
    }
    const seen = new Set()
    const owned = []
    for (const raw of ownedTestsFor(item, stage, outsByOutput)) {
      // Relativize against the worktree and the repo root, so a main-checkout absolute path runs
      // the worktree's copy of the test rather than the main checkout's.
      const n = relativizePath(raw, itemRoots(plan, item))
      if (!n) continue
      if (re && !re.test(n)) continue
      const full = isAbsolutePath(n) ? n : normalizePath(item.worktree) + '/' + n
      if (seen.has(full)) continue
      seen.add(full)
      owned.push('"' + full + '"')
    }
    if (owned.length === 0) {
      lines.push('SKIP ' + (v.name || 'verify') + ': no owned test files')
    } else {
      lines.push(command.split('<ownedTests>').join(owned.join(' ')))
    }
  }
  if (plan.worktreeMode === 'shared') {
    lines.push(
      'Shared worktree: failures whose test paths are all owned by another item or seat are expected while ' +
        'siblings are mid-wave. Record them (implementer: preExistingFailures; test-author: the verify[].summary), ' +
        'never edit those files, and continue.'
    )
  }
  return lines.join('\n')
}

/** Items of the declarations extraction union, each relativized against roots (default: as given). */
function extractUnion(outs, roots) {
  const i = outs['implementer-v1'] || {}
  const p = outs['planner-v1'] || {}
  return dedupePaths([].concat(i.mainFilesChanged || [], p.mainFiles || [], p.declarationModules || []), roots)
}

/** Relativize, then dedupe case-insensitively keeping the first spelling. */
function dedupePaths(list, roots) {
  const seen = new Set()
  const out = []
  for (const raw of list) {
    const rel = relativizePath(raw, roots)
    if (!rel) continue
    const k = rel.toLowerCase()
    if (seen.has(k)) continue
    seen.add(k)
    out.push(rel)
  }
  return out
}

/**
 * declarationGap(plan, item, stage, outs) -> null | {status:'stopped', reason}
 * outs is keyed by seat. Non-null only for a test-author-v1 stage on an item that also has a
 * declarations-v1 stage, when the planner declared a non-empty declarationModules list and the
 * extractor's output.modules lacks one of its entries (relativized, case-insensitive).
 */
function declarationGap(plan, item, stage, outs) {
  if (!stage || stage.output !== 'test-author-v1') return null
  if (!item.stages.some((s) => s.output === 'declarations-v1')) return null
  const byOutput = reKeyOutsByOutputId(item, outs || {})
  const planner = byOutput['planner-v1']
  const declared = planner && planner.declarationModules
  if (!Array.isArray(declared) || declared.length === 0) return null
  const roots = itemRoots(plan, item)
  const ext = byOutput['declarations-v1']
  const have = new Set(((ext && ext.modules) || []).map((m) => relativizePath(m, roots).toLowerCase()))
  const missing = dedupePaths(declared, roots).filter((m) => !have.has(m.toLowerCase()))
  if (missing.length === 0) return null
  return { status: 'stopped', reason: 'missingDeclaration: ' + missing.join(', ') }
}

/**
 * handoff(stage, outs, roots?, item?) -> string
 * outs is keyed by OUTPUT-SCHEMA id (not seat name). Builds the hand-off block for
 * stage.output from its declared upstream outputs; empty string when none apply.
 */
function handoff(stage, outs, roots, item) {
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
      if (item && !(item.stages || []).some((s) => s.output === 'test-author-v1')) {
        const owned = []
        const add = (f) => { if (typeof f === 'string' && !owned.includes(f)) owned.push(f) }
        for (const f of p.testFiles || []) add(f)
        for (const e of p.existingTestEdits || []) if (e && e.file) add(e.file)
        if (owned.length > 0) {
          lines.push(
            `TESTS ARE YOURS: ${owned.join(', ')} - no test-author seat runs on this item; write, run and commit them yourself.`
          )
        }
      }
    }
  } else if (stage.output === 'declarations-v1') {
    const i = outs['implementer-v1']
    if (i) {
      lines.push('HANDOFF from implementer:')
      lines.push(`publicSurface: ${i.publicSurface || 'none'}`)
      lines.push(`mainFilesChanged: ${(i.mainFilesChanged || []).join(', ') || 'none'}`)
      lines.push(`docFilesChanged: ${(i.docFilesChanged || []).join(', ') || 'none'}`)
    }
    const pl = outs['planner-v1']
    lines.push(`declarationModules: ${((pl && pl.declarationModules) || []).join(', ') || 'none'}`)
    lines.push(`extractFrom: ${extractUnion(outs, roots).join(', ') || 'none'}`)
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

/** Part 15: rerun-safe check + entry protocol. */
function promptRerunAndEntry(plan, item, stage) {
  if (!stage.writes && !stage.enters) return ''
  const lines = []
  const tokenFile = seatTokenFile(plan, item, stage)
  if (stage.writes) {
    lines.push(
      `RERUN CHECK: before doing any work, run git -C ${item.worktree} log --format=%H%x09%s%x09%b ${plan.baseSha}..HEAD ` +
        `and look for a commit subject containing "[${item.short}]" with trailer "Seat: ${stage.seat}". If found, your ` +
        'own notes are already filled (verify with notes_read(select:"list", itemId, keys:[<your keys>]), which returns no ' +
        `bodies) — ${tokenFile ? 'end your seat (END SEAT, outcome done) and ' : ''}stop, do not redo the work.`
    )
    lines.push(
      'INTERRUPTED-ATTEMPT CHECK: if your own commit is absent but the worktree has uncommitted changes in files you may own, ' +
        'an earlier attempt of this seat was interrupted. FIRST snapshot those changes without touching the index or the tree: ' +
        'use a temporary GIT_INDEX_FILE (read-tree HEAD, add -A for the owned paths, write-tree, commit-tree -p HEAD) and ' +
        `update-ref refs/wip/${item.short}-${stage.seat} to the new commit. THEN review the changes against the item's notes and continue from them: ` +
        'keep what is correct, finish what is missing, fix what is wrong. Never discard them wholesale (no checkout -- ., reset --hard, clean, stash). ' +
        (plan.worktreeMode === 'shared'
          ? 'This is a shared worktree: only uncommitted changes in files you own (your locked or declared files) count; never touch a foreign uncommitted file, report it instead of adopting it. '
          : 'This is a per-item worktree: uncommitted changes belong to you once earlier writing seats of this item have committed. ') +
        'Report interruptedAttempt: true and the snapshot ref in wipRef in the envelope.'
    )
  }
  if (stage.enters) {
    lines.push(`ENTRY: call items_read(select:"context", itemId:"${item.id}") first and read item.role.`)
    if (plan.entryMode === 'pre-entered') {
      lines.push('entryMode is pre-entered: the coordinator already moved this item to work. Verify role is "work" and never call advance.')
      lines.push(
        'ENTRY REPORT (required): role "work" -> entry {"applied":false,"alreadyInPhase":true}; ' +
          'else entry {"applied":false,"alreadyInPhase":false} and status "stopped".',
      )
    } else {
      lines.push('role "work" -> do not advance; report entry.alreadyInPhase.')
      lines.push(
        `role "queue" -> call advance(ops:[{op:"transition", itemId:"${item.id}", trigger:"start"}]) exactly once` +
          `${tokenFile ? ', with your seat token' : ''}.`
      )
      lines.push('Rerun-safe entry: never send start from work — only from queue.')
      lines.push(
        'ENTRY REPORT (required): read data.results[0]. ok -> entry {applied:true, newRole, previousRole, unblockedItems: ' +
          'unblocked[].itemId}. An error element -> entry {applied:false, errorCode: error.code, previousRole: ' +
          'error.detail.role (gate_blocked) or the role context showed, missingNotes: error.detail.missing[].key, blockers: ' +
          'error.detail.blockers[].id, contendedResources: from error.detail.resources[].name}; retried true only after an ' +
          'unavailable retry. gate_blocked with detail.role "work" means already in phase. Role "work" at context: entry ' +
          '{"applied":false,"alreadyInPhase":true}.',
      )
    }
  }
  return lines.join('\n')
}

/** Part 16: deviation protocol, writing seats only. */
function promptDeviation(plan, item, stage) {
  if (!stage.writes) return ''
  const head =
    'DEVIATION: when a queue note (task-scope, specification, diagnosis) or a fix-decisions note is wrong, impossible or ' +
    'costlier than an alternative, implement only what the conflict does not touch, never the alternative, and record the ' +
    'conflict under a "## Deviations requested" heading in your own work note (the decision, why it fails, your proposed ' +
    'alternative, evidence with file:line).'
  if (!seatTokenFile(plan, item, stage)) {
    return `${head} Then return status "stopped", reason "awaiting_decision"; the orchestrator raises the decision.`
  }
  return [
    head,
    `Then raise it with your seat token: runs_write(ops:[{op:"raise", class:"deviation", block:true, dedupeKey:"${item.short}:${stage.seat}:dev-<n>", ` +
      `question, options:[{key, label, consequence}], recommended, itemId:"${item.id}"}]) where n counts your raises on this item from 1.`,
    'Return status "stopped", reason "awaiting_decision", decisionId = the raised decision\'s id. A stall is never a reason to raise.',
  ].join('\n')
}

/** Part 17: end the seat, token holders only. */
function promptEndSeat(plan, item, stage) {
  if (!seatTokenFile(plan, item, stage)) return ''
  return (
    'END SEAT: as your last task-orchestrator call, after your commit and notes, end your seat with your seat token: ' +
    'runs_write(ops:[{op:"end_seat", outcome, summary}]); outcome "done" for status done, "failed" for stopped, ' +
    '"abandoned" for deferred; summary at most 500 characters and never the token. Set seatEnded: true in the envelope ' +
    'when it succeeded (false when it failed). The token stops working after it.'
  )
}

/** Part 18: return contract. */
function promptReturn(stage, item) {
  const lines = [
    `RETURN: return the structured envelope (${stage.output}); notes are the report. Each notes[] entry is {key, chars?, warning?}.`,
  ]
  if (stage.output === 'planner-v1') {
    lines.push('Emit StructuredOutput exactly once, as your final action; a second call replaces the first.')
    if (item && item.stages.some((s) => s.output === 'test-author-v1')) {
      lines.push(
        'declarationModules: list every repo-relative module whose behavior a test-plan scenario oracle or harness ' +
          'depends on, mirroring the declarationModules line of the test-plan note.'
      )
    }
  }
  if (stage.output === 'declarations-v1') {
    lines.push(
      'modules: list the repo-relative paths you actually extracted declarations for, one entry per module ' +
        '(the extractFrom paths, plus any further module you needed).'
    )
  }
  if (stage.output === 'test-author-v1') {
    lines.push(
      'missingDeclaration and breachDisclosure: exactly "none" (optionally followed by a dash and a note) ' +
        'when there is nothing to disclose.'
    )
  }
  return lines.join('\n')
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
 * Joins the non-empty prompt parts (§5.3) with "\n\n"; each part is a pure function of
 * plan/item/stage/outs. No rule text lives here — rules are fetched by key at runtime via the
 * config tool's rules op (part 6). No seat token is ever inlined: part 4 names its file.
 */
function seatPrompt(plan, item, stage, outs) {
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
    promptTools(plan, item, stage),
    promptSeatToken(plan, item, stage),
    promptOwnedNotes(stage, item),
    promptCarryIns(stage, item),
    promptRules(plan, stage),
    promptDriftPin(item, stage),
    promptResults(),
    promptConfigRetry(),
    promptErrors(),
    promptInlineBodies(),
    promptCommitForm(item, stage),
    promptVerify(plan, item, stage, outsByOutput),
    handoff(stage, outsByOutput, itemRoots(plan, item), item),
    promptRerunAndEntry(plan, item, stage),
    promptDeviation(plan, item, stage),
    promptEndSeat(plan, item, stage),
    promptReturn(stage, item),
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
      results.push({ short: h.short, output: null, roots: itemRoots(plan, h) })
      continue
    }
    const v = await milestones.get(h.id, plannerStage.seat)
    results.push({ short: h.short, output: v && v.status === 'done' ? v.output : null, roots: itemRoots(plan, h) })
  }
  return results
}

async function callSeat(plan, item, stage, outs, deps) {
  const prompt = seatPrompt(plan, item, stage, outs)
  const schema = envelopeSchema(stage.output, plan.outputSchemas, { enters: stage.enters })
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
  env = stripTokens(env)
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

  let currentIndex = 0
  let abnormal = false
  try {
    for (let i = 0; i < item.stages.length; i++) {
      currentIndex = i
      const stage = item.stages[i]
      const gap = declarationGap(plan, item, stage, outs)
      if (gap) {
        stages.push({
          seat: stage.seat, status: gap.status, reason: gap.reason, modelReported: '',
          agentTypeUsed: null, agentTypeFallback: false, notes: [],
          commits: { pre: '', post: '' }, files: [],
        })
        deps.milestones.settle(item.id, stage.seat, { status: gap.status, reason: gap.reason, output: null })
        result.status = gap.status
        result.reason = gap.reason
        deps.milestones.releaseFrom(item, i + 1)
        return result
      }
      const keys = lockKeysFor(item, stage, outs, plan)
      const stageResult = await deps.locks.withLocks(keys, () => callSeat(plan, item, stage, outs, deps))
      const env = stageResult.env
      outs[stage.seat] = env ? env.output : undefined

      const mapped = mapStageResult(stage, env, plan.entryMode, item)
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
        const overlap = overlapDeferral({ short: item.short, output: env.output, roots: itemRoots(plan, item) }, higher, plan.worktreeMode, (plan.project && plan.project.appendOnlyFiles) || [])
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
  } catch (e) {
    abnormal = true
    result.status = 'stopped'
    result.reason = `agent threw: ${e && e.message}`
    return result
  } finally {
    if (abnormal) deps.milestones.releaseFrom(item, currentIndex)
  }
}

/**
 * runPlan(plan, deps) -> {contract, started:true, runId, serverRunId, items, refused, deferred}
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
  const full = {
    contract: 'implement-wave/result-v1',
    started: true,
    runId: plan.runId,
    serverRunId: plan.serverRunId || null,
    items: results,
    refused: pf.refused,
    deferred: plan.deferred || [],
  }
  return plan.altitudeSafe === true ? altitudeSafeResult(full) : full
}

const SAFE_REASON_MAX = 120
const SAFE_RED_PROOF_MAX = 200
const SAFE_OUTPUT_LISTS = [
  'mainFiles', 'docFiles', 'testFiles', 'declarationModules',
  'mainFilesChanged', 'docFilesChanged', 'testFilesChanged',
]

function safeText(v, max) {
  return typeof v === 'string' ? v.slice(0, max) : ''
}

function safeOutput(out) {
  if (!out || typeof out !== 'object' || Array.isArray(out)) return null
  const kept = {}
  for (const k of SAFE_OUTPUT_LISTS) {
    if (Array.isArray(out[k])) kept[k] = out[k].filter((v) => typeof v === 'string')
  }
  if (Array.isArray(out.existingTestEdits)) {
    kept.existingTestEdits = out.existingTestEdits
      .filter((e) => e && typeof e === 'object' && typeof e.file === 'string')
      .map((e) => ({ file: e.file }))
  }
  if ('proceed' in out) kept.proceed = out.proceed
  if (typeof out.redProofShape === 'string') kept.redProofShape = out.redProofShape.slice(0, SAFE_RED_PROOF_MAX)
  return kept
}

function safeStage(st) {
  const s = st || {}
  const commits = s.commits && typeof s.commits === 'object' ? s.commits : {}
  return {
    seat: s.seat,
    status: s.status,
    reason: safeText(s.reason, SAFE_REASON_MAX),
    modelReported: typeof s.modelReported === 'string' ? s.modelReported : '',
    agentTypeUsed: typeof s.agentTypeUsed === 'string' ? s.agentTypeUsed : null,
    agentTypeFallback: !!s.agentTypeFallback,
    commits: { pre: typeof commits.pre === 'string' ? commits.pre : '', post: typeof commits.post === 'string' ? commits.post : '' },
    files: Array.isArray(s.files) ? s.files.filter((f) => typeof f === 'string') : [],
    notes: Array.isArray(s.notes)
      ? s.notes.filter((n) => n && typeof n === 'object').map((n) => ({ key: n.key, actor: n.actor, chars: n.chars }))
      : [],
  }
}

function safeEntry(e) {
  const x = e || {}
  return { id: x.id, short: x.short, reason: safeText(x.reason, SAFE_REASON_MAX) }
}

/**
 * altitudeSafeResult(full) -> result-v1 scrubbed to counts and pointers, plus `rollup`.
 * Keeps the item skeleton post-run verify and end-seats read (items[].stages, items[].outputs.<seat>
 * file lists) and drops every prose field (decisions, corrections, edits, deviations, publicSurface).
 * Reasons are cut to 120 characters; the full prose lives in the seats' notes.
 */
function altitudeSafeResult(full) {
  const items = (full.items || []).map((it) => {
    const item = { id: it.id, short: it.short, status: it.status }
    if (it.schemaFree) item.schemaFree = true
    item.reason = safeText(it.reason, SAFE_REASON_MAX)
    item.stages = (it.stages || []).map(safeStage)
    const outputs = {}
    for (const [seat, out] of Object.entries(it.outputs || {})) outputs[seat] = safeOutput(out)
    item.outputs = outputs
    return item
  })
  const refused = (full.refused || []).map(safeEntry)
  const deferred = (full.deferred || []).map(safeEntry)
  const count = (status) => items.filter((it) => it.status === status).length
  return {
    contract: 'implement-wave/result-v1',
    started: true,
    altitudeSafe: true,
    runId: full.runId,
    serverRunId: full.serverRunId || null,
    rollup: { items: items.length, done: count('done'), stopped: count('stopped'), deferred: count('deferred'), refused: refused.length },
    items,
    refused,
    deferred,
  }
}
// @core-end
const R = normalizeArgs(args)
if (!R.ok) return { started: false, reason: R.reason }
return await runPlan(R.plan, { agent, parallel, phase, log })
