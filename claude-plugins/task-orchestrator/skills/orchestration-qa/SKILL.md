---
name: Orchestration QA
description: Quality assurance for orchestration workflows - validates Skills and Subagents follow documented patterns, tracks deviations, suggests improvements
---

# Orchestration QA Skill

## Overview

This skill provides quality assurance for Task Orchestrator workflows by validating that Skills and Subagents follow their documented patterns, detecting deviations, and suggesting continuous improvements.

**Key Capabilities:**
- **Interactive configuration** - User chooses which analyses to enable (token efficiency)
- **Pre-execution validation** - Context capture, checkpoint setting
- **Post-execution review** - Workflow adherence, output validation
- **Specialized quality analysis** - Execution graphs, tag coverage, information density
- **Efficiency analysis** - Token optimization, tool selection, parallelization
- **Deviation reporting** - Structured findings with severity (ALERT/WARN/INFO)
- **Pattern tracking** - Continuous improvement suggestions

**Philosophy:**
- ✅ **User-driven configuration** - Pay token costs only for analyses you want
- ✅ **Observe and validate** - Never blocks execution
- ✅ **Report transparently** - Clear severity levels (ALERT/WARN/INFO)
- ✅ **Learn from patterns** - Track issues, suggest improvements
- ✅ **Progressive loading** - Load only analysis needed for context
- ❌ **Not a blocker** - Warns about issues, doesn't stop workflows
- ❌ **Not auto-fix** - Asks user for decisions on deviations

## When to Use This Skill

### Interactive Configuration (FIRST TIME)
**Trigger**: First time using orchestration-qa in a session, or when user wants to change settings
**Action**: Ask user which analysis categories to enable (multiselect interface)
**Output**: Configuration stored in session, used for all subsequent reviews
**User Value**: Only pay token costs for analyses you actually want

### Session Initialization
**Trigger**: After configuration, at start of orchestration session
**Action**: Load knowledge bases (Skills, Subagents, routing config) based on enabled categories
**Output**: Initialization status with active configuration, ready signal

### Pre-Execution Validation
**Triggers**:
- "Create feature for X" (before Feature Orchestration Skill or Feature Architect)
- "Execute tasks" (before Task Orchestration Skill)
- "Mark complete" (before Status Progression Skill)
- Before launching any Skill or Subagent

**Action**: Capture context, set validation checkpoints
**Output**: Stored context for post-execution comparison

### Post-Execution Review
**Triggers**:
- After any Skill completes
- After any Subagent returns
- User asks: "Review quality", "Show QA results", "Any issues?"

**Action**: Validate workflow adherence, analyze quality, detect deviations
**Output**: Structured quality report with findings and recommendations

## Parameters

```typescript
{
  phase: "init" | "pre" | "post" | "configure",

  // For pre/post phases
  entityType?: "feature-orchestration" | "task-orchestration" |
               "status-progression" | "dependency-analysis" |
               "feature-architect" | "planning-specialist" |
               "backend-engineer" | "frontend-developer" |
               "database-engineer" | "test-engineer" |
               "technical-writer" | "bug-triage-specialist",

  // For pre phase
  userInput?: string,          // Original user request

  // For post phase
  entityOutput?: string,       // Output from Skill/Subagent
  entityId?: string,           // Feature/Task/Project ID (if applicable)

  // Optional
  verboseReporting?: boolean           // Default: false (brief reports)
}
```

## Workflow

### Phase: configure (Interactive Configuration) - **ALWAYS RUN FIRST**

**Purpose**: Let user choose which analysis categories to enable for the session

**When**: Before init phase, or when user wants to change settings mid-session

**Interactive Prompts**:

Use AskUserQuestion to present configuration options:

```javascript
AskUserQuestion({
  questions: [
    {
      question: "Which quality analysis categories would you like to enable for this session?",
      header: "QA Categories",
      multiSelect: true,
      options: [
        {
          label: "Information Density",
          description: "Analyze task content quality, detect wasteful patterns, measure information-to-token ratio (Specialists only)"
        },
        {
          label: "Execution Graphs",
          description: "Validate dependency graphs and parallel execution opportunities (Planning Specialist only)"
        },
        {
          label: "Tag Coverage",
          description: "Check tag consistency and agent-mapping coverage (Planning Specialist & Feature Architect)"
        },
        {
          label: "Token Optimization",
          description: "Identify token waste patterns (verbose output, unnecessary loading, redundant operations)"
        },
        {
          label: "Tool Selection",
          description: "Verify optimal tool usage (overview vs get, search vs filtered query, bulk operations)"
        },
        {
          label: "Routing Validation",
          description: "Detect Skills bypass violations (CRITICAL - status changes, feature creation, task execution)"
        },
        {
          label: "Parallel Detection",
          description: "Find missed parallelization opportunities (independent tasks, batch operations)"
        }
      ]
    },
    {
      question: "How detailed should QA reports be?",
      header: "Report Style",
      multiSelect: false,
      options: [
        {
          label: "Brief",
          description: "Only show critical issues (ALERT level) - minimal token usage"
        },
        {
          label: "Standard",
          description: "Show ALERT and WARN level issues with brief explanations"
        },
        {
          label: "Detailed",
          description: "Show all issues (ALERT/WARN/INFO) with full analysis and recommendations"
        }
      ]
    }
  ]
})
```

**Default Configuration** (if user skips configuration):
- ✅ Routing Validation (CRITICAL - always enabled)
- ✅ Information Density (for specialists)
- ❌ All other categories disabled
- Report style: Standard

**Configuration Storage**:
Store user preferences in session state:
```javascript
session.qaConfig = {
  enabled: {
    informationDensity: true/false,
    executionGraphs: true/false,
    tagCoverage: true/false,
    tokenOptimization: true/false,
    toolSelection: true/false,
    routingValidation: true,  // Always true (CRITICAL)
    parallelDetection: true/false
  },
  reportStyle: "brief" | "standard" | "detailed"
}
```

**Token Cost**: ~200-300 tokens (one-time configuration)

### Phase: init (Session Initialization)

**Purpose**: Load knowledge bases for validation throughout session

**Steps**:
1. **If not configured**: Run configure phase first (interactive)
2. Read `initialization.md` for setup workflow
3. Glob `.claude/skills/*/SKILL.md` → extract Skills knowledge
   - Parse skill name, triggers, workflows, tools, token ranges
4. Glob `.claude/agents/task-orchestrator/*.md` → extract Subagents knowledge
   - Parse agent name, steps, critical patterns, output validation
5. Read `agent-mapping.yaml` → extract routing configuration
6. Initialize tracking state (deviations, patterns, improvements)
7. Report initialization status with active configuration

**Output**:
```javascript
{
  initialized: true,
  knowledgeBase: {
    skillsCount: 5,
    subagentsCount: 8,
    routingLoaded: true
  },
  configuration: {
    enabled: ["Information Density", "Routing Validation"],
    disabled: ["Execution Graphs", "Tag Coverage", "Token Optimization", "Tool Selection", "Parallel Detection"],
    reportStyle: "standard"
  },
  tracking: {
    sessionStart: timestamp,
    deviations: [],
    patterns: []
  }
}
```

**Token Cost**: ~800-1000 tokens (loads once per session)

### Phase: pre (Pre-Execution Validation)

**Purpose**: Capture context and set validation checkpoints before launching

**Steps**:
1. Read `pre-execution.md` for validation checklist
2. Identify entity type (Skill vs Subagent)
3. Capture original user input context
4. Set entity-specific validation checkpoints based on type:
   - **Skills**: Expected workflow steps, tool usage, token range
   - **Subagents**: Expected steps (8-9 steps), critical patterns, output format
5. Store context for post-execution comparison
6. Return ready signal

**Context Captured**:
- User's original request (full text)
- Expected mode (PRD/Interactive/Quick for Feature Architect)
- Entity type and anticipated complexity
- Validation checkpoints to verify after execution

**Output**:
```javascript
{
  ready: true,
  contextCaptured: true,
  checkpoints: [
    "Verify Skill assessed complexity correctly",
    "Verify templates discovered and applied",
    // ... entity-specific checkpoints
  ]
}
```

**Token Cost**: ~400-600 tokens

### Phase: post (Post-Execution Review)

**Purpose**: Validate workflow adherence, analyze quality, detect deviations

**Steps**:

#### 1. Load Post-Execution Workflow
Read `post-execution.md` for review process

#### 2. Determine Required Analyses
Based on entity type AND user configuration:

**Planning Specialist**:
- Always: `post-execution.md` → core workflow validation
- If `routingValidation` enabled: `routing-validation.md` → Skills usage check
- If `executionGraphs` enabled: `graph-quality.md` → execution graph validation
- If `tagCoverage` enabled: `tag-quality.md` → tag coverage analysis

**Feature Architect**:
- Always: `post-execution.md` → PRD extraction validation
- Always: Compare output vs original user input
- If `routingValidation` enabled: `routing-validation.md` → agent-mapping check
- If `tagCoverage` enabled: `tag-quality.md` → tag consistency check

**Implementation Specialists** (Backend, Frontend, Database, Test, Technical Writer):
- Always: `post-execution.md` → lifecycle steps verification
- If `routingValidation` enabled: `routing-validation.md` → Status Progression Skill usage
- If `informationDensity` enabled: `task-content-quality.md` → content quality analysis
- Always: Verify summary (300-500 chars), Files Changed section, test results

**All Skills**:
- Always: Read skill definition from knowledge base
- Always: Verify expected workflow steps followed
- Always: Check tool usage matches expected patterns
- Always: Validate token range

#### 3. Conditional Efficiency Analysis
Based on user configuration:
- If `tokenOptimization` enabled: Read `token-optimization.md` → identify token waste
- If `toolSelection` enabled: Read `tool-selection.md` → verify optimal tool usage
- If `parallelDetection` enabled: Read `parallel-detection.md` → find missed parallelization

#### 4. Deviation Detection
Compare actual execution against expected patterns:
- **ALERT**: Critical violations (status bypass, cross-domain tasks, missing requirements)
- **WARN**: Process issues (verbose output, skipped steps, suboptimal dependencies)
- **INFO**: Observations (efficiency opportunities, quality patterns)

#### 5. Reporting
If deviations found:
- Read `deviation-templates.md` → format report
- Add to TodoWrite with appropriate severity
- If ALERT: Report immediately to user with decision prompt
- If WARN: Log for end-of-session summary
- If INFO: Track for pattern analysis

#### 6. Pattern Tracking
Read `pattern-tracking.md` → continuous improvement:
- Check for recurring issues (count >= 2 in session)
- Suggest definition improvements if patterns detected
- Track for session summary

**Output**:
```javascript
{
  workflowAdherence: "8/8 steps followed (100%)",
  expectedOutputs: "7/7 present",
  deviations: [
    {
      severity: "ALERT",
      issue: "Cross-domain task detected",
      details: "Task mixes backend + frontend",
      recommendation: "Split into domain-isolated tasks"
    }
  ],
  analyses: {
    graphQuality: "95%",
    tagCoverage: "100%",
    tokenEfficiency: "85%"
  },
  recommendations: [
    "Update planning-specialist.md to enforce domain isolation",
    "Add validation checklist for cross-domain detection"
  ]
}
```

**Token Cost**:
- Basic validation: ~600-800 tokens
- With specialized analysis (Planning Specialist): ~1500-2000 tokens
- With efficiency analysis: +800-1200 tokens

## Progressive Loading Strategy

**Optimization**: Load only the analysis docs needed based on entity type AND user configuration

### Configuration-Driven Loading

**Core Loading** (always loaded regardless of config):
- `post-execution.md` → base workflow validation
- Skill/Subagent definition from knowledge base
- Entity-specific mandatory checks (summary, files changed, etc.)

**Conditional Loading** (based on user configuration):

```javascript
// Planning Specialist
if (config.routingValidation) → Read routing-validation.md
if (config.executionGraphs) → Read graph-quality.md
if (config.tagCoverage) → Read tag-quality.md

// Feature Architect
if (config.routingValidation) → Read routing-validation.md
if (config.tagCoverage) → Read tag-quality.md

// Implementation Specialists (Backend, Frontend, Database, Test, Technical Writer)
if (config.routingValidation) → Read routing-validation.md
if (config.informationDensity) → Read task-content-quality.md

// All Entities
if (config.tokenOptimization) → Read token-optimization.md
if (config.toolSelection) → Read tool-selection.md
if (config.parallelDetection) → Read parallel-detection.md

// Reporting
if (deviations.length > 0) → Read deviation-templates.md
if (session.deviations.count >= 2) → Read pattern-tracking.md
```

### Token Savings Examples

**Example 1: User only wants Information Density feedback**
- Configuration: Only "Information Density" enabled
- Loaded for Backend Engineer: `post-execution.md` + `task-content-quality.md` = ~1,200 tokens
- Skipped: `routing-validation.md`, `token-optimization.md`, `tool-selection.md`, `parallel-detection.md` = ~2,400 tokens saved
- **Savings: 67% reduction**

**Example 2: User wants minimal CRITICAL validation only**
- Configuration: Only "Routing Validation" enabled
- Loaded: `post-execution.md` + `routing-validation.md` = ~1,000 tokens
- Skipped: All other analysis docs = ~3,500 tokens saved
- **Savings: 78% reduction**

**Example 3: User wants comprehensive Planning Specialist review**
- Configuration: All categories enabled
- Loaded: `post-execution.md` + `graph-quality.md` + `tag-quality.md` + `routing-validation.md` + efficiency docs = ~3,500 tokens
- Skipped: None (comprehensive mode)
- **Savings: 0% (full analysis)**

### Special Cases

**Task Orchestration Skill**:
- `parallel-detection.md` always loaded if enabled in config (core to this skill's purpose)

**Status Progression Skill**:
- `routing-validation.md` always loaded if enabled in config (CRITICAL - status bypass detection)

## Output Format

### Success (No Deviations)
```markdown
✅ **QA Review**: [Entity Name]

Workflow adherence: 100%
All quality checks passed.

[If efficiency analysis enabled:]
Token efficiency: 85% (identified 2 optimization opportunities)
```

### Issues Found
```markdown
## QA Review: [Entity Name]

**Workflow Adherence:** X/Y steps (Z%)

### ✅ Successes
- [Success 1]
- [Success 2]

### ⚠️ Issues Detected

**🚨 ALERT**: [Critical issue]
- Impact: [What this affects]
- Found: [What was observed]
- Expected: [What should have happened]
- Recommendation: [How to fix]

**⚠️ WARN**: [Process issue]
- Found: [What was observed]
- Expected: [What should have happened]

### 📋 Added to TodoWrite
- Review [Entity]: [Issue description]
- Improvement: [Suggestion]

### 🎯 Recommendations
1. [Most critical action]
2. [Secondary action]

### 💭 Decision Required
[If user decision needed, present options]
```

## Integration with Orchestrator

**Recommended Pattern**:

```javascript
// 1. FIRST TIME: Interactive configuration
Use orchestration-qa skill (phase="configure")
// Agent asks user which analysis categories to enable
// User selects: "Information Density" + "Routing Validation"
// Configuration stored in session

// 2. Session initialization
Use orchestration-qa skill (phase="init")
// Returns: Initialized with [2] analysis categories enabled

// 3. Before launching Feature Architect
Use orchestration-qa skill (
  phase="pre",
  entityType="feature-architect",
  userInput="[user's original request]"
)

// 4. Launch Feature Architect
Task(subagent_type="Feature Architect", prompt="...")

// 5. After Feature Architect returns
Use orchestration-qa skill (
  phase="post",
  entityType="feature-architect",
  entityOutput="[subagent's response]",
  entityId="feature-uuid"
)
// Only loads: post-execution.md + routing-validation.md (user config)
// Skips: graph-quality.md, tag-quality.md, token-optimization.md (not enabled)

// 6. Review QA findings, take action if needed
```

**Mid-Session Reconfiguration**:

```javascript
// User: "I want to also track token optimization now"
Use orchestration-qa skill (phase="configure")
// Agent asks again, pre-selects current config
// User adds "Token Optimization" to enabled categories
// New config stored, affects all subsequent post-execution reviews
```

## Supporting Documentation

This skill uses progressive loading to minimize token usage. Supporting docs are read as needed:

- **initialization.md** - Session setup workflow
- **pre-execution.md** - Context capture and checkpoint setting
- **post-execution.md** - Core review workflow for all entities
- **graph-quality.md** - Planning Specialist: execution graph analysis
- **tag-quality.md** - Planning Specialist: tag coverage validation
- **task-content-quality.md** - Implementation Specialists: information density and wasteful pattern detection
- **token-optimization.md** - Efficiency: identify token waste patterns
- **tool-selection.md** - Efficiency: verify optimal tool usage
- **parallel-detection.md** - Efficiency: find missed parallelization
- **routing-validation.md** - Critical: Skills vs Direct tool violations
- **deviation-templates.md** - User report formatting by severity
- **pattern-tracking.md** - Continuous improvement tracking

## Token Efficiency

**Current Trainer** (monolithic): ~20k-30k tokens always loaded

**Orchestration QA Skill** (configuration-driven progressive loading):
- Configure phase: ~200-300 tokens (one-time, interactive)
- Init phase: ~1000 tokens (one-time per session)
- Pre-execution: ~600 tokens (per entity)
- Post-execution (varies by configuration):
  - **Minimal** (routing only): ~800-1000 tokens
  - **Standard** (info density + routing): ~1200-1500 tokens
  - **Planning Specialist** (graphs + tags + routing): ~2000-2500 tokens
  - **Comprehensive** (all categories): ~3500-4000 tokens

**Configuration Impact Examples**:

| User Configuration | Token Cost | vs Monolithic | vs Default |
|-------------------|------------|---------------|------------|
| Information Density only | ~1,200 tokens | 94% savings | 67% savings |
| Routing Validation only | ~1,000 tokens | 95% savings | 78% savings |
| Default (Info + Routing) | ~1,500 tokens | 93% savings | baseline |
| Comprehensive (all enabled) | ~4,000 tokens | 80% savings | -167% |

**Smart Defaults**: Most users only need Information Density + Routing Validation, achieving 93% token reduction while catching critical issues and wasteful content.

## Quality Metrics

Track these metrics across sessions:
- Workflow adherence percentage
- Deviation count by severity (ALERT/WARN/INFO)
- Pattern recurrence (same issue multiple times)
- Definition improvement suggestions generated
- Token efficiency of analyzed workflows

## Examples

See `examples.md` for detailed usage scenarios including:
- **Interactive configuration** - Choosing analysis categories
- **Session initialization** - Loading knowledge bases with config
- **Feature Architect validation** - PRD mode with selective analysis
- **Planning Specialist review** - Graph + tag analysis (when enabled)
- **Implementation Specialist review** - Information density tracking
- **Status Progression enforcement** - Critical routing violations
- **Mid-session reconfiguration** - Changing enabled categories
- **Token efficiency comparisons** - Different configuration impacts
