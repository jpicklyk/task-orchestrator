// Coverage for audit-report-html (lib + CLI), item b8761c37. Scenario ids S1-S17 and the probe list
// match the item's `test-plan` note. Oracles: the item's `task-scope` note (Contract C3, Decisions
// D1-D13, acceptance criteria); every expected value below is derived from those clauses or
// recomputed from the checked-in fixture input, never read from the implementation. The suite
// imports only the declared exports `renderAuditReport` and `escapeHtml` and spawns the declared CLI.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, mkdtempSync, rmSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { tmpdir } from 'node:os'
import { spawnSync } from 'node:child_process'

import { renderAuditReport, escapeHtml } from '../lib/audit-report-html.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const CLI = join(HERE, '..', 'audit-report-html.mjs')
const FIX = join(HERE, 'fixtures', 'audit-report-html')
const WRAPPER_PATH = join(FIX, 'result-wrapper.json')
const METRICS_PATH = join(FIX, 'metrics.json')
const loadJson = (p) => JSON.parse(readFileSync(p, 'utf8'))
const clone = (v) => JSON.parse(JSON.stringify(v))

const WRAPPER = loadJson(WRAPPER_PATH)
const METRICS = loadJson(METRICS_PATH)
const RESULT = WRAPPER.result
const KEPT = RESULT.kept

// ── helpers ─────────────────────────────────────────────────────────────────────────────────

function textOf(html) {
  return html
    .replace(/<style[\s\S]*?<\/style>/g, ' ')
    .replace(/<script[\s\S]*?<\/script>/g, ' ')
    .replace(/<[^>]*>/g, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')
    .replace(/\s+/g, ' ')
}

const SECTION_ORDER = ['summary', 'strengths', 'themes', 'findings', 'roadmap', 'hotspots', 'architecture', 'coverage']
// The slice of the page from section `id` up to the next later section id (D8 order) or the end.
function section(html, id) {
  const start = html.indexOf(`id="${id}"`)
  assert.notEqual(start, -1, `section ${id} present`)
  let end = html.length
  for (const later of SECTION_ORDER.slice(SECTION_ORDER.indexOf(id) + 1)) {
    const i = html.indexOf(`id="${later}"`, start + 1)
    if (i !== -1 && i < end) end = i
  }
  return html.slice(start, end)
}

function cards(html) {
  const out = []
  const re = /<details class="card"([^>]*)>([\s\S]*?)<\/details>/g
  let m
  while ((m = re.exec(html))) out.push({ attrs: m[1], open: m[0].slice(0, m[0].indexOf('>') + 1), html: m[0] })
  return out
}
const cardIds = (html) => cards(html).map((c) => (/ id="([^"]*)"/.exec(c.attrs) || [])[1])
const count = (s, needle) => s.split(needle).length - 1

function chips(html) {
  const out = []
  const re = /<button class="chip" data-g="([^"]*)" data-f="([^"]*)">([\s\S]*?) <b>(\d+)<\/b><\/button>/g
  let m
  while ((m = re.exec(html))) out.push({ g: m[1], f: m[2], label: m[3], n: Number(m[4]) })
  return out
}
function tiles(html) {
  const out = {}
  const re = /<div class="tile"><b>([^<]*)<\/b><span>([^<]*)<\/span><\/div>/g
  let m
  while ((m = re.exec(html))) out[m[2]] = m[1]
  return out
}
function tableRows(sectionHtml) {
  const rows = []
  const re = /<tr[^>]*>([\s\S]*?)<\/tr>/g
  let m
  while ((m = re.exec(sectionHtml))) {
    if (!/<td/.test(m[1])) continue
    const cells = [...m[1].matchAll(/<td[^>]*>([\s\S]*?)<\/td>/g)].map((c) => textOf(c[1]).trim())
    rows.push({ html: m[0], cells })
  }
  return rows
}

let seq = 0
function row(over = {}) {
  seq += 1
  return {
    findingId: `f-${seq}`,
    reportId: `R${String(seq).padStart(2, '0')}`,
    title: `Title ${seq}`,
    severity: 'low',
    verdict: 'CONFIRMED',
    category: 'cohesion',
    effort: 'S',
    locations: [`a/b${seq}.kt:1`],
    ...over,
  }
}
const res = (kept, extra = {}) => ({ contract: 'audit/result-v2', kept, ...extra })

function scratch(t) {
  const dir = mkdtempSync(join(tmpdir(), 'audit-html-'))
  t.after(() => rmSync(dir, { recursive: true, force: true }))
  return dir
}
function runCli(args) {
  return spawnSync(process.execPath, [CLI, ...args], { encoding: 'utf8' })
}

const FULL = renderAuditReport(WRAPPER, METRICS)

// ── S1 determinism (AC1, D1) ────────────────────────────────────────────────────────────────

test('S1 in-process render is repeatable and needs no clock', () => {
  const a = renderAuditReport(WRAPPER, METRICS)
  const b = renderAuditReport(WRAPPER, METRICS)
  assert.equal(a, b)
  const RealDate = globalThis.Date
  let stubbed
  try {
    globalThis.Date = function () { throw new Error('Date must not be used by the renderer') }
    stubbed = renderAuditReport(WRAPPER, METRICS)
  } finally {
    globalThis.Date = RealDate
  }
  assert.equal(stubbed, a)
  assert.ok(a.endsWith('\n') && !a.endsWith('\n\n'), 'ends with exactly one LF (D1)')
  assert.ok(!a.includes('\r'), 'LF only, no CR byte (D1, platform probe)')
  assert.ok(textOf(a).includes(RESULT.date), 'result.date is the only date shown (D1)')
})

test('S1 CLI output is byte-identical across runs and equals the lib render', (t) => {
  const dir = scratch(t)
  const o1 = join(dir, 'one.html')
  const o2 = join(dir, 'two.html')
  const args = ['--result', WRAPPER_PATH, '--metrics', METRICS_PATH]
  const r1 = runCli([...args, '--out', o1])
  const r2 = runCli([...args, '--out', o2])
  assert.equal(r1.status, 0, r1.stderr)
  assert.equal(r2.status, 0, r2.stderr)
  assert.ok(readFileSync(o1).equals(readFileSync(o2)))
  assert.equal(readFileSync(o1, 'utf8'), FULL)
})

// ── S2 anchors and card order (AC3, D3) ─────────────────────────────────────────────────────

test('S2 every kept row with a reportId has its lowercase anchor exactly once, in numeric order', () => {
  for (const k of KEPT) {
    assert.equal(count(FULL, `id="${k.reportId.toLowerCase()}"`), 1, k.reportId)
  }
  const expected = KEPT.map((k) => k.reportId).sort((a, b) => Number(a.slice(1)) - Number(b.slice(1))).map((s) => s.toLowerCase())
  assert.deepEqual(cardIds(FULL), expected)
})

test('S2 probe: numeric, not string, order (R9 < R10 < R100)', () => {
  const html = renderAuditReport(res([row({ reportId: 'R100' }), row({ reportId: 'R9' }), row({ reportId: 'R10' })]))
  assert.deepEqual(cardIds(html), ['r9', 'r10', 'r100'])
})

test('S2 card opening tag carries id, data-sev, data-verdict, data-rating, data-class in that order (D3)', () => {
  const html = renderAuditReport(
    res([row({ reportId: 'R07', severity: 'medium', verdict: 'PLAUSIBLE', rating: 'watch', findingId: 'x1' })], {
      proposal: { items: [{ findingId: 'x1', class: 'bug' }] },
    }),
  )
  const open = cards(html)[0].open
  assert.equal(open, '<details class="card" id="r07" data-sev="medium" data-verdict="PLAUSIBLE" data-rating="watch" data-class="bug">')
})

// ── S3 wrapper and bare input (AC4) ─────────────────────────────────────────────────────────

test('S3 wrapper and bare result render identically, in-process and through the CLI', (t) => {
  assert.equal(renderAuditReport(WRAPPER, METRICS), renderAuditReport(RESULT, METRICS))
  const dir = scratch(t)
  const bare = join(dir, 'bare.json')
  writeFileSync(bare, JSON.stringify(RESULT))
  const o1 = join(dir, 'w.html')
  const o2 = join(dir, 'b.html')
  assert.equal(runCli(['--result', WRAPPER_PATH, '--out', o1]).status, 0)
  assert.equal(runCli(['--result', bare, '--out', o2]).status, 0)
  assert.equal(readFileSync(o1, 'utf8'), readFileSync(o2, 'utf8'))
})

test('S3 a bare kept array wins over a nested result', () => {
  const html = renderAuditReport({ kept: [row({ title: 'OUTER-WINS' })], result: { kept: [row({ title: 'INNER-LOSES' })] } })
  assert.ok(html.includes('OUTER-WINS'))
  assert.ok(!html.includes('INNER-LOSES'))
})

// ── S4 tiles (D5) ───────────────────────────────────────────────────────────────────────────

test('S4 hero tiles equal the counts recomputed from kept[], not from stats', () => {
  const n = (f) => String(KEPT.filter(f).length)
  assert.deepEqual(tiles(FULL), {
    Kept: String(KEPT.length),
    Confirmed: n((k) => k.verdict === 'CONFIRMED'),
    Unverified: n((k) => k.verdict === 'UNVERIFIED'),
    'Critical/high': n((k) => k.severity === 'critical' || k.severity === 'high'),
    Medium: n((k) => k.severity === 'medium'),
    Low: n((k) => k.severity === 'low'),
  })
  // stats disagrees with kept[] on purpose: the tiles must follow kept[]
  const skewed = clone(RESULT)
  skewed.stats = { ...skewed.stats, kept: 999, confirmed: 998, unverified: 997 }
  assert.deepEqual(tiles(renderAuditReport(skewed, METRICS)), tiles(FULL))
})

test('S4 critical and high both feed the Critical/high tile', () => {
  const t = tiles(renderAuditReport(res([row({ severity: 'critical' }), row({ severity: 'high' }), row({ severity: 'medium' })])))
  assert.equal(t['Critical/high'], '2')
  assert.equal(t.Medium, '1')
  assert.equal(t.Low, '0')
})

// ── S5 chips (D4, D6) ───────────────────────────────────────────────────────────────────────

function classOf(k) {
  const items = (RESULT.proposal && RESULT.proposal.items) || []
  const hit = items.find((i) => i.findingId === k.findingId)
  return hit ? hit.class : undefined
}
function expectedGroup(rows, pick, known) {
  const tally = new Map()
  for (const r of rows) {
    const v = pick(r)
    if (v === undefined || v === null || v === '') continue
    tally.set(v, (tally.get(v) || 0) + 1)
  }
  const first = known.filter((v) => tally.has(v))
  const rest = [...tally.keys()].filter((v) => !known.includes(v)).sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
  return [...first, ...rest].map((v) => ({ f: v, n: tally.get(v) }))
}

test('S5 fixture chips: per group the values and counts match the fixture, in D6 order', () => {
  const all = chips(FULL)
  const pick = (g) => all.filter((c) => c.g === g).map((c) => ({ f: c.f, n: c.n }))
  assert.deepEqual(pick('verdict'), expectedGroup(KEPT, (k) => k.verdict, ['CONFIRMED', 'PLAUSIBLE', 'UNVERIFIED']))
  assert.deepEqual(pick('severity'), expectedGroup(KEPT, (k) => k.severity, ['critical', 'high', 'medium', 'low']))
  assert.deepEqual(pick('rating'), expectedGroup(KEPT, (k) => k.rating, ['act-now', 'plan-it', 'watch']))
  assert.deepEqual(pick('class'), expectedGroup(KEPT, classOf, ['bug', 'tech-debt', 'observation']))
  for (const c of all.filter((x) => x.g !== 'all')) assert.equal(c.label, c.f, 'chip label is the value')
  assert.ok(FULL.includes('data-g="all" data-f="all"') && count(FULL, 'data-g="all"') === 1)
})

test('S5 unknown values follow known ones in code-unit order; zero-count values get no chip; empty groups are omitted', () => {
  const html = renderAuditReport(
    res([
      row({ verdict: 'ZED' }),
      row({ verdict: 'CONFIRMED' }),
      row({ verdict: 'ALPHA' }),
      row({ verdict: 'CONFIRMED' }),
    ]),
  )
  const v = chips(html).filter((c) => c.g === 'verdict')
  assert.deepEqual(v.map((c) => [c.f, c.n]), [['CONFIRMED', 2], ['ALPHA', 1], ['ZED', 1]])
  assert.ok(!v.some((c) => c.f === 'UNVERIFIED' || c.f === 'PLAUSIBLE'))
  assert.ok(!chips(html).some((c) => c.g === 'class'), 'no proposal -> no class group (D4)')
  assert.ok(!chips(html).some((c) => c.g === 'rating'), 'no rating on any row -> no rating group')
})

test('S5 class chips follow the workflow vocabulary: bug, tech-debt, observation, then others in code-unit order', () => {
  const classes = ['zzz', 'observation', 'tech-debt', 'aaa', 'bug', 'tech-debt']
  const rows = classes.map((_, i) => row({ findingId: `c${i}` }))
  const proposal = { items: classes.map((c, i) => ({ findingId: `c${i}`, class: c })) }
  const got = chips(renderAuditReport(res(rows, { proposal }))).filter((c) => c.g === 'class')
  assert.deepEqual(got.map((c) => [c.f, c.n]), [['bug', 1], ['tech-debt', 2], ['observation', 1], ['aaa', 1], ['zzz', 1]])
  // the fixture spans bug and tech-debt, so the order is also observable on real data
  assert.equal(chips(FULL).filter((c) => c.g === 'class').map((c) => c.f).join(), 'bug,tech-debt')
})

test('S5 class comes only from the matching proposal row, never from category (D4)', () => {
  const rows = [row({ findingId: 'm1', category: 'bug' }), row({ findingId: 'm2', category: 'bug' })]
  const html = renderAuditReport(res(rows, { proposal: { items: [{ findingId: 'm1', class: 'tech-debt' }, { findingId: 'zzz', class: 'bug' }] } }))
  const cs = cards(html)
  assert.ok(cs[0].open.includes('data-class="tech-debt"'))
  assert.ok(!cs[1].open.includes('data-class'))
  assert.deepEqual(chips(html).filter((c) => c.g === 'class').map((c) => [c.f, c.n]), [['tech-debt', 1]])
  const noProposal = renderAuditReport(res(rows, { proposal: null }))
  assert.ok(!noProposal.includes('data-class'))
})

// ── S6 hot spots (D10) ──────────────────────────────────────────────────────────────────────

test('S6 fixture hot spots: rows in hotspotScore order, values joined by path, null shown as "-"', () => {
  const sec = section(FULL, 'hotspots')
  const rows = tableRows(sec)
  const A = 'current/src/main/kotlin/AdvanceService.kt'
  const Y = 'current/src/main/kotlin/YamlSchemaParser.kt'
  const C = 'current/src/main/kotlin/CurrentMcpServer.kt'
  assert.equal(rows.length, METRICS.hotSet.length)
  const order = rows.map((r) => [A, Y, C].find((p) => r.html.includes(p)))
  assert.deepEqual(order, [A, Y, C], 'hotspotScore 0.92 > 0.71 > 0.44')
  const byPath = (p) => rows.find((r) => r.html.includes(p))
  for (const v of ['84', '31', '2410', '310', '0.92']) assert.ok(byPath(A).cells.includes(v), `Advance cell ${v}`)
  assert.ok(!byPath(A).cells.includes('-'), 'no missing value, full trend: no dash')
  for (const v of ['51', '12', '1500', '0.71', '-']) assert.ok(byPath(Y).cells.includes(v), `Yaml cell ${v} (branchComplexity null)`)
  for (const v of ['40', '15', '900', '120', '0.44', '-']) assert.ok(byPath(C).cells.includes(v), `Mcp cell ${v} (no trend)`)
  // files[] rows outside the hotSet do not appear
  assert.ok(!sec.includes('UnitRunner.kt') && !sec.includes('notes.txt'))
})

test('S6 sparkline: n>=2 is one polyline of n points at D10 coordinates; n=1 is one circle', () => {
  const sec = section(FULL, 'hotspots')
  assert.equal(count(sec, '<polyline'), 1)
  assert.equal(count(sec, '<circle'), 1)
  assert.ok(sec.includes('viewBox="0 0 80 20"'))
  const pts = /<polyline[^>]*points="([^"]*)"/.exec(sec)[1].match(/-?\d+(?:\.\d+)?/g).map(Number)
  const v = [1800, 2100, 2410]
  const min = Math.min(...v)
  const max = Math.max(...v)
  const want = []
  v.forEach((val, i) => {
    want.push(Number((i * 80 / (v.length - 1)).toFixed(1)), Number((20 - (val - min) / (max - min) * 20).toFixed(1)))
  })
  assert.deepEqual(pts, want)
  assert.deepEqual(want, [0, 20, 40, 10.2, 80, 0])
})

test('S6 flat series draws at y=10; samples are ordered by date then sha', () => {
  const m = clone(METRICS)
  m.hotSet = ['p.kt']
  m.files = [{ path: 'p.kt', churn: 1, churnRecent: 1, indentComplexity: 1, branchComplexity: 1, hotspotScore: 1 }]
  m.trend = [{ path: 'p.kt', samples: [{ sha: 'b', date: '2026-02-01', indentComplexity: 5 }, { sha: 'a', date: '2026-01-01', indentComplexity: 5 }] }]
  const flat = section(renderAuditReport(res([row()]), m), 'hotspots')
  const flatPts = /points="([^"]*)"/.exec(flat)[1].match(/-?\d+(?:\.\d+)?/g).map(Number)
  assert.deepEqual(flatPts, [0, 10, 80, 10])
  m.trend = [{ path: 'p.kt', samples: [{ sha: 'z', date: '2026-03-01', indentComplexity: 40 }, { sha: 'y', date: '2026-01-01', indentComplexity: 0 }] }]
  const unordered = section(renderAuditReport(res([row()]), m), 'hotspots')
  const pts = /points="([^"]*)"/.exec(unordered)[1].match(/-?\d+(?:\.\d+)?/g).map(Number)
  assert.deepEqual(pts, [0, 20, 80, 0], 'sorted by date: 0 first, 40 last')
})

test('S6 ties by path, missing score last, empty hotSet message', () => {
  const m = clone(METRICS)
  m.files = [{ path: 'b.kt', hotspotScore: 1 }, { path: 'a.kt', hotspotScore: 1 }, { path: 'c.kt' }]
  m.hotSet = ['c.kt', 'b.kt', 'a.kt']
  m.trend = []
  const rows = tableRows(section(renderAuditReport(res([row()]), m), 'hotspots'))
  assert.deepEqual(rows.map((r) => ['a.kt', 'b.kt', 'c.kt'].find((p) => r.html.includes(p))), ['a.kt', 'b.kt', 'c.kt'])
  const empty = clone(METRICS)
  empty.hotSet = []
  assert.ok(textOf(section(renderAuditReport(res([row()]), empty), 'hotspots')).includes('No hot spots in scope.'))
})

// ── S7 architecture (D11) ───────────────────────────────────────────────────────────────────

test('S7 module rows ordered by instability desc then id; cycle, unresolved and unrecognised rendered', () => {
  const sec = section(FULL, 'architecture')
  const rows = tableRows(sec)
  assert.deepEqual(rows.map((r) => r.cells[0]), ['mcp', 'interfaces/shared', 'application/service', 'domain'])
  assert.deepEqual(rows[0].cells.slice(1), ['10', '1500', '2', '6', '0.75'])
  assert.deepEqual(rows[3].cells.slice(1), ['40', '5000', '20', '0', '0.00'])
  const text = textOf(sec)
  assert.ok(text.includes('interfaces/shared -> mcp'))
  assert.ok(text.includes('Unresolved imports: 17 (includes imports of external libraries)'))
  assert.ok(text.includes('.txt (1)'))
  assert.ok(text.includes('Synthetic fixture: values are hand-written, not measured.'))
})

test('S7 instability ties by id; no cycles, unreported unresolved, several extensions sorted', () => {
  const m = clone(METRICS)
  m.modules.nodes = [
    { id: 'zeta', files: 1, loc: 1, fanIn: 1, fanOut: 1, instability: 0.5 },
    { id: 'alpha', files: 1, loc: 1, fanIn: 1, fanOut: 1, instability: 0.5 },
  ]
  m.modules.cycles = []
  delete m.modules.unresolved
  m.languages.unrecognised = { '.zz': 2, '.aa': 1 }
  const sec = section(renderAuditReport(res([row()]), m), 'architecture')
  assert.deepEqual(tableRows(sec).map((r) => r.cells[0]), ['alpha', 'zeta'])
  const text = textOf(sec)
  assert.ok(text.includes('No cycles.'))
  assert.ok(text.includes('Unresolved imports: not reported'))
  assert.ok(text.indexOf('.aa (1)') !== -1 && text.indexOf('.aa (1)') < text.indexOf('.zz (2)'))
  m.modules.cycles = [['a', 'b', 'c']]
  assert.ok(textOf(section(renderAuditReport(res([row()]), m), 'architecture')).includes('a -> b -> c'))
})

// ── S8 coverage (D12) ───────────────────────────────────────────────────────────────────────

test('S8 gap critic labels for disabled / no-result / ran', () => {
  const label = (critic) => {
    const r = clone(RESULT)
    r.report.coverage.critic = critic
    return textOf(section(renderAuditReport(r, METRICS), 'coverage'))
  }
  assert.ok(label('disabled').includes('Gap critic: disabled'))
  const nr = label('no-result')
  assert.ok(nr.includes('Gap critic: ran, no result'))
  assert.ok(!nr.includes('Gap critic: disabled'))
  const ran = label('ran')
  assert.ok(ran.includes('Gap critic: ran'))
  assert.ok(!ran.includes('no result') && !ran.includes('disabled'))
})

test('S8 verification lines show the input numbers; reviewers, notExamined, refuted titles and stats appear', () => {
  const r = clone(RESULT)
  r.report.coverage.verification = { confirmed: 5, refuted: 6, unverifiedByCap: 7, unverifiedNoVerifier: 8 }
  const text = textOf(section(renderAuditReport(r, METRICS), 'coverage'))
  for (const line of ['Confirmed: 5', 'Refuted: 6', 'Unverified (cap): 7', 'Unverified (no verifier): 8']) assert.ok(text.includes(line), line)
  // a reviewer entry is a string or a {key, label, kind} object; the page shows the string or the label
  for (const e of RESULT.report.coverage.reviewers) {
    const name = typeof e === 'string' ? e : e.label
    assert.ok(text.includes(name), name)
  }
  for (const name of RESULT.report.coverage.notExamined) assert.ok(text.includes(name), name)
  for (const f of RESULT.refuted) assert.ok(text.includes(f.title), f.title)
  assert.ok(text.includes(String(RESULT.stats.raw)) && text.includes(String(RESULT.stats.merged)))
})

test('S8 a coverage.reviewers entry shaped {key,label,kind} renders its label, a string entry renders itself', () => {
  const r = clone(RESULT)
  r.report.coverage.reviewers = [
    { key: 'zz-key-one', label: 'Label Number One', kind: 'code' },
    'plain-string-reviewer',
    { key: 'zz-key-two', label: 'Label Number Two', kind: 'lens' },
  ]
  const text = textOf(section(renderAuditReport(r, METRICS), 'coverage'))
  for (const shown of ['Label Number One', 'Label Number Two', 'plain-string-reviewer']) assert.ok(text.includes(shown), shown)
  for (const hidden of ['zz-key-one', 'zz-key-two']) assert.ok(!text.includes(hidden), `${hidden} is the key, not the label`)
  // the fixture mixes both shapes, so the old key-based expectations can no longer pass
  const fx = textOf(section(FULL, 'coverage'))
  assert.ok(fx.includes('Application support') && fx.includes('Domain core services') && fx.includes('interfaces-bootstrap'))
  assert.ok(!fx.includes('application-support') && !fx.includes('domain-core-services'))
})

// ── S9 report-id links (D7) ─────────────────────────────────────────────────────────────────

test('S9 theme and roadmap R-tokens link only to kept rows and only as whole tokens', () => {
  const html = renderAuditReport(
    res([row({ reportId: 'R05' }), row({ reportId: 'R12' })], {
      report: {
        themes: [{ name: 'Theme one', summary: 'see R05 and R99 and PR12', reportIds: ['R05', 'R99'] }],
        roadmap: { now: ['Fix R05 first'], next: ['Then R99'], later: [] },
      },
    }),
  )
  const link = '<a href="#r05">R05</a>'
  const themes = section(html, 'themes')
  assert.ok(count(themes, link) >= 2, 'summary token and reportIds entry both link')
  assert.ok(themes.includes('R99') && !themes.includes('#r99'))
  assert.ok(!html.includes('#r99'))
  assert.ok(textOf(themes).includes('PR12'))
  assert.ok(!html.includes('href="#r12"'), 'PR12 is not an R12 token')
  const road = section(html, 'roadmap')
  assert.equal(count(road, link), 1)
  assert.ok(textOf(road).includes('Then R99') && !road.includes('#r99'))
})

// ── S10 / S11 escaping (AC2, D2) ────────────────────────────────────────────────────────────

test('S10 markup in a finding renders escaped; the page keeps its single static script', () => {
  const html = renderAuditReport(
    res([row({ title: '<script>alert(1)</script>', evidence: `a & b "q" 'x'` })]),
  )
  assert.ok(html.includes('&lt;script&gt;alert(1)&lt;/script&gt;'))
  assert.ok(!html.includes('<script>alert(1)'))
  assert.equal(count(html, '<script'), 1)
  assert.equal(count(html, '</script>'), 1)
  assert.ok(html.includes('a &amp; b &quot;q&quot; &#39;x&#39;'))
  assert.ok(!html.includes(`a & b "q"`))
})

test('S10 every free-text field is escaped, including impact, recommendation, locations, theme and strengths', () => {
  const evil = '<img src=x onerror=alert(2)>'
  const html = renderAuditReport(
    res([row({ impact: evil, recommendation: evil, locations: [evil], confidence: evil, sliceKey: evil })], {
      report: { healthVerdict: evil, executiveSummary: evil, strengths: [evil], themes: [{ name: evil, summary: evil, reportIds: [] }], roadmap: { now: [evil] } },
    }),
  )
  assert.ok(!html.includes('<img'))
  assert.ok(count(html, '&lt;img src=x onerror=alert(2)&gt;') >= 8)
})

test('S11 attribute values are escaped so a quote cannot open an attribute', () => {
  const evil = 'x" onclick="y'
  const html = renderAuditReport(res([row({ severity: evil, category: evil, verdict: evil, rating: evil })]))
  assert.ok(html.includes('data-sev="x&quot; onclick=&quot;y"'))
  assert.ok(!html.includes('onclick="y"'))
  assert.ok(!html.includes('data-f="x" onclick'))
})

// ── S12 CLI failure (AC7) ───────────────────────────────────────────────────────────────────

test('S12 CLI exits 2 with INVALID: and leaves --out untouched', (t) => {
  const dir = scratch(t)
  const junk = join(dir, 'junk.json')
  writeFileSync(junk, 'this is not json')
  const nokept = join(dir, 'nokept.json')
  writeFileSync(nokept, '{}')
  const nokeptInner = join(dir, 'nokept-inner.json')
  writeFileSync(nokeptInner, JSON.stringify({ result: {} }))
  const badMetrics = join(dir, 'm0.json')
  writeFileSync(badMetrics, JSON.stringify({ ...METRICS, contract: 'audit/metrics-v0' }))
  const SENT = 'SENTINEL-DO-NOT-OVERWRITE'
  const out = join(dir, 'out.html')
  // The message wording is not pinned (C3: "INVALID: <msg>"). Each case's own reason is asserted as
  // the failure kind in the spec's words plus the argument or file that caused it, so a message that
  // names a different cause fails that case.
  const cases = {
    'missing --out': { args: (o) => ['--result', WRAPPER_PATH], reason: [/missing/i, /--out/] },
    'unknown flag': { args: (o) => ['--result', WRAPPER_PATH, '--out', o, '--bogus', 'x'], reason: [/unknown/i, /--bogus/] },
    'missing flag value': { args: (o) => ['--result', WRAPPER_PATH, '--out', o, '--metrics'], reason: [/missing/i, /value/i, /--metrics/] },
    'missing --result': { args: (o) => ['--out', o], reason: [/missing/i, /--result/] },
    'unreadable result': { args: (o) => ['--result', join(dir, 'nope.json'), '--out', o], reason: [/--result/, /nope\.json/] },
    'non-JSON': { args: (o) => ['--result', junk, '--out', o], reason: [/JSON/, /junk\.json/] },
    'no kept[]': { args: (o) => ['--result', nokept, '--out', o], reason: [/kept/] },
    'no kept[] on result': { args: (o) => ['--result', nokeptInner, '--out', o], reason: [/kept/] },
    'metrics contract v0': { args: (o) => ['--result', WRAPPER_PATH, '--metrics', badMetrics, '--out', o], reason: [/audit\/metrics-v1/] },
    'unreadable metrics': { args: (o) => ['--result', WRAPPER_PATH, '--metrics', join(dir, 'nometrics.json'), '--out', o], reason: [/--metrics/, /nometrics\.json/] },
  }
  for (const [name, { args, reason }] of Object.entries(cases)) {
    writeFileSync(out, SENT)
    const r = runCli(args(out))
    assert.equal(r.status, 2, `${name}: exit code (stderr: ${r.stderr})`)
    assert.ok(r.stderr.startsWith('INVALID: '), `${name}: stderr starts with INVALID: (got: ${r.stderr})`)
    for (const re of reason) assert.match(r.stderr, re, `${name}: reason names its own cause`)
    assert.equal(readFileSync(out, 'utf8'), SENT, `${name}: --out untouched`)
  }
  // the reasons differ per cause: a missing --out is never reported as a missing --result
  assert.doesNotMatch(runCli(cases['missing --out'].args(out)).stderr, /--result/)
  assert.doesNotMatch(runCli(cases['missing --result'].args(out)).stderr, /--out/)
  // no pre-existing out file: none is created
  const fresh = join(dir, 'fresh.html')
  const r = runCli(['--result', junk, '--out', fresh])
  assert.equal(r.status, 2)
  assert.equal(existsSync(fresh), false)
})

test('S12 the lib throws TypeError for a result without kept[] and for a foreign metrics contract', () => {
  assert.throws(() => renderAuditReport({}), TypeError)
  assert.throws(() => renderAuditReport({ result: {} }), TypeError)
  assert.throws(() => renderAuditReport(RESULT, { ...METRICS, contract: 'audit/metrics-v0' }), TypeError)
  assert.throws(() => renderAuditReport({ kept: 'nope' }), TypeError)
})

// ── S13 no metrics (AC5, D9) ────────────────────────────────────────────────────────────────

test('S13 without metrics the two sections and their nav entries are gone and the page says so', () => {
  const html = renderAuditReport(WRAPPER)
  assert.ok(!html.includes('id="hotspots"') && !html.includes('id="architecture"'))
  assert.ok(!html.includes('#hotspots') && !html.includes('#architecture'))
  assert.ok(textOf(html).includes('Metrics were not available for this run.'))
  // the same input with metrics does render them, so the absence above can fail
  assert.ok(FULL.includes('id="hotspots"') && FULL.includes('id="architecture"'))
  assert.ok(FULL.includes('#hotspots') && FULL.includes('#architecture'))
  assert.ok(!textOf(FULL).includes('Metrics were not available for this run.'))
  assert.equal(renderAuditReport(WRAPPER, null), html, 'explicit null equals omitted')
})

// ── S14 empty and null (D5, D8) ─────────────────────────────────────────────────────────────

test('S14 an empty kept[] says so and counts nothing', () => {
  const html = renderAuditReport({ kept: [] })
  assert.ok(textOf(html).includes('No findings kept.'))
  assert.ok(html.includes('id="findings"'))
  assert.equal(cards(html).length, 0)
  // all six tiles exist and read 0 (a page with no tiles must not pass this scenario)
  const t = tiles(html)
  assert.deepEqual(Object.keys(t).sort(), ['Confirmed', 'Critical/high', 'Kept', 'Low', 'Medium', 'Unverified'])
  for (const label of Object.keys(t)) assert.equal(t[label], '0', label)
  assert.equal(chips(html).filter((c) => c.g !== 'all').length, 0)
  assert.ok(!textOf(FULL).includes('No findings kept.'))
})

test('S14 report null shows the placeholder and omits strengths, themes and roadmap', () => {
  const html = renderAuditReport(res([row()], { report: null }))
  assert.ok(textOf(html).includes('No synthesis result.'))
  for (const id of ['strengths', 'themes', 'roadmap']) assert.ok(!html.includes(`id="${id}"`), id)
  assert.equal(cards(html).length, 1)
  for (const id of ['strengths', 'themes', 'roadmap']) assert.ok(FULL.includes(`id="${id}"`), `${id} present with a report`)
  assert.ok(!textOf(FULL).includes('No synthesis result.'))
})

// ── S15 empty vs absent vs null (D3, D6) ────────────────────────────────────────────────────

test('S15 trackedId badge only for a non-empty id', () => {
  const html = renderAuditReport(
    res([
      row({ reportId: 'R01', title: 'T-empty', trackedId: '' }),
      row({ reportId: 'R02', title: 'T-absent' }),
      row({ reportId: 'R03', title: 'T-set', trackedId: 'abc' }),
      row({ reportId: 'R04', title: 'T-null', trackedId: null }),
    ]),
  )
  const byTitle = (t) => textOf(cards(html).find((c) => c.html.includes(t)).html)
  assert.ok(byTitle('T-set').includes('tracked abc'))
  for (const t of ['T-empty', 'T-absent', 'T-null']) assert.ok(!byTitle(t).includes('tracked'), t)
})

test('S15 rating attribute only on rows that have one; no rating anywhere -> no rating group', () => {
  const html = renderAuditReport(res([row({ title: 'T-rated', rating: 'watch' }), row({ title: 'T-plain' })]))
  const cs = cards(html)
  assert.ok(cs.find((c) => c.html.includes('T-rated')).open.includes('data-rating="watch"'))
  assert.ok(!cs.find((c) => c.html.includes('T-plain')).open.includes('data-rating'))
  assert.deepEqual(chips(html).filter((c) => c.g === 'rating').map((c) => [c.f, c.n]), [['watch', 1]])
  const none = renderAuditReport(res([row(), row()]))
  assert.ok(!none.includes('data-rating') && !none.includes('data-g="rating"'))
})

test('S15 empty or absent reportId: no id attribute, sorted after R rows then by findingId', () => {
  const html = renderAuditReport(
    res([
      row({ reportId: '', findingId: 'b-two', title: 'T-b' }),
      row({ reportId: 'R20', findingId: 'z', title: 'T-r20' }),
      row({ reportId: undefined, findingId: 'a-one', title: 'T-a' }),
      row({ reportId: 'R3', findingId: 'y', title: 'T-r3' }),
    ]),
  )
  const cs = cards(html)
  assert.deepEqual(
    cs.map((c) => ['T-r3', 'T-r20', 'T-a', 'T-b'].find((t) => c.html.includes(t))),
    ['T-r3', 'T-r20', 'T-a', 'T-b'],
  )
  assert.ok(!/ id=/.test(cs[2].open) && !/ id=/.test(cs[3].open))
  assert.ok(/ id="r3"/.test(cs[0].open))
})

// ── S16 self-contained (AC8, D13) ───────────────────────────────────────────────────────────

test('S16 the page is self-contained, responsive and dark-aware', () => {
  const pages = [FULL, renderAuditReport(res([row({ evidence: 'see https://x.example for more' })]))]
  for (const html of pages) {
    assert.ok(!html.includes('<link'))
    assert.ok(!html.includes(' src='))
    assert.ok(!html.includes('href="http'))
    assert.equal(count(html, '<script'), 1)
    assert.ok(html.includes('<meta name="viewport"'))
    assert.match(html, /@media \(prefers-color-scheme:\s*dark\)/) // CSS whitespace after the colon is insignificant (S16 ambiguity)
    assert.ok(html.includes(':root'))
  }
  assert.ok(textOf(pages[1]).includes('https://x.example'))
  assert.ok(!pages[1].includes('href="https://x.example'))
})

// ── S17 escapeHtml (C3) ─────────────────────────────────────────────────────────────────────

test('S17 escapeHtml follows the contract', () => {
  assert.equal(escapeHtml(null), '')
  assert.equal(escapeHtml(undefined), '')
  assert.equal(escapeHtml(0), '0')
  assert.equal(escapeHtml(false), 'false')
  assert.equal(escapeHtml(''), '')
  assert.equal(escapeHtml('&<>"\''), '&amp;&lt;&gt;&quot;&#39;')
  assert.equal(escapeHtml('&amp;'), '&amp;amp;')
  assert.equal(escapeHtml('<b>"x" & \'y\'</b>'), '&lt;b&gt;&quot;x&quot; &amp; &#39;y&#39;&lt;/b&gt;')
  assert.equal(escapeHtml('plain'), 'plain')
})

// ── probes without a scenario id ────────────────────────────────────────────────────────────

test('probe: unicode and multi-line text reach the page', () => {
  const html = renderAuditReport(res([row({ evidence: 'Zoë 日本語 ✓', impact: 'line1\r\nline2' })]))
  const text = textOf(html)
  assert.ok(text.includes('Zoë 日本語 ✓'))
  assert.ok(text.includes('line1') && text.includes('line2'))
})

test('probe: v1-shaped rows with only the old fields still render', () => {
  const html = renderAuditReport({ contract: 'audit/result-v1', kept: [{ findingId: 'f', reportId: 'R01', title: 'Old', severity: 'low', verdict: 'CONFIRMED' }] })
  assert.ok(html.includes('id="r01"') && textOf(html).includes('Old'))
})

// -- Feature-review A1 fixes (item bf401ffd, test-author seat) -------------------------------
// S10 (D4): a kept row that carries finding metrics shows one "Measured:" line after the Confidence line,
// one part per finite number. Oracle: the item's task-scope D4; nothing here was read from the implementation.

const measuredLine = (inner) => `<p class="src">Measured: ${inner}</p>`
const cardWith = (html, title) => cards(html).find((c) => c.html.includes(title))

test('S10 a row with churn and complexity shows both, in that order, after the Confidence line', () => {
  const html = renderAuditReport(res([row({ title: 'T-both', confidence: 'high', metrics: { churn: 12, complexity: 340 } })]))
  const card = cardWith(html, 'T-both').html
  assert.ok(card.includes(measuredLine('churn 12, complexity 340')), 'the exact Measured paragraph')
  assert.ok(card.indexOf('Confidence') !== -1 && card.indexOf('Confidence') < card.indexOf('Measured:'), 'after the Confidence line')
  assert.equal(count(card, 'Measured:'), 1)
})

test('S10 each part shows only for a finite number: churn alone, complexity alone', () => {
  const html = renderAuditReport(
    res([
      row({ title: 'T-churn', metrics: { churn: 12 } }),
      row({ title: 'T-cx', metrics: { complexity: 340 } }),
      row({ title: 'T-zero', metrics: { churn: 0, complexity: 0 } }),
    ]),
  )
  assert.ok(cardWith(html, 'T-churn').html.includes(measuredLine('churn 12')))
  assert.ok(cardWith(html, 'T-cx').html.includes(measuredLine('complexity 340')))
  assert.ok(cardWith(html, 'T-zero').html.includes(measuredLine('churn 0, complexity 0')), 'zero is a finite number')
})

test('S10 no numeric part, no line: a string, a non-finite number, an empty object, null, a non-object or no metrics at all', () => {
  const bad = [
    { title: 'T-str', metrics: { churn: 'x' } },
    { title: 'T-str2', metrics: { churn: '12', complexity: '340' } },
    { title: 'T-nan', metrics: { churn: NaN, complexity: Infinity } },
    { title: 'T-empty', metrics: {} },
    { title: 'T-null', metrics: null },
    { title: 'T-text', metrics: 'churn 5' },
    { title: 'T-none' },
  ]
  const html = renderAuditReport(res(bad.map((b) => row(b))))
  for (const b of bad) assert.equal(cardWith(html, b.title).html.includes('Measured:'), false, b.title)
})

test('S10 a non-finite part is dropped while the finite part stays', () => {
  const html = renderAuditReport(res([row({ title: 'T-mixed', metrics: { churn: 'x', complexity: 340 } })]))
  assert.ok(cardWith(html, 'T-mixed').html.includes(measuredLine('complexity 340')))
  assert.equal(cardWith(html, 'T-mixed').html.includes('churn'), false)
})

test('S10 the fixture rows carry no metrics, so the checked-in page has no Measured line', () => {
  assert.equal(FULL.includes('Measured:'), false)
})
