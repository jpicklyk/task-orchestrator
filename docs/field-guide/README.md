# Field guide

Nine illustrated, partly interactive pages that teach MCP Task Orchestrator to an outside reader.
Start at [index.html](index.html).

| Plate | Page | Covers |
|-------|------|--------|
| 1 | [index.html](index.html) | What the project is and why its rules live in the server |
| 2 | [phase-gates.html](phase-gates.html) | Roles, triggers, a gate simulator, status labels, error handling |
| 3 | [work-graph.html](work-graph.html) | Hierarchy, dependencies and cascades |
| 4 | [schemas-and-traits.html](schemas-and-traits.html) | Schema and trait resolution, note limits, resource leases, config layers |
| 5 | [runtime-map.html](runtime-map.html) | Server layers, deployment, REST API, plugin, claims, identity |
| 6 | [seats.html](seats.html) | Seats inside a phase and the independence check |
| 7 | [finding-work.html](finding-work.html) | The read calls, next-item ranking, project scope |
| 8 | [orchestrating-a-run.html](orchestrating-a-run.html) | Tiers, the plan pipeline, run-wave, rules, ralph |
| 9 | [improvement-loop.html](improvement-loop.html) | Observations, retrospectives, trends, proposals |

## Editing

The pages in this folder are build output. Edit the sources under [src/](src/) and rebuild:

```bash
node docs/field-guide/src/build.mjs
```

- `src/plates/NN-name.html` — one plate each: a `<title>`, page-specific CSS and the body.
- `src/shared.css` — the shared design tokens and diagram classes, inlined into every plate.
- `src/defs.html` — the SVG arrowhead markers every diagram references.

The build has no dependencies beyond Node. It needs no bundler and no install step.

To preview the built pages locally at `http://localhost:4173/`:

```bash
node docs/field-guide/src/serve.mjs
```

`node docs/field-guide/src/build.mjs artifact` writes the same plates as fragments into
`src/dist-artifact/` (git-ignored), linked by the URLs in `src/artifact-urls.json`, for publishing as
Claude artifacts.

## Hosting

The pages are static files with no server-side part, so GitHub Pages can serve them directly from
this repository: repository Settings, Pages, "Deploy from a branch", branch `main`, folder `/docs`.
The guide is then at `https://<owner>.github.io/<repo>/field-guide/`.

The content was written from the documentation under `current/docs/` and the plugin sources. When
those change, the plates need the same change by hand.
