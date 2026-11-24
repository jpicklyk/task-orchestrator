#!/usr/bin/env node
// PreToolUse EnterPlanMode — triggers the pre-plan-workflow skill.
// Gates (each emits `{}`): headless iteration / subagent / director session (suppressionReason) ->
// knowledge session (lib/session-profile.mjs: knowledge-profile registration or a lifecycle-free root
// config; the registration alone suffices) -> located config whose orchestration.mode is `off`. No
// located config otherwise keeps the directive.
import { emit } from "./lib/emit.mjs";
import { readHookInput } from "./lib/stdin.mjs";
import { suppressionReason } from "./execution-mode.mjs";
import { locateConfig } from "./config-locator.mjs";
import { parseOrchestrationConfig } from "./orchestration-lib.mjs";
import { isKnowledgeSession } from "./lib/session-profile.mjs";

function staysSilent() {
  const loc = locateConfig({ cwd: process.cwd(), env: process.env });
  if (isKnowledgeSession({ configText: loc?.text })) return true;
  if (!loc || loc.scope === "none" || typeof loc.text !== "string" || !loc.text) return false;
  return parseOrchestrationConfig(loc.text).mode === "off";
}

function main() {
  if (suppressionReason(readHookInput(), process.env)) return {};
  try {
    if (staysSilent()) return {};
  } catch {
    // fail open: an unreadable config keeps the directive
  }
  return {
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      additionalContext: `## PREREQUISITE — Complete before exploring or planning

You MUST invoke the \`task-orchestrator:pre-plan-workflow\` skill BEFORE starting any codebase exploration or plan writing.

Do this now. Do not begin exploring, reading code, or drafting your plan until this prerequisite completes.`
    }
  };
}

let out = {};
try {
  out = main();
} catch {
  out = {};
}
emit(out);
