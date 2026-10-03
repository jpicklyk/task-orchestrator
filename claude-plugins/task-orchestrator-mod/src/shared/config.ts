// Project config access. Reads `.taskorchestrator/config.yaml` relative to the session's working
// directory with the same minimal YAML reader the TO command hooks use (src/lib/yaml-lite.mjs is a
// copy of claude-plugins/task-orchestrator/hooks/yaml-lite.mjs — keep the two in sync).
import type { EngineInterface } from 'claude-code'

import { readSection, scalar } from '../lib/yaml-lite.mjs'

export const CONFIG_PATH = '.taskorchestrator/config.yaml'

/** The raw config text, or null when the file is absent or unreadable. */
export async function readConfig($: EngineInterface): Promise<string | null> {
  try {
    return await $.fs.read(CONFIG_PATH)
  } catch {
    return null
  }
}

/** `project.rootId` from the project config, or null when there is none. */
export async function readProjectRootId($: EngineInterface): Promise<string | null> {
  const cfg = await readConfig($)
  if (cfg === null) return null
  const section = readSection(cfg, 'project', { blockOnly: true })
  const rootId = section ? scalar(section.lines, 'rootId') : null

  return typeof rootId === 'string' && rootId.length > 0 ? rootId : null
}
