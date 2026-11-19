// bug-report.mjs - opt-in automatic GitHub bug reports for product defects found by a retrospective.
// Output contract 'run-wave/bug-report-v1'. All effects (gh, state file, clock, temp files) are
// injected through `deps`; run-planner.mjs's `bug-report` subcommand supplies the real ones. This
// module never reads the filesystem, the network, the clock or the environment itself.
//
// Flow per defect: qualify -> fingerprint -> scrub -> search upstream -> create | comment | skip.
// Fail closed: a scrub rejection posts nothing. Best effort: a failed gh call skips that defect.
// A create is attempted once: a failed or timed-out create posts nothing more for that defect in the run
// and is recorded as create-unconfirmed in the state, so the next run's search finds the issue or files it.

import { defectFingerprint, defectComponent } from './retro-metrics.mjs';
import { readSection, childBlock } from '../../hooks/yaml-lite.mjs';

export const CONTRACT = 'run-wave/bug-report-v1';
export const DEFAULT_REPO = 'jpicklyk/task-orchestrator';

const DAY_MS = 24 * 60 * 60 * 1000;
const RATE_LIMIT_MS = 7 * DAY_MS;
const CAPS = { creates: 3, comments: 5, searches: 10 };
const SIGNALS = ['tool-error', 'hook-failure', 'script-failure', 'shape-mismatch'];
const ENV_CODES = ['network', 'unauthenticated', 'timeout', 'no_api', 'econnrefused', 'enotfound', 'etimedout', 'eai_again'];
const FOOTER = 'Reported automatically by task-orchestrator session-retrospective';
const PLUGIN_TOOL_RE = /^[a-z0-9][a-z0-9-]*\.mjs$/;
const OP_RE = /^[A-Za-z][A-Za-z0-9_-]{0,63}$/;
const CODE_RE = /^(exit:\d{1,3}|[A-Za-z][A-Za-z0-9_]{0,63})$/;
const KEY_RE = /^[A-Za-z_][A-Za-z0-9_-]{0,63}$/;
const URL_RE = /^https:\/\/github\.com\/.+\/issues\/\d+$/;

const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const str = (v) => (typeof v === 'string' ? v : '');
const nonEmpty = (v) => typeof v === 'string' && v.trim() !== '';

// ---------------------------------------------------------------------------------------------
// Config gate

function githubFeedbackLines(configText) {
  const ext = readSection(String(configText || ''), 'extensions', { blockOnly: true });
  if (!ext) return null;
  let node = ext;
  for (const key of ['claude-code', 'retrospective', 'github_feedback']) {
    node = childBlock(node.lines, key);
    if (!node) return null;
  }
  return node.lines;
}

/**
 * readGate(configText) -> {open, repo}. open only when the block's first level holds exactly one
 * `bug_reports` line and it is the unquoted scalar `true`; independent of `enabled`. repo is always
 * DEFAULT_REPO: a config value never redirects a bug report.
 */
export function readGate(configText) {
  const lines = githubFeedbackLines(configText);
  if (!lines) return { open: false, repo: DEFAULT_REPO };
  const content = lines.filter((l) => l.trim() !== '' && !l.trim().startsWith('#'));
  const level = Math.min(...content.map((l) => l.length - l.trimStart().length));
  const atLevel = content.filter((l) => l.length - l.trimStart().length === level && /^bug_reports\s*:/.test(l.trim()));
  const open = atLevel.length === 1 && /^bug_reports\s*:\s*true\s*(#.*)?$/.test(atLevel[0].trim().replace(/\r$/, ''));
  return { open, repo: DEFAULT_REPO };
}

// ---------------------------------------------------------------------------------------------
// Qualification

/** The catalog entry of a server tool's op: {fields, ...} or null. */
function catalogEntry(catalog, tool, op) {
  const entries = isObj(catalog) && isObj(catalog.tools) && isObj(catalog.tools[tool]) ? catalog.tools[tool].entries : null;
  return isObj(entries) && Object.prototype.hasOwnProperty.call(entries, op) && isObj(entries[op]) ? entries[op] : null;
}

/**
 * qualifies(defect, catalog, pluginTools) -> null when it qualifies, else the first failing field name.
 * Signature change: the third parameter pluginTools is new. A server tool needs its op among the catalog's
 * entries and its code among that entry's `errors` list (catalog absent: fails op). A plugin tool must be one
 * of pluginTools, the plugin's own hook and script file names that the CLI lists and injects (absent or empty:
 * fails tool), and also match PLUGIN_TOOL_RE; its op and code must match fixed identifier patterns. So a
 * posted tool, op and code is a catalog name, a shipped file name or a short identifier, never a path or URL.
 */
export function qualifies(d, catalog, pluginTools) {
  if (!isObj(d)) return 'kind';
  if (d.kind !== 'defect') return 'kind';
  if (d.scope !== 'product') return 'scope';
  if (!SIGNALS.includes(d.signal)) return 'signal';
  for (const f of ['tool', 'op', 'code', 'message', 'quote']) if (!nonEmpty(d[f])) return f;
  if (ENV_CODES.includes(String(d.code).trim().toLowerCase())) return 'code';
  if (defectComponent(d.tool) === 'server') {
    const entry = catalogEntry(catalog, d.tool, d.op);
    if (!entry) return 'op';
    if (!Array.isArray(entry.errors) || !entry.errors.includes(d.code)) return 'code';
    return null;
  }
  if (!PLUGIN_TOOL_RE.test(d.tool) || !Array.isArray(pluginTools) || !pluginTools.includes(d.tool)) return 'tool';
  if (!OP_RE.test(d.op)) return 'op';
  if (!CODE_RE.test(d.code)) return 'code';
  return null;
}

/** fingerprintOf(defect) -> the cross-install fingerprint, never read from the input. */
export function fingerprintOf(d) {
  const tool = str(d.tool);
  return defectFingerprint({ component: defectComponent(tool), tool, op: str(d.op), code: str(d.code), message: str(d.message) });
}

// ---------------------------------------------------------------------------------------------
// Scrub

const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * scrubFindings(text, {fingerprint, denyNames}) -> sorted unique codes found: absolute-path, uuid,
 * hex-id, email, deny-name. The fingerprint itself is removed before the hex check.
 */
export function scrubFindings(text, { fingerprint = '', denyNames = [] } = {}) {
  let t = String(text === undefined || text === null ? '' : text);
  if (fingerprint) t = t.split(fingerprint).join('');
  const codes = new Set();
  if (
    /(?<![A-Za-z0-9])[A-Za-z]:[\\/]/.test(t) ||
    /\\\\[^\s\\]+\\/.test(t) ||
    /(?<![\w~])~[\\/]/.test(t) ||
    /file:\/\//i.test(t) ||
    /(?<![\w./])\/[\w.@-]+\/[\w.@-]+/.test(t)
  ) {
    codes.add('absolute-path');
  }
  if (/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i.test(t)) codes.add('uuid');
  if (/(?<![0-9a-z])[0-9a-f]{8,}(?![0-9a-z])/i.test(t)) codes.add('hex-id');
  if (/[^\s@<>()]+@[^\s@<>()]+\.[^\s@<>()]+/.test(t)) codes.add('email');
  for (const name of denyNames) {
    if (typeof name !== 'string' || name.trim().length < 3) continue;
    if (new RegExp(`(?<![\\p{L}\\p{N}])${escapeRe(name.trim())}(?![\\p{L}\\p{N}])`, 'iu').test(t)) {
      codes.add('deny-name');
      break;
    }
  }
  return [...codes].sort();
}

// ---------------------------------------------------------------------------------------------
// Title, body, comment

/** The type name of one value; nothing below an object or an array is read. */
const leafType = (v) => (v === null ? 'null' : Array.isArray(v) ? 'array' : isObj(v) ? 'object' : typeof v);
const slotType = (v) => (Array.isArray(v) ? (v.length === 0 ? [] : [leafType(v[0])]) : leafType(v));

/**
 * typeShape(value) -> the top level of value with every value replaced by its type name: identifier keys
 * only (at most 30), an object or map value reads 'object' and none of its keys is kept, an array reads
 * [type of its first element] ([] when empty). Signature change: the depth parameter is gone and nothing
 * below the top level is read (earlier versions kept nested identifier keys up to four levels).
 */
export function typeShape(value) {
  if (!isObj(value)) return slotType(value);
  const out = {};
  for (const k of Object.keys(value).filter((key) => KEY_RE.test(key)).slice(0, 30)) out[k] = slotType(value[k]);
  return out;
}

/**
 * The posted call shape: only a server tool's op has one. It keeps the entry's top-level catalog field names
 * (the head of a dotted or bracketed catalog name such as root.id or children[].ref is the top-level name
 * root or children), each as a type name; below an object, map or array field nothing is read. A plugin tool
 * has no catalog entry, so its call shape reads 'not recorded'.
 */
function callShapeText(d, catalog) {
  const entry = defectComponent(str(d.tool)) === 'server' ? catalogEntry(catalog, d.tool, d.op) : null;
  if (!entry || !isObj(d.callShape)) return 'not recorded';
  const top = new Set(Object.keys(isObj(entry.fields) ? entry.fields : {}).map((f) => f.split(/[.[]/)[0]));
  const kept = Object.fromEntries(Object.entries(d.callShape).filter(([k]) => top.has(k)));
  return '`' + JSON.stringify(typeShape(kept)) + '`';
}

function versionLines(versions) {
  return [`- Plugin version: ${versions.plugin || 'unknown'}`, `- Server version: ${versions.server || 'unknown'}`];
}

export function buildTitle(d) {
  return `[bug] ${defectComponent(str(d.tool))}: ${str(d.tool)} ${str(d.op)}: ${str(d.code)}`;
}

export function buildBody(d, fp, versions, catalog) {
  const tool = str(d.tool);
  return [
    '## Defect',
    '',
    `- Component: ${defectComponent(tool)}`,
    ...versionLines(versions),
    `- Tool and op: ${tool} ${str(d.op)}`,
    `- Error code: ${str(d.code)}`,
    `- Call shape: ${callShapeText(d, catalog)}`,
    '',
    `Fingerprint: ${fp}`,
    '',
    '---',
    FOOTER,
    ''
  ].join('\n');
}

export function buildComment(fp, versions, closed) {
  return [closed ? 'seen again after close (possible regression)' : 'seen again', '', ...versionLines(versions), '', `Fingerprint: ${fp}`, '', '---', FOOTER, ''].join('\n');
}

// ---------------------------------------------------------------------------------------------
// Versions

function semver(v) {
  const m = String(v || '').trim().replace(/^v/i, '').match(/^(\d+)\.(\d+)\.(\d+)/);
  return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null;
}

function greater(a, b) {
  const x = semver(a);
  const y = semver(b);
  if (!x || !y) return false;
  for (let i = 0; i < 3; i += 1) if (x[i] !== y[i]) return x[i] > y[i];
  return false;
}

// ---------------------------------------------------------------------------------------------
// Run

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function pickHit(list, fp) {
  const hits = (Array.isArray(list) ? list : []).filter((i) => isObj(i) && typeof i.body === 'string' && i.body.includes(fp));
  const state = (i) => String(i.state || '').toUpperCase();
  const open = hits.find((i) => state(i) === 'OPEN');
  if (open) return open;
  const closed = hits.filter((i) => state(i) === 'CLOSED');
  closed.sort((a, b) => (Date.parse(b.closedAt) || 0) - (Date.parse(a.closedAt) || 0));
  return closed[0] || null;
}

/**
 * runBugReport({defects, configText, denyNames, versions, catalog, pluginTools, deps}) -> the
 * 'run-wave/bug-report-v1' document. Signature change: pluginTools is new, the plugin's own hook and script
 * file names (null or empty: no plugin defect qualifies); see qualifies.
 * deps: {gh(args)->{status, stdout}, now()->ms, readState()->object|null, writeState(obj)->bool,
 *        writeTemp(text)->path, removeTemp(path)}
 * State: {seen: {<fingerprint>: {at, url}}}. A create that fails, times out or returns no issue URL is attempted
 * once only: the defect posts nothing more in the run (result skipped, reason gh-create-failed) and its entry is
 * {at, url: null, status: 'create-unconfirmed'}, so the next run's fingerprint search finds the issue or files it.
 * A create that never reached gh (writeTemp threw) leaves no entry.
 */
export function runBugReport({ defects, configText, denyNames = [], versions = {}, catalog = null, pluginTools = null, deps }) {
  const gate = readGate(configText);
  const doc = { contract: CONTRACT, gate: 'disabled', repo: gate.repo, results: [] };
  if (!gate.open) return doc;
  const gh = (args) => {
    try {
      const r = deps.gh(args);
      return { status: typeof r.status === 'number' ? r.status : 1, stdout: str(r.stdout) };
    } catch {
      return { status: 1, stdout: '' };
    }
  };
  if (gh(['auth', 'status']).status !== 0) {
    doc.gate = 'gh-unavailable';
    return doc;
  }
  doc.gate = 'ran';
  const names = [...denyNames];
  const login = gh(['api', 'user', '--jq', '.login']);
  if (login.status === 0 && login.stdout.trim()) names.push(login.stdout.trim());

  const stored = deps.readState();
  const state = isObj(stored) && isObj(stored.seen) ? { seen: { ...stored.seen } } : { seen: {} };
  const now = deps.now();
  const counts = { creates: 0, comments: 0, searches: 0 };
  const seenFps = new Set();
  let stateChanged = false;

  const push = (fp, d, action, url, reason) => {
    const target = url || reason || '';
    doc.results.push({
      fingerprint: fp,
      observation: isObj(d) && typeof d.observation === 'string' ? d.observation : null,
      action,
      url: url || null,
      reason: reason || null,
      line: `bug-report ${fp}: ${action} ${target}`.trimEnd()
    });
  };

  const withTemp = (text, fn, fallback) => {
    let path;
    try {
      path = deps.writeTemp(text);
    } catch {
      return fallback;
    }
    try {
      return fn(path);
    } finally {
      try {
        deps.removeTemp(path);
      } catch {
        // best effort
      }
    }
  };

  try {
    for (const d of Array.isArray(defects) ? defects : []) {
      const fp = isObj(d) ? fingerprintOf(d) : fingerprintOf({});
      const bad = qualifies(d, catalog, pluginTools);
      if (bad) {
        push(fp, d, 'skipped', null, `not-qualifying:${bad}`);
        continue;
      }
      if (seenFps.has(fp)) {
        push(fp, d, 'skipped', null, 'duplicate');
        continue;
      }
      seenFps.add(fp);

      const title = buildTitle(d);
      const body = buildBody(d, fp, versions, catalog);
      const open = buildComment(fp, versions, false);
      const closedComment = buildComment(fp, versions, true);
      const codes = new Set();
      for (const t of [title, body, open, closedComment]) for (const c of scrubFindings(t.split(FOOTER).join(''), { fingerprint: fp, denyNames: names })) codes.add(c);
      if (codes.size > 0) {
        push(fp, d, 'skipped', null, `scrub-rejected:${[...codes].sort().join(',')}`);
        continue;
      }

      if (counts.searches >= CAPS.searches) {
        push(fp, d, 'overflow', null, 'search-cap');
        continue;
      }
      counts.searches += 1;
      const search = gh(['issue', 'list', '--repo', gate.repo, '--state', 'all', '--search', `${fp} in:body`, '--json', 'number,url,state,stateReason,closedAt,labels,body', '--limit', '10']);
      const parsed = search.status === 0 ? parseJson(search.stdout) : null;
      if (!Array.isArray(parsed)) {
        push(fp, d, 'skipped', null, 'gh-search-failed');
        continue;
      }
      const hit = pickHit(parsed, fp);

      if (!hit) {
        if (counts.creates >= CAPS.creates) {
          push(fp, d, 'overflow', null, 'new-issue-cap');
          continue;
        }
        counts.creates += 1;
        let attempted = false;
        const url = withTemp(body, (file) => {
          // One attempt, never retried: a failed or timed-out create may still have filed the issue, and the
          // search index lags, so a second create here could duplicate it.
          attempted = true;
          const r = gh(['issue', 'create', '--repo', gate.repo, '--title', title, '--body-file', file, '--label', 'bug', '--label', 'auto-reported']);
          if (r.status !== 0) return null;
          const last = r.stdout.split(/\r?\n/).map((l) => l.trim()).filter((l) => URL_RE.test(l)).pop();
          return last || null;
        }, null);
        if (!url) {
          if (attempted) {
            // The next run's fingerprint search finds the issue if it exists, or files it then.
            state.seen[fp] = { at: new Date(now).toISOString(), url: null, status: 'create-unconfirmed' };
            stateChanged = true;
          }
          push(fp, d, 'skipped', null, 'gh-create-failed');
          continue;
        }
        state.seen[fp] = { at: new Date(now).toISOString(), url };
        stateChanged = true;
        push(fp, d, 'filed', url, null);
        continue;
      }

      const closed = String(hit.state || '').toUpperCase() === 'CLOSED';
      if (closed) {
        const labels = (Array.isArray(hit.labels) ? hit.labels : []).map((l) => String(isObj(l) ? l.name : l).toLowerCase());
        if (String(hit.stateReason || '').toUpperCase() === 'NOT_PLANNED' || labels.includes('wontfix')) {
          push(fp, d, 'skipped', hit.url, 'closed-not-planned');
          continue;
        }
        let releaseTag = null;
        const rel = gh(['release', 'list', '--repo', gate.repo, '--json', 'tagName,publishedAt', '--limit', '50']);
        const releases = rel.status === 0 ? parseJson(rel.stdout) : null;
        const closedAt = Date.parse(hit.closedAt);
        if (Array.isArray(releases) && Number.isFinite(closedAt)) {
          const before = releases
            .filter((r) => isObj(r) && Number.isFinite(Date.parse(r.publishedAt)) && Date.parse(r.publishedAt) <= closedAt)
            .sort((a, b) => Date.parse(b.publishedAt) - Date.parse(a.publishedAt));
          releaseTag = before.length > 0 ? before[0].tagName : null;
        }
        if (!releaseTag || !(greater(versions.plugin, releaseTag) || greater(versions.server, releaseTag))) {
          push(fp, d, 'skipped', hit.url, 'closed-no-regression');
          continue;
        }
      }

      const sighted = state.seen[fp];
      const at = isObj(sighted) ? Date.parse(sighted.at) : NaN;
      if (Number.isFinite(at) && now - at < RATE_LIMIT_MS) {
        push(fp, d, 'skipped', hit.url, 'rate-limited');
        continue;
      }
      if (counts.comments >= CAPS.comments) {
        push(fp, d, 'overflow', hit.url, 'comment-cap');
        continue;
      }
      counts.comments += 1;
      const ok = withTemp(closed ? closedComment : open, (file) => gh(['issue', 'comment', String(hit.number), '--repo', gate.repo, '--body-file', file]).status === 0, false);
      if (!ok) {
        push(fp, d, 'skipped', hit.url, 'gh-comment-failed');
        continue;
      }
      state.seen[fp] = { at: new Date(now).toISOString(), url: hit.url };
      stateChanged = true;
      push(fp, d, 'commented', hit.url, null);
    }
  } finally {
    if (stateChanged) {
      for (const [k, v] of Object.entries(state.seen)) {
        const t = isObj(v) ? Date.parse(v.at) : NaN;
        if (!Number.isFinite(t) || now - t > RATE_LIMIT_MS) delete state.seen[k];
      }
      let wrote = false;
      try {
        wrote = deps.writeState(state) === true;
      } catch {
        wrote = false;
      }
      if (!wrote) doc.stateWrite = 'failed';
    }
  }
  return doc;
}
