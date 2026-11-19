// session-retrospective-skill.test.mjs -- doc checks for the W4 rewrite of the session-retrospective skill
// (item 60f39f92). Scenario ids S11-S19 match the item's frozen `test-plan` note. Oracles: task-scope
// D1-D12 and AC3-AC8, dispatch-policy.json `classes.research`, and the GitHub heading-anchor rule for the
// shared reference's contents list. Nothing here is derived from the text of SKILL.md as it stands; each
// needle is a statement the task-scope requires or forbids. Node 22, node:test, no dependencies.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { join, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

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
    const text = lf(readFileSync(f, 'utf8'))
    for (const re of [/AskUserQuestion/, /\bconfirm/i, /end the turn/i, /--deep/]) {
      assert.equal(re.test(text), false, f + ' must not match ' + re)
    }
  }
  assert.ok(FM['argument-hint'].includes('--run <runId>'), 'hint names --run <runId>')
  assert.ok(FM['argument-hint'].includes('(repeatable)'), 'hint says repeatable')
  assert.ok(FM['argument-hint'].includes('--dry-run'), 'hint keeps --dry-run')
  assert.equal(FM['argument-hint'].includes('--deep'), false)
})

test('S14: the deep-mode reference exists and SKILL.md does not link it', () => {
  assert.ok(existsSync(join(SKILL_DIR, 'references', 'deep-mode.md')))
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
