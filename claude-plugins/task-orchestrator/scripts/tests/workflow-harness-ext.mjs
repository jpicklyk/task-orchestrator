// C-owned extension of workflow-harness.mjs. Imports and wraps B1's harness; never edits it
// (phase-c-dispatch-contract.md "Harness" row; b1-dispatch-contract.md:107,175).
//
// Frozen API (phase-c-dispatch-contract.md Appendix B, "HARNESS-EXT"):
//   loadCoreNamed(path, names)
//   autoAgent(responder, {order='fifo'}={})
//   fakePipeline(items, ...stages)
//   assertBarrier(log, phaseA, phaseB)
//   scanForbiddenApis(text)
//   freeRuntimeIds(coreText)
//   ruleWindowHits(targetText, rulesDir)
//
// No dependencies. Node 22, ESM.

import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { coreSlice } from './workflow-harness.mjs'

/**
 * loadCoreNamed(path, names) -> object keyed by `names`, built the same way B1's loadCore
 * builds its object: the whole marker-delimited core slice is evaluated once as a `new
 * Function` body (so every core-region function/const is defined and can call its siblings),
 * and only the requested names are returned. Unlike B1's loadCore (hard-coded to
 * implement-wave's own core export list), this lets any script's core be reached by name —
 * needed because audit.js's core exports are not in B1's CORE_EXPORT_NAMES list.
 */
export function loadCoreNamed(path, names) {
  const slice = coreSlice(path)
  const builder = new Function(`${slice}\nreturn {${names.join(',')}};`)
  return builder()
}

// Same recursive required-subsets-properties check as B1's (private) checkSchema. Duplicated
// here deliberately: workflow-harness.mjs does not export it, and the ext must never edit that
// file to get access to it.
function checkSchemaShape(schema, path = 'schema') {
  if (!schema) return
  if (schema.type === 'object') {
    const required = schema.required || []
    const props = schema.properties || {}
    for (const r of required) {
      if (!(r in props)) throw new Error(`${path}: required "${r}" not in properties`)
    }
    for (const [key, sub] of Object.entries(props)) checkSchemaShape(sub, `${path}.${key}`)
  }
  if (schema.type === 'array' && schema.items) checkSchemaShape(schema.items, `${path}[]`)
}

/**
 * autoAgent(responder, {order='fifo'}={}) -> {agent, calls, clock}
 *
 * Unlike B1's fakeAgent (scripted per-label envelopes, unscripted labels autofilled),
 * autoAgent has ONE responder for every label: `responder(label, prompt, opts)` ->
 * value | null | Promise<value|null>. A throw (sync or async) rejects that agent() call.
 *
 * `order` governs how calls that are concurrently pending (queued in the same synchronous
 * burst, e.g. every call inside one `parallel(items.map(() => agent(...)))`) get resolved
 * relative to each other on the virtual clock:
 *   - 'fifo' (default): resolved in the order they were issued.
 *   - 'reverse': resolved LIFO. This is what a barrier-ordering test wants — if the barrier
 *     `await`s are missing, resolving out of call order surfaces it; if they are present, the
 *     later phase cannot start until `parallel` settles regardless of internal resolution
 *     order, so barrier correctness is unaffected by which order setting is used.
 *
 * Rejects (same as B1's fakeAgent) if opts.isolation is set, or if opts.schema is a JSON
 * Schema object whose `required` is not a subset of `properties` (recursively) — a structural
 * check on the schema passed to agent(), not a validation of the responder's return value.
 */
export function autoAgent(responder, { order = 'fifo' } = {}) {
  const calls = []
  let tick = 0
  const nextTick = () => (tick += 1)

  let queue = []
  let scheduled = false

  function scheduleDrain() {
    if (scheduled) return
    scheduled = true
    queueMicrotask(drain)
  }

  async function drain() {
    scheduled = false
    const batch = queue
    queue = []
    const ordered = order === 'reverse' ? batch.slice().reverse() : batch
    for (const entry of ordered) {
      try {
        const value = await responder(entry.call.label, entry.call.prompt, entry.call.opts)
        entry.call.t1 = nextTick()
        entry.resolve(value === undefined ? null : value)
      } catch (err) {
        entry.call.t1 = nextTick()
        entry.reject(err instanceof Error ? err : new Error(String(err)))
      }
    }
  }

  async function agent(prompt, opts = {}) {
    if (opts.isolation) throw new Error('autoAgent: opts.isolation is not allowed')
    checkSchemaShape(opts.schema)
    const call = { label: opts.label, opts, prompt, t0: nextTick(), t1: null }
    calls.push(call)
    return new Promise((resolve, reject) => {
      queue.push({ call, resolve, reject })
      scheduleDrain()
    })
  }

  return { agent, calls, clock: { now: () => tick } }
}

/**
 * fakePipeline(items, ...stages) -> Promise<any[]>
 * Per item, runs `stages` in sequence: `prev = await stage(prev, item, index)`, starting
 * `prev` undefined. A throwing stage (sync or async) makes that item's result `null` and
 * skips its remaining stages. Items run independently/concurrently — there is no barrier
 * between items, and none between one item's stages and another's.
 */
export async function fakePipeline(items, ...stages) {
  return Promise.all(
    items.map(async (item, index) => {
      let prev
      for (const stage of stages) {
        try {
          prev = await stage(prev, item, index)
        } catch {
          return null
        }
      }
      return prev
    })
  )
}

/**
 * assertBarrier(log, phaseA, phaseB)
 * `log` is a `calls` array from autoAgent/fakeAgent (each call carrying `opts.phase`, `t0`,
 * `t1`). Throws unless BOTH phases have at least one call AND every phaseA call's `t1` is
 * strictly less than every phaseB call's `t0` (equivalently: max(phaseA.t1) < min(phaseB.t0)).
 */
export function assertBarrier(log, phaseA, phaseB) {
  const aCalls = log.filter((c) => c.opts && c.opts.phase === phaseA)
  const bCalls = log.filter((c) => c.opts && c.opts.phase === phaseB)
  if (aCalls.length === 0) throw new Error(`assertBarrier: phase "${phaseA}" has 0 calls`)
  if (bCalls.length === 0) throw new Error(`assertBarrier: phase "${phaseB}" has 0 calls`)
  const maxA = Math.max(...aCalls.map((c) => c.t1))
  const minB = Math.min(...bCalls.map((c) => c.t0))
  if (!(maxA < minB)) {
    throw new Error(
      `assertBarrier: phase "${phaseA}" (max t1=${maxA}) does not precede phase "${phaseB}" (min t0=${minB})`
    )
  }
}

const FORBIDDEN_APIS = ['Date.now(', 'Math.random(', 'new Date()', 'import(', 'require(']

/** scanForbiddenApis(text) -> the subset of FORBIDDEN_APIS present as a literal substring of `text`. */
export function scanForbiddenApis(text) {
  return FORBIDDEN_APIS.filter((api) => text.includes(api))
}

const RUNTIME_GLOBAL_NAMES = ['agent', 'parallel', 'pipeline', 'phase', 'log', 'args', 'budget', 'workflow']

function stripCommentsAndStrings(text) {
  let out = text.replace(/\/\*[\s\S]*?\*\//g, (m) => ' '.repeat(m.length))
  out = out.replace(/\/\/[^\n]*/g, (m) => ' '.repeat(m.length))
  out = out.replace(/`(?:\\.|[^`\\])*`/g, (m) => '`' + ' '.repeat(Math.max(0, m.length - 2)) + '`')
  out = out.replace(/"(?:\\.|[^"\\])*"/g, (m) => '"' + ' '.repeat(Math.max(0, m.length - 2)) + '"')
  out = out.replace(/'(?:\\.|[^'\\])*'/g, (m) => "'" + ' '.repeat(Math.max(0, m.length - 2)) + "'")
  return out
}

// A name is "locally bound" in a block of text when that block declares it as a function
// parameter (plain or object-destructured) or via a const/let/var (plain or destructured)
// binding — e.g. `async function runAudit(plan, deps)` unpacking `deps` internally via
// `const {agent, parallel, pipeline, phase, log} = deps`. Once bound this way, subsequent bare
// uses of the name within the SAME block are references to that local, not to an ambient
// runtime global, and must not be reported as free.
function isLocallyBound(blockText, name) {
  const destructureAssign = new RegExp(`\\b(?:const|let|var)\\s*\\{[^{}]*\\b${name}\\b[^{}]*\\}\\s*=`)
  if (destructureAssign.test(blockText)) return true
  const plainAssign = new RegExp(`\\b(?:const|let|var)\\s+${name}\\b`)
  if (plainAssign.test(blockText)) return true
  const paramList = new RegExp(`\\([^()]*\\b${name}\\b[^()]*\\)\\s*(?:=>|\\{)`)
  if (paramList.test(blockText)) return true
  return false
}

// Splits `text` into one block per top-level `function`/`async function` declaration (its
// signature + balanced-brace body, nested functions included as part of that text) plus the
// text between/around them, so local-binding exemptions (isLocallyBound) apply only within the
// declaring function's own scope — a name bound in one core function does not exempt a stray
// free use of the same name in a different core function.
function splitIntoFunctionBlocks(text) {
  const funcRe = /\b(?:async\s+function|function)\s*\*?\s*[A-Za-z_$][A-Za-z0-9_$]*\s*\([^)]*\)\s*\{/g
  const blocks = []
  let lastIndex = 0
  let m
  while ((m = funcRe.exec(text))) {
    blocks.push(text.slice(lastIndex, m.index))
    const bodyOpenBrace = m.index + m[0].length - 1
    let depth = 1
    let i = bodyOpenBrace + 1
    while (i < text.length && depth > 0) {
      if (text[i] === '{') depth += 1
      else if (text[i] === '}') depth -= 1
      i += 1
    }
    const bodyEnd = i // one past the matching closing brace
    blocks.push(text.slice(m.index, bodyEnd))
    funcRe.lastIndex = bodyEnd
    lastIndex = bodyEnd
  }
  blocks.push(text.slice(lastIndex))
  return blocks
}

/**
 * freeRuntimeIds(coreText) -> sorted array of runtime-global names (from
 * RUNTIME_GLOBAL_NAMES) that appear as FREE identifiers in `coreText` after stripping
 * comments and string/template literals. A `.name` property-access occurrence (e.g.
 * `deps.agent`) is never free. A bare occurrence (e.g. a call `log(...)`, or the bare
 * identifier `agent`) is free UNLESS it is within the same top-level function block as a local
 * binding for that name (a destructured/plain param or const/let/var) — see isLocallyBound.
 */
export function freeRuntimeIds(coreText) {
  const stripped = stripCommentsAndStrings(coreText)
  const hits = new Set()
  const re = /\.\s*[A-Za-z_$][A-Za-z0-9_$]*|[A-Za-z_$][A-Za-z0-9_$]*/g
  for (const block of splitIntoFunctionBlocks(stripped)) {
    const bound = new Set(RUNTIME_GLOBAL_NAMES.filter((name) => isLocallyBound(block, name)))
    re.lastIndex = 0
    let m
    while ((m = re.exec(block))) {
      const tok = m[0]
      if (tok.startsWith('.')) continue // property access — the alternation already consumed it whole
      if (bound.has(tok)) continue
      if (RUNTIME_GLOBAL_NAMES.includes(tok)) hits.add(tok)
    }
  }
  return Array.from(hits).sort()
}

// S12 tokenizer, identical to the one frozen in b1-dispatch-contract.md Appendix A, applied
// to both rule files and the target text:
//   remove ``` fences; replace backticks, **, __ and a leading # with spaces; remove leading
//   list markers (- * + or N.); replace [t](u) with t; lowercase; split on whitespace; trim
//   leading/trailing punctuation per token (keep < >); drop empty tokens.
function tokenizeS12(text) {
  let t = text.replace(/```/g, ' ')
  t = t.replace(/`/g, ' ')
  t = t.replace(/\*\*/g, ' ')
  t = t.replace(/__/g, ' ')
  t = t
    .split('\n')
    .map((line) => {
      let l = line.replace(/^(\s*)#+/, '$1 ')
      l = l.replace(/^(\s*)[-*+]\s+/, '$1 ')
      l = l.replace(/^(\s*)\d+\.\s+/, '$1 ')
      return l
    })
    .join('\n')
  t = t.replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
  t = t.toLowerCase()
  const tokens = []
  for (const raw of t.split(/\s+/)) {
    if (!raw) continue
    const trimmed = raw.replace(/^[^\w<>]+|[^\w<>]+$/g, '')
    if (trimmed) tokens.push(trimmed)
  }
  return tokens
}

function windowsOf(tokens, size) {
  const out = []
  for (let i = 0; i + size <= tokens.length; i++) out.push(tokens.slice(i, i + size))
  return out
}

function containsWindow(targetTokens, window) {
  outer: for (let i = 0; i + window.length <= targetTokens.length; i++) {
    for (let j = 0; j < window.length; j++) {
      if (targetTokens[i + j] !== window[j]) continue outer
    }
    return true
  }
  return false
}

/**
 * ruleWindowHits(targetText, rulesDir) -> {hits, windows}
 * Tokenizes every `.md` file directly under `rulesDir` and `targetText` with the S12
 * tokenizer, builds every contiguous 8-token window of each rule file, and reports (in
 * `hits`) every window that also appears as a contiguous run in the target's token stream.
 * `windows` is the total number of rule windows compared (so a caller can assert at least one
 * comparison actually happened, guarding against a silently-empty rules directory producing a
 * vacuous pass). Pointing `rulesDir` at an empty directory is the documented red-proof lever
 * for this check (b1-dispatch-contract.md Appendix A) — it needs no special-cased parameter
 * beyond `rulesDir` itself.
 */
export function ruleWindowHits(targetText, rulesDir) {
  const targetTokens = tokenizeS12(targetText)
  const files = readdirSync(rulesDir).filter((f) => f.endsWith('.md'))
  const hits = []
  let windows = 0
  for (const file of files) {
    const ruleTokens = tokenizeS12(readFileSync(join(rulesDir, file), 'utf8'))
    for (const w of windowsOf(ruleTokens, 8)) {
      windows += 1
      if (containsWindow(targetTokens, w)) hits.push({ rule: file, window: w.join(' ') })
    }
  }
  return { hits, windows }
}
