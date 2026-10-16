#!/usr/bin/env node
// PostToolUse ExitPlanMode â€” triggers the post-plan-workflow skill.
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
      hookEventName: "PostToolUse",
      additionalContext: `## NEXT STEP â€” Required before implementation

The plan has been approved. You MUST invoke the \`task-orchestrator:post-plan-workflow\` skill NOW to materialize MCP items and dispatch implementation.

Do not begin any implementation work until this completes.`
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
