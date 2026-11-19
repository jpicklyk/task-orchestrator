// bug-report.test.mjs -- scripts/lib/bug-report.mjs (runBugReport with injected deps, scrubFindings,
// fingerprintOf) and the docs the item edits.
//
// Scenario ids S1-S17 match the item's frozen `test-plan` note. Oracles: task-scope decisions D2-D17 and
// acceptance criteria AC1-AC14 (named in the comment above each test). This file never opens
// bug-report.mjs, retro-metrics.mjs or run-planner.mjs: only their declared exports are imported.
//
// SAFETY: the real gh is never run and the run-planner `bug-report` subcommand is never spawned. Every
// effect (gh, state file, clock, temp files) is a fake injected through `deps`; nothing here touches the
// network, the filesystem state file or GitHub. The CLI parts of S10 (bad arg exit 2, gate-off CLI exit 0)
// are therefore not covered here.
//
// The test-plan's helper names (fileBugReports, gate, state, writeBody) are not exports; the same oracles
// are exercised through runBugReport (gate = configText, state = readState/writeState, writeBody =
// writeTemp captured by body-file path).

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { runBugReport, scrubFindings, fingerprintOf, CONTRACT } from '../lib/bug-report.mjs'
import { defectFingerprint, defectComponent, normaliseMessage } from '../lib/retro-metrics.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = join(HERE, '..', '..')
const REPO_ROOT = join(PLUGIN, '..', '..')

const REPO = 'acme/widgets'
const DAY = 24 * 60 * 60 * 1000
const NOW = Date.UTC(2026, 10, 19, 12, 0, 0)
const VERSIONS = { plugin: '4.0.2', server: '4.0.1' }
const FOOTER = 'Reported automatically by task-orchestrator session-retrospective'
const UUID_A = 'aaaaaaaa-1111-4111-8111-111111111111'
const UUID_B = 'bbbbbbbb-2222-4222-8222-222222222222'

// -- fixtures ---------------------------------------------------------------------------------------

// A config text with the gate path extensions -> claude-code -> retrospective -> github_feedback.
// `bug` is the raw YAML scalar text for bug_reports (null omits the key).
function cfg({ bug = 'true', repo = REPO, enabled = null } = {}) {
  const lines = ['extensions:', '  claude-code:', '    retrospective:', '      github_feedback:']
  if (enabled !== null) lines.push(`        enabled: ${enabled}`)
  if (bug !== null) lines.push(`        bug_reports: ${bug}`)
  if (repo !== null) lines.push(`        repo: ${repo}`)
  return lines.join('\n') + '\n'
}

// A qualifying defect by construction: kind defect, scope product, a listed signal, and non-empty
// tool, op, code (not an environment code), message and quote.
const d = (o = {}) => ({
  kind: 'defect', scope: 'product', signal: 'tool-error', tool: 'items_write', op: 'create',
  code: 'validation_error', message: 'Item title is required', quote: 'raw quote text', ...o,
})
// Distinct defects (distinct op => distinct fingerprint).
const dn = (i, o = {}) => d({ op: `op-${i}`, ...o })
// Fingerprint oracle: defectFingerprint of the inputs with the component from defectComponent(tool).
const fpOf = (x) => defectFingerprint({ component: defectComponent(x.tool), tool: x.tool, op: x.op, code: x.code, message: x.message })

const flag = (args, f) => args.reduce((acc, a, i) => (a === f ? [...acc, args[i + 1]] : acc), [])
const callsOf = (calls, a, b) => calls.filter((c) => c[0] === a && c[1] === b)
const hit = (o = {}) => ({ number: 7, url: `https://github.com/${REPO}/issues/7`, state: 'OPEN', stateReason: null, closedAt: null, labels: [], body: '', ...o })
const ok = (stdout = '') => ({ status: 0, stdout })
const fail = () => ({ status: 1, stdout: '' })
const searchedFp = (args) => args[args.indexOf('--search') + 1].split(' ')[0]

// Fake gh keyed on args[0] + ' ' + args[1]. An override is a {status, stdout} or a function (args, nth).
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
    writeTemp: (text) => { const p = `fake-temp/body-${temps.size + 1}.md`; temps.set(p, text); return p },
    removeTemp: () => {},
  }
  return { calls, temps, writes, deps, stateReads: () => stateReads, bodyOf: (args) => temps.get(flag(args, '--body-file')[0]) }
}

async function run(defects, o = {}) {
  const env = makeEnv(o)
  const doc = await runBugReport({
    defects, configText: o.configText ?? cfg(), denyNames: o.denyNames ?? [], versions: o.versions ?? VERSIONS, deps: env.deps,
  })
  return { doc, ...env }
}

// A list handler answering an open hit per searched fingerprint, with the fingerprint in its body.
const openHitPerFp = () => (args) => {
  const fp = searchedFp(args)
  return ok(JSON.stringify([hit({ number: 50, body: `Fingerprint: ${fp}` })]))
}

// -- S1 ---------------------------------------------------------------------------------------------
// Oracle: AC5, D7, D9 (comment lines), D15 (result shape and line).
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
// Oracle: AC4, D5 (search shape), D6 (create shape), D13 (state), D15 (line, gh auth first).
test('S2 no hit: one labelled create with a body file, filed with the url, state records it', async () => {
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
  assert.deepEqual(flag(creates[0], '--label'), ['bug', 'auto-reported'])
  assert.equal(flag(creates[0], '--body-file').length, 1)
  assert.equal(flag(creates[0], '--title').length, 1)
  assert.equal(callsOf(r.calls, 'issue', 'comment').length, 0)
  assert.equal(r.doc.results[0].action, 'filed')
  assert.equal(r.doc.results[0].url, url)
  assert.equal(r.doc.results[0].line, `bug-report ${F}: filed ${url}`)
  assert.equal(r.writes.at(-1).seen[F].url, url)
})

// -- S3 ---------------------------------------------------------------------------------------------
// Oracle: AC7, D6 (title), D9 (body lines and order, exact footer, call shape, no observation id or quote).
test('S3 title and body hold every D9 line in order, the exact footer, a typed call shape, no quote or observation id', async () => {
  const x = d({
    quote: 'SECRETQUOTEZ', observation: 'obs-f6ec543b', callShape: { itemId: 'x', limit: 3 },
    expected: 'The create succeeds', actual: 'Got an error',
  })
  const F = fpOf(x)
  const comp = defectComponent(x.tool)
  const r = await run([x])
  const create = callsOf(r.calls, 'issue', 'create')[0]
  const title = flag(create, '--title')[0]
  assert.equal(title, `[bug] ${comp}: items_write create: validation_error - ${normaliseMessage(x.message)}`)
  const body = r.bodyOf(create)
  const lines = body.split(/\r?\n/)
  const at = (prefix) => lines.findIndex((l) => l.startsWith(prefix))
  const expectLine = (l) => assert.ok(lines.includes(l), `missing line: ${l}`)
  expectLine('## Defect')
  expectLine(`- Component: ${comp}`)
  expectLine('- Plugin version: 4.0.2')
  expectLine('- Server version: 4.0.1')
  expectLine('- Error code: validation_error')
  expectLine(`- Message (normalised): ${normaliseMessage(x.message)}`)
  expectLine('- Call shape: `{"itemId":"string","limit":"number"}`')
  expectLine(`Fingerprint: ${F}`)
  expectLine('---')
  const order = ['## Defect', '- Component:', '- Plugin version:', '- Server version:', '- Tool and op:', '- Error code:',
    '- Message (normalised):', '- Call shape:', 'Expected:', 'Actual:', 'Fingerprint:', '---']
  const idx = order.map(at)
  assert.ok(idx.every((i) => i >= 0), `all D9 lines present: ${JSON.stringify(idx)}`)
  assert.deepEqual([...idx].sort((a, b) => a - b), idx, 'D9 order')
  const toolOp = lines[at('- Tool and op:')]
  assert.ok(toolOp.includes('items_write') && toolOp.includes('create'))
  assert.ok(lines[at('Expected:')].includes('The create succeeds'))
  assert.ok(lines[at('Actual:')].includes('Got an error'))
  const nonblank = lines.filter((l) => l.trim() !== '')
  assert.equal(nonblank.at(-1), FOOTER)
  assert.equal(nonblank.at(-2), '---')
  for (const text of [title, body]) {
    assert.ok(!text.includes('SECRETQUOTEZ'), 'quote never posted')
    assert.ok(!text.includes('obs-f6ec543b'), 'observation id never posted')
  }
})

test('S3 probes: unknown server version, absent call shape and absent actual read the D9 fallbacks', async () => {
  const r = await run([d()], { versions: { plugin: '4.0.2' } })
  const lines = r.bodyOf(callsOf(r.calls, 'issue', 'create')[0]).split(/\r?\n/)
  assert.ok(lines.includes('- Server version: unknown'))
  assert.ok(lines.includes('- Call shape: not recorded'))
  assert.ok(lines.some((l) => l.startsWith('Actual:') && l.includes('not recorded')))
})

test('S3 probes: a CRLF actual becomes one line of at most 200 characters', async () => {
  const r = await run([d({ actual: 'first line\r\nsecond line' })])
  const body = r.bodyOf(callsOf(r.calls, 'issue', 'create')[0])
  assert.ok(!body.includes('\r'), 'no CR in the posted body')
  const line = body.split('\n').find((l) => l.startsWith('Actual:'))
  assert.ok(line.includes('first line') && line.includes('second line'))
  const long = await run([d({ actual: 'q'.repeat(300) })])
  const l2 = long.bodyOf(callsOf(long.calls, 'issue', 'create')[0]).split('\n').find((l) => l.startsWith('Actual:'))
  const value = l2.slice('Actual:'.length).trim()
  assert.ok(value.length <= 200, `actual value length ${value.length}`)
  assert.ok(value.startsWith('q'.repeat(190)))
})

test('S3 probe: the title message part is the normalised message cut to 80 characters', async () => {
  const r = await run([d({ message: 'z'.repeat(200) })])
  const title = flag(callsOf(r.calls, 'issue', 'create')[0], '--title')[0]
  assert.ok(title.endsWith(` - ${'z'.repeat(80)}`), title)
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
// Oracle: AC1, D2, D15 (gate off: no gh call, no state access).
for (const [name, configText] of [
  ['bug_reports absent', cfg({ bug: null })],
  ['bug_reports false', cfg({ bug: 'false' })],
  ['bug_reports quoted "true"', cfg({ bug: '"true"' })],
  ['bug_reports yes', cfg({ bug: 'yes' })],
  ['bug_reports 1', cfg({ bug: '1' })],
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

test('S5 control: the bare scalar true opens the gate, with or without enabled; repo defaults upstream', async () => {
  const a = await run([d()], { configText: cfg({ bug: 'true', enabled: 'false' }) })
  assert.equal(a.doc.gate, 'ran')
  const b = await run([d()], { configText: cfg({ bug: 'true', repo: null }) })
  assert.equal(b.doc.gate, 'ran')
  assert.equal(b.doc.repo, 'jpicklyk/task-orchestrator')
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
// Oracle: AC3, D4.
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
  ['code network', { code: 'network' }, 'code'],
  ['code UNAUTHENTICATED', { code: 'UNAUTHENTICATED' }, 'code'],
  ...['network', 'unauthenticated', 'timeout', 'no_api', 'ECONNREFUSED', 'ENOTFOUND', 'ETIMEDOUT', 'EAI_AGAIN']
    .flatMap((c) => [[`env code ${c}`, { code: c }, 'code'], [`env code ${c.toLowerCase()}`, { code: c.toLowerCase() }, 'code']]),
]
for (const [name, over, field] of NOT_QUALIFYING) {
  test(`S7 not qualifying: ${name}`, async () => {
    const r = await run([d(over)])
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
  const r = await run([d({ code: 'network_error' })])
  assert.equal(r.doc.results[0].action, 'filed')
})

// -- S8 ---------------------------------------------------------------------------------------------
// Oracle: AC7, D10 (codes and what each matches).
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

for (const [name, actual, denyNames, code] of [
  ['absolute path', 'it broke at C:\\Users\\a\\x', [], 'absolute-path'],
  ['uuid', `it broke for ${UUID_A}`, [], 'uuid'],
  ['8 hex id', 'it broke for 0123abcd', [], 'hex-id'],
  ['project name in another case', 'the Acme project broke', ['acme'], 'deny-name'],
]) {
  test(`S8 a rejected actual (${name}) posts nothing`, async () => {
    const x = d({ actual })
    const r = await run([x], { denyNames })
    assert.equal(r.doc.results[0].action, 'skipped')
    assert.ok(r.doc.results[0].reason.startsWith('scrub-rejected:'), r.doc.results[0].reason)
    assert.ok(r.doc.results[0].reason.includes(code), r.doc.results[0].reason)
    assert.equal(callsOf(r.calls, 'issue', 'create').length, 0)
    assert.equal(callsOf(r.calls, 'issue', 'comment').length, 0)
    assert.ok(!r.writes.some((w) => w.seen && w.seen[fpOf(x)]), 'no state entry for a rejected defect')
  })
}

// -- S9 ---------------------------------------------------------------------------------------------
// Oracle: AC4, D6, D12.
test('S9 labelled create fails, unlabelled retry passes: two creates, filed', async () => {
  const r = await run([d()], { 'issue create': (a, n) => (a.includes('--label') ? fail() : ok(`https://github.com/${REPO}/issues/55\n`)) })
  const creates = callsOf(r.calls, 'issue', 'create')
  assert.equal(creates.length, 2)
  assert.ok(creates[0].includes('--label'))
  assert.ok(!creates[1].includes('--label'))
  assert.equal(r.doc.results[0].action, 'filed')
  assert.equal(r.doc.results[0].url, `https://github.com/${REPO}/issues/55`)
})

test('S9 both creates fail: gh-create-failed and no state entry', async () => {
  const x = d()
  const r = await run([x], { 'issue create': fail() })
  assert.equal(callsOf(r.calls, 'issue', 'create').length, 2)
  assert.equal(r.doc.results[0].action, 'skipped')
  assert.equal(r.doc.results[0].reason, 'gh-create-failed')
  assert.ok(!r.writes.some((w) => w.seen && w.seen[fpOf(x)]))
})

// -- S10 --------------------------------------------------------------------------------------------
// Oracle: AC9, D12 (library part; the CLI exit codes are not covered, see the header).
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

// The state schema's `at` encoding is not declared (task-scope D13 gives only {seen: {fp: {at, url}}}).
// atOf(ms) encodes a timestamp the way a state written by this module does (number or ISO string), taken
// from one probe write; only the encoding is learned that way, never any behaviour. Recorded as an
// ambiguity in test-manifest.
const probe = await run([d()])
const WRITTEN_AT = probe.writes.at(-1).seen[fpOf(d())].at
const atOf = (ms) => (typeof WRITTEN_AT === 'number' ? ms : new Date(ms).toISOString())

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
// Oracle: AC11-AC14, D16, D17, Files.
const read = (...p) => readFileSync(join(...p), 'utf8')
const section = (text, startRe, endRe) => {
  const m = text.match(startRe)
  assert.ok(m, `heading ${startRe} not found`)
  const rest = text.slice(m.index + m[0].length)
  const e = rest.search(endRe)
  return e < 0 ? rest : rest.slice(0, e)
}

test('S17 init SKILL.md has the Bug-report consent section with the D16 disclosures', () => {
  const init = read(PLUGIN, 'skills', 'init', 'SKILL.md')
  const s = section(init, /^## Bug-report consent \(both modes\)\s*$/m, /\n## /)
  assert.ok(s.includes('bug_reports'))
  assert.match(s, /public/i)
  assert.match(s, /gh account/i)
  assert.match(s, /scrubbed/i)
  assert.ok(s.includes('AskUserQuestion'))
  assert.ok(s.indexOf('No (default)') >= 0 && s.indexOf('No (default)') < s.indexOf('Yes, file bug reports'))
})
test('S17 config-format.md documents bug_reports and no longer says global-scoped', () => {
  const t = read(PLUGIN, 'skills', 'manage-schemas', 'references', 'config-format.md')
  assert.ok(t.includes('bug_reports'))
  assert.ok(!t.includes('global-scoped'))
})
test('S17 the shared github-feedback reference links the bug-report section from its Contents line', () => {
  const t = read(PLUGIN, 'skills', '_shared', 'github-feedback.md')
  assert.equal(t.split('\n')[0], '# GitHub Feedback Filing')
  const contents = t.split('\n').find((l) => l.startsWith('Contents:'))
  assert.ok(contents.includes('(#bug-reports-product-defects)'))
  assert.match(t, /^## Bug reports \(product defects\)\s*$/m)
})
test('S17 session-retrospective SKILL.md stays within 13700 bytes and links the bug-report section', () => {
  const t = read(PLUGIN, 'skills', 'session-retrospective', 'SKILL.md')
  assert.ok(Buffer.byteLength(t, 'utf8') <= 13700, `bytes ${Buffer.byteLength(t, 'utf8')}`)
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
test('S17 the self-improving-workflow guide lists bug_reports: false', () => {
  const t = read(REPO_ROOT, 'current', 'docs', 'integration-guides', 'self-improving-workflow.md')
  assert.ok(t.includes('bug_reports: false'))
})
