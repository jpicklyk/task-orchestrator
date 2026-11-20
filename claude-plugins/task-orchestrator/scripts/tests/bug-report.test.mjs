// bug-report.test.mjs -- scripts/lib/bug-report.mjs (runBugReport with injected deps, scrubFindings,
// fingerprintOf, typeShape), the run-planner `bug-report` subcommand (S23) and the docs the item edits.
//
// Scenario ids S1-S23 match the item's frozen `test-plan` note and its `amendments` note (A1 adds S18-S23).
// Oracles: task-scope decisions D2-D17, acceptance criteria AC1-AC14, amendment ruling A1 and the A2 user
// rulings B4 'enforce allowlists' (a server code must be in its catalog entry's errors list; a plugin tool
// must be one of the injected pluginTools; the posted call shape is top-level catalog field names with type
// names only, never a nested or map key) and N1 'never retry' (one create attempt; a failed or timed-out
// create posts nothing more that run and is recorded create-unconfirmed) and the A3 rulings B5 'no labels on
// create' (the gh issue create args carry no --label at all; a report is identified by the '[bug]' title
// prefix, the Fingerprint: line and the footer) and N3 (a call shape recorded as the whole {ops:[...]} call of
// an ops tool posts the top-level catalog fields of the element whose op equals the defect's op, else of the
// first object element) -- named in the comment above each test. This file never opens bug-report.mjs,
// retro-metrics.mjs or run-planner.mjs: only their declared exports are imported.
//
// SAFETY: the real gh is never run. Every library test injects a fake gh through `deps`; nothing there
// touches the network, the state file or GitHub. The only test that spawns the run-planner `bug-report`
// subcommand is S23, and it runs it ONLY with PATH set to an empty temporary directory and the API URL
// pointed at a local fake server; before each spawn it asserts that no gh executable exists in that PATH
// directory or in the child's working directory, and that PATH is that empty directory. A machine on which
// gh would still be reachable fails the test before the subcommand starts.
//
// The test-plan's helper names (fileBugReports, gate, state, writeBody) are not exports; the same oracles
// are exercised through runBugReport (gate = configText, which is the USER-level config text, state =
// readState/writeState, writeBody = writeTemp captured by body-file path).

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, mkdirSync, writeFileSync, existsSync, rmSync, mkdtempSync } from 'node:fs'
import { createServer } from 'node:http'
import { spawn } from 'node:child_process'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { tmpdir } from 'node:os'

import { runBugReport, scrubFindings, fingerprintOf, typeShape, CONTRACT, DEFAULT_REPO } from '../lib/bug-report.mjs'
import { defectFingerprint, defectComponent, normaliseMessage } from '../lib/retro-metrics.mjs'
import { userConfigPath } from '../../hooks/config-locator.mjs'
import { makeSandbox } from '../../hooks/tests/retro-fake-runs.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = join(HERE, '..', '..')
const REPO_ROOT = join(PLUGIN, '..', '..')

const REPO = DEFAULT_REPO
const DAY = 24 * 60 * 60 * 1000
const NOW = Date.UTC(2026, 10, 19, 12, 0, 0)
const VERSIONS = { plugin: '4.0.2', server: '4.0.1' }
const FOOTER = 'Reported automatically by task-orchestrator session-retrospective'
const UUID_A = 'aaaaaaaa-1111-4111-8111-111111111111'
const UUID_B = 'bbbbbbbb-2222-4222-8222-222222222222'

// -- fixtures ---------------------------------------------------------------------------------------

// The hooks/lib/tool-catalog.json document (data, a declared artefact). The catalog shape runBugReport
// expects is not declared (the whole document or its `tools` map), so the tests pass a superset that is
// both: the document's own keys plus every tool name at the top level. Recorded as an ambiguity.
const catalogOf = (doc) => ({ ...doc, ...doc.tools })
const CATALOG_DOC = JSON.parse(readFileSync(join(PLUGIN, 'hooks', 'lib', 'tool-catalog.json'), 'utf8'))
const CATALOG = catalogOf(CATALOG_DOC)

// A config text with the gate path extensions -> claude-code -> retrospective -> github_feedback.
// `bug` is the raw YAML scalar text for bug_reports (null omits the key); `extra` lines are appended inside
// the github_feedback block.
function cfg({ bug = 'true', repo = null, enabled = null, extra = [] } = {}) {
  const lines = ['extensions:', '  claude-code:', '    retrospective:', '      github_feedback:']
  if (enabled !== null) lines.push(`        enabled: ${enabled}`)
  if (bug !== null) lines.push(`        bug_reports: ${bug}`)
  if (repo !== null) lines.push(`        repo: ${repo}`)
  for (const l of extra) lines.push(`        ${l}`)
  return lines.join('\n') + '\n'
}

// The plugin's own hook and script file names, as the CLI would inject them (pluginTools). Every plugin
// script name a qualifying test uses is listed here; a test about the allowlist passes its own list.
const PLUGIN_TOOLS = ['run-planner.mjs', 'a.mjs', '0x-y.mjs', 'retro-hook.mjs']

// A qualifying defect by construction (A2 B4): kind defect, scope product, a listed signal, a server tool that
// is in the catalog with an op among its entries, a code that is in THAT entry's errors list (items_write
// create lists invalid_request; it is not an environment code), and non-empty message and quote.
const d = (o = {}) => ({
  kind: 'defect', scope: 'product', signal: 'tool-error', tool: 'items_write', op: 'create',
  code: 'invalid_request', message: 'Item title is required', quote: 'raw quote text', ...o,
})
// Distinct defects: a distinct letters-only word in the message (digit runs are normalised away, so the word
// carries the difference), hence distinct fingerprints. Tool, op and code stay inside the catalog entry.
const WORDS = ['alpha', 'bravo', 'charlie', 'delta', 'echo', 'foxtrot', 'golf', 'hotel', 'india', 'juliet', 'kilo', 'lima',
  'mike', 'november', 'oscar']
const dn = (i, o = {}) => d({ message: `Item title is required ${WORDS[i]}`, ...o })
// A qualifying plugin-script defect (no catalog involved; its tool is one of PLUGIN_TOOLS).
const script = (o = {}) => d({ signal: 'script-failure', tool: 'run-planner.mjs', op: 'bug-report', code: 'exit:2', ...o })
// Fingerprint oracle: defectFingerprint of the inputs with the component from defectComponent(tool).
const fpOf = (x) => defectFingerprint({ component: defectComponent(x.tool), tool: x.tool, op: x.op, code: x.code, message: x.message })

const flag = (args, f) => args.reduce((acc, a, i) => (a === f ? [...acc, args[i + 1]] : acc), [])
const callsOf = (calls, a, b) => calls.filter((c) => c[0] === a && c[1] === b)
const hit = (o = {}) => ({ number: 7, url: `https://github.com/${REPO}/issues/7`, state: 'OPEN', stateReason: null, closedAt: null, labels: [], body: '', ...o })
const ok = (stdout = '') => ({ status: 0, stdout })
const fail = () => ({ status: 1, stdout: '' })
const searchedFp = (args) => args[args.indexOf('--search') + 1].split(' ')[0]
// A3 B5: true when a gh argument list carries a label in any spelling (--label, --label=x, -l, -lx) or either
// label name the earlier versions used.
const hasLabel = (args) => args.some((a) => /^(--label|-l)/.test(a) || a === 'auto-reported' || a === 'bug')

// Fake gh keyed on args[0] + ' ' + args[1]. An override is a {status, stdout} or a function (args, nth).
// o.failTemp(text) -> true makes writeTemp throw for that body.
function makeEnv(o = {}) {
  const calls = []
  const temps = new Map()
  const writes = []
  const counts = {}
  let stateReads = 0
  const defaults = {
    'auth status': () => ok(),
    'issue list': () => ok('[]'),
    'issue create': (_a, n) => ok(`Creating issue...\nhttps://github.com/${REPO}/issues/${100 + n}\n`),
    'issue comment': () => ok(),
    'release list': () => ok('[]'),
  }
  const gh = (args) => {
    calls.push(args.slice())
    const key = `${args[0]} ${args[1]}`
    counts[key] = (counts[key] ?? 0) + 1
    const h = o[key] ?? defaults[key]
    if (h === undefined) return fail()
    return typeof h === 'function' ? h(args, counts[key]) : h
  }
  const deps = {
    gh,
    now: () => o.now ?? NOW,
    readState: () => { stateReads++; return o.state ?? null },
    writeState: (s) => { writes.push(JSON.parse(JSON.stringify(s))); return o.writeOk ?? true },
    writeTemp: (text) => {
      if (o.failTemp && o.failTemp(text)) throw new Error('disk full')
      const p = `fake-temp/body-${temps.size + 1}.md`
      temps.set(p, text)
      return p
    },
    removeTemp: () => {},
  }
  return { calls, temps, writes, deps, stateReads: () => stateReads, bodyOf: (args) => temps.get(flag(args, '--body-file')[0]) }
}

// o.catalog and o.pluginTools may be given explicitly (null and undefined included); absent they default to
// the real catalog and PLUGIN_TOOLS.
async function run(defects, o = {}) {
  const env = makeEnv(o)
  const catalog = 'catalog' in o ? o.catalog : CATALOG
  const pluginTools = 'pluginTools' in o ? o.pluginTools : PLUGIN_TOOLS
  const doc = await runBugReport({
    defects, configText: o.configText ?? cfg(), denyNames: o.denyNames ?? [], versions: o.versions ?? VERSIONS, catalog, pluginTools,
    deps: env.deps,
  })
  return { doc, ...env }
}

// A list handler answering an open hit per searched fingerprint, with the fingerprint in its body.
const openHitPerFp = () => (args) => {
  const fp = searchedFp(args)
  return ok(JSON.stringify([hit({ number: 50, body: `Fingerprint: ${fp}` })]))
}

// The state schema's `at` encoding is not declared (task-scope D13 gives only {seen: {fp: {at, url}}}).
// atOf(ms) encodes a timestamp the way a state written by this module does (number or ISO string), taken
// from one probe write at NOW; only the encoding is learned that way, never any behaviour. Recorded as an
// ambiguity in test-manifest. Defined before any test so no test can run ahead of it.
const probe = await run([d()])
const WRITTEN_AT = probe.writes.at(-1).seen[fpOf(d())].at
const atOf = (ms) => (typeof WRITTEN_AT === 'number' ? ms : new Date(ms).toISOString())

// -- S1 ---------------------------------------------------------------------------------------------
// Oracle: AC5, D7, D9 (comment lines), D15 (result shape and line); A1 B1 (repo fixed).
test('S1 open hit with empty state: one seen-again comment with both versions and the fingerprint, no create', async () => {
  const x = d()
  const F = fpOf(x)
  const url = `https://github.com/${REPO}/issues/7`
  const r = await run([x], { 'issue list': ok(JSON.stringify([hit({ body: `Fingerprint: ${F}` })])) })
  const comments = callsOf(r.calls, 'issue', 'comment')
  assert.equal(comments.length, 1)
  assert.equal(comments[0][2], '7')
  assert.deepEqual(flag(comments[0], '--repo'), [REPO])
  assert.equal(callsOf(r.calls, 'issue', 'create').length, 0)
  const body = r.bodyOf(comments[0])
  assert.match(body, /seen again/)
  assert.ok(body.includes('4.0.2') && body.includes('4.0.1'), 'both versions')
  assert.ok(body.includes(`Fingerprint: ${F}`))
  assert.ok(body.includes(FOOTER))
  assert.equal(r.doc.contract, 'run-wave/bug-report-v1')
  assert.equal(CONTRACT, 'run-wave/bug-report-v1')
  assert.equal(r.doc.gate, 'ran')
  assert.equal(r.doc.repo, REPO)
  assert.equal(r.doc.results[0].fingerprint, F)
  assert.equal(r.doc.results[0].action, 'commented')
  assert.equal(r.doc.results[0].url, url)
  assert.equal(r.doc.results[0].line, `bug-report ${F}: commented ${url}`)
  assert.equal(r.writes.at(-1).seen[F].url, url)
})

// -- S2 ---------------------------------------------------------------------------------------------
// Oracle: AC4, D5 (search shape), D6 (create shape), D13 (state), D15 (line, gh auth first); A3 B5 user ruling
// 2026-11-19 'no labels on create': the create args carry no --label at all, and a report is identified by the
// '[bug]' title prefix, the Fingerprint: line and the footer.
test('S2 no hit: one create with a body file and no label, filed with the url, state records it', async () => {
  const x = d()
  const F = fpOf(x)
  const url = `https://github.com/${REPO}/issues/101`
  const r = await run([x])
  assert.deepEqual(r.calls[0].slice(0, 2), ['auth', 'status'])
  const lists = callsOf(r.calls, 'issue', 'list')
  assert.equal(lists.length, 1)
  assert.deepEqual(flag(lists[0], '--search'), [`${F} in:body`])
  assert.deepEqual(flag(lists[0], '--repo'), [REPO])
  assert.deepEqual(flag(lists[0], '--state'), ['all'])
  const creates = callsOf(r.calls, 'issue', 'create')
  assert.equal(creates.length, 1)
  assert.deepEqual(flag(creates[0], '--repo'), [REPO])
  assert.deepEqual(flag(creates[0], '--label'), [])
  assert.equal(hasLabel(creates[0]), false, `a create carries no label: ${JSON.stringify(creates[0])}`)
  assert.equal(flag(creates[0], '--body-file').length, 1)
  assert.equal(flag(creates[0], '--title').length, 1)
  assert.equal(callsOf(r.calls, 'issue', 'comment').length, 0)
  assert.equal(r.doc.results[0].action, 'filed')
  assert.equal(r.doc.results[0].url, url)
  assert.equal(r.doc.results[0].line, `bug-report ${F}: filed ${url}`)
  assert.equal(r.writes.at(-1).seen[F].url, url)
  // B5: with no label, the report is identified by the title prefix, the Fingerprint line and the footer.
  assert.ok(flag(creates[0], '--title')[0].startsWith('[bug] '), 'title prefix')
  const lines = r.bodyOf(creates[0]).split(/\r?\n/)
  assert.ok(lines.includes(`Fingerprint: ${F}`), 'Fingerprint line')
  assert.equal(lines.filter((l) => l.trim() !== '').at(-1), FOOTER, 'footer')
})

test('S2 no create carries a label whichever defect it files: every create of a three-defect run is unlabelled', async () => {
  const defects = [dn(1), dn(2), dn(3)]
  const r = await run(defects)
  const creates = callsOf(r.calls, 'issue', 'create')
  assert.equal(creates.length, 3)
  for (const c of creates) {
    assert.equal(hasLabel(c), false, JSON.stringify(c))
    assert.deepEqual(flag(c, '--label'), [])
    assert.ok(flag(c, '--title')[0].startsWith('[bug] '))
  }
})

test('S2 the next run after an unconfirmed create files the defect unlabelled too', async () => {
  const x = d()
  const F = fpOf(x)
  const r = await run([x], { state: { seen: { [F]: { at: atOf(NOW - 3600000), url: null, status: 'create-unconfirmed' } } } })
  const creates = callsOf(r.calls, 'issue', 'create')
  assert.equal(creates.length, 1)
  assert.equal(hasLabel(creates[0]), false, JSON.stringify(creates[0]))
})

// -- S3 ---------------------------------------------------------------------------------------------
// Oracle: AC7, A1 R2 (structured fields only: component, tool, op, error code, plugin and server versions,
// call shape, fingerprint; title `[bug] <component>: <tool> <op>: <code>`; message only feeds the
// fingerprint; expected and actual leave the input), D9 (line order, exact footer).
const MSG = 'Zorblax frobnicated the quuxwidget'
const EXPECTED = 'Gronkle should have succeeded'
const ACTUAL = 'Snarfblat came back instead'
const FREE_TEXT = [MSG, EXPECTED, ACTUAL, 'zorblax', 'frobnicated', 'quuxwidget', 'gronkle', 'snarfblat', 'SECRETQUOTEZ', 'obs-f6ec543b']
const absentFree = (text, where) => {
  for (const w of FREE_TEXT) assert.ok(!text.toLowerCase().includes(w.toLowerCase()), `${where} must not hold ${w}`)
}

test('S3 title and body hold exactly the structured fields in order, the exact footer, a typed call shape and no free text', async () => {
  const x = d({
    message: MSG, quote: 'SECRETQUOTEZ', observation: 'obs-f6ec543b', expected: EXPECTED, actual: ACTUAL,
    callShape: { title: 'x', priority: 'high', complexity: 3 },
  })
  const F = fpOf(x)
  const comp = defectComponent(x.tool)
  const r = await run([x])
  const create = callsOf(r.calls, 'issue', 'create')[0]
  const title = flag(create, '--title')[0]
  assert.equal(title, `[bug] ${comp}: items_write create: invalid_request`)
  assert.ok(!title.includes(F), 'the title holds no fingerprint')
  const body = r.bodyOf(create)
  const lines = body.split(/\r?\n/)
  const at = (prefix) => lines.findIndex((l) => l.startsWith(prefix))
  const expectLine = (l) => assert.ok(lines.includes(l), `missing line: ${l}`)
  expectLine('## Defect')
  expectLine(`- Component: ${comp}`)
  expectLine('- Plugin version: 4.0.2')
  expectLine('- Server version: 4.0.1')
  expectLine('- Error code: invalid_request')
  expectLine(`Fingerprint: ${F}`)
  expectLine('---')
  const order = ['## Defect', '- Component:', '- Plugin version:', '- Server version:', '- Tool and op:', '- Error code:',
    '- Call shape:', 'Fingerprint:', '---']
  const idx = order.map(at)
  assert.ok(idx.every((i) => i >= 0), `all lines present: ${JSON.stringify(idx)}`)
  assert.deepEqual([...idx].sort((a, b) => a - b), idx, 'D9 order')
  const toolOp = lines[at('- Tool and op:')]
  assert.ok(toolOp.includes('items_write') && toolOp.includes('create'))
  const shapeLine = lines[at('- Call shape:')]
  const m = shapeLine.match(/^- Call shape: `(.*)`$/)
  assert.ok(m, `call shape line: ${shapeLine}`)
  assert.deepEqual(JSON.parse(m[1]), { title: 'string', priority: 'string', complexity: 'number' })
  for (const prefix of ['- Message', 'Expected:', 'Actual:']) assert.equal(at(prefix), -1, `no ${prefix} line`)
  const nonblank = lines.filter((l) => l.trim() !== '')
  assert.equal(nonblank.at(-1), FOOTER)
  assert.equal(nonblank.at(-2), '---')
  absentFree(title, 'title')
  absentFree(body, 'body')
  absentFree(JSON.stringify(create), 'create args')
  assert.ok(!body.includes(normaliseMessage(x.message)), 'not the normalised message')
})

test('S3 the seen-again comment holds no message, expected, actual, quote or observation id', async () => {
  const x = d({ message: MSG, quote: 'SECRETQUOTEZ', observation: 'obs-f6ec543b', expected: EXPECTED, actual: ACTUAL })
  const r = await run([x], { 'issue list': ok(JSON.stringify([hit({ body: `Fingerprint: ${fpOf(x)}` })])) })
  const c = callsOf(r.calls, 'issue', 'comment')[0]
  absentFree(r.bodyOf(c), 'comment')
  absentFree(JSON.stringify(c), 'comment args')
})

test('S3 probes: unknown server version and absent call shape read the fallbacks', async () => {
  const r = await run([d()], { versions: { plugin: '4.0.2' } })
  const lines = r.bodyOf(callsOf(r.calls, 'issue', 'create')[0]).split(/\r?\n/)
  assert.ok(lines.includes('- Server version: unknown'))
  assert.ok(lines.includes('- Call shape: not recorded'))
})

test('S3 probe: a long message changes neither the title nor the body, only the fingerprint', async () => {
  const a = await run([d({ message: 'z'.repeat(200) })])
  const b = await run([d({ message: 'short' })])
  assert.equal(flag(callsOf(a.calls, 'issue', 'create')[0], '--title')[0], flag(callsOf(b.calls, 'issue', 'create')[0], '--title')[0])
  assert.ok(!a.bodyOf(callsOf(a.calls, 'issue', 'create')[0]).includes('zzzzzzzz'))
})

// -- S4 ---------------------------------------------------------------------------------------------
// Oracle: AC10, D3, retro-metrics doc comments of defectFingerprint and normaliseMessage.
test('S4 fingerprint equals defectFingerprint with defectComponent(tool) and is the search term', async () => {
  const x = d()
  const F = fpOf(x)
  assert.equal(fingerprintOf(x), F)
  assert.match(F, /^to-bug-[0-9a-f]{8}$/)
  const r = await run([x])
  assert.equal(r.doc.results[0].fingerprint, F)
  assert.deepEqual(flag(callsOf(r.calls, 'issue', 'list')[0], '--search'), [`${F} in:body`])
})

test('S4b the fingerprint is stable across ids, paths and digits in the message', () => {
  const a = d({ message: `failed 12 times for ${UUID_A} at C:\\x\\y` })
  const b = d({ message: `failed 99 times for ${UUID_B} at D:\\z\\w` })
  assert.equal(fingerprintOf(a), fingerprintOf(b))
  assert.equal(fingerprintOf(a), fpOf(a))
  assert.notEqual(fingerprintOf(a), fingerprintOf(d({ message: 'a different failure' })))
})

// -- S5 ---------------------------------------------------------------------------------------------
// Oracle: AC1, D2, D15 (gate off: no gh call, no state access); A1 B1 (consent text is the user-level config).
for (const [name, configText] of [
  ['bug_reports absent', cfg({ bug: null })],
  ['bug_reports false', cfg({ bug: 'false' })],
  ['bug_reports quoted "true"', cfg({ bug: '"true"' })],
  ['bug_reports yes', cfg({ bug: 'yes' })],
  ['bug_reports 1', cfg({ bug: '1' })],
  ['bug_reports True', cfg({ bug: 'True' })],
  ['bug_reports TRUE', cfg({ bug: 'TRUE' })],
  ['enabled true alone', cfg({ bug: null, enabled: 'true' })],
  ['empty config text', ''],
]) {
  test(`S5 gate disabled: ${name}`, async () => {
    const r = await run([d()], { configText })
    assert.equal(r.doc.gate, 'disabled')
    assert.equal(r.calls.length, 0)
    assert.equal(r.stateReads(), 0)
    assert.equal(r.writes.length, 0)
    assert.equal(r.doc.contract, 'run-wave/bug-report-v1')
  })
}

test('S5 control: the bare scalar true opens the gate, with or without enabled; the repo is the upstream default', async () => {
  const a = await run([d()], { configText: cfg({ bug: 'true', enabled: 'false' }) })
  assert.equal(a.doc.gate, 'ran')
  assert.equal(a.doc.repo, 'jpicklyk/task-orchestrator')
  const b = await run([d()], { configText: cfg({ bug: 'true' }) })
  assert.equal(b.doc.gate, 'ran')
  assert.equal(b.doc.repo, 'jpicklyk/task-orchestrator')
  assert.equal(DEFAULT_REPO, 'jpicklyk/task-orchestrator')
  assert.deepEqual(flag(callsOf(b.calls, 'issue', 'list')[0], '--repo'), ['jpicklyk/task-orchestrator'])
})

// -- S6 ---------------------------------------------------------------------------------------------
// Oracle: AC2, D15.
test('S6 gh auth status failing: gh-unavailable after one gh call', async () => {
  const r = await run([d(), dn(2)], { 'auth status': fail() })
  assert.equal(r.doc.gate, 'gh-unavailable')
  assert.equal(r.calls.length, 1)
  assert.deepEqual(r.calls[0].slice(0, 2), ['auth', 'status'])
  assert.equal(r.writes.length, 0)
})

// -- S7 ---------------------------------------------------------------------------------------------
// Oracle: AC3, D4; A1 R2 (a server tool needs its op among the catalog entries, absent catalog fails op; a
// plugin tool matches ^[a-z0-9][a-z0-9-]*\.mjs$, its op ^[A-Za-z][A-Za-z0-9_-]{0,63}$; code
// ^(exit:\d{1,3}|[A-Za-z][A-Za-z0-9_]{0,63})$; else not-qualifying:<field>); A2 B4 (a server code must be in
// its catalog entry's errors list; a plugin tool must be one of the injected pluginTools). A row that spreads
// PLUGIN_BASE runs with its tool added to pluginTools, so only the name pattern can reject it; the code
// pattern and environment-code rows use a plugin script defect for the same reason (a server code outside the
// entry's errors list would reject them anyway). Rows on the server fixture isolate the B4 membership check.
const PLUGIN_BASE = { signal: 'script-failure', tool: 'run-planner.mjs', op: 'bug-report', code: 'exit:2' }
const ENV_CODES = ['network', 'unauthenticated', 'timeout', 'no_api', 'ECONNREFUSED', 'ENOTFOUND', 'ETIMEDOUT', 'EAI_AGAIN']
const NOT_QUALIFYING = [
  ['kind process', { kind: 'process' }, 'kind'],
  ['scope global-process', { scope: 'global-process' }, 'scope'],
  ['scope project', { scope: 'project' }, 'scope'],
  ['no signal', { signal: undefined }, 'signal'],
  ['unlisted signal', { signal: 'vibes' }, 'signal'],
  ['empty quote', { quote: '' }, 'quote'],
  ['empty tool', { tool: '' }, 'tool'],
  ['empty op', { op: '' }, 'op'],
  ['empty code', { code: '' }, 'code'],
  ['empty message', { message: '' }, 'message'],
  ...ENV_CODES.flatMap((c) => [...new Set([c, c.toLowerCase(), c.toUpperCase()])]
    .map((v) => [`env code ${v}`, { ...PLUGIN_BASE, code: v }, 'code'])),
  ['env code unauthenticated, which the catalog lists for advance transition', { tool: 'advance', op: 'transition', code: 'unauthenticated' }, 'code'],
  ['env code UNAUTHENTICATED on a catalog entry that lists it', { tool: 'advance', op: 'transition', code: 'UNAUTHENTICATED' }, 'code'],
  // A1: tool, op and code patterns
  ['server tool op outside the catalog', { op: 'nonesuch' }, 'op'],
  ['server tool op that is a prototype name constructor', { op: 'constructor' }, 'op'],
  ['server tool op that is a prototype name toString', { op: 'toString' }, 'op'],
  ['server tool op that is a prototype name __proto__', { op: '__proto__' }, 'op'],
  ['server tool op that is a prototype name hasOwnProperty', { op: 'hasOwnProperty' }, 'op'],
  ['tool neither a server tool nor a script', { tool: 'not_a_tool' }, 'tool'],
  ['tool named like a prototype member', { tool: 'constructor' }, 'tool'],
  ['tool named toString', { tool: 'toString' }, 'tool'],
  ['plugin tool upper case', { ...PLUGIN_BASE, tool: 'Hook.mjs' }, 'tool'],
  ['plugin tool with a parent path', { ...PLUGIN_BASE, tool: '../x.mjs' }, 'tool'],
  ['plugin tool with a directory', { ...PLUGIN_BASE, tool: 'a/b.mjs' }, 'tool'],
  ['plugin tool with a backslash directory', { ...PLUGIN_BASE, tool: 'a\\b.mjs' }, 'tool'],
  ['plugin tool with a trailing newline', { ...PLUGIN_BASE, tool: 'x.mjs\n' }, 'tool'],
  ['plugin tool leading hyphen', { ...PLUGIN_BASE, tool: '-x.mjs' }, 'tool'],
  ['plugin tool wrong extension', { ...PLUGIN_BASE, tool: 'x.js' }, 'tool'],
  ['plugin tool with a space', { ...PLUGIN_BASE, tool: 'my script.mjs' }, 'tool'],
  ['plugin op starting with a digit', { ...PLUGIN_BASE, op: '1abc' }, 'op'],
  ['plugin op of 65 characters', { ...PLUGIN_BASE, op: 'a'.repeat(65) }, 'op'],
  ['plugin op with a space', { ...PLUGIN_BASE, op: 'has space' }, 'op'],
  ['plugin op that is a path', { ...PLUGIN_BASE, op: 'C:\\Users\\a' }, 'op'],
  ['plugin op with a leading hyphen', { ...PLUGIN_BASE, op: '-run' }, 'op'],
  ['plugin op with a trailing newline', { ...PLUGIN_BASE, op: 'run\n' }, 'op'],
  ['code exit with four digits', { ...PLUGIN_BASE, code: 'exit:1234' }, 'code'],
  ['code exit without digits', { ...PLUGIN_BASE, code: 'exit:' }, 'code'],
  ['code exit with a sign', { ...PLUGIN_BASE, code: 'exit:-1' }, 'code'],
  ['plugin code starting with a digit', { ...PLUGIN_BASE, code: '1abc' }, 'code'],
  ['plugin code with a hyphen', { ...PLUGIN_BASE, code: 'a-b' }, 'code'],
  ['plugin code of 65 characters', { ...PLUGIN_BASE, code: 'a'.repeat(65) }, 'code'],
  ['plugin code that is a path', { ...PLUGIN_BASE, code: 'C:\\Users\\a' }, 'code'],
  ['plugin code that is a rooted path', { ...PLUGIN_BASE, code: '/srv/a/b' }, 'code'],
  ['plugin code with a space', { ...PLUGIN_BASE, code: 'has space' }, 'code'],
  ['plugin code with a trailing newline', { ...PLUGIN_BASE, code: 'abc\n' }, 'code'],
  // A2 B4: a server code must be among the errors its own catalog entry lists
  ['server code identifier-shaped but in no catalog list', { code: 'ContosoPayrollError' }, 'code'],
  ['server code that was the old default and is in no list', { code: 'validation_error' }, 'code'],
  ['server code that another entry lists but items_write create does not', { code: 'claim_held' }, 'code'],
  ['server code differing from a listed one only in case', { code: 'Invalid_Request' }, 'code'],
  ['server code with a trailing space after a listed one', { code: 'invalid_request ' }, 'code'],
  ['server code in the exit form, which no catalog entry lists', { code: 'exit:2' }, 'code'],
]
for (const [name, over, field] of NOT_QUALIFYING) {
  test(`S7 not qualifying: ${name}`, async () => {
    const pluginRow = over.signal === 'script-failure' && typeof over.tool === 'string'
    const r = await run([d(over)], { pluginTools: pluginRow ? [...PLUGIN_TOOLS, over.tool] : PLUGIN_TOOLS })
    assert.equal(r.doc.results[0].action, 'skipped')
    assert.equal(r.doc.results[0].reason, `not-qualifying:${field}`)
    assert.equal(callsOf(r.calls, 'issue', 'list').length, 0)
    assert.equal(callsOf(r.calls, 'issue', 'create').length, 0)
    assert.equal(callsOf(r.calls, 'issue', 'comment').length, 0)
  })
}

test('S7 control: every listed signal qualifies, and a code merely resembling an environment code qualifies', async () => {
  for (const signal of ['tool-error', 'hook-failure', 'script-failure', 'shape-mismatch']) {
    const r = await run([d({ signal })])
    assert.equal(r.doc.results[0].action, 'filed', signal)
  }
  const r = await run([script({ code: 'network_error' })])
  assert.equal(r.doc.results[0].action, 'filed')
})

// -- S19 --------------------------------------------------------------------------------------------
// Oracle: A1 R2 (qualification by catalog and patterns, call shape limited to the catalog fields); A2 B4
// (server code in the entry's errors list; plugin tool one of pluginTools; call shape top-level catalog field
// names with type names only, never a nested or map key).
for (const [name, over] of [
  ['plugin script with an exit code', {}],
  ['plugin script single character name', { tool: 'a.mjs' }],
  ['plugin script digit-led name', { tool: '0x-y.mjs' }],
  ['plugin op of 64 characters', { op: `A${'z'.repeat(63)}` }],
  ['plugin op with underscore and hyphen', { op: 'Pre_Tool-Use' }],
  ['code exit with three digits', { code: 'exit:127' }],
  ['code exit zero', { code: 'exit:0' }],
  ['code of 64 characters', { code: `A${'z'.repeat(63)}` }],
  ['code with an underscore and digits', { code: 'E_BAD_9' }],
  ['hook failure signal', { signal: 'hook-failure', tool: 'retro-hook.mjs' }],
]) {
  test(`S19 qualifies: ${name}`, async () => {
    const x = script(over)
    const r = await run([x], { catalog: null })
    assert.equal(r.doc.results[0].action, 'filed', JSON.stringify(r.doc.results[0]))
    const create = callsOf(r.calls, 'issue', 'create')[0]
    assert.equal(flag(create, '--title')[0], `[bug] ${defectComponent(x.tool)}: ${x.tool} ${x.op}: ${x.code}`)
    assert.equal(r.doc.results[0].fingerprint, fpOf(x))
  })
}

test('S19 a server tool without a catalog fails the op check; a script does not need one', async () => {
  for (const catalog of [null, undefined]) {
    const a = await run([d()], { catalog })
    assert.equal(a.doc.results[0].reason, 'not-qualifying:op')
    assert.equal(callsOf(a.calls, 'issue', 'list').length, 0)
  }
  const b = await run([script()], { catalog: null })
  assert.equal(b.doc.results[0].action, 'filed')
})

// B4 catalog errors: every entry of the real catalog document is walked.
const isEnvCode = (c) => ENV_CODES.some((e) => e.toLowerCase() === c.toLowerCase())
const ENTRIES = Object.entries(CATALOG_DOC.tools).flatMap(([tool, def]) =>
  Object.entries(def.entries).map(([op, entry]) => ({ tool, op, errors: entry.errors })))

test('S19 every op of every server tool in the catalog qualifies with each code its entry lists, an environment code excepted', async () => {
  assert.ok(ENTRIES.length > 0)
  for (const { tool, op, errors } of ENTRIES) {
    const codes = errors.filter((c) => !isEnvCode(c))
    assert.ok(codes.length > 0, `${tool} ${op} lists a usable code`)
    for (const code of codes) {
      const r = await run([d({ tool, op, code })])
      assert.equal(r.doc.results[0].action, 'filed', `${tool} ${op} ${code}: ${JSON.stringify(r.doc.results[0])}`)
    }
  }
  const stray = await run([d({ tool: 'notes_read', op: 'create', code: 'not_found' })])
  assert.equal(stray.doc.results[0].reason, 'not-qualifying:op')
})

test('S19 a code that the entry does not list does not qualify, whichever other entry lists it or whatever its shape', async () => {
  const all = [...Object.keys(CATALOG_DOC.errors), 'ContosoPayrollError', 'jeff_secret_code', 'exit:2']
  for (const { tool, op, errors } of ENTRIES) {
    for (const code of all.filter((c) => !errors.includes(c))) {
      const r = await run([d({ tool, op, code })])
      assert.equal(r.doc.results[0].reason, 'not-qualifying:code', `${tool} ${op} ${code}: ${JSON.stringify(r.doc.results[0])}`)
      assert.equal(callsOf(r.calls, 'issue', 'list').length, 0, `${tool} ${op} ${code} searched`)
    }
  }
})

const smallCatalog = (errors, documentErrors = {}) => catalogOf({
  tools: { items_write: { kind: 'ops', entries: { create: { ...CATALOG_DOC.tools.items_write.entries.create, errors } } } },
  errors: documentErrors,
})
test('S19 the code check follows the supplied entry: its list decides, not the document errors map and not a fixed list', async () => {
  const listed = await run([d({ code: 'not_found' })], { catalog: smallCatalog(['not_found']) })
  assert.equal(listed.doc.results[0].action, 'filed', 'a code the entry lists qualifies although the errors map is empty')
  const unlisted = await run([d({ code: 'invalid_request' })], {
    catalog: smallCatalog(['not_found'], { invalid_request: { kind: 'permanent', http: 400 }, not_found: { kind: 'permanent', http: 404 } }),
  })
  assert.equal(unlisted.doc.results[0].reason, 'not-qualifying:code', 'the errors map listing it does not admit it')
  const empty = await run([d({ code: 'not_found' })], { catalog: smallCatalog([]) })
  assert.equal(empty.doc.results[0].reason, 'not-qualifying:code', 'an empty list admits no code')
  assert.equal(callsOf(empty.calls, 'issue', 'list').length, 0)
})

test('S19 qualification follows the supplied catalog: an op missing from it does not qualify', async () => {
  const small = smallCatalog(CATALOG_DOC.tools.items_write.entries.create.errors)
  const a = await run([d({ op: 'create' })], { catalog: small })
  assert.equal(a.doc.results[0].action, 'filed')
  const b = await run([d({ op: 'update' })], { catalog: small })
  assert.equal(b.doc.results[0].reason, 'not-qualifying:op')
})

// B4 plugin allowlist.
test('S19 a plugin tool qualifies only when it is one of pluginTools', async () => {
  const x = script()
  const listed = await run([x], { pluginTools: ['run-planner.mjs'] })
  assert.equal(listed.doc.results[0].action, 'filed')
  for (const list of [['other.mjs'], ['run-planner.mjs.bak', 'planner.mjs', 'run-planner-2.mjs'], ['RUN-PLANNER.MJS', 'Run-Planner.mjs']]) {
    const r = await run([x], { pluginTools: list })
    assert.equal(r.doc.results[0].action, 'skipped', JSON.stringify(list))
    assert.equal(r.doc.results[0].reason, 'not-qualifying:tool', JSON.stringify(list))
    assert.equal(callsOf(r.calls, 'issue', 'list').length, 0)
    assert.equal(callsOf(r.calls, 'issue', 'create').length, 0)
  }
})

test('S19 a name shaped like a plugin file but not injected does not qualify, whatever its op and code', async () => {
  for (const over of [{ tool: 'contoso-payroll.mjs' }, { tool: 'contoso-payroll.mjs', op: 'jeffsecret', code: 'exit:1' }, { tool: 'zz.mjs', signal: 'hook-failure' }]) {
    const r = await run([script(over)])
    assert.equal(r.doc.results[0].reason, 'not-qualifying:tool', JSON.stringify(over))
    assert.equal(callsOf(r.calls, 'issue', 'list').length, 0)
  }
})

test('S19 an empty, null, undefined or absent pluginTools admits no plugin defect', async () => {
  for (const pluginTools of [[], null, undefined]) {
    const r = await run([script()], { pluginTools })
    assert.equal(r.doc.results[0].reason, 'not-qualifying:tool', String(pluginTools))
    assert.equal(callsOf(r.calls, 'issue', 'list').length, 0)
  }
  const env = makeEnv()
  const doc = await runBugReport({ defects: [script()], configText: cfg(), versions: VERSIONS, catalog: CATALOG, deps: env.deps })
  assert.equal(doc.results[0].reason, 'not-qualifying:tool', 'key absent')
  assert.equal(callsOf(env.calls, 'issue', 'list').length, 0)
})

test('S19 pluginTools does not gate a server tool: a catalog defect qualifies with pluginTools empty, null or undefined', async () => {
  for (const pluginTools of [[], null, undefined]) {
    const r = await run([d()], { pluginTools })
    assert.equal(r.doc.results[0].action, 'filed', String(pluginTools))
  }
})

// B4 call shape: the catalog fields of the tool and op, as type names, never a nested or map key.
const shapeOf = (r) => {
  const create = callsOf(r.calls, 'issue', 'create')[0]
  const line = r.bodyOf(create).split(/\r?\n/).find((l) => l.startsWith('- Call shape:'))
  const m = line.match(/^- Call shape: `(.*)`$/)
  assert.ok(m, `call shape line: ${line}`)
  return JSON.parse(m[1])
}
const bodyText = (r) => r.bodyOf(callsOf(r.calls, 'issue', 'create')[0])

test('S19 the call shape keeps only the catalog fields of the tool and op, as type names', async () => {
  const r = await run([d({
    tool: 'notes_read', op: 'get', code: 'not_found',
    callShape: { itemId: 'x', key: 'k', ifVersion: 3, bogus_field: 'v', secret_note: 'zz', 'C:\\Users\\a': 1 },
  })])
  assert.deepEqual(shapeOf(r), { itemId: 'string', key: 'string', ifVersion: 'number' })
  const text = JSON.stringify(bodyText(r))
  for (const w of ['bogus_field', 'secret_note', 'Users']) assert.ok(!text.includes(w), `no ${w}`)
})

test('S19 the call shape follows the catalog supplied, not a fixed field list', async () => {
  const small = catalogOf({ tools: { items_write: { kind: 'ops', entries: { create: { ...CATALOG_DOC.tools.items_write.entries.create, fields: { title: { type: 'string', required: true } } } } } }, errors: {} })
  const r = await run([d({ callShape: { title: 't', priority: 'high' } })], { catalog: small })
  assert.deepEqual(shapeOf(r), { title: 'string' })
})

test('S19 an object or map value of a catalog field reads object and none of its keys is posted', async () => {
  const a = await run([d({
    tool: 'runs_write', op: 'open',
    callShape: { rootId: 'x', label: 'l', hints: { contosoSecret: 'v', other: 'w' }, budget: { attemptsPerSeat: 3, maxActiveSeats: 2 } },
  })])
  assert.deepEqual(shapeOf(a), { rootId: 'string', label: 'string', hints: 'object', budget: 'object' })
  for (const w of ['contosoSecret', 'attemptsPerSeat', 'maxActiveSeats']) assert.ok(!bodyText(a).includes(w), `no ${w}`)
  const b = await run([d({ callShape: { title: 'x', properties: { a: { contosoDeep: 1 }, contosoMapKey: 2 } } })])
  assert.deepEqual(shapeOf(b), { title: 'string', properties: 'object' })
  for (const w of ['contosoDeep', 'contosoMapKey']) assert.ok(!bodyText(b).includes(w), `no ${w}`)
})

test('S19 an array value of a catalog field posts no element and no key of an element', async () => {
  const r = await run([d({ callShape: { title: 'x', tags: ['secret-tag-zz', { contosoElementKey: 1 }] } })])
  const shape = shapeOf(r)
  assert.equal(shape.title, 'string')
  assert.ok('tags' in shape, 'the catalog field itself is kept')
  for (const w of ['secret-tag-zz', 'contosoElementKey']) assert.ok(!bodyText(r).includes(w), `no ${w}`)
})

// A3 N3: for a tool whose catalog kind is 'ops', a call shape recorded as the whole {ops:[...]} call posts the
// top-level catalog fields of the element whose op equals the defect's op, else of the first object element; a
// shape recorded as the element itself, and a select tool's shape, post as before (type names only).
const OPS_CASES = [
  ['notes_write upsert', { tool: 'notes_write', op: 'upsert', code: 'note_too_long' },
    { op: 'upsert', itemId: 'x', key: 'k', role: 'work', body: 'b', expectedVersion: 2, bogus_field: 'v' },
    { itemId: 'string', key: 'string', role: 'string', body: 'string', expectedVersion: 'number' }],
  ['items_write create', { tool: 'items_write', op: 'create', code: 'invalid_request' },
    { op: 'create', title: 't', priority: 'high', complexity: 3, bogus_field: 'v' },
    { title: 'string', priority: 'string', complexity: 'number' }],
  ['advance transition', { tool: 'advance', op: 'transition', code: 'gate_blocked' },
    { op: 'transition', itemId: 'x', trigger: 'start', summary: 's', bogus_field: 'v' },
    { itemId: 'string', trigger: 'string', summary: 'string' }],
  ['runs_write open', { tool: 'runs_write', op: 'open', code: 'invalid_request' },
    { op: 'open', rootId: 'x', label: 'l', hints: { contosoSecret: 'v' }, budget: { attemptsPerSeat: 3 }, bogus_field: 'v' },
    { rootId: 'string', label: 'string', hints: 'object', budget: 'object' }],
]
for (const [name, who, element, expected] of OPS_CASES) {
  test(`S19 N3 ${name}: the whole {ops:[...]} call and the element itself post the same catalog fields as type names`, async () => {
    const whole = await run([d({ ...who, callShape: { ops: [element], seatToken: 'SECRETTOKENZ' } })])
    assert.equal(whole.doc.results[0].action, 'filed', JSON.stringify(whole.doc.results[0]))
    assert.deepEqual(shapeOf(whole), expected, 'whole call')
    const alone = await run([d({ ...who, callShape: element })])
    assert.deepEqual(shapeOf(alone), expected, 'element itself')
    assert.deepEqual(shapeOf(whole), shapeOf(alone))
    for (const w of ['bogus_field', 'contosoSecret', 'attemptsPerSeat', 'SECRETTOKENZ', 'seatToken']) {
      assert.ok(!bodyText(whole).includes(w), `whole call posts no ${w}`)
      assert.ok(!bodyText(alone).includes(w), `element posts no ${w}`)
    }
  })
}

const DEL = { op: 'delete', itemId: 'a', key: 'k1', expectedVersion: 4 }
const UPS = { op: 'upsert', itemId: 'a', key: 'k2', role: 'work', body: 'b' }
const NW = (op, code) => ({ tool: 'notes_write', op, code })

test('S19 N3 a whole ops call posts the fields of the element whose op equals the defect op, wherever it sits', async () => {
  const lead = { op: 'delete', itemId: 'b', key: 'k0' }
  const upsertShape = { itemId: 'string', key: 'string', role: 'string', body: 'string' }
  for (const ops of [[DEL, UPS], [UPS, DEL], [lead, DEL, UPS], [DEL, lead, UPS]]) {
    const r = await run([d({ ...NW('upsert', 'invalid_request'), callShape: { ops } })])
    assert.deepEqual(shapeOf(r), upsertShape, `upsert among ${JSON.stringify(ops.map((e) => e.op))}`)
  }
  const deleteShape = { itemId: 'string', key: 'string', expectedVersion: 'number' }
  for (const ops of [[UPS, DEL], [DEL, UPS], [UPS, UPS, DEL]]) {
    const r = await run([d({ ...NW('delete', 'not_found'), callShape: { ops } })])
    assert.deepEqual(shapeOf(r), deleteShape, `delete among ${JSON.stringify(ops.map((e) => e.op))}`)
  }
})

test('S19 N3 with no element carrying the defect op, the first object element is read, skipping entries that are not objects', async () => {
  const other = { op: 'delete', itemId: 'z' }
  const first = { op: 'delete', itemId: 'a', key: 'k', expectedVersion: 4 }
  const expected = { itemId: 'string', key: 'string', expectedVersion: 'number' }
  for (const ops of [[first, other], ['junk', 7, first, other], [first, other, { op: 'update', itemId: 'q', key: 'w', role: 'work' }]]) {
    const r = await run([d({ ...NW('upsert', 'invalid_request'), callShape: { ops } })])
    assert.deepEqual(shapeOf(r), expected, JSON.stringify(ops.map((e) => (typeof e === 'object' ? e.op : e))))
  }
  const noOp = await run([d({ ...NW('upsert', 'invalid_request'), callShape: { ops: [{ itemId: 'a', key: 'k' }, other] } })])
  assert.deepEqual(shapeOf(noOp), { itemId: 'string', key: 'string' }, 'an element with no op is still the first object element')
})

test('S19 N3 an op that differs from the defect op only in case does not match: the first object element is read', async () => {
  const r = await run([d({ ...NW('upsert', 'invalid_request'), callShape: { ops: [DEL, { ...UPS, op: 'UPSERT' }] } })])
  assert.deepEqual(shapeOf(r), { itemId: 'string', key: 'string', expectedVersion: 'number' })
})

test('S19 N3 a select tool call shape is read as before: an ops key is not unwrapped', async () => {
  const r = await run([d({
    tool: 'notes_read', op: 'get', code: 'not_found',
    callShape: { itemId: 'x', key: 'k', ops: [{ itemId: 'y', ifVersion: 2 }] },
  })])
  assert.deepEqual(shapeOf(r), { itemId: 'string', key: 'string' })
  const alone = await run([d({ tool: 'notes_read', op: 'list', code: 'not_found', callShape: { itemId: 'x', keys: ['SECRETELEMENT'], limit: 5 } })])
  const shape = shapeOf(alone)
  assert.equal(shape.itemId, 'string')
  assert.equal(shape.limit, 'number')
  assert.ok('keys' in shape)
  assert.ok(!bodyText(alone).includes('SECRETELEMENT'), 'no array element')
})

test('S19 N3 a whole ops call posts no element value, no nested key, no key outside the catalog fields and no non-object entry', async () => {
  const r = await run([d({
    ...NW('upsert', 'invalid_request'),
    callShape: {
      ops: [{ op: 'upsert', itemId: 'SECRETVALUE', key: 'secret-key-zz', role: 'work', body: 'SECRETBODY', contosoExtra: 'v', metadata: { contosoNested: 1 } }, 'SECRETSTRING'],
      seatToken: 'SECRETTOKEN', contosoTop: 'v',
    },
  })])
  assert.deepEqual(shapeOf(r), { itemId: 'string', key: 'string', role: 'string', body: 'string' })
  const text = bodyText(r)
  for (const w of ['SECRETVALUE', 'secret-key-zz', 'SECRETBODY', 'contosoExtra', 'contosoNested', 'contosoTop', 'SECRETSTRING', 'SECRETTOKEN', 'seatToken']) {
    assert.ok(!text.includes(w), `no ${w}`)
  }
})

test('S19 N3 a whole ops call with no object element posts no element text and the defect is still filed', async () => {
  for (const ops of [[], ['SECRETSTRING'], [7, 'SECRETSTRING']]) {
    const r = await run([d({ ...NW('upsert', 'invalid_request'), callShape: { ops } })])
    assert.equal(r.doc.results[0].action, 'filed', JSON.stringify(ops))
    assert.ok(!bodyText(r).includes('SECRETSTRING'), JSON.stringify(ops))
  }
})

test('S19 a script defect call shape never carries a non-identifier key or a value', async () => {
  const r = await run([script({ callShape: { 'C:\\Users\\a': 'SECRETVALUE', '/srv/a/b': 'SECRETVALUE2' } })], { catalog: null })
  const text = bodyText(r)
  for (const w of ['SECRETVALUE', 'Users', '/srv/a/b']) assert.ok(!text.includes(w), `no ${w}`)
})

test('S19 a script defect call shape never carries a nested identifier key or a value', async () => {
  const r = await run([script({ callShape: { outer: { contosoDeep: 'SECRETVALUE' }, flat: 'SECRETVALUE2' } })], { catalog: null })
  const text = bodyText(r)
  for (const w of ['contosoDeep', 'SECRETVALUE']) assert.ok(!text.includes(w), `no ${w}`)
})

// typeShape (declared: the top level of the value with every value replaced by its type name; identifier keys
// only, at most 30; an object or map value reads 'object' and none of its keys is kept; below the top level
// nothing is read). The name an array reads is cut off in the declaration, so no test fixes it.
test('S19 typeShape replaces every top-level value by its type name; an object value reads object', () => {
  assert.deepEqual(typeShape({ a: 1, b: 's', c: true, d: { e: 2 } }), { a: 'number', b: 'string', c: 'boolean', d: 'object' })
  assert.ok(!JSON.stringify(typeShape({ secret: 'SECRETVALUE' })).includes('SECRETVALUE'))
  assert.deepEqual(typeShape({ hints: { contosoSecret: 'v' } }), { hints: 'object' })
})
test('S19 typeShape keeps no key below the top level, however deep the value is', () => {
  let deep = 'leaf'
  for (let i = 0; i < 100; i++) deep = { k: deep }
  assert.deepEqual(typeShape({ outer: deep }), { outer: 'object' })
  assert.deepEqual(typeShape(deep), { k: 'object' })
  assert.deepEqual(typeShape({ outer: { inner: 2 } }), { outer: 'object' })
})
test('S19 typeShape drops top-level keys that are not identifiers', () => {
  assert.deepEqual(typeShape({ ok: 1, 'C:\\Users\\a': 2, '/srv/a/b': 3, 'has space': 4, 'a@b.io': 5 }), { ok: 'number' })
})
test('S19 typeShape keeps at most 30 keys', () => {
  const many = (n) => Object.fromEntries(Array.from({ length: n }, (_, i) => [`key${i}`, i]))
  assert.equal(Object.keys(typeShape(many(5))).length, 5)
  assert.equal(Object.keys(typeShape(many(30))).length, 30)
  const forty = typeShape(many(40))
  assert.ok(Object.keys(forty).length <= 30, `kept ${Object.keys(forty).length}`)
  assert.ok(Object.keys(forty).every((k) => k.startsWith('key') && forty[k] === 'number'))
})
test('S19 typeShape of an array value leaks no element and no key of an element', () => {
  const s = typeShape({ list: ['SECRETVALUE', { contosoElementKey: 1 }] })
  assert.ok('list' in s)
  assert.ok(!JSON.stringify(s).includes('SECRETVALUE'))
  assert.ok(!JSON.stringify(s).includes('contosoElementKey'))
})

// -- S8 ---------------------------------------------------------------------------------------------
// Oracle: AC7, D10 (codes and what each matches); A1 B3 (lookbehind (?<![\w./]): a path after ':', '<' or
// '-' is flagged), A1 R2 (message and actual are never posted, so a path in them cannot reach the body).
const FP = 'to-bug-1a2b3c4d'
for (const [name, text, code] of [
  ['windows backslash path', 'failed at C:\\Users\\a\\x today', 'absolute-path'],
  ['windows forward path', 'failed at D:/a/b today', 'absolute-path'],
  ['unc path', 'share \\\\host\\s here', 'absolute-path'],
  ['home tilde path', 'file ~/x here', 'absolute-path'],
  ['home tilde backslash path', 'file ~\\x here', 'absolute-path'],
  ['file url', 'open file:///a/b now', 'absolute-path'],
  ['rooted two segments', 'see /home/a/b here', 'absolute-path'],
  ['rooted with trailing slash', 'see /home/a/b/ here', 'absolute-path'],
  ['rooted after a colon', 'path:/home/a/b here', 'absolute-path'],
  ['rooted at the start after a colon', ':/home/a/b', 'absolute-path'],
  ['rooted inside angle brackets', 'see </srv/a/b> here', 'absolute-path'],
  ['rooted after a hyphen', 'flag -/srv/a/b here', 'absolute-path'],
  ['uuid', `id ${UUID_A} here`, 'uuid'],
  ['8 hex run', 'id 0123abcd here', 'hex-id'],
  ['email', 'mail a@b.io now', 'email'],
]) {
  test(`S8 scrubFindings flags ${name}`, () => {
    assert.ok(scrubFindings(text, { fingerprint: FP, denyNames: [] }).includes(code))
  })
}

test('S8 scrubFindings: deny names are whole words, any case, three characters or more', () => {
  assert.ok(scrubFindings('The Acme project', { fingerprint: FP, denyNames: ['acme'] }).includes('deny-name'))
  assert.ok(scrubFindings('The ACME project', { fingerprint: FP, denyNames: ['Acme'] }).includes('deny-name'))
  assert.ok(!scrubFindings('The acmeware project', { fingerprint: FP, denyNames: ['acme'] }).includes('deny-name'))
  assert.ok(!scrubFindings('go ab now', { fingerprint: FP, denyNames: ['ab'] }).includes('deny-name'))
  assert.ok(scrubFindings('go abc now', { fingerprint: FP, denyNames: ['abc'] }).includes('deny-name'))
})

test('S8 scrubFindings: the fingerprint alone passes; 7 hex characters and a one-segment rooted word are not flagged', () => {
  assert.deepEqual(scrubFindings(`Fingerprint: ${FP}`, { fingerprint: FP, denyNames: [] }), [])
  assert.ok(!scrubFindings('id 0123abc here', { fingerprint: FP, denyNames: [] }).includes('hex-id'))
  assert.ok(!scrubFindings('under /home only', { fingerprint: FP, denyNames: [] }).includes('absolute-path'))
  assert.deepEqual(scrubFindings('plain words only', { fingerprint: FP, denyNames: [] }), [])
})

const LEAKS = ['C:\\Users\\a\\x', '/home/a/b', '0123abcd', UUID_A, 'a@b.io', 'acme', 'Acme']
for (const [name, over, denyNames] of [
  ['an absolute path in the message', { message: 'it broke at C:\\Users\\a\\x' }, []],
  ['an absolute path in actual', { actual: 'it broke at /home/a/b' }, []],
  ['a uuid in the message', { message: `it broke for ${UUID_A}` }, []],
  ['an 8 hex id in actual', { actual: 'it broke for 0123abcd' }, []],
  ['an email in the message', { message: 'mail a@b.io about it' }, []],
  ['the project name in another case in the message', { message: 'the Acme project broke' }, ['acme']],
  ['the project name in expected', { expected: 'the Acme project works' }, ['acme']],
]) {
  test(`S8 ${name} is never posted: the defect is filed and title and body hold none of it`, async () => {
    const x = d(over)
    const r = await run([x], { denyNames })
    assert.equal(r.doc.results[0].action, 'filed', JSON.stringify(r.doc.results[0]))
    assert.equal(r.doc.results[0].fingerprint, fpOf(x))
    const create = callsOf(r.calls, 'issue', 'create')[0]
    const posted = `${flag(create, '--title')[0]}\n${r.bodyOf(create)}`
    for (const leak of LEAKS) assert.ok(!posted.includes(leak), `posted text holds ${leak}`)
    assert.deepEqual(scrubFindings(posted, { fingerprint: fpOf(x), denyNames }), [])
  })
}

test('S8 the scrub stays as a defensive check: a deny name in a structured field rejects the filing and posts nothing', async () => {
  const x = d()
  const r = await run([x], { denyNames: ['items_write'] })
  assert.equal(r.doc.results[0].action, 'skipped')
  assert.ok(r.doc.results[0].reason.startsWith('scrub-rejected:'), r.doc.results[0].reason)
  assert.ok(r.doc.results[0].reason.includes('deny-name'), r.doc.results[0].reason)
  assert.equal(callsOf(r.calls, 'issue', 'create').length, 0)
  assert.equal(callsOf(r.calls, 'issue', 'comment').length, 0)
  assert.ok(!r.writes.some((w) => w.seen && w.seen[fpOf(x)]), 'no state entry for a rejected defect')
})

test('S8 the defensive scrub also covers the seen-again comment', async () => {
  const x = d()
  const r = await run([x], { denyNames: ['items_write'], 'issue list': ok(JSON.stringify([hit({ body: `Fingerprint: ${fpOf(x)}` })])) })
  assert.equal(r.doc.results[0].action, 'skipped')
  assert.ok(r.doc.results[0].reason.startsWith('scrub-rejected:'), r.doc.results[0].reason)
  assert.equal(callsOf(r.calls, 'issue', 'comment').length, 0)
})

// -- S9 ---------------------------------------------------------------------------------------------
// Oracle: AC4, D6, D12; A2 N1 'never retry' (one create attempt; a failed or timed-out create posts nothing
// more that run for that defect, its result is skipped gh-create-failed and its state entry is
// {at, url: null, status: 'create-unconfirmed'}, so the next run's fingerprint search finds the issue or
// files it; a create that never reached gh leaves no entry); A3 B5 user ruling 2026-11-19 'no labels on
// create' (the one create attempt carries no --label at all).
const FAILED_CREATES = [
  ['non-zero exit', { status: 1, stdout: '' }],
  ['non-zero exit with output', { status: 2, stdout: 'GraphQL: rate limited' }],
  ['timed out (no exit status)', { status: null, stdout: '' }],
]
for (const [name, failed] of FAILED_CREATES) {
  test(`S9 a failed create (${name}) is the only attempt: no label, no retry, skipped gh-create-failed`, async () => {
    const x = d()
    const F = fpOf(x)
    const r = await run([x], { 'issue create': failed })
    const creates = callsOf(r.calls, 'issue', 'create')
    assert.equal(creates.length, 1)
    assert.deepEqual(flag(creates[0], '--label'), [])
    assert.equal(hasLabel(creates[0]), false, JSON.stringify(creates[0]))
    assert.equal(callsOf(r.calls, 'issue', 'comment').length, 0)
    assert.equal(r.doc.results[0].action, 'skipped')
    assert.equal(r.doc.results[0].reason, 'gh-create-failed')
    assert.equal(r.doc.results[0].line, `bug-report ${F}: skipped gh-create-failed`)
  })
  test(`S9 a failed create (${name}) is recorded create-unconfirmed with no url`, async () => {
    const x = d()
    const F = fpOf(x)
    const r = await run([x], { 'issue create': failed })
    assert.deepEqual(r.writes.at(-1).seen[F], { at: WRITTEN_AT, url: null, status: 'create-unconfirmed' })
    assert.equal(r.doc.stateWrite, undefined)
  })
}

test('S9 a create that fails does not stop the next defect, which is filed; each gets its own state entry', async () => {
  const d1 = dn(1)
  const d2 = dn(2)
  const r = await run([d1, d2], { 'issue create': (_a, n) => (n === 1 ? fail() : ok(`https://github.com/${REPO}/issues/77\n`)) })
  assert.equal(callsOf(r.calls, 'issue', 'create').length, 2, 'one attempt per defect')
  assert.equal(r.doc.results[0].reason, 'gh-create-failed')
  assert.equal(r.doc.results[1].action, 'filed')
  assert.equal(r.doc.results[1].url, `https://github.com/${REPO}/issues/77`)
  const seen = r.writes.at(-1).seen
  assert.deepEqual(seen[fpOf(d1)], { at: WRITTEN_AT, url: null, status: 'create-unconfirmed' })
  assert.equal(seen[fpOf(d2)].url, `https://github.com/${REPO}/issues/77`)
  assert.notEqual(seen[fpOf(d2)].status, 'create-unconfirmed')
})

test('S9 the next run after an unconfirmed create files the defect when the search finds nothing, and the state then holds its url', async () => {
  const x = d()
  const F = fpOf(x)
  const r = await run([x], { state: { seen: { [F]: { at: atOf(NOW - 3600000), url: null, status: 'create-unconfirmed' } } } })
  assert.equal(callsOf(r.calls, 'issue', 'list').length, 1, 'the fingerprint search runs')
  assert.equal(callsOf(r.calls, 'issue', 'create').length, 1)
  assert.equal(r.doc.results[0].action, 'filed')
  assert.equal(r.writes.at(-1).seen[F].url, `https://github.com/${REPO}/issues/101`)
})

// -- S10 --------------------------------------------------------------------------------------------
// Oracle: AC9, D12 (library part; the CLI exit codes are S23).
test('S10 a failed search skips that defect and the next one is filed', async () => {
  const d1 = dn(1)
  const d2 = dn(2)
  const r = await run([d1, d2], { 'issue list': (a, n) => (n === 1 ? fail() : ok('[]')) })
  assert.equal(r.doc.results[0].action, 'skipped')
  assert.equal(r.doc.results[0].reason, 'gh-search-failed')
  assert.equal(r.doc.results[0].line, `bug-report ${fpOf(d1)}: skipped gh-search-failed`)
  assert.equal(r.doc.results[1].action, 'filed')
  assert.equal(r.doc.results[1].fingerprint, fpOf(d2))
})

test('S10 a failed comment skips that defect with gh-comment-failed', async () => {
  const x = d()
  const r = await run([x], {
    'issue list': ok(JSON.stringify([hit({ body: `Fingerprint: ${fpOf(x)}` })])),
    'issue comment': fail(),
  })
  assert.equal(r.doc.results[0].action, 'skipped')
  assert.equal(r.doc.results[0].reason, 'gh-comment-failed')
})

// -- S11 --------------------------------------------------------------------------------------------
// Oracle: D7 (under 7x24h rate-limited, exactly 7 days comments), D13 (state shape).
async function withSeen(ageMs, extra = {}) {
  const x = d()
  const F = fpOf(x)
  const r = await run([x], {
    state: { seen: { [F]: { at: atOf(NOW - ageMs), url: `https://github.com/${REPO}/issues/7` } } },
    'issue list': ok(JSON.stringify([hit({ body: `Fingerprint: ${F}` })])),
    ...extra,
  })
  return r
}
test('S11 seen 6 days 23 hours ago is rate-limited, with no comment', async () => {
  const r = await withSeen(6 * DAY + 23 * 3600000)
  assert.equal(r.doc.results[0].action, 'skipped')
  assert.equal(r.doc.results[0].reason, 'rate-limited')
  assert.equal(callsOf(r.calls, 'issue', 'comment').length, 0)
})
test('S11 seen exactly 7 days ago comments; one millisecond less is rate-limited', async () => {
  const a = await withSeen(7 * DAY)
  assert.equal(a.doc.results[0].action, 'commented')
  assert.equal(callsOf(a.calls, 'issue', 'comment').length, 1)
  const b = await withSeen(7 * DAY - 1)
  assert.equal(b.doc.results[0].reason, 'rate-limited')
})
test('S11 a sighting of another fingerprint does not rate-limit this one', async () => {
  const x = d()
  const F = fpOf(x)
  const r = await run([x], {
    state: { seen: { 'to-bug-00000000': { at: atOf(NOW - DAY), url: 'https://github.com/a/b/issues/1' } } },
    'issue list': ok(JSON.stringify([hit({ body: `Fingerprint: ${F}` })])),
  })
  assert.equal(r.doc.results[0].action, 'commented')
})
test('S11 replay: feeding the written state back into the same input is rate-limited', async () => {
  const x = d()
  const F = fpOf(x)
  const list = ok(JSON.stringify([hit({ body: `Fingerprint: ${F}` })]))
  const first = await run([x], { 'issue list': list })
  assert.equal(first.doc.results[0].action, 'commented')
  const second = await run([x], { 'issue list': list, state: first.writes.at(-1) })
  assert.equal(second.doc.results[0].reason, 'rate-limited')
  assert.equal(callsOf(second.calls, 'issue', 'comment').length, 0)
})

// -- S12 --------------------------------------------------------------------------------------------
// Oracle: AC6, D8 (closed hits), D7 applies, D12 (release-list failure).
const CLOSED_AT = '2026-11-01T00:00:00Z'
const BEFORE = '2026-10-30T00:00:00Z'
const AFTER = '2026-11-02T00:00:00Z'
async function closedRun(releases, versions, hitOver = {}, extra = {}) {
  const x = d()
  const F = fpOf(x)
  return run([x], {
    versions,
    'issue list': ok(JSON.stringify([hit({ state: 'CLOSED', stateReason: 'COMPLETED', closedAt: CLOSED_AT, body: `Fingerprint: ${F}`, ...hitOver })])),
    'release list': releases === null ? fail() : ok(JSON.stringify(releases)),
    ...extra,
  })
}
test('S12 closed NOT_PLANNED is skipped', async () => {
  const r = await closedRun([], VERSIONS, { stateReason: 'NOT_PLANNED' })
  assert.equal(r.doc.results[0].action, 'skipped')
  assert.equal(r.doc.results[0].reason, 'closed-not-planned')
  assert.equal(callsOf(r.calls, 'issue', 'comment').length, 0)
})
test('S12 a wontfix label skips a closed hit', async () => {
  const r = await closedRun([{ tagName: 'v4.0.1', publishedAt: BEFORE }], VERSIONS, { labels: [{ name: 'wontfix' }] })
  assert.equal(r.doc.results[0].reason, 'closed-not-planned')
})
test('S12 plugin newer than the release at close time: regression comment', async () => {
  const r = await closedRun([{ tagName: 'v4.0.3', publishedAt: AFTER }, { tagName: 'v4.0.1', publishedAt: BEFORE }], { plugin: '4.0.2' })
  assert.equal(r.doc.results[0].action, 'commented')
  const c = callsOf(r.calls, 'issue', 'comment')
  assert.equal(c.length, 1)
  assert.match(r.bodyOf(c[0]), /seen again after close \(possible regression\)/)
  const rl = callsOf(r.calls, 'release', 'list')[0]
  assert.deepEqual(flag(rl, '--repo'), [REPO])
})
test('S12 plugin equal to the release at close time: closed-no-regression', async () => {
  const r = await closedRun([{ tagName: 'v4.0.1', publishedAt: BEFORE }], { plugin: '4.0.1' })
  assert.equal(r.doc.results[0].action, 'skipped')
  assert.equal(r.doc.results[0].reason, 'closed-no-regression')
  assert.equal(callsOf(r.calls, 'issue', 'comment').length, 0)
})
test('S12 no release at all, or none at or before closedAt: closed-no-regression', async () => {
  const a = await closedRun([], { plugin: '4.0.2' })
  assert.equal(a.doc.results[0].reason, 'closed-no-regression')
  const b = await closedRun([{ tagName: 'v4.0.1', publishedAt: AFTER }], { plugin: '4.0.2' })
  assert.equal(b.doc.results[0].reason, 'closed-no-regression')
})
test('S12 a release published exactly at closedAt counts as at or before', async () => {
  const r = await closedRun([{ tagName: 'v4.0.1', publishedAt: CLOSED_AT }], { plugin: '4.0.2' })
  assert.equal(r.doc.results[0].action, 'commented')
})
test('S12 version comparison is numeric: 4.0.10 is newer than 4.0.9 and 4.0.9 is not newer than 4.0.10', async () => {
  const a = await closedRun([{ tagName: 'v4.0.9', publishedAt: BEFORE }], { plugin: '4.0.10' })
  assert.equal(a.doc.results[0].action, 'commented')
  const b = await closedRun([{ tagName: 'v4.0.10', publishedAt: BEFORE }], { plugin: '4.0.9' })
  assert.equal(b.doc.results[0].reason, 'closed-no-regression')
})
test('S12 a newer known server version alone is a regression', async () => {
  const r = await closedRun([{ tagName: 'v4.0.1', publishedAt: BEFORE }], { plugin: '4.0.0', server: '4.0.2' })
  assert.equal(r.doc.results[0].action, 'commented')
})
test('S12 the release at close time is the newest published at or before closedAt, whatever the list order', async () => {
  const early = { tagName: 'v4.0.0', publishedAt: '2026-10-01T00:00:00Z' }
  const late = { tagName: 'v4.0.5', publishedAt: BEFORE }
  for (const releases of [[early, late], [late, early]]) {
    const r = await closedRun(releases, { plugin: '4.0.3' })
    assert.equal(r.doc.results[0].reason, 'closed-no-regression')
  }
})
test('S12 a failed release list reads closed-no-regression', async () => {
  const r = await closedRun(null, { plugin: '4.0.2' })
  assert.equal(r.doc.results[0].reason, 'closed-no-regression')
})
test('S12 a regression comment is still rate-limited by a recent sighting', async () => {
  const x = d()
  const F = fpOf(x)
  const r = await closedRun([{ tagName: 'v4.0.1', publishedAt: BEFORE }], { plugin: '4.0.2' }, {}, {
    state: { seen: { [F]: { at: atOf(NOW - DAY), url: 'https://github.com/a/b/issues/1' } } },
  })
  assert.equal(r.doc.results[0].reason, 'rate-limited')
})
test('S12 an open hit wins over a closed one; among closed hits the latest closed is used', async () => {
  const x = d()
  const body = `Fingerprint: ${fpOf(x)}`
  const closed9 = hit({ number: 9, state: 'CLOSED', stateReason: 'COMPLETED', closedAt: CLOSED_AT, body })
  const open5 = hit({ number: 5, body })
  const a = await run([x], { 'issue list': ok(JSON.stringify([closed9, open5])) })
  assert.equal(callsOf(a.calls, 'issue', 'comment')[0][2], '5')
  const older = hit({ number: 3, state: 'CLOSED', stateReason: 'NOT_PLANNED', closedAt: '2026-09-01T00:00:00Z', body })
  const newer = hit({ number: 4, state: 'CLOSED', stateReason: 'COMPLETED', closedAt: CLOSED_AT, body })
  for (const order of [[older, newer], [newer, older]]) {
    const b = await run([x], {
      'issue list': ok(JSON.stringify(order)),
      'release list': ok(JSON.stringify([{ tagName: 'v4.0.1', publishedAt: BEFORE }])),
    })
    assert.equal(callsOf(b.calls, 'issue', 'comment')[0][2], '4')
  }
})

// -- S13 --------------------------------------------------------------------------------------------
// Oracle: AC8, D11.
test('S13 five new defects: three creates, two overflow new-issue-cap', async () => {
  const r = await run([1, 2, 3, 4, 5].map((i) => dn(i)))
  assert.equal(callsOf(r.calls, 'issue', 'create').length, 3)
  assert.deepEqual(r.doc.results.map((x) => x.action), ['filed', 'filed', 'filed', 'overflow', 'overflow'])
  assert.deepEqual(r.doc.results.slice(3).map((x) => x.reason), ['new-issue-cap', 'new-issue-cap'])
})
test('S13 seven open hits: five comments, two overflow comment-cap', async () => {
  const r = await run([1, 2, 3, 4, 5, 6, 7].map((i) => dn(i)), { 'issue list': openHitPerFp() })
  assert.equal(callsOf(r.calls, 'issue', 'comment').length, 5)
  assert.deepEqual(r.doc.results.map((x) => x.action), ['commented', 'commented', 'commented', 'commented', 'commented', 'overflow', 'overflow'])
  assert.deepEqual(r.doc.results.slice(5).map((x) => x.reason), ['comment-cap', 'comment-cap'])
})
test('S13 twelve defects: ten searches, the last two overflow search-cap', async () => {
  const r = await run(Array.from({ length: 12 }, (_, i) => dn(i + 1)))
  assert.equal(callsOf(r.calls, 'issue', 'list').length, 10)
  assert.equal(r.doc.results.length, 12)
  for (const i of [10, 11]) {
    assert.equal(r.doc.results[i].action, 'overflow')
    assert.equal(r.doc.results[i].reason, 'search-cap')
  }
  assert.equal(r.doc.results[3].reason, 'new-issue-cap')
})

// -- S14 --------------------------------------------------------------------------------------------
// Oracle: D11 (dedupe by fingerprint).
test('S14 duplicate fingerprints: one action, then duplicate', async () => {
  const a = d({ message: 'failed 12 times', quote: 'one' })
  const b = d({ message: 'failed 99 times', quote: 'two', observation: 'o2' })
  const r = await run([a, b])
  assert.equal(fingerprintOf(a), fingerprintOf(b))
  assert.equal(callsOf(r.calls, 'issue', 'create').length, 1)
  assert.equal(r.doc.results[0].action, 'filed')
  assert.equal(r.doc.results[1].action, 'skipped')
  assert.equal(r.doc.results[1].reason, 'duplicate')
})

// -- S15 --------------------------------------------------------------------------------------------
// Oracle: D5 (a hit counts only when its body holds the fingerprint).
test('S15 a hit whose body lacks the fingerprint is no hit: the defect is filed', async () => {
  const r = await run([d()], { 'issue list': ok(JSON.stringify([hit({ body: 'Fingerprint: to-bug-00000000' })])) })
  assert.equal(r.doc.results[0].action, 'filed')
  assert.equal(callsOf(r.calls, 'issue', 'comment').length, 0)
  assert.equal(callsOf(r.calls, 'issue', 'create').length, 1)
})

// -- S16 --------------------------------------------------------------------------------------------
// Oracle: D13 (entries over 7 days old dropped on write; failed write adds stateWrite "failed").
test('S16 the written state drops entries over 7 days old and keeps the rest', async () => {
  const x = d()
  const F = fpOf(x)
  const r = await run([x], {
    state: { seen: {
      'to-bug-0ld00000': { at: atOf(NOW - 8 * DAY), url: 'https://github.com/a/b/issues/1' },
      'to-bug-fresh000': { at: atOf(NOW - 6 * DAY), url: 'https://github.com/a/b/issues/2' },
    } },
  })
  const w = r.writes.at(-1)
  assert.ok(!('to-bug-0ld00000' in w.seen))
  assert.ok('to-bug-fresh000' in w.seen)
  assert.ok(F in w.seen)
})
test('S16 a failed state write adds stateWrite failed; a good one does not', async () => {
  const bad = await run([d()], { writeOk: false })
  assert.equal(bad.doc.stateWrite, 'failed')
  const good = await run([d()])
  assert.equal(good.doc.stateWrite, undefined)
})
test('S16 an unreadable state (null) reads as empty and an empty object does not fail', async () => {
  const a = await run([d()], { state: null })
  assert.equal(a.doc.results[0].action, 'filed')
  const b = await run([d()], { state: {} })
  assert.equal(b.doc.results[0].action, 'filed')
})

// -- S17 --------------------------------------------------------------------------------------------
// Oracle: AC11-AC14, D16 (as ruled by A1: user-level consent), D17, Files, A1 B1/B2/O10.
const read = (...p) => readFileSync(join(...p), 'utf8')
const section = (text, startRe, endRe) => {
  const m = text.match(startRe)
  assert.ok(m, `heading ${startRe} not found`)
  const rest = text.slice(m.index + m[0].length)
  const e = rest.search(endRe)
  return e < 0 ? rest : rest.slice(0, e)
}

test('S17 init SKILL.md has one Bug-report consent section, --user mode, listing the posted fields', () => {
  const init = read(PLUGIN, 'skills', 'init', 'SKILL.md')
  assert.equal((init.match(/^## Bug-report consent/gm) ?? []).length, 1)
  assert.ok(!/^## Bug-report consent \(both modes\)/m.test(init), 'the both-modes heading is gone')
  const s = section(init, /^## Bug-report consent \(--user mode\)\s*$/m, /\n## /)
  assert.ok(s.includes('bug_reports'))
  assert.match(s, /public/i)
  assert.match(s, /gh account/i)
  assert.ok(s.includes('AskUserQuestion'))
  assert.ok(s.indexOf('No (default)') >= 0 && s.indexOf('No (default)') < s.indexOf('Yes, file bug reports'))
  for (const field of ['component', 'tool', 'error code', 'version', 'call shape', 'fingerprint']) {
    assert.ok(s.toLowerCase().includes(field), `posted-field list names ${field}`)
  }
  assert.ok(s.includes('No bug reports are filed; proposal issues still follow github_feedback.enabled.'))
  assert.ok(!/scrubbed of note text/i.test(init))
  assert.ok(!/Nothing is ever filed/i.test(init))
})
test('S17 config-format.md documents bug_reports as user-level and no longer says global-scoped', () => {
  const t = read(PLUGIN, 'skills', 'manage-schemas', 'references', 'config-format.md')
  assert.ok(t.includes('bug_reports'))
  assert.ok(!t.includes('global-scoped'))
  assert.match(t, /user[- ]level/i)
})
test('S17 the shared github-feedback reference links the bug-report section and states its posted fields', () => {
  const t = read(PLUGIN, 'skills', '_shared', 'github-feedback.md')
  assert.equal(t.split('\n')[0], '# GitHub Feedback Filing')
  const contents = t.split('\n').find((l) => l.startsWith('Contents:'))
  assert.ok(contents.includes('(#bug-reports-product-defects)'))
  assert.match(t, /^## Bug reports \(product defects\)\s*$/m)
  const s = section(t, /^## Bug reports \(product defects\)\s*$/m, /\n## /)
  for (const field of ['component', 'error code', 'version', 'call shape', 'fingerprint']) {
    assert.ok(s.toLowerCase().includes(field), `bug-report section names ${field}`)
  }
  assert.match(s, /user[- ]level/i)
})
test('S17 session-retrospective SKILL.md stays within 14000 bytes and links the bug-report section', () => {
  const t = read(PLUGIN, 'skills', 'session-retrospective', 'SKILL.md')
  assert.ok(Buffer.byteLength(t, 'utf8') <= 14000, `bytes ${Buffer.byteLength(t, 'utf8')}`)
  assert.ok(t.includes('../_shared/github-feedback.md#bug-reports-product-defects'))
  const s7b = section(t, /^### 7b\./m, /\n### /)
  assert.ok(s7b.includes('product'))
  assert.ok(s7b.includes('global-process'))
})
test('S17 routing.md and review-proposals read the product and global-process scopes', () => {
  const routing = read(PLUGIN, 'skills', 'session-retrospective', 'references', 'routing.md')
  assert.ok(routing.includes('global-process'))
  const rp = read(PLUGIN, 'skills', 'review-proposals', 'SKILL.md')
  assert.ok(rp.includes('global-process') && rp.includes('product'))
})
test('S17 the self-improving-workflow guide lists bug_reports: false as user-level', () => {
  const t = read(REPO_ROOT, 'current', 'docs', 'integration-guides', 'self-improving-workflow.md')
  assert.ok(t.includes('bug_reports: false'))
  assert.match(t, /user[- ]level/i)
})

// -- S18 --------------------------------------------------------------------------------------------
// Oracle: A1 B1/R1 (readGate ignores `repo`; every repo is DEFAULT_REPO).
test('S18 a repo in the consent config is ignored: every gh call and the document name the default repo', async () => {
  for (const repoText of ['evil/x', '"evil/x"']) {
    const r = await run([d()], { configText: cfg({ bug: 'true', repo: repoText }) })
    assert.equal(r.doc.gate, 'ran')
    assert.equal(r.doc.repo, DEFAULT_REPO)
    const repos = r.calls.flatMap((c) => flag(c, '--repo'))
    assert.ok(repos.length >= 2, 'list and create carry --repo')
    assert.ok(repos.every((x) => x === DEFAULT_REPO), JSON.stringify(repos))
    assert.ok(!JSON.stringify(r.calls).includes('evil/x'))
  }
})
test('S18 the closed-hit path (release list, comment) also uses the default repo', async () => {
  const x = d()
  const r = await run([x], {
    configText: cfg({ bug: 'true', repo: 'evil/x' }),
    versions: { plugin: '4.0.2' },
    'issue list': ok(JSON.stringify([hit({ state: 'CLOSED', stateReason: 'COMPLETED', closedAt: CLOSED_AT, body: `Fingerprint: ${fpOf(x)}` })])),
    'release list': ok(JSON.stringify([{ tagName: 'v4.0.1', publishedAt: BEFORE }])),
  })
  assert.equal(r.doc.results[0].action, 'commented')
  const repos = r.calls.flatMap((c) => flag(c, '--repo'))
  assert.ok(repos.length >= 3 && repos.every((v) => v === DEFAULT_REPO), JSON.stringify(repos))
})

// -- S20 --------------------------------------------------------------------------------------------
// Oracle: A1 O8 (the gate opens only for exactly one block-level bug_reports line, `true`).
for (const [name, configText] of [
  ['true twice', cfg({ bug: 'true', extra: ['bug_reports: true'] })],
  ['true then false', cfg({ bug: 'true', extra: ['bug_reports: false'] })],
  ['false then true', cfg({ bug: 'false', extra: ['bug_reports: true'] })],
  ['true three times', cfg({ bug: 'true', extra: ['bug_reports: true', 'bug_reports: true'] })],
  ['only a commented line', cfg({ bug: null, extra: ['# bug_reports: true'] })],
  ['a top-level line outside the block', 'bug_reports: true\n'],
]) {
  test(`S20 gate disabled: ${name}`, async () => {
    const r = await run([d()], { configText })
    assert.equal(r.doc.gate, 'disabled')
    assert.equal(r.calls.length, 0)
    assert.equal(r.stateReads(), 0)
    assert.equal(r.writes.length, 0)
  })
}
test('S20 control: exactly one true line among other keys opens the gate', async () => {
  const r = await run([d()], { configText: cfg({ bug: 'true', enabled: 'false', extra: ['other: 1'] }) })
  assert.equal(r.doc.gate, 'ran')
})

// -- S21 --------------------------------------------------------------------------------------------
// Oracle: A2 N1 'never retry' (retires the A1 O4 re-search: after one failed create attempt nothing more is
// searched or posted for that defect that run; the next run's search finds the issue or files it).
test('S21 a failed create is not followed by a re-search: an issue that a second search would find is not read', async () => {
  const x = d()
  const F = fpOf(x)
  const found = hit({ number: 61, url: `https://github.com/${REPO}/issues/61`, body: `Fingerprint: ${F}` })
  const r = await run([x], {
    'issue list': (_a, n) => (n === 1 ? ok('[]') : ok(JSON.stringify([found]))),
    'issue create': fail(),
  })
  assert.equal(callsOf(r.calls, 'issue', 'list').length, 1, 'one search only')
  assert.equal(callsOf(r.calls, 'issue', 'create').length, 1)
  assert.equal(callsOf(r.calls, 'issue', 'comment').length, 0)
  assert.equal(r.doc.results[0].action, 'skipped')
  assert.equal(r.doc.results[0].reason, 'gh-create-failed')
  assert.deepEqual(r.writes.at(-1).seen[F], { at: WRITTEN_AT, url: null, status: 'create-unconfirmed' })
})
test('S21 two defects whose creates fail: one search and one create attempt each, both create-unconfirmed', async () => {
  const defects = [dn(1), dn(2)]
  const r = await run(defects, { 'issue create': fail() })
  assert.equal(callsOf(r.calls, 'issue', 'list').length, 2)
  assert.equal(callsOf(r.calls, 'issue', 'create').length, 2)
  assert.deepEqual(r.doc.results.map((x) => x.reason), ['gh-create-failed', 'gh-create-failed'])
  for (const x of defects) assert.deepEqual(r.writes.at(-1).seen[fpOf(x)], { at: WRITTEN_AT, url: null, status: 'create-unconfirmed' })
})
test('S21 a failed create takes no search outside the ten-search cap: twelve defects, ten searches, the last two overflow search-cap', async () => {
  const r = await run(Array.from({ length: 12 }, (_, i) => dn(i + 1)), { 'issue create': fail() })
  assert.equal(callsOf(r.calls, 'issue', 'list').length, 10)
  assert.equal(r.doc.results.length, 12)
  for (const i of [10, 11]) {
    assert.equal(r.doc.results[i].action, 'overflow')
    assert.equal(r.doc.results[i].reason, 'search-cap')
  }
})
test('S21 a failed create changes nothing for a later defect that has an open hit: it is commented, not created', async () => {
  const d1 = dn(1)
  const d2 = dn(2)
  const r = await run([d1, d2], {
    'issue list': (args) => (searchedFp(args) === fpOf(d1) ? ok('[]') : ok(JSON.stringify([hit({ number: 9, body: `Fingerprint: ${fpOf(d2)}` })]))),
    'issue create': fail(),
  })
  assert.equal(r.doc.results[0].reason, 'gh-create-failed')
  assert.equal(r.doc.results[1].action, 'commented')
  assert.equal(callsOf(r.calls, 'issue', 'comment').length, 1)
  assert.equal(callsOf(r.calls, 'issue', 'list').length, 2)
})

// -- S22 --------------------------------------------------------------------------------------------
// Oracle: A1 O6 (a throwing writeTemp is caught as gh-create-failed / gh-comment-failed with no gh call; the
// state write still runs); A2 N1 (a create that never reached gh leaves no state entry, not even
// create-unconfirmed).
test('S22 a writeTemp that throws on the second create skips it as gh-create-failed and keeps the first filing in the state', async () => {
  const x1 = dn(1)
  const x2 = dn(2)
  const r = await run([x1, x2], { failTemp: (text) => text.includes(fpOf(x2)) })
  assert.equal(r.doc.results[0].action, 'filed')
  assert.equal(r.doc.results[1].action, 'skipped')
  assert.equal(r.doc.results[1].reason, 'gh-create-failed')
  assert.equal(callsOf(r.calls, 'issue', 'create').length, 1)
  assert.ok(r.writes.length >= 1, 'state written')
  assert.ok(fpOf(x1) in r.writes.at(-1).seen)
  assert.ok(!(fpOf(x2) in r.writes.at(-1).seen), 'no entry for a create that never reached gh')
  assert.equal(r.doc.stateWrite, undefined)
})
test('S22 a writeTemp that throws on the second comment skips it as gh-comment-failed with no comment call', async () => {
  const x1 = dn(1)
  const x2 = dn(2)
  const r = await run([x1, x2], { 'issue list': openHitPerFp(), failTemp: (text) => text.includes(fpOf(x2)) })
  assert.equal(r.doc.results[0].action, 'commented')
  assert.equal(r.doc.results[1].action, 'skipped')
  assert.equal(r.doc.results[1].reason, 'gh-comment-failed')
  assert.equal(callsOf(r.calls, 'issue', 'comment').length, 1)
  assert.ok(fpOf(x1) in r.writes.at(-1).seen)
  assert.ok(!(fpOf(x2) in r.writes.at(-1).seen))
})
test('S22 a writeTemp that throws for the only defect still returns a document, posts nothing and records no entry', async () => {
  const x = d()
  const r = await run([x], { failTemp: () => true })
  assert.equal(r.doc.results[0].action, 'skipped')
  assert.equal(r.doc.results[0].reason, 'gh-create-failed')
  assert.equal(callsOf(r.calls, 'issue', 'create').length, 0)
  assert.ok(!r.writes.some((w) => w.seen && fpOf(x) in w.seen), 'no state entry, create-unconfirmed included')
})

// -- S23 --------------------------------------------------------------------------------------------
// Oracle: A1 O5, B1/R1 (the consent gate is read only from the user-level config; there is no --config),
// run-planner header comment (exit 0 with a 'run-wave/bug-report-v1' document, exit 2 only for bad
// arguments), D12/D15 (gh-unavailable when gh cannot run).
// SAFETY: see the file header. PATH is an empty directory; gh is never reachable.
const SCRUBBED_ENV = [
  'AGENT_CONFIG_DIR', 'TASK_ORCHESTRATOR_API_URL', 'TASK_ORCHESTRATOR_API_TOKEN', 'TASK_ORCHESTRATOR_MODE',
  'TASK_ORCHESTRATOR_HOME', 'TASK_ORCHESTRATOR_CEILING', 'RETRO_HOOK_DEBUG', 'TASK_ORCHESTRATOR_GH_SHIM', 'FAKE_GH_DIR',
]
const RUN_PLANNER = join(PLUGIN, 'scripts', 'run-planner.mjs')

async function startCounter(handler = null) {
  const requests = []
  const sockets = new Set()
  const server = createServer((req, res) => {
    requests.push(req.url)
    res.setHeader('content-type', 'application/json')
    const m = handler && /^\/api\/v1\/ops\/([^/?]+)/.exec(req.url)
    if (m) {
      const chunks = []
      req.on('data', (c) => chunks.push(c))
      req.on('end', () => {
        let body = {}
        try { body = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}') } catch { body = {} }
        const out = handler(m[1], body)
        // A handler answering {__error: <code>} makes the op fail with that error code.
        res.end(JSON.stringify(out && out.__error ? { ok: false, error: { code: out.__error, kind: 'permanent', message: 'x' }, meta: { reqId: 'x' } } : { ok: true, data: out, meta: { reqId: 'x' } }))
      })
      return
    }
    res.end(JSON.stringify({ version: '4.0.1', features: ['seats'] }))
  })
  server.on('connection', (s) => { sockets.add(s); s.on('close', () => sockets.delete(s)) })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  return {
    requests,
    url: `http://127.0.0.1:${server.address().port}`,
    close: () => new Promise((resolve) => { for (const s of sockets) s.destroy(); server.close(resolve) }),
  }
}

// project / user: config text or null (absent). args: the CLI arguments after `bug-report`, `<IN>` replaced
// by the input file; defects: the input file's defects.
async function cli({ project = null, user = null, args = ['--in', '<IN>'], defects = [d()], extraEnv = null, handler = null }) {
  const sbx = makeSandbox({ config: project, scope: 'project' })
  const counter = await startCounter(handler)
  try {
    const emptyPath = join(sbx.base, 'nopath')
    mkdirSync(emptyPath)
    for (const dir of [emptyPath, sbx.cfg]) {
      for (const name of ['gh', 'gh.exe', 'gh.cmd', 'gh.bat']) {
        assert.ok(!existsSync(join(dir, name)), `SAFETY: ${name} exists in ${dir}`)
      }
    }
    const env = { ...process.env }
    for (const k of Object.keys(env)) if (k.toLowerCase() === 'path') delete env[k]
    for (const k of SCRUBBED_ENV) delete env[k]
    Object.assign(env, {
      PATH: emptyPath,
      TEMP: sbx.tmp, TMP: sbx.tmp, TMPDIR: sbx.tmp,
      TASK_ORCHESTRATOR_HOME: sbx.home,
      TASK_ORCHESTRATOR_CEILING: sbx.base,
      AGENT_CONFIG_DIR: sbx.cfg,
      TASK_ORCHESTRATOR_API_URL: counter.url,
    })
    if (extraEnv) Object.assign(env, extraEnv(sbx))
    assert.equal(env.PATH, emptyPath, 'SAFETY: PATH is the empty directory')
    assert.equal(Object.keys(env).filter((k) => k.toLowerCase() === 'path').length, 1, 'SAFETY: a single PATH variable')
    if (user !== null) {
      const target = userConfigPath(env)
      mkdirSync(dirname(target), { recursive: true })
      writeFileSync(target, user, 'utf-8')
    }
    const inFile = join(sbx.tmp, 'in.json')
    writeFileSync(inFile, JSON.stringify({ defects }), 'utf-8')
    const argv = ['bug-report', ...args.map((a) => (a === '<IN>' ? inFile : a))]
    const res = await new Promise((resolve, reject) => {
      const child = spawn(process.execPath, [RUN_PLANNER, ...argv], { env, cwd: sbx.cfg })
      let stdout = ''
      let stderr = ''
      const timer = setTimeout(() => { child.kill(); reject(new Error('bug-report timed out')) }, 20000)
      child.stdout.on('data', (c) => { stdout += c })
      child.stderr.on('data', (c) => { stderr += c })
      child.on('error', (e) => { clearTimeout(timer); reject(e) })
      child.on('close', (status) => {
        clearTimeout(timer)
        let json = null
        try { json = JSON.parse(stdout.trim()) } catch { json = null }
        resolve({ status, stdout, stderr, json })
      })
    })
    let ghCalls = []
    if (env.FAKE_GH_DIR) {
      try { ghCalls = readFileSync(join(env.FAKE_GH_DIR, 'calls.jsonl'), 'utf8').split(/\r?\n/).filter(Boolean).map((l) => JSON.parse(l)) } catch { ghCalls = [] }
    }
    return { ...res, requests: counter.requests.length, ghCalls }
  } finally {
    await counter.close()
    sbx.cleanup()
  }
}

test('S23 a project config with bug_reports true and no user config: disabled and no request', async () => {
  const r = await cli({ project: cfg({ bug: 'true' }) })
  assert.equal(r.status, 0, r.stderr)
  assert.equal(r.json.contract, CONTRACT)
  assert.equal(r.json.gate, 'disabled')
  assert.equal(r.requests, 0)
})
test('S23 a project config with true and a user config with false: disabled and no request', async () => {
  const r = await cli({ project: cfg({ bug: 'true' }), user: cfg({ bug: 'false' }) })
  assert.equal(r.status, 0, r.stderr)
  assert.equal(r.json.gate, 'disabled')
  assert.equal(r.requests, 0)
})
test('S23 a user config with bug_reports true and gh unreachable: gh-unavailable after one request', async () => {
  const r = await cli({ user: cfg({ bug: 'true' }) })
  assert.equal(r.status, 0, r.stderr)
  assert.equal(r.json.contract, CONTRACT)
  assert.equal(r.json.gate, 'gh-unavailable')
  assert.equal(r.requests, 1)
})
test('S23 a project false does not close a user true', async () => {
  const r = await cli({ project: cfg({ bug: 'false' }), user: cfg({ bug: 'true' }) })
  assert.equal(r.json.gate, 'gh-unavailable')
  assert.equal(r.requests, 1)
})
test('S23 a user config with the key twice stays closed', async () => {
  const r = await cli({ user: cfg({ bug: 'true', extra: ['bug_reports: true'] }) })
  assert.equal(r.status, 0, r.stderr)
  assert.equal(r.json.gate, 'disabled')
  assert.equal(r.requests, 0)
})
test('S23 --config is not an argument: exit 2 and no request', async () => {
  const r = await cli({ user: cfg({ bug: 'true' }), args: ['--in', '<IN>', '--config', 'whatever.yaml'] })
  assert.equal(r.status, 2, r.stderr)
  assert.match(r.stderr, /unknown argument --config/)
  assert.equal(r.requests, 0)
})
test('S23 a missing --in is a bad argument: exit 2', async () => {
  const r = await cli({ user: cfg({ bug: 'true' }), args: [] })
  assert.equal(r.status, 2, r.stderr)
  assert.match(r.stderr, /--in <file> or --root <id> is required/)
  assert.equal(r.requests, 0)
})

// -- S24 --------------------------------------------------------------------------------------------
// Oracle: task-scope D7 and AC3: with TASK_ORCHESTRATOR_GH_SHIM naming evals/retrospective/fake-gh.mjs and PATH the
// empty directory, the CLI files exactly one issue through the shim, and the create argv carries no --label.
// SAFETY: the shim never touches the network; PATH is still the empty directory (S23 asserts no gh in it).
test('S24 the gh shim seam: the CLI files one issue through fake-gh with no --label', async () => {
  const shim = join(PLUGIN, 'evals', 'retrospective', 'fake-gh.mjs')
  let ghDir = null
  const r = await cli({
    user: cfg({ bug: 'true' }),
    extraEnv: (sbx) => { ghDir = join(sbx.base, 'ghlog'); return { TASK_ORCHESTRATOR_GH_SHIM: shim, FAKE_GH_DIR: ghDir } },
  })
  assert.equal(r.status, 0, r.stderr)
  assert.equal(r.json.gate, 'ran')
  assert.equal(r.json.results.length, 1)
  assert.equal(r.json.results[0].action, 'filed')
  assert.equal(r.json.results[0].url, 'https://github.com/jpicklyk/task-orchestrator/issues/990001')
  const creates = r.ghCalls.filter((c) => c.argv[0] === 'issue' && c.argv[1] === 'create')
  assert.equal(creates.length, 1)
  assert.ok(!creates[0].argv.includes('--label'), 'no --label on create')
})

// -- S25 --------------------------------------------------------------------------------------------
// Oracle: user ruling 2026-11-19 (read from retro-metrics) and fix cycle A2 item (1): bug-report takes --root <id>
// with --run <id>... / --item <id>... and gathers the defects of the members' session-tracking notes over REST as
// retro-metrics does (no input file); exactly one of --in and --root, else exit 2.
// Fix cycle A4 rulings: B2 only a gathered defect named by --product <fingerprint>[:<observation>] is a candidate (scope
// product, observation from the flag); every other gathered defect is skipped with reason not-product; no --product
// files nothing; a malformed --product exits 2 with a reason. B1 gaps and a missing API URL are visible (gaps, and
// input 'unavailable'). O1 the gate is read first: a closed gate does no REST read. O15 --root is the project root's
// item id: only members whose item rootId matches count.
// SAFETY: PATH is the empty directory; the shim is the only gh; the REST server is a loopback fake.
const ROOT_ID = 'ce064d2c-7c03-4eac-ae7d-89a14c2ba273'
const RUN_ID = 'r-202611200000-aaaaaaaa'
const MEMBER = 'aaaaaaaa-1111-4111-8111-111111111111'
const OBS = 'bbbbbbbb'
const TRACKING = 'Outcome: done. defect: retro-trigger.mjs, hook, exit:1, the hook stopped; Friction: none'
const TRACKED_FP = defectFingerprint({ component: 'plugin', tool: 'retro-trigger.mjs', op: 'hook', code: 'exit:1', message: 'the hook stopped' })
function restHandler(tool, body) {
  if (tool === 'runs_read' && body.select === 'seats') return { seats: [{ itemId: MEMBER }] }
  if (tool === 'runs_read') return {}
  if (tool === 'notes_read') return body.key === 'session-tracking' ? { body: TRACKING } : { body: null }
  if (tool === 'items_read') return { id: MEMBER, type: 'feature-task', rootId: ROOT_ID }
  return {}
}
const shimEnv = (sbx) => ({ TASK_ORCHESTRATOR_GH_SHIM: join(PLUGIN, 'evals', 'retrospective', 'fake-gh.mjs'), FAKE_GH_DIR: join(sbx.base, 'ghlog') })
const rootArgs = (...more) => ['--root', ROOT_ID, '--run', RUN_ID, ...more]
const creates = (r) => r.ghCalls.filter((c) => c.argv[0] === 'issue' && c.argv[1] === 'create')

test('S25 --root with --product gathers the named defect over REST and files it through the shim with no input file', async () => {
  const r = await cli({ user: cfg({ bug: 'true' }), args: rootArgs('--product', `${TRACKED_FP}:${OBS}`), handler: restHandler, extraEnv: shimEnv })
  assert.equal(r.status, 0, r.stderr)
  assert.equal(r.json.gate, 'ran')
  assert.equal(r.json.input, 'complete')
  assert.deepEqual(r.json.gaps, [])
  assert.equal(r.json.results.length, 1)
  assert.equal(r.json.results[0].action, 'filed')
  assert.equal(r.json.results[0].fingerprint, TRACKED_FP)
  assert.equal(r.json.results[0].observation, OBS)
  assert.equal(creates(r).length, 1)
})
test('S25 --product without an observation files the defect with a null observation', async () => {
  const r = await cli({ user: cfg({ bug: 'true' }), args: ['--root', ROOT_ID, '--item', MEMBER, '--product', TRACKED_FP], handler: restHandler, extraEnv: shimEnv })
  assert.equal(r.status, 0, r.stderr)
  assert.equal(r.json.results.length, 1)
  assert.equal(r.json.results[0].action, 'filed')
  assert.equal(r.json.results[0].observation, null)
})
test('S25 --root without --product files nothing: the gathered defect is skipped not-product', async () => {
  const r = await cli({ user: cfg({ bug: 'true' }), args: rootArgs(), handler: restHandler, extraEnv: shimEnv })
  assert.equal(r.status, 0, r.stderr)
  assert.equal(r.json.gate, 'ran')
  assert.equal(r.json.results.length, 1)
  assert.equal(r.json.results[0].fingerprint, TRACKED_FP)
  assert.equal(r.json.results[0].action, 'skipped')
  assert.equal(r.json.results[0].reason, 'not-product')
  assert.equal(creates(r).length, 0)
})
test('S25 a --product naming another fingerprint leaves the gathered defect skipped not-product and reports the missing one as a gap', async () => {
  const other = 'to-bug-0badc0de'
  const r = await cli({ user: cfg({ bug: 'true' }), args: rootArgs('--product', other), handler: restHandler, extraEnv: shimEnv })
  assert.equal(r.status, 0, r.stderr)
  assert.equal(creates(r).length, 0)
  assert.deepEqual(r.json.results.map((x) => [x.fingerprint, x.action, x.reason]), [[TRACKED_FP, 'skipped', 'not-product']])
  assert.deepEqual(r.json.gaps, [{ section: 'product', reason: 'not_gathered', fingerprint: other }])
  assert.equal(r.json.input, 'partial')
})
test('S25 --root with members that carry no defect files nothing', async () => {
  const none = (tool, body) => (tool === 'notes_read' && body.key === 'session-tracking' ? { body: 'Outcome: done.' } : restHandler(tool, body))
  const r = await cli({ user: cfg({ bug: 'true' }), args: rootArgs('--product', TRACKED_FP), handler: none, extraEnv: shimEnv })
  assert.equal(r.status, 0, r.stderr)
  assert.equal(r.json.results.length, 0)
  assert.equal(creates(r).length, 0)
})
test('S25 a member of another root is not gathered', async () => {
  const foreign = (tool, body) => (tool === 'items_read' ? { id: MEMBER, type: 'feature-task', rootId: '11111111-2222-4333-8444-555555555555' } : restHandler(tool, body))
  const r = await cli({ user: cfg({ bug: 'true' }), args: rootArgs('--product', TRACKED_FP), handler: foreign, extraEnv: shimEnv })
  assert.equal(r.status, 0, r.stderr)
  assert.equal(creates(r).length, 0)
  assert.deepEqual(r.json.results, [])
})
test('S25 a failing REST read is a gap and the input is partial', async () => {
  const failing = (tool, body) => (tool === 'runs_read' ? { __error: 'internal' } : restHandler(tool, body))
  const r = await cli({ user: cfg({ bug: 'true' }), args: ['--root', ROOT_ID, '--run', RUN_ID, '--item', MEMBER, '--product', TRACKED_FP], handler: failing, extraEnv: shimEnv })
  assert.equal(r.status, 0, r.stderr)
  assert.equal(r.json.gate, 'ran')
  assert.equal(r.json.input, 'partial')
  assert.ok(r.json.gaps.length > 0)
  assert.ok(r.json.gaps.every((g) => typeof g.section === 'string' && g.reason === 'internal'), JSON.stringify(r.json.gaps))
})
test('S25 no API URL: gate ran, input unavailable with the gap, nothing filed, exit 0', async () => {
  const r = await cli({ user: cfg({ bug: 'true' }), args: rootArgs('--product', TRACKED_FP), extraEnv: (sbx) => ({ ...shimEnv(sbx), TASK_ORCHESTRATOR_API_URL: '' }) })
  assert.equal(r.status, 0, r.stderr)
  assert.equal(r.json.gate, 'ran')
  assert.equal(r.json.input, 'unavailable')
  assert.deepEqual(r.json.gaps, [{ section: 'api', reason: 'no_api_url' }])
  assert.deepEqual(r.json.results, [])
  assert.equal(r.requests, 0)
  assert.equal(creates(r).length, 0)
})
test('S25 --root with the gate closed reads nothing: no request, no gh call, gate disabled', async () => {
  const r = await cli({ user: cfg({ bug: 'false' }), args: rootArgs('--product', TRACKED_FP), handler: restHandler, extraEnv: shimEnv })
  assert.equal(r.status, 0, r.stderr)
  assert.equal(r.json.gate, 'disabled')
  assert.deepEqual(r.json.results, [])
  assert.deepEqual(r.json.gaps, [])
  assert.equal(r.json.input, undefined)
  assert.equal(r.requests, 0)
  assert.equal(r.ghCalls.length, 0)
})
test('S25 --in and --root together exit 2', async () => {
  const r = await cli({ user: cfg({ bug: 'true' }), args: ['--in', '<IN>', '--root', ROOT_ID] })
  assert.equal(r.status, 2, r.stderr)
  assert.match(r.stderr, /--in and --root are mutually exclusive/)
  assert.equal(r.requests, 0)
})
test('S25 neither --in nor --root exits 2, and --run alone is not enough', async () => {
  const r = await cli({ user: cfg({ bug: 'true' }), args: ['--run', RUN_ID] })
  assert.equal(r.status, 2, r.stderr)
  assert.match(r.stderr, /--in <file> or --root <id> is required/)
  assert.equal(r.requests, 0)
})
test('S25 a malformed --product exits 2 with a reason: fingerprint, observation, and --product with --in', async () => {
  const cases = [
    [rootArgs('--product', 'to-bug-xyz'), /--product fingerprint must be to-bug- plus 8 hex/],
    [rootArgs('--product', 'abcdef12'), /--product fingerprint must be to-bug- plus 8 hex/],
    [rootArgs('--product', `${TRACKED_FP}:short`), /--product observation must be 8 or more hex/],
    [rootArgs('--product', `${TRACKED_FP}:zzzzzzzzzz`), /--product observation must be 8 or more hex/],
    [rootArgs('--product', `${TRACKED_FP}:${OBS}:more`), /--product observation must be 8 or more hex/],
    [rootArgs('--product'), /--product needs a value/],
    [['--in', '<IN>', '--product', TRACKED_FP], /go with --root, not --in/],
  ]
  for (const [args, reason] of cases) {
    const r = await cli({ user: cfg({ bug: 'true' }), args })
    assert.equal(r.status, 2, `${args.join(' ')}: ${r.stderr}`)
    assert.match(r.stderr, reason)
    assert.equal(r.requests, 0)
  }
})
test('S25 a --root value that is not an item id exits 2 with a reason', async () => {
  const r = await cli({ user: cfg({ bug: 'true' }), args: ['--root', 'not-an-id', '--run', RUN_ID] })
  assert.equal(r.status, 2, r.stderr)
  assert.match(r.stderr, /--root needs an item id/)
  assert.equal(r.requests, 0)
})
