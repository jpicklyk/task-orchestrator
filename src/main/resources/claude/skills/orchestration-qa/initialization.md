# Session Initialization

**Purpose**: Load knowledge bases for Skills, Subagents, and routing configuration to enable validation throughout the session.

**When**: First interaction in new session (phase="init")

**Token Cost**: ~800-1000 tokens (one-time per session)

## Initialization Workflow

### Step 1: Load Skills Knowledge Base

**Action**: Discover and parse all Skill definitions

```javascript
// Glob all skill files
skillFiles = Glob(pattern=".claude/skills/*/SKILL.md")

// For each skill file found:
for skillFile in skillFiles:
  // Read and parse YAML frontmatter + content
  content = Read(skillFile)

  // Extract from YAML frontmatter
  name = content.frontmatter.name
  description = content.frontmatter.description

  // Extract from content sections
  mandatoryTriggers = extractSection(content, "When to Use This Skill")
  workflows = extractSection(content, "Workflow")
  expectedOutputs = extractSection(content, "Output Format")
  toolUsage = extractSection(content, "Tools Used")
  tokenRange = extractSection(content, "Token Cost")

  // Store in knowledge base
  skills[name] = {
    file: skillFile,
    description: description,
    mandatoryTriggers: mandatoryTriggers,
    workflows: workflows,
    expectedOutputs: expectedOutputs,
    tools: toolUsage,
    tokenRange: tokenRange
  }
```

**Example Skills Loaded**:

```javascript
skills = {
  "Feature Orchestration": {
    file: ".claude/skills/feature-orchestration/SKILL.md",
    mandatoryTriggers: [
      "Create a feature",
      "Complete feature",
      "Feature progress"
    ],
    workflows: [
      "Smart Feature Creation",
      "Task Breakdown Coordination",
      "Feature Completion"
    ],
    expectedOutputs: ["Feature ID", "Task count", "Next action"],
    tools: ["query_container", "manage_container", "query_templates", "recommend_agent"],
    tokenRange: [300, 800]
  },

  "Task Orchestration": {
    file: ".claude/skills/task-orchestration/SKILL.md",
    mandatoryTriggers: [
      "Execute tasks",
      "What's next",
      "Launch batch",
      "What tasks are ready"
    ],
    workflows: [
      "Dependency-Aware Batching",
      "Parallel Specialist Launch",
      "Progress Monitoring"
    ],
    expectedOutputs: ["Batch structure", "Parallel opportunities", "Specialist recommendations"],
    tools: ["query_container", "manage_container", "query_dependencies", "recommend_agent"],
    tokenRange: [500, 900]
  },

  "Status Progression": {
    file: ".claude/skills/status-progression/SKILL.md",
    mandatoryTriggers: [
      "Mark complete",
      "Update status",
      "Status change",
      "Move to testing"
    ],
    workflows: [
      "Read Config",
      "Validate Prerequisites",
      "Interpret Errors"
    ],
    expectedOutputs: ["Status updated", "Validation error with details"],
    tools: ["Read", "query_container", "query_dependencies"],
    tokenRange: [200, 400],
    critical: "MANDATORY for ALL status changes - never bypass"
  },

  "Dependency Analysis": {
    file: ".claude/skills/dependency-analysis/SKILL.md",
    mandatoryTriggers: [
      "What's blocking",
      "Show dependencies",
      "Check blockers"
    ],
    workflows: [
      "Query Dependencies",
      "Analyze Chains",
      "Report Findings"
    ],
    expectedOutputs: ["Blocker list", "Dependency chains", "Unblock suggestions"],
    tools: ["query_dependencies", "query_container"],
    tokenRange: [300, 600]
  },

  "Dependency Orchestration": {
    file: ".claude/skills/dependency-orchestration/SKILL.md",
    mandatoryTriggers: [
      "Resolve circular dependencies",
      "Optimize dependencies"
    ],
    workflows: [
      "Advanced Dependency Analysis",
      "Critical Path",
      "Bottleneck Detection"
    ],
    expectedOutputs: ["Dependency graph", "Critical path", "Optimization suggestions"],
    tokenRange: [400, 700]
  }
}
```

### Step 2: Load Subagents Knowledge Base

**Action**: Discover and parse all Subagent definitions

```javascript
// Glob all subagent files
subagentFiles = Glob(pattern=".claude/agents/task-orchestrator/*.md")

// For each subagent file found:
for subagentFile in subagentFiles:
  // Read and parse content
  content = Read(subagentFile)

  // Extract from YAML frontmatter
  name = content.frontmatter.name
  description = content.frontmatter.description

  // Extract workflow steps (numbered steps in document)
  expectedSteps = extractNumberedSteps(content)

  // Extract critical patterns (CRITICAL, IMPORTANT sections)
  criticalPatterns = extractPatterns(content, markers=["CRITICAL", "IMPORTANT"])

  // Extract output expectations
  outputValidation = extractSection(content, "Output Format" or "Return")

  // Store in knowledge base
  subagents[name] = {
    file: subagentFile,
    description: description,
    triggeredBy: extractTriggeredBy(content),
    expectedSteps: expectedSteps,
    criticalPatterns: criticalPatterns,
    outputValidation: outputValidation,
    tokenRange: extractTokenRange(content)
  }
```

**Example Subagents Loaded**:

```javascript
subagents = {
  "Feature Architect": {
    file: ".claude/agents/task-orchestrator/feature-architect.md",
    triggeredBy: [
      "Complex feature creation",
      "PRD provided",
      "Formal planning"
    ],
    expectedSteps: [
      "Step 1: Understand Context (get_overview, list_tags)",
      "Step 2: Detect Input Type (PRD/Interactive/Quick)",
      "Step 3a/3b/3c: Process based on mode",
      "Step 4: Discover Templates",
      "Step 5: Design Tag Strategy",
      "Step 5.5: Verify Agent Mapping Coverage",
      "Step 6: Create Feature",
      "Step 7: Add Custom Sections (mode-dependent)",
      "Step 8: Return Handoff (minimal)"
    ],
    criticalPatterns: [
      "description = forward-looking (what needs to be built)",
      "Do NOT populate summary field during creation",
      "Return minimal handoff (50-100 tokens)",
      "PRD mode: Extract ALL sections from document",
      "Tag strategy: Reuse existing tags (list_tags first)",
      "Check agent-mapping.yaml for new tags"
    ],
    outputValidation: [
      "Feature created with description?",
      "Templates applied?",
      "Tags follow project conventions?",
      "PRD sections represented (if PRD mode)?",
      "Handoff minimal (not verbose)?"
    ],
    tokenRange: [1800, 2200]
  },

  "Planning Specialist": {
    file: ".claude/agents/task-orchestrator/planning-specialist.md",
    triggeredBy: [
      "Feature needs task breakdown",
      "Complex feature created"
    ],
    expectedSteps: [
      "Step 1: Read Feature Context (includeSections=true)",
      "Step 2: Discover Task Templates",
      "Step 3: Break Down into Domain-Isolated Tasks",
      "Step 4: Create Tasks with Descriptions",
      "Step 5: Map Dependencies",
      "Step 7: Inherit and Refine Tags",
      "Step 8: Return Brief Summary"
    ],
    criticalPatterns: [
      "One task = one specialist domain",
      "Task description populated (200-600 chars)",
      "Do NOT populate summary field",
      "ALWAYS create documentation task for user-facing features",
      "Create separate test task for comprehensive testing",
      "Database → Backend → Frontend dependency pattern"
    ],
    outputValidation: [
      "Tasks created with descriptions?",
      "Domain isolation preserved?",
      "Dependencies mapped correctly?",
      "Documentation task included (if user-facing)?",
      "Testing task included (if needed)?",
      "No circular dependencies?",
      "Templates applied to tasks?"
    ],
    tokenRange: [1800, 2200]
  },

  "Backend Engineer": {
    file: ".claude/agents/task-orchestrator/backend-engineer.md",
    triggeredBy: ["Backend implementation task"],
    expectedSteps: [
      "Step 1: Read task (includeSections=true)",
      "Step 2: Read dependencies (if any)",
      "Step 3: Do work (code, tests)",
      "Step 4: Update task sections",
      "Step 5: Run tests and validate",
      "Step 6: Populate summary (300-500 chars)",
      "Step 7: Create Files Changed section",
      "Step 8: Use Status Progression Skill to mark complete",
      "Step 9: Return minimal output"
    ],
    criticalPatterns: [
      "ALL tests must pass before completion",
      "Summary REQUIRED (300-500 chars)",
      "Files Changed section REQUIRED (ordinal 999)",
      "Use Status Progression Skill to mark complete",
      "Return minimal output (50-100 tokens)",
      "If BLOCKED: Report with details, don't mark complete"
    ],
    outputValidation: [
      "Task marked complete?",
      "Summary populated (300-500 chars)?",
      "Files Changed section created?",
      "Tests mentioned in summary?",
      "Used Status Progression Skill for completion?",
      "Output minimal (not verbose)?",
      "If blocked: Clear reason + attempted fixes?"
    ],
    tokenRange: [1800, 2200]
  }

  // Similar structures for:
  // - Frontend Developer
  // - Database Engineer
  // - Test Engineer
  // - Technical Writer
  // - Bug Triage Specialist
}
```

### Step 3: Load Routing Configuration

**Action**: Read agent-mapping.yaml for tag-based routing

```javascript
// Read routing config
configPath = getProjectRoot().resolve(".taskorchestrator/agent-mapping.yaml")
configContent = Read(configPath)

// Parse YAML
agentMapping = parseYAML(configContent)

// Store tag mappings
routing = {
  tagMappings: agentMapping.tagMappings,
  // Example:
  // "backend" → ["Backend Engineer"]
  // "frontend" → ["Frontend Developer"]
  // "database" → ["Database Engineer"]
  // "testing" → ["Test Engineer"]
  // "documentation" → ["Technical Writer"]
}
```

**Example Routing Configuration**:

```javascript
routing = {
  tagMappings: {
    "backend": ["Backend Engineer"],
    "frontend": ["Frontend Developer"],
    "database": ["Database Engineer"],
    "testing": ["Test Engineer"],
    "documentation": ["Technical Writer"],
    "bug": ["Bug Triage Specialist"],
    "architecture": ["Feature Architect"],
    "planning": ["Planning Specialist"],
    "api": ["Backend Engineer"],
    "ui": ["Frontend Developer"],
    "schema": ["Database Engineer"],
    "migration": ["Database Engineer"]
  }
}
```

### Step 4: Initialize Tracking State

**Action**: Set up session-level tracking for deviations and patterns

```javascript
trainingState = {
  session: {
    startTime: now(),
    knowledgeBaseLoaded: true,
    skillsCount: skills.length,
    subagentsCount: subagents.length
  },

  tracking: {
    // Store original user inputs by workflow ID
    originalInputs: {},

    // Validation checkpoints by workflow ID
    checkpoints: [],

    // Categorized deviations
    deviations: {
      orchestrator: [],      // Routing violations (Skills bypassed)
      skills: [],            // Skill workflow issues
      subagents: []          // Subagent workflow issues
    },

    // Improvement suggestions
    improvements: []
  }
}
```

### Step 5: Report Initialization Status

**Output**: Confirmation that QA system is ready

```markdown
✅ **Orchestration QA Initialized**

**Knowledge Base Loaded:**
- Skills: 5 (feature-orchestration, task-orchestration, status-progression, dependency-analysis, dependency-orchestration)
- Subagents: 8 (feature-architect, planning-specialist, backend-engineer, frontend-developer, database-engineer, test-engineer, technical-writer, bug-triage-specialist)
- Routing: agent-mapping.yaml loaded (12 tag mappings)

**Quality Assurance Active:**
- ✅ Pre-execution validation
- ✅ Post-execution review
- ✅ Routing validation (Skills vs Direct)
- ✅ Pattern tracking (continuous improvement)

**Session Tracking:**
- Deviations: 0 ALERT, 0 WARN, 0 INFO
- Patterns: 0 recurring issues
- Improvements: 0 suggestions

Ready to monitor orchestration quality.
```

## Error Handling

### Skills Directory Not Found

```javascript
if (!exists(".claude/skills/")) {
  return {
    error: "Skills directory not found",
    suggestion: "Run setup_claude_orchestration to install Skills and Subagents",
    fallback: "QA will operate with limited validation (no Skills knowledge)"
  }
}
```

### Subagents Directory Not Found

```javascript
if (!exists(".claude/agents/task-orchestrator/")) {
  return {
    error: "Subagents directory not found",
    suggestion: "Run setup_claude_orchestration to install Subagents",
    fallback: "QA will operate with limited validation (no Subagents knowledge)"
  }
}
```

### Agent Mapping Not Found

```javascript
if (!exists(".taskorchestrator/agent-mapping.yaml")) {
  return {
    warning: "agent-mapping.yaml not found",
    suggestion: "Routing validation will use default patterns",
    fallback: "QA will operate without tag-based routing validation"
  }
}
```

## Caching Strategy

**Knowledge bases are expensive to load** (~800-1000 tokens). Cache them for the session:

```javascript
// Load once per session
if (!session.knowledgeBaseLoaded) {
  loadSkillsKnowledgeBase()
  loadSubagentsKnowledgeBase()
  loadRoutingConfiguration()
  session.knowledgeBaseLoaded = true
}

// Reuse throughout session
skill = skills["Feature Orchestration"]
subagent = subagents["Planning Specialist"]
routing = routing.tagMappings["backend"]
```

**When to reload**:
- New session starts
- User explicitly requests: "Reload QA knowledge base"
- Skills/Subagents modified during session (rare)

## Usage Example

```javascript
// At session start
orchestration-qa(phase="init")

// Returns:
{
  initialized: true,
  skillsCount: 5,
  subagentsCount: 8,
  routingLoaded: true,
  message: "✅ Orchestration QA Initialized - Ready to monitor quality"
}

// Knowledge base now available for all subsequent validations
```
