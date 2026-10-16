// Phase guard in-process (T6, ported to the 4.0 tools).
// Owned by work item 26246238-681f-497d-ae4c-6dbafd2c1e12.
//
// In-process ports of two TO command hooks, semantics unchanged except that reads go through the
// engine's own MCP connection (D2) and state lives in session-scoped `$.state` (D7), so no tmpdir
// marker file is written while the mod runs:
//   - phase-guard-record.mjs   -> classic.PostToolUse (advance), registered
//   - phase-guard.mjs          -> classic.SubagentStop, returns { block }, registered
// The 3.x skill-enforcement and actor-attribution PreToolUse ports are gone: 4.0 refuses a body actor,
// and a PreToolUse wrapper could not carry the dedupe flag anyway (it would land inside tool_input).
//
// Dedupe (D3): after a clean run the hook forwards `next({ ...e, [MOD_ACTIVE_FLAG]: true })` so the
// command hook, which checks `to_mod_active` on stdin, exits early. On an internal error it forwards
// without the flag and the command hook acts as the fallback. T1 P3 proved the flag reaches stdin on
// classic.PostToolUse; SubagentStop is unproven (if it does not reach stdin, the command phase-guard
// finds no marker - recording was suppressed - and no-ops, so nothing is lost or doubled).
//
// This is the only file here that touches `$` (the validator follows `$` only into functions declared
// in the same file); `guardIo` turns `$` into the plain functions the rest takes.
import type { EngineInterface, On } from 'claude-code'

import type { PhaseGuardEntry } from '../../types'
import { MOD_ACTIVE_FLAG, PLUGIN, TO_SERVER, toToolName } from '../shared/constants.ts'
import { parseToResult } from '../shared/to-client.ts'
import { checkStop, normalizeEntry, recordAdvance } from './core.ts'
import type { GuardIo } from './core.ts'

export { checkStop, recordAdvance } from './core.ts'

function guardIo($: EngineInterface): GuardIo {
  return {
    getEntry: async agentId => normalizeEntry((await $.state.get({ plugin: 'task-orchestrator-mod', key: 'phaseGuardAgents', id: agentId } as const)).value),
    setEntry: async (agentId, entry: PhaseGuardEntry) => {
      await $.state.set({ plugin: 'task-orchestrator-mod', key: 'phaseGuardAgents', id: agentId } as const, entry)
    },
    callTo: async (tool, args) => parseToResult<unknown>(tool, await $.mcp.call(TO_SERVER, tool, args)),
    isHeadless: async () => (await $.env.get('TASK_ORCHESTRATOR_MODE')) === 'headless-iteration',
  }
}

export function registerPhaseGuard(on: On): void {
  // phase-guard-record.mjs: remember which items a subagent entered a phase for.
  on('classic.PostToolUse', async ($, e, next) => {
    // Not an advance call, or one this plugin's own `$` call raised: pass through untouched (D4).
    if (toToolName(e.tool_name) !== 'advance' || next.origin?.plugin === PLUGIN) return next(e)
    try {
      await recordAdvance(guardIo($), e as never)
    } catch {
      return next(e)
    }

    return next({ ...e, [MOD_ACTIVE_FLAG]: true } as never)
  })

  // phase-guard.mjs: block a subagent that stops with its phase's required notes missing.
  on('classic.SubagentStop', async ($, e, next) => {
    if (next.origin?.plugin === PLUGIN) return next(e)
    let reason: string | null
    try {
      reason = await checkStop(guardIo($), e as never)
    } catch {
      return next(e)
    }
    if (reason !== null) return { block: reason }

    return next({ ...e, [MOD_ACTIVE_FLAG]: true } as never)
  })
}
