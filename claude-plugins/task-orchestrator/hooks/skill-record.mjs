#!/usr/bin/env node
// PostToolUse Skill: records the skill a subagent loaded, so seat-skill-gate.mjs and
// skill-enforcement.mjs see it on record for that agent (lib/seat-skill-state.mjs, one file per skill).
//
// Acts only with a non-empty `agent_id` (a subagent call). Records `tool_input.skill` (one leading `/`
// stripped) and `tool_response.commandName` when it is a non-empty string; records nothing when
// `tool_response.success === false`. An uninstalled skill answers `Unknown skill: <name>` as a tool error
// and fires no hook, so it is never recorded. No REST call. Always emits `{}`; never blocks.

import { readHookInput } from './lib/stdin.mjs';
import { emitEmpty } from './lib/emit.mjs';
import { recordSkillLoaded } from './lib/seat-skill-state.mjs';

function main() {
  try {
    const input = readHookInput();
    if (!input) return;
    const agentId = input.agent_id;
    if (typeof agentId !== 'string' || agentId === '') return;
    const response = input.tool_response;
    const resp = response && typeof response === 'object' && !Array.isArray(response) ? response : null;
    if (resp && resp.success === false) return;
    const names = [];
    const raw = input.tool_input && typeof input.tool_input === 'object' ? input.tool_input.skill : undefined;
    if (typeof raw === 'string') {
      const name = raw.trim().replace(/^\//, '');
      if (name) names.push(name);
    }
    if (resp && typeof resp.commandName === 'string' && resp.commandName.trim() !== '') {
      names.push(resp.commandName.trim());
    }
    for (const name of new Set(names)) recordSkillLoaded(input.session_id, agentId, name);
  } catch {
    // fail open
  }
}

main();
emitEmpty();
