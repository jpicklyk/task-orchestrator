# Pre-Execution Validation

**Purpose**: Capture context and set validation checkpoints before launching any Skill or Subagent.

**When**: Before any Skill or Subagent is launched (phase="pre")

**Token Cost**: ~400-600 tokens

## Validation Workflow

### Step 1: Capture Original User Input

**Critical**: Store the user's complete original request for post-execution comparison.

```javascript
context = {
  userInput: {
    fullText: userMessage,
    timestamp: now(),
    inputType: detectInputType(userMessage)  // PRD / Detailed / Quick / Command
  }
}
```

**Input Type Detection**:

```javascript
function detectInputType(message) {
  // PRD: Formal document with multiple sections
  if (message.includes("# ") && message.length > 500 && hasSections(message)) {
    return "PRD"
  }

  // Detailed: Rich context, multiple paragraphs, requirements
  if (message.length > 200 && paragraphCount(message) >= 3) {
    return "Detailed"
  }

  // Quick: Short request, minimal context
  if (message.length < 100) {
    return "Quick"
  }

  // Command: Direct instruction
  return "Command"
}
```

### Step 2: Identify Entity Type

**Determine what's being launched** (Skill vs Subagent):

```javascript
entityType = identifyEntity(userMessage, context)

// Skills
if (matches(userMessage, skills[].mandatoryTriggers)) {
  entityType = matchedSkill  // "feature-orchestration", "task-orchestration", etc.
  category = "SKILL"
}

// Subagents
if (orchestrator decides to launch subagent) {
  entityType = subagentName  // "feature-architect", "planning-specialist", etc.
  category = "SUBAGENT"
}
```

### Step 3: Set Entity-Specific Validation Checkpoints

**Create checklist to verify after execution completes.**

#### Feature Orchestration Skill

```javascript
checkpoints = [
  "Verify Skill assessed complexity correctly",
  "Verify Skill created feature OR launched Feature Architect",
  "Verify templates discovered via query_templates",
  "Verify output in token range (300-800 tokens)"
]

context.expected = {
  complexity: detectExpectedComplexity(userInput),
  mode: "simple" or "complex",
  tools: ["query_templates", "manage_container" or "Task(Feature Architect)"],
  tokenRange: [300, 800]
}
```

**Complexity Detection**:
```javascript
function detectExpectedComplexity(input) {
  // Simple indicators
  if (input.length < 150 && paragraphs < 2) return "simple"

  // Complex indicators
  if (input.inputType == "PRD") return "complex"
  if (input.length > 200) return "complex"
  if (mentions(input, ["multiple", "integration", "system"])) return "complex"

  return "simple"
}
```

#### Task Orchestration Skill

```javascript
checkpoints = [
  "Verify Skill analyzed dependencies via query_dependencies",
  "Verify Skill identified parallel opportunities",
  "Verify Skill used recommend_agent for routing",
  "Verify Skill returned batch structure",
  "Verify output in token range (500-900 tokens)"
]

// Get current feature state for comparison
if (featureId) {
  context.featureState = {
    totalTasks: query_container(containerType="feature", id=featureId).taskCounts.total,
    pendingTasks: query_container(containerType="feature", id=featureId, status="pending").length,
    dependencies: query_dependencies for all pending tasks
  }
}
```

#### Status Progression Skill

```javascript
checkpoints = [
  "Verify Skill read config.yaml",
  "Verify Skill validated prerequisites",
  "Verify Skill returned clear result or error",
  "Verify output in token range (200-400 tokens)"
]

// CRITICAL CHECK: Was Status Progression Skill actually used?
context.criticalValidation = {
  mustUseSkill: true,
  violationSeverity: "CRITICAL",
  reason: "Status changes MUST use Status Progression Skill for prerequisite validation"
}

// Get current entity state for prerequisite checking
context.entityState = {
  currentStatus: entity.status,
  summary: entity.summary,
  dependencies: query_dependencies(taskId) if task,
  tasks: query_container(featureId).tasks if feature
}
```

#### Feature Architect Subagent

```javascript
checkpoints = [
  "Compare Feature Architect output vs original user input",
  "Verify mode detection (PRD/Interactive/Quick)",
  "Verify all PRD sections extracted (if PRD mode)",
  "Verify core concepts preserved",
  "Verify templates applied",
  "Verify tags follow project conventions",
  "Verify agent-mapping.yaml checked (for new tags)",
  "Verify handoff minimal (50-100 tokens)"
]

// PRD Mode: Extract sections from user input
if (context.userInput.inputType == "PRD") {
  context.prdSections = extractSections(userInput)
  // Example: ["Business Context", "User Stories", "Technical Specs", "Requirements"]

  checkpoints.push(
    "Verify all PRD sections have corresponding feature sections"
  )
}

context.expected = {
  mode: context.userInput.inputType,
  descriptionLength: context.userInput.inputType == "PRD" ? [500, 1000] : [200, 500],
  sectionsExpected: context.prdSections?.length || 0,
  handoffTokens: [50, 100],
  tokenRange: [1800, 2200]
}
```

#### Planning Specialist Subagent

```javascript
// First, read the created feature
feature = query_container(operation="get", containerType="feature", id=featureId, includeSections=true)

// Store feature requirements for comparison
context.featureRequirements = {
  description: feature.description,
  sections: feature.sections,
  isUserFacing: detectUserFacing(feature),
  requiresMultipleDomains: detectDomains(feature)
}

checkpoints = [
  "Verify domain isolation (one task = one specialist)",
  "Verify dependencies mapped (Database → Backend → Frontend)",
  "Verify documentation task created (if user-facing)",
  "Verify testing task created (if needed)",
  "Verify all feature requirements covered by tasks",
  "Verify no cross-domain tasks",
  "Verify no circular dependencies",
  "Verify task descriptions populated (200-600 chars)",
  "Verify templates applied to tasks",
  "Verify output in token range (1800-2200 tokens)"
]

context.expected = {
  needsDocumentation: context.featureRequirements.isUserFacing,
  needsTesting: detectTestingNeeded(feature),
  domainCount: context.featureRequirements.requiresMultipleDomains ? 3 : 1,
  tokenRange: [1800, 2200]
}
```

**Domain Detection**:
```javascript
function detectDomains(feature) {
  domains = []

  if (mentions(feature.description, ["database", "schema", "migration"])) {
    domains.push("database")
  }
  if (mentions(feature.description, ["api", "service", "endpoint", "backend"])) {
    domains.push("backend")
  }
  if (mentions(feature.description, ["ui", "component", "page", "frontend"])) {
    domains.push("frontend")
  }

  return domains.length
}
```

#### Implementation Specialist Subagents

**Applies to**: Backend Engineer, Frontend Developer, Database Engineer, Test Engineer, Technical Writer

```javascript
// Read task context
task = query_container(operation="get", containerType="task", id=taskId, includeSections=true)

context.taskRequirements = {
  description: task.description,
  sections: task.sections,
  hasDependencies: query_dependencies(taskId).incoming.length > 0,
  complexity: task.complexity
}

checkpoints = [
  "Verify specialist completed task lifecycle",
  "Verify tests run and passing (if code task)",
  "Verify summary populated (300-500 chars)",
  "Verify Files Changed section created (ordinal 999)",
  "Verify used Status Progression Skill to mark complete",
  "Verify output minimal (50-100 tokens)",
  "If blocked: Verify clear reason + attempted fixes"
]

context.expected = {
  summaryLength: [300, 500],
  hasFilesChanged: true,
  statusChanged: true,
  tokenRange: [1800, 2200],
  outputTokens: [50, 100]
}

// Verify recommend_agent was used
context.routingValidation = {
  shouldUseRecommendAgent: true,
  matchesTags: checkTagMatch(task.tags, specialistName)
}
```

### Step 4: Verify Routing Decision

**Check orchestrator made correct routing choice.**

```javascript
routingCheck = {
  userRequest: userMessage,
  detectedIntent: detectIntent(userMessage),
  orchestratorChoice: entityType,
  correctChoice: validateRouting(detectedIntent, entityType)
}

// Intent detection
function detectIntent(message) {
  // Coordination triggers → MUST use Skills
  coordinationTriggers = [
    "mark complete", "update status", "create feature",
    "execute tasks", "what's next", "check blockers", "complete feature"
  ]

  // Implementation triggers → Should ask user (Direct vs Specialist)
  implementationTriggers = [
    "implement", "write code", "create API", "build",
    "add tests", "fix bug", "database schema", "frontend component"
  ]

  if (matches(message, coordinationTriggers)) return "COORDINATION"
  if (matches(message, implementationTriggers)) return "IMPLEMENTATION"

  return "UNKNOWN"
}

// Routing validation
function validateRouting(intent, choice) {
  if (intent == "COORDINATION" && !isSkill(choice)) {
    return {
      valid: false,
      severity: "CRITICAL",
      violation: "Coordination request must use Skill, not direct tools or subagent",
      expected: "Use appropriate Skill (Feature Orchestration, Task Orchestration, Status Progression)"
    }
  }

  if (intent == "IMPLEMENTATION" && !askedUser) {
    return {
      valid: false,
      severity: "WARN",
      violation: "Implementation request should ask user (Direct vs Specialist)",
      expected: "Ask user preference before proceeding"
    }
  }

  return { valid: true }
}
```

**Special Case: Status Changes**

```javascript
// Status changes are ALWAYS coordination → MUST use Status Progression Skill
if (userMessage.includes("complete") || userMessage.includes("status")) {
  if (choice != "status-progression") {
    return {
      valid: false,
      severity: "CRITICAL",
      violation: "Status change MUST use Status Progression Skill",
      reason: "Prerequisite validation required (summary length, dependencies, task counts)",
      expected: "Use Status Progression Skill for ALL status changes"
    }
  }
}
```

### Step 5: Store Context for Post-Execution

**Save all captured information for comparison after execution.**

```javascript
session.contexts[workflowId] = {
  timestamp: now(),
  userInput: context.userInput,
  entityType: entityType,
  category: "SKILL" or "SUBAGENT",
  checkpoints: checkpoints,
  expected: context.expected,
  featureRequirements: context.featureRequirements,  // if Planning Specialist
  taskRequirements: context.taskRequirements,        // if Implementation Specialist
  routingValidation: routingCheck,
  criticalValidation: context.criticalValidation     // if Status Progression
}
```

### Step 6: Return Ready Signal

```javascript
return {
  ready: true,
  contextCaptured: true,
  entityType: entityType,
  category: category,
  checkpoints: checkpoints.length,
  routingValid: routingCheck.valid,
  warnings: routingCheck.valid ? [] : [routingCheck.violation]
}
```

**If routing violation detected**, alert immediately:

```javascript
if (!routingCheck.valid && routingCheck.severity == "CRITICAL") {
  return {
    ready: false,
    violation: {
      severity: "CRITICAL",
      type: "Routing Violation",
      message: routingCheck.violation,
      expected: routingCheck.expected,
      action: "STOP - Do not proceed until corrected"
    }
  }
}
```

## Routing Violation Examples

### CRITICAL: Status Change Without Status Progression Skill

```javascript
User: "Mark task T1 complete"
Orchestrator: [Calls manage_container directly]

// Pre-execution validation detects:
{
  violation: "CRITICAL",
  type: "Status change bypassed mandatory Status Progression Skill",
  expected: "Use Status Progression Skill for status changes",
  reason: "Prerequisite validation required (summary 300-500 chars, dependencies completed)",
  action: "STOP - Use Status Progression Skill instead"
}

// Alert user immediately, do NOT proceed
```

### CRITICAL: Feature Creation Without Feature Orchestration Skill

```javascript
User: "Create a user authentication feature"
Orchestrator: [Calls manage_container directly]

// Pre-execution validation detects:
{
  violation: "CRITICAL",
  type: "Feature creation bypassed mandatory Feature Orchestration Skill",
  expected: "Use Feature Orchestration Skill for feature creation",
  reason: "Complexity assessment and template discovery required",
  action: "STOP - Use Feature Orchestration Skill instead"
}
```

### WARN: Implementation Without Asking User

```javascript
User: "Implement login API"
Orchestrator: [Works directly without asking preference]

// Pre-execution validation detects:
{
  violation: "WARN",
  type: "Implementation without user preference",
  expected: "Ask user: Direct vs Specialist?",
  reason: "User should choose approach",
  action: "Log to TodoWrite, suggest asking user"
}

// Log but don't block
```

## Output Example

```javascript
// Successful pre-execution
{
  ready: true,
  contextCaptured: true,
  entityType: "planning-specialist",
  category: "SUBAGENT",
  checkpoints: 10,
  routingValid: true,
  expected: {
    mode: "Detailed",
    needsDocumentation: true,
    domainCount: 3,
    tokenRange: [1800, 2200]
  },
  message: "✅ Ready to launch Planning Specialist - 10 checkpoints set"
}
```

## Integration Example

```javascript
// Before launching Planning Specialist
orchestration-qa(
  phase="pre",
  entityType="planning-specialist",
  userInput="Create user authentication feature with OAuth2, JWT tokens, role-based access"
)

// Returns context captured, checkpoints set
// Orchestrator proceeds with launch
```
