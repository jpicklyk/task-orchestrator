// audit-report-html.mjs - pure renderer: audit result (audit/result-v2, v1 rows accepted) plus the
// optional audit/metrics-v1 JSON in, one self-contained HTML string out. Imports nothing, does no
// I/O, reads no clock: the same input gives byte-identical output. Every input value goes through
// escapeHtml before it enters text or an attribute; the page's only <script> is a static literal.

const METRICS_CONTRACT = 'audit/metrics-v1';

export function escapeHtml(value) {
  if (value === null || value === undefined) return '';
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

const e = escapeHtml;
const cmp = (a, b) => (a < b ? -1 : a > b ? 1 : 0);
const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
const arr = (v) => (Array.isArray(v) ? v : []);
const fmt = (v) => (v === null || v === undefined ? '-' : String(v));
const label = (x) =>
  x !== null && typeof x === 'object' ? (x.label ?? x.name ?? x.sliceKey ?? x.key ?? x.id ?? '') : x;

const SEVERITY_ORDER = ['critical', 'high', 'medium', 'low'];
const VERDICT_ORDER = ['CONFIRMED', 'PLAUSIBLE', 'UNVERIFIED'];
const CLASS_ORDER = ['bug', 'tech-debt', 'observation'];
const RATING_ORDER = ['act-now', 'plan-it', 'watch'];

const CSS = `:root{--bg:#f7f6f3;--surface:#ffffff;--ink:#1d1c1a;--muted:#6b6862;--line:#e4e1da;--accent:#2f5d8a;--code:#f0eee9;--hi:#a3231a;--hi-bg:#fbe5e2;--med:#a35a00;--med-bg:#fdf0de;--low:#4f6070;--low-bg:#e9eef3;--ok:#1f6b45;--ok-bg:#e2f2e8;--un:#6b6862;--un-bg:#eeece7;--shadow:0 1px 2px rgba(0,0,0,.05)}
@media (prefers-color-scheme:dark){:root{--bg:#151514;--surface:#1f1e1c;--ink:#ecebe7;--muted:#a19d95;--line:#33312d;--accent:#8db8e3;--code:#2a2926;--hi:#f2a199;--hi-bg:#3b1a17;--med:#f0b25e;--med-bg:#3a2a12;--low:#a9bccd;--low-bg:#232b33;--ok:#7fd1a3;--ok-bg:#16301f;--un:#b3afa7;--un-bg:#2a2926;--shadow:none}}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%;scroll-behavior:smooth;scroll-padding-top:56px}
body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;overflow-wrap:anywhere}
.top{position:sticky;top:0;z-index:5;background:var(--bg);border-bottom:1px solid var(--line);padding:8px 16px}
.top nav{display:flex;gap:6px;overflow-x:auto;scrollbar-width:none}
.top nav::-webkit-scrollbar{display:none}
.top nav a{flex:none;font-size:13px;padding:6px 10px;border-radius:999px;background:var(--surface);border:1px solid var(--line);color:var(--ink);text-decoration:none;white-space:nowrap}
main{max-width:760px;margin:0 auto;padding:16px 16px 64px}
h1{font-size:26px;line-height:1.2;margin:8px 0 4px}
.sub{color:var(--muted);font-size:14px;margin:0 0 14px}
.verdict{background:var(--surface);border:1px solid var(--line);border-left:4px solid var(--accent);border-radius:10px;padding:12px 14px;margin:0 0 14px;box-shadow:var(--shadow)}
.tiles{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-bottom:8px}
.tile{background:var(--surface);border:1px solid var(--line);border-radius:10px;padding:10px 8px;text-align:center}
.tile b{display:block;font-size:22px;font-variant-numeric:tabular-nums;line-height:1.1}
.tile span{font-size:12px;color:var(--muted)}
h2{font-size:21px;margin:36px 0 10px}
h3{font-size:17px;margin:22px 0 8px}
p{margin:0 0 12px}ul{margin:0 0 12px;padding-left:22px}li{margin:3px 0}
code{font:13px/1.4 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;background:var(--code);padding:1px 5px;border-radius:4px;overflow-wrap:anywhere}
a{color:var(--accent)}
.tbl{overflow-x:auto;margin:0 0 14px;border:1px solid var(--line);border-radius:10px;background:var(--surface)}
table{border-collapse:collapse;width:100%;font-size:14px}
th,td{text-align:left;padding:8px 10px;border-bottom:1px solid var(--line);vertical-align:top}
th{font-size:12px;text-transform:uppercase;letter-spacing:.04em;color:var(--muted)}
tr:last-child td{border-bottom:0}
td svg{width:80px;height:20px;display:block}
td svg polyline{fill:none;stroke:var(--accent);stroke-width:1.5}
td svg circle{fill:var(--accent)}
.filters{display:flex;flex-wrap:wrap;gap:6px;margin:4px 0 10px}
.chip{font:inherit;font-size:14px;padding:7px 12px;border-radius:999px;border:1px solid var(--line);background:var(--surface);color:var(--ink);min-height:36px}
.chip b{font-variant-numeric:tabular-nums;color:var(--muted);font-weight:600;margin-left:2px}
.chip.on{background:var(--ink);color:var(--bg);border-color:var(--ink)}
.chip.on b{color:var(--bg)}
.toolbar{display:flex;gap:8px;margin:0 0 12px}
.toolbar input{flex:1;min-width:0;font:inherit;font-size:16px;padding:8px 12px;border-radius:10px;border:1px solid var(--line);background:var(--surface);color:var(--ink)}
.ghost{font:inherit;font-size:14px;padding:8px 12px;border-radius:10px;border:1px solid var(--line);background:transparent;color:var(--ink);white-space:nowrap}
.card{background:var(--surface);border:1px solid var(--line);border-radius:12px;margin:0 0 10px;box-shadow:var(--shadow)}
.card[hidden]{display:none}
.card summary{list-style:none;cursor:pointer;padding:12px 14px;display:grid;grid-template-columns:auto 1fr;column-gap:10px;row-gap:8px}
.card summary::-webkit-details-marker{display:none}
.num{font-weight:700;font-variant-numeric:tabular-nums;color:var(--muted);font-size:14px}
.ct{font-weight:600;line-height:1.35}
.badges{grid-column:2;display:flex;flex-wrap:wrap;gap:5px}
.b{font-size:12px;padding:2px 8px;border-radius:999px;background:var(--un-bg);color:var(--un);white-space:nowrap}
.sev-critical,.sev-high{background:var(--hi-bg);color:var(--hi);font-weight:600}
.sev-medium{background:var(--med-bg);color:var(--med);font-weight:600}
.sev-low{background:var(--low-bg);color:var(--low)}
.v-CONFIRMED{background:var(--ok-bg);color:var(--ok);font-weight:600}
.card[open] summary{border-bottom:1px solid var(--line)}
.cb{padding:12px 14px 4px}
.src{color:var(--muted);font-size:13px}
.none{color:var(--muted)}
details.card:target{outline:2px solid var(--accent);outline-offset:1px}`;

// Static page script: filters, search, expand-all, report-id links. It reads data- attributes and
// card text from the DOM, so no input data is ever embedded here.
const SCRIPT = `(function(){
var attr={verdict:'verdict',severity:'sev',rating:'rating',class:'class'};
var cards=[].slice.call(document.querySelectorAll('#findings details.card'));
var chips=[].slice.call(document.querySelectorAll('.chip'));
var box=document.getElementById('q');
var all=document.getElementById('expand');
var shown=document.getElementById('shown');
var g='all',f='all',q='';
function apply(){
var n=0;
cards.forEach(function(c){
var ok=(g==='all'||c.getAttribute('data-'+attr[g])===f)&&(q===''||c.textContent.toLowerCase().indexOf(q)>=0);
c.hidden=!ok;if(ok)n++;});
if(shown)shown.textContent=n+' of '+cards.length+' shown';
chips.forEach(function(b){b.classList.toggle('on',b.getAttribute('data-g')===g&&b.getAttribute('data-f')===f);});}
chips.forEach(function(b){b.addEventListener('click',function(){g=b.getAttribute('data-g');f=b.getAttribute('data-f');apply();});});
if(box)box.addEventListener('input',function(){q=box.value.toLowerCase();apply();});
if(all)all.addEventListener('click',function(){
var open=cards.some(function(c){return !c.open;});
cards.forEach(function(c){c.open=open;});
all.textContent=open?'Collapse all':'Expand all';});
function show(id){
var c=id?document.getElementById(id):null;
if(!c||c.tagName!=='DETAILS')return false;
if(c.hidden){g='all';f='all';q='';if(box)box.value='';apply();}
c.open=true;c.scrollIntoView();return true;}
document.addEventListener('click',function(ev){
var a=ev.target&&ev.target.closest?ev.target.closest('a[href^="#r"]'):null;
if(a)show(a.getAttribute('href').slice(1));});
window.addEventListener('hashchange',function(){show(location.hash.slice(1));});
apply();
if(location.hash)show(location.hash.slice(1));
})();`;

function normalizeInput(input) {
  if (input && Array.isArray(input.kept)) return input;
  if (input && input.result && Array.isArray(input.result.kept)) return input.result;
  throw new TypeError('input has no kept[] array (bare result or wrapper with result.kept expected)');
}

function idNum(reportId) {
  const m = /\d+/.exec(reportId);
  return m ? Number(m[0]) : Infinity;
}

function orderedKept(kept) {
  const withId = kept.filter((k) => k && k.reportId);
  const rest = kept.filter((k) => k && !k.reportId);
  withId.sort(
    (a, b) =>
      cmp(idNum(a.reportId), idNum(b.reportId)) ||
      cmp(String(a.reportId), String(b.reportId)) ||
      cmp(String(a.findingId ?? ''), String(b.findingId ?? '')),
  );
  rest.sort((a, b) => cmp(String(a.findingId ?? ''), String(b.findingId ?? '')));
  return withId.concat(rest);
}

function rowClasses(proposal) {
  const map = new Map();
  for (const it of arr(proposal && proposal.items)) {
    if (it && it.findingId !== undefined && it.class && !map.has(it.findingId)) {
      map.set(it.findingId, it.class);
    }
  }
  return map;
}

function chipValues(rows, key, known) {
  const counts = new Map();
  for (const r of rows) {
    const v = r[key];
    if (v === undefined || v === null || v === '') continue;
    counts.set(v, (counts.get(v) || 0) + 1);
  }
  const first = known.filter((v) => counts.has(v));
  const others = [...counts.keys()].filter((v) => !known.includes(v)).sort(cmp);
  return first.concat(others).map((v) => [v, counts.get(v)]);
}

export function renderAuditReport(input, metrics = null) {
  const res = normalizeInput(input);
  if (metrics !== null && metrics !== undefined && (typeof metrics !== 'object' || metrics.contract !== METRICS_CONTRACT)) {
    throw new TypeError(`metrics contract must be ${METRICS_CONTRACT}`);
  }
  const m = metrics === undefined ? null : metrics;

  const kept = orderedKept(res.kept);
  const classOf = rowClasses(res.proposal);
  const rows = kept.map((k) => ({
    k,
    verdict: k.verdict,
    severity: k.severity,
    rating: k.rating,
    class: classOf.get(k.findingId),
  }));
  const keptIds = new Set(kept.filter((k) => k.reportId).map((k) => String(k.reportId).toLowerCase()));

  const link = (text) =>
    e(text).replace(/\bR\d+\b/g, (tok) =>
      keptIds.has(tok.toLowerCase()) ? `<a href="#${tok.toLowerCase()}">${tok}</a>` : tok,
    );
  const list = (items) => `<ul>${items.map((x) => `<li>${link(x)}</li>`).join('')}</ul>`;

  const report = res.report && typeof res.report === 'object' ? res.report : null;
  const out = [];

  // Sections present, in fixed order.
  const themes = arr(report && report.themes);
  const strengths = arr(report && report.strengths);
  const roadmap = (report && report.roadmap) || {};
  const roadmapParts = [['Now', roadmap.now], ['Next', roadmap.next], ['Later', roadmap.later]].filter(
    ([, v]) => arr(v).length > 0,
  );
  const sections = [
    ['summary', 'Summary', true],
    ['strengths', 'Strengths', strengths.length > 0],
    ['themes', 'Themes', themes.length > 0],
    ['findings', 'Findings', true],
    ['roadmap', 'Roadmap', roadmapParts.length > 0],
    ['hotspots', 'Hot spots', m !== null],
    ['architecture', 'Architecture', m !== null],
    ['coverage', 'Coverage', true],
  ].filter((s) => s[2]);

  out.push('<!doctype html>');
  out.push('<html lang="en"><head><meta charset="utf-8">');
  out.push('<meta name="viewport" content="width=device-width, initial-scale=1">');
  out.push('<title>Audit report</title>');
  out.push(`<style>\n${CSS}\n</style></head>`);
  out.push('<body>');
  out.push(
    `<div class="top"><nav aria-label="Sections">${sections
      .map(([id, title]) => `<a href="#${id}">${title}</a>`)
      .join('')}</nav></div>`,
  );
  out.push('<main>');

  // Hero
  const count = (fn) => kept.filter(fn).length;
  const tiles = [
    ['Kept', kept.length],
    ['Confirmed', count((k) => k.verdict === 'CONFIRMED')],
    ['Unverified', count((k) => k.verdict === 'UNVERIFIED')],
    ['Critical/high', count((k) => k.severity === 'critical' || k.severity === 'high')],
    ['Medium', count((k) => k.severity === 'medium')],
    ['Low', count((k) => k.severity === 'low')],
  ];
  const subParts = [];
  if (res.runId) subParts.push(`<code>${e(res.runId)}</code>`);
  if (res.date) subParts.push(e(res.date));
  out.push('<header>');
  out.push('<h1>Audit report</h1>');
  if (subParts.length) out.push(`<p class="sub">${subParts.join(' &middot; ')}</p>`);
  out.push(`<div class="tiles">${tiles.map(([l, n]) => `<div class="tile"><b>${n}</b><span>${l}</span></div>`).join('')}</div>`);
  out.push('</header>');

  // Summary
  out.push('<section id="summary"><h2>Summary</h2>');
  if (report) {
    if (report.healthVerdict) out.push(`<div class="verdict">${link(report.healthVerdict)}</div>`);
    if (report.executiveSummary) out.push(`<p>${link(report.executiveSummary)}</p>`);
  } else {
    out.push('<p class="none">No synthesis result.</p>');
  }
  out.push('</section>');

  if (strengths.length) {
    out.push(`<section id="strengths"><h2>Strengths</h2>${list(strengths)}</section>`);
  }

  if (themes.length) {
    out.push('<section id="themes"><h2>Themes</h2>');
    for (const t of themes) {
      if (!t) continue;
      const ids = arr(t.reportIds).map((id) => link(id)).join(', ');
      out.push(
        `<details class="card theme"><summary><span class="ct">${e(t.name)}</span></summary><div class="cb">` +
          (t.summary ? `<p>${link(t.summary)}</p>` : '') +
          (ids ? `<p class="src">Findings: ${ids}</p>` : '') +
          '</div></details>',
      );
    }
    out.push('</section>');
  }

  // Findings
  out.push('<section id="findings"><h2>Findings</h2>');
  if (rows.length === 0) {
    out.push('<p class="none">No findings kept.</p>');
  } else {
    const chips = [`<button class="chip" data-g="all" data-f="all">All <b>${rows.length}</b></button>`];
    for (const [group, key, known] of [
      ['verdict', 'verdict', VERDICT_ORDER],
      ['severity', 'severity', SEVERITY_ORDER],
      ['rating', 'rating', RATING_ORDER],
      ['class', 'class', CLASS_ORDER],
    ]) {
      for (const [v, n] of chipValues(rows, key, known)) {
        chips.push(`<button class="chip" data-g="${group}" data-f="${e(v)}">${e(v)} <b>${n}</b></button>`);
      }
    }
    out.push(`<div class="filters">${chips.join('')}</div>`);
    out.push(
      '<div class="toolbar"><input id="q" type="search" placeholder="Search findings" aria-label="Search findings">' +
        '<button class="ghost" id="expand">Expand all</button></div>',
    );
    out.push(`<p class="src" id="shown">${rows.length} of ${rows.length} shown</p>`);
    for (const r of rows) {
      const k = r.k;
      const attrs =
        (k.reportId ? ` id="${e(String(k.reportId).toLowerCase())}"` : '') +
        ` data-sev="${e(k.severity)}" data-verdict="${e(k.verdict)}"` +
        (k.rating ? ` data-rating="${e(k.rating)}"` : '') +
        (r.class ? ` data-class="${e(r.class)}"` : '');
      const badges = [];
      if (k.severity) badges.push(`<span class="b sev-${e(k.severity)}">${e(k.severity)}</span>`);
      if (k.verdict) badges.push(`<span class="b v-${e(k.verdict)}">${e(k.verdict)}</span>`);
      if (k.rating) badges.push(`<span class="b">${e(k.rating)}</span>`);
      if (k.effort) badges.push(`<span class="b">${e(k.effort)}</span>`);
      if (k.category) badges.push(`<span class="b">${e(k.category)}</span>`);
      if (k.trackedId) badges.push(`<span class="b">tracked ${e(k.trackedId)}</span>`);
      const body = [];
      const locs = arr(k.locations);
      if (locs.length) body.push(`<p class="src">${locs.map((l) => `<code>${e(l)}</code>`).join('<br>')}</p>`);
      if (k.evidence) body.push(`<p><b>Evidence.</b> ${e(k.evidence)}</p>`);
      if (k.impact) body.push(`<p><b>Impact.</b> ${e(k.impact)}</p>`);
      if (k.recommendation) body.push(`<p><b>Recommendation.</b> ${e(k.recommendation)}</p>`);
      if (k.confidence !== undefined && k.confidence !== null && k.confidence !== '') {
        body.push(`<p class="src">Confidence: ${e(k.confidence)}</p>`);
      }
      const mt = k.metrics && typeof k.metrics === 'object' ? k.metrics : {};
      const measured = [['churn', mt.churn], ['complexity', mt.complexity]].filter(([, v]) => typeof v === 'number' && Number.isFinite(v));
      if (measured.length) body.push(`<p class="src">Measured: ${measured.map(([n, v]) => `${n} ${e(v)}`).join(', ')}</p>`);
      if (k.sliceKey) body.push(`<p class="src">Slice: ${e(k.sliceKey)}</p>`);
      if (k.unverifiedReason === 'cap') body.push('<p class="src">Unverified: cap</p>');
      else if (k.unverifiedReason === 'no-verifier') body.push('<p class="src">Unverified: no verifier</p>');
      out.push(
        `<details class="card"${attrs}><summary><span class="num">${e(k.reportId)}</span><span class="ct">${e(k.title)}</span>` +
          `<span class="badges">${badges.join('')}</span></summary><div class="cb">${body.join('')}</div></details>`,
      );
    }
  }
  out.push('</section>');

  if (roadmapParts.length) {
    out.push('<section id="roadmap"><h2>Roadmap</h2>');
    for (const [title, items] of roadmapParts) out.push(`<h3>${title}</h3>${list(arr(items).map(String))}`);
    out.push('</section>');
  }

  if (m !== null) {
    out.push(hotspotsSection(m));
    out.push(architectureSection(m));
  }

  out.push(coverageSection(res, report, m !== null, link));

  out.push('</main>');
  out.push(`<script>\n${SCRIPT}\n</script>`);
  out.push('</body></html>');
  return out.join('\n') + '\n';
}

function table(headers, bodyRows) {
  return (
    '<div class="tbl"><table><thead><tr>' +
    headers.map((h) => `<th>${h}</th>`).join('') +
    '</tr></thead><tbody>' +
    bodyRows.map((cells) => `<tr>${cells.map((c) => `<td>${c}</td>`).join('')}</tr>`).join('') +
    '</tbody></table></div>'
  );
}

function sparkline(samples) {
  const pts = arr(samples)
    .filter((s) => s && isNum(s.indentComplexity))
    .sort((a, b) => cmp(String(a.date ?? ''), String(b.date ?? '')) || cmp(String(a.sha ?? ''), String(b.sha ?? '')))
    .map((s) => s.indentComplexity);
  if (pts.length === 0) return '-';
  if (pts.length === 1) return '<svg viewBox="0 0 80 20"><circle cx="40" cy="10" r="2"/></svg>';
  const min = Math.min(...pts);
  const max = Math.max(...pts);
  const points = pts
    .map((v, i) => {
      const x = ((i * 80) / (pts.length - 1)).toFixed(1);
      const y = (max === min ? 10 : 20 - ((v - min) / (max - min)) * 20).toFixed(1);
      return `${x},${y}`;
    })
    .join(' ');
  return `<svg viewBox="0 0 80 20"><polyline points="${points}"/></svg>`;
}

function hotspotsSection(m) {
  const files = new Map();
  for (const f of arr(m.files)) if (f && typeof f.path === 'string' && !files.has(f.path)) files.set(f.path, f);
  const trend = new Map();
  for (const t of arr(m.trend)) if (t && typeof t.path === 'string' && !trend.has(t.path)) trend.set(t.path, t);
  const paths = arr(m.hotSet)
    .map((h) => (typeof h === 'string' ? h : h && typeof h.path === 'string' ? h.path : null))
    .filter((p) => p !== null);
  const parts = ['<section id="hotspots"><h2>Complexity hot spots</h2>'];
  if (paths.length === 0) {
    parts.push('<p class="none">No hot spots in scope.</p>');
  } else {
    const score = (p) => (files.get(p) && isNum(files.get(p).hotspotScore) ? files.get(p).hotspotScore : -Infinity);
    const sorted = paths.slice().sort((a, b) => cmp(score(b), score(a)) || cmp(a, b));
    parts.push(
      table(
        ['Path', 'Churn', 'Churn (recent)', 'Indent complexity', 'Branch complexity (approx.)', 'Hot spot score', 'Trend'],
        sorted.map((p) => {
          const f = files.get(p) || {};
          const t = trend.get(p);
          return [
            `<code>${e(p)}</code>`,
            e(fmt(f.churn)),
            e(fmt(f.churnRecent)),
            e(fmt(f.indentComplexity)),
            e(fmt(f.branchComplexity)),
            e(fmt(f.hotspotScore)),
            t ? sparkline(t.samples) : '-',
          ];
        }),
      ),
    );
  }
  parts.push('</section>');
  return parts.join('\n');
}

function architectureSection(m) {
  const mods = m.modules && typeof m.modules === 'object' ? m.modules : {};
  const parts = ['<section id="architecture"><h2>Architecture</h2>'];
  const cycles = arr(mods.cycles);
  parts.push('<h3>Dependency cycles</h3>');
  if (cycles.length === 0) parts.push('<p class="none">No cycles.</p>');
  else parts.push(`<ul>${cycles.map((c) => `<li>${arr(c).map((id) => e(id)).join(' -&gt; ')}</li>`).join('')}</ul>`);

  parts.push('<h3>Modules</h3>');
  const nodes = arr(mods.nodes).filter((n) => n && typeof n === 'object');
  if (nodes.length === 0) {
    parts.push('<p class="none">No module data.</p>');
  } else {
    const inst = (n) => (isNum(n.instability) ? n.instability : -Infinity);
    nodes.sort((a, b) => cmp(inst(b), inst(a)) || cmp(String(a.id ?? ''), String(b.id ?? '')));
    parts.push(
      table(
        ['Module', 'Files', 'LOC', 'Fan-in', 'Fan-out', 'Instability'],
        nodes.map((n) => [
          `<code>${e(n.id)}</code>`,
          e(fmt(n.files)),
          e(fmt(n.loc)),
          e(fmt(n.fanIn)),
          e(fmt(n.fanOut)),
          isNum(n.instability) ? n.instability.toFixed(2) : '-',
        ]),
      ),
    );
  }
  parts.push(`<p>Unresolved imports: ${isNum(mods.unresolved) ? `${mods.unresolved} (includes imports of external libraries)` : 'not reported'}</p>`);

  const unrec = m.languages && m.languages.unrecognised && typeof m.languages.unrecognised === 'object' ? m.languages.unrecognised : {};
  const exts = Object.keys(unrec).sort(cmp);
  if (exts.length) parts.push(`<p>Unrecognised extensions: ${exts.map((x) => `${e(x)} (${e(unrec[x])})`).join(', ')}</p>`);

  const notes = arr(m.notes);
  if (notes.length) parts.push(`<h3>Metrics notes</h3><ul>${notes.map((n) => `<li>${e(n)}</li>`).join('')}</ul>`);
  parts.push('</section>');
  return parts.join('\n');
}

function coverageSection(res, report, hasMetrics, link) {
  const parts = ['<section id="coverage"><h2>Coverage</h2>'];
  if (!hasMetrics) parts.push('<p>Metrics were not available for this run.</p>');
  if (report && report.method) parts.push(`<h3>Method</h3><p>${link(report.method)}</p>`);
  const cov = report && report.coverage && typeof report.coverage === 'object' ? report.coverage : null;
  if (cov) {
    const reviewers = arr(cov.reviewers);
    if (reviewers.length) parts.push(`<h3>Reviewers</h3><ul>${reviewers.map((r) => `<li>${e(label(r))}</li>`).join('')}</ul>`);
    const notExamined = arr(cov.notExamined);
    if (notExamined.length) parts.push(`<h3>Not examined</h3><ul>${notExamined.map((r) => `<li>${e(label(r))}</li>`).join('')}</ul>`);
    const critic = { disabled: 'Gap critic: disabled', 'no-result': 'Gap critic: ran, no result', ran: 'Gap critic: ran' }[cov.critic];
    const lines = [];
    if (critic) lines.push(critic);
    const v = cov.verification && typeof cov.verification === 'object' ? cov.verification : null;
    if (v) {
      lines.push(`Confirmed: ${fmt(v.confirmed)}`);
      lines.push(`Refuted: ${fmt(v.refuted)}`);
      lines.push(`Unverified (cap): ${fmt(v.unverifiedByCap)}`);
      lines.push(`Unverified (no verifier): ${fmt(v.unverifiedNoVerifier)}`);
    }
    if (lines.length) parts.push(`<h3>Verification</h3>${lines.map((l) => `<p>${e(l)}</p>`).join('')}`);
  }
  const refuted = arr(res.refuted);
  if (refuted.length) {
    parts.push(`<h3>Refuted findings</h3><ul>${refuted.map((r) => `<li>${e(r && r.title)}</li>`).join('')}</ul>`);
  }
  const stats = res.stats && typeof res.stats === 'object' ? res.stats : null;
  if (stats && (stats.raw !== undefined || stats.merged !== undefined)) {
    const sl = [];
    if (stats.raw !== undefined) sl.push(`Raw findings: ${fmt(stats.raw)}`);
    if (stats.merged !== undefined) sl.push(`After merge: ${fmt(stats.merged)}`);
    parts.push(`<h3>Counts</h3>${sl.map((l) => `<p>${e(l)}</p>`).join('')}`);
  }
  parts.push('</section>');
  return parts.join('\n');
}
