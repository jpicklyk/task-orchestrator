// Entry module for the task-orchestrator-mod plugin. Thin by design: each feature lives under
// src/<feature>/ and owns its own hooks; this file only wires them. Add a feature by importing its
// register function here — do not put hook logic in this file.
import type { Register } from 'claude-code'

import { registerBand } from '../src/band/index.ts'
import { registerCallShape } from '../src/call-shape/index.ts'
import { registerGraphData } from '../src/graph-data/index.ts'
import { registerGraphPane } from '../src/graph-pane/index.ts'
import { registerPhaseGuard } from '../src/phase-guard/index.ts'
import { registerRetro } from '../src/retro/index.ts'

export const register: Register = (on, options) => {
  registerGraphData(on)
  registerGraphPane(on, options)
  registerBand(on)
  registerRetro(on)
  registerPhaseGuard(on)
  registerCallShape(on)
}
