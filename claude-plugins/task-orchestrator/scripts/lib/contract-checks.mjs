// contract-checks.mjs - pure checks that keep the plugin in step with the 4.0 server contract.
//
// Three checks, each a pure function of its arguments plus file reads under the given plugin root:
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

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { SERVER_SEGMENT_TOKEN, orchestratorToolMatcher } from '../../hooks/registration.mjs';

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

const SEG = '(?:(?!__)[A-Za-z0-9_.-])*';
const QUALIFIED_RE = new RegExp(`mcp__(${SEG}${SERVER_SEGMENT_TOKEN}${SEG})__([a-z][a-z0-9_]*|\\*)`, 'g');
const BARE_RE = new RegExp(`\\b(${BARE_RETIRED.join('|')})\\b`, 'g');

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

/**
 * Scans markdown files for to-call blocks and validates each against the catalog.
 * @param {{pluginRoot: string, dirs: string[], catalog: object}} args dirs are plugin-relative dirs scanned for *.md
 *   (recursively when the entry ends with "/**", otherwise one level)
 * @returns {{file: string, line: number, error: string}[]}
 */
export function scanToCallDocs({ pluginRoot, dirs, catalog }) {
  const problems = [];
  for (const spec of dirs) {
    const recursive = spec.endsWith('/**');
    const dir = join(pluginRoot, recursive ? spec.slice(0, -3) : spec);
    const files = [];
    if (recursive) walkAll(dir, files);
    else if (existsSync(dir)) {
      for (const name of readdirSync(dir).sort()) if (name.endsWith('.md')) files.push(join(dir, name));
    }
    for (const abs of files) {
      const file = toPosix(relative(pluginRoot, abs));
      for (const block of parseToCallBlocks(readText(abs))) {
        if ('error' in block) problems.push({ file, line: block.line, error: block.error });
        else for (const error of checkToCall(block.value, catalog)) problems.push({ file, line: block.line, error });
      }
    }
  }
  return problems;
}

function walkAll(dir, out) {
  if (!existsSync(dir)) return;
  for (const name of readdirSync(dir).sort()) {
    const abs = join(dir, name);
    if (statSync(abs).isDirectory()) walkAll(abs, out);
    else if (name.endsWith('.md')) out.push(abs);
  }
}
