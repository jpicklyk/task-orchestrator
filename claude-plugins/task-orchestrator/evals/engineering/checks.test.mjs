import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, existsSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { grade, parseFindings, parseVerdict, parseRanks } from './checks.mjs';
import { loadScenarios, validateScenarios, buildRepo, observe, parseArgs, resultRow } from './run-evals.mjs';

test('the shipped scenario set is valid: 3 or more per skill, known check types, fixtures present', () => {
  assert.deepEqual(validateScenarios(loadScenarios()), []);
});

test('validateScenarios names a skill with fewer than 3 scenarios and an unknown check type', () => {
  const [one] = loadScenarios();
  const problems = validateScenarios([{ ...one, checks: [{ id: 'X', type: 'nope', pattern: 'p' }] }]);
  assert.ok(problems.some((p) => /unknown check type nope/.test(p)));
  assert.ok(problems.some((p) => /at least 3 are required/.test(p)));
});

test('parsers read the finding, verdict and rank formats', () => {
  const out = 'FINDING a.js:3 | bad\nnoise\nfinding b.js:1 | worse\nVERDICT: changes\nRANK 2 | src/b.js | Plan it | why\nRANK 1 | src/a.js | Act now | why | more';
  assert.equal(parseFindings(out).length, 2);
  assert.equal(parseVerdict(out), 'changes');
  assert.deepEqual(parseRanks(out).map((r) => [r.rank, r.path, r.rating]), [[1, 'src/a.js', 'Act now'], [2, 'src/b.js', 'Plan it']]);
  assert.equal(parseVerdict('nothing'), null);
});

const scn = (checks, extra = {}) => ({ checks, ...extra });

test('added-matches fails only when the pattern is added inside the path filter', () => {
  const s = scn([{ id: 'c', type: 'added-matches', in: '^src/', notIn: 'money\\.js$', regex: 'toFixed', pattern: 'p' }]);
  assert.equal(grade(s, { added: { 'src/a.js': 'x.toFixed(2)' } }).outcome, 'fail');
  assert.equal(grade(s, { added: { 'src/money.js': 'x.toFixed(2)' } }).outcome, 'no failure');
  assert.equal(grade(s, { added: { 'test/a.js': 'x.toFixed(2)' } }).outcome, 'no failure');
});

test('added-lacks fails when nothing matching is added', () => {
  const s = scn([{ id: 'c', type: 'added-lacks', in: '^GLOSSARY\\.md$', regex: 'backorder', pattern: 'p' }]);
  assert.equal(grade(s, { added: {} }).outcome, 'fail');
  assert.equal(grade(s, { added: { 'GLOSSARY.md': '## Backorder' } }).outcome, 'no failure');
});

test('new-file-matches honours min', () => {
  const s = scn([{ id: 'c', type: 'new-file-matches', min: 2, regex: '^src/(read|parse)', pattern: 'p' }]);
  assert.equal(grade(s, { newFiles: ['src/readConfig.js'] }).outcome, 'no failure');
  assert.equal(grade(s, { newFiles: ['src/readConfig.js', 'src/parseConfig.js'] }).outcome, 'fail');
});

test('review checks: missing finding, false positive and verdict', () => {
  const s = scn([
    { id: 'm', type: 'finding-missing', regex: 'retry\\.js.*error', pattern: 'swallowed' },
    { id: 'f', type: 'finding-present', regex: 'repo', pattern: 'false positive' },
    { id: 'v', type: 'verdict-is', expect: 'changes', pattern: 'approves' },
  ], { output: 'review.md' });
  const good = grade(s, { output: 'FINDING retry.js:4 | swallows the error\nVERDICT: changes' });
  assert.equal(good.outcome, 'no failure');
  const bad = grade(s, { output: 'FINDING repo.js:1 | pass-through\nVERDICT: approve' });
  assert.deepEqual(bad.failures.sort(), ['approves', 'false positive', 'swallowed']);
  assert.equal(grade(s, { output: null }).failures[0], 'no output file');
});

test('rank checks read the first rank, a rating and a line', () => {
  const out = 'RANK 1 | src/legacy.js | Act now | ugly\nRANK 2 | src/pricing.js | Plan it | changes often, see 0002';
  const first = scn([{ id: 'a', type: 'rank-first-matches', regex: 'pricing', pattern: 'first' }], { output: 'audit.md' });
  assert.equal(grade(first, { output: out }).outcome, 'fail');
  const rating = scn([{ id: 'b', type: 'rank-rating-not', path: 'legacy', rating: 'act now', pattern: 'rating' }], { output: 'audit.md' });
  assert.equal(grade(rating, { output: out }).outcome, 'fail');
  const lacks = scn([{ id: 'c', type: 'rank-line-lacks', path: 'pricing', regex: '0002', pattern: 'record' }], { output: 'audit.md' });
  assert.equal(grade(lacks, { output: out }).outcome, 'no failure');
  assert.equal(grade(lacks, { output: 'RANK 1 | src/pricing.js | Plan it | slow' }).outcome, 'fail');
});

test('output-lacks fails when the pass is absent', () => {
  const s = scn([{ id: 'g', type: 'output-lacks', regex: 'glossary', pattern: 'no pass' }], { output: 'audit.md' });
  assert.equal(grade(s, { output: 'RANK 1 | a | Watch | x' }).outcome, 'fail');
  assert.equal(grade(s, { output: 'RANK 1 | a | Watch | glossary drift' }).outcome, 'no failure');
});

test('buildRepo and observe: layered fixture commits base then change; history fixture builds a log', () => {
  const dir = mkdtempSync(join(tmpdir(), 'eng-eval-test-'));
  try {
    const all = loadScenarios();
    const review = all.find((s) => s.id === 'spotting-code-slop-1-seeded-diff');
    const w1 = join(dir, 'r1');
    buildRepo(review, w1);
    assert.ok(existsSync(join(w1, 'src/orders/retry.js')), 'the change overlay is committed');
    assert.ok(!existsSync(join(w1, '_history.json')));
    mkdirSync(join(w1, 'src/x'), { recursive: true });
    writeFileSync(join(w1, 'src/x/new.js'), 'export const a = 1;\n');
    writeFileSync(join(w1, 'review.md'), 'VERDICT: changes\n');
    const obs = observe(review, w1, 'done');
    assert.deepEqual(obs.newFiles, ['src/x/new.js']);
    assert.match(obs.added['src/x/new.js'], /export const a/);
    assert.equal(obs.output, 'VERDICT: changes\n');
    assert.equal(obs.added['review.md'], undefined);

    const audit = all.find((s) => s.id === 'auditing-codebase-health-1-hot-spot');
    const w2 = join(dir, 'r2');
    buildRepo(audit, w2);
    const log = readFileSync(join(w2, 'src/pricing.js'), 'utf8');
    assert.match(log, /revision 14/);
    assert.ok(existsSync(join(w2, 'src/legacyReport.js')));
    assert.doesNotMatch(readFileSync(join(w2, 'src/legacyReport.js'), 'utf8'), /revision/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('parseArgs reads the options and rejects an unknown one', () => {
  const a = parseArgs(['--scenario', 'x', '--model', 'opus', '--plugin-dir', '.', '--label', 'with y', '--record', '--check']);
  assert.deepEqual([a.scenarios, a.models, a.label, a.record, a.check], [['x'], ['opus'], 'with y', true, true]);
  assert.throws(() => parseArgs(['--nope']), /unknown argument/);
  assert.throws(() => parseArgs(['--model']), /needs a value/);
});

test('resultRow flags a run with no failure', () => {
  const row = resultRow({ date: 'd', sha: 's', model: 'm', scenario: 'x', mode: 'baseline', outcome: 'no failure', failures: [] });
  assert.match(row, /\| pass \| no failure \(write no skill text for it\) \|$/);
  assert.match(resultRow({ date: 'd', sha: 's', model: 'm', scenario: 'x', mode: 'baseline', outcome: 'fail', failures: ['a', 'b'] }), /\| fail \| a; b \|$/);
});
