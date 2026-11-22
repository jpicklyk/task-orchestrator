// checks.mjs -- deterministic graders for the engineering skill scenarios. Pure functions over an observation; no
// model judge, no file or process access (the runner builds the observation, see run-evals.mjs).
//
// A check FAILS when the failure it probes is observed. Every check names the pattern it probes; a scenario's
// failures are the distinct pattern names of its failed checks, and a scenario with none is "no failure".
//
// Observation:
//   added        { path: addedText }  lines the run added or changed, per file (git diff against the fixture)
//   newFiles     [path]               files the run created
//   output       string | null        content of the scenario's output file (review.md, audit.md), null if missing
//   answer       string               the session's final message
//
// Check shapes (all regexes are case-insensitive; `in` and `notIn` filter by file path):
//   new-file-matches  { regex, min = 1 }              fails when at least `min` new files match
//   added-matches     { regex, in, notIn, min = 1 }   fails when added text matches at least `min` times
//   added-lacks       { regex, in }                   fails when added text never matches
//   finding-missing   { regex }                       fails when no FINDING line matches
//   finding-present   { regex }                       fails when a FINDING line matches
//   verdict-is        { expect }                      fails when the VERDICT line differs
//   rank-first-matches { regex }                      fails when rank 1 names a path that does not match
//   rank-rating-not   { path, rating }                fails when the RANK line for `path` carries a rating that matches
//   rank-line-lacks   { path, regex }                 fails when the RANK line for `path` does not match `regex`
//   output-lacks      { regex }                       fails when the output file never matches
//   rank-each-matches { regex }                       fails when no RANK line exists or any ranked path does not match
//
// Slop checks (build scenarios; they read added lines only, `in` and `notIn` filter by path, min defaults to 1):
//   narrating-comments { in, notIn, min }             a comment that restates the code line below it, or a change-log
//                                                     comment ("added", "now returns", "previously")
//   swallowed-catch    { in, notIn, min }             a catch body that is empty, log-only or returns a default
//   defensive-guards   { in, notIn, min = 3 }         null/undefined, typeof-undefined, Array.isArray and ?. guards
//   unused-export      { in, notIn, min }             a new export whose name appears nowhere else in the added lines
//   modified-outside   { regex, min }                 existing files changed outside the allowed path regex (scope creep)

const re = (s) => new RegExp(s, 'i');

/** FINDING lines: `FINDING <file>:<line> | <summary>`. */
export function parseFindings(output) {
  return String(output || '').split(/\r?\n/).map((l) => l.trim()).filter((l) => /^FINDING\b/i.test(l));
}

/** VERDICT line: `VERDICT: approve` or `VERDICT: changes`; null when absent. */
export function parseVerdict(output) {
  const m = String(output || '').match(/^\s*VERDICT:\s*(approve|changes)\b/im);
  return m ? m[1].toLowerCase() : null;
}

/** RANK lines: `RANK <n> | <path> | <rating> | <reason>`. */
export function parseRanks(output) {
  const rows = [];
  for (const line of String(output || '').split(/\r?\n/)) {
    const m = line.trim().match(/^RANK\s+(\d+)\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*(.*)$/i);
    if (m) rows.push({ rank: Number(m[1]), path: m[2], rating: m[3], reason: m[4], line: line.trim() });
  }
  return rows.sort((a, b) => a.rank - b.rank);
}

function addedFor(obs, spec) {
  const inRe = spec.in ? re(spec.in) : null;
  const notRe = spec.notIn ? re(spec.notIn) : null;
  return Object.entries(obs.added || {})
    .filter(([p]) => (!inRe || inRe.test(p)) && (!notRe || !notRe.test(p)))
    .map(([, text]) => text)
    .join('\n');
}

function countMatches(text, regex) {
  const g = new RegExp(regex.source, 'gi');
  return (text.match(g) || []).length;
}

const rowFor = (obs, pathRegex) => parseRanks(obs.output).find((r) => re(pathRegex).test(r.path));

const isTestPath = (p) => /(^|\/)(tests?|__tests__)\/|\.test\.[cm]?[jt]s$/i.test(p);

/** Added files that match `in`/`notIn`, as [path, addedText]; with no `in`, test files are left out (slop graders look at production code). */
function addedEntries(obs, spec) {
  const inRe = spec.in ? re(spec.in) : null;
  const notRe = spec.notIn ? re(spec.notIn) : null;
  return Object.entries(obs.added || {}).filter(([p]) => (inRe ? inRe.test(p) : !isTestPath(p)) && (!notRe || !notRe.test(p)));
}

const STOP = new Set(['the', 'a', 'an', 'of', 'to', 'for', 'and', 'or', 'in', 'on', 'is', 'are', 'it', 'this', 'that', 'we', 'if', 'then', 'with', 'by', 'as', 'be']);

/** Lower-case word stems of a text, camelCase and snake_case split, stop words dropped. */
export function stems(text) {
  return String(text)
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .toLowerCase()
    .split(/[^a-z0-9]+/)
    .filter((w) => w.length > 1 && !STOP.has(w))
    .map((w) => w.replace(/(ing|ed|es|s)$/, ''));
}

const CHANGELOG_COMMENT = /^\s*(?:\/\/|\/\*+|\*)\s*(?:added|new:|updated|changed|removed|fixed|modified|refactored|previously|used to|now (?:uses|returns|calls|checks|handles))\b/i;
const COMMENT_LINE = /^\s*(?:\/\/+|\/\*+|\*(?!\/))\s?(.*?)\s*(?:\*\/)?$/;

/** Narrating or change-log comment lines in one file's added text; each hit is the comment line. */
export function narratingComments(text) {
  const lines = String(text).split(/\r?\n/);
  const hits = [];
  for (let i = 0; i < lines.length; i += 1) {
    const c = lines[i].match(COMMENT_LINE);
    if (!c) continue;
    if (CHANGELOG_COMMENT.test(lines[i])) { hits.push(lines[i].trim()); continue; }
    if (/^\s*(?:\/\*\*|\*)/.test(lines[i])) continue; // a doc comment documents an API, it does not narrate
    const next = lines.slice(i + 1).find((l) => l.trim() !== '');
    if (next === undefined || COMMENT_LINE.test(next)) continue;
    const words = stems(c[1]);
    if (words.length < 2) continue;
    const code = new Set(stems(next));
    if (words.filter((w) => code.has(w)).length / words.length >= 0.6) hits.push(lines[i].trim());
  }
  return hits;
}

/** Catch bodies (no nested braces) that are empty or comment-only, log-only, or only return a default. */
export function swallowedCatches(text) {
  const hits = [];
  for (const m of String(text).matchAll(/catch\s*(?:\([^)]*\))?\s*\{([^{}]*)\}/g)) {
    const body = m[1].replace(/\/\/[^\n]*|\/\*[\s\S]*?\*\//g, '').trim();
    const stmts = body.split(';').map((s) => s.trim()).filter(Boolean);
    const trivial = (s) => /^console\.\w+\(.*\)$/s.test(s) || /^return(\s+(null|undefined|false|true|0|''|""|\[\]))?$/.test(s);
    if (stmts.length === 0 || stmts.every(trivial)) hits.push(m[0].replace(/\s+/g, ' ').slice(0, 80));
  }
  return hits;
}

const GUARD = /(?:[!=]==?\s*(?:null|undefined)\b|typeof\s+[\w.]+\s*[!=]==?\s*['"]undefined['"]|Array\.isArray\(|\?\.)/g;

/** Names of new exports in added text that nothing in the added lines refers to a second time. */
export function unusedExports(obs, spec) {
  const all = Object.values(obs.added || {}).join('\n');
  const names = [];
  for (const [, text] of addedEntries(obs, spec)) {
    for (const m of text.matchAll(/export\s+(?:async\s+)?(?:function\*?|const|let|class)\s+([A-Za-z_$][\w$]*)/g)) names.push(m[1]);
  }
  return names.filter((n) => (all.match(new RegExp(`(?<![\\w$])${n.replace(/\$/g, '\\$')}(?![\\w$])`, 'g')) || []).length <= 1);
}

export const CHECK_TYPES = {
  'new-file-matches': (obs, s) => {
    const hits = (obs.newFiles || []).filter((p) => re(s.regex).test(p));
    return { failed: hits.length >= (s.min ?? 1), detail: hits.join(', ') };
  },
  'added-matches': (obs, s) => {
    const n = countMatches(addedFor(obs, s), re(s.regex));
    return { failed: n >= (s.min ?? 1), detail: `${n} match(es)` };
  },
  'added-lacks': (obs, s) => {
    const n = countMatches(addedFor(obs, s), re(s.regex));
    return { failed: n === 0, detail: n === 0 ? 'never added' : '' };
  },
  'finding-missing': (obs, s) => {
    const hit = parseFindings(obs.output).find((l) => re(s.regex).test(l));
    return { failed: !hit, detail: hit ? '' : 'no finding line matches' };
  },
  'finding-present': (obs, s) => {
    const hit = parseFindings(obs.output).find((l) => re(s.regex).test(l));
    return { failed: Boolean(hit), detail: hit || '' };
  },
  'verdict-is': (obs, s) => {
    const v = parseVerdict(obs.output);
    return { failed: v !== s.expect, detail: `verdict ${v ?? 'missing'}` };
  },
  'rank-first-matches': (obs, s) => {
    const first = parseRanks(obs.output)[0];
    return { failed: !first || !re(s.regex).test(first.path), detail: first ? `rank 1 is ${first.path}` : 'no RANK line' };
  },
  'rank-rating-not': (obs, s) => {
    const row = rowFor(obs, s.path);
    return { failed: Boolean(row && re(s.rating).test(row.rating)), detail: row ? row.line : 'path not ranked' };
  },
  'rank-line-lacks': (obs, s) => {
    const row = rowFor(obs, s.path);
    return { failed: !row || !re(s.regex).test(row.line), detail: row ? row.line : 'path not ranked' };
  },
  'output-lacks': (obs, s) => ({ failed: !re(s.regex).test(String(obs.output || '')), detail: '' }),
  'rank-each-matches': (obs, s) => {
    const ranks = parseRanks(obs.output);
    const off = ranks.filter((r) => !re(s.regex).test(r.path));
    return { failed: ranks.length === 0 || off.length > 0, detail: ranks.length === 0 ? 'no RANK line' : off.map((r) => r.path).join(', ') };
  },
  'narrating-comments': (obs, s) => {
    const hits = addedEntries(obs, s).flatMap(([, t]) => narratingComments(t));
    return { failed: hits.length >= (s.min ?? 1), detail: hits.slice(0, 3).join(' | ') };
  },
  'swallowed-catch': (obs, s) => {
    const hits = addedEntries(obs, s).flatMap(([, t]) => swallowedCatches(t));
    return { failed: hits.length >= (s.min ?? 1), detail: hits.slice(0, 3).join(' | ') };
  },
  'defensive-guards': (obs, s) => {
    const n = addedEntries(obs, s).reduce((sum, [, t]) => sum + (t.match(GUARD) || []).length, 0);
    return { failed: n >= (s.min ?? 3), detail: `${n} guard(s)` };
  },
  'unused-export': (obs, s) => {
    const names = unusedExports(obs, s);
    return { failed: names.length >= (s.min ?? 1), detail: names.join(', ') };
  },
  'modified-outside': (obs, s) => {
    const fresh = new Set(obs.newFiles || []);
    const off = Object.keys(obs.added || {}).filter((p) => !fresh.has(p) && !re(s.regex).test(p));
    return { failed: off.length >= (s.min ?? 1), detail: off.join(', ') };
  },
};

export const CHECK_IDS = Object.keys(CHECK_TYPES);

/** Grade one observation. Returns { results: [{id, pattern, failed, detail}], failures: [pattern], outcome }. */
export function grade(scenario, obs) {
  const results = [];
  if (scenario.output && obs.output == null) {
    results.push({ id: 'output', pattern: 'no output file', failed: true, detail: `${scenario.output} was not written` });
  } else {
    for (const c of scenario.checks) {
      const { failed, detail } = CHECK_TYPES[c.type](obs, c);
      results.push({ id: c.id, pattern: c.pattern, failed, detail });
    }
  }
  const failures = [...new Set(results.filter((r) => r.failed).map((r) => r.pattern))];
  return { results, failures, outcome: failures.length === 0 ? 'no failure' : 'fail' };
}
