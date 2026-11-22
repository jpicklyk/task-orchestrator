// contract-checks.mjs - checks that keep the plugin in step with the 4.0 server contract.
//
// Three checks, each a function of its arguments plus file reads under the given plugin root (the
// domain-boundary scan also asks git which files are ignored):
//   checkMatchers   every orchestrator hook matcher names only real 4.0 tools and equals the
//                   registration.mjs builder output.
//   scanToolNames   no file names a retired 3.x tool: a qualified mcp__<server>__<name> literal with an
//                   unknown name is always a violation; a bare retired name is a violation unless the
//                   file is listed as pending a port or the line carries a listed prose exception.
//                   The baseline is a two-way ratchet: a stale row fails too.
//   parseToCallBlocks / checkToCall   the documented call-shape blocks (```to-call fences holding
//                   {tool, select|op, args, profile?}) are valid against the generated catalog.
//
// Callers: hooks/tests/contract-*.test.mjs (against the real repo and against fixtures).

import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { SERVER_SEGMENT_TOKEN, hostToolMatcher, orchestratorToolMatcher } from '../../hooks/registration.mjs';

/** The 3.x tool names that no longer exist. */
export const RETIRED_3X_TOOLS = Object.freeze([
  'advance_item', 'claim_item', 'complete_tree', 'create_work_tree', 'get_blocked_items', 'get_context',
  'get_next_item', 'get_next_status', 'manage_dependencies', 'manage_items', 'manage_notes',
  'manage_plan_documents', 'manage_project_config', 'query_dependencies', 'query_items', 'query_notes',
  'query_rules',
]);

// complete_tree is an items_write op in 4.0, so the bare word is legitimate prose; only a qualified
// mcp__..__complete_tree literal is a violation.
const BARE_RETIRED = RETIRED_3X_TOOLS.filter((name) => name !== 'complete_tree');

// Files that necessarily spell the retired names (the list itself and the baseline).
const SELF_EXEMPT = new Set(['scripts/lib/contract-checks.mjs', 'scripts/lib/contract-names-baseline.json']);

const SCANNED_EXTENSIONS = new Set(['.mjs', '.js', '.json', '.md', '.yaml', '.yml', '.txt', '.sh']);
const SKIPPED_SEGMENTS = new Set(['tests', 'node_modules']);

const SEG = '(?:(?!__)[A-Za-z0-9_.*+?-])*';
const QUALIFIED_RE = new RegExp(`mcp__(${SEG}${SERVER_SEGMENT_TOKEN}${SEG})__([a-z][a-z0-9_]*|\\*)`, 'g');
const BARE_RE = new RegExp(`(?<![A-Za-z0-9])(${BARE_RETIRED.join('|')})(?![A-Za-z0-9_])`, 'g');

// ---------------------------------------------------------------------------------------------------
// Matchers

const MATCHER_RE = new RegExp(`^\\^mcp__\\.\\*${SERVER_SEGMENT_TOKEN}\\.\\*__(?:\\((.*)\\)|(.*))\\$$`);

/**
 * Checks every hook matcher that targets the orchestrator server (contains SERVER_SEGMENT_TOKEN).
 * @returns {{event: string, matcher: string, tool: string|null, reason: string}[]}
 */
export function checkMatchers(hooksConfig, allowedTools) {
  const allowed = new Set(allowedTools);
  const problems = [];
  const hooks = (hooksConfig && hooksConfig.hooks) || {};
  for (const [event, entries] of Object.entries(hooks)) {
    for (const entry of entries || []) {
      const matcher = entry && entry.matcher;
      if (typeof matcher !== 'string' || !matcher.includes(SERVER_SEGMENT_TOKEN)) continue;
      if (matcher === hostToolMatcher()) continue; // names the token only inside a negative lookahead
      const m = MATCHER_RE.exec(matcher);
      if (!m) {
        problems.push({ event, matcher, tool: null, reason: 'matcher is not in the registration.mjs builder shape' });
        continue;
      }
      const tools = (m[1] !== undefined ? m[1] : m[2]).split('|');
      let toolProblem = false;
      for (const tool of tools) {
        if (!allowed.has(tool)) {
          problems.push({ event, matcher, tool, reason: `${tool} is not a 4.0 tool` });
          toolProblem = true;
        }
      }
      if (!toolProblem && matcher !== orchestratorToolMatcher(tools)) {
        problems.push({ event, matcher, tool: null, reason: 'matcher differs from orchestratorToolMatcher output' });
      }
    }
  }
  return problems;
}

// ---------------------------------------------------------------------------------------------------
// Hard-coded tool names

function walk(dir, out) {
  let names;
  try {
    names = readdirSync(dir);
  } catch {
    return;
  }
  for (const name of names.sort()) {
    if (SKIPPED_SEGMENTS.has(name)) continue;
    const abs = join(dir, name);
    const st = statSync(abs);
    if (st.isDirectory()) walk(abs, out);
    else {
      const dot = name.lastIndexOf('.');
      if (dot >= 0 && SCANNED_EXTENSIONS.has(name.slice(dot))) out.push(abs);
    }
  }
}

const toPosix = (p) => p.split(sep).join('/');

function readText(abs) {
  return readFileSync(abs, 'utf-8').replace(/\r\n/g, '\n');
}

// Bare retired names on a line, after blanking qualified literals (those are judged separately).
function bareNames(line) {
  const blanked = line.replace(QUALIFIED_RE, (m) => ' '.repeat(m.length));
  return [...blanked.matchAll(BARE_RE)].map((m) => m[1]);
}

function qualifiedViolations(line, toolSet) {
  const found = [];
  for (const m of line.matchAll(QUALIFIED_RE)) {
    if (m[2] === '*') continue;
    if (!toolSet.has(m[2])) found.push(m[2]);
  }
  return found;
}

/**
 * Scans the plugin for hard-coded tool names.
 * @param {{pluginRoot: string, dirs: string[], tools: string[], baseline: {pendingPort?: {file: string, owner: string}[], proseExceptions?: {file: string, text: string, comment?: string}[]}}} args
 * @returns {{violations: {file: string, line: number, name: string, reason: string}[], stale: {file: string, reason: string}[]}}
 */
export function scanToolNames({ pluginRoot, dirs, tools, baseline }) {
  const toolSet = new Set(tools);
  const pending = new Set(((baseline && baseline.pendingPort) || []).map((r) => r.file));
  const exceptions = (baseline && baseline.proseExceptions) || [];
  const violations = [];
  const stale = [];

  const files = [];
  for (const dir of dirs) walk(join(pluginRoot, dir), files);
  for (const abs of files) {
    const file = toPosix(relative(pluginRoot, abs));
    if (SELF_EXEMPT.has(file)) continue;
    const lines = readText(abs).split('\n');
    const lineExceptions = exceptions.filter((e) => e.file === file).map((e) => e.text);
    lines.forEach((line, idx) => {
      for (const name of qualifiedViolations(line, toolSet)) {
        violations.push({ file, line: idx + 1, name, reason: 'qualified tool name is not a 4.0 tool' });
      }
      if (pending.has(file)) return;
      if (lineExceptions.some((text) => line.includes(text))) return;
      for (const name of bareNames(line)) {
        violations.push({ file, line: idx + 1, name, reason: 'retired 3.x tool name' });
      }
    });
  }

  for (const file of [...pending].sort()) {
    const abs = join(pluginRoot, file);
    if (!existsSync(abs)) {
      stale.push({ file, reason: 'pendingPort file does not exist' });
      continue;
    }
    const lines = readText(abs).split('\n');
    const holds = lines.some((line) => bareNames(line).length > 0 || qualifiedViolations(line, toolSet).length > 0);
    if (!holds) stale.push({ file, reason: 'pendingPort file holds no retired tool name' });
  }
  for (const e of exceptions) {
    const abs = join(pluginRoot, e.file);
    if (!existsSync(abs) || !readText(abs).includes(e.text)) {
      stale.push({ file: e.file, reason: `proseException text not found: ${e.text}` });
    }
  }
  return { violations, stale };
}

// ---------------------------------------------------------------------------------------------------
// to-call blocks

/**
 * Finds the ```to-call fenced blocks of a markdown text.
 * @returns {({line: number, value: any} | {line: number, error: string})[]} line is the 1-based line of the opening fence
 */
export function parseToCallBlocks(markdown) {
  const lines = String(markdown).replace(/\r\n/g, '\n').split('\n');
  const blocks = [];
  for (let i = 0; i < lines.length; i++) {
    if (lines[i] !== '```to-call') continue;
    let end = -1;
    for (let j = i + 1; j < lines.length; j++) {
      if (lines[j].startsWith('```')) {
        end = lines[j] === '```' ? j : -2;
        break;
      }
    }
    if (end < 0) {
      blocks.push({ line: i + 1, error: 'to-call block is not closed by a plain ``` fence' });
      continue;
    }
    try {
      blocks.push({ line: i + 1, value: JSON.parse(lines.slice(i + 1, end).join('\n')) });
    } catch (err) {
      blocks.push({ line: i + 1, error: `to-call block is not valid JSON: ${err.message}` });
    }
    i = end;
  }
  return blocks;
}

const TOP_KEYS = new Set(['tool', 'select', 'op', 'args', 'profile']);
const isPlainObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);

/**
 * Validates one parsed to-call value against the catalog (hooks/lib/tool-catalog.json).
 * @returns {string[]} error messages; empty when valid
 */
export function checkToCall(value, catalog) {
  if (!isPlainObject(value)) return ['to-call block must be a JSON object'];
  const errors = [];
  for (const key of Object.keys(value)) {
    if (!TOP_KEYS.has(key)) errors.push(`unknown top-level key: ${key}`);
  }
  const tool = value.tool;
  const def = typeof tool === 'string' ? catalog.tools[tool] : undefined;
  if (!def) {
    errors.push(`unknown tool: ${typeof tool === 'string' ? tool : String(tool)}`);
    return errors;
  }
  const isOps = def.kind === 'ops';
  const selector = isOps ? 'op' : 'select';
  const forbidden = isOps ? 'select' : 'op';
  if (value[forbidden] !== undefined) errors.push(`${tool} is a${isOps ? 'n ops' : ' select'} tool: ${forbidden} is not allowed`);
  const name = value[selector];
  if (typeof name !== 'string' || name === '') {
    errors.push(`${tool} needs ${selector}`);
    return errors;
  }
  const entry = def.entries[name];
  if (!entry) {
    errors.push(`unknown ${selector} for ${tool}: ${name}`);
    return errors;
  }
  const profile = value.profile === undefined ? 'orchestration' : value.profile;
  if (typeof profile !== 'string' || !entry.profiles.includes(profile)) {
    errors.push(`${tool} ${name} is not available in profile ${String(profile)}`);
  }
  if (!isPlainObject(value.args)) {
    errors.push('args must be an object');
    return errors;
  }
  const fieldNames = Object.keys(entry.fields);
  const plain = new Set(fieldNames.filter((f) => !f.includes('.')));
  if (isOps) plain.add('idempotencyKey');
  for (const [key, arg] of Object.entries(value.args)) {
    if (!plain.has(key)) {
      errors.push(`unknown arg: ${key}`);
      continue;
    }
    const children = fieldNames.filter((f) => f.startsWith(`${key}.`)).map((f) => f.slice(key.length + 1));
    if (children.length > 0 && isPlainObject(arg)) {
      for (const nested of Object.keys(arg)) {
        if (!children.includes(nested)) errors.push(`unknown nested arg: ${key}.${nested}`);
      }
    }
  }
  for (const [field, spec] of Object.entries(entry.fields)) {
    if (spec.required && !field.includes('.') && !(field in value.args)) errors.push(`missing required arg: ${field}`);
  }
  return errors;
}

// ---------------------------------------------------------------------------------------------------
// Dispatch literals

const DISPATCH_VALUE = '(?:haiku|sonnet|opus|fable|low|medium|high|xhigh|max)(?![A-Za-z0-9_-])';
// A dispatch value is spelled as `model: "x"`, `effort: 'x'`, `model=x`, `--model x`, `--effort=x` or an
// argv pair `'--effort', 'x'`; the bare alias or level words in prose do not match.
const DISPATCH_RES = [
  new RegExp(String.raw`(?<![A-Za-z0-9_-])(model|effort)["']?\s*[:=]\s*["'` + '`' + String.raw`]?` + DISPATCH_VALUE, 'g'),
  new RegExp(String.raw`--(model|effort)(?:[ =]|["'],\s*)["'` + '`' + String.raw`]?` + DISPATCH_VALUE, 'g'),
  // keyword, whitespace, quoted value: `model "sonnet"`
  new RegExp(String.raw`(?<![A-Za-z0-9_-])(model|effort)\s+["']` + DISPATCH_VALUE, 'g'),
];

// The scan's own source and the allow-list necessarily spell the shapes.
const DISPATCH_SELF_EXEMPT = new Set(['scripts/lib/contract-checks.mjs', 'scripts/lib/dispatch-literals-allowlist.json']);

/** dispatchLiteralsInLine(line) -> the dispatch-value spellings found in one line (exported for tests). */
export function dispatchLiteralsInLine(line) {
  return dispatchLiterals(line);
}

function dispatchLiterals(line) {
  const found = [];
  for (const re of DISPATCH_RES) for (const m of line.matchAll(re)) found.push(m[0]);
  return found;
}

/**
 * Scans the plugin for a model alias or effort level used as a dispatch value outside
 * dispatch-policy.json. The allow-list is a two-way ratchet: a row whose file or text is gone fails.
 * @param {{pluginRoot: string, dirs: string[], allowlist: {file: string, text: string, comment?: string}[]}} args
 * @returns {{violations: {file: string, line: number, literal: string}[], stale: {file: string, reason: string}[]}}
 */
export function scanDispatchLiterals({ pluginRoot, dirs, allowlist }) {
  const rows = allowlist || [];
  const violations = [];
  const stale = [];
  const files = [];
  for (const dir of dirs) walk(join(pluginRoot, dir), files);
  for (const abs of files) {
    const file = toPosix(relative(pluginRoot, abs));
    if (DISPATCH_SELF_EXEMPT.has(file)) continue;
    const allowed = rows.filter((r) => r.file === file).map((r) => r.text);
    readText(abs).split('\n').forEach((line, idx) => {
      if (allowed.some((text) => line.includes(text))) return;
      for (const literal of dispatchLiterals(line)) violations.push({ file, line: idx + 1, literal });
    });
  }
  for (const r of rows) {
    const abs = join(pluginRoot, r.file);
    if (!existsSync(abs) || !readText(abs).split('\n').some((line) => line.includes(r.text) && dispatchLiterals(line).length > 0)) {
      stale.push({ file: r.file, reason: `allow-list text not found or holds no dispatch literal: ${r.text}` });
    }
  }
  return { violations, stale };
}

// ---------------------------------------------------------------------------------------------------
// Domain boundary: plugin and project text never names a Claude Code or Anthropic skill the layer does
// not own. Detection is a closed name set in two forms (slash, and next to the word skill), not every
// /word: a generic scan hits paths and regex flags.

/**
 * Claude Code and Anthropic skills and slash commands. 'mcp' is left out on purpose: /mcp is also the
 * server's own endpoint path, which a text scan cannot tell apart.
 */
export const HOST_SKILLS = Object.freeze([
  'agents', 'claude-api', 'clear', 'code-review', 'compact', 'init', 'loop', 'reload-skills', 'review', 'schedule',
  'security-review', 'simplify', 'skill-creator', 'workflows',
]);

const DECLARED_SKILL_RE = /^\s*skill:\s*["']?([A-Za-z0-9_.:-]+)["']?\s*(?:#.*)?$/;

/** declaredSkills(yamlText) -> sorted unique values of the `skill:` lines of a config text. */
export function declaredSkills(yamlText) {
  const found = new Set();
  for (const line of String(yamlText).replace(/\r\n/g, '\n').split('\n')) {
    const m = DECLARED_SKILL_RE.exec(line);
    if (m) found.add(m[1]);
  }
  return [...found].sort();
}

/** pluginShippedNames(pluginRoot) -> sorted unique names the plugin itself ships (skills, agents, workflows, rules). */
export function pluginShippedNames(pluginRoot) {
  const found = new Set();
  const list = (dir) => {
    try {
      return readdirSync(join(pluginRoot, dir), { withFileTypes: true });
    } catch {
      return [];
    }
  };
  for (const e of list('skills')) if (e.isDirectory() && !e.name.startsWith('_')) found.add(e.name);
  for (const [dir, ext] of [['agents', '.md'], ['workflows', '.js'], ['bundled-rules', '.md']]) {
    for (const e of list(dir)) if (e.isFile() && e.name.endsWith(ext)) found.add(e.name.slice(0, -ext.length));
  }
  return [...found].sort();
}

/**
 * pluginShippedSkillNames(pluginRoot) -> sorted unique directory names under skills/ (a leading `_` excluded).
 * Only these are plugin skills: an agent, workflow or rule that shares a name with a project skill does not make the
 * project skill the plugin's own, so the domain-boundary scan keeps scanning for it.
 */
export function pluginShippedSkillNames(pluginRoot) {
  try {
    return readdirSync(join(pluginRoot, 'skills'), { withFileTypes: true })
      .filter((e) => e.isDirectory() && !e.name.startsWith('_'))
      .map((e) => e.name)
      .sort();
  } catch {
    return [];
  }
}

const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const SKILL_WORD = '[Ss][Kk][Ii][Ll][Ll][Ss]?';

function boundaryHits(line, names, phrases) {
  const hits = [];
  for (const name of names) {
    const n = escapeRe(name);
    const slash = new RegExp('(?<=^|[\\s`\'"(\\[|,])/' + n + '(?![A-Za-z0-9_/-])', 'g');
    for (const m of line.matchAll(slash)) hits.push({ name, form: 'slash', col: m.index });
    if (name.includes('-') || name === 'simplify') {
      const a = new RegExp('(?<![A-Za-z0-9_/-])' + n + '["\'`]?\\s+' + SKILL_WORD + '(?![A-Za-z])', 'g');
      for (const m of line.matchAll(a)) hits.push({ name, form: 'skill', col: m.index });
      const b = new RegExp(
        '(?<![A-Za-z])' + SKILL_WORD + '(?:["\']?\\s*[:=]+\\s*[\\[(]?\\s*|\\s+)["\'`]?' + n + '(?![A-Za-z0-9_-])',
        'g',
      );
      for (const m of line.matchAll(b)) hits.push({ name, form: 'skill', col: m.index });
      // Form (b) sees only the first name of a list; every later item that is exactly this name hits too.
      const list = new RegExp('(?<![A-Za-z])' + SKILL_WORD + '["\']?\\s*[:=]+\\s*[\\[(]?([^\\])]*)', 'g');
      for (const m of line.matchAll(list)) {
        m[1].split(',').forEach((item, i) => {
          if (i > 0 && item.trim().replace(/^["'`]|["'`]$/g, '') === name) hits.push({ name, form: 'skill', col: m.index });
        });
      }
    }
  }
  for (const phrase of phrases) {
    const re = new RegExp('(?<![A-Za-z])' + escapeRe(phrase) + '(?![A-Za-z])', 'gi');
    for (const m of line.matchAll(re)) hits.push({ name: phrase, form: 'phrase', col: m.index });
  }
  return hits.sort((x, y) => x.col - y.col);
}

function collectBoundaryFiles(root, scopes) {
  const out = new Set();
  const visit = (dir, recursive) => {
    let entries;
    try {
      entries = readdirSync(dir);
    } catch {
      return;
    }
    for (const name of entries.sort()) {
      if (SKIPPED_SEGMENTS.has(name)) continue;
      const abs = join(dir, name);
      const st = statSync(abs);
      if (st.isDirectory()) {
        if (recursive) visit(abs, true);
      } else {
        const dot = name.lastIndexOf('.');
        if (dot >= 0 && SCANNED_EXTENSIONS.has(name.slice(dot))) out.add(abs);
      }
    }
  };
  for (const s of scopes) visit(join(root, s.dir), s.recursive !== false);
  return [...out];
}

/**
 * Drops the files git ignores (one check-ignore call, run from root). Tracked files are never reported
 * by check-ignore, so they stay. Exit 1 (none ignored), 128 (not a repository) or a spawn error drops
 * nothing.
 */
function dropGitIgnored(root, files) {
  if (files.length === 0) return files;
  const rels = files.map((abs) => toPosix(relative(root, abs)));
  const r = spawnSync('git', ['-C', root, 'check-ignore', '--stdin'], { input: rels.join('\n'), encoding: 'utf8' });
  if (r.error || r.status !== 0) return files;
  const ignored = new Set(r.stdout.split('\n').map((l) => l.trim()).filter(Boolean));
  return files.filter((_, i) => !ignored.has(rels[i]));
}

/**
 * Scans scopes (gitignored files excluded) for a host skill name (slash or next to the word skill) or a
 * banned phrase. allow and
 * baseline rows ({file, name}) excuse every hit of that name in that file; both are two-way ratchets:
 * a row whose file is missing or holds no hit of its name is stale.
 * @param {{root: string, scopes: {dir: string, recursive?: boolean}[], names: string[], phrases?: string[], allow?: {file: string, name: string}[], baseline?: {file: string, name: string}[]}} args
 * @returns {{violations: {file: string, line: number, name: string, form: string}[], stale: {file: string, name: string, reason: string}[]}}
 */
export function scanDomainBoundary({ root, scopes, names, phrases = [], allow = [], baseline = [] }) {
  const excused = new Set([...allow, ...baseline].map((r) => `${r.file}\u0000${r.name}`));
  const violations = [];
  const hitKeys = new Set();
  for (const abs of dropGitIgnored(root, collectBoundaryFiles(root, scopes))) {
    const file = toPosix(relative(root, abs));
    readText(abs).split('\n').forEach((line, idx) => {
      for (const h of boundaryHits(line, names, phrases)) {
        const key = `${file}\u0000${h.name}`;
        hitKeys.add(key);
        if (!excused.has(key)) violations.push({ file, line: idx + 1, name: h.name, form: h.form });
      }
    });
  }
  const stale = [];
  const checkRows = (rows, label) => {
    for (const r of rows) {
      if (!existsSync(join(root, r.file))) stale.push({ file: r.file, name: r.name, reason: `${label} file does not exist` });
      else if (!hitKeys.has(`${r.file}\u0000${r.name}`)) stale.push({ file: r.file, name: r.name, reason: `${label} row has no hit` });
    }
  };
  checkRows(allow, 'allow');
  checkRows(baseline, 'baseline');
  return { violations, stale };
}
