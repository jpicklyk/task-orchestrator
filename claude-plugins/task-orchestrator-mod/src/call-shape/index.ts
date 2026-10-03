// Call-shape repair rewrites for TO tool calls (T7).
// Owned by work item cedcfc11-6205-432d-8968-371bdf1cb917.
//
// `tool.call` sits above `classic.PreToolUse`, so the TO command hooks (actor attribution, skill
// enforcement) see the repaired input. Only the call's shape is repaired; server validation and
// write semantics are untouched.
import type { On } from 'claude-code'

import { PLUGIN, toToolName } from '../shared/constants.ts'
import { CONFIG_PATH } from '../shared/config.ts'
import { readSection, scalar } from '../lib/yaml-lite.mjs'
import { isOwnCall, rewriteCall } from './rewrite.ts'

export function registerCallShape(on: On): void {
  on('tool.call', async ($, e, next) => {
    const tool = toToolName(e.tool)
    if (tool === null || isOwnCall(next.origin?.plugin, PLUGIN)) return next(e)

    let rootId: string | null = null
    try {
      // Same lookup as shared/readProjectRootId, inlined: the validator follows `$` only into
      // functions declared in the same file, never across an import.
      const cfg = await $.fs.read(CONFIG_PATH)
      const section = readSection(cfg, 'project', { blockOnly: true })
      const id = section ? scalar(section.lines, 'rootId') : null
      rootId = typeof id === 'string' && id.length > 0 ? id : null
    } catch {
      rootId = null
    }

    const { input, changes } = rewriteCall(tool, e as unknown as Record<string, unknown>, rootId)
    if (changes.length === 0) return next(e)

    for (const change of changes) $.ui.log(`call-shape: ${change}`)

    return next(input as unknown as typeof e)
  })
}
