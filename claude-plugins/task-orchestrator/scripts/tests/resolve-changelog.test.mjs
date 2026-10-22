// Ported to 4.0 from main commit 850eaf6e (item cac1bffa, task-scope D11): the main test file, adapted only where AC names it (actor fields, planDocSlug -> serverRunId, ledger assignment, 4.0 file paths).
// Coverage for resolve-changelog (lib + CLI), item 461e5991. Scenario ids S1-S14 and the probe
// list match the item's `test-plan` note (6dc24f2a). Oracles: the item's `task-scope` note
// (Contract, Parsing, Merge R1-R4, Render clauses). Every expected value below is hand-derived
// from those clauses (see the comment above each case); no expected value was read from the
// implementation. This suite imports only the declared export `resolveChangelog` and spawns the
// declared CLI.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, mkdtempSync, rmSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { tmpdir } from 'node:os'
import { spawnSync } from 'node:child_process'

import { resolveChangelog } from '../resolve-changelog-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const CLI = join(HERE, '..', 'resolve-changelog.mjs')
const FIX = join(HERE, 'fixtures', 'resolve-changelog')
const fixture = (name) => readFileSync(join(FIX, name), 'utf8')

// ── builders (canonical Render form, task-scope "Render") ───────────────────────────────────

const TAIL = '## [1.0.0] - 2026-01-01\n\n### Added\n\n- Initial release.\n'
const bl = (key, text) => `- **${key}** ${text}`
const sec = (name, ...bullets) => `### ${name}\n\n${bullets.join('\n')}\n\n`
const doc = (...secs) => `# Changelog\n\n## [Unreleased]\n\n${secs.join('')}${TAIL}`
const toCrlf = (s) => s.replace(/\n/g, '\r\n')

const SENTINEL = 'SENTINEL-DO-NOT-OVERWRITE'

function scratch(t) {
  const dir = mkdtempSync(join(tmpdir(), 'resolve-cl-'))
  t.after(() => rmSync(dir, { recursive: true, force: true }))
  return dir
}

function setup(t, { ancestor, base, branch }) {
  const dir = scratch(t)
  const p = { dir, ancestor: join(dir, 'ancestor.md'), base: join(dir, 'base.md'), branch: join(dir, 'branch.md'), out: join(dir, 'out.md') }
  writeFileSync(p.ancestor, ancestor)
  writeFileSync(p.base, base)
  writeFileSync(p.branch, branch)
  writeFileSync(p.out, SENTINEL)
  return p
}

function cli(args) {
  return spawnSync(process.execPath, [CLI, ...args], { encoding: 'utf8' })
}

function cliStd(p, extra = ['--out', p.out]) {
  return cli(['--ancestor', p.ancestor, '--base', p.base, '--branch', p.branch, ...extra])
}

const ok = (r) => {
  assert.equal(r.ok, true, `expected ok result, got ${JSON.stringify(r)}`)
  return r
}

// ── S1: three-way acceptance fixture ────────────────────────────────────────────────────────
// Per bullet (A/B/R = ancestor/base/branch):
//   Shared old (A=B=R)                      -> R1 -> B
//   Stale entry (A=R, B absent)             -> R3 -> dropped
//   Edited on branch (A=B, R reworded)      -> R2 -> R
//   Base only add (A=R absent, B present)   -> R3 -> B
//   Branch only add (A=B absent, R present) -> R2 -> R
//   Both added (B != R, A absent)           -> R4 -> B, R discarded
//   Fix kept (A=B=R) -> R1; Branch fix (R2).
// Render: base section order; branch-new bullets first, then base order; base text outside
// the region verbatim (incl. the 1.1.0 section that only base has).

test('S1: three-way fixture resolves to the hand-derived expected file (lib)', () => {
  const r = ok(resolveChangelog({
    ancestor: fixture('ancestor.md'), base: fixture('base.md'), branch: fixture('branch.md'),
  }))
  assert.equal(r.text, fixture('expected.md'))
  assert.deepEqual(r.discarded, [
    { section: 'Added', key: 'Both added.', branchText: '- **Both added.** branch wording.' },
  ])
})

test('S1: CLI --out is byte-equal to expected.md, exit 0, discarded entry on stderr', (t) => {
  const outDir = scratch(t)
  const out = join(outDir, 'resolved.md')
  const r = cli([
    '--ancestor', join(FIX, 'ancestor.md'), '--base', join(FIX, 'base.md'),
    '--branch', join(FIX, 'branch.md'), '--out', out,
  ])
  assert.equal(r.status, 0, r.stderr)
  assert.equal(readFileSync(out, 'utf8'), fixture('expected.md'))
  assert.ok(r.stderr.includes('DISCARDED [Added] Both added.'), r.stderr)
  assert.ok(r.stderr.includes('branch wording.'), r.stderr)
  assert.ok(!r.stderr.includes('Shared old'), 'only the discarded bullet is reported')
})

test('S1: CLI without --out prints the resolved text on stdout', () => {
  const r = cli([
    '--ancestor', join(FIX, 'ancestor.md'), '--base', join(FIX, 'base.md'),
    '--branch', join(FIX, 'branch.md'),
  ])
  assert.equal(r.status, 0, r.stderr)
  assert.equal(r.stdout, fixture('expected.md'))
})

// ── S2: identical / one-sided bullets (R1, R3) ──────────────────────────────────────────────

test('S2: the same edited bullet on both sides yields one copy and no discard (R1)', () => {
  const ancestor = doc(sec('Added', bl('X.', 'old')))
  const both = doc(sec('Added', bl('X.', 'new')))
  const r = ok(resolveChangelog({ ancestor, base: both, branch: both }))
  assert.equal(r.text, both)
  assert.deepEqual(r.discarded, [])
})

test('S2: the same bullet added on both sides yields one copy and no discard (R1)', () => {
  const both = doc(sec('Added', bl('Y.', 'same')))
  const r = ok(resolveChangelog({ ancestor: doc(), base: both, branch: both }))
  assert.equal(r.text, both)
  assert.deepEqual(r.discarded, [])
})

test('S2: a base-only edit is taken from base (R3), nothing discarded', () => {
  const r = ok(resolveChangelog({
    ancestor: doc(sec('Added', bl('X.', 'old'))),
    base: doc(sec('Added', bl('X.', 'new'))),
    branch: doc(sec('Added', bl('X.', 'old'))),
  }))
  assert.equal(r.text, doc(sec('Added', bl('X.', 'new'))))
  assert.deepEqual(r.discarded, [])
})

// ── S3: base without an [Unreleased] heading ────────────────────────────────────────────────
// Ancestor and branch carry "Old stale."; base lacks it (R3 -> dropped). "New one." is branch
// only (R2). Region is inserted before base's first `## ` line; base text is otherwise as is.

test('S3: base without [Unreleased] -> stale dropped, branch add kept, region inserted before first ## line', () => {
  const base = `# Changelog\n\n${TAIL}`
  const ancestor = doc(sec('Added', bl('Old stale.', 'gone')))
  const branch = doc(sec('Added', bl('Old stale.', 'gone'), bl('New one.', 'n')))
  const r = ok(resolveChangelog({ ancestor, base, branch }))
  assert.equal(
    r.text,
    '# Changelog\n\n## [Unreleased]\n\n### Added\n\n- **New one.** n\n\n## [1.0.0] - 2026-01-01\n\n### Added\n\n- Initial release.\n',
  )
  assert.deepEqual(r.discarded, [])
})

test('S3: base without [Unreleased] and nothing surviving -> base text unchanged', () => {
  const base = `# Changelog\n\n${TAIL}`
  const ancestor = doc(sec('Added', bl('Old stale.', 'gone')))
  const r = ok(resolveChangelog({ ancestor, base, branch: ancestor }))
  assert.equal(r.text, base)
})

// ── S4: section ordering and empty-section omission ─────────────────────────────────────────
// Added (A absent, B=R) R1; Security, Docs branch-only (R2); Changed: A=R, B absent -> R3 ->
// dropped, leaving the branch-only section empty -> omitted. Order: base's sections, then
// branch-only sections in branch order.

test('S4: branch-only sections follow base sections in branch order; empty sections are omitted', () => {
  const base = doc(sec('Added', bl('A1.', 'a')))
  const branch = doc(
    sec('Security', bl('S1.', 's')), sec('Added', bl('A1.', 'a')),
    sec('Docs', bl('D1.', 'd')), sec('Changed', bl('C1.', 'c')),
  )
  const ancestor = doc(sec('Changed', bl('C1.', 'c')))
  const r = ok(resolveChangelog({ ancestor, base, branch }))
  assert.equal(r.text, doc(
    sec('Added', bl('A1.', 'a')), sec('Security', bl('S1.', 's')), sec('Docs', bl('D1.', 'd')),
  ))
  assert.ok(!r.text.includes('### Changed'))
})

// ── S5-S7: duplicates ───────────────────────────────────────────────────────────────────────

const dupBase = doc(sec('Added', bl('Dup.', 'one')))
const dupBranch = doc(sec('Fixed', bl('Dup.', 'one')))

test('S5: a key surviving under two sections in the RESULT is a duplicate (lib)', () => {
  // Inputs are individually clean: base has Dup. under Added only, branch under Fixed only.
  // Added/Dup.: A absent, R absent, B present -> R3 -> B. Fixed/Dup.: A,B absent -> R2 -> R.
  const r = resolveChangelog({ ancestor: doc(), base: dupBase, branch: dupBranch })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'duplicate')
  assert.deepEqual(r.duplicates, [{ kind: 'bullet', name: 'Dup.' }])
})

test('S5: CLI exits 1, leaves --out untouched and prints DUPLICATE bullet <key>', (t) => {
  const p = setup(t, { ancestor: doc(), base: dupBase, branch: dupBranch })
  const r = cliStd(p)
  assert.equal(r.status, 1, r.stderr)
  assert.equal(readFileSync(p.out, 'utf8'), SENTINEL)
  assert.match(r.stderr, /^DUPLICATE bullet Dup\.$/m)
})

test('S5: the same key under two sections inside ONE input is a duplicate', () => {
  const twice = doc(sec('Added', bl('Dup.', 'a')), sec('Fixed', bl('Dup.', 'b')))
  for (const which of ['ancestor', 'base', 'branch']) {
    const inputs = { ancestor: doc(), base: doc(), branch: doc() }
    inputs[which] = twice
    const r = resolveChangelog(inputs)
    assert.equal(r.ok, false, which)
    assert.equal(r.code, 'duplicate', which)
    assert.ok(r.duplicates.some((d) => d.kind === 'bullet' && d.name === 'Dup.'), which)
  }
})

test('S6: a section heading twice in one region is a duplicate of kind section', (t) => {
  const base = doc(sec('Fixed', bl('F1.', 'a')), sec('Fixed', bl('F2.', 'b')))
  const r = resolveChangelog({ ancestor: doc(), base, branch: doc() })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'duplicate')
  assert.ok(r.duplicates.some((d) => d.kind === 'section' && d.name === 'Fixed'), JSON.stringify(r))
  const p = setup(t, { ancestor: doc(), base, branch: doc() })
  const c = cliStd(p)
  assert.equal(c.status, 1, c.stderr)
  assert.match(c.stderr, /^DUPLICATE section Fixed$/m)
  assert.equal(readFileSync(p.out, 'utf8'), SENTINEL)
})

test('S7: two [Unreleased] headings in one input is a duplicate of kind unreleased', (t) => {
  const twice = `# Changelog\n\n## [Unreleased]\n\n## [Unreleased]\n\n${TAIL}`
  for (const which of ['ancestor', 'base', 'branch']) {
    const inputs = { ancestor: doc(), base: doc(), branch: doc() }
    inputs[which] = twice
    const r = resolveChangelog(inputs)
    assert.equal(r.ok, false, which)
    assert.equal(r.code, 'duplicate', which)
    assert.ok(r.duplicates.some((d) => d.kind === 'unreleased'), which)
  }
  const p = setup(t, { ancestor: doc(), base: doc(), branch: twice })
  const c = cliStd(p)
  assert.equal(c.status, 1, c.stderr)
  assert.match(c.stderr, /^DUPLICATE unreleased/m)
  assert.equal(readFileSync(p.out, 'utf8'), SENTINEL)
})

// ── S8: exit 2 / invalid input ──────────────────────────────────────────────────────────────

const MARKERS = ['<<<<<<< HEAD', '>>>>>>> feature', '=======']

test('S8: a conflict-marker line anywhere in any input is invalid (lib)', () => {
  for (const which of ['ancestor', 'base', 'branch']) {
    for (const marker of MARKERS) {
      const outside = `# Changelog\n\n${marker}\n\n## [Unreleased]\n\n${sec('Added', bl('X.', 'x'))}${TAIL}`
      const inside = `# Changelog\n\n## [Unreleased]\n\n### Added\n\n${bl('X.', 'x')}\n${marker}\n\n${TAIL}`
      for (const [where, text] of [['outside', outside], ['inside', inside]]) {
        const inputs = { ancestor: doc(), base: doc(), branch: doc() }
        inputs[which] = text
        const r = resolveChangelog(inputs)
        assert.equal(r.ok, false, `${which}/${marker}/${where}`)
        assert.equal(r.code, 'invalid', `${which}/${marker}/${where}`)
        assert.equal(typeof r.message, 'string')
        assert.ok(r.message.length > 0)
      }
    }
  }
})

test('S8: CLI exits 2 with --out unwritten for a conflict marker in an input', (t) => {
  const p = setup(t, { ancestor: doc(), base: doc(), branch: `<<<<<<< HEAD\n${doc()}` })
  const r = cliStd(p)
  assert.equal(r.status, 2, r.stderr)
  assert.match(r.stderr, /^INVALID: /m)
  assert.equal(readFileSync(p.out, 'utf8'), SENTINEL)
})

test('S8: CLI exits 2 with --out unwritten for a missing --base', (t) => {
  const p = setup(t, { ancestor: doc(), base: doc(), branch: doc() })
  const r = cli(['--ancestor', p.ancestor, '--branch', p.branch, '--out', p.out])
  assert.equal(r.status, 2, r.stderr)
  assert.ok(r.stderr.length > 0)
  assert.equal(readFileSync(p.out, 'utf8'), SENTINEL)
})

test('S8: CLI exits 2 with --out unwritten for an unknown flag', (t) => {
  const p = setup(t, { ancestor: doc(), base: doc(), branch: doc() })
  const r = cliStd(p, ['--bogus', 'x', '--out', p.out])
  assert.equal(r.status, 2, r.stderr)
  assert.equal(readFileSync(p.out, 'utf8'), SENTINEL)
})

test('S8: CLI exits 2 with --out unwritten for a nonexistent input path', (t) => {
  const p = setup(t, { ancestor: doc(), base: doc(), branch: doc() })
  const r = cli(['--ancestor', p.ancestor, '--base', join(p.dir, 'does-not-exist.md'), '--branch', p.branch, '--out', p.out])
  assert.equal(r.status, 2, r.stderr)
  assert.equal(readFileSync(p.out, 'utf8'), SENTINEL)
})

// ── S9: outside-region edits on the branch ──────────────────────────────────────────────────

test('S9: branch text outside the region differing from both ancestor and base is invalid', (t) => {
  const edited = doc(sec('Added', bl('X.', 'x'))).replace('Initial release.', 'Reworded release.')
  const plain = doc(sec('Added', bl('X.', 'x')))
  const r = resolveChangelog({ ancestor: plain, base: plain, branch: edited })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'invalid')
  const p = setup(t, { ancestor: plain, base: plain, branch: edited })
  const c = cliStd(p)
  assert.equal(c.status, 2, c.stderr)
  assert.equal(readFileSync(p.out, 'utf8'), SENTINEL)
})

test('S9: branch outside text equal to base (but not ancestor) is accepted; equal to ancestor (but not base) is accepted', () => {
  const plain = doc(sec('Added', bl('X.', 'x')))
  const released = plain.replace('Initial release.', 'Reworded release.')
  const a = ok(resolveChangelog({ ancestor: plain, base: released, branch: released }))
  assert.equal(a.text, released)
  const b = ok(resolveChangelog({ ancestor: plain, base: released, branch: plain }))
  assert.equal(b.text, released)
})

// ── S10: unparseable region lines ───────────────────────────────────────────────────────────

test('S10: a column-0 prose line inside a section is invalid', () => {
  const prose = `# Changelog\n\n## [Unreleased]\n\n### Added\n\n${bl('X.', 'x')}\nSome stray prose line\n\n${TAIL}`
  for (const which of ['ancestor', 'base', 'branch']) {
    const inputs = { ancestor: doc(), base: doc(), branch: doc() }
    inputs[which] = prose
    const r = resolveChangelog(inputs)
    assert.equal(r.ok, false, which)
    assert.equal(r.code, 'invalid', which)
  }
})

test('S10: a non-blank region line before the first ### heading is invalid', () => {
  const early = `# Changelog\n\n## [Unreleased]\n\n${bl('X.', 'x')}\n\n### Added\n\n${bl('Y.', 'y')}\n\n${TAIL}`
  const r = resolveChangelog({ ancestor: doc(), base: doc(), branch: early })
  assert.equal(r.ok, false)
  assert.equal(r.code, 'invalid')
})

// ── S11: line endings ───────────────────────────────────────────────────────────────────────

test('S11: a CRLF base yields a fully CRLF output with the LF-resolved content', () => {
  const base = toCrlf(doc(sec('Added', bl('A.', 'a'))))
  const branch = doc(sec('Added', bl('B.', 'b')))
  const r = ok(resolveChangelog({ ancestor: doc(), base, branch }))
  const lf = doc(sec('Added', bl('B.', 'b'), bl('A.', 'a')))
  assert.equal(r.text, toCrlf(lf))
  assert.ok(!/(?<!\r)\n/.test(r.text), 'no bare LF')
})

test('S11: an LF base yields LF output even when branch and ancestor are CRLF', () => {
  const base = doc(sec('Added', bl('A.', 'a')))
  const r = ok(resolveChangelog({ ancestor: toCrlf(doc()), base, branch: toCrlf(doc(sec('Added', bl('B.', 'b')))) }))
  assert.ok(!r.text.includes('\r'))
  assert.equal(r.text, doc(sec('Added', bl('B.', 'b'), bl('A.', 'a'))))
})

test('S11: a CRLF branch equal to an LF base after normalisation produces no discard', () => {
  const ancestor = doc(sec('Added', bl('X.', 'old')))
  const base = doc(sec('Added', bl('X.', 'new')))
  const r = ok(resolveChangelog({ ancestor, base, branch: toCrlf(base) }))
  assert.deepEqual(r.discarded, [])
  assert.equal(r.text, base)
})

// ── S12: deletions ──────────────────────────────────────────────────────────────────────────

test('S12a: branch deletes a bullet that base left alone -> dropped (R2), nothing discarded', () => {
  const both = doc(sec('Added', bl('X.', 'x'), bl('Y.', 'y')))
  const r = ok(resolveChangelog({ ancestor: both, base: both, branch: doc(sec('Added', bl('X.', 'x'))) }))
  assert.equal(r.text, doc(sec('Added', bl('X.', 'x'))))
  assert.deepEqual(r.discarded, [])
})

test('S12b: branch deletes a bullet that base modified -> base kept, discarded with branchText null (R4)', () => {
  const r = ok(resolveChangelog({
    ancestor: doc(sec('Added', bl('X.', 'x'), bl('Y.', 'y'))),
    base: doc(sec('Added', bl('X.', 'x'), bl('Y.', 'y changed on base'))),
    branch: doc(sec('Added', bl('X.', 'x'))),
  }))
  assert.equal(r.text, doc(sec('Added', bl('X.', 'x'), bl('Y.', 'y changed on base'))))
  assert.deepEqual(r.discarded, [{ section: 'Added', key: 'Y.', branchText: null }])
})

test('S12b: CLI reports a deleted-on-branch discard on stderr', (t) => {
  const p = setup(t, {
    ancestor: doc(sec('Added', bl('Y.', 'y'))),
    base: doc(sec('Added', bl('Y.', 'y changed on base'))),
    branch: doc(),
  })
  const r = cliStd(p)
  assert.equal(r.status, 0, r.stderr)
  assert.ok(r.stderr.includes('DISCARDED [Added] Y.'), r.stderr)
  assert.ok(r.stderr.includes('(deleted on branch)'), r.stderr)
  assert.equal(readFileSync(p.out, 'utf8'), doc(sec('Added', bl('Y.', 'y changed on base'))))
})

// ── S13: bullet keys and continuation lines ─────────────────────────────────────────────────

test('S13: a bullet without a bold lead is keyed by its first line; divergent edits keep base and report the branch lines', () => {
  const r = ok(resolveChangelog({
    ancestor: doc(sec('Added', '- Plain thing\n  detail old')),
    base: doc(sec('Added', '- Plain thing\n  detail base')),
    branch: doc(sec('Added', '- Plain thing\n  detail branch')),
  }))
  assert.equal(r.text, doc(sec('Added', '- Plain thing\n  detail base')))
  assert.deepEqual(r.discarded, [
    { section: 'Added', key: 'Plain thing', branchText: '- Plain thing\n  detail branch' },
  ])
})

test('S13: indented continuation lines are kept in order', () => {
  const multi = '- **Multi.** first\n  second\n  third'
  const r = ok(resolveChangelog({
    ancestor: doc(sec('Added', bl('X.', 'x'))),
    base: doc(sec('Added', bl('X.', 'x'))),
    branch: doc(sec('Added', bl('X.', 'x'), multi)),
  }))
  assert.equal(r.text, doc(sec('Added', multi, bl('X.', 'x'))))
})

// ── S14: bullet order ───────────────────────────────────────────────────────────────────────

test('S14: new branch bullets come first in branch order, then base bullets in base order', () => {
  const ancestor = doc(sec('Added', bl('B1.', '1'), bl('B2.', '2')))
  const base = ancestor
  const branch = doc(sec('Added', bl('N2.', 'n2'), bl('B2.', '2'), bl('N1.', 'n1'), bl('B1.', '1')))
  const r = ok(resolveChangelog({ ancestor, base, branch }))
  assert.equal(r.text, doc(sec('Added', bl('N2.', 'n2'), bl('N1.', 'n1'), bl('B1.', '1'), bl('B2.', '2'))))
})

// ── probes ──────────────────────────────────────────────────────────────────────────────────

test('probe: key comparison is case-sensitive, so differently cased keys are two bullets', () => {
  const r = ok(resolveChangelog({
    ancestor: doc(),
    base: doc(sec('Fixed', bl('Fixed: X.', 'from base'))),
    branch: doc(sec('Fixed', bl('fixed: x.', 'from branch'))),
  }))
  assert.deepEqual(r.discarded, [])
  assert.equal(r.text, doc(sec('Fixed', bl('fixed: x.', 'from branch'), bl('Fixed: X.', 'from base'))))
})

test('probe: section names are case-sensitive, so ### Fixed and ### fixed are not duplicates', () => {
  const base = doc(sec('Fixed', bl('F1.', 'a')), sec('fixed', bl('F2.', 'b')))
  const r = ok(resolveChangelog({ ancestor: doc(), base, branch: doc() }))
  assert.equal(r.text, base)
})

test('probe: trailing whitespace alone does not make bullets differ, and output lines are right-trimmed', () => {
  const r = ok(resolveChangelog({
    ancestor: doc(),
    base: doc(sec('Added', '- **T.** x   ')),
    branch: doc(sec('Added', '- **T.** x')),
  }))
  assert.deepEqual(r.discarded, [])
  assert.equal(r.text, doc(sec('Added', '- **T.** x')))
})

test('probe: an empty-string ancestor is accepted (everything counts as added)', () => {
  const r = ok(resolveChangelog({
    ancestor: '',
    base: doc(sec('Added', bl('X.', 'base'))),
    branch: doc(sec('Added', bl('Y.', 'branch'))),
  }))
  assert.equal(r.text, doc(sec('Added', bl('Y.', 'branch'), bl('X.', 'base'))))
  assert.deepEqual(r.discarded, [])
})

test('probe: all three inputs empty resolve to empty text', () => {
  const r = ok(resolveChangelog({ ancestor: '', base: '', branch: '' }))
  assert.equal(r.text, '')
  assert.deepEqual(r.discarded, [])
})

test('probe: keys containing backticks or a lone asterisk are matched whole', () => {
  const k1 = 'Fix `a.b` handling.'
  const k2 = 'Fixed: a*b.'
  const r = ok(resolveChangelog({
    ancestor: doc(),
    base: doc(sec('Fixed', bl(k1, 'base'), bl(k2, 'base'))),
    branch: doc(sec('Fixed', bl(k1, 'branch'), bl(k2, 'branch'))),
  }))
  assert.equal(r.text, doc(sec('Fixed', bl(k1, 'base'), bl(k2, 'base'))))
  const byKey = Object.fromEntries(r.discarded.map((d) => [d.key, d]))
  assert.deepEqual(Object.keys(byKey).sort(), [k1, k2].sort())
  assert.equal(byKey[k1].branchText, bl(k1, 'branch'))
  assert.equal(byKey[k2].branchText, bl(k2, 'branch'))
})

test('probe: resolving a prior output against itself is idempotent', () => {
  const expected = fixture('expected.md')
  const r = ok(resolveChangelog({ ancestor: expected, base: expected, branch: expected }))
  assert.equal(r.text, expected)
  assert.deepEqual(r.discarded, [])
})

test('probe: an indented ======= line inside a continuation is not a conflict marker', () => {
  const bullet = '- **Eq.** head\n  =======\n  tail'
  const r = ok(resolveChangelog({
    ancestor: doc(), base: doc(), branch: doc(sec('Added', bullet)),
  }))
  assert.equal(r.text, doc(sec('Added', bullet)))
})

test('probe: never throws on odd string input', () => {
  for (const s of ['', '\n', 'plain text only', '## [Unreleased]', '## [Unreleased]\n- x', '### Added\n- y', '\r\n\r\n']) {
    for (const [a, b, c] of [[s, s, s], [s, '', ''], ['', s, ''], ['', '', s]]) {
      const r = resolveChangelog({ ancestor: a, base: b, branch: c })
      assert.equal(typeof r.ok, 'boolean', JSON.stringify([a, b, c]))
      if (!r.ok) assert.ok(r.code === 'duplicate' || r.code === 'invalid')
    }
  }
})

test('probe: CLI --out pointing at the base file overwrites it with the resolved text', (t) => {
  const p = setup(t, {
    ancestor: fixture('ancestor.md'), base: fixture('base.md'), branch: fixture('branch.md'),
  })
  const r = cliStd(p, ['--out', p.base])
  assert.equal(r.status, 0, r.stderr)
  assert.equal(readFileSync(p.base, 'utf8'), fixture('expected.md'))
  assert.ok(existsSync(p.out))
  assert.equal(readFileSync(p.out, 'utf8'), SENTINEL)
})
