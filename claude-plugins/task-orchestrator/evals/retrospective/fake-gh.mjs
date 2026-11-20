#!/usr/bin/env node
// fake-gh.mjs -- a stand-in for the GitHub CLI used by the retrospective evals and the bug-report CLI test.
// run-planner bug-report spawns it through the TASK_ORCHESTRATOR_GH_SHIM seam. It never touches the network.
//
// State lives in the directory named by FAKE_GH_DIR (default: <os tmpdir>/fake-gh): calls.jsonl (one JSON line per
// call: {argv, body}) and issues.json (the issues "created" so far). Answers: auth status (0), api user (login
// eval-user), issue list --search <fp> in:body (the created issues whose body holds <fp>), issue create (prints
// https://github.com/jpicklyk/task-orchestrator/issues/990001, 990002, ...), issue comment (0). Anything else exits 1.

import { readFileSync, writeFileSync, appendFileSync, mkdirSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const dir = process.env.FAKE_GH_DIR || join(tmpdir(), 'fake-gh');
mkdirSync(dir, { recursive: true });
const argv = process.argv.slice(2);

function flag(name) {
  const i = argv.indexOf(name);
  return i !== -1 ? argv[i + 1] : undefined;
}
let body = null;
const bodyFile = flag('--body-file');
if (bodyFile) {
  try {
    body = readFileSync(bodyFile, 'utf8');
  } catch {
    body = null;
  }
}
appendFileSync(join(dir, 'calls.jsonl'), JSON.stringify({ argv, body }) + '\n');

const issuesPath = join(dir, 'issues.json');
const issues = existsSync(issuesPath) ? JSON.parse(readFileSync(issuesPath, 'utf8')) : [];
const [a, b] = argv;

if (a === 'auth' && b === 'status') process.exit(0);
if (a === 'api' && b === 'user') {
  process.stdout.write('eval-user\n');
  process.exit(0);
}
if (a === 'issue' && b === 'list') {
  const term = String(flag('--search') || '').replace(/ in:body$/, '');
  const hits = issues.filter((i) => term && i.body.includes(term));
  process.stdout.write(JSON.stringify(hits));
  process.exit(0);
}
if (a === 'issue' && b === 'create') {
  const number = 990001 + issues.length;
  const url = `https://github.com/jpicklyk/task-orchestrator/issues/${number}`;
  issues.push({ number, url, state: 'OPEN', stateReason: null, closedAt: null, labels: [], body: body || '' });
  writeFileSync(issuesPath, JSON.stringify(issues));
  process.stdout.write(url + '\n');
  process.exit(0);
}
if (a === 'issue' && b === 'comment') process.exit(0);
process.exit(1);
