// Call-shape repair rewrites for TO tool calls (T7, ported to the 4.0 tools).
// Owned by work item cedcfc11-6205-432d-8968-371bdf1cb917.
//
// `tool.call` sits above `classic.PreToolUse`, so the TO command hooks see the repaired input. Only the
// call's shape is repaired; server validation and write semantics are untouched.
import type { On } from 'claude-code'

import { PLUGIN, toToolName } from '../shared/constants.ts'
import { CONFIG_PATH, parseProjectRootId } from '../shared/config.ts'
import { isOwnCall, rewriteCall } from './rewrite.ts'

/** Every 4.0 TO tool: R1/R2 apply to items_read, W1-W4 to the write tools, W3 to all. */
const REPAIRED_TOOLS = /^mcp__.*task-orchestrator.*__(?:advance|config|deps_read|deps_write|docs_read|docs_write|items_read|items_write|notes_read|notes_write|report|runs_read|runs_write)$/

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
