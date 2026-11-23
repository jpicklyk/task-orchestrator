// Per-file measures for audit-metrics: counted lines, indentation complexity, branch tokens, functions.
// Pure: text in, numbers out. Branch numbers are approximate (no string or regex stripping).
import { compileLang, shippedLanguageTable } from './languages.mjs';

function leadingColumns(s) {
  let c = 0;
  for (let i = 0; i < s.length; i++) {
    if (s[i] === ' ') c += 1;
    else if (s[i] === '\t') c += 4;
    else break;
  }
  return c;
}

function countBranches(text, cl) {
  let n = 0;
  for (const re of cl.branchRes) {
    re.lastIndex = 0;
    const m = text.match(re);
    if (m) n += m.length;
  }
  return n;
}

/**
 * Splits text into lines and keeps the counted ones (non-blank, non-comment).
 * @returns {{line: number, text: string, columns: number}[]} line is 1-based
 */
export function countedLines(text, lang) {
  const cl = lang ? compileLang(lang) : null;
  const lines = text.replace(/^﻿/, '').split(/\r\n|\n/);
  const out = [];
  let inBlock = false;
  for (let i = 0; i < lines.length; i++) {
    const raw = lines[i];
    const trimmed = raw.trim();
    if (trimmed === '') continue;
    let code;
    if (inBlock) {
      const e = raw.indexOf(cl.blockEnd);
      if (e < 0) code = false;
      else {
        inBlock = false;
        code = raw.slice(e + cl.blockEnd.length).trim() !== '';
      }
    } else if (!cl) {
      code = true;
    } else if (cl.lineComments.some((t) => trimmed.startsWith(t))) {
      code = false;
    } else if (cl.blockStart && trimmed.startsWith(cl.blockStart)) {
      const e = trimmed.indexOf(cl.blockEnd, cl.blockStart.length);
      if (e < 0) {
        inBlock = true;
        code = false;
      } else {
        code = trimmed.slice(e + cl.blockEnd.length).trim() !== '';
      }
    } else {
      code = true;
      if (cl.blockStart) {
        const s = raw.indexOf(cl.blockStart);
        if (s >= 0 && raw.indexOf(cl.blockEnd, s + cl.blockStart.length) < 0) inBlock = true;
      }
    }
    if (code) out.push({ line: i + 1, text: raw, columns: leadingColumns(raw) });
  }
  return out;
}

function indentUnit(cs) {
  const freq = new Map();
  for (let i = 1; i < cs.length; i++) {
    const d = cs[i].columns - cs[i - 1].columns;
    if (d > 0) freq.set(d, (freq.get(d) || 0) + 1);
  }
  let best = 4;
  let bestN = 0;
  for (const [d, n] of freq) {
    if (n > bestN || (n === bestN && d < best)) {
      best = d;
      bestN = n;
    }
  }
  return best;
}

function count(text, ch) {
  let n = 0;
  for (let i = 0; i < text.length; i++) if (text[i] === ch) n++;
  return n;
}

/** The last counted line before the next counted line at or left of the declaration's columns. */
function indentEnd(cs, ci) {
  for (let j = ci + 1; j < cs.length; j++) if (cs[j].columns <= cs[ci].columns) return cs[j - 1].line;
  return cs[cs.length - 1].line;
}

function loneEquals(text, k) {
  const prev = text[k - 1];
  const next = text[k + 1];
  return !(prev === '=' || prev === '!' || prev === '<' || prev === '>' || next === '=' || next === '>');
}

/**
 * Brace style: from the end of the match, the first `{` (body) or a lone `=` or `;` (no block) outside the
 * parameter list. A line after the parameter list that returns to the declaration's indentation without
 * opening a brace ends the search (a declaration without a body).
 */
function braceEnd(cs, ci, matchEnd, matchText) {
  let paren = count(matchText, '(') - count(matchText, ')');
  let started = false;
  let depth = 0;
  for (let j = ci; j < cs.length; j++) {
    const text = cs[j].text;
    if (j > ci && !started && paren <= 0 && cs[j].columns <= cs[ci].columns && text.trim()[0] !== '{') return indentEnd(cs, ci);
    for (let k = j === ci ? matchEnd : 0; k < text.length; k++) {
      const ch = text[k];
      if (started) {
        if (ch === '{') depth++;
        else if (ch === '}') {
          depth--;
          if (depth === 0) return cs[j].line;
        }
      } else if (ch === '(') paren++;
      else if (ch === ')') paren--;
      else if (paren <= 0) {
        if (ch === '{') {
          started = true;
          depth = 1;
        } else if (ch === ';' || (ch === '=' && loneEquals(text, k))) return indentEnd(cs, ci);
      }
    }
  }
  return started ? cs[cs.length - 1].line : indentEnd(cs, ci);
}

const END_OPENERS = new Set(['def', 'class', 'module', 'if', 'unless', 'while', 'until', 'for', 'case', 'begin']);

/** End style: openers (a keyword at line start, `do` anywhere) against `end` words. */
function endStyleEnd(cs, ci) {
  let depth = 0;
  for (let j = ci; j < cs.length; j++) {
    const t = cs[j].text.trim();
    const first = /^\w+/.exec(t);
    const startsOpener = first && END_OPENERS.has(first[0]);
    const hasDo = /\bdo\b/.test(t);
    depth += (startsOpener ? 1 : 0) + (hasDo && !(startsOpener && /^(?:while|until|for)$/.test(first[0])) ? 1 : 0);
    depth -= (t.match(/\bend\b/g) || []).length;
    if (depth <= 0) return cs[j].line;
  }
  return cs[cs.length - 1].line;
}

function findFunctions(cs, cl, branchPrefix, lineToIndex) {
  const out = [];
  for (let ci = 0; ci < cs.length; ci++) {
    let m = null;
    let name = null;
    for (const re of cl.functionRes) {
      m = re.exec(cs[ci].text);
      if (m) {
        name = m[1];
        break;
      }
    }
    if (!m) continue;
    const matchEnd = m.index + m[0].length;
    let end;
    if (cl.style === 'end') end = endStyleEnd(cs, ci);
    else if (cl.style === 'indent') end = indentEnd(cs, ci);
    else end = braceEnd(cs, ci, matchEnd, m[0]);
    const ei = lineToIndex.get(end);
    out.push({ name, line: cs[ci].line, length: end - cs[ci].line + 1, branches: branchPrefix[ei + 1] - branchPrefix[ci] });
  }
  out.sort((a, b) => b.branches - a.branches || b.length - a.length || a.line - b.line);
  return out.slice(0, 5);
}

/** Resolves a language argument: a language object, a name from the shipped table, or nothing. */
function resolveLang(lang) {
  if (!lang) return null;
  if (typeof lang === 'string') return shippedLanguageTable().langByName(lang);
  return lang;
}

/**
 * Counted lines plus the measures of one file.
 * @returns {{counted: {line: number, text: string, columns: number}[], summary: {loc: number, indentComplexity: number, maxNesting: number, branchComplexity: (number|null), topFunctions: (object[]|null)}}}
 */
export function analyzeText(text, langArg) {
  const lang = resolveLang(langArg);
  const cl = lang ? compileLang(lang) : null;
  const cs = countedLines(text, lang);
  const unit = indentUnit(cs);
  let indentComplexity = 0;
  let maxNesting = 0;
  for (const c of cs) {
    const level = Math.floor(c.columns / unit);
    indentComplexity += level;
    if (level > maxNesting) maxNesting = level;
  }
  let branchComplexity = null;
  let topFunctions = null;
  if (cl) {
    const branchPrefix = [0];
    for (const c of cs) branchPrefix.push(branchPrefix[branchPrefix.length - 1] + countBranches(c.text, cl));
    branchComplexity = branchPrefix[cs.length];
    const lineToIndex = new Map(cs.map((c, i) => [c.line, i]));
    topFunctions = cs.length ? findFunctions(cs, cl, branchPrefix, lineToIndex) : [];
  }
  return { counted: cs, summary: { loc: cs.length, indentComplexity, maxNesting, branchComplexity, topFunctions } };
}

/** The measures of one file: {loc, indentComplexity, maxNesting, branchComplexity, topFunctions}. */
export function measureText(text, lang) {
  return analyzeText(text, lang).summary;
}
