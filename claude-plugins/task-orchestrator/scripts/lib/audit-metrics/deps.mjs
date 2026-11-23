// Dependency graph for audit-metrics: import extraction, resolution to files, module rollup, cycles.
// Pure: no filesystem or git access. Unresolved imports are counted, never guessed.
import { posix } from 'node:path';
import { compileLang } from './languages.mjs';
import { cmp } from './churn.mjs';

/** Import specs found on counted lines (every pattern match, deduplicated per line). */
export function extractImports(counted, lang) {
  const cl = compileLang(lang);
  const specs = [];
  if (cl.importRes.length === 0) return specs;
  for (const c of counted) {
    const seen = new Set();
    for (const re of cl.importRes) {
      re.lastIndex = 0;
      let m;
      while ((m = re.exec(c.text)) !== null) {
        if (m[0] === '') re.lastIndex++;
        if (m[1] && !seen.has(m[1])) {
          seen.add(m[1]);
          specs.push(m[1]);
        }
      }
    }
  }
  return specs;
}

const dirOf = (p) => p.slice(0, Math.max(p.lastIndexOf('/'), 0));
const segs = (p) => p.split('/').filter((s) => s !== '');

/**
 * Module id of every scoped file. base(entry) = longest common directory of the scoped files under that
 * --paths entry; a module is base + the first `depth` directory segments below it.
 * @param {{entry: string, files: string[]}[]} scopes entry '' means the whole repository
 */
export function makeModuleOf(scopes, depth) {
  const bases = scopes.map((s) => {
    let common = null;
    for (const f of s.files) {
      const d = segs(dirOf(f));
      if (common === null) common = d;
      else {
        let i = 0;
        while (i < common.length && i < d.length && common[i] === d[i]) i++;
        common = common.slice(0, i);
      }
    }
    return { entry: s.entry, base: common || [] };
  });
  return (path) => {
    const owner = scopes.findIndex((s) => s.entry === '' || path === s.entry || path.startsWith(s.entry + '/'));
    const base = bases[Math.max(owner, 0)].base;
    const dir = segs(dirOf(path));
    return [...base, ...dir.slice(base.length, base.length + depth)].join('/');
  };
}

function commonPrefixLength(a, b) {
  let i = 0;
  while (i < a.length && i < b.length && a[i] === b[i]) i++;
  return i;
}

/**
 * Resolves the imports of the scoped files and rolls them up per module.
 * @param {{importers: {path: string, lang: object, specs: string[]}[], universe: string[], scoped: Set<string>, moduleOf: (p: string) => string}} args
 * @returns {{edges: {from: string, to: string, count: number}[], unresolved: number}}
 */
export function resolveGraph({ importers, universe, scoped, moduleOf }) {
  const byLast = new Map();
  const noextToFile = new Map();
  const dirs = new Map();
  const universeSet = new Set(universe);
  const add = (key, entry) => {
    if (!byLast.has(key)) byLast.set(key, []);
    byLast.get(key).push(entry);
  };
  for (const p of universe) {
    const noext = p.replace(/\.[^./]*$/, '');
    noextToFile.set(noext, p);
    add(noext.slice(noext.lastIndexOf('/') + 1), { full: '/' + noext, path: p, rep: p, scoped: scoped.has(p) });
    const d = dirOf(p);
    if (d !== '') {
      let e = dirs.get(d);
      if (!e) {
        e = { full: '/' + d, path: d, rep: null, scoped: false };
        dirs.set(d, e);
        add(d.slice(d.lastIndexOf('/') + 1), e);
      }
      if (scoped.has(p) && !e.scoped) {
        e.scoped = true;
        e.rep = p;
      }
    }
  }
  const edges = new Map();
  let unresolved = 0;
  const cache = new Map();

  const pick = (cands, importerSegs) => {
    if (cands.length === 1) return cands[0];
    let best = null;
    let bestLen = -1;
    let tied = false;
    for (const c of cands) {
      const n = commonPrefixLength(segs(c.path), importerSegs);
      if (n > bestLen) {
        best = c;
        bestLen = n;
        tied = false;
      } else if (n === bestLen) tied = true;
    }
    return tied ? null : best;
  };

  const resolvePackage = (parts, importerSegs) => {
    for (let len = parts.length; len >= Math.min(2, parts.length); len--) {
      const suffix = '/' + parts.slice(0, len).join('/');
      const cands = (byLast.get(parts[len - 1]) || []).filter((e) => e.full.endsWith(suffix));
      if (cands.length === 0) continue;
      const inScope = cands.filter((e) => e.scoped);
      if (inScope.length === 0) return 'outside';
      const chosen = pick(inScope, importerSegs);
      return chosen ? moduleOf(chosen.rep) : 'unresolved';
    }
    return 'unresolved';
  };

  const resolveRelative = (spec, importerDir, exts) => {
    const target = posix.normalize(posix.join(importerDir, spec));
    if (target.startsWith('..')) return 'unresolved';
    const tries = [target, ...exts.map((x) => target + x), ...exts.map((x) => `${target}/index${x}`)];
    const hit = tries.find((t) => universeSet.has(t));
    if (!hit) return 'unresolved';
    return scoped.has(hit) ? moduleOf(hit) : 'outside';
  };

  const resolveOne = (imp, spec) => {
    const cl = compileLang(imp.lang);
    const importerDir = dirOf(imp.path);
    if (cl.importKind === 'relative') {
      if (!spec.startsWith('.')) return 'unresolved';
      return resolveRelative(spec, importerDir, cl.extensions);
    }
    if (cl.importKind !== 'package') return 'unresolved';
    const dots = /^\.+/.exec(spec);
    if (dots && cl.separator === '.') {
      let base = importerDir;
      for (let i = 1; i < dots[0].length; i++) base = dirOf(base);
      const rest = segs(spec.slice(dots[0].length).split('.').join('/'));
      const target = [...segs(base), ...rest].join('/');
      const file = noextToFile.get(target);
      if (file) return scoped.has(file) ? moduleOf(file) : 'outside';
      const d = dirs.get(target);
      if (d) return d.scoped ? moduleOf(d.rep) : 'outside';
      return 'unresolved';
    }
    const parts = spec.split(cl.separator).filter((s) => s !== '');
    while (parts.length && parts[parts.length - 1] === '*') parts.pop();
    if (parts.length === 0) return 'unresolved';
    return resolvePackage(parts, segs(importerDir));
  };

  for (const imp of importers) {
    const from = moduleOf(imp.path);
    for (const spec of imp.specs) {
      const key = `${imp.path}\0${spec}`;
      let r = cache.get(key);
      if (r === undefined) {
        r = resolveOne(imp, spec);
        cache.set(key, r);
      }
      if (r === 'outside') continue;
      if (r === 'unresolved') {
        unresolved++;
        continue;
      }
      if (r === from) continue;
      const ek = `${from}\0${r}`;
      edges.set(ek, (edges.get(ek) || 0) + 1);
    }
  }
  const list = [...edges].map(([k, count]) => {
    const [from, to] = k.split('\0');
    return { from, to, count };
  });
  list.sort((a, b) => cmp(a.from, b.from) || cmp(a.to, b.to));
  return { edges: list, unresolved };
}

/** Strongly connected components of size >= 2 (Tarjan); ids sorted within, components by first id. */
export function findCycles(ids, edges) {
  const adj = new Map(ids.map((id) => [id, []]));
  for (const e of edges) {
    if (adj.has(e.from) && adj.has(e.to)) adj.get(e.from).push(e.to);
  }
  const index = new Map();
  const low = new Map();
  const onStack = new Set();
  const stack = [];
  const cycles = [];
  let counter = 0;
  const visit = (v) => {
    index.set(v, counter);
    low.set(v, counter);
    counter++;
    stack.push(v);
    onStack.add(v);
    for (const w of adj.get(v)) {
      if (!index.has(w)) {
        visit(w);
        low.set(v, Math.min(low.get(v), low.get(w)));
      } else if (onStack.has(w)) low.set(v, Math.min(low.get(v), index.get(w)));
    }
    if (low.get(v) === index.get(v)) {
      const comp = [];
      let w;
      do {
        w = stack.pop();
        onStack.delete(w);
        comp.push(w);
      } while (w !== v);
      if (comp.length >= 2) cycles.push(comp.sort(cmp));
    }
  };
  for (const id of ids) if (!index.has(id)) visit(id);
  return cycles.sort((a, b) => cmp(a[0], b[0]));
}
