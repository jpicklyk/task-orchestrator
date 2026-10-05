// Names shared by every feature of the mod. Facts behind them were verified live in spike
// 11960f01 (see its implementation-notes).

/** This plugin's name: `next.origin.plugin` equals it when a dispatch was raised by our own `$` call. */
export const PLUGIN = 'task-orchestrator-mod'

/** The TO MCP server's name as `/mcp` lists it; what `$.mcp.call` takes. */
export const TO_SERVER = 'mcp-task-orchestrator'

/** Matches the tool names of the TO MCP server as the engine spells them (`mcp__<server>__<tool>`). */
export const TO_TOOL = /^mcp__.*task-orchestrator.*__/

/**
 * Field a `classic.<Event>` hook adds to the event before `next`, so a TO command hook reading it
 * on stdin can tell the mod already handled that event and exit early (selective dedupe).
 */
export const MOD_ACTIVE_FLAG = 'to_mod_active'

/** The bare TO tool name (`advance_item`) of an engine tool name, or null when it is not a TO tool. */
export function toToolName(tool: string): string | null {
  return TO_TOOL.test(tool) ? tool.replace(TO_TOOL, '') : null
}
