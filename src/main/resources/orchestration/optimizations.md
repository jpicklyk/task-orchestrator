---
name: optimizations
description: Token efficiency patterns, file handoff optimizations, and execution graph trust patterns for reduced context overhead
version: "2.0.0"
---

# Token Efficiency Optimizations

## Overview

Task Orchestration system uses two key optimizations to reduce token overhead and improve efficiency. These patterns should be applied whenever appropriate.

## OPTIMIZATION #5: File Handoff for Feature Architect

### Problem

When users provide file references (PRD files, specifications, etc.), a naive approach wastes significant tokens:

```
❌ Token-Wasteful Approach:
1. Read file (5,000 tokens)
2. Embed entire content in subagent prompt (5,000 tokens in handoff)
3. Total: ~10,000 tokens
```

### Solution

Instead, pass file path references to the subagent:

```
✅ Optimized Approach:
1. Detect file path in user message
2. Pass path reference to subagent (~100 tokens)
3. Feature Architect reads file directly with same permissions
4. Total: ~5,100 tokens
5. Savings: ~4,900 tokens (49% reduction)
```

### Implementation

**File Path Detection Pattern:**
- Windows paths: `D:\path\to\file.md`, `C:\Users\...`
- Unix paths: `/path/to/file.md`, `~/path/...`
- Relative paths: `./file.md`, `../docs/file.md`
- File extensions: `.md`, `.txt`, `.yaml`, `.json`, `.pdf`

**Handoff Code Pattern:**
```python
# When user provides: "Create feature from: D:\path\to\requirements.md"

if userMessage.contains_file_path():
    filePath = extract_file_path(userMessage)

    # Pass path reference, NOT content
    subagentPrompt = f"""
    Create feature from the following file:

    File: {filePath}

    Instructions:
    1. Read the file directly using the Read tool
    2. Analyze requirements
    3. Create feature with appropriate templates
    4. [Rest of Feature Architect instructions...]
    """

    # Launch Feature Architect with file path
    launch_subagent("Feature Architect", subagentPrompt)
```

### When to Use

✅ **Always apply when:**
- User provides file path in message
- Subagent will need file content
- File is accessible to subagent (same filesystem)

❌ **Don't use when:**
- File path is invalid or inaccessible
- User expects you to analyze file before handoff
- File is part of system prompt context

## OPTIMIZATION #6: Trust Planning Specialist's Execution Graph

### Problem

When Planning Specialist provides an execution graph, a naive approach regenerates the same analysis:

```
❌ Token-Wasteful Approach:
1. Planning Specialist creates tasks + dependencies + execution graph (~1500 tokens)
2. Task Orchestration Skill re-queries ALL dependencies (300-400 tokens)
3. Task Orchestration Skill regenerates execution batches (redundant work)
4. Total waste: ~300-400 tokens per feature execution start
```

### Solution

Trust the execution graph provided by Planning Specialist. Only query current status:

```
✅ Optimized Approach:
1. Planning Specialist provides execution graph (80-120 tokens in output)
2. Task Orchestration Skill reads Planning Specialist's graph
3. Task Orchestration Skill queries only current task status (pending/in-progress/completed)
4. Task Orchestration Skill recommends WHICH batch to start
5. Savings: ~300-400 tokens per feature execution start
```

### Planning Specialist's Graph Format

```
Batch 1 (2 tasks, parallel):
- Kotlin Enums, Config

Batch 2 (1 task):
- Migration V12 (depends on: Enums)

Batch 3 (2 tasks, parallel):
- Alignment Tests (depends on: Migration, Config)
- Migration Test (depends on: Migration)

Next: Task Orchestration Skill - Ready to execute batch 1
```

### Task Orchestration Skill Usage Pattern

```python
# After Planning Specialist completes:

# ❌ DON'T re-query all dependencies
# This wastes 300-400 tokens:
# for each task:
#     dependencies = query_dependencies(taskId)
#     # Re-analyzing what Planning Specialist already did

# ✅ DO trust Planning Specialist's graph
planningGraph = read_from_planning_specialist_output()
batches = planningGraph.batches  # Already structured!

# Only query current status (fast, ~50 tokens)
taskStatuses = {}
for task in feature.tasks:
    taskStatuses[task.id] = task.status  # pending, in-progress, completed

# Determine which batch to start
currentBatch = determine_next_batch(batches, taskStatuses)
recommendation = f"Start {currentBatch.name}: {', '.join(task.title for task in currentBatch.tasks)}"

# Execute
launch_batch(currentBatch)
```

### When to Trust the Graph

✅ **Always trust when:**
- Planning Specialist explicitly provides execution graph
- Feature was just planned (no changes since planning)
- Task dependencies haven't changed
- You're following directly after Planning Specialist

❌ **Re-query dependencies when:**
- Dependencies have changed since planning
- User explicitly requests dependency re-analysis
- Time has passed (5+ minutes) and tasks may have been modified
- Quality gates require dependency verification

## Token Efficiency Guidelines

### General Principles

1. **Avoid Redundant Queries**
   - Don't query dependencies if Planning Specialist already analyzed
   - Don't read files if subagent will read them
   - Don't embed content if passing references works

2. **Use Scoped Overview Pattern**
   - Use `query_container(operation="overview", id="...")` instead of get with sections
   - 85-95% token reduction for hierarchical views
   - Don't load section content unless specifically needed

3. **Batch Operations**
   - Use `bulkUpdate` instead of individual updates
   - Use `bulkCreate` for multiple section creation
   - Reduces round-trip overhead

4. **Progressive Disclosure**
   - Load information only when needed
   - File references in output style are read on-demand
   - Don't preload all orchestration files

### Token Budget by Operation

| Operation | Typical Tokens | When to Use |
|-----------|----------------|------------|
| query_container (overview, no sections) | 50-100 | Status checks, hierarchical views |
| query_container (get, with sections) | 5,000-18,000 | Full documentation needed |
| query_dependencies (single task) | 100-200 | Specific dependency analysis |
| Skill invocation | 300-800 | Coordination work |
| Subagent launch (with context) | 1,500-2,500 | Implementation work |
| File handoff (path reference) | 100 | Pass file to subagent |
| Execution graph read | 80-120 | Plan already provided |

### Decision Matrix

```
Need hierarchical view of feature?
├─ YES, with sections → query_container(operation="get", id="...", includeSections=true)
└─ YES, without sections → query_container(operation="overview", id="...")  [SAVES 85% tokens]

Need to hand off file to subagent?
├─ File available to subagent → Pass file path (~100 tokens)  [SAVES 4900 tokens]
└─ File not available → Embed content (~5000 tokens each)

Need task dependencies analyzed?
├─ Planning Specialist provided graph → Use graph directly  [SAVES 300 tokens]
└─ Fresh analysis needed → query_dependencies(taskId)

Need to update multiple items?
├─ Same type, multiple items → manage_container(bulkUpdate)  [SAVES round-trip]
└─ Different types or properties → Individual updates
```

## Context Management Best Practices

### Output Style Loading (Layer 1)

The Task Orchestrator output style uses progressive disclosure:

```
Initial context loaded: 1,500 tokens (output style)
├─ Coordinator role activated immediately
├─ Pre-flight checklist established
├─ File references noted (NOT loaded)
└─ Ready for 95% of interactions

Additional context loaded on-demand:
├─ decision-trees.md (~300 tokens) - When creating features
├─ workflows.md (~300 tokens) - When managing status
├─ examples.md (~300 tokens) - When in complex scenario
├─ error-handling.md (~200 tokens) - When validation fails
└─ optimizations.md (~150 tokens) - When optimizing performance

Total maximum: 1,500 + 300 + 300 + 300 + 200 + 150 = 2,750 tokens

vs. Old monolithic output style: 12,000 tokens always loaded
Savings: ~9,250 tokens (77% reduction)
```

### Scoped Overview Pattern

**Efficiency Champion - Use frequently:**

```javascript
// When user asks: "Show me details on Feature X"
query_container(
  operation="overview",
  containerType="feature",
  id="feature-uuid"
)
// Returns: feature metadata + tasks list + task counts
// NO section content, NO excessive detail
// Tokens: ~1,200 vs ~18,500 with sections (93% savings)
```

**When section content IS needed:**

```javascript
// Only when user specifically asks for documentation
query_container(
  operation="get",
  containerType="feature",
  id="feature-uuid",
  includeSections=true
)
// Returns: Complete feature with all sections
// Use sparingly - high token cost
```

## Applying Optimizations in Workflow

### Feature Creation with File

```
User: "Create feature from D:\requirements.md"
  ↓
You: Detect file path
  ↓
You: Launch Feature Architect with path reference
  ↓
Feature Architect: Reads file directly
  ↓
Savings: 4,900 tokens (49% reduction)
```

### Feature Execution with Graph

```
Planning Specialist: Provides execution graph
  ↓
You: Read graph from specialist output
  ↓
Task Orchestration Skill: Launch first batch
  ↓
You: Don't re-query dependencies
  ↓
Savings: 300 tokens per batch (25% reduction)
```

### Status Check

```
User: "What's the status of Feature X?"
  ↓
You: query_container(operation="overview", id="feature-uuid")
  ↓
Query returns: metadata + task counts (no sections)
  ↓
Report: "[5]/[8] tasks complete"
  ↓
Savings: 17,300 tokens (93% reduction vs get with sections)
```
