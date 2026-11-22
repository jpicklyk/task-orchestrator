import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, existsSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, delimiter } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = fileURLToPath(new URL('../../../../', import.meta.url));

import { grade, parseFindings, parseVerdict, parseRanks, broadTries, singleImplementations } from './checks.mjs';
import { loadScenarios, validateScenarios, buildRepo, observe, parseArgs, resultRow, preflightEnv, buildChildEnv, checkoutLabel, makeSkillPlugin, saveArtifacts, collectRows, timeoutMs, parseStream } from './run-evals.mjs';

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
  assert.equal(grade(lacks, { output: 'RANK 1 | src/other.js | Plan it | slow' }).outcome, 'fail');
  const optional = scn([{ id: 'c', type: 'rank-line-lacks', path: 'pricing', regex: '0002', absentOk: true, pattern: 'record' }], { output: 'audit.md' });
  assert.equal(grade(optional, { output: 'RANK 1 | src/other.js | Plan it | slow' }).outcome, 'no failure');
  assert.equal(grade(optional, { output: 'RANK 1 | src/pricing.js | Plan it | slow' }).outcome, 'fail');
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

    const audit = all.find((s) => s.id === 'auditing-codebase-1-hot-spot');
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
  assert.match(row, /\| pass \| no failure observed \| - \|$/);
  assert.doesNotMatch(row, /skill text/);
  const failed = resultRow({ date: 'd', sha: 's', model: 'm', scenario: 'x', mode: 'baseline', outcome: 'fail', failures: ['a', 'b'], artifacts: 'runs/r/x--m/' });
  assert.match(failed, /\| fail \| a; b \| runs\/r\/x--m\/ \|$/);
});

test('narrating-comments flags a comment that restates the next line and a change-log comment, not a why-comment', () => {
  const s = scn([{ id: 'n', type: 'narrating-comments', in: '^src/', pattern: 'p' }]);
  const narrate = '// calculate the total price\nconst totalPrice = calculate(items);\n';
  assert.equal(grade(s, { added: { 'src/a.js': narrate } }).outcome, 'fail');
  assert.equal(grade(s, { added: { 'src/a.js': '// added retry support\nretry();\n' } }).outcome, 'fail');
  assert.equal(grade(s, { added: { 'src/a.js': '// Stripe rejects amounts under 50 cents, so round up first\nconst cents = Math.max(50, total);\n' } }).outcome, 'no failure');
  assert.equal(grade(s, { added: { 'test/a.js': narrate } }).outcome, 'no failure');
});

test('swallowed-catch flags empty, log-only and default-returning catch bodies, not a rethrow or a wrapped result', () => {
  const s = scn([{ id: 'c', type: 'swallowed-catch', in: '^src/', pattern: 'p' }]);
  for (const body of ['try { x(); } catch (e) {}', 'try { x(); } catch (e) { console.error(e); }', 'try { x(); } catch { return null; }', 'try { x(); } catch (e) { /* ignore */ }']) {
    assert.equal(grade(s, { added: { 'src/a.js': body } }).outcome, 'fail', body);
  }
  for (const body of ['try { x(); } catch (e) { throw new Error(`x failed: ${e.message}`); }', 'try { x(); } catch (e) { return { ok: false, error: e.message }; }']) {
    assert.equal(grade(s, { added: { 'src/a.js': body } }).outcome, 'no failure', body);
  }
});

test('defensive-guards counts guards against min', () => {
  const s = scn([{ id: 'd', type: 'defensive-guards', in: '^src/', min: 3, pattern: 'p' }]);
  assert.equal(grade(s, { added: { 'src/a.js': 'if (a == null) return;\nif (b === undefined) return;\nif (!Array.isArray(c)) return;\n' } }).outcome, 'fail');
  assert.equal(grade(s, { added: { 'src/a.js': 'if (a == null) return;\nconst x = o?.y;\n' } }).outcome, 'no failure');
});

test('unused-export flags a new export nothing else in the added lines names', () => {
  const s = scn([{ id: 'u', type: 'unused-export', in: '^src/', pattern: 'p' }]);
  assert.equal(grade(s, { added: { 'src/a.js': 'export function helper() {}\nexport function main() { return 1; }\n', 'test/a.test.js': "import { main } from '../src/a.js';\nmain();\n" } }).outcome, 'fail');
  assert.equal(grade(s, { added: { 'src/a.js': 'export function main() { return 1; }\n', 'test/a.test.js': "import { main } from '../src/a.js';\nmain();\n" } }).outcome, 'no failure');
});

test('modified-outside fails on an existing file outside the allowed paths, never on a new file', () => {
  const s = scn([{ id: 'm', type: 'modified-outside', regex: '^(src/billing/|test/)', pattern: 'p' }]);
  assert.equal(grade(s, { added: { 'src/other/x.js': 'x' }, newFiles: [] }).outcome, 'fail');
  assert.equal(grade(s, { added: { 'src/other/x.js': 'x' }, newFiles: ['src/other/x.js'] }).outcome, 'no failure');
  assert.equal(grade(s, { added: { 'src/billing/invoice.js': 'x' }, newFiles: [] }).outcome, 'no failure');
});

test('rank-each-matches fails when any ranked path is outside the set', () => {
  const s = scn([{ id: 'r', type: 'rank-each-matches', regex: 'hooks/(a|b)\.mjs', pattern: 'p' }], { output: 'audit.md' });
  assert.equal(grade(s, { output: 'RANK 1 | hooks/a.mjs | Act now | x\nRANK 2 | hooks/b.mjs | Watch | y' }).outcome, 'no failure');
  assert.equal(grade(s, { output: 'RANK 1 | hooks/a.mjs | Act now | x\nRANK 2 | hooks/z.mjs | Watch | y' }).outcome, 'fail');
  assert.equal(grade(s, { output: 'nothing' }).outcome, 'fail');
});

test('narrating-comments leaves a doc comment that states a contract alone and flags one that only restates the declaration', () => {
  const s = scn([{ id: 'n', type: 'narrating-comments', in: '^src/', pattern: 'p' }]);
  const contract = '/** unownedKeys(notes) -> string[]: keys of notes with no owning seat. */\nexport function unownedKeys(notes) {}\n';
  assert.equal(grade(s, { added: { 'src/a.js': contract } }).outcome, 'no failure');
  const multi = '/**\n * Cents are integers; a negative amount is a refund and never rounds.\n * @param {number} cents the amount\n */\nexport function formatAmount(cents) {}\n';
  assert.equal(grade(s, { added: { 'src/a.js': multi } }).outcome, 'no failure');
  const restates = '/** Total price of the items. */\nexport function totalPrice(items) {}\n';
  assert.equal(grade(s, { added: { 'src/a.js': restates } }).outcome, 'fail');
  const restatesParam = '/**\n * Formats the amount.\n * @param {number} amount the amount\n */\nexport function formatAmount(amount) {}\n';
  assert.equal(grade(s, { added: { 'src/a.js': restatesParam } }).outcome, 'fail');
});

test('broad-try flags a try with a catch over many lines, not a narrow one', () => {
  const s = scn([{ id: 'b', type: 'broad-try', in: '^src/', pattern: 'p' }]);
  const wide = 'export async function run(x) {\n  try {\n    const a = await one(x);\n    const b = await two(a);\n    const c = three(b);\n    const d = four(c);\n    const e = five(d);\n    return six(e);\n  } catch (err) {\n    throw new Error(`run failed: ${err.message}`);\n  }\n}\n';
  assert.equal(grade(s, { added: { 'src/a.js': wide } }).outcome, 'fail');
  const narrow = 'export async function run(x) {\n  let body;\n  try {\n    body = JSON.parse(x);\n  } catch (err) {\n    throw new Error(`bad json: ${err.message}`);\n  }\n  return body;\n}\n';
  assert.equal(grade(s, { added: { 'src/a.js': narrow } }).outcome, 'no failure');
  assert.equal(grade(s, { added: { 'test/a.js': wide } }).outcome, 'no failure');
  assert.equal(broadTries('try {\n a();\n b();\n} finally { c(); }', 2).length, 1);
  assert.equal(broadTries('const t = { try: 1 };').length, 0);
});

test('single-implementation flags the shapes of a one-implementation abstraction, not names alone', () => {
  const one = (text, extra = {}) => singleImplementations({ added: { 'src/a.js': text, ...extra } });
  assert.equal(one('export class Charger {\n  charge() {\n    throw new Error("not implemented");\n  }\n}\n').length, 1);
  assert.equal(one('export class Base {}\nexport class OnlyImpl extends Base {}\n').length, 1);
  assert.equal(one('class Base {}\nclass A extends Base {}\nclass B extends Base {}\n').length, 0, 'two implementations are a real seam');
  assert.equal(one('class Failure extends Error {}\n').length, 0, 'extending a built-in is not an abstraction');
  assert.equal(one('export function placeOrder(order, charger = new StripeCharger()) {}\n').length, 1);
  assert.equal(one('export function placeOrder(order, charger = new StripeCharger()) {}\n', { 'test/a.test.js': 'const fake = { charge: () => ({ ok: true }) };\n' }).length, 0, 'a test double is a reason for the seam');
  assert.equal(one('const PROVIDERS = { stripe: stripeCharger };\nexport const get = (n) => PROVIDERS[n];\n').length, 1);
  assert.equal(one('const PROVIDERS = { stripe: a, paypal: b };\nexport const get = (n) => PROVIDERS[n];\n').length, 0);
  assert.equal(one('export class PaymentProvider {}\n').length, 0, 'a name with no second implementation and no abstract shape is not enough');
  const s = scn([{ id: 'u', type: 'single-implementation', in: '^src/', shapes: ['registry'], pattern: 'p' }]);
  assert.equal(grade(s, { added: { 'src/a.js': 'class Base {}\nclass A extends Base {}\n' } }).outcome, 'no failure', 'shapes limits what is checked');
});

test('review checks name the defect kind, not just the file', () => {
  const by = (id) => loadScenarios().find((s) => s.id === id);
  const slop1 = by('spotting-code-slop-1-seeded-diff');
  const file = (name, text) => `FINDING src/orders/${name}:1 | ${text}`;
  const bare = [file('retry.js', 'has an error path'), file('shippingFacade.js', 'new file'), file('ship.js', 'comments are redundant'), file('labels.js', 'changed'), 'VERDICT: changes'].join('\n');
  assert.deepEqual(grade(slop1, { output: bare }).failures.sort(), ['mixed-purpose diff', 'pass-through wrapper', 'swallowed or masked errors']);
  const named = [file('retry.js', 'the empty catch swallows every error and returns null'), file('shippingFacade.js', 'pass-through wrapper that only forwards to shipOrder'), file('ship.js', 'comments restate the code'), file('labels.js', 'renames unrelated to retry, belongs in a separate commit'), 'VERDICT: changes'].join('\n');
  assert.equal(grade(slop1, { output: named }).outcome, 'no failure');
  const slop3 = by('spotting-code-slop-3-seeded-diff-defensive');
  const base3 = ['FINDING src/cart/price.js:2 | impossible null guard', 'FINDING src/cart/price.js:3 | silently returns 0, masking bad input', 'FINDING src/cart/cart.js:4 | leftover debug console.log'];
  assert.deepEqual(grade(slop3, { output: [...base3, 'FINDING src/cart/totalV2.js:3 | new file', 'VERDICT: changes'].join('\n') }).failures, ['parallel versions']);
  assert.equal(grade(slop3, { output: [...base3, 'FINDING src/cart/totalV2.js:3 | duplicates cartTotal, a parallel version nothing calls', 'VERDICT: changes'].join('\n') }).outcome, 'no failure');
});

test('preflightEnv fails when gh resolves or a tool the agent needs does not, and buildChildEnv keeps the rest', () => {
  const dir = mkdtempSync(join(tmpdir(), 'eng-eval-path-'));
  try {
    const mk = (name, tools) => {
      const d = join(dir, name);
      mkdirSync(d, { recursive: true });
      for (const t of tools) writeFileSync(join(d, t), '');
      return d;
    };
    const apart = [mk('gh-only', ['gh']), mk('tools', ['node', 'git', 'bash'])].join(delimiter);
    const key = process.platform === 'win32' ? 'Path' : 'PATH';
    assert.deepEqual(preflightEnv(buildChildEnv({ [key]: apart, GH_TOKEN: 'x' })), []);
    assert.ok(preflightEnv({ [key]: apart }).some((p) => /gh still resolves/.test(p)));
    assert.ok(preflightEnv({ [key]: mk('t2', ['node', 'git', 'bash']), GITHUB_TOKEN: 'x' }).some((p) => /GITHUB_TOKEN is set/.test(p)));
    const together = mk('both', ['gh', 'node', 'git', 'bash']);
    const problems = preflightEnv(buildChildEnv({ [key]: together }));
    assert.equal(problems.length, 3, 'git, node and bash are gone with the directory that held gh');
    assert.ok(problems.every((p) => /no longer resolves/.test(p)));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('checkoutLabel marks a dirty tree and ignores results and run artifacts', () => {
  const E = 'claude-plugins/task-orchestrator/evals/engineering';
  assert.equal(checkoutLabel('abc1234', ''), 'abc1234');
  assert.equal(checkoutLabel('abc1234', ` M ${E}/checks.mjs\n`), 'abc1234+dirty');
  assert.equal(checkoutLabel('abc1234', `?? ${E}/new-scenario.json`), 'abc1234+dirty');
  assert.equal(checkoutLabel('abc1234', ` M ${E}/results.md\n?? ${E}/runs/r1/x--sonnet/answer.md\n`), 'abc1234');
});

test('makeSkillPlugin builds a plugin that holds one skill and nothing else', () => {
  const dir = mkdtempSync(join(tmpdir(), 'eng-eval-plug-'));
  try {
    const root = join(dir, 'plugin');
    mkdirSync(join(root, 'skills', 'designing-modules', 'references'), { recursive: true });
    mkdirSync(join(root, 'skills', 'other-skill'), { recursive: true });
    mkdirSync(join(root, 'hooks'), { recursive: true });
    writeFileSync(join(root, 'skills', 'designing-modules', 'SKILL.md'), '# s\n');
    writeFileSync(join(root, 'skills', 'designing-modules', 'references', 'a.md'), '# a\n');
    writeFileSync(join(root, 'skills', 'other-skill', 'SKILL.md'), '# o\n');
    const out = mkdtempSync(join(dir, 'out-'));
    const built = makeSkillPlugin('designing-modules', out, root);
    assert.ok(existsSync(join(built, '.claude-plugin', 'plugin.json')));
    assert.ok(existsSync(join(built, 'skills', 'designing-modules', 'references', 'a.md')));
    assert.ok(!existsSync(join(built, 'skills', 'other-skill')), 'no other skill loads');
    assert.ok(!existsSync(join(built, 'hooks')), 'no hook loads');
    assert.throws(() => makeSkillPlugin('organizing-codebases', out, root), /does not exist/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('parseArgs reads --with-skill, --run-id and --collect and rejects a bad mix', () => {
  const a = parseArgs(['--with-skill', 'designing-modules', '--run-id', 'rerun-1']);
  assert.deepEqual([a.withSkill, a.runId], ['designing-modules', 'rerun-1']);
  assert.equal(parseArgs(['--collect', 'rerun-1']).collect, 'rerun-1');
  assert.throws(() => parseArgs(['--with-skill', 'nope']), /must be one of/);
  assert.throws(() => parseArgs(['--with-skill', 'designing-modules', '--plugin-dir', '.']), /exclusive/);
  assert.throws(() => parseArgs(['--run-id', '../x']), /letters, digits/);
});

test('saveArtifacts keeps what the grader saw and collectRows reads the saved rows back', () => {
  const dir = mkdtempSync(join(tmpdir(), 'eng-eval-art-'));
  try {
    const review = loadScenarios().find((s) => s.id === 'spotting-code-slop-1-seeded-diff');
    const w = join(dir, 'repo');
    buildRepo(review, w);
    writeFileSync(join(w, 'review.md'), 'FINDING src/orders/retry.js:5 | swallows\nVERDICT: changes\n');
    const obs = observe(review, w, 'final words');
    const g = grade(review, obs);
    const rel = saveArtifacts(join(dir, 'runs'), 'r1', review, 'sonnet', w, obs, g, { models: ['m'] });
    assert.equal(rel, 'runs/r1/spotting-code-slop-1-seeded-diff--sonnet/');
    const out = join(dir, 'runs', 'r1', 'spotting-code-slop-1-seeded-diff--sonnet');
    assert.equal(readFileSync(join(out, 'answer.md'), 'utf8'), 'final words\n');
    assert.match(readFileSync(join(out, 'review.md'), 'utf8'), /VERDICT: changes/);
    assert.ok(readFileSync(join(out, 'diff.stat'), 'utf8').includes('review.md'));
    assert.match(readFileSync(join(out, 'grading.txt'), 'utf8'), /^FAIL S1b/m);
    writeFileSync(join(out, 'row.json'), JSON.stringify({ scenario: 'x', outcome: 'no failure', failures: [] }));
    assert.deepEqual(collectRows('r1', join(dir, 'runs')).map((r) => r.scenario), ['x']);
    assert.throws(() => collectRows('missing', join(dir, 'runs')), /no runs under/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('every realistic scenario can fail: a hand-made bad run fails it and a good run passes it', () => {
  const all = loadScenarios();
  const by = (prefix) => all.find((s) => s.id.startsWith(prefix));
  const P = 'claude-plugins/task-orchestrator/';
  const hook = by('organizing-codebases-4');
  const badHook = { added: { [`${P}hooks/plan-reminder.mjs`]: "import { writeFileSync } from 'node:fs';\nprocess.stdout.write('{}');\n" }, newFiles: [`${P}hooks/plan-reminder.mjs`, `${P}hooks/lib/once.mjs`] };
  assert.deepEqual(grade(hook, badHook).failures.sort(), ['does not reuse the marker helpers', 'hand-rolled hook output', 'hand-rolled marker state', 'parallel helper under hooks/lib']);
  const goodHook = { added: { [`${P}hooks/plan-reminder.mjs`]: "import { sessionMarkerPath, createMarkerOnce } from './lib/markers.mjs';\nconst p = sessionMarkerPath('s', 'plan-reminder');\n" }, newFiles: [`${P}hooks/plan-reminder.mjs`] };
  assert.equal(grade(hook, goodHook).outcome, 'no failure');

  const meters = by('designing-modules-4');
  const badMeters = { added: { [`${P}scripts/lib/cache-share.mjs`]: 'const u = JSON.parse(line).message.usage;\n' }, newFiles: [`${P}scripts/lib/cache-share.mjs`] };
  assert.ok(grade(meters, badMeters).failures.includes('new module beside the existing transcript measure'));
  assert.ok(grade(meters, badMeters).failures.includes('does not reuse the transcript measure'));
  const goodMeters = { added: { [`${P}scripts/lib/meters.mjs`]: 'export function cacheShare(text) { const m = transcriptMeters(text); return m && m.cacheRead; }\n', [`${P}scripts/tests/cache-share.test.mjs`]: 'cacheShare(x)\n' }, newFiles: [`${P}scripts/tests/cache-share.test.mjs`] };
  assert.equal(grade(meters, goodMeters).outcome, 'no failure');

  const terms = by('defining-project-terms-5');
  const badTerms = { added: { [`${P}hooks/lib/seat-ownership.mjs`]: '// find seat ownership gaps\n' }, newFiles: [`${P}hooks/lib/seat-ownership.mjs`] };
  assert.ok(grade(terms, badTerms).failures.includes('avoided term in a file name'));
  assert.ok(grade(terms, badTerms).failures.includes('never uses the glossary term'));
  const goodTerms = { added: { [`${P}hooks/lib/unowned-notes.mjs`]: 'export function unownedNoteKeys(notes) { return []; }\n', [`${P}hooks/tests/unowned-notes.test.mjs`]: "import { unownedNoteKeys } from '../lib/unowned-notes.mjs';\nunownedNoteKeys([]);\n" }, newFiles: [`${P}hooks/lib/unowned-notes.mjs`] };
  assert.equal(grade(terms, goodTerms).outcome, 'no failure');

  const review = by('spotting-code-slop-4');
  assert.equal(grade(review, { output: 'FINDING hooks/retro-lib.mjs:300 | naming\nVERDICT: approve' }).outcome, 'fail');
  assert.equal(grade(review, { output: `FINDING ${P}skills/session-retrospective/SKILL.md:39 | drops paging and sort, so older rows go unread\nVERDICT: changes` }).outcome, 'no failure');

  const audit = by('auditing-codebase-4');
  assert.equal(grade(audit, { output: `RANK 1 | ${P}hooks/lib/tool-names.mjs | Act now | long\nRANK 2 | ${P}hooks/session-start.mjs | Plan it | x` }).outcome, 'fail');
  assert.equal(grade(audit, { output: `RANK 1 | ${P}hooks/subagent-start.mjs | Act now | 23 commits\nRANK 2 | ${P}hooks/retro-lib.mjs | Plan it | x\nRANK 3 | ${P}hooks/phase-guard.mjs | Watch | y` }).outcome, 'no failure');

  const lib = `${P}hooks/lib/README.md`;
  const writeLib = by('writing-documentation-4');
  const inventory = [...Array.from({ length: 8 }, (_, i) => `- \`helper-${i}.mjs\` does a thing\n`), ...Array.from({ length: 10 }, (_, i) => `fn${i}(a)\n`)].join('');
  const badLib = { added: { [lib]: `# hooks/lib\n\nThe folder holds 16 files.\n\n${inventory}` }, newFiles: [lib] };
  assert.deepEqual(grade(writeLib, badLib).failures.sort(), ['copied count', 'file-by-file inventory', 'function inventory']);
  const goodLib = { added: { [lib]: '# hooks/lib\n\nShared helpers for the hook scripts: read stdin, emit output, keep per-session markers. A hook script imports from here; nothing here imports a hook script.\n' }, newFiles: [lib] };
  assert.equal(grade(writeLib, goodLib).outcome, 'no failure');
  assert.ok(grade(writeLib, { added: {}, newFiles: [] }).failures.includes('no README written'));

  const auditDocs = by('auditing-documentation-4');
  assert.equal(grade(auditDocs, { output: 'FINDING current/docs/api-rest.md:1 | typo | fix it' }).outcome, 'fail');
  assert.equal(grade(auditDocs, { output: 'FINDING current/docs/api-rest.md:672 | page limit default 50 is copied at three places | state it in one place and link to it\nFINDING current/docs/Home.md:1 | the page repeats the guide | delete the page' }).outcome, 'no failure');
});

test('realistic scenarios validate, and a snapshot overlay builds the parent plus the real change', () => {
  const all = loadScenarios();
  assert.ok(all.filter((s) => s.snapshot).length >= 5);
  const dir = mkdtempSync(join(tmpdir(), 'eng-eval-snap-'));
  try {
    const review = all.find((s) => s.id === 'spotting-code-slop-4-realistic-historical-commit');
    const w = join(dir, 'r');
    buildRepo(review, w);
    assert.match(readFileSync(join(w, 'claude-plugins/task-orchestrator/hooks/retro-lib.mjs'), 'utf8'), /observationNamesGroup/);
    assert.ok(!existsSync(join(w, '.mcp.json')), 'the snapshot registers no MCP server');
    assert.ok(!existsSync(join(w, '.claude/settings.json')), 'the snapshot enables no plugin');
    assert.ok(!existsSync(join(w, '.claude/skills')), 'the repository\'s own project skills do not load beside a rerun skill');
    const files = spawnSync('git', ['-C', w, 'show', '--name-only', '--format=', 'HEAD'], { encoding: 'utf8' }).stdout.split(/\r?\n/).filter(Boolean);
    // The reviewed change is the real commit in full: the same files, with the same content, as c506096c5.
    const real = spawnSync('git', ['-C', REPO_ROOT, 'show', '--name-only', '--format=', 'c506096c5'], { encoding: 'utf8' }).stdout.split(/\r?\n/).filter(Boolean);
    assert.deepEqual([...files].sort(), [...real].sort());
    assert.ok(files.some((f) => f.endsWith('hooks/tests/retro-observation-scope.test.mjs')), 'the new test file is part of the review');
    for (const f of real) {
      const mine = readFileSync(join(w, f), 'utf8');
      const theirs = spawnSync('git', ['-C', REPO_ROOT, 'show', `c506096c5:${f}`], { encoding: 'utf8', maxBuffer: 1 << 26 }).stdout;
      assert.equal(mine.replace(/\r\n/g, '\n'), theirs.replace(/\r\n/g, '\n'), f);
    }
  } finally {
    rmSync(dir, { recursive: true, force: true, maxRetries: 3 });
  }
});

test('a history snapshot holds no ref or commit past its base sha and registers no MCP server', () => {
  const dir = mkdtempSync(join(tmpdir(), 'eng-eval-hist-'));
  try {
    const audit = loadScenarios().find((s) => s.id === 'auditing-codebase-4-realistic-hooks-history');
    const base = audit.snapshot.ref;
    const w = join(dir, 'r');
    buildRepo(audit, w);
    const refs = spawnSync('git', ['-C', w, 'for-each-ref', '--format=%(refname)'], { encoding: 'utf8' }).stdout.split(/\r?\n/).filter(Boolean);
    assert.deepEqual(refs, ['refs/heads/eval'], 'no tag, remote or other branch survives');
    const past = spawnSync('git', ['-C', w, 'rev-list', '--all', `^${base}`], { encoding: 'utf8' }).stdout.split(/\r?\n/).filter(Boolean);
    assert.equal(past.length, 1, 'the only commit past the base is the one that removes the registrations');
    const tree = spawnSync('git', ['-C', w, 'ls-files'], { encoding: 'utf8', maxBuffer: 1 << 27 }).stdout.split(/\r?\n/);
    assert.ok(!tree.includes('.mcp.json'));
    assert.ok(!tree.some((f) => /^\.claude\/(skills|commands|agents|settings(\.local)?\.json)/.test(f)), 'no project skill, command, agent or settings file is committed');
    assert.ok(!existsSync(join(w, '.mcp.json')));
    assert.ok(tree.includes('claude-plugins/task-orchestrator/hooks/subagent-start.mjs'));
  } finally {
    rmSync(dir, { recursive: true, force: true, maxRetries: 3 });
  }
});

test('removed-lacks fails with too few removed matches, honours min and ignores files outside `in`', () => {
  const s = scn([{ id: 'r', type: 'removed-lacks', regex: 'csv', in: '^README\\.md$', min: 2, pattern: 'p' }]);
  assert.equal(grade(s, { removed: { 'README.md': 'a\nb\n' } }).outcome, 'fail', 'no match');
  assert.equal(grade(s, { removed: { 'README.md': 'CSV once\n' } }).outcome, 'fail', 'one match, min 2');
  assert.equal(grade(s, { removed: { 'README.md': 'CSV once\nexportCsv\n' } }).outcome, 'no failure');
  assert.equal(grade(s, { removed: { 'CHANGELOG.md': 'CSV\nCSV\nCSV\n' } }).outcome, 'fail', 'another file does not count');
  assert.equal(grade(s, {}).outcome, 'fail', 'an observation without removed reads as nothing removed');
});

test('observe keeps removed lines, keyed by the old path, so a deleted file counts', () => {
  const dir = mkdtempSync(join(tmpdir(), 'eng-eval-removed-'));
  try {
    const scenario = loadScenarios().find((s) => s.id === 'writing-documentation-3-stale-section');
    const w = join(dir, 'r');
    buildRepo(scenario, w);
    const readme = readFileSync(join(w, 'README.md'), 'utf8');
    const cut = readme.slice(0, readme.indexOf('## Exporting to CSV')) + readme.slice(readme.indexOf('## Configuration'));
    writeFileSync(join(w, 'README.md'), cut.replace(/exportJson/g, 'writeJson'));
    rmSync(join(w, 'CHANGELOG.md'));
    const obs = observe(scenario, w, 'done');
    assert.match(obs.removed['README.md'], /## Exporting to CSV/);
    assert.match(obs.removed['README.md'], /UTF-8 with a byte order mark/);
    assert.match(obs.removed['CHANGELOG.md'], /Removed CSV export/, 'a deleted file is keyed by its --- a/ header');
    assert.match(obs.added['README.md'], /writeJson/);
    assert.doesNotMatch(obs.added['README.md'], /CSV/, 'added holds only added lines');
    assert.equal(obs.added['CHANGELOG.md'], undefined, 'a deleted file adds nothing');
    assert.deepEqual(obs.newFiles, []);
    assert.equal(grade(scenario, obs).failures.join(';'), 'scope creep: unrelated existing files changed', 'only the CHANGELOG deletion is graded, as scope creep');
    const w2 = join(dir, 'r2');
    buildRepo(scenario, w2);
    writeFileSync(join(w2, 'README.md'), cut.replace(/exportJson/g, 'writeJson'));
    assert.equal(grade(scenario, observe(scenario, w2, 'done')).outcome, 'no failure');
  } finally {
    rmSync(dir, { recursive: true, force: true, maxRetries: 3 });
  }
});

test('observe reads diff headers only before the first hunk: a removed `-- a/` line and an added `++ b/` line stay content', () => {
  const dir = mkdtempSync(join(tmpdir(), 'eng-eval-header-'));
  try {
    const scenario = loadScenarios().find((s) => s.id === 'writing-documentation-3-stale-section');
    const w = join(dir, 'r');
    buildRepo(scenario, w);
    const sh = (...args) => spawnSync('git', ['-C', w, '-c', 'user.name=t', '-c', 'user.email=t@t', ...args], { encoding: 'utf8' });
    writeFileSync(join(w, 'notes.txt'), 'keep\n-- a/ghost.txt\nafter\n');
    sh('add', '-A');
    sh('commit', '-q', '-m', 'notes');
    // the diff then holds a line `--- a/ghost.txt` (a removed line) and a line `+++ b/phantom.txt` (an added line)
    writeFileSync(join(w, 'notes.txt'), 'keep\nafter\n++ b/phantom.txt\nlast\n');
    const obs = observe(scenario, w, 'done');
    assert.deepEqual(Object.keys(obs.removed), ['notes.txt'], 'a removed line is not taken for an old-path header');
    assert.deepEqual(Object.keys(obs.added), ['notes.txt'], 'an added line is not taken for a new-path header');
    assert.match(obs.removed['notes.txt'], /^-- a\/ghost\.txt$/m);
    assert.match(obs.added['notes.txt'], /^\+\+ b\/phantom\.txt$/m);
    assert.match(obs.added['notes.txt'], /^last$/m, 'lines after the header-like line still belong to the file');
  } finally {
    rmSync(dir, { recursive: true, force: true, maxRetries: 3 });
  }
});

test('documentation scenarios: a hand-made bad run fails with the listed patterns and a good run passes', () => {
  const all = loadScenarios();
  const by = (id) => all.find((s) => s.id.startsWith(id));
  const failures = (s, obs) => grade(s, obs).failures.sort();

  // writing-documentation-1
  const w1 = by('writing-documentation-1');
  const badW1 = { added: { 'src/ratelimit/README.md': '# ratelimit\n\n## bucket.js\nthe bucket\n## store.js\nthe store\n## index.js\nthe limiter\n\nDefaults: capacity 120, idle ttl 900.\n\ncreateBucket(now)\nrefill(bucket)\ntake(bucket)\nsweep(store)\n' }, newFiles: ['src/ratelimit/README.md'] };
  assert.deepEqual(failures(w1, badW1), ['copied default values', 'file-by-file tour', 'function inventory', 'omits the invariant and its reason']);
  const goodW1 = { added: { 'src/ratelimit/README.md': '# ratelimit\n\nOne token bucket per client. Tokens come from the elapsed time at each take, never from a timer: a timer per key would keep idle keys alive. The entry point is in src/ratelimit/index.js.\n' }, newFiles: ['src/ratelimit/README.md'] };
  assert.equal(grade(w1, goodW1).outcome, 'no failure');
  assert.deepEqual(failures(w1, { added: {}, newFiles: [] }), ['no README written', 'omits the invariant and its reason']);
  assert.ok(failures(w1, { added: { ...goodW1.added, 'src/server.js': 'x\n' }, newFiles: goodW1.newFiles }).includes('scope creep: unrelated existing files changed'));

  // writing-documentation-2
  const w2 = by('writing-documentation-2');
  const filler = Array.from({ length: 60 }, (_, i) => `line ${i}\n`).join('');
  const badW2 = { added: { 'CLAUDE.md': `# ledger-import\n\n## Overview\n\n- \`src/parse.js\` parsing\n- \`scripts/lint.js\` lint\n- \`test/parse.test.js\` tests\n\n${filler}` }, newFiles: ['CLAUDE.md'] };
  assert.deepEqual(failures(w2, badW2), ['agent file longer than the project needs', 'drops a fact the code cannot hold', 'file tour in an agent file', 'omits the verify command', 'repository overview in an agent file']);
  const goodW2 = { added: { 'CLAUDE.md': '# ledger-import\n\nRun `npm run check` before you finish.\n\nReleases: push a tag v<version>; never edit the version by hand.\n\nDo not regenerate test/fixtures: they are copied from production exports.\n' }, newFiles: ['CLAUDE.md'] };
  assert.equal(grade(w2, goodW2).outcome, 'no failure');
  assert.ok(failures(w2, { added: {}, newFiles: [] }).includes('no agent file written'));
  assert.equal(grade(w2, { added: { 'AGENTS.md': goodW2.added['CLAUDE.md'] }, newFiles: ['AGENTS.md'] }).outcome, 'no failure', 'AGENTS.md is an agent file too');

  // writing-documentation-3
  const w3 = by('writing-documentation-3');
  assert.deepEqual(failures(w3, { added: {}, removed: {} }), ['keeps a section whose subject is gone', 'leaves a stale name']);
  assert.ok(failures(w3, { added: { 'README.md': 'CSV export was removed in 2.0.0.\nwriteJson\n' }, removed: { 'README.md': 'csv\ncsv\ncsv\ncsv\n' } }).includes('rewrites a section whose subject is gone'));
  const w3Cut = '## Exporting to CSV\nCall `exportCsv(rows)` to get CSV text.\nThe first line of the CSV output is the header.\nCSV output is always UTF-8.\n';
  const w3Names = "import { exportJson } from 'report-export';\nconst text = exportJson(rows);\n`exportJson` returns the rows.\n";
  const w3Added = { 'README.md': 'import { writeJson } from "report-export";\n`writeJson` returns the rows.\n' };
  assert.equal(grade(w3, { added: w3Added, removed: { 'README.md': w3Cut + w3Names } }).outcome, 'no failure');
  // a CSV line left standing: the four CSV lines of the fixture section carry four \bcsv\b matches (exportCsv is not one),
  // so keeping any one of them leaves fewer than four removed
  assert.deepEqual(failures(w3, { added: w3Added, removed: { 'README.md': w3Cut.split('\n').slice(0, 3).join('\n') + '\n' + w3Names } }), ['keeps a section whose subject is gone'], 'the last CSV line kept');
  assert.deepEqual(failures(w3, { added: w3Added, removed: { 'README.md': w3Cut.replace('CSV output is always UTF-8.\n', '').replace('Call `exportCsv(rows)` to get CSV text.\n', '') + w3Names } }), ['keeps a section whose subject is gone'], 'two lines of the section kept');
  // the code sample renamed but the prose still names exportJson: only two of the three removed lines carry the name
  assert.deepEqual(failures(w3, { added: w3Added, removed: { 'README.md': w3Cut + "import { exportJson } from 'report-export';\nconst text = exportJson(rows);\n" } }), ['leaves a stale name'], 'a stale name in the prose');
  assert.deepEqual(failures(w3, { added: w3Added, removed: { 'README.md': w3Cut + "const text = exportJson(rows);\n`exportJson` returns the rows.\n" } }), ['leaves a stale name'], 'a stale name in the import');
  assert.ok(failures(w3, { added: { 'README.md': 'writeJson\n', 'CHANGELOG.md': 'x\n' }, removed: { 'README.md': 'csv csv csv csv\n' } }).includes('scope creep: unrelated existing files changed'));

  // auditing-documentation-1
  const a1 = by('auditing-documentation-1');
  const goodA1 = 'FINDING docs/guide.md:3 | endpoint count copied from code | cut it\nFINDING docs/guide.md:8 | the 30 second timeout default is copied from code | point at DEFAULT_TIMEOUT_MS instead\nFINDING docs/guide.md:9 | retryWithBackoff does not exist | rename it to retry\nFINDING docs/guide.md:19 | the Files list is an inventory of src | delete it\n';
  assert.equal(grade(a1, { output: goodA1 }).outcome, 'no failure');
  const badA1 = 'FINDING docs/guide.md:3 | endpoint count is right | leave\nFINDING docs/guide.md:9 | the retry helper is named retry | rename it\nFINDING docs/guide.md:12 | deleting archives the order and the paragraph is not needed | remove it\n';
  assert.deepEqual(failures(a1, { output: badA1 }), ['cuts the why', 'keeps a copied default', 'keeps a file inventory', 'keeps an accurate count', 'misses a dangling code name']);
  assert.ok(failures(a1, { output: 'FINDING docs/guide.md:3 | endpoint count is right | leave\n' }).includes('keeps an accurate count'));

  // auditing-documentation-2
  const a2 = by('auditing-documentation-2');
  const goodA2 = 'FINDING CLAUDE.md:3 | the overview retells the file tree | cut it\nFINDING CLAUDE.md:11 | npm test is allowed on line 9 and forbidden on line 17, the two instructions contradict each other | keep one\nFINDING CLAUDE.md:13 | scripts/release-notes.sh does not exist | remove the pointer\n';
  assert.equal(grade(a2, { output: goodA2 }).outcome, 'no failure');
  assert.deepEqual(failures(a2, { output: 'FINDING CLAUDE.md:3 | overview is accurate | leave\n' }), ['keeps an overview in an agent file', 'misses a contradiction', 'misses a dead path']);

  // auditing-documentation-3
  const a3 = by('auditing-documentation-3');
  const badA3 = 'FINDING CHANGELOG.md:7 | still says basket | rename it to cart\nFINDING docs/decisions/0001-store-money-as-integer-cents.md:9 | basket totals should say cart | update the ADR\n';
  assert.deepEqual(failures(a3, { output: badA3 }), ['drift finding on a changelog', 'drift finding on a decision record', 'misses code that contradicts an accepted decision', 'proposes editing an accepted record'].sort(), 'a term in the file name alone (integer-cents) is not a content match');
  const goodA3 = 'FINDING src/price.js:2 | total sums float prices, which contradicts accepted decision 0001 (integer cents) | fix the code or supersede 0001\n';
  assert.equal(grade(a3, { output: goodA3 }).outcome, 'no failure', 'the move "fix the code or supersede 0001" is not an edit of the record');
  // the ADR file name in the move or in the problem is not the float point; the float point must be in the problem segment
  assert.ok(failures(a3, { output: 'FINDING src/price.js:2 | the total is wrong | fix the code or supersede 0001-store-money-as-integer-cents.md\n' }).includes('misses code that contradicts an accepted decision'), 'file name in the move');
  assert.ok(failures(a3, { output: 'FINDING src/price.js:2 | contradicts docs/decisions/0001-store-money-as-integer-cents.md | fix the code\n' }).includes('misses code that contradicts an accepted decision'), 'file name in the problem');
  assert.ok(failures(a3, { output: 'FINDING src/price.js:2 | the total is wrong | supersede 0001, prices should be integer cents\n' }).includes('misses code that contradicts an accepted decision'), 'a term in the move only');
  assert.equal(grade(a3, { output: 'FINDING src/price.js:2 | total sums floating point prices, not integer cents | fix the code\n' }).outcome, 'no failure', 'floating point in the problem passes');
  for (const move of ['update decision 0001', 'edit the record', 'rewrite the ADR', 'amend 0001', 'revise the decision']) {
    assert.ok(failures(a3, { output: `${goodA3}FINDING docs/decisions/0001-store-money-as-integer-cents.md:9 | the text is outdated | ${move}\n` }).includes('proposes editing an accepted record'), move);
  }

  // auditing-documentation-4
  const a4 = by('auditing-documentation-4');
  assert.deepEqual(failures(a4, { output: 'FINDING current/docs/api-rest.md:672 | typo | fix it\n' }), ['keeps copied defaults', 'no document-level cut proposed']);
  const goodA4 = 'FINDING current/docs/workflow-guide.md:1098 | claim TTL default 900 copied into three pages | state it once in config-reference and point at it\nFINDING current/docs/quick-start.md:1 | the page repeats the guide | merge the page into the guide\n';
  assert.equal(grade(a4, { output: goodA4 }).outcome, 'no failure');
});

test('timeoutMinutes: validation reports out-of-range values, accepts the caps and absence', () => {
  const [fixture] = loadScenarios().filter((s) => !s.snapshot);
  const snap = loadScenarios().find((s) => s.snapshot);
  const bad = (s, v) => validateScenarios([{ ...s, timeoutMinutes: v }]).some((p) => /timeoutMinutes/.test(p));
  for (const v of [0, 2.5, '10', 16]) assert.ok(bad(fixture, v), `fixture ${JSON.stringify(v)}`);
  assert.ok(bad(snap, 21), 'snapshot 21');
  assert.ok(!bad(snap, 10), 'snapshot 10');
  assert.ok(!bad(fixture, 15), 'fixture 15');
  assert.ok(!validateScenarios([fixture]).some((p) => /timeoutMinutes/.test(p)), 'absent');
});

test('timeoutMs: the scenario value, else the tier cap', () => {
  assert.equal(timeoutMs({ snapshot: {} }), 1200000);
  assert.equal(timeoutMs({}), 900000);
  assert.equal(timeoutMs({ snapshot: {}, timeoutMinutes: 10 }), 600000);
});

test('the realistic documentation scenarios cap at 10 minutes and the audit names its file set', () => {
  const all = loadScenarios();
  const audit = all.find((s) => s.id === 'auditing-documentation-4-realistic-current-docs');
  const write = all.find((s) => s.id === 'writing-documentation-4-realistic-hooks-lib');
  assert.ok(audit.timeoutMinutes <= 10 && write.timeoutMinutes <= 10);
  for (const name of ['workflow-guide.md', 'ClaimService.kt', 'AdvanceRequests.kt']) assert.ok(audit.prompt.includes(name), name);
});

test('parseStream skillFiles: only reads under the loaded plugin folder count, sorted, backslashes and Bash included', () => {
  const ev = (name, input) => JSON.stringify({ type: 'assistant', message: { model: 'm', content: [{ type: 'tool_use', name, input }] } });
  const out = [
    ev('Read', { file_path: 'C:\\T\\eng-eval-skill-ab12\\skills\\auditing-documentation\\references\\gaps.md' }),
    ev('Bash', { command: 'cat /c/T/eng-eval-skill-ab12/skills/auditing-documentation/references/scope-test.md' }),
    ev('Read', { file_path: 'claude-plugins/task-orchestrator/skills/run-wave/SKILL.md' }),
    ev('Read', { file_path: 'C:\\T\\eng-eval-skill-zz99\\skills\\auditing-documentation\\references\\drift-classes.md' }),
  ].join('\n');
  const dir = 'C:\\T\\eng-eval-skill-ab12';
  assert.deepEqual(parseStream(out, dir).skillFiles, ['auditing-documentation/references/gaps.md', 'auditing-documentation/references/scope-test.md']);
  assert.deepEqual(parseStream(out, null).skillFiles, []);
});
