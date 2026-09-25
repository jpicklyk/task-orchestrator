// Shared helpers for matching the MCP Task Orchestrator server regardless of the key/name it is
// registered under. Claude Code matcher semantics (hooks reference): a matcher made only of
// letters/digits/`_`/`-`/space/`,`/`|` is an EXACT match (or `|`-list of exact names); any other
// character makes it an UNANCHORED JS regex. MCP tool names are `mcp__<server>__<tool>`, and the
// server segment varies by how the user registered the server (e.g. `mcp-task-orchestrator`,
// `mcp-task-orchestrator-http`, or a plugin-declared `plugin_<plugin>_<server>`). Pure module — no
// side effects on import.

// Token every accepted server-segment spelling must contain.
export const SERVER_SEGMENT_TOKEN = 'task-orchestrator';

/**
 * Builds an anchored regex-mode matcher string that fires only when the tool's server segment
 * contains SERVER_SEGMENT_TOKEN, for any of the given bare tool names.
 *
 * @param {string[]} tools - bare tool names, e.g. ['advance_item', 'manage_notes']
 * @returns {string} matcher string, e.g. '^mcp__.*task-orchestrator.*__(advance_item|manage_notes)$'
 */
export function orchestratorToolMatcher(tools) {
  if (!Array.isArray(tools) || tools.length === 0) {
    throw new Error('orchestratorToolMatcher requires a non-empty array of tool names');
  }
  const toolPart = tools.length === 1 ? tools[0] : `(${tools.join('|')})`;
  return `^mcp__.*${SERVER_SEGMENT_TOKEN}.*__${toolPart}$`;
}

/**
 * True when an MCP server registration key would satisfy the hook matchers built by
 * orchestratorToolMatcher — i.e. it contains SERVER_SEGMENT_TOKEN.
 *
 * @param {string} key
 * @returns {boolean}
 */
export function isOrchestratorServerKey(key) {
  return typeof key === 'string' && key.includes(SERVER_SEGMENT_TOKEN);
}
