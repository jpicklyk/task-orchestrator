#!/usr/bin/env node
// PreToolUse EnterPlanMode â€” triggers the pre-plan-workflow skill.
// Gates (each emits `{}`): headless iteration / subagent / director session (suppressionReason) ->
// located config whose orchestration.mode is `off`. No located config keeps the directive.
import { emit } from "./lib/emit.mjs";
import { readHookInput } from "./lib/stdin.mjs";
import { suppressionReason } from "./execution-mode.mjs";
import { locateConfig } from "./config-locator.mjs";
import { parseOrchestrationConfig } from "./orchestration-lib.mjs";

function modeIsOff() {
  const loc = locateConfig({ cwd: process.cwd(), env: process.env });
  if (!loc || loc.scope === "none" || typeof loc.text !== "string" || !loc.text) return false;
  return parseOrchestrationConfig(loc.text).mode === "off";
}

function main() {
  if (suppressionReason(readHookInput(), process.env)) return {};
  try {
    if (modeIsOff()) return {};
  } catch {
    // fail open: an unreadable config keeps the directive
  }
  return {
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      additionalContext: `## PREREQUISITE â€” Complete before exploring or planning

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
