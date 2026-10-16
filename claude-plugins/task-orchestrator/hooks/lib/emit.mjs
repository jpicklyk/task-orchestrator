// Hook output builders and writers. Pure at import; each writer does one stdout write, no newline.

export function contextOutput(eventName, text, extra = {}) {
  return { hookSpecificOutput: { hookEventName: eventName, additionalContext: text, ...extra } };
}

export function denyOutput(reason) {
  return { hookSpecificOutput: { hookEventName: 'PreToolUse', permissionDecision: 'deny', permissionDecisionReason: reason } };
}

export function blockOutput(reason) {
  return { decision: 'block', reason };
}

export function systemMessageOutput(text) {
  return { systemMessage: text };
}

export function emit(obj) {
  process.stdout.write(JSON.stringify(obj));
}

export function emitEmpty() {
  process.stdout.write('{}');
}

export function emitContext(eventName, text, extra = {}) {
  emit(contextOutput(eventName, text, extra));
}

export function emitDeny(reason) {
  emit(denyOutput(reason));
}

export function emitBlock(reason) {
  emit(blockOutput(reason));
}

export function emitSystemMessage(text) {
  emit(systemMessageOutput(text));
}
