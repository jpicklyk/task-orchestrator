// Call-shape repair rewrites for TO tool calls (T7).
// Owned by work item cedcfc11-6205-432d-8968-371bdf1cb917.
//
// `tool.call` sits above `classic.PreToolUse`, so the TO command hooks (actor attribution, skill
// enforcement) see the repaired input. Only the call's shape is repaired; server validation and
// write semantics are untouched.
import type { On } from 'claude-code'

import { PLUGIN, toToolName } from '../shared/constants.ts'
import { CONFIG_PATH, parseProjectRootId } from '../shared/config.ts'
import { isOwnCall, rewriteCall } from './rewrite.ts'

/** The TO tools whose input this feature can repair; other TO calls never reach the hook. */
const REPAIRED_TOOLS = /^mcp__.*task-orchestrator.*__(?:query_items|get_next_item|get_blocked_items|get_context|manage_notes)$/

export function registerCallShape(on: On): void {
  on('tool.call', { tool: REPAIRED_TOOLS }, async ($, e, next) => {
    const tool = toToolName(e.tool)
    if (tool === null || isOwnCall(next.origin?.plugin, PLUGIN)) return next(e)

    let rootId: string | null = null
    try {
      rootId = parseProjectRootId(await $.fs.read(CONFIG_PATH))
    } catch {
      rootId = null
    }

    const { input, changes } = rewriteCall(tool, e as unknown as Record<string, unknown>, rootId)
    if (changes.length === 0) return next(e)

    for (const change of changes) $.ui.log(`call-shape: ${change}`)

    return next(input as unknown as typeof e)
  })
}
