#!/usr/bin/env node
// SubagentStart — injects agent-owned-phase protocol.
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
// The injected wording itself is conditional rather than a blanket "call advance(start)":
// it tells the agent to defer to whatever seat its OWN dispatch prompt assigned it (only an
// entry seat, or a single-phase owner with no seat named, advances the item; a non-entry or
// read-only seat never does), since this hook cannot see the dispatch prompt's seat assignment.
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

**Follow your dispatch prompt's seat assignment.** If your dispatch prompt names a seat for you (e.g. \`implementer\`, \`reviewer\`, or another named seat), that seat's own transition rule governs — follow it exactly. Only an **entry seat** (the seat that starts the item's current phase), or a single agent that owns the whole phase with no seat named, calls \`advance\` with \`ops: [{op: "transition", itemId: "<your-item-UUID>", trigger: "start"}]\`. A non-entry seat or a **read-only** agent never calls \`advance\` — it does its assigned work and fills its own notes without transitioning the item. When you do own the transition: enter your phase, do the work, and fill the phase's required notes via \`notes_write\` (\`ops: [{op: "upsert", itemId, key, role, body}]\`). When your dispatch prompt carries a \`seatToken\`, send it as a top-level field of every call you make. Read the item's gate with \`items_read\` (\`select: "context"\`, \`itemId\`); it names the owed keys and a \`guidanceKey\`, and the guidance text itself comes from \`items_read\` \`select: "schema"\` with \`fields: ["guidance"]\`.

Every result is an envelope: branch on \`ok\` and on \`error.code\` / \`error.kind\` (and \`retryAfterMs\` when present) of the element, never on the absence of an error.

**Read the role before you send \`start\`.** Call \`items_read\` (\`select: "context"\`, \`itemId\`) first. A \`start\` is not idempotent: a second \`start\` moves the item forward whenever its gate passes, so retrying it can advance the item past your phase. Send \`start\` only when the role is the one just before your phase: \`queue\`, for a seat that enters the work phase. A review-phase seat never sends \`start\`: the orchestrator moves the item to review before dispatching you. When the context already shows your phase, do not send \`start\`; read the guidance as above and begin. For any other role (blocked, terminal, or a phase past yours), do not send \`start\`: stop and report the role back to the orchestrator.

**A \`gate_blocked\` answer means a required note is missing, a blocking violation, or both: read \`detail.missing\` and \`detail.violations[].gate\` (both may be present; a review-pending or independence violation is not cleared by filling notes)**. It says nothing about where the item is. Stop, report the missing notes or the violations back to the orchestrator, and do not retry.

**If the transition fails with a transient or shedding code (${retry || 'none'}), do NOT treat it as already-in-phase and do NOT retry or spin.** Stop immediately and report the \`error.code\`, the \`error.message\` and \`retryAfterMs\` back to the orchestrator — do not proceed as if you had entered your phase.

**Any other code** (${permanent || 'none'}) means the transition did not put the item in your phase. Stop immediately and report the \`error.code\` and \`error.message\` back to the orchestrator — do not proceed as if you had entered your phase, and do not retry the same call.

**Never call \`advance\` again after entering your phase, and never use \`trigger: "complete"\`** — the orchestrator owns all subsequent transitions.

If you stop with your phase's required notes still unfilled, you will be sent back (up to twice) with the missing keys named — fill them, or state plainly what blocks you.

## Subagent Discipline

1. **Commit before returning.** Stage and commit all file changes with a descriptive message — the orchestrator squash-merges your branch.
2. **Stay in scope.** Touch only files for your assigned task. No version bumps, shared config, or CI edits. Deliver what the task asked at the scope intended: finish the whole task rather than leaving stubs, and stop short of work clearly beyond it.
3. **Notes are the report.** Write findings into your item's notes — distill inline; route verbatim artifacts (test output, diffs, logs) via \`bodyFromFile\`. Your final message to the orchestrator is 1-2 lines: item ID, outcome, and which note keys were filled. Do not restate note content.
4. **Leave nothing running.** Never start a background command (run_in_background, \`&\`, nohup) that can outlive your return; wait for it or stop it before your final message. Bound filesystem searches to the repo/worktree and known caches (e.g. \`~/.gradle/caches\`); never search from \`/\` or a drive root.`;

const output = {
  hookSpecificOutput: {
    hookEventName: "SubagentStart",
    additionalContext: protocol
  }
};
emit(output);
