#!/usr/bin/env node
// coordinator-tiers.mjs - the director's coordinator tiers and their launch profiles. Pure at
// import; the CLI runs only when this file is the entry point.
//
//   node coordinator-tiers.mjs profile --tier N|2b [--budget-usd X] [--scope S] [--target T] [--model M] [--effort E] [--prompt-file F]
//
// prints launchProfile(N, ...) as JSON. The director probes its own tool list with the same rule
// selectTier uses (see skills/director/references/coordinator-tiers.md).

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { forClass } from './lib/dispatch-policy.mjs';

/** Tiers 1, 2, 2b, 3 and 4 in preference order: what each needs and where its coordinator runs. */
export const TIERS = Object.freeze([
  Object.freeze({ tier: 1, needs: Object.freeze(['start_session', 'SendMessage|send_message']), coordinator: 'desktop session' }),
  Object.freeze({ tier: 2, needs: Object.freeze(['Workflow']), coordinator: 'background agent plus Workflow in the director session' }),
  Object.freeze({ tier: '2b', needs: Object.freeze(['REPL', 'Agent']), coordinator: 'Code Mode coordinator subagent running Method B' }),
  Object.freeze({ tier: 3, needs: Object.freeze(['claude CLI']), coordinator: 'headless claude -p process' }),
  Object.freeze({ tier: 4, needs: Object.freeze([]), coordinator: 'inline in the director session' }),
]);

const TIER4_WARNING =
  'No coordinator tier is available: the run executes inline in the director session, which breaks the altitude rule (the director reads and dispatches seats itself). Keep the run small, or make the Workflow tool or the claude CLI available.';

const REPROBE_WARNING =
  'Only REPL and Poll were probed: in Code Mode, list the tools a script can call and probe again.';

/** True when `name` is `requirement` or ends with `__` + `requirement`. */
function toolMatches(name, requirement) {
  return typeof name === 'string' && (name === requirement || name.endsWith(`__${requirement}`));
}

function hasTool(tools, requirement) {
  return tools.some((t) => toolMatches(t, requirement));
}

/**
 * The best tier for the probed tool list: 1 needs `start_session` and (`SendMessage` or
 * `send_message`), 2 needs `Workflow`, '2b' needs `REPL` and `Agent` (Code Mode), 3 needs `claudeCli === true`, else 4. `warning` is null for
 * tiers 1, 2, 2b and 3 and the altitude warning for tier 4. A non-empty list made only of `REPL` and `Poll`
 * entries (at least one `REPL`) when neither tier 1 nor tier 2 is met is the visible pair of Code
 * Mode, not the real list: it returns `{ tier: null, reprobe: true, warning }` (ahead of the
 * claudeCli and tier-4 branches) so the director lists the tools a script can call and probes again.
 * Any other list never carries a `reprobe` key.
 */
export function selectTier({ tools = [], claudeCli = false } = {}) {
  const list = Array.isArray(tools) ? tools : [];
  if (hasTool(list, 'start_session') && (hasTool(list, 'SendMessage') || hasTool(list, 'send_message'))) {
    return { tier: 1, warning: null };
  }
  if (hasTool(list, 'Workflow')) return { tier: 2, warning: null };
  if (hasTool(list, 'REPL') && hasTool(list, 'Agent')) return { tier: '2b', warning: null };
  if (
    list.length > 0 &&
    hasTool(list, 'REPL') &&
    list.every((t) => toolMatches(t, 'REPL') || toolMatches(t, 'Poll'))
  ) {
    return { tier: null, reprobe: true, warning: REPROBE_WARNING };
  }
  if (claudeCli === true) return { tier: 3, warning: null };
  return { tier: 4, warning: TIER4_WARNING };
}

/** The tier-2b coordinator brief: 10 lines, pinned verbatim in skills/director/references/coordinator-tiers.md. */
function tier2bPrompt(scope, target) {
  return [
    `Run /task-orchestrator:run-wave ${scope} --method B --altitude-safe.`,
    'You are a tier-2b coordinator: a Code Mode subagent. Run every step that skills/run-wave/references/code-mode.md covers through its recipe; you have no Workflow tool.',
    'Report to "main" with SendMessage, only the output of the report helper (events planned, decision, paused, closed). Never send note bodies, diffs or tokens.',
    'Run each round as a short script, always well under the ~600 s REPL script limit (it binds a subagent whatever its $ timeout), and never wait inside a script.',
    "Between rounds, wait on Poll with no script running: an idle coordinator is woken by its seats' task notifications.",
    "Take each seat's envelope from its task-notification result text, never from the task .output file, which stays empty.",
    'Never use a standalone sleep: a shell guard blocks it.',
    "After decision or paused, wait for the director's message; never answer a decision yourself. Stop on a director close and report closed.",
    "Never ask the user anything: put what you need (a decision, a permission) on the report's needs: line.",
    `At post-run Step 3 pass --coordinator coordinator:${target} to provenance (exact label only).`,
  ].join('\n');
}

/**
 * The launch profile for `tier`. Tier 1: start_session takes model, effort and permission_mode;
 * the output style is set after start (set_session_output_style); no start_session parameter
 * carries crossSessionInbound, so the director sets it in desktop settings and checks it with
 * get_settings before launch. Tier 3 requires `opts.budgetUsd` (a finite number > 0) and
 * `opts.prompt` (a non-empty string), checked in that order. Tier 2b requires `opts.scope` (a non-empty
 * string) and returns one Code Mode Agent call (no run_in_background: Code Mode Agent has none);
 * `target` is `opts.target` or the first token of scope with hyphens removed, cut to 8 characters.
 * Any other tier throws.
 * Model and effort (tiers 1, 2b and 3) default to the dispatch policy's `coordinator` class; a
 * non-empty `opts.model` / `opts.effort` overrides each.
 */
export function launchProfile(tier, opts = {}) {
  const given = opts && typeof opts === 'object' ? opts : {};
  const cls = (tier === 1 || tier === '2b' || tier === 3) ? forClass('coordinator') : {};
  const pick = (v, d) => (v !== undefined && v !== null && v !== '' ? v : d);
  const o = { ...given, model: pick(given.model, cls.model), effort: pick(given.effort, cls.effort) };
  switch (tier) {
    case 1:
      return {
        tier: 1,
        startSession: {
          initiation: 'user_asked',
          context: 'fresh',
          model: o.model,
          effort: o.effort,
          permission_mode: o.permissionMode,
        },
        outputStyle: o.outputStyle ?? null,
        crossSessionInbound: 'accept',
        messaging: 'SendMessage',
      };
    case 2:
      return { tier: 2, workflow: 'task-orchestrator:implement-wave', args: { altitudeSafe: true } };
    case '2b': {
      const scope = o.scope;
      if (typeof scope !== 'string' || scope.trim() === '') {
        throw new Error('tier 2b needs scope: a non-empty string (pass --scope)');
      }
      const target = typeof o.target === 'string' && o.target !== ''
        ? o.target
        : scope.trim().split(/[\s,]+/)[0].replace(/-/g, '').slice(0, 8);
      return {
        tier: '2b',
        agent: {
          description: `coordinator:${target}`,
          subagent_type: 'general-purpose',
          model: o.model,
          effort: o.effort,
          prompt: tier2bPrompt(scope, target),
        },
        method: 'B',
        reportTo: 'main',
        messaging: 'SendMessage',
      };
    }
    case 3: {
      const budget = o.budgetUsd;
      if (typeof budget !== 'number' || !Number.isFinite(budget) || budget <= 0) {
        throw new Error('tier 3 needs budgetUsd: a finite number greater than 0');
      }
      if (typeof o.prompt !== 'string' || o.prompt === '') {
        throw new Error('tier 3 needs prompt: a non-empty string (pass --prompt-file)');
      }
      const argv = ['claude', '-p', o.prompt, '--allowedTools', 'Workflow', '--max-budget-usd', String(budget)];
      argv.push('--model', o.model, '--effort', o.effort);
      return { tier: 3, argv };
    }
    case 4:
      return { tier: 4, inline: true, warning: TIER4_WARNING };
    default:
      throw new Error(`unknown coordinator tier: ${tier} (expected 1, 2, 2b, 3 or 4)`);
  }
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => {
      if (i + 1 >= argv.length) throw new Error(`${a} needs a value`);
      return argv[++i];
    };
    if (a === '--tier') { const v = next(); out.tier = v === '2b' ? v : Number(v); }
    else if (a === '--scope') out.scope = next();
    else if (a === '--target') out.target = next();
    else if (a === '--budget-usd') out.budgetUsd = Number(next());
    else if (a === '--model') out.model = next();
    else if (a === '--effort') out.effort = next();
    else if (a === '--prompt-file') out.promptFile = next();
    else throw new Error(`unknown argument: ${a}`);
  }
  return out;
}

function main(argv) {
  const [cmd, ...rest] = argv;
  if (cmd !== 'profile') {
    process.stderr.write('usage: coordinator-tiers.mjs profile --tier N|2b [--budget-usd X] [--scope S] [--target T] [--model M] [--effort E] [--prompt-file F]\n');
    return 2;
  }
  try {
    const a = parseArgs(rest);
    const opts = {};
    if (a.budgetUsd !== undefined) opts.budgetUsd = a.budgetUsd;
    if (a.scope !== undefined) opts.scope = a.scope;
    if (a.target !== undefined) opts.target = a.target;
    if (a.model !== undefined) opts.model = a.model;
    if (a.effort !== undefined) opts.effort = a.effort;
    if (a.promptFile !== undefined) opts.prompt = readFileSync(a.promptFile, 'utf-8');
    process.stdout.write(JSON.stringify(launchProfile(a.tier, opts), null, 2) + '\n');
    return 0;
  } catch (err) {
    process.stderr.write(`coordinator-tiers: ${err.message}\n`);
    return 1;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exit(main(process.argv.slice(2)));
}
