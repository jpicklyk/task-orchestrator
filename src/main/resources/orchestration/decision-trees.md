---
name: decision-trees
description: Decision trees and workflows for feature creation, task breakdown, and implementation routing in Task Orchestration system
version: "2.0.0"
---

# Decision Trees for Task Orchestration

## Feature Creation Decision

```
User requests feature creation
  ↓
Feature Orchestration Skill (mandatory)
  ↓
Skill assesses complexity:
├─ Simple (< 3 tasks, clear scope)?
│   └─ Skill creates directly with basic templates
└─ Complex (3+ tasks, unclear scope)?
    ├─ Claude Code: Skill launches Feature Architect subagent
    └─ Other clients: Skill uses create_feature_workflow prompt
```

### OPTIMIZATION #5 - File Handoff for Feature Architect

When launching Feature Architect with a file reference (e.g., user provides PRD file path):

**Token-Wasteful Approach (DON'T DO THIS):**
```
❌ Read file (5,000 tokens)
❌ Embed entire content in subagent prompt (5,000 tokens in handoff)
❌ Total: ~10,000 tokens (read + handoff)
```

**Optimized Approach (DO THIS):**
```
✅ Detect file path in user message
✅ Pass file path reference to subagent (~100 tokens)
✅ Feature Architect reads file directly
✅ Total: ~5,100 tokens (subagent reads file)
✅ Savings: ~4,900 tokens (49% reduction on handoff)
```

**Implementation Pattern:**
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
    2. [Rest of instructions...]
    """

    # Launch Feature Architect with file path
    launch_subagent("Feature Architect", subagentPrompt)
```

**File Path Detection:**
- Windows: `D:\path\to\file.md`, `C:\Users\...`
- Unix: `/path/to/file.md`, `~/path/...`
- Relative: `./file.md`, `../docs/file.md`
- Extensions: `.md`, `.txt`, `.yaml`, `.json`, `.pdf`

**Benefits:**
- 49% token reduction per file handoff
- Subagent reads with same permissions
- No content duplication
- Scales to multiple file references

## Task Breakdown Decision

```
Feature needs task breakdown
  ↓
Feature Orchestration Skill (mandatory)
  ↓
Skill assesses breakdown:
├─ Simple breakdown (< 5 tasks)?
│   └─ Skill creates tasks directly
└─ Complex breakdown (5+ tasks, dependencies)?
    ├─ Claude Code: Skill launches Planning Specialist subagent
    └─ Other clients: Skill uses task_breakdown_workflow prompt
```

## Task Execution Decision

```
User requests task execution
  ↓
Task Orchestration Skill (mandatory)
  ↓
Skill workflow:
├─ Check dependencies
├─ Identify parallel opportunities
├─ Use recommend_agent() for routing
└─ Execute:
    ├─ Claude Code: Launch specialists (single or parallel)
    └─ Other clients: Use implementation_workflow prompt
```

### OPTIMIZATION #6 - Trust Planning Specialist's Execution Graph

When Planning Specialist has already provided an execution graph, Task Orchestration Skill should **trust and use it** instead of regenerating dependencies.

**Token-Wasteful Approach (DON'T DO THIS):**
```
❌ Planning Specialist creates tasks + dependencies + execution graph
❌ Task Orchestration Skill re-queries ALL dependencies (300-400 tokens)
❌ Task Orchestration Skill regenerates execution batches (redundant work)
❌ Total waste: ~300-400 tokens per feature execution start
```

**Optimized Approach (DO THIS):**
```
✅ Planning Specialist provides execution graph in output (80-120 tokens)
✅ Task Orchestration Skill reads Planning Specialist's graph
✅ Task Orchestration Skill queries only current task status (pending/in-progress/completed)
✅ Task Orchestration Skill recommends WHICH batch to start (don't re-analyze graph)
✅ Savings: ~300-400 tokens per feature execution start
```

**Planning Specialist's Graph Format:**
```
Batch 1 (2 tasks, parallel):
- Kotlin Enums, Config

Batch 2 (1 task):
- Migration V12 (depends on: Enums)

Batch 3 (2 tasks, parallel):
- Alignment Tests (depends on: Migration, Config)
- Migration Test (depends on: Migration)

Next: Task Orchestration Skill
```

**Task Orchestration Skill Usage:**
```python
# After Planning Specialist completes:

# ❌ DON'T re-query all dependencies
# for each task:
#     query_dependencies(taskId)  # Redundant!

# ✅ DO trust Planning Specialist's graph
planningGraph = read_from_planning_specialist_output()
batches = planningGraph.batches  # Already structured!

# Only query current status
for task in feature.tasks:
    status = task.status  # pending, in-progress, completed

# Recommend which batch to start
if all_tasks_in_batch_1_completed():
    recommend("Start Batch 2: Migration V12")
elif all_pending():
    recommend("Start Batch 1: Kotlin Enums, Config (parallel)")
```

**Benefits:**
- Eliminates redundant dependency queries
- Faster task execution start
- Consistent with Planning Specialist's analysis
- 300-400 token savings per feature

**When Task Orchestration Skill Should Re-Query:**
- ❌ Never on feature start (trust Planning Specialist)
- ✅ Only when dependencies changed since planning
- ✅ Only when user explicitly requests dependency re-analysis

## Implementation Work Decision

```
User requests implementation work (not coordination)
  ↓
ASK USER: Direct vs Specialist?
  ↓
├─ User chooses DIRECT:
│   ├─ You do the work
│   └─ You manage task lifecycle (via Status Progression Skill)
│
└─ User chooses SPECIALIST:
    ├─ Use Task Orchestration Skill to route
    └─ Verify specialist completed task lifecycle
```
