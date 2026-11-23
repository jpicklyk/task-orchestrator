// scopePaths: the --paths list of a codebase audit, from the tracked files and the paths the user named.
// Pure: no filesystem or git access.
const TEST_NAMES = new Set(['test', 'tests', '__tests__', 'spec', 'specs']);
export const FIXTURE_NAMES = new Set(['fixtures', '__fixtures__']);

const byUnit = (a, b) => (a < b ? -1 : a > b ? 1 : 0);
const segs = (p) => p.split('/').filter((s) => s !== '');
const within = (path, dir) => dir === '.' || dir === '' || path === dir || path.startsWith(dir + '/');

function normalizeNamed(p) {
  const s = String(p).replace(/\\/g, '/').replace(/^(?:\.\/)+/, '').replace(/\/+$/, '');
  return s === '' || s === '.' ? '.' : s;
}

/** Test directories of the tracked files: no fixture trees, none inside another. Sorted by code unit. */
function testDirsOf(tracked) {
  const found = new Set();
  for (const raw of tracked) {
    const parts = segs(String(raw).replace(/\\/g, '/'));
    for (let i = 1; i < parts.length; i++) {
      if (TEST_NAMES.has(parts[i - 1].toLowerCase())) found.add(parts.slice(0, i).join('/'));
    }
  }
  const noFixtures = [...found].filter((d) => !segs(d).some((s) => FIXTURE_NAMES.has(s.toLowerCase())));
  return noFixtures.filter((d) => !noFixtures.some((o) => o !== d && d.startsWith(o + '/'))).sort(byUnit);
}

/**
 * @param {string[]} tracked tracked file paths
 * @param {string[]} [named] the paths the user named; empty or absent means the whole repository
 * @returns {string[]} no named paths: '.' then the test directories. Named paths: those paths in the given order, then the
 *   test directories related to them (inside a named path, or the mirror of a named path in a sibling test directory when
 *   tracked), sorted
 */
export function scopePaths(tracked, named = []) {
  const testDirs = testDirsOf(tracked);
  if (!named || named.length === 0) return ['.', ...testDirs];
  const given = [...new Set(named.map(normalizeNamed))];
  const trackedNorm = tracked.map((t) => String(t).replace(/\\/g, '/'));
  const hasTracked = (dir) => trackedNorm.some((t) => t.startsWith(dir + '/'));
  const added = new Set();
  for (const t of testDirs) {
    const tParts = segs(t);
    const p0 = tParts.slice(0, -1).join('/');
    for (const n of given) {
      if (within(t, n)) {
        added.add(t);
      } else if (p0 === '' || n.startsWith(p0 + '/')) {
        const rest = segs(p0 === '' ? n : n.slice(p0.length + 1)).slice(1);
        const mirror = [t, ...rest].join('/');
        added.add(hasTracked(mirror) ? mirror : t);
      }
    }
  }
  return [...given, ...[...added].filter((p) => !given.includes(p)).sort(byUnit)];
}
