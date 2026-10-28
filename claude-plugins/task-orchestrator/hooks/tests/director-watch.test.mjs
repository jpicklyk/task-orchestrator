// Tests for hooks/director-watch.mjs (item dbb45373, scenarios S10-S12 and the AC8 inertness evidence).
// BLIND author: written from the frozen task-scope (AC4, AC5, AC8) and test-plan notes, the supplied
// declarations and the existing hook tests. Oracles are the task-scope statements: push fires on status
// exactly "open" only, each id once per session; a retrospective directive needs a closed, unmarked run
// when the REST path is not available. In-process tests use synthetic session ids and a temp state file;
// the one subprocess test redirects TEMP so no real marker path is touched.
import './hermetic-claude-config.mjs';
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

import { run, buildPush, PUSH_PREFIX, WATCH_STATE_NAME } from '../director-watch.mjs'
import { directorMarkerPath } from '../execution-mode.mjs'
import { sessionMarkerPath } from '../lib/markers.mjs'

const HOOK = fileURLToPath(new URL('../director-watch.mjs', import.meta.url))
const HOOKS_CONFIG = fileURLToPath(new URL('../hooks-config.json', import.meta.url))
const RUNS_READ = 'mcp__mcp-task-orchestrator__runs_read'

const tracked = []
function tmp(prefix) {
  const d = mkdtempSync(join(tmpdir(), prefix))
  tracked.push(d)
  return d
}
after(() => {
  for (const d of tracked) rmSync(d, { recursive: true, force: true })
})

let seq = 0
const sid = (p) => `${p}-${process.pid}-${++seq}`

function okEnvelope(data) {
  return { ok: true, data, meta: { reqId: 'k7f3q9ab' } }
}
function wrap(env, via = 'structured') {
  if (via === 'text') return { content: [{ type: 'text', text: JSON.stringify(env) }] }
  return { content: [{ type: 'text', text: env.ok ? 'ok' : 'failed' }], structuredContent: env, isError: !env.ok }
}
function decisionsInput(session, items, extra = {}, env = okEnvelope({ items })) {
  return { tool_name: RUNS_READ, tool_input: { select: 'decisions' }, tool_response: wrap(env), session_id: session, ...extra }
}
function getInput(session, runId, data, extra = {}) {
  return {
    tool_name: RUNS_READ, tool_input: { select: 'get', runId },
    tool_response: wrap(okEnvelope({ runId, ...data })), session_id: session, ...extra,
  }
}
const d = (id, status = 'open') => ({ id, status })

function ctxOf(out) {
  assert.equal(out.hookSpecificOutput.hookEventName, 'PostToolUse')
  return out.hookSpecificOutput.additionalContext
}
function opts(over = {}) {
  return { director: true, statePath: join(tmp('to-watch-'), 'state.json'), rest: null, retroMode: 'dispatch', ...over }
}
const stateOf = (o) => JSON.parse(readFileSync(o.statePath, 'utf8'))

test('exports: the push prefix and state name are the declared literals', () => {
  assert.equal(PUSH_PREFIX, 'Decision waiting:')
  assert.equal(WATCH_STATE_NAME, 'director-watch')
  const built = JSON.stringify(buildPush(['dec-1']))
  assert.ok(built.includes('Decision waiting:'), built)
  assert.ok(built.includes('dec-1'), built)
})

// ---- S10 decision push ----

test('S10: one open decision yields one PostToolUse context that starts "Decision waiting:", names the id and PushNotification', async () => {
  const o = opts()
  const out = await run(decisionsInput(sid('s10'), [d('dec-aaa')]), {}, o)
  const text = ctxOf(out)
  assert.ok(text.startsWith('Decision waiting:'), text)
  assert.ok(text.includes('dec-aaa'), text)
  assert.ok(text.includes('PushNotification'), text)
  assert.deepEqual(stateOf(o).pushed, ['dec-aaa'])
})

test('S10: the same payload replayed in the same session yields {} (the id was already pushed)', async () => {
  const o = opts()
  const session = sid('s10r')
  const first = await run(decisionsInput(session, [d('dec-aaa')]), {}, o)
  assert.ok(ctxOf(first).includes('dec-aaa'), 'control: the first call pushes, so the replay result is dedupe, not a broken fixture')
  assert.deepEqual(await run(decisionsInput(session, [d('dec-aaa')]), {}, o), {})
  assert.deepEqual(stateOf(o).pushed, ['dec-aaa'])
})

test('S10: a second open decision added later pushes only the new id', async () => {
  const o = opts()
  const session = sid('s10n')
  await run(decisionsInput(session, [d('dec-aaa')]), {}, o)
  const out = await run(decisionsInput(session, [d('dec-aaa'), d('dec-bbb')]), {}, o)
  const text = ctxOf(out)
  assert.ok(text.includes('dec-bbb'), text)
  assert.ok(!text.includes('dec-aaa'), 'the already-pushed id is not named again: ' + text)
  assert.deepEqual([...stateOf(o).pushed].sort(), ['dec-aaa', 'dec-bbb'])
})

test('S10: the same id appearing twice in one payload is named once', async () => {
  const o = opts()
  const out = await run(decisionsInput(sid('s10d'), [d('dec-dup'), d('dec-dup')]), {}, o)
  const text = ctxOf(out)
  assert.equal(text.split('dec-dup').length - 1, 1, text)
  assert.deepEqual(stateOf(o).pushed, ['dec-dup'])
})

test('S10: two new open ids in one payload are both named in one context', async () => {
  const o = opts()
  const out = await run(decisionsInput(sid('s10m'), [d('dec-1'), d('dec-2'), d('dec-3', 'answered')]), {}, o)
  const text = ctxOf(out)
  assert.ok(text.includes('dec-1') && text.includes('dec-2'), text)
  assert.ok(!text.includes('dec-3'), 'a non-open decision is not pushed: ' + text)
})

test('S10: a state file already holding the id suppresses the push (state is read, not only written)', async () => {
  const o = opts()
  writeFileSync(o.statePath, JSON.stringify({ pushed: ['dec-old'], retro: [] }))
  assert.deepEqual(await run(decisionsInput(sid('s10s'), [d('dec-old')]), {}, o), {})
  const out = await run(decisionsInput(sid('s10s2'), [d('dec-old'), d('dec-new')]), {}, o)
  assert.ok(ctxOf(out).includes('dec-new'))
})

test('S10: the tool_response may arrive as text content; the push still fires', async () => {
  const o = opts()
  const input = { ...decisionsInput(sid('s10t'), []), tool_response: wrap(okEnvelope({ items: [d('dec-txt')] }), 'text') }
  assert.ok(ctxOf(await run(input, {}, o)).includes('dec-txt'))
})

// ---- S11 inert cases ----

test('S11: only status exactly "open" pushes; acknowledged, answered, withdrawn and a differently cased Open do not', async () => {
  for (const status of ['acknowledged', 'answered', 'withdrawn', 'Open', 'OPEN', 'opened', '']) {
    const o = opts()
    assert.deepEqual(await run(decisionsInput(sid('s11s'), [d('dec-x', status)]), {}, o), {}, `status ${JSON.stringify(status)}`)
  }
  assert.ok(ctxOf(await run(decisionsInput(sid('s11c'), [d('dec-x', 'open')]), {}, opts())).includes('dec-x'), 'control: open pushes')
})

test('S11: a non-director session gets {} for an open decision', async () => {
  assert.deepEqual(await run(decisionsInput(sid('s11d'), [d('dec-x')]), {}, opts({ director: false })), {})
})

test('S11: with no director marker for a synthetic session, the default probe yields {}', async () => {
  const o = opts()
  delete o.director
  assert.deepEqual(await run(decisionsInput(sid('s11nomarker'), [d('dec-x')]), {}, o), {})
})

test('S11: a subagent invocation (agent_id) gets {} even in a director session', async () => {
  assert.deepEqual(await run(decisionsInput(sid('s11a'), [d('dec-x')], { agent_id: 'agent-1' }), {}, opts()), {})
})

test('S11: a headless-iteration session gets {}', async () => {
  assert.deepEqual(
    await run(decisionsInput(sid('s11h'), [d('dec-x')]), { TASK_ORCHESTRATOR_MODE: 'headless-iteration' }, opts()),
    {},
  )
})

test('S11: a failed tool result (ok false) gets {} even if its data carries an open decision', async () => {
  const env = { ok: false, error: { code: 'internal', kind: 'transient', message: 'boom' }, data: { items: [d('dec-x')] }, meta: { reqId: 'k7f3q9ab' } }
  assert.deepEqual(await run(decisionsInput(sid('s11f'), [], {}, env), {}, opts()), {})
})

test('S11: other selects (frontier, list, missing) get {} even with an open decision in the data', async () => {
  for (const select of ['frontier', 'list', undefined]) {
    const input = decisionsInput(sid('s11sel'), [d('dec-x')])
    input.tool_input = select === undefined ? {} : { select }
    assert.deepEqual(await run(input, {}, opts()), {}, String(select))
  }
})

test('S11: null, empty and malformed input never throw and yield {}', async () => {
  for (const bad of [null, undefined, {}, { tool_name: RUNS_READ }, { tool_name: RUNS_READ, tool_input: { select: 'decisions' }, tool_response: 'garbage', session_id: sid('s11g') }]) {
    assert.deepEqual(await run(bad, {}, opts()), {}, JSON.stringify(bad))
  }
})

// ---- S12 run-ended retrospective ----

test('S12: a run that is open or paused yields {} (idle is not done)', async () => {
  for (const status of ['open', 'paused']) {
    assert.deepEqual(await run(getInput(sid('s12o'), 'run-1', { status, marks: {} }), {}, opts()), {}, status)
  }
})

test('S12: a closed run that already carries the retro mark yields {}', async () => {
  const out = await run(getInput(sid('s12m'), 'run-1', { status: 'closed', marks: { retro: { at: '2026-10-16T00:00:00Z' } } }), {}, opts())
  assert.deepEqual(out, {})
})

test('S12: a closed unmarked run in dispatch mode dispatches the retrospective for that run in the background', async () => {
  const o = opts()
  const text = ctxOf(await run(getInput(sid('s12d'), 'run-77', { status: 'closed', marks: {} }), {}, o))
  assert.ok(text.includes('/session-retrospective --run run-77'), text)
  assert.ok(text.includes('run_in_background'), text)
  assert.deepEqual(stateOf(o).retro, ['run-77'])
})

test('S12: the same closed run replayed yields {}; a different closed run still dispatches', async () => {
  const o = opts()
  const session = sid('s12r')
  const first = ctxOf(await run(getInput(session, 'run-1', { status: 'closed', marks: {} }), {}, o))
  assert.ok(first.includes('run-1'), 'control: the first call dispatches')
  assert.deepEqual(await run(getInput(session, 'run-1', { status: 'closed', marks: {} }), {}, o), {})
  const second = ctxOf(await run(getInput(session, 'run-2', { status: 'closed', marks: {} }), {}, o))
  assert.ok(second.includes('run-2'), second)
  assert.ok(!second.includes('run-1'), second)
})

test('S12: when the REST path is available (rest set) the hook yields {} for a closed unmarked run', async () => {
  const input = getInput(sid('s12rest'), 'run-1', { status: 'closed', marks: {} })
  assert.deepEqual(await run(input, {}, opts({ rest: '11111111-2222-4333-8444-555555555555' })), {})
  assert.ok(ctxOf(await run(input, {}, opts({ rest: null }))).includes('run-1'), 'control: with rest null the same input dispatches')
})

test('S12: retro mode off yields {}; nudge mode is treated as dispatch in a director session', async () => {
  const input = () => getInput(sid('s12mode'), 'run-1', { status: 'closed', marks: {} })
  assert.deepEqual(await run(input(), {}, opts({ retroMode: 'off' })), {})
  const nudge = ctxOf(await run(input(), {}, opts({ retroMode: 'nudge' })))
  assert.ok(nudge.includes('Retrospective dispatch'), nudge)
  assert.ok(!nudge.includes('Retrospective suggested'), nudge)
  assert.ok(nudge.includes('run-1'), nudge)
  const dispatch = ctxOf(await run(input(), {}, opts({ retroMode: 'dispatch' })))
  assert.ok(!dispatch.includes('Retrospective suggested'), dispatch)
})

test('S12: a get on a run for a non-director, subagent or headless session yields {}', async () => {
  const input = (extra) => getInput(sid('s12x'), 'run-1', { status: 'closed', marks: {} }, extra)
  assert.deepEqual(await run(input(), {}, opts({ director: false })), {})
  assert.deepEqual(await run(input({ agent_id: 'a1' }), {}, opts()), {})
  assert.deepEqual(await run(input(), { TASK_ORCHESTRATOR_MODE: 'headless-iteration' }, opts()), {})
})

test('S12: decision pushes and retrospectives keep separate state lists in the one state file', async () => {
  const o = opts()
  const session = sid('s12both')
  await run(decisionsInput(session, [d('dec-1')]), {}, o)
  await run(getInput(session, 'run-1', { status: 'closed', marks: {} }), {}, o)
  assert.deepEqual(stateOf(o), { pushed: ['dec-1'], retro: ['run-1'] })
})

// ---- end to end through the real process with redirected TEMP (default state path and marker probe) ----

test('E2E: as a subprocess, the director marker plus an open decision pushes once, and the state lives at the director-watch marker path', () => {
  const root = tmp('to-watch-e2e-')
  const session = sid('e2e')
  const saved = {}
  for (const k of ['TMPDIR', 'TEMP', 'TMP']) {
    saved[k] = process.env[k]
    process.env[k] = root
  }
  let marker
  let statePath
  try {
    marker = directorMarkerPath(session)
    statePath = sessionMarkerPath(session, WATCH_STATE_NAME)
  } finally {
    for (const k of Object.keys(saved)) {
      if (saved[k] === undefined) delete process.env[k]
      else process.env[k] = saved[k]
    }
  }
  mkdirSync(dirname(marker), { recursive: true })
  writeFileSync(marker, '')
  assert.ok(marker.startsWith(root) && statePath.startsWith(root), 'paths resolve inside the fixture root')
  const exec = (input) => {
    const r = spawnSync(process.execPath, [HOOK], {
      env: { ...process.env, TASK_ORCHESTRATOR_MODE: '', TMPDIR: root, TEMP: root, TMP: root },
      cwd: root, input: JSON.stringify(input), encoding: 'utf8',
    })
    assert.equal(r.status, 0, r.stderr)
    return JSON.parse(r.stdout)
  }
  const first = exec(decisionsInput(session, [d('dec-e2e')]))
  assert.ok(first.hookSpecificOutput.additionalContext.startsWith('Decision waiting:'), JSON.stringify(first))
  assert.ok(existsSync(statePath), 'state file at sessionMarkerPath(session, "director-watch")')
  assert.deepEqual(JSON.parse(readFileSync(statePath, 'utf8')).pushed, ['dec-e2e'])
  assert.deepEqual(exec(decisionsInput(session, [d('dec-e2e')])), {})
  // a session without the marker is inert through the real process too
  assert.deepEqual(exec(decisionsInput(sid('e2e-nomarker'), [d('dec-other')])), {})
})

// ---- registration ----

test('registration: hooks-config wires director-watch on PostToolUse for runs_read with timeout 5', () => {
  const cfg = JSON.parse(readFileSync(HOOKS_CONFIG, 'utf8'))
  const groups = cfg.hooks.PostToolUse.filter((g) => g.hooks.some((h) => h.command.includes('director-watch.mjs')))
  assert.equal(groups.length, 1)
  assert.equal(groups[0].matcher, '^mcp__.*task-orchestrator.*__runs_read$')
  const hook = groups[0].hooks.find((h) => h.command.includes('director-watch.mjs'))
  assert.ok(hook.command.includes('node'), hook.command)
  assert.ok(hook.command.includes('${CLAUDE_PLUGIN_ROOT}/hooks/director-watch.mjs'), hook.command)
  assert.equal(hook.timeout, 5)
  assert.equal((JSON.stringify(cfg).match(/director-watch\.mjs/g) || []).length, 1)
})