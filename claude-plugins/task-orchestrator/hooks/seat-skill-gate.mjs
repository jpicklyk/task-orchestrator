#!/usr/bin/env node
// PreToolUse (Edit|Write|NotebookEdit, and notes_write): refuses a subagent's gated calls until the
// skills its seat requires are on record.
//
// Acts only with a non-empty `agent_id` and a required record (written by seat-skill-hint.mjs) with
// `enforce` whose gate matches this call: 'edit' for Edit, Write and NotebookEdit; 'note' for a notes_write
// call with an `upsert` op whose role, lower-cased, equals the record's phase. Missing = the record's
// skills minus those on record (skill-record.mjs) minus the agent file's preloaded skills.
//   none missing           -> {}
//   missing, probe written -> deny, every attempt, reason beginning `SEAT SKILLS REQUIRED:` and naming the
//                             binding source; it tells an agent whose skill cannot load to stop and report
//   probe unwritable       -> {} (the marker root could not record the fix)
// There is no release: an uninstalled skill fires no hook, so the agent ends the loop by reporting.
// No REST call. Fail-open: any error emits `{}`.

import { readHookInput } from './lib/stdin.mjs';
import { emitEmpty, emitDeny } from './lib/emit.mjs';
import { opsOf } from './lib/ops.mjs';
import {
  SEAT_SKILLS_REQUIRED_PREFIX,
  UNKNOWN_SKILL_TEXT,
  bindingSourceText,
  readRequired,
  missingSkills,
  armDenial,
  quoteList,
} from './lib/seat-skill-state.mjs';

const EDIT_TOOLS = new Set(['Edit', 'Write', 'NotebookEdit']);

function gateOfCall(input, record) {
  if (EDIT_TOOLS.has(input.tool_name)) return 'edit';
  const ops = opsOf(input.tool_input);
  if (ops.length === 0) return null;
  const hit = ops.some((o) => o.op === 'upsert' && typeof o.role === 'string' && o.role.toLowerCase() === record.phase);
  return hit ? 'note' : null;
}

function main() {
  const input = readHookInput();
  if (!input) return emitEmpty();
  const agentId = input.agent_id;
  if (typeof agentId !== 'string' || agentId === '') return emitEmpty();
  const record = readRequired(input.session_id, agentId);
  if (!record || !record.enforce || !record.gate) return emitEmpty();
  if (gateOfCall(input, record) !== record.gate) return emitEmpty();
  const missing = missingSkills(input.session_id, agentId, record.skills, record.preloaded);
  if (missing.length === 0) return emitEmpty();
  if (!armDenial(input.session_id, agentId)) return emitEmpty();
  const point = record.gate === 'edit' ? 'a file edit' : `a ${record.phase} note write`;
  return emitDeny(
    `${SEAT_SKILLS_REQUIRED_PREFIX} the ${record.seat} seat on item ${record.itemId} must load ${quoteList(missing)} before ${point}. ` +
      `Invoke each with the Skill tool, then retry this call. ${bindingSourceText(record.source)} ${UNKNOWN_SKILL_TEXT}`,
  );
}

try {
  main();
} catch {
  emitEmpty();
}
