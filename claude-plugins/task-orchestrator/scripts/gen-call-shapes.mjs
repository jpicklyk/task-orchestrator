#!/usr/bin/env node
// gen-call-shapes.mjs - generates the plugin's 4.0 call-shape contract from the server's own files.
//
// Inputs (read only):
//   - the tool goldens  current/src/test/resources/golden/tools/<tool>.<profile>.json
//     (MCP inputSchema per profile: profile visibility, select/op membership, field names)
//   - current/docs/api-reference.md: every `<!-- generated:tool NAME -->` block (per select/op profiles,
//     capability, destructive flag, field types and required flags, error codes) and the
//     `<!-- generated:error-catalog -->` block (error kind and HTTP status).
// Outputs (the five managed files, plugin-relative posix paths):
//   skills/_generated/call-shapes.md, hooks/lib/tool-catalog.json, hooks/lib/tool-names.mjs,
//   and the TOOL_NAMES region of workflows/implement-wave.js and workflows/review-wave.js.
//
// Usage: node gen-call-shapes.mjs [--check] [--plugin-root D] [--golden-dir D] [--api-reference F]
//   write mode: writes all five files, exit 0.
//   --check: exit 0 when fresh; exit 1 listing each stale path on stderr.
//   Inconsistent inputs (golden vs API reference): exit 2.

import { readFileSync, writeFileSync, readdirSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const DEFAULT_PLUGIN_ROOT = resolve(SCRIPT_DIR, '..');
const DEFAULT_GOLDEN_DIR = resolve(DEFAULT_PLUGIN_ROOT, '../../current/src/test/resources/golden/tools');
const DEFAULT_API_REFERENCE = resolve(DEFAULT_PLUGIN_ROOT, '../../current/docs/api-reference.md');

export const OUT_CALL_SHAPES = 'skills/_generated/call-shapes.md';
export const OUT_CATALOG = 'hooks/lib/tool-catalog.json';
export const OUT_TOOL_NAMES = 'hooks/lib/tool-names.mjs';
export const OUT_WORKFLOWS = ['workflows/implement-wave.js', 'workflows/review-wave.js'];
export const MANAGED_FILES = [OUT_CALL_SHAPES, OUT_CATALOG, OUT_TOOL_NAMES, ...OUT_WORKFLOWS];

export const REGION_BEGIN = '// @generated:tool-names-begin';
export const REGION_END = '// @generated:tool-names-end';
const REGENERATE_COMMAND = 'node claude-plugins/task-orchestrator/scripts/gen-call-shapes.mjs';
const PROFILE_WORDS = ['knowledge', 'orchestration', 'director'];

function fail(message) {
  throw new Error(message);
}

const sortStrings = (list) => [...list].sort();

// ---------------------------------------------------------------------------------------------------
// Goldens

function readGoldens(goldenDir) {
  const tools = {};
  const files = readdirSync(goldenDir).filter((f) => f.endsWith('.json')).sort();
  for (const file of files) {
    const [tool, profile] = file.split('.');
    const json = JSON.parse(readFileSync(join(goldenDir, file), 'utf-8'));
    const props = (json.inputSchema && json.inputSchema.properties) || {};
    const entry = tools[tool] || (tools[tool] = { kind: null, profiles: {}, fields: new Set() });
    const kind = props.ops ? 'ops' : 'select';
    if (entry.kind && entry.kind !== kind) fail(`golden ${file}: tool ${tool} mixes ops and select shapes`);
    entry.kind = kind;
    let members;
    let fieldProps;
    if (kind === 'ops') {
      fieldProps = props.ops.items.properties;
      members = fieldProps.op.enum;
    } else {
      fieldProps = props;
      members = props.select.enum;
    }
    if (!Array.isArray(members)) fail(`golden ${file}: no ${kind === 'ops' ? 'op' : 'select'} enum`);
    entry.profiles[profile] = new Set(members);
    for (const name of Object.keys(fieldProps)) entry.fields.add(name);
  }
  return tools;
}

// ---------------------------------------------------------------------------------------------------
// API reference

const PIPE = String.fromCharCode(124);
const BACKSLASH = String.fromCharCode(92);
const ESCAPED_PIPE = BACKSLASH + PIPE;

// Splits a markdown table row on unescaped pipes.
function splitRow(line) {
  const cells = [];
  let cell = '';
  const body = line.trim();
  for (let i = 0; i < body.length; i++) {
    const ch = body[i];
    if (ch === BACKSLASH && body[i + 1] === PIPE) {
      cell += ESCAPED_PIPE;
      i++;
    } else if (ch === PIPE) {
      cells.push(cell.trim());
      cell = '';
    } else {
      cell += ch;
    }
  }
  cells.push(cell.trim());
  // leading and trailing pipes produce empty first/last cells
  return cells.slice(1, -1);
}

const unescapePipes = (text) => text.split(ESCAPED_PIPE).join(PIPE);
const escapePipes = (text) => text.split(PIPE).join(ESCAPED_PIPE);

function parseHeading(line, where) {
  const m = /^\*\*`(\w+)` (op|select) `([\w.-]+)`\*\* \(([^)]*)\): /.exec(line);
  if (!m) fail(`${where}: unparseable entry heading: ${line.slice(0, 80)}`);
  const [, tool, kind, name, phrase] = m;
  const parts = phrase.split(';').map((p) => p.trim());
  const profileText = parts[0];
  let profiles;
  if (profileText === 'REST only') {
    profiles = [];
  } else {
    profiles = PROFILE_WORDS.filter((w) => new RegExp(`\\b${w}\\b`).test(profileText));
    if (profiles.length === 0) fail(`${where}: no profile named in "${profileText}"`);
  }
  let capability = null;
  let destructive = false;
  for (const part of parts.slice(1)) {
    const cap = /^capability `([^`]+)`$/.exec(part);
    if (cap) capability = cap[1];
    else if (part === 'destructive') destructive = true;
    else fail(`${where}: unknown heading clause "${part}"`);
  }
  return { tool, kind: kind === 'op' ? 'ops' : 'select', name, profiles: sortStrings(profiles), capability, destructive };
}

function parseToolBlock(tool, lines) {
  const entries = {};
  let i = 0;
  while (i < lines.length) {
    if (!lines[i].startsWith('**`')) {
      i++;
      continue;
    }
    const where = `api-reference ${tool}`;
    const head = parseHeading(lines[i], where);
    if (head.tool !== tool) fail(`${where}: entry heading names tool ${head.tool}`);
    const fields = {};
    let errors = [];
    i++;
    while (i < lines.length && !lines[i].startsWith('**`')) {
      const line = lines[i];
      if (line.startsWith('| ') && !line.startsWith('| Field ') && !line.startsWith('| ---')) {
        const cells = splitRow(line);
        if (cells.length < 4) fail(`${where} ${head.name}: malformed table row: ${line.slice(0, 80)}`);
        const fieldName = cells[0].replace(/^`|`$/g, '');
        if (cells[2] !== 'yes' && cells[2] !== 'no') fail(`${where} ${head.name}: bad Required cell for ${fieldName}`);
        fields[fieldName] = { type: unescapePipes(cells[1]), required: cells[2] === 'yes' };
      } else if (line.startsWith('Errors: ')) {
        errors = sortStrings([...line.matchAll(/`([^`]+)`/g)].map((m) => m[1]));
      }
      i++;
    }
    if (entries[head.name]) fail(`${where}: duplicate entry ${head.name}`);
    entries[head.name] = {
      kind: head.kind,
      anchor: `${tool}-${head.name}`,
      profiles: head.profiles,
      capability: head.capability,
      destructive: head.destructive,
      fields,
      errors,
    };
  }
  return entries;
}

function readApiReference(apiReferencePath) {
  const text = readFileSync(apiReferencePath, 'utf-8').replace(/\r\n/g, '\n');
  const lines = text.split('\n');
  const tools = {};
  const errors = {};
  for (let i = 0; i < lines.length; i++) {
    const open = /^<!-- generated:tool (\w+) -->$/.exec(lines[i]);
    if (open) {
      const tool = open[1];
      const end = lines.indexOf(`<!-- /generated:tool ${tool} -->`, i);
      if (end < 0) fail(`api-reference: unterminated generated:tool ${tool} block`);
      tools[tool] = parseToolBlock(tool, lines.slice(i + 1, end));
      i = end;
    } else if (lines[i] === '<!-- generated:error-catalog -->') {
      const end = lines.indexOf('<!-- /generated:error-catalog -->', i);
      if (end < 0) fail('api-reference: unterminated generated:error-catalog block');
      for (const line of lines.slice(i + 1, end)) {
        if (!line.startsWith('| `')) continue;
        const cells = splitRow(line);
        const kind = cells[1];
        const http = Number(cells[2]);
        if (!['permanent', 'transient', 'shedding'].includes(kind) || !Number.isInteger(http)) {
          fail(`api-reference error catalog: malformed row: ${line.slice(0, 80)}`);
        }
        errors[cells[0].replace(/^`|`$/g, '')] = { kind, http };
      }
      i = end;
    }
  }
  return { tools, errors };
}

// ---------------------------------------------------------------------------------------------------
// Catalog

/**
 * Builds the catalog from the goldens and the API reference. Throws (the CLI turns it into exit 2)
 * when the two disagree.
 */
export function buildCatalog({ goldenDir = DEFAULT_GOLDEN_DIR, apiReferencePath = DEFAULT_API_REFERENCE } = {}) {
  const goldens = readGoldens(goldenDir);
  const api = readApiReference(apiReferencePath);
  const catalog = { tools: {}, errors: {} };

  for (const tool of sortStrings(Object.keys(goldens))) {
    const golden = goldens[tool];
    const apiEntries = api.tools[tool];
    if (!apiEntries) fail(`tool ${tool} has a golden but no generated:tool block in the API reference`);
    const goldenProfiles = sortStrings(Object.keys(golden.profiles));
    const entries = {};
    for (const [name, entry] of Object.entries(apiEntries)) {
      if (entry.kind !== golden.kind) {
        fail(`${tool} ${name}: API reference documents an ${entry.kind} entry but the golden is a ${golden.kind} tool`);
      }
      const inGolden = goldenProfiles.filter((p) => golden.profiles[p].has(name));
      if (JSON.stringify(inGolden) !== JSON.stringify(entry.profiles)) {
        fail(`${tool} ${name}: API reference profiles [${entry.profiles}] disagree with golden membership [${inGolden}]`);
      }
      for (const field of entry.profiles.length === 0 ? [] : Object.keys(entry.fields)) {
        if (!field.includes('.') && !golden.fields.has(field)) {
          fail(`${tool} ${name}: API field ${field} is not a property of the golden ${tool} input`);
        }
      }
      entries[name] = {
        anchor: entry.anchor,
        profiles: entry.profiles,
        capability: entry.capability,
        destructive: entry.destructive,
        fields: entry.fields,
        errors: entry.errors,
      };
    }
    for (const profile of goldenProfiles) {
      for (const member of golden.profiles[profile]) {
        if (!apiEntries[member]) fail(`${tool} ${member}: in the ${profile} golden but missing from the API reference`);
      }
    }
    catalog.tools[tool] = { kind: golden.kind, profiles: goldenProfiles, entries };
  }
  for (const tool of Object.keys(api.tools)) {
    if (!goldens[tool]) fail(`tool ${tool} is in the API reference but has no golden`);
  }
  for (const code of sortStrings(Object.keys(api.errors))) catalog.errors[code] = api.errors[code];
  for (const [tool, def] of Object.entries(catalog.tools)) {
    for (const [name, entry] of Object.entries(def.entries)) {
      for (const code of entry.errors) {
        if (!catalog.errors[code]) fail(`${tool} ${name}: error ${code} is not in the error catalog`);
      }
    }
  }
  return catalog;
}

// ---------------------------------------------------------------------------------------------------
// Rendering

function renderCallShapes(catalog) {
  const out = [];
  out.push('<!-- GENERATED by claude-plugins/task-orchestrator/scripts/gen-call-shapes.mjs. Do not edit. -->');
  out.push(`<!-- Regenerate: ${REGENERATE_COMMAND} -->`);
  out.push('');
  out.push('# Call shapes');
  out.push('');
  out.push('Machine-generated reference of every 4.0 tool select and op: the profiles that expose it, its capability, its');
  out.push('fields (type and required flag) and its error codes. Prose lives in `current/docs/api-reference.md`; this file');
  out.push('carries shapes only. A write call is `{ops: [{op, ...fields, idempotencyKey?}]}`; a read call is `{select, ...fields}`.');
  for (const tool of Object.keys(catalog.tools)) {
    const def = catalog.tools[tool];
    out.push('');
    out.push(`## \`${tool}\` (${def.kind === 'ops' ? 'ops' : 'select'})`);
    out.push('');
    out.push(`Profiles: ${def.profiles.join(', ')}.`);
    const word = def.kind === 'ops' ? 'op' : 'select';
    for (const [name, entry] of Object.entries(def.entries)) {
      out.push('');
      out.push(`<a id="${entry.anchor}"></a>`);
      out.push(`### \`${tool}\` ${word} \`${name}\``);
      out.push('');
      const meta = [entry.profiles.length ? `Profiles: ${entry.profiles.join(', ')}` : 'REST only'];
      meta.push(`capability \`${entry.capability}\``);
      if (entry.destructive) meta.push('destructive');
      out.push(`${meta.join('; ')}.`);
      out.push('');
      out.push('| Field | Type | Required |');
      out.push('| --- | --- | --- |');
      for (const [field, spec] of Object.entries(entry.fields)) {
        out.push(`| \`${field}\` | ${escapePipes(spec.type)} | ${spec.required ? 'yes' : 'no'} |`);
      }
      out.push('');
      out.push(`Errors: ${entry.errors.map((c) => `\`${c}\``).join(', ')}`);
    }
  }
  return `${out.join('\n')}\n`;
}

function renderToolNames(catalog) {
  const tools = Object.keys(catalog.tools);
  const profileNames = sortStrings([...new Set(tools.flatMap((t) => catalog.tools[t].profiles))]);
  const lines = [];
  lines.push('// GENERATED by scripts/gen-call-shapes.mjs from the tool goldens and docs/api-reference.md. Do not edit.');
  lines.push(`// Regenerate: ${REGENERATE_COMMAND}`);
  lines.push('// 4.0 tool names: the basenames of current/src/test/resources/golden/tools/*.json.');
  lines.push('export const TOOLS = Object.freeze([');
  for (const t of tools) lines.push(`  '${t}',`);
  lines.push(']);');
  lines.push('');
  lines.push('// Tools visible to each profile.');
  lines.push('export const PROFILES = Object.freeze({');
  for (const p of profileNames) {
    const members = tools.filter((t) => catalog.tools[t].profiles.includes(p));
    lines.push(`  ${p}: Object.freeze([${members.map((t) => `'${t}'`).join(', ')}]),`);
  }
  lines.push('});');
  lines.push('');
  lines.push('// Tools whose call shape is ops[] (writes).');
  const writes = tools.filter((t) => catalog.tools[t].kind === 'ops');
  lines.push(`export const WRITE_TOOLS = Object.freeze([${writes.map((t) => `'${t}'`).join(', ')}]);`);
  lines.push('');
  lines.push('// Error code -> kind (permanent | transient | shedding).');
  lines.push('export const ERROR_KINDS = Object.freeze({');
  for (const [code, spec] of Object.entries(catalog.errors)) lines.push(`  ${code}: '${spec.kind}',`);
  lines.push('});');
  return `${lines.join('\n')}\n`;
}

function renderRegion(catalog) {
  const lines = [];
  lines.push(`${REGION_BEGIN} (scripts/gen-call-shapes.mjs; do not edit, run it to refresh)`);
  lines.push('const TOOL_NAMES = Object.freeze({');
  for (const t of Object.keys(catalog.tools)) lines.push(`  ${t.toUpperCase()}: '${t}',`);
  lines.push('})');
  lines.push(REGION_END);
  return lines.join('\n');
}

/** Replaces the generated region of a workflow's text; throws when the markers are missing. */
export function replaceRegion(text, region, label = 'workflow') {
  const begin = text.indexOf(REGION_BEGIN);
  const endMarker = text.indexOf(REGION_END);
  if (begin < 0 || endMarker < 0 || endMarker < begin) {
    fail(`${label}: missing ${REGION_BEGIN} / ${REGION_END} markers`);
  }
  const lineStart = text.lastIndexOf('\n', begin) + 1;
  const endOfEndLine = text.indexOf('\n', endMarker);
  const tailStart = endOfEndLine < 0 ? text.length : endOfEndLine;
  return text.slice(0, lineStart) + region + text.slice(tailStart);
}

/**
 * Renders the five managed files. Returns Map<plugin-relative posix path, full text>.
 * The two workflow files are read from pluginRoot (only their generated region changes).
 */
export function renderOutputs(catalog, { pluginRoot = DEFAULT_PLUGIN_ROOT } = {}) {
  const outputs = new Map();
  outputs.set(OUT_CALL_SHAPES, renderCallShapes(catalog));
  outputs.set(OUT_CATALOG, `${JSON.stringify(catalog, null, 2)}\n`);
  outputs.set(OUT_TOOL_NAMES, renderToolNames(catalog));
  const region = renderRegion(catalog);
  for (const rel of OUT_WORKFLOWS) {
    const text = readFileSync(join(pluginRoot, rel), 'utf-8').replace(/\r\n/g, '\n');
    outputs.set(rel, replaceRegion(text, region, rel));
  }
  return outputs;
}

/** Returns {stale}: sorted plugin-relative paths whose content on disk differs from the render or is missing. */
export function checkOutputs({ pluginRoot = DEFAULT_PLUGIN_ROOT, goldenDir = DEFAULT_GOLDEN_DIR, apiReferencePath = DEFAULT_API_REFERENCE } = {}) {
  const catalog = buildCatalog({ goldenDir, apiReferencePath });
  const outputs = renderOutputs(catalog, { pluginRoot });
  const stale = [];
  for (const [rel, text] of outputs) {
    const abs = join(pluginRoot, rel);
    const onDisk = existsSync(abs) ? readFileSync(abs, 'utf-8').replace(/\r\n/g, '\n') : null;
    if (onDisk !== text) stale.push(rel);
  }
  return { stale: sortStrings(stale) };
}

function writeOutputs({ pluginRoot, goldenDir, apiReferencePath }) {
  const catalog = buildCatalog({ goldenDir, apiReferencePath });
  const outputs = renderOutputs(catalog, { pluginRoot });
  for (const [rel, text] of outputs) {
    const abs = join(pluginRoot, rel);
    mkdirSync(dirname(abs), { recursive: true });
    writeFileSync(abs, text, 'utf-8');
  }
  return [...outputs.keys()];
}

// ---------------------------------------------------------------------------------------------------
// CLI

function parseArgs(argv) {
  const opts = { check: false, pluginRoot: DEFAULT_PLUGIN_ROOT, goldenDir: DEFAULT_GOLDEN_DIR, apiReferencePath: DEFAULT_API_REFERENCE };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const value = () => {
      if (i + 1 >= argv.length) fail(`${arg} needs a value`);
      return resolve(argv[++i]);
    };
    if (arg === '--check') opts.check = true;
    else if (arg === '--plugin-root') opts.pluginRoot = value();
    else if (arg === '--golden-dir') opts.goldenDir = value();
    else if (arg === '--api-reference') opts.apiReferencePath = value();
    else fail(`unknown argument ${arg}`);
  }
  return opts;
}

export function main(argv) {
  try {
    const opts = parseArgs(argv);
    if (opts.check) {
      const { stale } = checkOutputs(opts);
      if (stale.length === 0) return 0;
      process.stderr.write(`call shapes are stale: ${stale.join(', ')}\nRegenerate with: ${REGENERATE_COMMAND}\n`);
      return 1;
    }
    writeOutputs(opts);
    return 0;
  } catch (err) {
    process.stderr.write(`gen-call-shapes: ${err.message}\n`);
    return 2;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  process.exitCode = main(process.argv.slice(2));
}
