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
