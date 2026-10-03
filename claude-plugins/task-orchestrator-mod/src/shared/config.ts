// Project config parsing. Pure: the caller reads the file itself with `$.fs.read(CONFIG_PATH)` in
// the module that owns `$` — the plugin validator follows `$` only within one file, so a helper
// that takes `$` across an import is refused. src/lib/yaml-lite.mjs is a copy of
// claude-plugins/task-orchestrator/hooks/yaml-lite.mjs — keep the two in sync.
import { readSection, scalar } from '../lib/yaml-lite.mjs'

/** The project config, relative to the session's working directory. */
export const CONFIG_PATH = '.taskorchestrator/config.yaml'

/** `project.rootId` from config text, or null when there is none. */
export function parseProjectRootId(configText: string | null): string | null {
  if (!configText) return null
  const section = readSection(configText, 'project', { blockOnly: true })
  const rootId = section ? scalar(section.lines, 'rootId') : null

  return typeof rootId === 'string' && rootId.length > 0 ? rootId : null
}
