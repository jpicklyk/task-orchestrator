import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import {
  mkdtempSync, mkdirSync, writeFileSync, readFileSync, cpSync, rmSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import {
  TIER_NAMES, tierPolicy, mergeTierOverride, loadTierOverride, renderTierBlock,
  loadPolicy, forClass, classNames,
} from '../lib/dispatch-policy.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const PLUGIN = resolve(here, '..', '..');
const REPO = resolve(PLUGIN, '..', '..');
const CLI = join(PLUGIN, 'scripts', 'tier-policy.mjs');
const GENERATE = join(PLUGIN, '_fragments', 'generate.mjs');
const FRAGMENT = join(PLUGIN, '_fragments', 'tier-classification.md');
const POLICY_JSON = join(PLUGIN, 'dispatch-policy.json');

const POINTER =
  'Thresholds and signals are data: `tiers` in the plugin\'s `dispatch-policy.json`. '
  + 'When `.taskorchestrator/tier-policy.json` exists beside the project config, '
  + 'the table in force is the output of `node <plugin root>/scripts/tier-policy.mjs`.';

const tmpDirs = [];
function tmp(prefix = 'tier-policy-') {
  const d = mkdtempSync(join(tmpdir(), prefix));
  tmpDirs.push(d);
  return d;
}
process.on('exit', () => {
  for (const d of tmpDirs) {
    try { rmSync(d, { recursive: true, force: true }); } catch { /* best effort */ }
  }
});

const norm = (s) => s.replace(/\r\n/g, '\n');
const clone = (o) => JSON.parse(JSON.stringify(o));
const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

function baseTiers() {
  return clone(tierPolicy());
}

function section(block, heading) {
  const lines = block.split('\n');
  const i = lines.indexOf(heading);
  assert.ok(i >= 0, `heading present: ${heading}`);
  const out = [];
  for (let j = i + 1; j < lines.length && lines[j] !== ''; j++) out.push(lines[j]);
  return out;
}
const UP = '**Force-UP signals** (bump tier regardless of file count):';
const DOWN = '**Force-DOWN signals:**';

function cliEnv(home, ceiling) {
  const env = { ...process.env };
  delete env.AGENT_CONFIG_DIR;
  env.TASK_ORCHESTRATOR_HOME = home;
  env.TASK_ORCHESTRATOR_CEILING = ceiling;
  return env;
}

function makeProject({ override } = {}) {
  const root = tmp('tier-proj-');
  const home = join(root, 'home');
  mkdirSync(home);
  const project = join(root, 'proj');
  mkdirSync(join(project, '.taskorchestrator'), { recursive: true });
  writeFileSync(
    join(project, '.taskorchestrator', 'config.yaml'),
    'project:\n  rootId: 0f4099f8-9861-45a7-ad58-e29aad2651fd\n',
  );
  if (override !== undefined) {
    writeFileSync(
      join(project, '.taskorchestrator', 'tier-policy.json'),
      typeof override === 'string' ? override : JSON.stringify(override),
    );
  }
  return { root, home, project };
}

function runCli(args, { cwd, home, ceiling }) {
  const r = spawnSync(process.execPath, [CLI, ...args], {
    cwd, env: cliEnv(home, ceiling), encoding: 'utf8',
  });
  return { status: r.status, stdout: r.stdout, stderr: r.stderr };
}

describe('S1 default render', () => {
  test('equals the generated fragment file and the spec-derived text', () => {
    const out = renderTierBlock(tierPolicy());
    assert.equal(out, norm(readFileSync(FRAGMENT, 'utf8')));
    const expected = [
      '| Criteria | Tier | Pipeline |',
      '|----------|------|----------|',
      '| 1-2 files, known fix, no migration/new API | **Direct** | Orchestrator edits, tests, reviews inline |',
      '| 3-10 files, single logical unit, clear or explorable scope | **Delegated** | Single subagent, separate review agent |',
      '| 11+ files, multiple independent work streams, dependency edges | **Parallel** | Worktree agents, full pipeline |',
      '',
      UP,
      '- Database migration -> min Delegated',
      '- New public API surface -> min Delegated',
      '- Multiple independent work streams -> Parallel',
      '- User says "let\'s plan" / collaborative language -> min Delegated',
      '',
      DOWN,
      '- User says "just fix it" / "quick" -> Direct (unless complexity contradicts)',
      '- The item\'s type resolves to the `default` schema, or the item is schema-free -> eligible for Direct',
      '',
      POINTER,
      '',
    ].join('\n');
    assert.equal(out, expected);
  });

  test('ASCII only, LF only, single trailing newline, pointer last', () => {
    const out = renderTierBlock(tierPolicy());
    assert.ok(!out.includes(String.fromCharCode(0x2192)));
    assert.ok(!out.includes('\r'));
    assert.match(out, /[^\n]\n$/);
    assert.equal(out.split('\n').slice(-2, -1)[0], POINTER);
    // eslint-disable-next-line no-control-regex
    assert.ok(/^[\x0a\x20-\x7e]*$/.test(out), 'printable ASCII only');
  });

  test('TIER_NAMES is the fixed, frozen, exact-case list', () => {
    assert.deepEqual([...TIER_NAMES], ['Direct', 'Delegated', 'Parallel']);
    assert.ok(Object.isFrozen(TIER_NAMES));
  });

  test('render is idempotent and does not mutate its input', () => {
    const t = baseTiers();
    const before = clone(t);
    const a = renderTierBlock(t);
    const b = renderTierBlock(t);
    assert.equal(a, b);
    assert.deepEqual(t, before);
  });
});

describe('S2 thresholds override', () => {
  test('{4,20} shifts the row ranges only', () => {
    const merged = mergeTierOverride(baseTiers(), {
      thresholds: { directMaxFiles: 4, delegatedMaxFiles: 20 },
    });
    const out = renderTierBlock(merged);
    const rows = out.split('\n').filter((l) => l.startsWith('| ') && l.includes('**'));
    assert.equal(rows.length, 3);
    assert.ok(rows[0].startsWith('| 1-4 files, known fix'));
    assert.ok(rows[1].startsWith('| 5-20 files, single logical unit'));
    assert.ok(rows[2].startsWith('| 21+ files, multiple independent'));
    const def = renderTierBlock(tierPolicy()).split('\n');
    const got = out.split('\n');
    const rest = (ls) => ls.filter((l) => !(l.startsWith('| ') && l.includes('**')));
    assert.deepEqual(rest(got), rest(def));
  });

  test('a single field merges against the default of the other', () => {
    const merged = mergeTierOverride(baseTiers(), { thresholds: { directMaxFiles: 3 } });
    assert.equal(merged.thresholds.directMaxFiles, 3);
    assert.equal(merged.thresholds.delegatedMaxFiles, 10);
    const out = renderTierBlock(merged);
    assert.match(out, /\| 1-3 files,/);
    assert.match(out, /\| 4-10 files,/);
    assert.match(out, /\| 11\+ files,/);
  });
});

describe('S3 minimal thresholds', () => {
  test('{1,2} uses the singular and a 3+ tail', () => {
    const merged = mergeTierOverride(baseTiers(), {
      thresholds: { directMaxFiles: 1, delegatedMaxFiles: 2 },
    });
    const rows = renderTierBlock(merged).split('\n').filter((l) => l.startsWith('| ') && l.includes('**'));
    assert.ok(rows[0].startsWith('| 1 file, '), rows[0]);
    assert.ok(rows[1].startsWith('| 2 files, '), rows[1]);
    assert.ok(rows[2].startsWith('| 3+ files, '), rows[2]);
  });
});

describe('S4 row override', () => {
  test('pipeline-only override keeps criteria, other rows unchanged', () => {
    const base = baseTiers();
    const merged = mergeTierOverride(base, { rows: { Delegated: { pipeline: 'X' } } });
    assert.equal(merged.rows.Delegated.criteria, base.rows.Delegated.criteria);
    assert.equal(merged.rows.Delegated.pipeline, 'X');
    assert.deepEqual(merged.rows.Direct, base.rows.Direct);
    assert.deepEqual(merged.rows.Parallel, base.rows.Parallel);
    const row = renderTierBlock(merged).split('\n').find((l) => l.includes('**Delegated**'));
    assert.equal(row, '| 3-10 files, single logical unit, clear or explorable scope | **Delegated** | X |');
  });

  test('criteria-only override keeps pipeline', () => {
    const base = baseTiers();
    const merged = mergeTierOverride(base, { rows: { Parallel: { criteria: 'many streams' } } });
    const row = renderTierBlock(merged).split('\n').find((l) => l.includes('**Parallel**'));
    assert.equal(row, '| 11+ files, many streams | **Parallel** | Worktree agents, full pipeline |');
  });
});

describe('S5 force signals override', () => {
  test('forceUp replaces the whole list; forceDown default untouched', () => {
    const base = baseTiers();
    const merged = mergeTierOverride(base, {
      forceUp: [{ when: 'CI config', bound: 'min', tier: 'Delegated' }],
    });
    const out = renderTierBlock(merged);
    assert.deepEqual(section(out, UP), ['- CI config -> min Delegated']);
    assert.deepEqual(section(out, DOWN), section(renderTierBlock(tierPolicy()), DOWN));
  });

  test('empty list renders heading and "- none"; absent keeps default', () => {
    const emptied = renderTierBlock(mergeTierOverride(baseTiers(), { forceDown: [] }));
    assert.deepEqual(section(emptied, DOWN), ['- none']);
    assert.ok(section(emptied, UP).length === 4);
    const kept = renderTierBlock(mergeTierOverride(baseTiers(), {}));
    assert.equal(kept, renderTierBlock(tierPolicy()));
  });

  test('bounds render as exact, min, max, eligible; note is parenthesised', () => {
    const merged = mergeTierOverride(baseTiers(), {
      forceUp: [
        { when: 'A', bound: 'min', tier: 'Parallel' },
        { when: 'B', bound: 'exact', tier: 'Delegated', note: 'n1' },
      ],
      forceDown: [
        { when: 'C', bound: 'max', tier: 'Direct' },
        { when: 'D', bound: 'eligible', tier: 'Delegated', note: 'n2' },
        { when: 'E', bound: 'exact', tier: 'Direct' },
      ],
    });
    const out = renderTierBlock(merged);
    assert.deepEqual(section(out, UP), ['- A -> min Parallel', '- B -> Delegated (n1)']);
    assert.deepEqual(section(out, DOWN), [
      '- C -> max Direct', '- D -> eligible for Delegated (n2)', '- E -> Direct',
    ]);
  });
});

describe('S6 validation failures', () => {
  const SRC = 'src-under-test';
  const cases = [
    ['thresholds {10,10}', { thresholds: { directMaxFiles: 10, delegatedMaxFiles: 10 } }, 'thresholds'],
    ['thresholds direct above delegated', { thresholds: { directMaxFiles: 11, delegatedMaxFiles: 10 } }, 'thresholds'],
    ['direct alone meets default delegated', { thresholds: { directMaxFiles: 10 } }, 'thresholds'],
    ['directMaxFiles 0', { thresholds: { directMaxFiles: 0 } }, 'thresholds.directMaxFiles'],
    ['directMaxFiles negative', { thresholds: { directMaxFiles: -1 } }, 'thresholds.directMaxFiles'],
    ['directMaxFiles 2.5', { thresholds: { directMaxFiles: 2.5 } }, 'thresholds.directMaxFiles'],
    ['directMaxFiles "2"', { thresholds: { directMaxFiles: '2' } }, 'thresholds.directMaxFiles'],
    ['directMaxFiles null', { thresholds: { directMaxFiles: null } }, 'thresholds.directMaxFiles'],
    ['delegatedMaxFiles 0', { thresholds: { delegatedMaxFiles: 0 } }, 'thresholds.delegatedMaxFiles'],
    ['unknown tier Huge', { rows: { Huge: { criteria: 'x' } } }, 'rows.Huge'],
    ['lowercase tier direct', { rows: { direct: { criteria: 'x' } } }, 'rows.direct'],
    ['rows.toString', { rows: JSON.parse('{"toString":{"criteria":"x"}}') }, 'rows.toString'],
    ['rows.__proto__ (JSON-parsed)', JSON.parse('{"rows":{"__proto__":{"criteria":"x"}}}'), 'rows.__proto__'],
    ['criteria with pipe', { rows: { Direct: { criteria: 'a | b' } } }, 'rows.Direct.criteria'],
    ['criteria with newline', { rows: { Direct: { criteria: 'a\nb' } } }, 'rows.Direct.criteria'],
    ['criteria empty', { rows: { Direct: { criteria: '' } } }, 'rows.Direct.criteria'],
    ['pipeline with pipe', { rows: { Parallel: { pipeline: 'a|b' } } }, 'rows.Parallel.pipeline'],
    ['pipeline with newline', { rows: { Delegated: { pipeline: 'a\nb' } } }, 'rows.Delegated.pipeline'],
    ['row unknown key', { rows: { Direct: { foo: 'x' } } }, 'foo'],
    ['thresholds unknown key', { thresholds: { foo: 3 } }, 'foo'],
    ['forceUp bound eligible', { forceUp: [{ when: 'w', bound: 'eligible', tier: 'Direct' }] }, 'forceUp[0].bound'],
    ['forceUp bound max', { forceUp: [{ when: 'w', bound: 'max', tier: 'Direct' }] }, 'forceUp[0].bound'],
    ['forceDown bound min', { forceDown: [{ when: 'w', bound: 'min', tier: 'Direct' }] }, 'forceDown[0].bound'],
    ['forceDown[1].tier Medium', {
      forceDown: [
        { when: 'a', bound: 'exact', tier: 'Direct' },
        { when: 'b', bound: 'exact', tier: 'Medium' },
      ],
    }, 'forceDown[1].tier'],
    ['forceUp tier lowercase', { forceUp: [{ when: 'w', bound: 'min', tier: 'direct' }] }, 'forceUp[0].tier'],
    ['forceUp when empty', { forceUp: [{ when: '', bound: 'min', tier: 'Direct' }] }, 'forceUp[0].when'],
    ['forceUp when newline', { forceUp: [{ when: 'a\nb', bound: 'min', tier: 'Direct' }] }, 'forceUp[0].when'],
    ['forceDown note newline', { forceDown: [{ when: 'w', bound: 'exact', tier: 'Direct', note: 'a\nb' }] }, 'forceDown[0].note'],
    ['forceUp null', { forceUp: null }, 'forceUp'],
    ['forceDown not an array', { forceDown: { when: 'x' } }, 'forceDown'],
    ['forceUp string', { forceUp: 'x' }, 'forceUp'],
    ['top-level classes', { classes: {} }, 'classes'],
    ['entry unknown key', { forceUp: [{ when: 'w', bound: 'min', tier: 'Direct', foo: 1 }] }, 'foo'],
  ];

  for (const [name, override, path] of cases) {
    test(`rejects ${name}`, () => {
      const base = baseTiers();
      const baseBefore = clone(base);
      const ovBefore = clone(override);
      assert.throws(
        () => mergeTierOverride(base, override, SRC),
        (e) => e instanceof Error
          && new RegExp(`^tier policy ${esc(SRC)}: .*${esc(path)}`).test(e.message),
      );
      assert.deepEqual(base, baseBefore, 'base untouched');
      assert.deepEqual(clone(override), ovBefore, 'override untouched');
    });
  }

  test('default source label is "override"', () => {
    assert.throws(
      () => mergeTierOverride(baseTiers(), { classes: {} }),
      (e) => e instanceof Error && /^tier policy override: /.test(e.message),
    );
  });

  test('inputs are deep-equal after a successful merge, and the result is a new object', () => {
    const base = baseTiers();
    const before = clone(base);
    const override = {
      thresholds: { directMaxFiles: 5 },
      rows: { Direct: { pipeline: 'p' } },
      forceUp: [{ when: 'w', bound: 'min', tier: 'Delegated' }],
    };
    const ovBefore = clone(override);
    const merged = mergeTierOverride(base, override);
    assert.notEqual(merged, base);
    assert.deepEqual(base, before);
    assert.deepEqual(override, ovBefore);
    merged.thresholds.directMaxFiles = 99;
    merged.forceUp.push({ when: 'z', bound: 'min', tier: 'Direct' });
    assert.deepEqual(base, before, 'mutating the result never reaches the base');
    assert.deepEqual(clone(tierPolicy()), before, 'cached default is not reachable through a merge');
  });

  test('merging the same override twice is idempotent', () => {
    const override = { thresholds: { directMaxFiles: 4 }, forceDown: [] };
    const once = mergeTierOverride(baseTiers(), override);
    const twice = mergeTierOverride(once, override);
    assert.deepEqual(twice, once);
    assert.deepEqual(mergeTierOverride(baseTiers(), {}), baseTiers());
  });
});

describe('S7 loadTierOverride', () => {
  test('absent file -> null', () => {
    const d = tmp();
    assert.equal(loadTierOverride(join(d, 'nope.json')), null);
  });

  test('invalid JSON -> not valid JSON', () => {
    const d = tmp();
    const f = join(d, 'tier-policy.json');
    writeFileSync(f, '{');
    assert.throws(() => loadTierOverride(f), /not valid JSON/);
  });

  test('array and scalar -> not an object', () => {
    const d = tmp();
    for (const [name, body] of [['arr.json', '[]'], ['num.json', '3'], ['null.json', 'null'], ['str.json', '"x"']]) {
      const f = join(d, name);
      writeFileSync(f, body);
      assert.throws(() => loadTierOverride(f), /not an object/, name);
    }
  });

  test('an empty file is an error, not null', () => {
    const d = tmp();
    const f = join(d, 'empty.json');
    writeFileSync(f, '');
    assert.throws(() => loadTierOverride(f), /not valid JSON/);
  });

  test('valid object round-trips', () => {
    const d = tmp();
    const f = join(d, 'ok.json');
    writeFileSync(f, '{"thresholds":{"directMaxFiles":3}}');
    assert.deepEqual(loadTierOverride(f), { thresholds: { directMaxFiles: 3 } });
  });
});

describe('S8 CLI render', () => {
  test('no override file -> stdout equals the fragment, exit 0', () => {
    const p = makeProject();
    const r = runCli([], { cwd: p.project, home: p.home, ceiling: p.root });
    assert.equal(r.status, 0, r.stderr);
    assert.equal(r.stdout, norm(readFileSync(FRAGMENT, 'utf8')));
  });

  test('located override beside the config changes the thresholds', () => {
    const p = makeProject({ override: { thresholds: { directMaxFiles: 3 } } });
    const r = runCli([], { cwd: p.project, home: p.home, ceiling: p.root });
    assert.equal(r.status, 0, r.stderr);
    assert.match(r.stdout, /\| 1-3 files,/);
    assert.match(r.stdout, /\| 4-10 files,/);
    assert.equal(r.stdout, renderTierBlock(mergeTierOverride(baseTiers(), { thresholds: { directMaxFiles: 3 } })));
  });

  test('--override file wins and is applied', () => {
    const p = makeProject();
    const f = join(p.root, 'custom.json');
    writeFileSync(f, JSON.stringify({ forceDown: [] }));
    const r = runCli(['--override', f], { cwd: p.project, home: p.home, ceiling: p.root });
    assert.equal(r.status, 0, r.stderr);
    assert.deepEqual(section(r.stdout, DOWN), ['- none']);
  });
});

describe('S9 CLI failures', () => {
  test('invalid override via --override: exit 1, stderr names file and field, stdout empty', () => {
    const p = makeProject();
    const f = join(p.root, 'bad-override.json');
    writeFileSync(f, JSON.stringify({ thresholds: { directMaxFiles: 0 } }));
    const r = runCli(['--override', f], { cwd: p.project, home: p.home, ceiling: p.root });
    assert.equal(r.status, 1);
    assert.equal(r.stdout, '');
    assert.ok(r.stderr.includes('bad-override.json'), r.stderr);
    assert.ok(r.stderr.includes('thresholds.directMaxFiles'), r.stderr);
  });

  test('invalid located override fails the same way', () => {
    const p = makeProject({ override: { classes: {} } });
    const r = runCli([], { cwd: p.project, home: p.home, ceiling: p.root });
    assert.equal(r.status, 1);
    assert.equal(r.stdout, '');
    assert.ok(r.stderr.includes('tier-policy.json'), r.stderr);
    assert.ok(r.stderr.includes('classes'), r.stderr);
  });

  test('malformed JSON located override fails with exit 1', () => {
    const p = makeProject({ override: '{' });
    const r = runCli([], { cwd: p.project, home: p.home, ceiling: p.root });
    assert.equal(r.status, 1);
    assert.equal(r.stdout, '');
    assert.match(r.stderr, /not valid JSON/);
  });

  test('--override pointing at a missing file: exit 1, stdout empty', () => {
    const p = makeProject();
    const f = join(p.root, 'missing.json');
    const r = runCli(['--override', f], { cwd: p.project, home: p.home, ceiling: p.root });
    assert.equal(r.status, 1);
    assert.equal(r.stdout, '');
    assert.ok(r.stderr.includes('missing.json'), r.stderr);
  });

  test('unknown argument: exit 1, stdout empty', () => {
    const p = makeProject();
    const r = runCli(['--bogus'], { cwd: p.project, home: p.home, ceiling: p.root });
    assert.equal(r.status, 1);
    assert.equal(r.stdout, '');
    assert.notEqual(r.stderr, '');
  });

  test('--override without a value: exit 1, stdout empty', () => {
    const p = makeProject();
    const r = runCli(['--override'], { cwd: p.project, home: p.home, ceiling: p.root });
    assert.equal(r.status, 1);
    assert.equal(r.stdout, '');
  });

  test('no config located -> defaults, exit 0', () => {
    const root = tmp('tier-none-');
    const home = join(root, 'home');
    const cwd = join(root, 'empty');
    mkdirSync(home);
    mkdirSync(cwd);
    const r = runCli([], { cwd, home, ceiling: root });
    assert.equal(r.status, 0, r.stderr);
    assert.equal(r.stdout, norm(readFileSync(FRAGMENT, 'utf8')));
  });
});

describe('S10 generator on the real tree', () => {
  test('--check exits 0', () => {
    const r = spawnSync(process.execPath, [GENERATE, '--check'], { cwd: REPO, encoding: 'utf8' });
    assert.equal(r.status, 0, `${r.stdout}\n${r.stderr}`);
  });
});

describe('S11 generator on a tmp copy', () => {
  const CONSUMERS = [
    'claude-plugins/task-orchestrator/skills/orchestrate/SKILL.md',
    '.claude/skills/implement/SKILL.md',
  ];
  const MODEL_EFFORT = 'claude-plugins/task-orchestrator/skills/model-effort/SKILL.md';

  function copyTree() {
    const root = tmp('tier-gen-');
    const rel = (p) => join(root, p);
    const plug = 'claude-plugins/task-orchestrator';
    mkdirSync(rel(plug + '/scripts'), { recursive: true });
    mkdirSync(rel(plug + '/hooks'), { recursive: true });
    cpSync(join(PLUGIN, 'dispatch-policy.json'), rel(plug + '/dispatch-policy.json'));
    cpSync(join(PLUGIN, '_fragments'), rel(plug + '/_fragments'), { recursive: true });
    cpSync(join(PLUGIN, 'scripts', 'lib'), rel(plug + '/scripts/lib'), { recursive: true });
    cpSync(CLI, rel(plug + '/scripts/tier-policy.mjs'));
    cpSync(join(PLUGIN, 'hooks', 'config-locator.mjs'), rel(plug + '/hooks/config-locator.mjs'));
    for (const c of [...CONSUMERS, MODEL_EFFORT]) {
      mkdirSync(dirname(rel(c)), { recursive: true });
      cpSync(join(REPO, c), rel(c));
    }
    return root;
  }
  const gen = (root, ...args) => spawnSync(
    process.execPath,
    [join(root, 'claude-plugins/task-orchestrator/_fragments/generate.mjs'), ...args],
    { cwd: root, encoding: 'utf8' },
  );
  const read = (root, p) => readFileSync(join(root, p), 'utf8');
  const FRAG_REL = 'claude-plugins/task-orchestrator/_fragments/tier-classification.md';
  const POLICY_REL = 'claude-plugins/task-orchestrator/dispatch-policy.json';

  test('the copy starts in sync', () => {
    const root = copyTree();
    const r = gen(root, '--check');
    assert.equal(r.status, 0, `${r.stdout}\n${r.stderr}`);
  });

  test('a changed threshold: --check fails naming the fragment; write regenerates all; --check passes', () => {
    const root = copyTree();
    const policy = JSON.parse(read(root, POLICY_REL));
    policy.tiers.thresholds.directMaxFiles = 5;
    writeFileSync(join(root, POLICY_REL), JSON.stringify(policy, null, 2));
    const modelBefore = readFileSync(join(root, MODEL_EFFORT));

    const stale = gen(root, '--check');
    assert.equal(stale.status, 1);
    assert.ok(`${stale.stdout}${stale.stderr}`.includes('tier-classification.md'), stale.stdout + stale.stderr);

    const wrote = gen(root);
    assert.equal(wrote.status, 0, `${wrote.stdout}\n${wrote.stderr}`);
    assert.match(read(root, FRAG_REL), /1-5 files,/);
    assert.match(read(root, FRAG_REL), /6-10 files,/);
    for (const c of CONSUMERS) {
      const text = read(root, c);
      assert.match(text, /1-5 files,/, c);
      assert.ok(!/\| 1-2 files,/.test(text), c);
    }
    assert.deepEqual(readFileSync(join(root, MODEL_EFFORT)), modelBefore, 'model-effort unchanged');

    const after = gen(root, '--check');
    assert.equal(after.status, 0, `${after.stdout}\n${after.stderr}`);
  });

  test('a hand-edited fragment is reported and restored by write mode', () => {
    const root = copyTree();
    const original = readFileSync(join(root, FRAG_REL));
    writeFileSync(join(root, FRAG_REL), Buffer.concat([original, Buffer.from('hand edit\n')]));
    const stale = gen(root, '--check');
    assert.equal(stale.status, 1);
    assert.ok(`${stale.stdout}${stale.stderr}`.includes('tier-classification.md'));
    const wrote = gen(root);
    assert.equal(wrote.status, 0, `${wrote.stdout}\n${wrote.stderr}`);
    assert.equal(norm(read(root, FRAG_REL)), norm(original.toString('utf8')));
    assert.equal(gen(root, '--check').status, 0);
  });

  test('a stale consumer region is reported by path', () => {
    const root = copyTree();
    const c = CONSUMERS[0];
    const text = read(root, c);
    assert.match(text, /1-2 files,/);
    writeFileSync(join(root, c), text.replace('1-2 files,', '1-9 files,'));
    const r = gen(root, '--check');
    assert.equal(r.status, 1);
    assert.ok(`${r.stdout}${r.stderr}`.includes('orchestrate'), r.stdout + r.stderr);
  });

  test('write mode is idempotent and ignores any project override file', () => {
    const root = copyTree();
    mkdirSync(join(root, '.taskorchestrator'), { recursive: true });
    writeFileSync(
      join(root, '.taskorchestrator', 'tier-policy.json'),
      JSON.stringify({ thresholds: { directMaxFiles: 7 } }),
    );
    const snap = () => [FRAG_REL, ...CONSUMERS].map((p) => read(root, p));
    const before = snap();
    assert.equal(gen(root).status, 0);
    const first = snap();
    assert.equal(gen(root).status, 0);
    assert.deepEqual(snap(), first);
    assert.deepEqual(first, before, 'a project override never reaches generated files');
  });
});

describe('S13 policy without tiers', () => {
  function policyWithout(mutate) {
    const d = tmp('tier-pol-');
    const policy = JSON.parse(readFileSync(POLICY_JSON, 'utf8'));
    mutate(policy);
    const f = join(d, 'dispatch-policy.json');
    writeFileSync(f, JSON.stringify(policy));
    return f;
  }

  test('no tiers key: loadPolicy and forClass work; tierPolicy throws /tiers missing/', () => {
    const f = policyWithout((p) => { delete p.tiers; });
    assert.doesNotThrow(() => loadPolicy(f));
    assert.deepEqual(forClass('review', f), forClass('review'));
    assert.throws(() => tierPolicy(f), /tiers missing/);
  });

  test('malformed tiers: loadPolicy and forClass still work; tierPolicy throws', () => {
    const f = policyWithout((p) => { p.tiers.thresholds = { directMaxFiles: 10, delegatedMaxFiles: 10 }; });
    assert.doesNotThrow(() => loadPolicy(f));
    assert.deepEqual(forClass('review', f), forClass('review'));
    assert.throws(() => tierPolicy(f), /thresholds/);
  });

  test('a tiers block missing a row or a list is rejected', () => {
    const noRow = policyWithout((p) => { delete p.tiers.rows.Parallel; });
    assert.throws(() => tierPolicy(noRow));
    const noList = policyWithout((p) => { delete p.tiers.forceDown; });
    assert.throws(() => tierPolicy(noList));
  });

  test('the shipped policy still loads and classes are unchanged in shape', () => {
    // Literals from the pre-existing dispatch-policy.test.mjs (committed before this item).
    assert.doesNotThrow(() => loadPolicy());
    assert.equal(classNames().length, 15);
    assert.deepEqual(loadPolicy().models, ['opus', 'sonnet', 'haiku', 'fable']);
    assert.deepEqual(forClass('review'), { model: 'opus', effort: 'high' });
    assert.deepEqual(forClass('implementation'), { model: 'sonnet', effort: 'medium' });
  });
});
