# Orchestration Mode v2.0 Migration Guide

**Version**: 2.0.0
**Date**: 2025-10-27

## Overview

Task Orchestrator v2.0 introduces a progressive disclosure architecture for orchestration mode, dramatically reducing context overhead while improving role activation clarity.

## What Changed

### Before (v1.x)
- Single 1,089-line output style file
- All content loaded at session start (~12,000 tokens)
- Role activation unclear/delayed
- Difficult to maintain
- Monolithic structure combined all workflows in one file

### After (v2.0)
- Minimal 150-line output style (~1,500 tokens)
- 7 organized workflow files loaded on-demand
- Immediate role activation (clear "ORCHESTRATION MODE ACTIVE" header)
- 87.5% token reduction for always-loaded context
- Easy to maintain and extend
- Progressive disclosure: load specific workflows only when needed

## Breaking Changes

### Output Style Structure

**Old structure**:
```
src/main/resources/claude/output-styles/task-orchestrator.md (1,089 lines)
```

**New structure**:
```
src/main/resources/claude/output-styles/task-orchestrator.md (150 lines - minimal style)
src/main/resources/orchestration/ (7 organized workflow files)
.taskorchestrator/orchestration/ (auto-copied to your project)
```

### File Locations

When you run `setup_project` or `setup_claude_orchestration`, files are copied to:

```
.taskorchestrator/orchestration/
├── decision-trees.md         (Feature creation, task breakdown, routing)
├── workflows.md              (Status progression, parallel execution)
├── examples.md               (Real scenarios with solutions)
├── optimizations.md          (Token efficiency patterns)
├── error-handling.md         (Prerequisite validation failures)
├── activation-prompt.md      (AI-agnostic version for other agents)
└── README.md                 (File index and setup instructions)
```

## Token Reduction Breakdown

### Always-Loaded Context

| Aspect | v1.x | v2.0 | Reduction |
|--------|------|------|-----------|
| Output style | 12,000 tokens | 1,500 tokens | 87.5% |
| Orchestration files | 0 (loaded on-demand) | 0 (loaded on-demand) | N/A |
| Total session start | ~12,000 tokens | ~1,500 tokens | 87.5% |

### Per-Interaction Context

| Scenario | v1.x | v2.0 | Savings |
|----------|------|------|---------|
| Simple status check | 12,000 (all loaded) | 1,500 + 200 (1 file) | 85% |
| Create complex feature | 12,000 (all loaded) | 1,500 + 300 (decision tree) | 84% |
| Handle workflow error | 12,000 (all loaded) | 1,500 + 200 (error handling) | 85% |

### Why Progressive Disclosure Works

1. **Most interactions don't need all workflows**: You ask a status question, not every coordination pattern
2. **Files are small**: Individual workflow files are 200-400 lines (300-600 tokens)
3. **Claude reads what's referenced**: Only files Claude needs are loaded into context
4. **Meaningful reduction**: 10,500 tokens saved upfront per session

## Migration Steps

### Step 1: Update Task Orchestrator Server (if running locally)

If you built from source:

```bash
cd D:\Projects\task-orchestrator
git pull origin main
./gradlew clean build
```

If using Docker:

```bash
docker pull ghcr.io/jpicklyk/task-orchestrator:latest
```

### Step 2: Run setup_project (Required)

Ask your AI:
```
"Run setup_project to initialize Task Orchestrator configuration"
```

This will:
1. Create `.taskorchestrator/` directory (if it doesn't exist)
2. Copy orchestration files to `.taskorchestrator/orchestration/`
3. Create/update core configuration files

### Step 3: Verify Installation

Check that orchestration files were created:

```bash
ls -la .taskorchestrator/orchestration/
```

You should see 7 files:
- `decision-trees.md`
- `workflows.md`
- `examples.md`
- `optimizations.md`
- `error-handling.md`
- `activation-prompt.md`
- `README.md`

### Step 4: Enable New Output Style

In Claude Code, enable the new output style:

```bash
/output-style Task Orchestrator
```

Expected behavior:
- You should see a clear "🚨 ORCHESTRATION MODE ACTIVE 🚨" header
- Claude immediately identifies itself as a workflow coordinator
- You get a pre-flight checklist prompt

### Step 5: Verify Activation

Try a simple request:
```
"What's next?" or "List the tasks in this project"
```

You should see Claude use the Feature Orchestration Skill or Task Orchestration Skill (not write code directly).

## Benefits

### Token Efficiency
- **Session startup**: 12,000 → 1,500 tokens (87.5% reduction)
- **Typical interaction**: 12,000 → 1,700 tokens (85% reduction)
- **Cumulative over 10 interactions**: 120,000 → 17,000 tokens (85% savings)

### Immediate Role Activation
- Clear "🚨 ORCHESTRATION MODE ACTIVE 🚨" header
- No ambiguity about being a coordinator
- Pre-flight checklist guides every response

### Progressive Disclosure Benefits
- Workflows load only when needed
- Cleaner, more organized file structure
- Easier to maintain and extend
- Each file has clear purpose

### AI-Agnostic Design
- Works with Cursor, Windsurf, and other agents
- `.taskorchestrator/orchestration/activation-prompt.md` for non-Claude Code agents
- Consistent workflows across all AI platforms

## Troubleshooting

### Issue: Output style doesn't activate immediately

**Symptom**: You don't see the "🚨 ORCHESTRATION MODE ACTIVE 🚨" header

**Cause**: Old output style still cached or not properly switched

**Solution**:
1. Restart Claude Code completely
2. Verify you're using v2.0 output style:
   ```bash
   /output-style default
   /output-style Task Orchestrator
   ```
3. Check that new output style is installed:
   ```bash
   cat .claude/output-styles/task-orchestrator.md | head -20
   # Should see "ORCHESTRATION MODE ACTIVE"
   ```

### Issue: Missing orchestration files

**Symptom**: Files referenced in output style don't exist at `.taskorchestrator/orchestration/`

**Cause**: `setup_project` didn't complete or files weren't copied

**Solution**:
1. Run setup_project again:
   ```
   "Run setup_project to initialize Task Orchestrator configuration"
   ```
2. Verify files were created:
   ```bash
   ls -la .taskorchestrator/orchestration/
   ```
3. Check file count - should be 7 files
4. If still missing, check `.taskorchestrator/orchestration/README.md` for format

### Issue: File path references not working

**Symptom**: Claude can't find referenced orchestration files

**Cause**: Orchestration directory not in expected location

**Solution**:
1. Verify directory structure:
   ```bash
   ls -la .taskorchestrator/
   # Should show: orchestration directory
   ```
2. Check that all 7 files exist in orchestration directory
3. Verify file names match exactly (case-sensitive on macOS/Linux)
4. Check CLAUDE.md hasn't been modified to change file paths

### Issue: Claude uses old coordination patterns

**Symptom**: Claude doesn't use Skills for coordination, writes code directly instead

**Cause**: Old output style cached or not properly enabled

**Solution**:
1. Disable and re-enable orchestration mode:
   ```bash
   /output-style default
   [restart Claude Code]
   /output-style Task Orchestrator
   ```
2. Ask a coordination question: "What's next?"
3. Claude should use Feature Orchestration Skill
4. If still writing code, check that CLAUDE.md hasn't been modified

### Issue: Orchestration files report "version mismatch"

**Symptom**: Message about outdated orchestration files when running setup_project

**Cause**: Local files are older than latest version in source

**Solution**:
1. Backup your local versions (in case you customized them):
   ```bash
   cp -r .taskorchestrator/orchestration .taskorchestrator/orchestration.backup
   ```
2. Let `setup_project` update the files (it will ask permission)
3. Review changes to ensure your customizations are preserved:
   ```bash
   diff -r .taskorchestrator/orchestration.backup .taskorchestrator/orchestration/
   ```

### Issue: Context overhead didn't reduce as expected

**Symptom**: Token usage is still high, not 87.5% reduction

**Cause**: Claude is loading all orchestration files instead of on-demand

**Solution**:
1. Check what files are being loaded:
   - Look at response to see if multiple workflow files mentioned
   - Progressive disclosure means single files at a time
2. Ask Claude to work on coordination-only tasks
3. For implementation work, use `recommend_agent` to route to specialists (separate context)
4. Monitor token usage across multiple interactions to see cumulative savings

## Rollback (Emergency Only)

If you need to revert to v1.x output style:

### Option 1: Quick Rollback (< 5 minutes)

```bash
# Disable orchestration mode
/output-style default

# Verify standard mode works
[Test a simple request]
```

This immediately disables the new output style without affecting any files.

### Option 2: Full Rollback (restore v1.x)

If you need the old output style back:

```bash
# From project directory:
git log --oneline | grep "refactor: orchestration"
git show <commit-hash>:src/main/resources/claude/output-styles/task-orchestrator.md > old-style.md

# Copy old style
cp old-style.md .claude/output-styles/task-orchestrator.md

# Verify it loads
/output-style Task Orchestrator
# Should see old format without "ORCHESTRATION MODE ACTIVE"
```

### Option 3: Keep Both (v1.x and v2.0)

Save the old output style with a different name:

```bash
# In Claude Code:
/output-style default
# Or switch between them manually
```

## Version Detection

### Check Current Orchestration Version

```bash
grep "version:" .taskorchestrator/orchestration/*.md
```

All should show `version: "2.0.0"`.

### Check Output Style Version

```bash
head -20 .claude/output-styles/task-orchestrator.md
# Should mention "progressive disclosure" and "Task Orchestrator"
# v1.x will be monolithic, v2.0 will reference orchestration files
```

### Compare Versions

```bash
# Source files (what you're running)
ls -la src/main/resources/orchestration/

# Installed files (what Claude Code uses)
ls -la .taskorchestrator/orchestration/
```

If installed files are missing, run `setup_project` again.

## Parallel Installation with v1.x

If you're running v1.x and want to test v2.0:

1. Keep `.claude/output-styles/task-orchestrator.md` (v1.x)
2. Copy v2.0 to different name:
   ```bash
   cp src/main/resources/claude/output-styles/task-orchestrator.md .claude/output-styles/task-orchestrator-v2.md
   ```
3. Switch between them:
   ```bash
   /output-style Task Orchestrator    # uses .md with YAML frontmatter name
   /output-style default              # turns off
   ```

## Performance Expectations

### Session Startup
- **v1.x**: 12,000 tokens always loaded
- **v2.0**: 1,500 tokens always loaded
- **Improvement**: 10,500 tokens freed immediately

### Typical Interaction (coordination)
- **v1.x**: 12,000 tokens + interaction tokens
- **v2.0**: 1,500 + 200-400 tokens (progressive) + interaction tokens
- **Improvement**: 10,100-10,300 tokens freed per interaction

### Cumulative Over Session
- **10 interactions**: 105,000 tokens saved (v2.0 vs v1.x)
- **20 interactions**: 210,000 tokens saved
- **Enables**: Complex features that would hit context limits with v1.x

## Additional Resources

- **Setup instructions**: `.taskorchestrator/orchestration/README.md`
- **Progressive disclosure architecture**: `plans/orchestration-mode-refactoring.md`
- **Detailed workflows**: Files in `.taskorchestrator/orchestration/`
- **Output style source**: `src/main/resources/claude/output-styles/task-orchestrator.md`

## FAQ

### Q: Do I need to run setup_project again?
**A**: Only if you're upgrading from v1.x. Running it again is safe and will just verify/update files.

### Q: Can I customize orchestration files?
**A**: Yes! Files in `.taskorchestrator/orchestration/` are user-customizable. Changes won't be overwritten unless you run setup_project with an update.

### Q: Will my existing tasks/features break?
**A**: No. This is purely a UI/context change. Your database, tasks, and features are unaffected.

### Q: How do I turn off orchestration mode?
**A**: Run `/output-style default` in Claude Code. This switches back to standard mode without affecting any files.

### Q: Can other AI agents use this?
**A**: Yes! Copy `.taskorchestrator/orchestration/activation-prompt.md` into your agent's system prompt.

### Q: What if I prefer the old output style?
**A**: Run `/output-style default` to disable it. The old style is in git history if you need it.

## Feedback

If you encounter issues or have suggestions:
- Check this guide's troubleshooting section
- Review `.taskorchestrator/orchestration/error-handling.md` for common scenarios
- Open an issue at https://github.com/anthropics/task-orchestrator/issues

---

**Questions?** See the troubleshooting section above or open an issue.

**Ready to upgrade?** Run `setup_project` then `/output-style Task Orchestrator`.
