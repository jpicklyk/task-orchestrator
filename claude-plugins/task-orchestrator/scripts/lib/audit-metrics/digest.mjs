// buildDigest: the audit/digest-v1 object, a pure function of an audit/metrics-v1 object (no IO, no git).
// Candidates are ranked refactoring leads: hot files, import cycles, unstable modules and cross-module co-change.
import { cmp, coChangePairs } from './churn.mjs';

export const DIGEST_CONTRACT = 'audit/digest-v1';
export const DEFAULT_CANDIDATES = 12;

const round4 = (x) => Math.round(x * 1e4) / 1e4;

/**
 * @param {object} metrics an audit/metrics-v1 object (never modified)
 * @param {{top?: number, candidates?: number}} [options]
 */
export function buildDigest(metrics, { top = 10, candidates = DEFAULT_CANDIDATES } = {}) {
  if (!metrics || metrics.contract !== 'audit/metrics-v1') throw new TypeError('buildDigest needs an audit/metrics-v1 object');
  const rowByPath = new Map(metrics.files.map((r) => [r.path, r]));
  const moduleOf = (p) => (rowByPath.has(p) ? rowByPath.get(p).module : null);

  const hotSet = metrics.hotSet.map((r) => {
    const f = rowByPath.get(r.path);
    return {
      path: r.path,
      churn: r.churn,
      churnRecent: r.churnRecent,
      complexity: r.indentComplexity,
      hotspotScore: r.hotspotScore,
      module: f ? f.module : null,
    };
  });
  const topComplexity = metrics.files
    .filter((r) => r.indentComplexity > 0)
    .sort((a, b) => b.indentComplexity - a.indentComplexity || cmp(a.path, b.path))
    .slice(0, top)
    .map((r) => ({ path: r.path, complexity: r.indentComplexity, module: r.module }));
  const cycles = metrics.modules.cycles.map((c) => [...c]);
  const unstableModules = metrics.modules.nodes
    .filter((n) => n.fanIn >= 1 && n.instability > 0)
    .sort((a, b) => b.instability - a.instability || b.fanIn - a.fanIn || cmp(a.id, b.id))
    .slice(0, top)
    .map((n) => ({ id: n.id, instability: n.instability, fanIn: n.fanIn, fanOut: n.fanOut }));
  const coupling = metrics.coupling.map((c) => ({ path: c.path, partners: c.partners.map((q) => ({ path: q.path, count: q.count })) }));
  const trend = metrics.trend.map((t) => ({ path: t.path, complexities: t.samples.map((s) => s.indentComplexity) }));

  const hottest = (mods) => {
    let best = null;
    for (const r of metrics.files) {
      if (!mods.includes(r.module)) continue;
      if (!best || r.hotspotScore > best.hotspotScore || (r.hotspotScore === best.hotspotScore && cmp(r.path, best.path) < 0)) best = r;
    }
    return { path: best ? best.path : null, score: best ? best.hotspotScore : 0 };
  };

  const all = [];
  for (const r of metrics.hotSet) {
    const f = rowByPath.get(r.path);
    all.push({
      kind: 'hot-file',
      subject: [r.path],
      facts: {
        churn: r.churn,
        churnRecent: r.churnRecent,
        complexity: r.indentComplexity,
        branchComplexity: f ? f.branchComplexity : null,
        hotspotScore: r.hotspotScore,
      },
      score: r.hotspotScore,
    });
  }
  for (const ids of cycles) {
    const h = hottest(ids);
    all.push({
      kind: 'cycle',
      subject: [...ids],
      facts: {
        fileCount: metrics.files.filter((r) => ids.includes(r.module)).length,
        hottestPath: h.path,
        edges: metrics.modules.edges.filter((e) => ids.includes(e.from) && ids.includes(e.to)).length,
      },
      score: h.score,
    });
  }
  for (const u of unstableModules) {
    const h = hottest([u.id]);
    const node = metrics.modules.nodes.find((n) => n.id === u.id);
    all.push({
      kind: 'unstable-module',
      subject: [u.id],
      facts: { instability: u.instability, fanIn: u.fanIn, fanOut: u.fanOut, files: node.files, hottestPath: h.path },
      score: round4(u.instability * h.score),
    });
  }
  const hotScore = new Map(metrics.hotSet.map((r) => [r.path, r.hotspotScore]));
  for (const p of coChangePairs(metrics.coupling, moduleOf)) {
    all.push({
      kind: 'co-change',
      subject: [p.path, ...p.partners.map((q) => q.path)],
      facts: { commits: p.commits, partners: p.partners },
      score: round4((p.partners[0].count / p.commits) * (hotScore.get(p.path) ?? 0)),
    });
  }
  const ranked = all
    .map((c) => ({ candidateId: `${c.kind}:${c.subject[0]}`, ...c }))
    .sort((a, b) => b.score - a.score || cmp(a.candidateId, b.candidateId))
    .slice(0, candidates);

  return {
    contract: DIGEST_CONTRACT,
    window: { ...metrics.window },
    headSha: metrics.generatedFrom.headSha,
    metricsPath: null,
    hotSet,
    topComplexity,
    cycles,
    unstableModules,
    coupling,
    trend,
    notes: [...metrics.notes],
    candidates: ranked,
  };
}

export const serializeDigest = (d) => JSON.stringify(d) + '\n';
