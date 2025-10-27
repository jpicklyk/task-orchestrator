---
name: orchestration-readme
description: Index and setup guide for Task Orchestration workflow files for Claude Code and other AI agents
version: "2.0.0"
---

# Task Orchestrator Orchestration Workflows

This directory contains workflow files for operating as a Task Orchestrator coordinator. These files implement progressive disclosure - load only what you need for your current task.

## Quick Start

### For Claude Code Users

Enable Task Orchestrator coordination mode:

```bash
/output-style Task Orchestrator
```

This loads a minimal 150-line output style (~1,500 tokens) that references these workflow files. Files are loaded on-demand as you need them (progressive disclosure).

### For Other AI Agents (Cursor, Windsurf, etc.)

To enable orchestration mode without Claude Code output styles:

1. Copy the content of `activation-prompt.md` into your AI agent's custom instructions or system prompt
2. Reference these workflow files as needed during your session
3. Follow progressive disclosure - read only when needed for specific tasks

---

## File Index

| File | Purpose | Content | When to Read | Lines |
|------|---------|---------|--------------|-------|
| **activation-prompt.md** | Role activation | Pre-flight checklist, core rules, decision patterns | Session start (once) | 150 |
| **decision-trees.md** | Feature/task routing | Feature creation tree, task breakdown tree, implementation routing | Creating features, breaking down tasks | 300 |
| **workflows.md** | Status/execution patterns | Feature progress monitoring, status progression, parallel execution, quality gates | Managing statuses, parallel work | 400 |
| **examples.md** | Real scenarios | Concrete examples of coordination workflows, user choice scenarios | Complex or ambiguous situations | 300 |
| **optimizations.md** | Token efficiency | File handoff pattern (#5), execution graph trust (#6), context management | File references, batch operations | 200 |
| **error-handling.md** | Failure resolution | Prerequisite validation failures, blocking scenarios, error message anatomy | Validation fails, status blocked | 200 |

**Total**: ~1,550 lines across 6 files (vs 1,089 lines in monolithic output style before refactor)

---

## Progressive Disclosure Strategy

### Layer 1: Always Loaded (Output Style)

When you activate Task Orchestrator mode, a minimal 150-line output style loads immediately:

- Pre-flight checklist
- Core coordination rules
- File references (not content)
- Quick pattern recognition table
- Session initialization protocol

**Token cost**: ~1,500 tokens

### Layer 2: Load on Demand (Workflow Files)

These files load ONLY when relevant to your current task:

- **decision-trees.md** - Load when creating features or breaking down tasks
- **workflows.md** - Load when managing status or parallel execution
- **examples.md** - Load when encountering complex or ambiguous situations
- **error-handling.md** - Load when validation fails or status is blocked
- **optimizations.md** - Load when optimizing for token efficiency

**Token cost per file**: 200-400 tokens, only when needed

**Total context with progressive disclosure**: 1,500 + (300-400 per file × active files)

**vs. Old approach**: 12,000 tokens always loaded

**Savings**: 80-90% for typical interactions

### Layer 3: Project Context (Always Loaded)

CLAUDE.md in the project root provides project-specific context:

- Build commands
- Architecture overview
- Development guidelines
- Existing tools and patterns

**Token cost**: ~8,000 tokens (project-specific, unchanging)

---

## Usage Patterns

### Pattern 1: Simple Feature Request

```
User: "Create simple feature: Add user login"

Action:
1. Load output style (~1,500 tokens) - already loaded
2. Recognize: Simple feature (< 3 tasks)
3. Launch Feature Orchestration Skill
4. Don't load decision-trees.md - skill handles simple features
5. Complete feature creation

Total: 1,500 tokens (no additional file loading)
```

### Pattern 2: Complex Feature with File

```
User: "Create feature from D:\requirements.pdf"

Action:
1. Load output style (~1,500 tokens) - already loaded
2. Recognize: Complex feature, file path provided
3. Load decision-trees.md (~300 tokens)
4. Recognize OPTIMIZATION #5 pattern
5. Pass file path to Feature Architect subagent
6. Don't read the PDF yourself

Total: 1,800 tokens (added decision-trees.md)
Savings: 4,900 tokens (didn't read and embed PDF)
```

### Pattern 3: Feature Execution with Graph

```
Planning Specialist completes, provides execution graph

Action:
1. Load output style (~1,500 tokens)
2. Load workflows.md (~300 tokens)
3. Read Planning Specialist's execution graph (no additional tokens)
4. Load optimizations.md (~150 tokens)
5. Recognize OPTIMIZATION #6 pattern
6. Trust the graph - don't re-query dependencies
7. Launch first batch via Task Orchestration Skill

Total: 1,950 tokens
Savings: 300-400 tokens (didn't re-query dependencies)
```

### Pattern 4: Status Validation Failure

```
Status Progression Skill returns validation error

Action:
1. Load output style (~1,500 tokens)
2. Load error-handling.md (~200 tokens)
3. Match error to scenario table
4. Suggest remediation
5. Retry status change

Total: 1,700 tokens
Value: Clear error explanation + solution
```

---

## Configuration Management

Each file includes a YAML frontmatter with version information:

```yaml
---
name: filename-without-md
description: Brief description of file purpose
version: "2.0.0"
---
```

### Version Checking

View all versions:

```bash
grep "version:" orchestration/*.md
```

Expected output:
```
orchestration/activation-prompt.md:version: "2.0.0"
orchestration/decision-trees.md:version: "2.0.0"
orchestration/workflows.md:version: "2.0.0"
orchestration/examples.md:version: "2.0.0"
orchestration/optimizations.md:version: "2.0.0"
orchestration/error-handling.md:version: "2.0.0"
```

### Version Updates

When Task Orchestrator updates orchestration files:

1. Source files updated in `src/main/resources/orchestration/`
2. `setup_project` tool detects version mismatch
3. User can run workflow to update local copies
4. Changes take effect immediately (no restart needed)

---

## Architecture Benefits

### Token Efficiency

- **Old approach**: 1,089-line output style (~12,000 tokens always loaded)
- **New approach**: 150-line output style (~1,500 tokens) + on-demand workflow files
- **Savings**: 80-90% reduction in always-loaded context

### Cognitive Load

- Output style activates coordinator role immediately
- No need to remember all patterns upfront
- Reference specific files when needed
- Clear navigation structure

### Maintenance

- Each file has single responsibility
- Easier to update specific workflows
- Version control prevents accidental overwrites
- Progressive disclosure prevents context creep

### AI-Agnostic Design

- Works with Claude Code output styles
- Works with other AI agents (copy activation-prompt.md)
- Works with manual CLI workflows
- Works with prompt engineering for specific agents

---

## For Claude Code Users

### Initial Setup

1. Run `/output-style Task Orchestrator` once
2. Minimal output style loads (~1,500 tokens)
3. Workflow files auto-discovered in `.taskorchestrator/orchestration/`
4. Progressive disclosure handles file loading automatically

### Customization

You can customize your local copy of workflow files without affecting the team:

```bash
# Local copies are in .taskorchestrator/orchestration/
# Source files are in src/main/resources/orchestration/

# Your local changes:
vim .taskorchestrator/orchestration/decision-trees.md

# Won't affect source or team's installation
```

### Staying Updated

After project updates, check for new versions:

```bash
# List current versions
grep "version:" .taskorchestrator/orchestration/*.md

# If versions mismatch source, run:
/workflow setup_claude_orchestration

# This refreshes all files to latest versions
```

---

## For Other AI Agents

### Setup Without Claude Code

If you're using Cursor, Windsurf, or another AI agent:

1. Read `activation-prompt.md` and add to your system prompt
2. Reference workflow files as needed:
   - Decision-trees when creating features
   - Workflows when managing status
   - Examples when in complex scenario
   - Error-handling when validation fails
   - Optimizations when optimizing performance

### Context Management

Remember progressive disclosure principles:

- Don't load all files at once
- Read only when relevant
- Build mental model incrementally
- Reference files by name when needed

Example:
```
User: "Create a complex feature"
You: "I'll use decision-trees.md to guide this complex feature creation..."
[Read decision-trees.md only]
[Don't read other files yet]
```

---

## Common Questions

### Q: Should I always load all workflow files?

**A**: No. Use progressive disclosure:
- Load only files relevant to current task
- Don't preload files you might need
- Reference files by name when needed
- Most sessions use 1-2 files maximum

### Q: What if I'm not sure which file to read?

**A**: Check the table above and look for your situation:
- Feature/task routing issues? → decision-trees.md
- Status or execution problems? → workflows.md
- Error or validation failure? → error-handling.md
- Token optimization needed? → optimizations.md
- Real example of similar scenario? → examples.md

### Q: Can I customize these files?

**A**: Yes (Claude Code users only):
- Local changes to `.taskorchestrator/orchestration/` won't be overwritten
- Source files in `src/main/resources/orchestration/` are the team version
- Your customizations are local-only (not committed to git)
- Run `setup_claude_orchestration` to restore defaults

### Q: How does progressive disclosure work?

**A**: It's automatic. When Claude Code loads Task Orchestrator output style:
1. Minimal 150-line output style loads (~1,500 tokens)
2. File references are documented but not loaded
3. When you need specific workflow, you reference the file
4. Claude reads that file (200-400 tokens) on-demand
5. File context available for rest of conversation
6. Unused files never load (save tokens)

### Q: What if I need multiple workflow files?

**A**: Load them as needed:
```
Scenario: Planning Specialist provides execution graph, but dependencies changed

1. Load workflows.md - for feature progress monitoring
2. Load optimizations.md - for execution graph guidance
3. Load decision-trees.md - for task execution routing
Total: 1,500 + 300 + 150 + 300 = 2,250 tokens
(vs. monolithic: 12,000 tokens)
```

---

## Version History

### v2.0.0 (Current)

- Initial release of progressive disclosure architecture
- 6 organized workflow files
- Minimal output style with file references
- OPTIMIZATION #5: File handoff for Feature Architect
- OPTIMIZATION #6: Trust Planning Specialist's execution graph
- 80-90% reduction in always-loaded context

---

## Support & Feedback

If you find these workflows helpful or have suggestions for improvement:

1. Note what worked well
2. Describe any unclear patterns
3. Suggest additional scenarios
4. Share any token efficiency tips you discovered

Progressive disclosure is designed to improve with real-world usage patterns.

---

## Quick Reference

### Fastest Path to Common Tasks

**Create a feature:**
1. Load output style
2. Use Feature Orchestration Skill
3. Done (most simple features need no additional files)

**Execute tasks:**
1. Load output style
2. Use Task Orchestration Skill
3. Trust Planning Specialist's graph (load optimizations.md if needed)

**Fix a blocker:**
1. Load output style
2. Load error-handling.md
3. Match error to scenario
4. Apply solution

**Optimize for tokens:**
1. Load output style
2. Load optimizations.md
3. Apply OPTIMIZATION #5 or #6 pattern
4. Save 300-4,900 tokens per operation

---

**Last updated**: 2025-10-27
**Version**: 2.0.0
**Status**: Progressive disclosure architecture active
