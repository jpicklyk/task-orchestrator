// bug-report.mjs - opt-in automatic GitHub bug reports for product defects found by a retrospective.
// Output contract 'run-wave/bug-report-v1'. All effects (gh, state file, clock, temp files) are
// injected through `deps`; run-planner.mjs's `bug-report` subcommand supplies the real ones. This
// module never reads the filesystem, the network, the clock or the environment itself.
//
// Flow per defect: qualify -> fingerprint -> scrub -> search upstream -> create | comment | skip.
// Fail closed: a scrub rejection posts nothing. Best effort: a failed gh call skips that defect.

import { defectFingerprint, defectComponent, normaliseMessage } from './retro-metrics.mjs';
import { readSection, childBlock, scalar } from '../../hooks/yaml-lite.mjs';

export const CONTRACT = 'run-wave/bug-report-v1';
export const DEFAULT_REPO = 'jpicklyk/task-orchestrator';

const DAY_MS = 24 * 60 * 60 * 1000;
const RATE_LIMIT_MS = 7 * DAY_MS;
const CAPS = { creates: 3, comments: 5, searches: 10 };
const SIGNALS = ['tool-error', 'hook-failure', 'script-failure', 'shape-mismatch'];
const ENV_CODES = ['network', 'unauthenticated', 'timeout', 'no_api', 'econnrefused', 'enotfound', 'etimedout', 'eai_again'];
const FOOTER = 'Reported automatically by task-orchestrator session-retrospective';
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
 * readGate(configText) -> {open, repo}. open only for the unquoted scalar `bug_reports: true` at the
 * block's first level; independent of `enabled`. repo defaults to DEFAULT_REPO unless it is owner/repo.
 */
export function readGate(configText) {
  const lines = githubFeedbackLines(configText);
  if (!lines) return { open: false, repo: DEFAULT_REPO };
  const content = lines.filter((l) => l.trim() !== '' && !l.trim().startsWith('#'));
  const level = Math.min(...content.map((l) => l.length - l.trimStart().length));
  const open = content.some((l) => l.length - l.trimStart().length === level && /^bug_reports\s*:\s*true\s*(#.*)?$/.test(l.trim().replace(/\r$/, '')));
  const repoRaw = scalar(lines, 'repo');
  const repo = repoRaw && /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repoRaw) ? repoRaw : DEFAULT_REPO;
  return { open, repo };
}

// ---------------------------------------------------------------------------------------------
// Qualification

/** qualifies(defect) -> null when it qualifies, else the first failing field name. */
export function qualifies(d) {
  if (!isObj(d)) return 'kind';
  if (d.kind !== 'defect') return 'kind';
  if (d.scope !== 'product') return 'scope';
  if (!SIGNALS.includes(d.signal)) return 'signal';
  for (const f of ['tool', 'op', 'code', 'message', 'quote']) if (!nonEmpty(d[f])) return f;
  if (ENV_CODES.includes(String(d.code).trim().toLowerCase())) return 'code';
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
    /(?<![\w.:/<>-])\/[\w.@-]+\/[\w.@-]+/.test(t)
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

/** typeShape(value) -> the value with every leaf replaced by its type name (keys kept, bounded). */
export function typeShape(value, depth = 0) {
  if (value === null) return 'null';
  if (Array.isArray(value)) return depth >= 4 || value.length === 0 ? [] : [typeShape(value[0], depth + 1)];
  if (isObj(value)) {
    if (depth >= 4) return 'object';
    const out = {};
    for (const k of Object.keys(value).slice(0, 30)) out[k] = typeShape(value[k], depth + 1);
    return out;
  }
  return typeof value;
}

const oneLine = (v, max) => {
  const s = str(v).replace(/\s+/g, ' ').trim();
  return s === '' ? 'not recorded' : s.slice(0, max);
};

function versionLines(versions) {
  return [`- Plugin version: ${versions.plugin || 'unknown'}`, `- Server version: ${versions.server || 'unknown'}`];
}

export function buildTitle(d, fp) {
  void fp;
  const tool = str(d.tool);
  return `[bug] ${defectComponent(tool)}: ${tool} ${str(d.op)}: ${str(d.code)} - ${normaliseMessage(d.message).slice(0, 80)}`;
}

export function buildBody(d, fp, versions) {
  const tool = str(d.tool);
  const shape = d.callShape === undefined || d.callShape === null ? null : `\`${JSON.stringify(typeShape(d.callShape))}\``;
  return [
    '## Defect',
    '',
    `- Component: ${defectComponent(tool)}`,
    ...versionLines(versions),
    `- Tool and op: ${tool} ${str(d.op)}`,
    `- Error code: ${str(d.code)}`,
    `- Message (normalised): ${normaliseMessage(d.message)}`,
    `- Call shape: ${shape || 'not recorded'}`,
    '',
    `Expected: ${oneLine(d.expected, 200)}`,
    `Actual: ${oneLine(d.actual, 200)}`,
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
 * runBugReport({defects, configText, denyNames, versions, deps}) -> the 'run-wave/bug-report-v1' document.
 * deps: {gh(args)->{status, stdout}, now()->ms, readState()->object|null, writeState(obj)->bool,
 *        writeTemp(text)->path, removeTemp(path)}
 */
export function runBugReport({ defects, configText, denyNames = [], versions = {}, deps }) {
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

  const withTemp = (text, fn) => {
    const path = deps.writeTemp(text);
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

  for (const d of Array.isArray(defects) ? defects : []) {
    const fp = isObj(d) ? fingerprintOf(d) : fingerprintOf({});
    const bad = qualifies(d);
    if (bad) {
      push(fp, d, 'skipped', null, `not-qualifying:${bad}`);
      continue;
    }
    if (seenFps.has(fp)) {
      push(fp, d, 'skipped', null, 'duplicate');
      continue;
    }
    seenFps.add(fp);

    const title = buildTitle(d, fp);
    const body = buildBody(d, fp, versions);
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
      const url = withTemp(body, (file) => {
        const base = ['issue', 'create', '--repo', gate.repo, '--title', title, '--body-file', file];
        let r = gh([...base, '--label', 'bug', '--label', 'auto-reported']);
        if (r.status !== 0) r = gh(base);
        if (r.status !== 0) return null;
        const last = r.stdout.split(/\r?\n/).map((l) => l.trim()).filter((l) => URL_RE.test(l)).pop();
        return last || null;
      });
      if (!url) {
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
    const ok = withTemp(closed ? closedComment : open, (file) => gh(['issue', 'comment', String(hit.number), '--repo', gate.repo, '--body-file', file]).status === 0);
    if (!ok) {
      push(fp, d, 'skipped', hit.url, 'gh-comment-failed');
      continue;
    }
    state.seen[fp] = { at: new Date(now).toISOString(), url: hit.url };
    stateChanged = true;
    push(fp, d, 'commented', hit.url, null);
  }

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
  return doc;
}
