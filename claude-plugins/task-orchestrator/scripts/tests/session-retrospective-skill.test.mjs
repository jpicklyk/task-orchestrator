// session-retrospective-skill.test.mjs -- doc checks for the W4 rewrite of the session-retrospective skill
// (item 60f39f92). Scenario ids S11-S19 match the item's frozen `test-plan` note. Oracles: task-scope
// D1-D12 and AC3-AC8, dispatch-policy.json `classes.research`, and the GitHub heading-anchor rule for the
// shared reference's contents list. Nothing here is derived from the text of SKILL.md as it stands; each
// needle is a statement the task-scope requires or forbids. Node 22, node:test, no dependencies.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { join, dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = resolve(HERE, '..', '..')
const SKILLS = join(PLUGIN, 'skills')
const SKILL_DIR = join(SKILLS, 'session-retrospective')
const SKILL_PATH = join(SKILL_DIR, 'SKILL.md')
const lf = (s) => s.replace(/\r\n/g, '\n')
const SKILL_RAW = readFileSync(SKILL_PATH, 'utf8')
const SKILL = lf(SKILL_RAW)

// frontmatter: the lines between the first two '---' lines, key: value
function frontmatter(text) {
  const lines = text.split('\n')
  assert.equal(lines[0], '---', 'SKILL.md opens with frontmatter')
  const end = lines.indexOf('---', 1)
  assert.ok(end > 0, 'frontmatter closes')
  const out = {}
  for (const l of lines.slice(1, end)) {
    const m = /^([A-Za-z][\w-]*):\s*(.*)$/.exec(l)
    if (m) out[m[1]] = m[2].replace(/^"(.*)"$/, '$1')
  }
  return out
}
const FM = frontmatter(SKILL)

// text of a heading's section: from the heading line to the next heading of the same or a higher level
function section(text, headingRe) {
  const m = headingRe.exec(text)
  assert.ok(m, 'heading ' + headingRe + ' exists')
  const level = /^#+/.exec(m[0])[0].length
  const rest = text.slice(m.index + m[0].length)
  const next = rest.search(new RegExp('^#{1,' + level + '} ', 'm'))
  return m[0] + (next === -1 ? rest : rest.slice(0, next))
}

// relative markdown links from a text, resolved against dir
function linkedFiles(text, dir) {
  const out = []
  for (const m of text.matchAll(/\]\(([^)#\s]+\.md)(?:#[^)]*)?\)/g)) {
    if (/^[a-z]+:/i.test(m[1])) continue
    out.push(resolve(dir, m[1]))
  }
  return [...new Set(out)]
}

// -- S11: size --------------------------------------------------------------------------------------

test('S11: SKILL.md is at most 250 lines and at most 14000 bytes (4k tokens at 3.5 bytes per token)', () => {
  const lines = SKILL_RAW.split('\n').length - (SKILL_RAW.endsWith('\n') ? 1 : 0)
  assert.ok(lines <= 250, 'lines: ' + lines)
  const bytes = Buffer.byteLength(SKILL_RAW, 'utf8')
  assert.ok(bytes <= 14000, 'bytes: ' + bytes)
})

// -- S12: frontmatter -------------------------------------------------------------------------------

test('S12: frontmatter model and effort equal dispatch-policy classes.research; context fork with an agent', () => {
  const policy = JSON.parse(readFileSync(join(PLUGIN, 'dispatch-policy.json'), 'utf8'))
  const research = policy.classes.research
  assert.equal(FM.model, research.model)
  assert.equal(FM.effort, research.effort)
  assert.equal(FM.context, 'fork')
  assert.ok(FM.agent && FM.agent.length > 0, 'an agent is named')
  assert.equal(FM.name, 'session-retrospective')
})

// -- S13: description -------------------------------------------------------------------------------

test('S13: the description drops the broad triggers and keeps the narrow ones', () => {
  const d = FM.description.toLowerCase()
  for (const dropped of ['wrap up', 'how did that go', 'end of session review', 'what did we learn', 'evaluate our process']) {
    assert.equal(d.includes(dropped), false, 'description must not contain "' + dropped + '"')
  }
  for (const kept of ['retrospective', 'session review', 'analyze this run']) {
    assert.ok(d.includes(kept), 'description keeps "' + kept + '"')
  }
})

// -- S14: no interaction, no --deep -----------------------------------------------------------------

test('S14: SKILL.md and the references it links ask nobody anything and carry no --deep; the hint keeps the repeatable --run and --dry-run', () => {
  const linked = linkedFiles(SKILL, SKILL_DIR).filter((f) => f.startsWith(SKILL_DIR + '\\') || f.startsWith(SKILL_DIR + '/'))
  assert.ok(linked.length >= 1, 'SKILL.md links at least one of its own references')
  const files = [SKILL_PATH, ...linked]
  for (const f of files) {
    assert.ok(existsSync(f), 'linked file exists: ' + f)
    const text = lf(readFileSync(f, 'utf8')).replaceAll('orchestrator-confirmation', '')
    for (const re of [/AskUserQuestion/, /\bconfirm/i, /end the turn/i, /--deep/]) {
      assert.equal(re.test(text), false, f + ' must not match ' + re)
    }
  }
  assert.ok(FM['argument-hint'].includes('--run <runId>'), 'hint names --run <runId>')
  assert.ok(FM['argument-hint'].includes('(repeatable)'), 'hint says repeatable')
  assert.ok(FM['argument-hint'].includes('--dry-run'), 'hint keeps --dry-run')
  assert.equal(FM['argument-hint'].includes('--deep'), false)
})

test('S14: deep-mode.md lives under review-retrospectives, is gone here, and SKILL.md does not link it', () => {
  assert.equal(existsSync(join(SKILL_DIR, 'references', 'deep-mode.md')), false)
  assert.ok(existsSync(join(SKILLS, 'review-retrospectives', 'references', 'deep-mode.md')))
  assert.equal(linkedFiles(SKILL, SKILL_DIR).some((f) => /deep-mode\.md$/.test(f)), false)
})

// -- S15: no dated figures --------------------------------------------------------------------------

test('S15: SKILL.md carries no 2026- date, no "First baseline" paragraph and no 31% or 1127 figure', () => {
  assert.equal(/2026-\d\d/.test(SKILL), false, 'no dated baseline')
  for (const needle of ['First baseline', '31%', '1127']) {
    assert.equal(SKILL.includes(needle), false, 'SKILL.md must not contain "' + needle + '"')
  }
})

// -- S16: checklist and gate loop -------------------------------------------------------------------

test('S16: a "- [ ]" checklist precedes Step 1; Step 8b reads the item context before advance complete; a gate_blocked retry once is stated', () => {
  const checklist = SKILL.indexOf('- [ ]')
  const step1 = SKILL.search(/^## Step 1\b/m)
  assert.ok(checklist !== -1, 'a checklist exists')
  assert.ok(step1 !== -1, 'Step 1 heading exists')
  assert.ok(checklist < step1, 'the checklist comes before Step 1')

  const s8b = section(SKILL, /^## Step 8b\b[^\n]*$/m)
  const ctx = s8b.search(/"tool":\s*"items_read",\s*"select":\s*"context"/)
  const adv = s8b.search(/"tool":\s*"advance"[^\n]*"trigger":\s*"complete"/)
  assert.ok(ctx !== -1, 'Step 8b has an items_read context block')
  assert.ok(adv !== -1, 'Step 8b has an advance complete block')
  assert.ok(ctx < adv, 'context is read before advance complete')

  assert.match(SKILL, /gate_blocked/)
  assert.match(SKILL, /retry once/i)
})

// -- S17: Step 8a -----------------------------------------------------------------------------------

test('S17: Step 8a runs outside the 3-or-more gate, at most 10 verdicts, oldest first, reopens a terminal trend and skips cancel when terminal', () => {
  const step8 = section(SKILL, /^## Step 8 [^\n]*$/m)
  const s8a = section(SKILL, /^### 8a\.?[^\n]*$/m)
  assert.ok(step8.includes(s8a.slice(0, 40)), '8a is a subsection under the Step 8 heading')
  assert.match(s8a, /outside the 3-or-more gate/i)
  assert.match(s8a, /at most 10/i)
  assert.match(s8a, /oldest/i)
  assert.match(s8a, /reopen/i)
  assert.match(s8a, /cancel[^\n]*terminal|terminal[^\n]*cancel/i)
  assert.match(s8a, /Skip in dry-run|non-dry/i, 'the dry-run condition is stated')
})

// -- S18: shared GitHub reference -------------------------------------------------------------------

test('S18: skills/_shared/github-feedback.md has a contents list of working anchors; the old copy is gone and unlinked; both skills link the shared one', () => {
  const shared = join(SKILLS, '_shared', 'github-feedback.md')
  assert.ok(existsSync(shared))
  assert.equal(existsSync(join(SKILL_DIR, 'references', 'github-feedback.md')), false, 'old reference removed')

  const text = lf(readFileSync(shared, 'utf8'))
  const firstH2 = text.search(/^## /m)
  const intro = text.slice(0, firstH2)
  assert.match(intro, /Contents/i)
  const anchors = [...intro.matchAll(/\]\(#([^)]+)\)/g)].map((m) => m[1])
  assert.ok(anchors.length >= 6, 'one anchor per section: ' + anchors.length)
  const slug = (h) => h.toLowerCase().replace(/[^a-z0-9 _-]/g, '').replace(/ /g, '-')
  const slugs = [...text.matchAll(/^## (.+)$/gm)].map((m) => slug(m[1]))
  for (const a of anchors) assert.ok(slugs.includes(a), 'anchor #' + a + ' matches a heading')
  for (const h of ['Config gate', 'C5 - gh availability guard', 'Dedup procedure (cheapest first)', 'C4 - Issue template', 'C2 - Record-back', 'Best-effort discipline']) {
    assert.ok(slugs.includes(slug(h)), 'heading ' + h)
    assert.ok(anchors.includes(slug(h)), 'contents links ' + h)
  }

  const reviewProposals = lf(readFileSync(join(SKILLS, 'review-proposals', 'SKILL.md'), 'utf8'))
  for (const [name, body] of [['session-retrospective', SKILL], ['review-proposals', reviewProposals]]) {
    assert.ok(body.includes('](../_shared/github-feedback.md'), name + ' links ../_shared/github-feedback.md')
    assert.equal(body.includes('references/github-feedback.md'), false, name + ' has no link to the old path')
  }
  // no other skill file still points at the old location
  const walk = (dir) => readdirSync(dir).flatMap((n) => {
    const p = join(dir, n)
    return statSync(p).isDirectory() ? walk(p) : p.endsWith('.md') ? [p] : []
  })
  for (const f of walk(SKILLS)) {
    assert.equal(/references\/github-feedback\.md/.test(readFileSync(f, 'utf8')), false, f + ' links the old path')
  }
})

// -- S19: trigger section moved ---------------------------------------------------------------------

test('S19: hooks/README.md has a Retrospective trigger section naming retro-trigger, retro-backstop and retro-ack; SKILL.md has no "How the trigger works"', () => {
  const readme = lf(readFileSync(join(PLUGIN, 'hooks', 'README.md'), 'utf8'))
  const sec = section(readme, /^## Retrospective trigger[^\n]*$/m)
  for (const name of ['retro-trigger', 'retro-backstop', 'retro-ack']) {
    assert.ok(sec.includes(name), 'the section names ' + name)
  }
  assert.equal(SKILL.includes('How the trigger works'), false)
})

// -- W2 (item 16500580): finding classification and defect routing -----------------------------------
// Oracles: the item's task-scope AC1-AC10 and decisions D1-D10.

const ROUTING_PATH = join(SKILL_DIR, 'references', 'routing.md')
const ROUTING = lf(readFileSync(ROUTING_PATH, 'utf8'))
const EVALUATION = lf(readFileSync(join(SKILL_DIR, 'references', 'evaluation.md'), 'utf8'))

test('W2-AC1: Step 1 reads observations created after the run opened, skips retro-reported, and gates the review record', () => {
  const s1 = section(SKILL, /^## Step 1\b[^\n]*$/m)
  for (const needle of ['createdAfter', 'retro-reported', 'orchestrator-confirmation', 'after A<n>', 'at most 10']) {
    assert.ok(s1.includes(needle), 'Step 1 names ' + needle)
  }
})

test('W2-AC2: no delegation-metadata read in SKILL.md, evaluation.md or routing.md', () => {
  for (const [name, text] of [['SKILL.md', SKILL], ['evaluation.md', EVALUATION], ['routing.md', ROUTING]]) {
    assert.equal(text.includes('delegation-metadata'), false, name)
  }
})

test('W2-AC3: 3f names four kinds and three scopes; routing.md holds the rubric and the F-line', () => {
  const s3 = section(SKILL, /^## Step 3\b[^\n]*$/m)
  assert.ok(s3.includes('3f'))
  for (const w of ['defect', 'process', 'agent-discipline', 'positive', 'product', 'global-process', 'project']) {
    assert.ok(s3.includes('`' + w + '`'), '3f names ' + w)
  }
  assert.match(ROUTING, /F<n> kind=<[^>]*> scope=<[^>]*> dim=<dimension> route=/)
})

test('W2-AC4: 5e sits between 5d and Step 6 with no session threshold; routing.md carries the to-calls', () => {
  const i5d = SKILL.indexOf('**5d**')
  const i5e = SKILL.indexOf('**5e**')
  const i6 = SKILL.search(/^## Step 6\b/m)
  assert.ok(i5d !== -1 && i5d < i5e && i5e < i6)
  assert.match(SKILL.slice(i5e, i6), /no session threshold/)
  assert.match(ROUTING, /"select": "search"[^\n]*"matchMode": "substring"/)
  assert.match(ROUTING, /"op": "create_tree"[^\n]*"tags": \["agent-observation", "bug", "retro-reported"\][^\n]*"key": "observation-detail"/)
  assert.match(ROUTING, /"select": "schema"[^\n]*"type": "bug-fix"/)
  assert.match(ROUTING, /evidence-<YYYY-MM-DD>-<retro8>/)
})

test('W2-AC5: project trends use the project-<root8> tag and a per-project container; no project: tag in any to-call', () => {
  for (const text of [SKILL, ROUTING]) {
    assert.ok(text.includes('project-<root8>'))
    assert.equal(/"project:/.test(text), false, 'no project: tag value')
  }
  assert.match(ROUTING, /child of the project root/)
})

test('W2-AC6: agent-discipline creates nothing and ends the report as memory lines; the dispatch directive asks for them', async () => {
  assert.match(ROUTING, /No item and no trend is created/)
  assert.match(SKILL, /memory: <pattern> -> <correct behaviour> \(<reason>\)/)
  const { buildDispatch } = await import(pathToFileURL(join(PLUGIN, 'hooks', 'retro-lib.mjs')).href)
  const text = buildDispatch(['r-aaa'], 'root-1')
  assert.ok(text.includes('memory: line'))
  assert.ok(text.indexOf('save each memory: line') < text.indexOf('Dispatch exactly ONE'))
  assert.ok(text.includes('any memory: lines'))
})

test('W2-AC7: 7c files product-scope proposals only and the shared reference says product-scope; 7b scope comes from the trend', () => {
  assert.match(SKILL, /^### 7c\. GitHub issues \(product-scope proposals only\)/m)
  assert.match(lf(readFileSync(join(SKILLS, '_shared', 'github-feedback.md'), 'utf8')).split('\n')[2], /product-scope/)
  assert.match(section(SKILL, /^### 7b\b[^\n]*$/m), /`project-\*` tag/)
})

test('W2-AC8: the skill names the run marks and the stale rule', () => {
  for (const needle of ['retro-skipped', 'retro-retry', 'retro-failed', '[stale]', 'Stale: scope']) {
    assert.ok(SKILL.includes(needle), needle)
  }
})

test('W2-AC9: the defect form is taught at every Friction-form site', () => {
  const form = 'defect: <tool>, <op>, <code>, <message>'
  const sites = [
    ['..', '..', '.taskorchestrator', 'config.yaml'],
    ['..', '..', 'current', 'docs', 'integration-guides', 'self-improving-workflow.md'],
    ['skills', 'manage-schemas', 'references', 'example-schemas.md'],
    ['skills', 'manage-schemas', 'references', 'create-workflow.md'],
    ['skills', 'manage-schemas', 'references', 'edit-workflow.md'],
    ['skills', 'run-wave', 'references', 'post-run.md'],
  ]
  const friction = 'Friction: <type: description>'
  const occurrences = (text, needle) => text.split(needle).length - 1
  for (const s of sites) {
    const text = readFileSync(join(PLUGIN, ...s), 'utf8')
    assert.ok(text.includes(form), s.join('/'))
    // every site of the Friction form teaches the defect form at least as often as the Friction form occurs
    assert.ok(occurrences(text, form) >= occurrences(text, friction), s.join('/') + ': defect form fewer than Friction-form sites')
  }
  // no skill .md outside the listed sites holds the Friction form
  const listed = new Set(sites.map((s) => resolve(PLUGIN, ...s)))
  const walk = (dir) => readdirSync(dir).flatMap((n) => {
    const p = join(dir, n)
    return statSync(p).isDirectory() ? walk(p) : p.endsWith('.md') ? [p] : []
  })
  for (const f of walk(SKILLS)) {
    if (listed.has(resolve(f))) continue
    assert.equal(readFileSync(f, 'utf8').includes(friction), false, f + ' holds the Friction form but is not a listed site')
  }
})

test('W2-AC10: SKILL.md stays at most 250 lines and 14000 bytes', () => {
  const lines = SKILL_RAW.split('\n').length - (SKILL_RAW.endsWith('\n') ? 1 : 0)
  assert.ok(lines <= 250, 'lines: ' + lines)
  assert.ok(Buffer.byteLength(SKILL_RAW, 'utf8') <= 14000, 'bytes')
})

// -- W2 fix cycle A1: findings B1 and O1-O5 ------------------------------------------------------------

test('W2-B1: a scope merely not terminal yet is not stale; a manual run is stale only when abandoned (7 days, no live run)', () => {
  const marks = SKILL.split('\n').find((l) => l.startsWith('**Run marks.**'))
  assert.ok(marks, 'the Run marks paragraph exists')
  assert.match(marks, /scope merely not terminal yet is not stale/)
  assert.match(marks, /dispatch directive says its scope never completed/)
  assert.match(marks, /no open or paused run with that `scopeId`/)
  assert.match(marks, /closed over 7 days ago/)
  assert.equal(/other than the root whose item is not terminal/.test(SKILL), false, 'the in-flight-scope rule is gone')
  assert.match(EVALUATION, /never one merely still in flight/)
})

test('W2-O1: a fingerprinted defect is also searched by keywords among open bug observations', () => {
  const dedup = section(ROUTING, /^## Defect routing$/m)
  const fence = String.fromCharCode(96).repeat(3)
  const searches = dedup.split(fence).filter((b) => b.startsWith('to-call') && b.includes('"select": "search"'))
  assert.equal(searches.length, 2, 'two search calls')
  assert.ok(searches.some((b) => b.includes('<fingerprint>')), 'fingerprint search')
  assert.ok(searches.some((b) => b.includes('"tags": ["bug"]') && !b.includes('<fingerprint>')), 'keyword search tagged bug')
  assert.match(dedup, /hand-filed .\[bug\]. observation carries no .Fingerprint:. line/)
})

test('W2-O2: the regression description and the create shape agree', () => {
  const sec = section(ROUTING, /^## Defect routing$/m)
  assert.equal(/description starts .Regression of/.test(sec), false, 'no description that starts with Regression')
  assert.match(sec, /.Fingerprint: <fp>. on line 1[^\n]*.Regression of <short>. on the next line/)
  assert.ok(sec.includes('"description": "Fingerprint: <fp>\\n[Regression of <short>\\n]'), 'the create call carries the regression line')
})

test('W2-O3: the global container searches keep the depth-0 root-item qualifier', () => {
  assert.match(section(SKILL, /^## Step 4\b[^\n]*$/m), /depth-0 root item[^\n]*no parent/)
  assert.match(SKILL, /\*\*7a\*\* Find or create the .Improvement Proposals. root item \(unscoped, as 5a\)/)
  assert.match(SKILL, /\*\*5a\*\* Find the .Session Retrospectives. root item/)
})

test('W2-O4: Step 6 new-trend creation sets the retrospective-trend and dimension tags', () => {
  const s6 = section(SKILL, /^## Step 6\b[^\n]*$/m)
  assert.match(s6, /tags: \["retrospective-trend", "<dimension>"\]/)
})

test('W2-O5: a director session is told to relay memory lines, a non-director to save them', async () => {
  const { buildDispatch, directiveFor } = await import(pathToFileURL(join(PLUGIN, 'hooks', 'retro-lib.mjs')).href)
  const dir = buildDispatch(['r-aaa'], 'root-1', undefined, { director: true })
  assert.ok(dir.includes('relay each memory: line'))
  assert.equal(dir.includes('save each memory: line'), false)
  assert.ok(dir.includes('any memory: lines'))
  const plain = buildDispatch(['r-aaa'], 'root-1')
  assert.ok(plain.includes('save each memory: line'))
  assert.equal(plain.includes('relay each memory'), false)
  const result = { won: ['r-aaa'], nudge: [], failed: [], stale: [], fixCycle: {} }
  assert.ok(directiveFor(result, null, { director: true }).includes('relay each memory: line'))
  assert.ok(directiveFor(result, null, {}).includes('save each memory: line'))
})

// -- A3: the bug-report step is unconditional -------------------------------------------------------

test('A3: the checklist carries its own 5e bug-report line and Step 5e and 8b make the run unconditional', () => {
  const step1 = SKILL.search(/^## Step 1\b/m)
  const line = SKILL.indexOf('- [ ] 5e Run bug-report (always; the command decides)')
  assert.ok(line !== -1 && line < step1, 'checklist line before Step 1')
  const i5e = SKILL.indexOf('**5e**')
  const s5e = SKILL.slice(i5e, SKILL.search(/^## Step 6\b/m))
  assert.match(s5e, /ALWAYS run `node "<helper>" bug-report --root <root> --run <run>\.\.\.`/)
  assert.match(s5e, /gate open or closed, with or without defects/)
  // The command answers `gate: disabled` in its JSON `gate` field; it prints no separate line.
  assert.match(s5e, /answers `gate: disabled` when closed/)
  assert.doesNotMatch(s5e, /prints `gate: disabled`/)
  // Fix cycle A4 (B2): one --product per routed product defect; none files nothing.
  assert.match(s5e, /plus one `--product <fingerprint>:<observation>` per product defect routed here \(none: nothing filed\)/)
  assert.match(s5e, /never check the gate or skip the step/)
  assert.doesNotMatch(s5e, /When the user-level `bug_reports` gate is open, run/)
  const s8b = section(SKILL, /^## Step 8b\b[^\n]*$/m)
  assert.match(s8b, /result `line`s, else its `gate` value \(not complete without it\)/)
  const GH = lf(readFileSync(resolve(HERE, '../../skills/_shared/github-feedback.md'), 'utf8'))
  assert.match(GH, /always run; |Always run, once per retrospective/)
  // The reference documents the --product contract and the gap fields (A4 B1, B2, O3).
  assert.match(GH, /--product <fingerprint>\[:<observation>\]/)
  assert.match(GH, /`skipped` with reason `not-product`/)
  assert.match(GH, /`gaps` lists what could not be read/)
  assert.match(GH, /`unavailable` \(no API URL resolved/)
  assert.match(GH, /answers `gate: disabled` in its JSON/)
  assert.match(ROUTING, /always run; the command decides/)
})
