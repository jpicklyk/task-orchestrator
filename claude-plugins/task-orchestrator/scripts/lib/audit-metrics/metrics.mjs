// collectMetrics: the whole audit/metrics-v1 object, built from injected IO.
//   io.git(args)    -> stdout text, or null when git fails
//   io.readFile(rel) -> Buffer, or null when the file cannot be read
// Branch-based numbers (branchComplexity, topFunctions) are approximate.
import { analyzeText } from './complexity.mjs';
import { deriveBases, extractImports, findCycles, makeModuleOf, resolveGraph } from './deps.mjs';
import { cmp, commitsByPath, computeChurn, couplingFor, fetchFrom, inWindow, isBulk, logArgs, parseLog, pickTrendCommits } from './churn.mjs';
import { extOf, loadLanguageTable } from './languages.mjs';

const round = (x, places) => Math.round(x * 10 ** places) / 10 ** places;

/** Normalised --paths entry: forward slashes, no leading ./ or trailing /; '.' becomes ''. */
export function normalizeEntry(p) {
  const s = p.replace(/\\/g, '/').replace(/^(?:\.\/)+/, '').replace(/\/+$/, '');
  return s === '.' ? '' : s;
}

const inEntry = (path, entry) => entry === '' || path === entry || path.startsWith(entry + '/');

function upperBound(sorted, v) {
  let lo = 0;
  let hi = sorted.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (sorted[mid] <= v) lo = mid + 1;
    else hi = mid;
  }
  return lo;
}

/**
 * @param {{root: string, paths: string[], since: string, recent: string, moduleDepth?: number, top?: number, maxCommitFiles?: number, langTable: object}} options
 * @param {{git: (args: string[]) => (string|null), readFile: (rel: string) => (Buffer|null)}} io
 */
export function collectMetrics(options, io) {
  const { root, since, recent, langTable } = options;
  const moduleDepth = options.moduleDepth ?? 2;
  const top = options.top ?? 10;
  const maxCommitFiles = options.maxCommitFiles ?? 40;
  const table = loadLanguageTable(langTable);
  const notes = [];

  const entries = [...new Set(options.paths.map(normalizeEntry))];
  const headOut = io.git(['rev-parse', 'HEAD']);
  const headSha = headOut && headOut.trim() !== '' ? headOut.trim() : null;

  // Universe: tracked, readable, text, non-noise files.
  const lsOut = io.git(['ls-files', '-z']);
  const tracked = lsOut ? [...new Set(lsOut.split('\0').filter((p) => p !== ''))].sort(cmp) : [];
  const scopedSet = new Set(tracked.filter((p) => entries.some((e) => inEntry(p, e)) && !table.isNoise(p)));
  const universe = [];
  const info = new Map();
  for (const path of tracked) {
    if (table.isNoise(path)) continue;
    const buf = io.readFile(path);
    if (!buf || buf.subarray(0, 8000).includes(0)) continue;
    const lang = table.langFor(path);
    const a = analyzeText(buf.toString('utf8'), lang);
    universe.push(path);
    info.set(path, {
      path,
      lang,
      ...a.summary,
      specs: scopedSet.has(path) && lang ? extractImports(a.counted, lang) : [],
    });
  }
  const universeSet = new Set(universe);
  const scoped = universe.filter((p) => scopedSet.has(p));
  for (const e of entries) {
    if (!universe.some((p) => inEntry(p, e) && scopedSet.has(p))) notes.push(`--paths entry "${e === '' ? '.' : e}" matches no files`);
  }

  // History.
  let commits = [];
  if (headSha) {
    const logOut = io.git(logArgs(fetchFrom(since)));
    commits = logOut ? parseLog(logOut) : [];
  } else notes.push('repository has no commits');
  const longCommits = inWindow(commits, since);
  if (headSha && longCommits.length === 0) notes.push(`no commits on or after ${since}`);
  const { churn, churnRecent } = computeChurn(longCommits, recent);

  // Score: percentile ranks over the universe.
  const n = universe.length;
  const churnSorted = universe.map((p) => churn.get(p) || 0).sort((a, b) => a - b);
  const complexSorted = universe.map((p) => info.get(p).indentComplexity).sort((a, b) => a - b);
  const pct = (sorted, v) => (v === 0 ? 0 : upperBound(sorted, v) / n);
  const rowOf = (p) => {
    const c = churn.get(p) || 0;
    const ic = info.get(p).indentComplexity;
    return { path: p, churn: c, churnRecent: churnRecent.get(p) || 0, indentComplexity: ic, hotspotScore: round(pct(churnSorted, c) * pct(complexSorted, ic), 4) };
  };
  const byScore = (a, b) => b.hotspotScore - a.hotspotScore || cmp(a.path, b.path);
  const universeRows = universe.map(rowOf);
  const rowByPath = new Map(universeRows.map((r) => [r.path, r]));
  const repoHotSet = universeRows.filter((r) => r.hotspotScore > 0).sort(byScore).slice(0, top);
  let hotSet = [];
  if (repoHotSet.length > 0) {
    const asc = repoHotSet.map((r) => r.churn).sort((a, b) => a - b);
    const lowerMedian = asc[Math.floor((asc.length - 1) / 2)];
    hotSet = scoped
      .map((p) => rowByPath.get(p))
      .filter((r) => r.hotspotScore > 0 && r.churn * 3 >= lowerMedian)
      .sort(byScore)
      .slice(0, top);
  }
  if (hotSet.length === 0) notes.push('hotSet is empty: no scoped file has both churn and indentation in the window');

  const files = scoped.map((p) => {
    const i = info.get(p);
    const r = rowByPath.get(p);
    return {
      path: p,
      lang: i.lang ? i.lang.name : null,
      loc: i.loc,
      churn: r.churn,
      churnRecent: r.churnRecent,
      indentComplexity: i.indentComplexity,
      maxNesting: i.maxNesting,
      branchComplexity: i.branchComplexity,
      topFunctions: i.topFunctions,
      hotspotScore: r.hotspotScore,
    };
  });

  // Coupling and trend for the hot files.
  const hotPaths = new Set(hotSet.map((r) => r.path));
  const hotCommits = commitsByPath(longCommits, hotPaths);
  const coupling = hotSet.map((r) => couplingFor(r.path, hotCommits.get(r.path), universeSet, maxCommitFiles, top));
  const bulk = longCommits.filter((c) => isBulk(c, maxCommitFiles)).length;
  if (bulk > 0) notes.push(`${bulk} bulk commit(s) naming more than ${maxCommitFiles} paths excluded from coupling`);
  const trend = hotSet.slice(0, 5).map((r) => {
    const samples = [];
    for (const c of pickTrendCommits(hotCommits.get(r.path))) {
      const out = io.git(['show', `${c.sha}:${r.path}`]);
      if (out === null) continue;
      samples.push({ sha: c.sha, date: c.date, indentComplexity: analyzeText(out, info.get(r.path).lang).summary.indentComplexity });
    }
    return { path: r.path, samples };
  });

  // Modules.
  const scopes = entries.map((e) => ({ entry: e, files: scoped.filter((p) => inEntry(p, e)) }));
  const importers = scoped.filter((p) => info.get(p).lang).map((p) => ({ path: p, lang: info.get(p).lang, specs: info.get(p).specs }));
  const bases = deriveBases({ importers, universe, scoped: scopedSet });
  const moduleOf = makeModuleOf(scopes, moduleDepth, bases);
  const graph = resolveGraph({ importers, universe, scoped: scopedSet, moduleOf });
  for (const row of files) row.module = moduleOf(row.path);
  const nodeMap = new Map();
  for (const p of scoped) {
    const id = moduleOf(p);
    const node = nodeMap.get(id) || { id, files: 0, loc: 0, fanIn: 0, fanOut: 0, instability: 0 };
    node.files += 1;
    node.loc += info.get(p).loc;
    nodeMap.set(id, node);
  }
  for (const e of graph.edges) {
    if (nodeMap.has(e.from)) nodeMap.get(e.from).fanOut += 1;
    if (nodeMap.has(e.to)) nodeMap.get(e.to).fanIn += 1;
  }
  const nodes = [...nodeMap.values()].sort((a, b) => cmp(a.id, b.id));
  for (const node of nodes) node.instability = node.fanIn + node.fanOut === 0 ? 0 : round(node.fanOut / (node.fanIn + node.fanOut), 3);

  // Languages.
  const recognised = {};
  const unrecognised = {};
  for (const p of scoped) {
    const ext = extOf(p) || '(none)';
    const bucket = info.get(p).lang ? recognised : unrecognised;
    bucket[ext] = (bucket[ext] || 0) + 1;
  }
  const sortKeys = (o) => Object.fromEntries(Object.keys(o).sort(cmp).map((k) => [k, o[k]]));

  return {
    contract: 'audit/metrics-v1',
    root,
    paths: entries.map((e) => (e === '' ? '.' : e)),
    window: { since, recent },
    generatedFrom: { headSha },
    files,
    repoHotSet,
    hotSet,
    coupling,
    trend,
    modules: { depth: moduleDepth, nodes, edges: graph.edges, cycles: findCycles(nodes.map((x) => x.id), graph.edges), unresolved: graph.unresolved },
    languages: { recognised: sortKeys(recognised), unrecognised: sortKeys(unrecognised) },
    notes,
  };
}
