#!/usr/bin/env node
// SubagentStart: injects a pointer to the seat's protocol rule plus the generated transition code lists.
// Early exits, checked in order (headless first, then the pre-existing guards):
//   1. Headless ralph iteration (TASK_ORCHESTRATOR_MODE=headless-iteration) — ralph iterations
//      never dispatch subagents, but exit silently anyway rather than assume that holds forever.
//   2. No config file is found by the locator (project or user scope) — the project is not
//      orchestrated, so non-orchestrated projects pay no context cost. A bare .taskorchestrator/
//      dir (e.g. ~/.taskorchestrator/ holding only deploy.env) no longer counts.
//   3. Stdin is parsed as JSON; empty or invalid stdin fails open (proceeds as today) rather than
//      blocking the agent.
//   4. The subagent's `agent_type` is exactly `workflow-subagent` — Claude workflow agents follow
//      their own script-driven transition logic (see workflow-authoring), so no protocol text is
//      injected for them at all, even if their dispatch happens to name an `implementer`/
//      `reviewer` seat internally.
//   5. The subagent's `agent_type` is not a phase-owner type (implementer/reviewer, bare or
//      plugin-qualified) — the protocol only applies to an agent that owns a work/review phase;
//      injecting it into Explore/Plan/general-purpose/etc. tells agents that own no phase to
//      call advance and commit, which is wrong for them.
//
// The injected text is a pointer, not the protocol: this hook cannot see the dispatch prompt's
// seat assignment, so it tells the agent to fetch the protocol rule its dispatch names (entry seat,
// in-phase seat or read-only agent), which owns the transition behaviour and the seat's conduct.
// It adds only what the rules cannot carry: the transition codes from the generated catalog.
import { readHookInput } from './lib/stdin.mjs';
import { emit } from './lib/emit.mjs';
import { isHeadlessIteration, isPhaseOwnerAgentType } from './execution-mode.mjs';
import { locateConfig } from './config-locator.mjs';
import { ERROR_KINDS } from './lib/tool-names.mjs';
import { readFileSync } from 'fs';

if (isHeadlessIteration()) {
  process.exit(0);
}

if (locateConfig().scope === 'none') {
  process.exit(0);
}

const hookInput = readHookInput() ?? {};

if (hookInput.agent_type === 'workflow-subagent') {
  process.exit(0);
}

if (!isPhaseOwnerAgentType(hookInput.agent_type)) {
  process.exit(0);
}

// The codes an `advance` transition can answer, from the generated catalog, partitioned by the
// generated ERROR_KINDS (permanent | transient | shedding). `gate_blocked` means a required note is
// missing; it is not an already-in-phase signal and is named separately.
function transitionCodes() {
  try {
    const catalog = JSON.parse(readFileSync(new URL('./lib/tool-catalog.json', import.meta.url), 'utf8'));
    const errors = catalog?.tools?.advance?.entries?.transition?.errors;
    return Array.isArray(errors) ? errors : [];
  } catch {
    return [];
  }
}

function codeLists() {
  const codes = transitionCodes().filter((c) => c !== 'gate_blocked');
  const kind = (c) => ERROR_KINDS[c];
  const list = (arr) => arr.map((c) => `\`${c}\``).join(', ');
  return {
    retry: list(codes.filter((c) => kind(c) === 'transient' || kind(c) === 'shedding')),
    permanent: list(codes.filter((c) => kind(c) === 'permanent')),
  };
}

const { retry, permanent } = codeLists();

const protocol = `## Agent-Owned-Phase Protocol

Your dispatch names a protocol rule (\`protocol.entry-seat\`, \`protocol.in-phase-seat\` or \`protocol.read-only-agent\`); outside a run, the \`SEAT RULES:\` hint names it. Fetch it with \`docs_read\` \`get\` (\`kind\` \`rule\`, \`slug\`, \`rootId\`) and follow it, its \`## claude:\` section included: it says whether you transition the item, which notes are yours and how you report.

The codes an \`advance\` \`transition\` can answer, from the generated catalog, for the rule's branches:

\`gate_blocked\` means a required note is missing, a blocking violation, or both (\`detail.missing\`, \`detail.violations[].gate\`); report them and do not retry.

Transient or shedding codes (${retry || 'none'}): stop and report \`error.code\`, \`error.message\` and \`retryAfterMs\`; do not retry or spin.

Any other code (${permanent || 'none'}): the transition did not put the item in your phase; stop and report \`error.code\` and \`error.message\`.`;

const output = {
  hookSpecificOutput: {
    hookEventName: "SubagentStart",
    additionalContext: protocol
  }
};
emit(output);
