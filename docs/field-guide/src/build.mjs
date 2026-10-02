// Builds the field-guide plates from src/plates/*.html.
//
//   node docs/field-guide/src/build.mjs            -> standalone pages in docs/field-guide/ (GitHub Pages)
//   node docs/field-guide/src/build.mjs artifact   -> body fragments in src/dist-artifact/ (Claude artifacts)
//
// A plate source is a fragment: <title>, a fonts placeholder and two <style> blocks, then <!--DEFS-->, then the
// page body. The "site" target wraps it in a full HTML document and links plates by relative file name. The
// "artifact" target leaves it as a fragment (the artifact host adds its own document skeleton) and links plates
// by the URLs in artifact-urls.json.
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const src = dirname(fileURLToPath(import.meta.url));
const read = (f) => readFileSync(join(src, f), 'utf8');
const target = process.argv[2] === 'artifact' ? 'artifact' : 'site';

const plates = [
  { file: '01-at-a-glance.html', site: 'index.html', artifact: 'to-at-a-glance', name: 'At a Glance', blurb: 'What it is and why the rule lives in the server' },
  { file: '02-phase-gates.html', site: 'phase-gates.html', artifact: 'to-phase-gates', name: 'Phase Gates', blurb: 'Roles, triggers and a gate you can try' },
  { file: '03-work-graph.html', site: 'work-graph.html', artifact: 'to-work-graph', name: 'Work Graph', blurb: 'Hierarchy, dependencies and cascades' },
  { file: '04-schemas-and-traits.html', site: 'schemas-and-traits.html', artifact: 'to-schemas-traits', name: 'Schemas and Traits', blurb: 'How YAML becomes an enforced gate' },
  { file: '05-runtime-map.html', site: 'runtime-map.html', artifact: 'to-runtime-map', name: 'Runtime Map', blurb: 'Architecture, deployment and fleets' },
  { file: '06-seats.html', site: 'seats.html', artifact: 'to-seats', name: 'Seats', blurb: 'Who does what inside a phase' },
  { file: '07-finding-work.html', site: 'finding-work.html', artifact: 'to-finding-work', name: 'Finding Work', blurb: 'The read calls and how the next item is chosen' },
  { file: '08-orchestrating-a-run.html', site: 'orchestrating-a-run.html', artifact: 'to-orchestrating-run', name: 'Orchestrating a Run', blurb: 'From a request to finished items' },
  { file: '09-improvement-loop.html', site: 'improvement-loop.html', artifact: 'to-improvement-loop', name: 'Improvement Loop', blurb: 'From friction to an adopted change' },
];

const artifactUrls = existsSync(join(src, 'artifact-urls.json')) ? JSON.parse(read('artifact-urls.json')) : {};
const shared = read('shared.css');
const defs = read('defs.html');
const fonts = '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Barlow+Condensed:wght@500;600;700&family=IBM+Plex+Mono:wght@400;500&family=IBM+Plex+Sans:wght@400;500;600&display=swap">';
const outDir = target === 'site' ? join(src, '..') : join(src, 'dist-artifact');
mkdirSync(outDir, { recursive: true });

const href = (q) => (target === 'site' ? q.site : artifactUrls[q.artifact]);

plates.forEach((p, i) => {
  const items = plates.map((q, j) => {
    const inner = `<span class="n">PLATE ${j + 1}</span><span class="t">${q.name}</span><span class="small sub">${q.blurb}</span>`;
    if (j === i) return `<span class="item cur" aria-current="page">${inner}</span>`;
    return href(q) ? `<a class="item" href="${href(q)}">${inner}</a>` : `<span class="item">${inner}</span>`;
  }).join('\n');
  const nav = `<footer style="display:grid;gap:14px"><nav class="series-nav" aria-label="Field guide plates">\n${items}\n</nav>
<p class="foot"><span>MCP Task Orchestrator field guide. MIT licensed, open source.</span><a href="https://github.com/jpicklyk/task-orchestrator">github.com/jpicklyk/task-orchestrator</a></p></footer>`;

  let page = read(join('plates', p.file))
    .replace('<!--FONTS-->', () => fonts)
    .replace('/*SHARED*/', () => shared)
    .replace('<!--NAV-->', () => nav)
    .replace(/Plate (\d) of \d<\/span>/, (m, n) => 'Plate ' + n + ' of ' + plates.length + '</span>');

  if (target === 'site') {
    const cut = page.indexOf('<!--DEFS-->');
    if (cut < 0) throw new Error(p.file + ': <!--DEFS--> marker missing');
    const head = page.slice(0, cut).trim();
    const body = page.slice(cut + '<!--DEFS-->'.length).trim();
    page = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<style>:root{color-scheme:light}[hidden]{display:none!important}img{max-width:100%}</style>
${head}
</head>
<body>
${defs}
${body}
</body>
</html>
`;
  } else {
    page = page.replace('<!--DEFS-->', () => defs);
  }
  const name = target === 'site' ? p.site : p.artifact + '.html';
  writeFileSync(join(outDir, name), page);
  console.log(target, name, page.length);
});
