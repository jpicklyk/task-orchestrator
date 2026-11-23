// Dependency graph for audit-metrics: import extraction, module base derivation, resolution to files, module rollup, cycles.
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

const commonDir = (files) => {
  let common = null;
  for (const f of files) {
    const d = segs(dirOf(f));
    if (common === null) common = d;
    else {
      let i = 0;
      while (i < common.length && i < d.length && common[i] === d[i]) i++;
      common = common.slice(0, i);
    }
  }
  return common || [];
};

const isUnder = (dirSegs, baseSegs) => baseSegs.length <= dirSegs.length && baseSegs.every((s, i) => dirSegs[i] === s);

/**
 * Module id of every scoped file. base(file) = the deepest of `groupBases` (see deriveBases) that is an
 * ancestor-or-self of the file's directory, else the longest common directory of the scoped files under the
 * file's --paths entry; a module is base + the first `depth` directory segments below it.
 * @param {{entry: string, files: string[]}[]} scopes entry '' means the whole repository
 * @param {number} depth
 * @param {string[][]} [groupBases] base directories as segment arrays
 */
export function makeModuleOf(scopes, depth, groupBases = []) {
  const entryBases = scopes.map((s) => commonDir(s.files));
  const ordered = [...groupBases].sort((a, b) => b.length - a.length);
  return (path) => {
    const dir = segs(dirOf(path));
    let base = ordered.find((b) => isUnder(dir, b));
    if (!base) {
      const owner = scopes.findIndex((s) => s.entry === '' || path === s.entry || path.startsWith(s.entry + '/'));
      base = entryBases[Math.max(owner, 0)] || [];
    }
    return [...base, ...dir.slice(base.length, base.length + depth)].join('/');
  };
}

function commonPrefixLength(a, b) {
  let i = 0;
  while (i < a.length && i < b.length && a[i] === b[i]) i++;
  return i;
}

/** Lookup tables over the universe shared by base derivation and graph resolution. */
function buildIndex(universe, scoped) {
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
  return { byLast, noextToFile, dirs, universeSet, scoped };
}

function pick(cands, importerSegs) {
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
}

/** Qualified-name match by path suffix: {kind: 'hit', chosen, suffix} | {kind: 'outside'} | {kind: 'unresolved'}. */
function matchPackage(index, parts, importerSegs) {
  for (let len = parts.length; len >= Math.min(2, parts.length); len--) {
    const suffix = '/' + parts.slice(0, len).join('/');
    const cands = (index.byLast.get(parts[len - 1]) || []).filter((e) => e.full.endsWith(suffix));
    if (cands.length === 0) continue;
    const inScope = cands.filter((e) => e.scoped);
    if (inScope.length === 0) return { kind: 'outside' };
    const chosen = pick(inScope, importerSegs);
    return chosen ? { kind: 'hit', chosen, suffix } : { kind: 'unresolved' };
  }
  return { kind: 'unresolved' };
}

/** Relative-path match: {kind: 'hit', file, scoped} | {kind: 'unresolved'}. */
function matchRelative(index, spec, importerDir, exts) {
  const target = posix.normalize(posix.join(importerDir, spec));
  if (target.startsWith('..')) return { kind: 'unresolved' };
  const tries = [target, ...exts.map((x) => target + x), ...exts.map((x) => `${target}/index${x}`)];
  const hit = tries.find((t) => index.universeSet.has(t));
  return hit ? { kind: 'hit', file: hit, scoped: index.scoped.has(hit) } : { kind: 'unresolved' };
}

/**
 * Dotted relative match (Python 'from .b import x', 'from ..pkg import y'): {kind: 'hit', file, scoped} | {kind: 'unresolved'}.
 * A directory hit names the directory's representative file.
 */
function matchDotted(index, spec, importerDir) {
  const dots = /^\.+/.exec(spec);
  let base = importerDir;
  for (let i = 1; i < dots[0].length; i++) base = dirOf(base);
  const rest = segs(spec.slice(dots[0].length).split('.').join('/'));
  const target = [...segs(base), ...rest].join('/');
  const file = index.noextToFile.get(target);
  if (file) return { kind: 'hit', file, scoped: index.scoped.has(file) };
  const d = index.dirs.get(target);
  if (d) return { kind: 'hit', file: d.rep, scoped: d.scoped };
  return { kind: 'unresolved' };
}

const packagePartsOf = (cl, spec) => {
  const parts = spec.split(cl.separator).filter((s) => s !== '');
  while (parts.length && parts[parts.length - 1] === '*') parts.pop();
  return parts;
};

/**
 * Module base directories derived from the resolved imports of the scoped files.
 * Package languages: an import whose qualified name matches the end of a scoped target's path gives a source
 * root (the target path minus that suffix); the scoped package-language files under the deepest such root form
 * a group. Relative languages: a connected component (size >= 2) of the resolved relative imports between
 * scoped files forms a group. A group's base is the longest common directory of its files.
 * @param {{importers: {path: string, lang: object, specs: string[]}[], universe: string[], scoped: Set<string>}} args
 * @returns {string[][]} base directories as segment arrays, deduplicated
 */
export function deriveBases({ importers, universe, scoped }) {
  const index = buildIndex(universe, scoped);
  const roots = new Set();
  const parent = new Map();
  const find = (x) => {
    let r = x;
    while (parent.get(r) !== r) r = parent.get(r);
    return r;
  };
  const union = (a, b) => {
    if (!parent.has(a)) parent.set(a, a);
    if (!parent.has(b)) parent.set(b, b);
    const ra = find(a);
    const rb = find(b);
    if (ra !== rb) parent.set(ra, rb);
  };
  for (const imp of importers) {
    const cl = compileLang(imp.lang);
    const importerDir = dirOf(imp.path);
    for (const spec of imp.specs) {
      if (cl.importKind === 'relative') {
        if (!spec.startsWith('.')) continue;
        const m = matchRelative(index, spec, importerDir, cl.extensions);
        if (m.kind === 'hit' && m.scoped && m.file !== imp.path) union(imp.path, m.file);
      } else if (cl.importKind === 'package') {
        if (/^\.+/.test(spec) && cl.separator === '.') {
          const m = matchDotted(index, spec, importerDir);
          if (m.kind === 'hit' && m.scoped && m.file !== imp.path) union(imp.path, m.file);
          continue;
        }
        const parts = packagePartsOf(cl, spec);
        if (parts.length === 0) continue;
        const m = matchPackage(index, parts, segs(importerDir));
        if (m.kind === 'hit') roots.add(m.chosen.full.slice(0, m.chosen.full.length - m.suffix.length).replace(/^\//, ''));
      }
    }
  }
  const groups = new Map();
  const addTo = (key, file) => {
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(file);
  };
  const rootList = [...roots].map((r) => ({ r, s: segs(r) })).sort((a, b) => b.s.length - a.s.length);
  for (const imp of importers) {
    if (compileLang(imp.lang).importKind !== 'package') continue;
    const dir = segs(dirOf(imp.path));
    const owner = rootList.find((x) => isUnder(dir, x.s));
    if (owner) addTo(`p\0${owner.r}`, imp.path);
  }
  for (const f of parent.keys()) addTo(`r\0${find(f)}`, f);
  const bases = new Map();
  for (const [key, files] of groups) {
    if (key.startsWith('r\0') && files.length < 2) continue;
    const base = commonDir(files);
    bases.set(base.join('/'), base);
  }
  return [...bases.values()];
}

/**
 * Resolves the imports of the scoped files and rolls them up per module.
 * @param {{importers: {path: string, lang: object, specs: string[]}[], universe: string[], scoped: Set<string>, moduleOf: (p: string) => string}} args
 * @returns {{edges: {from: string, to: string, count: number}[], unresolved: number}}
 */
export function resolveGraph({ importers, universe, scoped, moduleOf }) {
  const index = buildIndex(universe, scoped);
  const edges = new Map();
  let unresolved = 0;
  const cache = new Map();

  const resolvePackage = (parts, importerSegs) => {
    const m = matchPackage(index, parts, importerSegs);
    return m.kind === 'hit' ? moduleOf(m.chosen.rep) : m.kind;
  };

  const resolveRelative = (spec, importerDir, exts) => {
    const m = matchRelative(index, spec, importerDir, exts);
    if (m.kind !== 'hit') return 'unresolved';
    return m.scoped ? moduleOf(m.file) : 'outside';
  };

  const resolveOne = (imp, spec) => {
    const cl = compileLang(imp.lang);
    const importerDir = dirOf(imp.path);
    if (cl.importKind === 'relative') {
      if (!spec.startsWith('.')) return 'unresolved';
      return resolveRelative(spec, importerDir, cl.extensions);
    }
    if (cl.importKind !== 'package') return 'unresolved';
    if (/^\.+/.test(spec) && cl.separator === '.') {
      const m = matchDotted(index, spec, importerDir);
      if (m.kind !== 'hit') return 'unresolved';
      return m.scoped ? moduleOf(m.file) : 'outside';
    }
    const parts = packagePartsOf(cl, spec);
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
