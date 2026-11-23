// path-identity: whether two path spellings name one file-system entry.
// Each path is resolved, then its nearest existing ancestor is canonicalised with realpathSync.native (which also fixes
// case and 8.3 short names on Windows, unlike realpathSync) and the missing remainder is appended. The strings are compared
// case-folded on win32 and darwin, exact elsewhere. Two paths that both exist are also the same entry when dev and ino match.
import { existsSync, realpathSync, statSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';

function canonical(p) {
  let base = resolve(p);
  const rest = [];
  while (!existsSync(base)) {
    const parent = dirname(base);
    if (parent === base) break;
    rest.unshift(basename(base));
    base = parent;
  }
  let real = base;
  try {
    real = realpathSync.native(base);
  } catch {
    // an ancestor that cannot be canonicalised keeps its resolved spelling
  }
  return join(real, ...rest);
}

function statIds(p) {
  try {
    const s = statSync(p, { bigint: true });
    return { dev: s.dev, ino: s.ino };
  } catch {
    return null;
  }
}

export function sameEntry(a, b) {
  const fold = process.platform === 'win32' || process.platform === 'darwin';
  const ca = canonical(a);
  const cb = canonical(b);
  if (fold ? ca.toLowerCase() === cb.toLowerCase() : ca === cb) return true;
  const ia = statIds(resolve(a));
  const ib = statIds(resolve(b));
  return ia !== null && ib !== null && ia.ino !== 0n && ia.dev === ib.dev && ia.ino === ib.ino;
}
