// checks.mjs -- the graders of the retrospective scenario evals, one function per check id. Grading is
// deterministic: REST reads of the throwaway server, the fake gh log and a scan of the session transcripts.
// There is no model judge. A check answers { ok: boolean, detail: string }.
//
// ctx (built by run-evals.mjs per stage):
//   read(tool, body)        -> the answer's data (REST read); throws on an error answer
//   rootId, root8           the seeded project root
//   stage                   the stage definition (id, kind, expectGh, ...)
//   stageStart              ISO instant taken before the stage began
//   runIds                  the run ids the stage covers
//   otherRunIds             the run ids of the other features of the scenario (not covered by the stage)
//   seeded                  facts of the seed: { fingerprint, defectMessage, projectName, trendId, globalProposalsRootId, ... }
//   memory                  scratch object shared by the stages of one scenario (earlier stage facts)
//   hook                    hook stages: { status, stdout, json }
//   live                    live stages: { exitCode, transcripts: string[] (text of every transcript of the session) }
//   ghCalls()               the fake gh calls so far: [{ argv, body }]

const FOOTER = 'Reported automatically by task-orchestrator session-retrospective';

const pass = (detail = '') => ({ ok: true, detail });
const fail = (detail) => ({ ok: false, detail });

async function newRetros(ctx) {
  const data = await ctx.read('items_read', { select: 'list', type: 'session-retrospective', createdAfter: ctx.stageStart, limit: 50 });
  return Array.isArray(data.items) ? data.items : [];
}

async function newItems(ctx, filter) {
  const data = await ctx.read('items_read', { select: 'list', createdAfter: ctx.stageStart, limit: 100, ...filter });
  return Array.isArray(data.items) ? data.items : [];
}

async function noteKeys(ctx, itemId) {
  const data = await ctx.read('notes_read', { select: 'list', itemId, limit: 200 });
  return (Array.isArray(data.items) ? data.items : []).map((n) => n.key);
}

async function marksOf(ctx, runId) {
  const data = await ctx.read('runs_read', { select: 'get', runId });
  return Object.keys((data && data.marks) || {});
}

const tagsOf = (it) => (Array.isArray(it.tags) ? it.tags : []);
const callsOf = (ctx, a, b) => ctx.ghCalls().filter((c) => c.argv[0] === a && c.argv[1] === b);

export const CHECKS = {
  // ---- common to every live stage ------------------------------------------------------------------------
  async C1(ctx) {
    const items = await newRetros(ctx);
    if (items.length !== 1) return fail(`expected 1 new session-retrospective item, found ${items.length}`);
    const title = String(items[0].title || '');
    const missing = ctx.runIds.filter((id) => !title.includes(`run:${id}`));
    return missing.length === 0 ? pass(items[0].id) : fail(`title lacks run:${missing.join(', run:')}: ${title}`);
  },
  async C2(ctx) {
    const items = await newRetros(ctx);
    if (items.length !== 1) return fail(`expected 1 new session-retrospective item, found ${items.length}`);
    const item = await ctx.read('items_read', { select: 'get', itemId: items[0].id });
    if (item.role !== 'terminal') return fail(`retrospective role is ${item.role}`);
    const keys = await noteKeys(ctx, items[0].id);
    const missing = ['session-metrics', 'workflow-evaluation', 'improvement-signals', 'actions-taken'].filter((k) => !keys.includes(k));
    return missing.length === 0 ? pass() : fail(`missing notes: ${missing.join(', ')}`);
  },
  async C3(ctx) {
    const bad = [];
    for (const id of ctx.runIds) if (!(await marksOf(ctx, id)).includes('retro')) bad.push(id);
    return bad.length === 0 ? pass() : fail(`no retro mark on ${bad.join(', ')}`);
  },
  async C4(ctx) {
    const hits = (ctx.live.transcripts || []).filter((t) => t.includes('"name":"AskUserQuestion"')).length;
    if ((ctx.live.transcripts || []).length === 0) return fail('no transcript found for the session id');
    return hits === 0 ? pass(`${ctx.live.transcripts.length} transcripts scanned`) : fail(`AskUserQuestion tool_use in ${hits} transcript(s)`);
  },
  async C5(ctx) {
    const n = ctx.ghCalls().length;
    if (ctx.stage.expectGh) return pass(`${n} fake gh calls (the scenario expects some)`);
    return n === 0 ? pass() : fail(`${n} fake gh calls, expected none`);
  },
  async C6(ctx) {
    return ctx.live.exitCode === 0 ? pass() : fail(`claude -p exit ${ctx.live.exitCode}; stderr: ${String(ctx.live.stderr || '').trim().slice(-300)}; last event: ${String(ctx.live.tail || '').slice(0, 500)}`);
  },

  // ---- scenario 1: clean ----------------------------------------------------------------------------------
  async H1(ctx) {
    if (ctx.hook.stdout.trim() !== '{}') return fail(`hook stdout was ${ctx.hook.stdout.slice(0, 200)}`);
    const bad = [];
    for (const id of ctx.runIds) {
      const marks = await marksOf(ctx, id);
      for (const k of ['retro-skipped', 'retro']) if (!marks.includes(k)) bad.push(`${id.slice(0, 8)} lacks ${k}`);
    }
    return bad.length === 0 ? pass() : fail(bad.join('; '));
  },
  async H2(ctx) {
    const text = ctx.hook.json && ctx.hook.json.hookSpecificOutput && ctx.hook.json.hookSpecificOutput.additionalContext;
    if (typeof text !== 'string') return fail(`no directive in hook stdout: ${ctx.hook.stdout.slice(0, 200)}`);
    const count = text.split('Retrospective dispatch (mode: dispatch)').length - 1;
    if (count !== 1) return fail(`${count} directives`);
    const missing = ctx.runIds.filter((id) => !text.includes(`--run ${id}`));
    if (missing.length > 0) return fail(`directive lacks ${missing.join(', ')}`);
    const foreign = ctx.otherRunIds.filter((id) => text.includes(id));
    return foreign.length === 0 ? pass() : fail(`directive names another feature's run ${foreign.join(', ')}`);
  },
  async L1(ctx) {
    const bugs = await newItems(ctx, { tags: ['bug'] });
    if (bugs.length > 0) return fail(`${bugs.length} new item(s) tagged bug`);
    const proposals = await newItems(ctx, { type: 'improvement-proposal' });
    return proposals.length === 0 ? pass() : fail(`${proposals.length} new improvement-proposal item(s)`);
  },

  // ---- scenario 2: defect ----------------------------------------------------------------------------------
  async D1(ctx) {
    const obs = (await newItems(ctx, { type: 'agent-observation' })).filter((it) => tagsOf(it).includes('retro-reported'));
    if (obs.length !== 1) return fail(`expected 1 new retro-reported agent-observation, found ${obs.length}`);
    const o = obs[0];
    const want = ['agent-observation', 'bug', 'retro-reported'].filter((t) => !tagsOf(o).includes(t));
    if (want.length > 0) return fail(`tags lack ${want.join(', ')}`);
    if (o.role === 'terminal') return fail('observation is terminal, expected open');
    const item = await ctx.read('items_read', { select: 'get', itemId: o.id });
    const line1 = String(item.description || '').split(/\r?\n/)[0].trim();
    if (line1 !== `Fingerprint: ${ctx.seeded.fingerprint}`) return fail(`description line 1 is "${line1}"`);
    ctx.memory.observationId = o.id;
    return pass(o.id);
  },
  async D2(ctx) {
    const creates = callsOf(ctx, 'issue', 'create');
    if (creates.length !== 1) {
      const ran = (ctx.live && ctx.live.transcripts ? ctx.live.transcripts : []).some((t) => /"name":"Bash","input":\{"command":"[^"]*bug-report (--in|--root)/.test(t));
      return fail(`${creates.length} issue create calls, expected 1 (the session ${ran ? 'ran' : 'never ran'} the bug-report command)`);
    }
    const c = creates[0];
    if (c.argv.includes('--label')) return fail('create argv carries --label');
    const body = c.body || '';
    if (!body.includes(`Fingerprint: ${ctx.seeded.fingerprint}`)) return fail('body lacks the Fingerprint line');
    if (!body.includes(FOOTER)) return fail('body lacks the footer');
    const leaks = [ctx.seeded.defectMessage, ctx.rootId, ctx.seeded.projectName].filter((s) => s && body.toLowerCase().includes(String(s).toLowerCase()));
    return leaks.length === 0 ? pass() : fail(`body holds ${leaks.length} private value(s)`);
  },
  async D3(ctx) {
    const id = ctx.memory.observationId;
    if (!id) return fail('D1 found no observation');
    const note = await ctx.read('notes_read', { select: 'get', itemId: id, key: 'observation-detail' });
    const last = String(note.body || '').trim().split(/\r?\n/).pop().trim();
    return /^github-issue: https:\/\/github\.com\/jpicklyk\/task-orchestrator\/issues\/990001$/.test(last) ? pass() : fail(`last line is "${last}"`);
  },
  async D4(ctx) {
    const id = ctx.memory.observationId;
    if (!id) return fail('the 2a observation is unknown');
    const bugs = (await ctx.read('items_read', { select: 'list', type: 'agent-observation', tags: ['bug'], limit: 50 })).items || [];
    if (bugs.length !== 1) return fail(`${bugs.length} bug observations, expected only the 2a one`);
    const keys = await noteKeys(ctx, id);
    return keys.some((k) => k.startsWith('evidence-')) ? pass() : fail(`no evidence- note on the 2a observation (keys: ${keys.join(', ')})`);
  },
  async D5(ctx) {
    const comments = callsOf(ctx, 'issue', 'comment');
    const creates = callsOf(ctx, 'issue', 'create');
    if (comments.length !== 1) return fail(`${comments.length} issue comment calls, expected 1`);
    return creates.length === 1 ? pass() : fail(`${creates.length} issue create calls in total, expected the one from 2a`);
  },

  // ---- scenario 3: project friction -----------------------------------------------------------------------
  async P1(ctx) {
    const trend = await ctx.read('items_read', { select: 'get', itemId: ctx.seeded.trendId });
    if (!/Sessions:\s*2\b/.test(String(trend.summary || ''))) return fail(`trend summary: "${trend.summary}"`);
    const keys = await noteKeys(ctx, ctx.seeded.trendId);
    return keys.some((k) => k.startsWith('evidence-')) ? pass() : fail('no evidence note on the seeded trend');
  },
  async P2(ctx) {
    const props = await newItems(ctx, { type: 'improvement-proposal' });
    if (props.length !== 1) return fail(`expected 1 new improvement-proposal, found ${props.length}`);
    const p = props[0];
    const item = await ctx.read('items_read', { select: 'get', itemId: p.id });
    if (!item.parentId) return fail('the proposal has no parent');
    const parent = await ctx.read('items_read', { select: 'get', itemId: item.parentId });
    if (parent.title !== 'Improvement Proposals' || String(parent.parentId || '').slice(0, 8) !== ctx.rootId.slice(0, 8)) {
      return fail(`parent is "${parent.title}" under ${String(parent.parentId).slice(0, 8)}, expected the per-project container directly under the root`);
    }
    const note = await ctx.read('notes_read', { select: 'get', itemId: p.id, key: 'proposal' });
    return /Scope:\s*project-specific/i.test(String(note.body || '')) ? pass() : fail('proposal note lacks "Scope: project-specific"');
  },
  async P3(ctx) {
    const data = await ctx.read('items_read', { select: 'list', ancestorId: ctx.seeded.globalProposalsRootId, limit: 100 });
    const below = (data.items || []).filter((it) => !String(it.id).startsWith(ctx.seeded.globalProposalsRootId.slice(0, 8)));
    return below.length === 0 ? pass() : fail(`${below.length} item(s) under the global Improvement Proposals root`);
  },
  async P4(ctx) {
    const trends = await newItems(ctx, { type: 'retrospective-trend' });
    const globals = trends.filter((t) => !tagsOf(t).includes(`project-${ctx.root8}`));
    const bad = globals.filter((t) => !/fix-cycle-cost|retro-cost/.test(String(t.title || '')));
    return bad.length === 0 ? pass() : fail(`global trend(s): ${bad.map((t) => t.title).join(' | ')}`);
  },
  async P5(ctx) {
    const n = ctx.ghCalls().length;
    return n === 0 ? pass() : fail(`${n} fake gh calls`);
  },

  // ---- scenario 4: stale ----------------------------------------------------------------------------------
  async T1(ctx) {
    const items = await newRetros(ctx);
    if (items.length !== 1) return fail(`expected 1 new session-retrospective item, found ${items.length}`);
    return String(items[0].title).startsWith('[stale] ') ? fail(`title starts [stale]: ${items[0].title}`) : pass();
  },
  async T2(ctx) {
    const items = await newRetros(ctx);
    if (items.length !== 1) return fail(`expected 1 new session-retrospective item, found ${items.length}`);
    return String(items[0].title).startsWith('[stale] ') ? pass() : fail(`title does not start [stale]: ${items[0].title}`);
  },
};

export const CHECK_IDS = Object.keys(CHECKS);

/** Check ids every live stage runs. */
export const LIVE_COMMON = ['C1', 'C2', 'C3', 'C4', 'C5', 'C6'];
