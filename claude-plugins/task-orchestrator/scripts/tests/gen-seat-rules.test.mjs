// Item 6f4e4c77, scenario S13 (and the single-source check behind AC1): scripts/gen-seat-rules.mjs writes the seat rule table and the
// shared resolver block into a generated region of both workflows, and `--check` says whether the region is current.
//
// Oracles: task-scope D3 ("Both workflows, in @core, between // @generated:seat-rules-begin and -end: const SEAT_RULES =
// Object.freeze(<json>) and the shared block without export, written by scripts/gen-seat-rules.mjs [--check] [--plugin-root D]
// (exit 0/1/2), as gen-code-mode-prelude.mjs"), test-plan S13 ("--check 0 on repo; edited region in a copy -> 1 naming file;
// marker gone -> 2"), and the declared constants of the script. The exit codes 0, 1 and 2 come from the plan, not from the script.
// BLIND test author: the script is only run, never opened. Fixture condition for each non-zero exit: the pristine copy of the
// same fixture exits 0 in the same test.
// Forbidden-construct declaration: none.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { loadCoreNamed } from './workflow-harness-ext.mjs'
import {
  TABLE_FILE, RESOLVER_FILE, WORKFLOW_FILES, SHARED_BEGIN, SHARED_END, REGION_BEGIN, REGION_END, sharedBlock,
} from '../gen-seat-rules.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = resolve(HERE, '..', '..')
const GEN = join(PLUGIN, 'scripts', 'gen-seat-rules.mjs')

const run = (args) => spawnSync(process.execPath, [GEN, ...args], { encoding: 'utf8' })
const out = (r) => `${r.stdout}${r.stderr}`

/** A scratch plugin root holding only what the generator reads and writes. */
function scratchRoot() {
  const root = mkdtempSync(join(tmpdir(), 'gen-seat-rules-'))
  for (const rel of [TABLE_FILE, RESOLVER_FILE, ...WORKFLOW_FILES]) {
    mkdirSync(dirname(join(root, rel)), { recursive: true })
    cpSync(join(PLUGIN, rel), join(root, rel))
  }
  return root
}
const cleanup = (root) => rmSync(root, { recursive: true, force: true })

test('declared constants name the table, the resolver, both workflows and the marker lines', () => {
  assert.equal(TABLE_FILE, 'hooks/lib/seat-rules.json')
  assert.equal(RESOLVER_FILE, 'hooks/lib/seat-rules.mjs')
  assert.deepEqual(WORKFLOW_FILES, ['workflows/implement-wave.js', 'workflows/review-wave.js'])
  assert.equal(SHARED_BEGIN, '// @seat-rules-shared-begin')
  assert.equal(SHARED_END, '// @seat-rules-shared-end')
  assert.equal(REGION_BEGIN, '// @generated:seat-rules-begin (scripts/gen-seat-rules.mjs; do not edit, run it to refresh)')
  assert.equal(REGION_END, '// @generated:seat-rules-end')
})

test('S13: --check exits 0 on the repository', () => {
  const res = run(['--check'])
  assert.equal(res.status, 0, out(res))
})

test('S13: --check exits 0 on a pristine scratch copy and 1, naming the file, when the generated region of one workflow is edited', () => {
  const root = scratchRoot()
  try {
    assert.equal(run(['--check', '--plugin-root', root]).status, 0, 'fixture: the pristine copy is current')
    for (const rel of WORKFLOW_FILES) {
      const file = join(root, rel)
      const original = readFileSync(file, 'utf8')
      const at = original.indexOf(REGION_BEGIN)
      const end = original.indexOf(REGION_END)
      assert.ok(at !== -1 && end > at, `${rel} has its region`)
      const lineEnd = original.indexOf('\n', at)
      // Edit inside the region: add a line right after the begin marker.
      writeFileSync(file, original.slice(0, lineEnd + 1) + '// edited by hand\n' + original.slice(lineEnd + 1))
      const res = run(['--check', '--plugin-root', root])
      assert.equal(res.status, 1, `${rel}: ${out(res)}`)
      assert.ok(out(res).includes(rel.split('/').pop()), `the output names ${rel}: ${out(res)}`)
      writeFileSync(file, original)
      assert.equal(run(['--check', '--plugin-root', root]).status, 0, `${rel} restored`)
    }
  } finally {
    cleanup(root)
  }
})

test('S13: --check exits 1 when the table changes and the regions are not regenerated; running the generator then makes --check exit 0', () => {
  const root = scratchRoot()
  try {
    const table = JSON.parse(readFileSync(join(root, TABLE_FILE), 'utf8'))
    table.roles.planner.defaults = [...table.roles.planner.defaults, 'extra-default']
    writeFileSync(join(root, TABLE_FILE), JSON.stringify(table, null, 2) + '\n')
    const stale = run(['--check', '--plugin-root', root])
    assert.equal(stale.status, 1, out(stale))
    const wrote = run(['--plugin-root', root])
    assert.equal(wrote.status, 0, out(wrote))
    assert.equal(run(['--check', '--plugin-root', root]).status, 0, 'regenerated regions are current')
    assert.ok(readFileSync(join(root, WORKFLOW_FILES[0]), 'utf8').includes('extra-default'), 'the new table row reached the workflow')
  } finally {
    cleanup(root)
  }
})

test('S13: --check exits 2 when a workflow has lost its region marker', () => {
  const root = scratchRoot()
  try {
    for (const marker of [REGION_BEGIN, REGION_END]) {
      const file = join(root, WORKFLOW_FILES[1])
      const original = readFileSync(file, 'utf8')
      assert.ok(original.includes(marker), 'fixture: the marker is there to remove')
      writeFileSync(file, original.split('\n').filter((l) => !l.startsWith(marker)).join('\n'))
      const res = run(['--check', '--plugin-root', root])
      assert.equal(res.status, 2, `${marker}: ${out(res)}`)
      writeFileSync(file, original)
      assert.equal(run(['--check', '--plugin-root', root]).status, 0, 'restored')
    }
  } finally {
    cleanup(root)
  }
})

test('generating twice is idempotent: the second run changes no byte of either workflow', () => {
  const root = scratchRoot()
  try {
    assert.equal(run(['--plugin-root', root]).status, 0)
    const first = WORKFLOW_FILES.map((rel) => readFileSync(join(root, rel), 'utf8'))
    assert.equal(run(['--plugin-root', root]).status, 0)
    assert.deepEqual(WORKFLOW_FILES.map((rel) => readFileSync(join(root, rel), 'utf8')), first)
  } finally {
    cleanup(root)
  }
})

test('sharedBlock: the shared region of the resolver, without any export, defines the three resolver functions', () => {
  const block = sharedBlock(readFileSync(join(PLUGIN, RESOLVER_FILE), 'utf8'))
  assert.equal(/\bexport\b/.test(block), false, 'D3: the shared block is written without export')
  for (const name of ['seatRoleOf', 'seatRulesFor', 'promptKeysFor']) assert.ok(block.includes(`function ${name}(`), name)
  assert.equal(block.includes(SHARED_BEGIN), false, 'the markers are not part of the block')
})

// ---- the region the workflows carry is the table and the resolver (AC1: one source) -----------------------------

const TABLE = JSON.parse(readFileSync(join(PLUGIN, TABLE_FILE), 'utf8'))
const NAMES = ['SEAT_RULES', 'seatRoleOf', 'seatRulesFor', 'promptKeysFor']

for (const rel of WORKFLOW_FILES) {
  test(`${rel}: SEAT_RULES in the generated region equals the table file, and the region's functions give the frozen lists`, () => {
    const core = loadCoreNamed(join(PLUGIN, rel), NAMES)
    assert.deepEqual(core.SEAT_RULES, TABLE)
    assert.equal(Object.isFrozen(core.SEAT_RULES), true, 'D3: Object.freeze')
    const planner = { seat: 'planner', phase: 'queue', writes: false, notes: ['task-scope'], rules: ['planning-sweeps', 'review-cited-claims'] }
    assert.deepEqual(core.promptKeysFor(core.SEAT_RULES, planner), ['protocol.in-phase-seat', 'planning-sweeps', 'review-cited-claims'])
    const impl = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: ['n'], rules: ['closure-notes'] }
    assert.deepEqual(core.promptKeysFor(core.SEAT_RULES, impl), ['protocol.entry-seat', 'commit-discipline', 'closure-notes'])
    const reviewer = { seat: 'reviewer', phase: 'review', writes: false, notes: ['review-checklist'], rules: [] }
    assert.deepEqual(core.promptKeysFor(core.SEAT_RULES, reviewer), ['protocol.read-only-agent'])
    assert.equal(core.seatRoleOf({ seat: 'orchestrator', phase: 'work', writes: true, notes: ['n'] }), null)
    const dropped = core.seatRulesFor(core.SEAT_RULES, { role: 'planner', seat: 'planner', declared: ['protocol.entry-seat', 'x-rule'], noteRules: [], served: null })
    assert.deepEqual(dropped.rules, ['x-rule'])
    assert.deepEqual(dropped.degradations, ['seat rule protocol.entry-seat dropped: protocol rule of another role (planner)'])
  })
}
