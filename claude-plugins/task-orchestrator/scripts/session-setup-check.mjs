#!/usr/bin/env node

import { readFileSync, existsSync } from 'fs';

const EXPECTED_VERSION = 'v2';

// 1. Config opt-out check
const CONFIG_FILE = '.taskorchestrator/config.yaml';
if (existsSync(CONFIG_FILE)) {
  const configContent = readFileSync(CONFIG_FILE, 'utf-8');
  if (/setup_check_enabled:\s*false/.test(configContent)) {
    process.exit(0);
  }
}

// 2. Scan instruction files - stop at the first one found
const CANDIDATES = ['CLAUDE.md', 'CLAUDE.local.md', '.cursorrules', '.windsurfrules'];
let instructionFile = '';
for (const candidate of CANDIDATES) {
  if (existsSync(candidate)) {
    instructionFile = candidate;
    break;
  }
}

// 3. Version marker check
let foundVersion = '';
if (instructionFile) {
  const content = readFileSync(instructionFile, 'utf-8');
  const match = content.match(/<!-- mcp-task-orchestrator-setup: (\S+) -->/);
  if (match) {
    foundVersion = match[1];
  }
}

// 4. Decision
if (!instructionFile || !foundVersion) {
  // Missing - output additionalContext
  console.log(JSON.stringify({
    hookSpecificOutput: {
      additionalContext: "MCP Task Orchestrator setup instructions are not installed in this project. To enable workflow rules, read the MCP resource task-orchestrator://guidelines/setup-instructions and follow its steps to add the instruction block to your project's agent instructions file. This ensures consistent tool usage patterns across sessions."
    }
  }));
} else if (foundVersion !== EXPECTED_VERSION) {
  // Outdated - output additionalContext with version info
  console.log(JSON.stringify({
    hookSpecificOutput: {
      additionalContext: `MCP Task Orchestrator setup instructions are outdated (found ${foundVersion}, current is ${EXPECTED_VERSION}). Read the MCP resource task-orchestrator://guidelines/setup-instructions to get the updated instruction block. Replace the existing block in ${instructionFile}.`
    }
  }));
}
// If version matches, exit silently
