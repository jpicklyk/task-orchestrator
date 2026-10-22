// Coverage for scripts/render-ralph-prompts.mjs and the three rendered ralph sources (cae5d141: one outcome
// table from the error catalog). Test author actor id: test-author:cae5d141:r-202610170015-cae5d141.
//
// Oracle: task-scope AC1 (zero retired 3.x tool names, no actor sites, baseline rows gone), AC2 (markers,
// `--check` exit 1 on drift / 0 in sync, committed files in sync, line-ending preserving), AC7 (iteration
// prompt: no selector or claim step, no release instruction, items_read context, notes_write, the four
// variables; system prompt keeps "No early claim release"; the generated table is the only outcome text),
// AC10 (SKILL.md flags and Troubleshooting), carry-in p9-contract-baseline. Scenario ids: S8 (render/check),
// S9 (prompt contents). The module's declared error strings are used verbatim.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { renderOutcomeTable } from '../ralph-lib.mjs'
import { MARKER, BEGIN, END, TARGETS, rebuild, main } from '../render-ralph-prompts.mjs'
import { RETIRED_3X_TOOLS } from '../lib/contract-checks.mjs'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const PLUGIN = path.resolve(HERE, '..', '..')
const SKILL = path.join(PLUGIN, 'skills', 'ralph', 'SKILL.md')
const ITER = path.join(PLUGIN, 'skills', 'ralph', 'iteration-prompt.md')
const SYS = path.join(PLUGIN, 'skills', 'ralph', 'iteration-system-prompt.md')
const BASELINE = path.join(PLUGIN, 'scripts', 'lib', 'contract-names-baseline.json')

const read = (p) => fs.readFileSync(p, 'utf8')
const sha = (p) => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex')
const norm = (s) => s.replace(/\r\n/g, '\n')
const wholeWord = (text, name) => new RegExp('(^|[^A-Za-z0-9_])' + name + '([^A-Za-z0-9_]|$)').test(text)
const count = (text, needle) => text.split(needle).length - 1
function regionOf(text) {
  const t = norm(text)
  const b = t.indexOf(BEGIN)
  const e = t.indexOf(END)
  assert.ok(b !== -1 && e > b, 'BEGIN then END present')
  return t.slice(b + BEGIN.length, e)
}
function outside(text) {
  const t = norm(text)
  const b = t.indexOf(BEGIN)
  const e = t.indexOf(END)
  return t.slice(0, b) + t.slice(e + END.length)
}

const tmpDirs = []
function tmp() {
  const d = fs.mkdtempSync(path.join(os.tmpdir(), 'ralph-render-'))
  tmpDirs.push(d)
  return d
}
process.on('exit', () => {
  for (const d of tmpDirs) fs.rmSync(d, { recursive: true, force: true })
})
function quiet() {
  const logs = []
  const errs = []
  return { logs, errs, log: (m) => logs.push(String(m)), err: (m) => errs.push(String(m)) }
}

// ---- S8: markers, targets, rebuild --------------------------------------------------------------------

test('S8 AC2: the marker names are the declared literals', () => {
  assert.equal(MARKER, 'ralph-outcomes')
  assert.equal(BEGIN, '<!-- BEGIN GENERATED:ralph-outcomes -->')
  assert.equal(END, '<!-- END GENERATED:ralph-outcomes -->')
})

test('S8 AC2: TARGETS are exactly the three ralph sources, all existing', () => {
  const resolved = TARGETS.map((p) => path.resolve(p))
  assert.deepEqual([...resolved].sort(), [path.resolve(ITER), path.resolve(SKILL), path.resolve(SYS)].sort())
  for (const p of TARGETS) assert.ok(fs.existsSync(p), p)
})

test('S8 AC2: each committed source carries exactly one generated region and it equals renderOutcomeTable()', () => {
  const table = norm(renderOutcomeTable()).trim()
  for (const p of TARGETS) {
    const text = read(p)
    assert.equal(count(text, BEGIN), 1, `${path.basename(p)}: one BEGIN`)
    assert.equal(count(text, END), 1, `${path.basename(p)}: one END`)
    assert.ok(norm(regionOf(text)).includes(table), `${path.basename(p)}: the region holds the rendered table`)
  }
})

test('S8 AC2: main({check: true}) is 0 on the committed files and writes nothing', async () => {
  const before = TARGETS.map(sha)
  const q = quiet()
  const code = await main({ check: true, log: q.log, err: q.err })
  assert.equal(code, 0, q.errs.join('\n'))
  assert.deepEqual(TARGETS.map(sha), before, '--check never writes')
})

function scratchCopy() {
  const dir = tmp()
  const targets = TARGETS.map((p) => {
    const dest = path.join(dir, path.basename(p))
    fs.copyFileSync(p, dest)
    return dest
  })
  return { dir, targets }
}

test('S8 AC2: a scratch edit inside a generated region is drift - --check exits 1 and leaves the file alone; a write run repairs it and --check is 0 again', async () => {
  const { targets } = scratchCopy()
  const original = targets.map((p) => read(p))
  const victim = targets[0]
  const drifted = read(victim).replace(END, 'HAND EDITED OUTCOME TEXT\n' + END)
  fs.writeFileSync(victim, drifted)
  const q = quiet()
  assert.equal(await main({ check: true, targets, log: q.log, err: q.err }), 1)
  assert.equal(read(victim), drifted, '--check does not repair')
  const w = quiet()
  assert.equal(await main({ targets, log: w.log, err: w.err }), 0)
  assert.equal(read(victim), original[0], 'the write run restores the generated region exactly')
  assert.equal(await main({ check: true, targets, log: w.log, err: w.err }), 0)
})

test('S8 AC2: a source with no markers is not silently accepted by --check', async () => {
  const dir = tmp()
  const f = path.join(dir, 'plain.md')
  fs.writeFileSync(f, '# no markers here\n')
  const q = quiet()
  const code = await main({ check: true, targets: [f], log: q.log, err: q.err })
  assert.notEqual(code, 0)
  assert.equal(read(f), '# no markers here\n', 'a markerless file is never rewritten')
})

test('S8 AC2: rebuild replaces only the region between the markers, keeps the text outside byte for byte, and is idempotent', () => {
  const text = `head line\n${BEGIN}\nOLD CONTENT\n${END}\ntail line\n`
  const r = rebuild(text, '| a |\n| b |')
  assert.equal(r.error, undefined)
  assert.ok(r.text.startsWith(`head line\n${BEGIN}`))
  assert.ok(r.text.endsWith(`${END}\ntail line\n`))
  assert.ok(r.text.includes('| a |\n| b |'))
  assert.equal(r.text.includes('OLD CONTENT'), false)
  assert.equal(rebuild(r.text, '| a |\n| b |').text, r.text)
})

test('S8 AC2: rebuild with no markers returns the declared "markers not found" error and no text', () => {
  const r = rebuild('just prose\n', 'T')
  assert.equal(r.error, 'markers not found')
  assert.equal(r.text, undefined)
})

test('S8 AC2: rebuild with END before BEGIN, or both on one line, returns the declared malformed-markers error', () => {
  const msg = 'malformed markers (END must follow BEGIN on a later line)'
  assert.equal(rebuild(`${END}\nx\n${BEGIN}\n`, 'T').error, msg)
  assert.equal(rebuild(`${BEGIN}${END}\n`, 'T').error, msg)
})

// Line endings are a property of the written file (a Windows checkout turns the committed LF files into CRLF),
// so the contract is checked at the main() level, which owns reading and writing the file.
function toCrlf(s) {
  return s.replace(/\r\n/g, '\n').replace(/\n/g, '\r\n')
}

test('S8 AC2: a CRLF copy of the committed sources is in sync (--check exits 0), and --check does not touch it', async () => {
  const { targets } = scratchCopy()
  for (const t of targets) fs.writeFileSync(t, toCrlf(read(t)))
  const before = targets.map(sha)
  const q = quiet()
  assert.equal(await main({ check: true, targets, log: q.log, err: q.err }), 0, q.errs.join('\n'))
  assert.deepEqual(targets.map(sha), before)
})

test('S8 AC2: repairing drift in a CRLF file writes CRLF only, and in an LF file writes LF only', async () => {
  for (const eol of ['\r\n', '\n']) {
    const { targets } = scratchCopy()
    const victim = targets[1]
    const original = eol === '\r\n' ? toCrlf(read(victim)) : read(victim).replace(/\r\n/g, '\n')
    fs.writeFileSync(victim, original.replace(END, 'HAND EDITED' + eol + END))
    const q = quiet()
    assert.equal(await main({ targets: [victim], log: q.log, err: q.err }), 0, q.errs.join('\n'))
    const repaired = read(victim)
    assert.equal(repaired, original, 'the repaired file equals the in-sync file in the same line-ending style')
    if (eol === '\r\n') assert.equal(/(^|[^\r])\n/.test(repaired), false, 'no bare LF in the CRLF file')
    else assert.equal(repaired.includes('\r'), false, 'no CR in the LF file')
  }
})
// ---- AC1: baseline rows ----------------------------------------------------------------------------------

test('AC1: the contract-names baseline holds no row for the ralph files or for item cae5d141, and no prose exception was added', () => {
  const baseline = JSON.parse(read(BASELINE))
  const flat = JSON.stringify(baseline).toLowerCase()
  assert.equal(flat.includes('cae5d141'), false)
  assert.equal(flat.includes('ralph'), false)
  assert.deepEqual(baseline.proseExceptions, [])
})

// ---- S9: the rendered prompt sources ----------------------------------------------------------------------

test('S9 AC1: no ralph source names a retired 3.x tool', () => {
  for (const p of [SKILL, ITER, SYS]) {
    const text = read(p)
    for (const name of RETIRED_3X_TOOLS) assert.equal(wholeWord(text, name), false, `${path.basename(p)} names retired tool ${name}`)
  }
})

test('S9 AC1: the iteration prompts have no actor site; SKILL.md mentions an actor only to say it was removed', () => {
  const actor = new RegExp('\\b' + ['ac', 'tor'].join('') + '\\b', 'i')
  for (const p of [ITER, SYS]) assert.equal(actor.test(read(p)), false, `${path.basename(p)} still has an actor site`)
  for (const line of norm(read(SKILL)).split('\n')) {
    if (actor.test(line)) assert.ok(/removed|no longer|dropped|gone/i.test(line), `SKILL.md line mentions an actor without saying it was removed: ${line.slice(0, 120)}`)
  }
})

test('S9 AC7: iteration-prompt.md has no selector step and no claim or release instruction, and reads context with items_read', () => {
  const body = outside(read(ITER))
  assert.equal(/select\W{0,4}next\b/i.test(body), false, 'no items_read next selector in the iteration prompt')
  assert.equal(/\bop\W{0,3}\s*[:=]\s*\W{0,3}(claim|release|heartbeat)\b/i.test(body), false, 'no claim/release/heartbeat op instruction')
  assert.ok(/items_read[\s\S]{0,120}context/.test(body), 'the work starts from items_read context')
  assert.ok(body.includes('notes_write'), 'notes are written with notes_write')
  assert.ok(body.includes('schema-workflow'), 'advance transition goes through /schema-workflow')
})

test('S9 AC7: iteration-prompt.md carries the four prompt variables', () => {
  const body = read(ITER)
  for (const v of ['item_id', 'run_id', 'seat_token_file', 'ttl']) assert.ok(body.includes('${' + v + '}'), '${' + v + '}')
})

test('S9 AC7/D8: the outcome marker description gains the optional code and retryAfterMs', () => {
  const both = read(ITER) + '\n' + read(SYS)
  assert.ok(both.includes('retryAfterMs'))
  assert.ok(/\bcode\b/.test(both))
  assert.ok(both.includes('RALPH_OUTCOME'))
})

test('S9 AC7: iteration-system-prompt.md keeps the operating principles and "No early claim release"', () => {
  const body = read(SYS)
  assert.ok(/No early claim release/i.test(body))
  assert.ok(body.includes('How your turn ends'))
})

test('S9 AC2/AC7: the generated table is the only outcome text - the two prompts do not restate driver-side codes outside it', () => {
  for (const p of [ITER, SYS]) {
    const body = outside(read(p))
    for (const code of ['iteration_timeout', 'spawn_error', 'run_at_capacity', 'dependency_unmet']) {
      assert.equal(body.includes(code), false, `${path.basename(p)} restates ${code} outside the generated region`)
    }
  }
})

test('S9 AC10: SKILL.md documents the new flags, the principal / private-scratch-dir / killed-driver troubleshooting, and no longer calls ancestorId preview-only', () => {
  const text = read(SKILL)
  for (const flag of ['--iteration-timeout', '--run-budget-tokens', '--run-budget-usd', '--max-attempts', '--root', '--items', '--scratch-dir', '--claude-bin']) {
    assert.ok(text.includes(flag), `SKILL.md documents ${flag}`)
  }
  assert.ok(/principal/i.test(text), 'D2: the principal requirement')
  assert.ok(/scratch/i.test(text) && /(0o?600|chmod|permission)/i.test(text), 'D3: the private scratch dir and its permissions')
  assert.ok(text.includes('abandoned'), 'D13: recovery of a run left open by a killed driver (runs_write close abandoned)')
  assert.ok(text.includes('ancestorId='), 'the filter gains ancestorId=')
  assert.equal(/preview[- ]only/i.test(text), false, 'the ancestorId preview-only limitation is gone')
})
