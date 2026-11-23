// Language table for audit-metrics: the data lives in languages.json (or a --lang-table replacement);
// this module indexes it and compiles its regex sources once per language.
import { readFileSync } from 'node:fs';

/** Lower-case extension of a path including the dot, '' when the basename has none (a dotfile has none). */
export function extOf(path) {
  const base = path.slice(path.lastIndexOf('/') + 1);
  const i = base.lastIndexOf('.');
  return i <= 0 ? '' : base.slice(i).toLowerCase();
}

/**
 * Indexes a parsed language table.
 * @param {object} obj parsed languages.json: {languages: [...], noise: {basenames, suffixes, segments}}
 * @returns {{langFor: (path: string) => (object|null), langByName: (name: string) => (object|null), isNoise: (path: string) => boolean}}
 */
export function loadLanguageTable(obj) {
  const languages = Array.isArray(obj && obj.languages) ? obj.languages : [];
  const noise = (obj && obj.noise) || {};
  const byExt = new Map();
  const byName = new Map();
  for (const lang of languages) {
    byName.set(lang.name, lang);
    for (const ext of lang.extensions || []) if (!byExt.has(ext.toLowerCase())) byExt.set(ext.toLowerCase(), lang);
  }
  const basenames = new Set(noise.basenames || []);
  const suffixes = noise.suffixes || [];
  const segments = new Set(noise.segments || []);
  return {
    langFor: (path) => byExt.get(extOf(path)) || null,
    langByName: (name) => byName.get(name) || null,
    isNoise(path) {
      const parts = path.split('/');
      const base = parts[parts.length - 1];
      if (basenames.has(base)) return true;
      if (suffixes.some((s) => base.endsWith(s))) return true;
      for (let i = 0; i < parts.length - 1; i++) if (segments.has(parts[i])) return true;
      return false;
    },
  };
}

let shipped = null;
/** The table shipped next to this module (languages.json), loaded once. */
export function shippedLanguageTable() {
  if (!shipped) shipped = loadLanguageTable(JSON.parse(readFileSync(new URL('./languages.json', import.meta.url), 'utf8')));
  return shipped;
}

const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const compiled = new WeakMap();

/** The regexes and tokens of one language object, built once. */
export function compileLang(lang) {
  let c = compiled.get(lang);
  if (c) return c;
  const block = lang.blockComment && lang.blockComment.start && lang.blockComment.end ? lang.blockComment : null;
  c = {
    lineComments: lang.lineComment || [],
    blockStart: block ? block.start : null,
    blockEnd: block ? block.end : null,
    style: lang.blockStyle || 'brace',
    branchRes: (lang.branchTokens || []).map((t) => new RegExp(/^\w+$/.test(t) ? `\\b${t}\\b` : escapeRe(t), 'g')),
    functionRes: (lang.functionPatterns || []).map((p) => new RegExp(p)),
    importRes: (lang.importPatterns || []).map((p) => new RegExp(p, 'g')),
    importKind: lang.importKind || null,
    separator: lang.importSeparator || '.',
    extensions: lang.extensions || [],
  };
  compiled.set(lang, c);
  return c;
}

/**
 * Compiles every language of a parsed table now, so a bad regex source surfaces before any file is read.
 * @param {object} obj parsed languages.json
 * @throws {Error} naming the language whose patterns do not compile
 */
export function validateLanguageTable(obj) {
  const languages = Array.isArray(obj && obj.languages) ? obj.languages : [];
  for (const lang of languages) {
    try {
      compileLang(lang);
    } catch (e) {
      throw new Error(`language ${lang && lang.name}: ${e.message}`);
    }
  }
}
