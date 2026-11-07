// dispatch-meta.test.mjs — collectDispatchMeta (pure, fake io) and the run-planner `dispatch-meta`
// CLI over a temp folder shaped like Claude Code's per-session `subagents/` directory: one
// 'agent-<id>.meta.json' ({description, model, ...}) beside each 'agent-<id>.jsonl' transcript whose
// assistant lines carry `message.model`, `effort` and `perTurnEffort`.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync, utimesSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { collectDispatchMeta, stripDisplayTag } from '../run-exec-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const RUN_PLANNER = join(HERE, '..', 'run-planner.mjs')

function assistantLine(model, effort) {
  return JSON.stringify({ type: 'assistant', effort, perTurnEffort: effort, message: { model, role: 'assistant', content: [] } })
}

function transcript(model, efforts) {
  return [
    JSON.stringify({ type: 'user', message: { role: 'user', content: 'go' } }),
    ...efforts.map((e) => assistantLine(model, e)),
    JSON.stringify({ type: 'attachment', attachment: {} }),
    '',
  ].join('\n')
}

function fakeIo({ files, transcripts, walk }) {
  return {
    walkMeta: (root) => walk[root] || [],
    readInput: (p) => (Object.hasOwn(files, p) ? files[p] : null),
    readText: (p) => (Object.hasOwn(transcripts, p) ? transcripts[p] : null),
  }
}

test('stripDisplayTag removes a trailing " [model/effort]" tag and leaves a bare label alone', () => {
  assert.equal(stripDisplayTag('implementer:abcd1234 [sonnet/high]'), 'implementer:abcd1234')
  assert.equal(stripDisplayTag('reviewer.security-review:abcd1234 [inherit]'), 'reviewer.security-review:abcd1234')
  assert.equal(stripDisplayTag('implementer:abcd1234'), 'implementer:abcd1234')
})

test('collectDispatchMeta: matches meta descriptions (tag stripped) to the wanted labels, takes the modal effort and last model id from the transcript, and lets the newest file win per label', () => {
  const io = fakeIo({
    walk: {
      '/s': [
        { path: '/s/agent-old.meta.json', mtimeMs: 100 },
        { path: '/s/agent-new.meta.json', mtimeMs: 200 },
        { path: '/s/agent-other.meta.json', mtimeMs: 300 },
        { path: '/s/wf/agent-planner.meta.json', mtimeMs: 150 },
      ],
    },
    files: {
      '/s/agent-old.meta.json': { description: 'implementer:aaaaaaaa [sonnet/high]', model: 'sonnet' },
      '/s/agent-new.meta.json': { description: 'implementer:aaaaaaaa [sonnet/high]', model: 'sonnet' },
      '/s/agent-other.meta.json': { description: 'implementer:bbbbbbbb', model: 'opus' },
      '/s/wf/agent-planner.meta.json': { description: 'planner:aaaaaaaa', model: 'opus' },
    },
    transcripts: {
      '/s/agent-old.jsonl': transcript('claude-sonnet-5-5', ['high', 'high']),
      '/s/agent-new.jsonl': transcript('claude-sonnet-5-5', ['medium', 'high', 'medium']),
      '/s/agent-other.jsonl': transcript('claude-opus-5-5', ['max']),
      '/s/wf/agent-planner.jsonl': transcript('claude-opus-5-5', ['high']),
    },
  })
  const meta = collectDispatchMeta({ io, roots: ['/s'], labels: ['implementer:aaaaaaaa', 'planner:aaaaaaaa'] })
  assert.deepEqual(Object.keys(meta).sort(), ['implementer:aaaaaaaa', 'planner:aaaaaaaa'])
  const impl = meta['implementer:aaaaaaaa']
  assert.equal(impl.transcript, '/s/agent-new.jsonl', 'the newest meta file for a label wins')
  assert.equal(impl.model, 'sonnet')
  assert.equal(impl.modelId, 'claude-sonnet-5-5')
  assert.equal(impl.effort, 'medium', 'the most frequent effort over the assistant lines')
  assert.equal(impl.effortSource, 'transcript')
  assert.equal(meta['planner:aaaaaaaa'].effort, 'high')
  assert.equal(Object.hasOwn(meta, 'implementer:bbbbbbbb'), false, 'an unwanted label is ignored')
})

test('collectDispatchMeta: a tie between efforts goes to the later value, a missing transcript leaves effort absent, the alias derives from the model id when meta.model is not an alias, and --since drops older files', () => {
  const io = fakeIo({
    walk: { '/s': [{ path: '/s/agent-a.meta.json', mtimeMs: 50 }, { path: '/s/agent-b.meta.json', mtimeMs: 500 }] },
    files: {
      '/s/agent-a.meta.json': { description: 'implementer:aaaaaaaa', model: 'claude-opus-5-5' },
      '/s/agent-b.meta.json': { description: 'reviewer:aaaaaaaa' },
    },
    transcripts: { '/s/agent-a.jsonl': transcript('claude-opus-5-5', ['low', 'high']) },
  })
  const all = collectDispatchMeta({ io, roots: ['/s'], labels: ['implementer:aaaaaaaa', 'reviewer:aaaaaaaa'] })
  assert.equal(all['implementer:aaaaaaaa'].effort, 'high')
  assert.equal(all['implementer:aaaaaaaa'].model, 'opus', 'alias derived from the model id')
  assert.equal(Object.hasOwn(all['reviewer:aaaaaaaa'], 'effort'), false)
  assert.equal(all['reviewer:aaaaaaaa'].model, 'unknown')
  const recent = collectDispatchMeta({ io, roots: ['/s'], labels: ['implementer:aaaaaaaa', 'reviewer:aaaaaaaa'], since: 100 })
  assert.deepEqual(Object.keys(recent), ['reviewer:aaaaaaaa'])
})

test('CLI: dispatch-meta --plan --session-dir walks the session folder and prints the meta document provenance --meta reads', () => {
  const dir = mkdtempSync(join(tmpdir(), 'dispatch-meta-'))
  const session = join(dir, 'session')
  const wf = join(session, 'subagents', 'workflows', 'wf_1')
  mkdirSync(wf, { recursive: true })
  const implMeta = join(session, 'subagents', 'agent-1.meta.json')
  writeFileSync(implMeta, JSON.stringify({ agentType: 'workflow-subagent', description: 'implementer:aaaaaaaa [sonnet/high]', model: 'sonnet' }))
  writeFileSync(join(session, 'subagents', 'agent-1.jsonl'), transcript('claude-sonnet-5-5', ['high', 'high', 'medium']))
  writeFileSync(join(wf, 'agent-2.meta.json'), JSON.stringify({ description: 'planner:aaaaaaaa [opus/high]', model: 'opus' }))
  writeFileSync(join(wf, 'agent-2.jsonl'), transcript('claude-opus-5-5', ['xhigh']))
  const old = new Date(Date.now() - 60 * 60 * 1000)
  utimesSync(implMeta, old, old)
  const plan = {
    contract: 'run-wave/plan-doc-v1',
    args: { runId: 'r-1', items: [{ id: 'aaaaaaaa-0000-4000-8000-000000000000', short: 'aaaaaaaa', stages: [{ seat: 'planner' }, { seat: 'implementer' }, { seat: 'reviewer' }] }] },
  }
  const planPath = join(dir, 'plan.json')
  writeFileSync(planPath, JSON.stringify(plan))

  const res = spawnSync(process.execPath, [RUN_PLANNER, 'dispatch-meta', '--plan', planPath, '--session-dir', session], { encoding: 'utf8' })
  assert.equal(res.status, 0, res.stderr)
  const out = JSON.parse(res.stdout)
  assert.equal(out.contract, 'run-wave/dispatch-meta-v1')
  assert.deepEqual(out.roots, [session])
  assert.equal(out.wanted, 3)
  assert.equal(out.matched, 2, 'the reviewer seat has no transcript and is simply absent')
  assert.equal(out.meta['implementer:aaaaaaaa'].effort, 'high')
  assert.equal(out.meta['implementer:aaaaaaaa'].model, 'sonnet')
  assert.equal(out.meta['planner:aaaaaaaa'].effort, 'xhigh')
  assert.equal(out.meta['planner:aaaaaaaa'].modelId, 'claude-opus-5-5')

  const since = String(Date.now() - 30 * 60 * 1000)
  const recent = spawnSync(process.execPath, [RUN_PLANNER, 'dispatch-meta', '--plan', planPath, '--session-dir', session, '--since', since], { encoding: 'utf8' })
  assert.equal(recent.status, 0, recent.stderr)
  assert.deepEqual(Object.keys(JSON.parse(recent.stdout).meta), ['planner:aaaaaaaa'], '--since drops the hour-old implementer meta file')

  const bad = spawnSync(process.execPath, [RUN_PLANNER, 'dispatch-meta', '--plan', planPath, '--session-dir', session, '--since', 'yesterday'], { encoding: 'utf8' })
  assert.notEqual(bad.status, 0, 'a non-integer --since is refused')
})
