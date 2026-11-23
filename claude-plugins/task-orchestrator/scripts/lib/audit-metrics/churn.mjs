// History measures for audit-metrics: log parsing, churn windows, change coupling, trend samples.
// Pure: every function takes parsed commits, none runs git.

export const cmp = (a, b) => (a < b ? -1 : a > b ? 1 : 0);

/** git log arguments for one pass over the history, newest first, with NUL-separated paths. */
export function logArgs(sinceDate) {
  return ['-c', 'core.quotepath=off', 'log', '--no-merges', '--no-renames', '--name-only', '-z', `--since=${sinceDate}`, '--format=%x01%H%x09%cs'];
}

/** The date two days before a YYYY-MM-DD date: the log is fetched from there and filtered on %cs. */
export function fetchFrom(since) {
  const [y, m, d] = since.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d - 2)).toISOString().slice(0, 10);
}

/**
 * Parses logArgs output.
 * @returns {{sha: string, date: string, paths: string[]}[]} newest first, paths deduplicated
 */
export function parseLog(out) {
  const commits = [];
  for (const chunk of out.split('\u0001')) {
    if (chunk === '') continue;
    const nul = chunk.indexOf('\0');
    const header = nul < 0 ? chunk : chunk.slice(0, nul);
    const [sha, date] = header.split('\t');
    if (!sha || !date) continue;
    const rest = nul < 0 ? '' : chunk.slice(nul + 1).replace(/^\n/, '');
    commits.push({ sha, date: date.trim(), paths: [...new Set(rest.split('\0').filter((p) => p !== ''))] });
  }
  return commits;
}

/** Long-window commits: committer date on or after since. */
export function inWindow(commits, since) {
  return commits.filter((c) => c.date >= since);
}

/** Per-path commit counts in the long window and in the recent window. */
export function computeChurn(longCommits, recent) {
  const churn = new Map();
  const churnRecent = new Map();
  for (const c of longCommits) {
    for (const p of c.paths) {
      churn.set(p, (churn.get(p) || 0) + 1);
      if (c.date >= recent) churnRecent.set(p, (churnRecent.get(p) || 0) + 1);
    }
  }
  return { churn, churnRecent };
}

/** A bulk commit names more than maxFiles paths (noise files included). */
export const isBulk = (commit, maxFiles) => commit.paths.length > maxFiles;

/** Commits of the list that name any of the wanted paths, grouped per path (list order kept). */
export function commitsByPath(commits, wanted) {
  const map = new Map([...wanted].map((p) => [p, []]));
  for (const c of commits) for (const p of c.paths) if (map.has(p)) map.get(p).push(c);
  return map;
}

/** Coupling of one file: the other universe files changed in the same non-bulk commits. */
export function couplingFor(path, fileCommits, universe, maxFiles, top) {
  const usable = fileCommits.filter((c) => !isBulk(c, maxFiles));
  const counts = new Map();
  for (const c of usable) {
    for (const q of c.paths) if (q !== path && universe.has(q)) counts.set(q, (counts.get(q) || 0) + 1);
  }
  const partners = [...counts].map(([p, count]) => ({ path: p, count })).sort((a, b) => b.count - a.count || cmp(a.path, b.path));
  return { path, commits: usable.length, partners: partners.slice(0, top) };
}

/** Up to 6 commits, oldest first: all when there are at most 6, else indices round(i(k-1)/5). */
export function pickTrendCommits(fileCommitsNewestFirst) {
  const oldest = [...fileCommitsNewestFirst].reverse();
  const k = oldest.length;
  if (k <= 6) return oldest;
  const picks = [];
  for (let i = 0; i <= 5; i++) picks.push(oldest[Math.round((i * (k - 1)) / 5)]);
  return picks;
}
