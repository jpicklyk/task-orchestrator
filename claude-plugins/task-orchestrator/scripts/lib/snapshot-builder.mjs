// snapshot-builder.mjs - builds the run-wave/snapshot-v1 object (skills/run-wave/references/snapshot.md)
// from item ids over the REST API: the work Step 2 of run-wave otherwise does by hand.
//
// buildSnapshot(input, io) is async and holds no environment access of its own: every read of the server, git,
// the run profile, the probe result and the clock arrives through the injected `io`, as run-exec-lib.mjs gets
// EXEC_IO (run-planner.mjs builds the real one). The pure helpers (projectSchema, parseWorktreePorcelain,
// carryInSection, scopeNeeds, snapshotArgErrors) are exported for tests.
//
// io:
//   call(tool, body)      -> Promise<{ok, data, error:{code, ...}}>   one REST op; never rejects
//   wellKnown()           -> Promise<object|null>                     the well-known document
//   git(args)             -> {status, stdout}                         git -C <cwd> <args>; a failed spawn is status != 0
//   fetchOrigin()         -> {status}                                  git fetch origin (skipped with input.noFetch)
//   readProfile(repoRoot) -> {kind:'absent'} | {kind:'invalid'} | {kind:'ok', value}
//   probe()               -> Promise<object>                          the `probe` subcommand's object
// input: {root, ancestor|items:[...], scopes?:{...}, noFetch?, now}
//
// Result: {ok:true, snapshot, summary:{candidates, needs:{scopes}, skipped, warnings}}
//      or {ok:false, exit:2|3, error, detail}   (nothing is written by this module either way)

import { carryInKeys, validateSnapshot } from '../run-planner-lib.mjs';

const CONTRACT = 'run-wave/snapshot-v1';
const PAGE_LIMIT = 200;
const MAX_PAGES = 500;
const SECTION_HEADING = /^##\s+Carry-in dispositions\s*$/i;
const RECORD_KEY = 'carry-in-dispositions';

function isPlain(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

/** Two ids name the same item when one is a prefix of the other (compact 8-hex ids against full UUIDs). */
function idMatch(a, b) {
  return typeof a === 'string' && typeof b === 'string' && a !== '' && b !== '' && (a.startsWith(b) || b.startsWith(a));
}

function stop(exit, error, detail) {
  return { snapshotStop: true, exit, error, detail };
}

/** Argument errors that make the call invalid before anything is read (exit 2); [] when fine. */
export function snapshotArgErrors({ root, ancestor, items, hasOut, scopes } = {}) {
  const errors = [];
  if (typeof root !== 'string' || root.trim() === '') errors.push('--root <rootId> is required');
  const hasAncestor = typeof ancestor === 'string' && ancestor.trim() !== '';
  const hasItems = Array.isArray(items) && items.length > 0;
  if (hasAncestor && hasItems) errors.push('--ancestor and --items are mutually exclusive');
  else if (!hasAncestor && !hasItems) errors.push('one of --ancestor <id> or --items <id,...> is required');
  if (!hasOut) errors.push('--out <file> is required');
  if (scopes !== undefined && scopes !== null && !isPlain(scopes)) errors.push('--scopes file must hold a JSON object');
  return errors;
}

// description and guidance strings of the schema never enter the snapshot.
function stripText(v) {
  if (Array.isArray(v)) return v.map(stripText);
  if (!isPlain(v)) return v;
  const out = {};
  for (const [k, val] of Object.entries(v)) {
    if (k === 'description' || k === 'guidance') continue;
    out[k] = stripText(val);
  }
  return out;
}

function projectNote(n) {
  const out = { key: n.key, role: n.role, required: n.required };
  if (n.seat !== undefined && n.seat !== null) out.seat = n.seat;
  // the served note owner (a string or null); copied when the answer carries the member, null kept
  if (n.owningSeat !== undefined) out.owningSeat = n.owningSeat;
  if (n.skill !== undefined) out.skill = n.skill;
  if (n.skills !== undefined) out.skills = n.skills;
  if (n.skillMode !== undefined) out.skillMode = n.skillMode;
  if (n.lane !== undefined) out.lane = stripText(n.lane);
  if (Number.isInteger(n.maxLength) && n.maxLength >= 1) out.maxLength = n.maxLength;
  return out;
}

/**
 * projectSchema(answer) -> a snapshot `schemas[<id>]` entry from one `items_read` `schema` envelope.
 * An answer with notes is status "ok"; a schemaFree answer or a not_found error is "not-found"; anything
 * else is "unavailable". Notes are projected to their structural fields (no description, no guidance).
 */
export function projectSchema(answer) {
  if (answer && answer.ok === true && isPlain(answer.data)) {
    const d = answer.data;
    if (d.schemaFree === true) return { status: 'not-found' };
    if (!Array.isArray(d.notes)) return { status: 'unavailable' };
    const out = {
      status: 'ok',
      type: d.type,
      configFingerprint: d.configFingerprint,
      configSource: d.configSource,
      notes: d.notes.filter(isPlain).map(projectNote)
    };
    for (const k of ['seats', 'dispatch', 'dispatchBySeat', 'resources']) {
      if (d[k] !== undefined) out[k] = stripText(d[k]);
    }
    return out;
  }
  if (answer && answer.error && answer.error.code === 'not_found') return { status: 'not-found' };
  return { status: 'unavailable' };
}

/** parseWorktreePorcelain(text) -> {<path>: {branch, head}}; branch is null for a detached or bare entry. */
export function parseWorktreePorcelain(text) {
  const out = {};
  let cur = null;
  const flush = () => {
    if (cur && cur.path) out[cur.path] = { branch: cur.branch, head: cur.head };
    cur = null;
  };
  for (const raw of String(text || '').split(/\r?\n/)) {
    const line = raw.trimEnd();
    if (line === '') {
      flush();
    } else if (line.startsWith('worktree ')) {
      flush();
      cur = { path: line.slice('worktree '.length).replace(/\\/g, '/'), branch: null, head: null };
    } else if (!cur) {
      // a line before any worktree header
    } else if (line.startsWith('HEAD ')) {
      cur.head = line.slice('HEAD '.length);
    } else if (line.startsWith('branch ')) {
      const b = line.slice('branch '.length);
      cur.branch = b.startsWith('refs/heads/') ? b.slice('refs/heads/'.length) : b;
    }
  }
  flush();
  return out;
}

/** carryInSection(body) -> the trimmed lines under a `## Carry-in dispositions` heading, up to the next `#` or `##` heading; null when absent or empty. */
export function carryInSection(body) {
  if (typeof body !== 'string') return null;
  const lines = body.split(/\r?\n/);
  const at = lines.findIndex((l) => SECTION_HEADING.test(l.trim()));
  if (at < 0) return null;
  const out = [];
  for (let i = at + 1; i < lines.length; i += 1) {
    if (/^#{1,2}(\s|$)/.test(lines[i])) break;
    out.push(lines[i]);
  }
  const text = out.join('\n').trim();
  return text === '' ? null : text;
}

function candidateFor(candidates, ref) {
  const hit = candidates.filter((c) => idMatch(c.id, ref));
  return hit.length === 1 ? hit[0] : null;
}

/**
 * scopeNeeds(candidates, blocked, scopes) -> the full ids at both ends of every unsatisfied dependency edge
 * in `blocked` that joins two candidates with the same parent, in first-seen order, minus the ids `scopes` covers.
 */
export function scopeNeeds(candidates, blocked, scopes) {
  const covered = new Set(isPlain(scopes) ? Object.keys(scopes) : []);
  const out = [];
  const add = (id) => {
    if (!covered.has(id) && !out.includes(id)) out.push(id);
  };
  for (const b of Array.isArray(blocked) ? blocked : []) {
    const to = candidateFor(candidates, b.itemId);
    if (!to || !to.parentId) continue;
    for (const bb of b.blockedBy || []) {
      if (bb.satisfied) continue;
      const from = candidateFor(candidates, bb.itemId);
      if (!from || !from.parentId || !idMatch(from.parentId, to.parentId)) continue;
      add(from.id);
      add(to.id);
    }
  }
  return out;
}

async function read(io, tool, body) {
  const r = await io.call(tool, body);
  if (!r || r.ok !== true) {
    const sel = body.select || (Array.isArray(body.ops) && body.ops[0] && body.ops[0].op) || '';
    throw stop(3, 'refused', { read: `${tool} ${sel}`.trim(), code: (r && r.error && r.error.code) || 'no_response' });
  }
  return r;
}

// Every page of a list-shaped read, following page.next until it is absent.
async function pages(io, tool, body, itemsKey = 'items') {
  const out = [];
  let cursor;
  for (let n = 0; n < MAX_PAGES; n += 1) {
    const r = await read(io, tool, { ...body, limit: PAGE_LIMIT, ...(cursor ? { cursor } : {}) });
    const rows = r.data && Array.isArray(r.data[itemsKey]) ? r.data[itemsKey] : [];
    out.push(...rows);
    const next = r.data && r.data.page && r.data.page.next;
    if (!next) return out;
    if (next === cursor) break;
    cursor = next;
  }
  throw stop(3, 'refused', { read: `${tool} ${body.select || ''}`.trim(), code: 'paging_did_not_end' });
}

async function hasChildren(io, id) {
  const r = await read(io, 'items_read', { select: 'list', parentId: id, limit: 1 });
  return Array.isArray(r.data && r.data.items) && r.data.items.length > 0;
}

function mapBlocked(row) {
  const entry = {
    itemId: row.id,
    role: row.role,
    blockType: row.blockType,
    blockedBy: (Array.isArray(row.blockers) ? row.blockers : []).map((x) => ({
      itemId: x.id,
      role: x.role,
      effectiveUnblockRole: x.threshold,
      satisfied: x.satisfied
    }))
  };
  if (row.reason !== undefined && row.reason !== null) entry.reason = row.reason;
  if (row.decisionId !== undefined && row.decisionId !== null) entry.decisionId = row.decisionId;
  if (row.resumable !== undefined && row.resumable !== null) entry.resumable = row.resumable;
  return entry;
}

// Candidate ids (as the list reads answer them) of an ancestor scope: next, the join of dependency-blocked items
// whose every unsatisfied blocker is itself a candidate (a fixpoint), then the work-role rows.
function ancestorOrder(nextRows, workRows, blockedRows, excludeId) {
  const skip = (id) => excludeId !== null && idMatch(id, excludeId);
  const ready = [];
  const work = [];
  const known = [];
  const knows = (id) => known.some((k) => idMatch(k, id));
  for (const row of nextRows) {
    if (!skip(row.id) && !knows(row.id)) {
      ready.push(row.id);
      known.push(row.id);
    }
  }
  for (const row of workRows) {
    if (!skip(row.id) && !knows(row.id)) {
      work.push(row.id);
      known.push(row.id);
    }
  }
  const joins = [];
  let grew = true;
  while (grew) {
    grew = false;
    for (const row of blockedRows) {
      if (row.blockType !== 'dependency' || row.role === 'terminal' || skip(row.id) || knows(row.id)) continue;
      const open = (Array.isArray(row.blockers) ? row.blockers : []).filter((b) => !b.satisfied);
      if (open.length === 0 || !open.every((b) => knows(b.id))) continue;
      joins.push(row.id);
      known.push(row.id);
      grew = true;
    }
  }
  return { ready, joins, work };
}

async function build(input, io) {
  const errors = snapshotArgErrors({ ...input, hasOut: true });
  if (errors.length) throw stop(2, 'invalid args', errors.join('; '));
  const warnings = [];

  // Local facts first: an unusable checkout or profile is a usage error, found before any server read.
  const top = io.git(['rev-parse', '--show-toplevel']);
  const topText = top && top.status === 0 ? String(top.stdout || '').trim() : '';
  if (topText === '') throw stop(2, 'invalid args', 'git rev-parse --show-toplevel failed: not a git checkout');
  const repoRoot = topText.replace(/\\/g, '/').replace(/\/+$/, '');
  const profileRead = io.readProfile(repoRoot);
  if (profileRead.kind === 'invalid') throw stop(2, 'invalid args', `${repoRoot}/.taskorchestrator/run-profile.json is not valid JSON`);
  const profile = profileRead.kind === 'ok' ? profileRead.value : {};

  const rootItem = (await read(io, 'items_read', { select: 'get', itemId: input.root })).data;
  if (!isPlain(rootItem) || typeof rootItem.id !== 'string') throw stop(3, 'refused', { read: 'items_read get', code: 'bad_answer' });
  if (rootItem.parentId !== null && rootItem.parentId !== undefined) throw stop(2, 'invalid args', `--root ${input.root} is not a root item (depth 0)`);
  const rootId = rootItem.id;
  const sameRoot = (it) => it.rootId === undefined || it.rootId === null || idMatch(it.rootId, rootId);

  const rulesAnswer = await read(io, 'config', { ops: [{ op: 'rules', rootId }] });
  const ruleRes = rulesAnswer.data && rulesAnswer.data.results && rulesAnswer.data.results[0];
  if (!ruleRes || ruleRes.ok === false || !Array.isArray(ruleRes.rules)) {
    throw stop(3, 'refused', { read: 'config rules', code: (ruleRes && ruleRes.error && ruleRes.error.code) || 'bad_answer' });
  }
  const rulesServed = ruleRes.rules.map((r) => ({ key: r.key, rulesVersion: r.rulesVersion }));

  // Scope: the candidate id list (with its get answers), the skipped list, the blocked rows.
  let ancestorId = null;
  let seeds; // [{id, it?}] in candidate order
  const skipped = [];
  let blockedRows;
  const given = [];
  if (typeof input.ancestor === 'string' && input.ancestor.trim() !== '') {
    const anc = (await read(io, 'items_read', { select: 'get', itemId: input.ancestor })).data;
    if (!isPlain(anc) || typeof anc.id !== 'string') throw stop(3, 'refused', { read: 'items_read get', code: 'bad_answer' });
    if (!sameRoot(anc)) throw stop(2, 'invalid args', `--ancestor ${input.ancestor} belongs to another root`);
    ancestorId = anc.id;
    const ancHasChildren = await hasChildren(io, anc.id);
    const scope = { ancestorId };
    const nextRows = await pages(io, 'items_read', { select: 'next', ...scope });
    const workRows = await pages(io, 'items_read', { select: 'list', role: 'work', ...scope });
    blockedRows = await pages(io, 'items_read', { select: 'blocked', ...scope });
    const order = ancestorOrder(nextRows, workRows, blockedRows, ancHasChildren ? anc.id : null);
    seeds = [...order.ready, ...order.joins, ...order.work].map((id) => ({ id }));
  } else {
    seeds = [];
    for (const raw of input.items) {
      const it = (await read(io, 'items_read', { select: 'get', itemId: raw, fields: ['createdAt'] })).data;
      if (!isPlain(it) || typeof it.id !== 'string') throw stop(3, 'refused', { read: 'items_read get', code: 'bad_answer' });
      if (!sameRoot(it)) throw stop(2, 'invalid args', `item ${raw} belongs to another root`);
      given.push(it.id);
      if (seeds.some((s) => s.id === it.id) || skipped.some((s) => s.id === it.id)) continue;
      if (it.role === 'queue' || it.role === 'work') seeds.push({ id: it.id, it });
      else skipped.push({ id: it.id, role: it.role });
    }
    const all = await pages(io, 'items_read', { select: 'blocked', ancestorId: rootId });
    blockedRows = all.filter((row) => given.some((g) => idMatch(row.id, g)));
  }

  const candidates = [];
  const schemas = {};
  const answers = {};
  const noteOwners = {};
  const carryIns = {};
  let features = null;
  const seen = new Set();
  for (const seed of seeds) {
    const it = seed.it || (await read(io, 'items_read', { select: 'get', itemId: seed.id, fields: ['createdAt'] })).data;
    if (!isPlain(it) || typeof it.id !== 'string' || seen.has(it.id)) continue;
    seen.add(it.id);
    const kids = await hasChildren(io, it.id);
    const cand = {
      id: it.id,
      short: it.id.slice(0, 8),
      title: it.title,
      priority: it.priority
    };
    if (typeof it.complexity === 'number') cand.complexity = it.complexity;
    if (typeof it.rank === 'number') cand.rank = it.rank;
    if (typeof it.createdAt === 'string' && it.createdAt) cand.createdAt = it.createdAt;
    Object.assign(cand, {
      type: it.type,
      role: it.role,
      parentId: it.parentId === undefined ? null : it.parentId,
      traits: Array.isArray(it.traits) ? it.traits : [],
      hasChildren: kids,
      source: it.role === 'work' ? 'work' : 'ready'
    });
    candidates.push(cand);
    const answer = await io.call('items_read', { select: 'schema', itemId: it.id });
    answers[it.id] = answer;
    schemas[it.id] = projectSchema(answer);
    if (features === null && answer && answer.ok === true && isPlain(answer.data) && Array.isArray(answer.data.features)) {
      features = answer.data.features;
    }
  }

  if (features === null) {
    const wk = await io.wellKnown();
    if (isPlain(wk) && Array.isArray(wk.features)) features = wk.features;
    else throw stop(3, 'refused', { reason: 'no features source: no schema answer carried features and the well-known document is unavailable' });
  }

  // parents
  const parents = {};
  for (const c of candidates) {
    if (!c.parentId || c.parentId in parents) continue;
    const ctx = (await read(io, 'items_read', { select: 'context', itemId: c.parentId })).data;
    const gate = (ctx && ctx.gateStatus) || {};
    parents[c.parentId] = { role: ctx && ctx.item && ctx.item.role, canAdvance: gate.canAdvance, missing: Array.isArray(gate.missing) ? gate.missing : [] };
  }

  // note owners (work candidates) and carry-ins (every candidate)
  for (const c of candidates) {
    const schema = schemas[c.id];
    const schemaNotes = schema.status === 'ok' ? schema.notes : [];
    if (c.source === 'work') {
      const keys = schemaNotes.map((n) => n.key);
      noteOwners[c.id] = [];
      if (keys.length > 0) {
        const rows = await pages(io, 'notes_read', { select: 'list', itemId: c.id, keys });
        noteOwners[c.id] = rows.filter((n) => n.owner && n.owner.seat).map((n) => ({ key: n.key, seat: n.owner.seat }));
      }
    }
    const rows = await pages(io, 'notes_read', { select: 'list', itemId: c.id });
    const keys = carryInKeys(rows.map((n) => n.key));
    if (keys.length === 0) continue;
    let dispositions = null;
    const rec = await io.call('notes_read', { select: 'get', itemId: c.id, key: RECORD_KEY });
    if (rec && rec.ok === true) {
      if (!isPlain(rec.data) || typeof rec.data.body !== 'string') throw stop(3, 'refused', { read: 'notes_read get', code: 'bad_answer' });
      dispositions = rec.data.body;
    } else if (!rec || !rec.error || rec.error.code !== 'not_found') {
      throw stop(3, 'refused', { read: 'notes_read get', code: (rec && rec.error && rec.error.code) || 'no_response' });
    } else {
      const present = new Set(rows.map((n) => n.key));
      const planner = schemaNotes.find((n) => n.role === 'queue' && (n.seat === undefined || n.seat === null || n.seat === 'planner') && present.has(n.key));
      if (planner) {
        const note = await read(io, 'notes_read', { select: 'get', itemId: c.id, key: planner.key });
        dispositions = carryInSection(note.data && note.data.body);
      }
    }
    carryIns[c.id] = { keys, dispositions };
  }

  const blocked = blockedRows.map(mapBlocked);
  const scopes = isPlain(input.scopes) ? input.scopes : undefined;
  const needs = scopeNeeds(candidates, blocked, scopes);

  // git
  if (!input.noFetch) {
    const f = io.fetchOrigin();
    if (!f || f.status !== 0) warnings.push('git fetch origin failed');
  }
  const origin = io.git(['rev-parse', 'origin/main']);
  const originText = origin && origin.status === 0 ? String(origin.stdout || '').trim() : '';
  if (originText === '') warnings.push('origin/main not resolvable');
  const wt = io.git(['worktree', 'list', '--porcelain']);
  if (!wt || wt.status !== 0) warnings.push('git worktree list failed');
  const worktrees = wt && wt.status === 0 ? parseWorktreePorcelain(wt.stdout) : {};

  const probe = await io.probe();

  const snapshot = {
    contract: CONTRACT,
    rootId,
    ancestorId,
    capturedAt: input.now,
    features,
    rulesServed,
    candidates,
    blocked,
    schemas
  };
  if (scopes !== undefined) snapshot.scopes = scopes;
  if (Object.keys(carryIns).length > 0) snapshot.carryIns = carryIns;
  Object.assign(snapshot, {
    noteOwners,
    parents,
    profile,
    probe,
    git: { repoRoot, originMain: originText === '' ? null : originText, worktrees }
  });

  const v = validateSnapshot(snapshot);
  if (!v.ok) throw stop(3, 'refused', v.errors);
  return { ok: true, snapshot, summary: { candidates: candidates.length, needs: { scopes: needs }, skipped, warnings } };
}

export async function buildSnapshot(input, io) {
  try {
    return await build(input || {}, io);
  } catch (e) {
    if (e && e.snapshotStop) return { ok: false, exit: e.exit, error: e.error, detail: e.detail };
    throw e;
  }
}
