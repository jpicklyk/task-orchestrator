// Phase guard + advisory hooks in-process (T6).
// Owned by work item 26246238-681f-497d-ae4c-6dbafd2c1e12.
//
// In-process ports of four TO command hooks, semantics unchanged except that reads go through the
// engine's own MCP connection (D2) and state lives in session-scoped `$.state` (D7), so no tmpdir
// marker file is written while the mod runs:
//   - phase-guard-record.mjs   -> classic.PostToolUse (advance_item), registered
//   - phase-guard.mjs          -> classic.SubagentStop, returns { block }, registered
//   - skill-enforcement.mjs    -> classic.PreToolUse (manage_notes), NOT registered, see below
//   - enforce-actor-attribution.mjs -> classic.PreToolUse (advance_item, manage_notes), NOT registered
//
// Dedupe (D3): after a clean run the hook forwards `next({ ...e, [MOD_ACTIVE_FLAG]: true })` so the
// command hook, which checks `to_mod_active` on stdin, exits early. On an internal error it forwards
// without the flag and the command hook acts as the fallback. T1 P3 proved the flag reaches stdin on
// classic.PostToolUse; SubagentStop is unproven (if it does not reach stdin, the command phase-guard
// finds no marker - recording was suppressed - and no-ops, so nothing is lost or doubled).
//
// The two PreToolUse ports are kept and tested but left unregistered: `classic.PreToolUse`'s `e` is a
// ToolCallEnvelope, whose extra fields may be taken for the tool's arguments (the flag would then
// reach the server), and flag propagation to stdin there is unverified. The command hooks stay
// authoritative for those two until it is verified (the plugin test harness cannot raise
// classic.PreToolUse to the plugin's hooks, so the wrapper is untested at engine level; the logic
// beneath it, core.ts, is); wire `registerPreToolUse(on)` into
// `registerPhaseGuard` then.
//
// This is the only file here that touches `$` (the validator follows `$` only into functions declared
// in the same file); `guardIo` turns `$` into the plain functions the rest takes.
import type { EngineInterface, On } from 'claude-code'

import type { PhaseGuardEntry } from '../../types'
import { MOD_ACTIVE_FLAG, PLUGIN, TO_SERVER, toToolName } from '../shared/constants.ts'
import { CONFIG_PATH } from '../shared/config.ts'
import { actorAttribution, checkStop, normalizeEntry, recordAdvance, skillEnforcement } from './core.ts'
import type { GuardIo } from './core.ts'

export { actorAttribution, checkStop, recordAdvance, skillEnforcement } from './core.ts'

function parseToolResult(tool: string, result: { content: readonly unknown[]; isError?: boolean }): unknown {
  let text = ''
  for (const block of result.content) {
    const b = block as { type?: string; text?: string }
    if (b.type === 'text' && typeof b.text === 'string') {
      text = b.text
      break
    }
  }
  if (result.isError) throw new Error(`${tool}: ${text || 'error result'}`)

  return JSON.parse(text)
}

function guardIo($: EngineInterface): GuardIo {
  return {
    getEntry: async agentId => normalizeEntry((await $.state.get({ plugin: 'task-orchestrator-mod', key: 'phaseGuardAgents', id: agentId } as const)).value),
    setEntry: async (agentId, entry: PhaseGuardEntry) => {
      await $.state.set({ plugin: 'task-orchestrator-mod', key: 'phaseGuardAgents', id: agentId } as const, entry)
    },
    getWarned: async () => {
      const { value } = await $.state.get({ plugin: 'task-orchestrator-mod', key: 'skillWarned' } as const)

      return Array.isArray(value) ? value : []
    },
    setWarned: async pairs => {
      await $.state.set({ plugin: 'task-orchestrator-mod', key: 'skillWarned' } as const, pairs)
    },
    callTo: async (tool, args) => parseToolResult(tool, await $.mcp.call(TO_SERVER, tool, args)),
    readConfig: async () => {
      try {
        return await $.fs.read(CONFIG_PATH)
      } catch {
        return null
      }
    },
    isHeadless: async () => (await $.env.get('TASK_ORCHESTRATOR_MODE')) === 'headless-iteration',
  }
}

export function registerPhaseGuard(on: On): void {
  // phase-guard-record.mjs: remember which items a subagent entered a phase for.
  on('classic.PostToolUse', async ($, e, next) => {
    // Not an advance_item call, or one this plugin's own `$` call raised: pass through untouched (D4).
    if (toToolName(e.tool_name) !== 'advance_item' || next.origin?.plugin === PLUGIN) return next(e)
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

/**
 * skill-enforcement.mjs and enforce-actor-attribution.mjs as one `classic.PreToolUse` hook. Not called
 * by `registerPhaseGuard` yet (see the header); exported so it is tested and ready to wire.
 */
export function registerPreToolUse(on: On): void {
  on('classic.PreToolUse', async ($, e, next) => {
    const tool = toToolName(e.tool)
    if ((tool !== 'manage_notes' && tool !== 'advance_item') || next.origin?.plugin === PLUGIN) return next(e)
    const input = e as unknown as Record<string, unknown>
    const io = guardIo($)
    try {
      const attribution = await actorAttribution(io, tool, input)
      if (attribution.deny !== undefined) return { deny: attribution.deny }
      const skills = tool === 'manage_notes' ? await skillEnforcement(io, input) : { context: [] as string[], handled: true }
      // A config the mod could not read leaves the command hooks authoritative (no flag).
      const handled = attribution.handled && skills.handled
      const context = [...attribution.context, ...skills.context]
      const ran = await next(handled ? ({ ...e, [MOD_ACTIVE_FLAG]: true } as never) : e)
      if (context.length === 0) return ran

      return { ...ran, additionalContext: [...(ran.additionalContext ?? []), ...context] }
    } catch {
      return next(e)
    }
  })
}
