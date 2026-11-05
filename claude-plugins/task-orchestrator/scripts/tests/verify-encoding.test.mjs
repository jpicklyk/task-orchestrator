// verify encoding check (#640): scanTextEncoding, verify() blobs, EXEC_COMMANDS.verify CLI glue.
// Fixtures are built in-test (byte arrays, \u escapes), never as files, so the check never flags them.
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { itemFixture, planFixture } from './workflow-harness.mjs'
import * as rel from '../run-exec-lib.mjs'

const enc = (s) => new TextEncoder().encode(s)
const cat = (...parts) => Uint8Array.from(parts.flatMap((p) => Array.from(p)))
const NL = Uint8Array.from([0x0a])

test('cp1252 0x97 byte is non-utf8 on its own line', () => {
  const bytes = cat(enc('ok line'), NL, enc('a '), Uint8Array.from([0x97]), enc(' b'), NL, enc('end'))
  assert.deepEqual(rel.scanTextEncoding('a.kt', bytes), [{ line: 2, kind: 'non-utf8' }])
})

test('double-encoded em dash is flagged with its line', () => {
  const bytes = enc('fine\nbad \u00E2\u20AC\u201D dash\nfine')
  assert.deepEqual(rel.scanTextEncoding('a.md', bytes), [{ line: 2, kind: 'double-encoded' }])
})

test('triple encoding and U+00C2 + nbsp are flagged', () => {
  const triple = enc('x \u00C3\u00A2\u00E2\u201A\u00AC y')
  assert.deepEqual(rel.scanTextEncoding('a.md', triple), [{ line: 1, kind: 'double-encoded' }])
  const nbsp = enc('one\ntwo\nthree \u00C2\u00A0 four')
  assert.deepEqual(rel.scanTextEncoding('a.md', nbsp), [{ line: 3, kind: 'double-encoded' }])
})

test('non-utf8 and double-encoded on different lines of one file, one entry per line', () => {
  const bytes = cat(enc('\u00E2\u20AC\u201D'), NL, Uint8Array.from([0x97, 0x97]), NL, enc('ok'))
  assert.deepEqual(rel.scanTextEncoding('a.txt', bytes), [
    { line: 1, kind: 'double-encoded' },
    { line: 2, kind: 'non-utf8' },
  ])
})

test('clean UTF-8 with real non-ASCII text passes', () => {
  const bytes = enc('caf\u00E9 \u2014 na\u00EFve \u00A7 \u00C5ngstr\u00F6m\n')
  assert.deepEqual(rel.scanTextEncoding('a.md', bytes), [])
})

test('NUL-sniffed binary and a .png with bad bytes are skipped; null and empty too', () => {
  assert.deepEqual(rel.scanTextEncoding('blob.dat', Uint8Array.from([0x41, 0x00, 0x97, 0x97])), [])
  assert.deepEqual(rel.scanTextEncoding('Img.PNG', Uint8Array.from([0x97, 0x97])), [])
  assert.deepEqual(rel.scanTextEncoding('a.txt', null), [])
  assert.deepEqual(rel.scanTextEncoding('a.txt', new Uint8Array(0)), [])
})

function verifyWith(blobs) {
  const item = itemFixture({ short: 'aaaaaaaa', stages: [] })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [item] }), meta: {} }
  const result = { contract: 'implement-wave/result-v1', started: true, runId: doc.args.runId, items: [], refused: [], deferred: [] }
  const gitFacts = {
    exists: {},
    commits: [{ sha: 'a'.repeat(40), subject: 'feat: x [aaaaaaaa]', body: 'why', files: ['src/a.md', 'src/gone.md'] }],
  }
  if (blobs) gitFacts.blobs = { [item.worktree]: blobs }
  return rel.verify(doc, result, gitFacts).items[0]
}

test('verify: blobs give the finding and fail the item; no blobs means no check; null blob skipped', () => {
  const bad = verifyWith({ 'src/a.md': enc('x\n\u00E2\u20AC\u201D'), 'src/gone.md': null })
  assert.equal(bad.ok, false)
  assert.deepEqual(bad.findings, ['src/a.md:2 non-UTF-8 or double-encoded text'])
  assert.equal(verifyWith(null).ok, true)
  const skipped = verifyWith({ 'src/a.md': null, 'src/gone.md': null })
  assert.equal(skipped.ok, true)
})

test('EXEC_COMMANDS.verify reads blobs through io.gitBytes and exits 3 on a hit', () => {
  const item = itemFixture({ short: 'aaaaaaaa', stages: [] })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [item] }), meta: {} }
  const result = { contract: 'implement-wave/result-v1', started: true, runId: doc.args.runId, items: [], refused: [], deferred: [] }
  const sha = 'b'.repeat(40)
  const log = `${sha}\x1ffeat: x [aaaaaaaa]\x1fwhy\x1e\nsrc/a.md\nsrc/gone.md\n`
  const calls = []
  let out = null
  let code = null
  const io = {
    readInput: (p) => (p === 'plan' ? doc : result),
    loadCore: () => ({}),
    git: (cwd, args) => (args[0] === 'log' ? { status: 0, stdout: log } : { status: 1, stdout: '' }),
    gitBytes: (cwd, args) => {
      calls.push(args.join(' '))
      return args[2] === 'HEAD:src/a.md' ? { status: 0, stdout: enc('\u00C2\u00A0') } : { status: 128, stdout: new Uint8Array(0) }
    },
    writeOut: (t) => { out = JSON.parse(t) },
    exit: (c) => { code = c },
  }
  rel.EXEC_COMMANDS.verify(['--plan', 'plan', '--result', 'result'], io)
  assert.equal(code, 3)
  assert.deepEqual(calls, ['cat-file blob HEAD:src/a.md', 'cat-file blob HEAD:src/gone.md'])
  assert.deepEqual(out.items[0].findings, ['src/a.md:1 non-UTF-8 or double-encoded text'])
})