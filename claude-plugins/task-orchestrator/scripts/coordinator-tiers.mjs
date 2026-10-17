#!/usr/bin/env node
// coordinator-tiers.mjs - the director's coordinator tiers and their launch profiles. Pure at
// import; the CLI runs only when this file is the entry point.
//
//   node coordinator-tiers.mjs profile --tier N [--budget-usd X] [--model M] [--prompt-file F]
//
// prints launchProfile(N, ...) as JSON. The director probes its own tool list with the same rule
// selectTier uses (see skills/director/references/coordinator-tiers.md).

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/** Tiers 1-4 in preference order: what each needs and where its coordinator runs. */
export const TIERS = Object.freeze([
  Object.freeze({ tier: 1, needs: Object.freeze(['start_session', 'SendMessage|send_message']), coordinator: 'desktop session' }),
  Object.freeze({ tier: 2, needs: Object.freeze(['Workflow']), coordinator: 'background agent plus Workflow in the director session' }),
  Object.freeze({ tier: 3, needs: Object.freeze(['claude CLI']), coordinator: 'headless claude -p process' }),
  Object.freeze({ tier: 4, needs: Object.freeze([]), coordinator: 'inline in the director session' }),
]);

const TIER4_WARNING =
  'No coordinator tier is available: the run executes inline in the director session, which breaks the altitude rule (the director reads and dispatches seats itself). Keep the run small, or make the Workflow tool or the claude CLI available.';

/** True when `name` is `requirement` or ends with `__` + `requirement`. */
function toolMatches(name, requirement) {
  return typeof name === 'string' && (name === requirement || name.endsWith(`__${requirement}`));
}

function hasTool(tools, requirement) {
  return tools.some((t) => toolMatches(t, requirement));
}

/**
 * The best tier for the probed tool list: 1 needs `start_session` and (`SendMessage` or
 * `send_message`), 2 needs `Workflow`, 3 needs `claudeCli === true`, else 4. `warning` is null for
 * tiers 1-3 and the altitude warning for tier 4.
 */
export function selectTier({ tools = [], claudeCli = false } = {}) {
  const list = Array.isArray(tools) ? tools : [];
  if (hasTool(list, 'start_session') && (hasTool(list, 'SendMessage') || hasTool(list, 'send_message'))) {
    return { tier: 1, warning: null };
  }
  if (hasTool(list, 'Workflow')) return { tier: 2, warning: null };
  if (claudeCli === true) return { tier: 3, warning: null };
  return { tier: 4, warning: TIER4_WARNING };
}

/**
 * The launch profile for `tier`. Tier 1: start_session takes model, effort and permission_mode;
 * the output style is set after start (set_session_output_style); no start_session parameter
 * carries crossSessionInbound, so the director sets it in desktop settings and checks it with
 * get_settings before launch. Tier 3 requires `opts.budgetUsd` (a finite number > 0) and
 * `opts.prompt` (a non-empty string), checked in that order; any tier outside 1-4 throws.
 */
export function launchProfile(tier, opts = {}) {
  const o = opts && typeof opts === 'object' ? opts : {};
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
    case 3: {
      const budget = o.budgetUsd;
      if (typeof budget !== 'number' || !Number.isFinite(budget) || budget <= 0) {
        throw new Error('tier 3 needs budgetUsd: a finite number greater than 0');
      }
      if (typeof o.prompt !== 'string' || o.prompt === '') {
        throw new Error('tier 3 needs prompt: a non-empty string (pass --prompt-file)');
      }
      const argv = ['claude', '-p', o.prompt, '--allowedTools', 'Workflow', '--max-budget-usd', String(budget)];
      if (o.model !== undefined && o.model !== null && o.model !== '') argv.push('--model', o.model);
      return { tier: 3, argv };
    }
    case 4:
      return { tier: 4, inline: true, warning: TIER4_WARNING };
    default:
      throw new Error(`unknown coordinator tier: ${tier} (expected 1, 2, 3 or 4)`);
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
    if (a === '--tier') out.tier = Number(next());
    else if (a === '--budget-usd') out.budgetUsd = Number(next());
    else if (a === '--model') out.model = next();
    else if (a === '--prompt-file') out.promptFile = next();
    else throw new Error(`unknown argument: ${a}`);
  }
  return out;
}

function main(argv) {
  const [cmd, ...rest] = argv;
  if (cmd !== 'profile') {
    process.stderr.write('usage: coordinator-tiers.mjs profile --tier N [--budget-usd X] [--model M] [--prompt-file F]\n');
    return 2;
  }
  try {
    const a = parseArgs(rest);
    const opts = {};
    if (a.budgetUsd !== undefined) opts.budgetUsd = a.budgetUsd;
    if (a.model !== undefined) opts.model = a.model;
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
