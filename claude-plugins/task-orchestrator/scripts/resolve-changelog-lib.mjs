// resolve-changelog-lib.mjs - pure three-way resolver for the [Unreleased] region of a
// CHANGELOG. No fs, no process, no clock; the CLI (resolve-changelog.mjs) does all I/O.
//
// resolveChangelog({ ancestor, base, branch }) returns
//   { ok: true, text, discarded: [{ section, key, branchText }] }
//   { ok: false, code: 'duplicate', duplicates: [{ kind, name }] }
//   { ok: false, code: 'invalid', message }
// and never throws on string input.

const HEADING = '## [Unreleased]';

const invalid = (message) => ({ ok: false, code: 'invalid', message });
const rtrim = (s) => s.replace(/\s+$/, '');

function keyOf(line) {
  const first = line.slice(2).trim();
  if (first.startsWith('**')) {
    const close = first.indexOf('**', 2);
    if (close > 2) return first.slice(2, close).trim();
  }
  return first;
}

function parseInput(label, raw) {
  if (typeof raw !== 'string') return { error: invalid(`${label}: input is not a string`) };
  const lines = raw.replace(/\r\n/g, '\n').split('\n').map(rtrim);
  for (const l of lines) {
    if (l.startsWith('<<<<<<< ') || l.startsWith('>>>>>>> ') || l === '=======') {
      return { error: invalid(`${label}: conflict marker present`) };
    }
  }
  const heads = [];
  lines.forEach((l, i) => {
    if (l === HEADING) heads.push(i);
  });
  const dups = [];
  if (heads.length > 1) dups.push({ kind: 'unreleased', name: '[Unreleased]' });
  let start = -1;
  let end = -1;
  let region = [];
  if (heads.length >= 1) {
    start = heads[0];
    end = start + 1;
    while (end < lines.length && !lines[end].startsWith('## ')) end++;
    region = lines.slice(start + 1, end);
  }
  const sections = [];
  let cur = null;
  let bullet = null;
  for (const l of region) {
    if (l.trim() === '') continue;
    if (l.startsWith('### ')) {
      cur = { name: l.slice(4).trim(), bullets: [] };
      sections.push(cur);
      bullet = null;
    } else if (l.startsWith('- ') && cur) {
      bullet = { key: keyOf(l), lines: [l] };
      cur.bullets.push(bullet);
    } else if (/^\s/.test(l) && bullet) {
      bullet.lines.push(l);
    } else {
      return { error: invalid(`${label}: unsupported line in [Unreleased]: ${l}`) };
    }
  }
  const seenSec = new Set();
  const seenKey = new Set();
  for (const s of sections) {
    if (seenSec.has(s.name)) dups.push({ kind: 'section', name: s.name });
    seenSec.add(s.name);
    for (const b of s.bullets) {
      if (seenKey.has(b.key)) dups.push({ kind: 'bullet', name: b.key });
      seenKey.add(b.key);
    }
  }
  if (dups.length) return { error: { ok: false, code: 'duplicate', duplicates: dups } };
  const rest = start === -1 ? lines : [...lines.slice(0, start), ...lines.slice(end)];
  const outside = rest.filter((l) => l.trim() !== '').join('\n');
  return { lines, start, end, sections, outside };
}

const bulletText = (b) => b.lines.join('\n');
const SEP = '\u0000';

function renderRegion(sections) {
  const out = [HEADING, ''];
  for (const s of sections) {
    out.push(`### ${s.name}`, '');
    for (const b of s.bullets) out.push(...b.lines);
    out.push('');
  }
  return out;
}

export function resolveChangelog({ ancestor, base, branch } = {}) {
  const A = parseInput('ancestor', ancestor);
  if (A.error) return A.error;
  const B = parseInput('base', base);
  if (B.error) return B.error;
  const R = parseInput('branch', branch);
  if (R.error) return R.error;

  if (R.outside !== A.outside && R.outside !== B.outside) {
    return invalid('branch changed text outside [Unreleased] that differs from both ancestor and base');
  }

  const index = (p) => {
    const m = new Map();
    for (const s of p.sections) for (const b of s.bullets) m.set(s.name + SEP + b.key, bulletText(b));
    return m;
  };
  const am = index(A);
  const bm = index(B);
  const rm = index(R);
  const ids = new Set([...am.keys(), ...bm.keys(), ...rm.keys()]);

  const kept = new Map(); // id -> chosen text
  const discarded = [];
  for (const id of ids) {
    const a = am.get(id);
    const b = bm.get(id);
    const r = rm.get(id);
    let pick;
    if (b === r) pick = b; // R1
    else if (b === a) pick = r; // R2
    else if (r === a) pick = b; // R3
    else {
      pick = b; // R4: both sides changed it differently, base wins
      const [section, key] = id.split(SEP);
      discarded.push({ section, key, branchText: r === undefined ? null : r });
    }
    if (pick !== undefined) kept.set(id, pick);
  }

  const names = [];
  for (const p of [B, R, A]) for (const s of p.sections) if (!names.includes(s.name)) names.push(s.name);
  const sections = [];
  for (const name of names) {
    const bs = B.sections.find((s) => s.name === name);
    const rs = R.sections.find((s) => s.name === name);
    const mk = (b) => ({ key: b.key, lines: kept.get(name + SEP + b.key).split('\n') });
    const out = [];
    for (const b of rs ? rs.bullets : []) {
      const id = name + SEP + b.key;
      if (kept.has(id) && !bm.has(id)) out.push(mk(b));
    }
    for (const b of bs ? bs.bullets : []) {
      if (kept.has(name + SEP + b.key)) out.push(mk(b));
    }
    if (out.length) sections.push({ name, bullets: out });
  }

  const dups = [];
  const seen = new Set();
  for (const s of sections) {
    for (const b of s.bullets) {
      if (seen.has(b.key)) dups.push({ kind: 'bullet', name: b.key });
      seen.add(b.key);
    }
  }
  if (dups.length) return { ok: false, code: 'duplicate', duplicates: dups };

  const region = renderRegion(sections);
  const lines = B.lines;
  let outLines;
  if (B.start !== -1) {
    outLines = [...lines.slice(0, B.start), ...region, ...lines.slice(B.end)];
  } else if (!sections.length) {
    outLines = lines;
  } else {
    const idx = lines.findIndex((l) => l.startsWith('## '));
    if (idx !== -1) {
      const pre = lines.slice(0, idx);
      if (pre.length && pre[pre.length - 1] !== '') pre.push('');
      outLines = [...pre, ...region, ...lines.slice(idx)];
    } else {
      const pre = lines.slice();
      while (pre.length && pre[pre.length - 1] === '') pre.pop();
      if (pre.length) pre.push('');
      const reg = region.slice();
      while (reg.length && reg[reg.length - 1] === '') reg.pop();
      outLines = [...pre, ...reg, ''];
    }
  }
  const eol = typeof base === 'string' && base.includes('\r\n') ? '\r\n' : '\n';
  return { ok: true, text: outLines.join(eol), discarded };
}
