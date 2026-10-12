// rollup.mjs - compact per-child role rollup from a query_items overview JSON (anchorId shape).
// Usage: node rollup.mjs [--json | --markdown] < overview.json
// Unknown flags are ignored.
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export const TITLE_MAX = 40;
export const ID_LEN = 8;
export const COLUMNS = ['queue', 'work', 'review', 'blocked', 'terminal'];

// Truncation: a title longer than 40 chars becomes its first 37 chars + "..."
// (exactly 40 total; the ellipsis counts inside the limit). Shorter titles are unchanged.
export function truncate(title, max = TITLE_MAX) {
  const s = String(title ?? '');
  return s.length <= max ? s : s.slice(0, max - 3) + '...';
}

function count(v) {
  return Number.isFinite(v) ? v : 0;
}

// Format-agnostic row model: {rows:[{id,title,queue,work,review,blocked,terminal}], total:{...}}
export function buildRows(overview) {
  const items = Array.isArray(overview?.items) ? overview.items : [];
  const total = Object.fromEntries(COLUMNS.map((c) => [c, 0]));
  const rows = items.map((it) => {
    const row = { id: String(it?.id ?? '').slice(0, ID_LEN), title: truncate(it?.title) };
    for (const c of COLUMNS) {
      row[c] = count(it?.childCounts?.[c]);
      total[c] += row[c];
    }
    return row;
  });
  return { rows, total };
}

function line(id, title, counts) {
  return [id.padEnd(ID_LEN), title.padEnd(TITLE_MAX), ...COLUMNS.map((c) => String(counts[c]).padStart(8))].join('  ');
}

export function formatText({ rows, total }) {
  const out = [line('id', 'title', Object.fromEntries(COLUMNS.map((c) => [c, c])))];
  for (const r of rows) out.push(line(r.id, r.title, r));
  out.push(line('TOTAL', '', total));
  return out.join('\n');
}

function cell(s) {
  return String(s).replaceAll('|', '\\|');
}

function mdRow(cells) {
  return '| ' + cells.join(' | ') + ' |';
}

// GitHub markdown table rendered from the row model (not from formatText output).
export function formatMarkdown({ rows, total }) {
  const out = [
    mdRow(['id', 'title', ...COLUMNS]),
    mdRow(['---', '---', ...COLUMNS.map(() => '---:')]),
  ];
  for (const r of rows) out.push(mdRow([cell(r.id), cell(r.title), ...COLUMNS.map((c) => r[c])]));
  out.push(mdRow(['TOTAL', '', ...COLUMNS.map((c) => total[c])]));
  return out.join('\n');
}

export function main(argv, input) {
  if (argv.includes('--json') && argv.includes('--markdown')) {
    return { code: 2, stdout: '', stderr: 'rollup: --json and --markdown are mutually exclusive\n' };
  }
  let overview;
  try {
    overview = JSON.parse(input);
  } catch (e) {
    return { code: 1, stdout: '', stderr: `rollup: invalid JSON on stdin: ${e.message}\n` };
  }
  const result = buildRows(overview);
  const text = argv.includes('--json')
    ? JSON.stringify(result)
    : argv.includes('--markdown')
      ? formatMarkdown(result)
      : formatText(result);
  return { code: 0, stdout: text + '\n', stderr: '' };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const r = main(process.argv.slice(2), readFileSync(0, 'utf8'));
  if (r.stderr) process.stderr.write(r.stderr);
  if (r.stdout) process.stdout.write(r.stdout);
  process.exitCode = r.code;
}
