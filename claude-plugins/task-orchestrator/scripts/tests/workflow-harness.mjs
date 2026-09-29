// ESM test harness for workflows/implement-wave.js. No dependencies. Node 22.
import { readFileSync } from 'node:fs'
import vm from 'node:vm'

const CORE_BEGIN = '// @core-begin'
const CORE_END = '// @core-end'

const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor

export function scriptText(path) {
  return readFileSync(path, 'utf8')
}

export function coreSlice(path) {
  const text = scriptText(path)
  const start = text.indexOf(CORE_BEGIN)
  const end = text.indexOf(CORE_END)
  if (start === -1 || end === -1 || end < start) {
    throw new Error(`markers not found in ${path}`)
  }
  return text.slice(start + CORE_BEGIN.length, end)
}

export function loadMeta(path) {
  const text = scriptText(path)
  const metaStart = text.indexOf('export const meta')
  const coreStart = text.indexOf(CORE_BEGIN)
  if (metaStart === -1) throw new Error(`meta not found in ${path}`)
  const metaSrc = text.slice(metaStart, coreStart === -1 ? undefined : coreStart).replace(/^export\s+/, '')
  const context = vm.createContext({})
  const script = new vm.Script(`${metaSrc}\nmeta`)
  return script.runInContext(context)
}

export function loadScript(path) {
  const text = scriptText(path)
  const body = text.replace(/^export\s+const\s+meta/, 'const meta')
  const runFn = new AsyncFunction('agent', 'parallel', 'pipeline', 'phase', 'log', 'args', 'budget', 'workflow', body)
  return {
    run(globals = {}) {
      return runFn(
        globals.agent, globals.parallel, globals.pipeline, globals.phase,
        globals.log, globals.args, globals.budget, globals.workflow
      )
    },
  }
}

const CORE_EXPORT_NAMES = [
  'ENVELOPE_VERSION', 'OUTPUT_SCHEMAS', 'normalizeArgs', 'normalizePath', 'preflight',
  'makeMilestones', 'makeLocks', 'lockKeysFor', 'overlapDeferral', 'envelopeSchema',
  'mapEntry', 'mapStageResult', 'seatActor', 'seatPrompt', 'handoff', 'runItem', 'runPlan',
  'scanDeclarations',
]

export function loadCore(path) {
  const slice = coreSlice(path)
  const builder = new Function(`${slice}\nreturn {${CORE_EXPORT_NAMES.join(',')}};`)
  return builder()
}

function checkSchema(schema, path = 'schema') {
  if (!schema) return
  if (schema.type === 'object') {
    const required = schema.required || []
    const props = schema.properties || {}
    for (const r of required) {
      if (!(r in props)) throw new Error(`${path}: required "${r}" not in properties`)
    }
    for (const [key, sub] of Object.entries(props)) {
      checkSchema(sub, `${path}.${key}`)
    }
  }
  if (schema.type === 'array' && schema.items) {
    checkSchema(schema.items, `${path}[]`)
  }
}

function autofillValue(sub) {
  if (!sub) return null
  if (sub.enum) return sub.enum[0]
  switch (sub.type) {
    case 'boolean':
      return true
    case 'string':
      return 'none'
    case 'array':
      return []
    case 'object':
      return autofillObject(sub)
    case 'integer':
    case 'number':
      return 0
    default:
      return null
  }
}

function autofillObject(schema) {
  const out = {}
  const required = (schema && schema.required) || []
  const props = (schema && schema.properties) || {}
  for (const key of required) {
    out[key] = autofillValue(props[key] || {})
  }
  return out
}

/**
 * fakeAgent(script={}, {manual=false}={}) -> {agent, calls, clock, release, pending}
 * script[label] is an envelope, an array (per-call sequence), fn(n, prompt, opts),
 * null, or {throw: msg}. An unscripted label gets a done envelope whose output fills
 * the schema's required fields. Integer virtual clock, ticked on every start and
 * completion. manual mode defers resolution to release(); pending() lists waiting labels.
 * Rejects if opts.isolation is set or a schema breaks required⊆properties.
 */
export function fakeAgent(script = {}, { manual = false } = {}) {
  const calls = []
  let tick = 0
  const pendingByKey = new Map()

  function nextTick() {
    tick += 1
    return tick
  }

  async function agent(prompt, opts = {}) {
    if (opts.isolation) throw new Error('fakeAgent: opts.isolation is not allowed')
    checkSchema(opts.schema)
    const label = opts.label
    const call = { label, opts, prompt, t0: nextTick(), t1: null }
    calls.push(call)
    const n = calls.filter((c) => c.label === label).length - 1
    const scripted = script[label]

    let envelope
    if (Array.isArray(scripted)) {
      envelope = scripted[Math.min(n, scripted.length - 1)]
    } else if (typeof scripted === 'function') {
      envelope = scripted(n, prompt, opts)
    } else if (scripted !== undefined) {
      envelope = scripted
    } else {
      envelope = {
        status: 'done', reason: 'ok', notes: [], commits: { pre: '', post: '' }, files: [],
        modelReported: 'fake', entry: { applied: true, newRole: 'work' },
        output: autofillObject(opts.schema && opts.schema.properties && opts.schema.properties.output),
      }
    }

    if (envelope && envelope.throw) {
      call.t1 = nextTick()
      throw new Error(envelope.throw)
    }

    if (manual) {
      return new Promise((resolve) => {
        pendingByKey.set(`${label}#${n}`, { resolve, call, envelope })
      })
    }
    call.t1 = nextTick()
    return envelope === null || envelope === undefined ? null : envelope
  }

  return {
    agent,
    calls,
    clock: { now: () => tick },
    release(label, env) {
      for (const [key, pending] of pendingByKey.entries()) {
        if (key.startsWith(`${label}#`)) {
          pending.call.t1 = nextTick()
          pending.resolve(env !== undefined ? env : pending.envelope)
          pendingByKey.delete(key)
          return
        }
      }
    },
    pending() {
      return Array.from(pendingByKey.values()).map((p) => p.call.label)
    },
  }
}

/** fakeParallel(thunks) -> Promise.all with each thunk's rejection mapped to null. */
export async function fakeParallel(thunks) {
  return Promise.all(thunks.map((t) => Promise.resolve().then(t).catch(() => null)))
}

/** planFixture({items, ...overrides}) -> a valid args-v1 object with §3.1 defaults. */
export function planFixture({ items = [], ...overrides } = {}) {
  return {
    contract: 'implement-wave/args-v1',
    runId: 'r-test-0001',
    rootId: 'root-test-0001',
    baseSha: 'e77c3e95',
    worktreeMode: 'shared',
    entryMode: 'seat',
    capabilities: { features: ['seats', 'dispatchBySeat', 'independent_of', 'rules'], phase0Hooks: true },
    items,
    deferred: [],
    ...overrides,
  }
}

/** itemFixture({short, stages, ...}) -> item defaulting to an absolute worktree. */
export function itemFixture({ short, stages: itemStages = [], ...overrides } = {}) {
  return {
    id: `item-${short}`,
    short,
    title: short,
    type: 'scratch',
    configFingerprint: 'fp-1',
    traits: [],
    worktree: `/tmp/wt-${short}`,
    stages: itemStages,
    waitsFor: [],
    ...overrides,
  }
}

function stage(overrides) {
  return Object.assign({ notes: [], writes: false, dispatch: {} }, overrides)
}

export const stages = {
  bugFixLike() {
    return [
      stage({ seat: 'planner', phase: 'queue', writes: false, output: 'planner-v1' }),
      stage({
        seat: 'implementer', phase: 'work', enters: true, writes: true,
        output: 'implementer-v1', dispatch: { agent: 'task-orchestrator:implementer' },
      }),
      stage({ seat: 'declarations-extractor', phase: 'work', inserted: true, writes: false, notes: [], output: 'declarations-v1' }),
      stage({
        seat: 'test-author', phase: 'work', writes: true, output: 'test-author-v1',
        readsExclude: ['implementation-notes', 'session-tracking'],
      }),
    ]
  },
  featureTaskLike() {
    return [
      stage({ seat: 'planner', phase: 'queue', writes: false, output: 'planner-v1' }),
      stage({
        seat: 'implementer', phase: 'work', enters: true, writes: true,
        output: 'implementer-v1', dispatch: { agent: 'task-orchestrator:implementer' },
      }),
    ]
  },
  pluginChangeLike() {
    return [
      stage({ seat: 'owner', phase: 'work', enters: true, implicitOwner: true, writes: true, output: 'implementer-v1' }),
    ]
  },
  schemaFree() {
    return [stage({ seat: 'owner', phase: 'work', enters: true, writes: true, output: 'generic-v1' })]
  },
}
