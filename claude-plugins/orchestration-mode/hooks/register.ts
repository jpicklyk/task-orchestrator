// Entry module for the orchestration-mode plugin. It only wires the one feature; the hooks and
// their matchers live in src/indicator.ts so the validator resolves them from that file's source.
import type { Register } from 'claude-code'

import { registerOrchestrationMode } from '../src/indicator.ts'

export const register: Register = (on, options) => {
  registerOrchestrationMode(on, options)
}
