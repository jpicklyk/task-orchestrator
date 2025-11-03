# Post-Execution Review

**Purpose**: Validate that Skills and Subagents followed their documented workflows and produced expected outputs.

**When**: After any Skill or Subagent completes (phase="post")

**Token Cost**: ~600-800 tokens (basic), ~1500-2000 tokens (with specialized analysis)

## Core Review Workflow

### Step 1: Load Entity Definition

**Action**: Read the definition from knowledge base loaded during initialization.

```javascript
// For Skills
if (category == "SKILL") {
  definition = skills[entityType]
  // Contains: mandatoryTriggers, workflows, expectedOutputs, tools, tokenRange
}

// For Subagents
if (category == "SUBAGENT") {
  definition = subagents[entityType]
  // Contains: expectedSteps, criticalPatterns, outputValidation, tokenRange
}
```

### Step 2: Retrieve Pre-Execution Context

**Action**: Load stored context from pre-execution phase.

```javascript
context = session.contexts[workflowId]
// Contains: userInput, checkpoints, expected, featureRequirements, etc.
```

### Step 3: Verify Workflow Adherence

**Check that entity followed its documented workflow steps.**

#### For Skills

```javascript
workflowCheck = {
  expectedWorkflows: definition.workflows,
  actualExecution: analyzeOutput(entityOutput),
  stepsFollowed: 0,
  stepsExpected: definition.workflows.length,
  deviations: []
}

// Example for Feature Orchestration Skill
expectedWorkflows = [
  "Assess complexity (Simple vs Complex)",
  "Discover templates via query_templates",
  "Create feature directly (Simple) OR Launch Feature Architect (Complex)",
  "Return feature ID and next action"
]

// Verify each workflow step
for step in expectedWorkflows:
  if (evidenceOfStep(entityOutput, step)) {
    workflowCheck.stepsFollowed++
  } else {
    workflowCheck.deviations.push({
      step: step,
      issue: "No evidence of this step in output",
      severity: "WARN"
    })
  }
```

#### For Subagents

```javascript
stepCheck = {
  expectedSteps: definition.expectedSteps,  // e.g., 8 steps for Feature Architect
  actualSteps: extractSteps(entityOutput),
  stepsFollowed: 0,
  stepsExpected: definition.expectedSteps.length,
  deviations: []
}

// Example for Feature Architect (8 expected steps)
expectedSteps = [
  "Step 1: get_overview + list_tags",
  "Step 2: Detect input type (PRD/Interactive/Quick)",
  "Step 4: query_templates",
  "Step 5: Tag strategy (reuse existing tags)",
  "Step 5.5: Check agent-mapping.yaml",
  "Step 6: Create feature",
  "Step 7: Add custom sections (if Detailed/PRD)",
  "Step 8: Return minimal handoff"
]

// Verify tool usage as evidence of steps
for step in expectedSteps:
  if (evidenceOfStep(entityOutput, step)) {
    stepCheck.stepsFollowed++
  } else {
    stepCheck.deviations.push({
      step: step,
      issue: "Step not completed or no evidence",
      severity: determineStepSeverity(step)
    })
  }
```

**Evidence Detection**:
```javascript
function evidenceOfStep(output, step) {
  // Check for tool calls mentioned
  if (step.includes("query_templates") && mentions(output, "template")) return true
  if (step.includes("list_tags") && mentions(output, "tags")) return true

  // Check for workflow markers
  if (step.includes("Create feature") && mentions(output, "feature created")) return true

  // Check for explicit mentions
  if (contains(output, step.toLowerCase())) return true

  return false
}
```

### Step 4: Validate Critical Patterns

**Check entity followed critical patterns from its definition.**

```javascript
patternCheck = {
  criticalPatterns: definition.criticalPatterns,
  violations: []
}

// Example for Feature Architect
criticalPatterns = [
  "description = forward-looking (what needs to be built)",
  "Do NOT populate summary field during creation",
  "Return minimal handoff (50-100 tokens)",
  "PRD mode: Extract ALL sections from document",
  "Tag strategy: Reuse existing tags",
  "Check agent-mapping.yaml for new tags"
]

for pattern in criticalPatterns:
  violation = checkPattern(pattern, entityOutput, context)
  if (violation) {
    patternCheck.violations.push(violation)
  }
}
```

**Pattern Checking Examples**:

```javascript
// Pattern: "Do NOT populate summary field"
function checkSummaryField(output, entityId) {
  entity = query_container(operation="get", containerType="feature", id=entityId)

  if (entity.summary && entity.summary.length > 0) {
    return {
      pattern: "Do NOT populate summary field during creation",
      violation: "Summary field populated",
      severity: "WARN",
      found: `Summary: "${entity.summary}" (${entity.summary.length} chars)`,
      expected: "Summary should be empty until completion"
    }
  }
  return null
}

// Pattern: "Return minimal handoff (50-100 tokens)"
function checkHandoffSize(output) {
  tokenCount = estimateTokens(output)

  if (tokenCount > 200) {
    return {
      pattern: "Return minimal handoff (50-100 tokens)",
      violation: "Verbose handoff",
      severity: "WARN",
      found: `${tokenCount} tokens`,
      expected: "50-100 tokens (brief summary)",
      suggestion: "Detailed work should go in feature sections, not response"
    }
  }
  return null
}

// Pattern: "PRD mode: Extract ALL sections"
function checkPRDExtraction(output, context) {
  if (context.userInput.inputType != "PRD") return null

  // Compare PRD sections vs feature sections
  prdSections = context.prdSections  // Captured in pre-execution
  feature = query_container(operation="get", containerType="feature", id=entityId, includeSections=true)
  featureSections = feature.sections

  missingSections = []
  for prdSection in prdSections:
    if (!hasMatchingSection(featureSections, prdSection)) {
      missingSections.push(prdSection)
    }
  }

  if (missingSections.length > 0) {
    return {
      pattern: "PRD mode: Extract ALL sections from document",
      violation: "PRD sections incomplete",
      severity: "ALERT",
      found: `Feature has ${featureSections.length} sections`,
      expected: `PRD has ${prdSections.length} sections`,
      missing: missingSections,
      suggestion: "Add missing sections to feature"
    }
  }
  return null
}
```

### Step 5: Verify Expected Outputs

**Check entity produced expected outputs from its definition.**

```javascript
outputCheck = {
  expectedOutputs: definition.outputValidation || definition.expectedOutputs,
  actualOutputs: analyzeOutputs(entityOutput, entityId),
  present: [],
  missing: []
}

// Example for Planning Specialist
expectedOutputs = [
  "Tasks created with descriptions?",
  "Domain isolation preserved?",
  "Dependencies mapped correctly?",
  "Documentation task included (if user-facing)?",
  "Testing task included (if needed)?",
  "No circular dependencies?",
  "Templates applied to tasks?"
]

for expectedOutput in expectedOutputs:
  if (verifyOutput(expectedOutput, entityId, context)) {
    outputCheck.present.push(expectedOutput)
  } else {
    outputCheck.missing.push({
      output: expectedOutput,
      severity: determineSeverity(expectedOutput),
      impact: describeImpact(expectedOutput)
    })
  }
}
```

### Step 6: Validate Against Checkpoints

**Compare execution against checkpoints set in pre-execution.**

```javascript
checkpointResults = {
  total: context.checkpoints.length,
  passed: 0,
  failed: []
}

for checkpoint in context.checkpoints:
  result = verifyCheckpoint(checkpoint, entityOutput, entityId, context)

  if (result.passed) {
    checkpointResults.passed++
  } else {
    checkpointResults.failed.push({
      checkpoint: checkpoint,
      reason: result.reason,
      severity: result.severity
    })
  }
}
```

**Checkpoint Verification Examples**:

```javascript
// Checkpoint: "Verify templates discovered via query_templates"
function verifyTemplatesDiscovered(output) {
  if (mentions(output, "template") || mentions(output, "query_templates")) {
    return { passed: true }
  }
  return {
    passed: false,
    reason: "No evidence of template discovery (query_templates not called)",
    severity: "WARN"
  }
}

// Checkpoint: "Verify domain isolation (one task = one specialist)"
function verifyDomainIsolation(featureId) {
  tasks = query_container(operation="overview", containerType="feature", id=featureId).tasks

  violations = []
  for task in tasks:
    domains = detectDomains(task.title + " " + task.description)
    if (domains.length > 1) {
      violations.push({
        task: task.title,
        domains: domains,
        issue: "Task spans multiple specialist domains"
      })
    }
  }

  if (violations.length > 0) {
    return {
      passed: false,
      reason: `${violations.length} cross-domain tasks detected`,
      severity: "ALERT",
      details: violations
    }
  }

  return { passed: true }
}
```

### Step 7: Check Token Range

**Verify entity stayed within expected token range.**

```javascript
tokenCheck = {
  actual: estimateTokens(entityOutput),
  expected: definition.tokenRange,
  withinRange: false,
  deviation: 0
}

tokenCheck.withinRange = (
  tokenCheck.actual >= tokenCheck.expected[0] &&
  tokenCheck.actual <= tokenCheck.expected[1]
)

if (!tokenCheck.withinRange) {
  tokenCheck.deviation = tokenCheck.actual > tokenCheck.expected[1]
    ? tokenCheck.actual - tokenCheck.expected[1]
    : tokenCheck.expected[0] - tokenCheck.actual

  if (tokenCheck.deviation > tokenCheck.expected[1] * 0.5) {
    // More than 50% over expected range
    severity = "WARN"
  } else {
    severity = "INFO"
  }
}
```

### Step 8: Compare Against Original User Input

**For Subagents: Verify original user requirements preserved.**

```javascript
if (category == "SUBAGENT") {
  requirementsCheck = compareToOriginal(
    userInput: context.userInput,
    output: entityOutput,
    entityId: entityId
  )
}
```

**Comparison Logic**:

```javascript
function compareToOriginal(userInput, output, entityId) {
  // For Feature Architect: Check core concepts preserved
  if (entityType == "feature-architect") {
    feature = query_container(operation="get", containerType="feature", id=entityId, includeSections=true)

    originalConcepts = extractConcepts(userInput.fullText)
    featureConcepts = extractConcepts(feature.description + " " + sectionsToText(feature.sections))

    missingConcepts = originalConcepts.filter(c => !featureConcepts.includes(c))

    if (missingConcepts.length > 0) {
      return {
        preserved: false,
        severity: "ALERT",
        missing: missingConcepts,
        suggestion: "Add missing concepts to feature description or sections"
      }
    }
  }

  // For Planning Specialist: Check all feature requirements covered
  if (entityType == "planning-specialist") {
    requirements = extractRequirements(context.featureRequirements.description)
    tasks = query_container(operation="overview", containerType="feature", id=featureId).tasks

    uncoveredRequirements = []
    for req in requirements:
      if (!anyTaskCovers(tasks, req)) {
        uncoveredRequirements.push(req)
      }
    }

    if (uncoveredRequirements.length > 0) {
      return {
        preserved: false,
        severity: "WARN",
        uncovered: uncoveredRequirements,
        suggestion: "Create additional tasks to cover all requirements"
      }
    }
  }

  return { preserved: true }
}
```

### Step 9: Determine Specialized Analysis Needed

**Based on entity type, decide which specialized analysis to run.**

```javascript
specializedAnalyses = []

// Planning Specialist → Graph + Tag analysis
if (entityType == "planning-specialist") {
  specializedAnalyses.push("graph-quality")
  specializedAnalyses.push("tag-quality")
}

// All entities → Routing validation
specializedAnalyses.push("routing-validation")

// If efficiency analysis enabled
if (params.enableEfficiencyAnalysis) {
  specializedAnalyses.push("token-optimization")
  specializedAnalyses.push("tool-selection")
  specializedAnalyses.push("parallel-detection")
}

// Load and run each specialized analysis
for analysis in specializedAnalyses:
  Read `.claude/skills/orchestration-qa/${analysis}.md`
  runAnalysis(analysis, entityType, entityOutput, entityId, context)
}
```

### Step 10: Aggregate Results

**Combine all validation results.**

```javascript
results = {
  entity: entityType,
  category: category,

  workflowAdherence: `${workflowCheck.stepsFollowed}/${workflowCheck.stepsExpected} steps (${percentage}%)`,
  expectedOutputs: `${outputCheck.present.length}/${outputCheck.expectedOutputs.length} present`,
  checkpoints: `${checkpointResults.passed}/${checkpointResults.total} passed`,

  criticalPatternViolations: patternCheck.violations.filter(v => v.severity == "ALERT"),
  processIssues: patternCheck.violations.filter(v => v.severity == "WARN"),

  tokenUsage: {
    actual: tokenCheck.actual,
    expected: tokenCheck.expected,
    withinRange: tokenCheck.withinRange,
    deviation: tokenCheck.deviation
  },

  requirementsPreserved: requirementsCheck?.preserved ?? true,

  deviations: aggregateDeviations(
    workflowCheck.deviations,
    patternCheck.violations,
    outputCheck.missing,
    checkpointResults.failed
  ),

  specializedAnalyses: specializedAnalysisResults
}
```

### Step 11: Categorize Deviations by Severity

```javascript
deviationsSummary = {
  ALERT: results.deviations.filter(d => d.severity == "ALERT"),
  WARN: results.deviations.filter(d => d.severity == "WARN"),
  INFO: results.deviations.filter(d => d.severity == "INFO")
}
```

**Severity Determination**:

- **ALERT**: Critical violations that affect functionality or correctness
  - Missing requirements from user input
  - Cross-domain tasks (violates domain isolation)
  - Status change without Status Progression Skill
  - Circular dependencies
  - PRD sections not extracted

- **WARN**: Process issues that should be addressed
  - Workflow steps skipped (non-critical)
  - Output too verbose
  - Templates not applied when available
  - Tags don't follow conventions
  - No Files Changed section

- **INFO**: Observations and opportunities
  - Token usage outside expected range (but reasonable)
  - Efficiency opportunities identified
  - Quality patterns observed

### Step 12: Return Results

If deviations found, prepare for reporting:

```javascript
if (deviationsSummary.ALERT.length > 0 || deviationsSummary.WARN.length > 0) {
  // Read deviation-templates.md for formatting
  Read `.claude/skills/orchestration-qa/deviation-templates.md`

  // Format report based on severity
  report = formatDeviationReport(results, deviationsSummary)

  // Add to TodoWrite
  addToTodoWrite(deviationsSummary)

  // Return report
  return report
}
```

If no issues:

```javascript
return {
  success: true,
  message: `✅ QA Review: ${entityType} - All checks passed`,
  workflowAdherence: results.workflowAdherence,
  summary: "No deviations detected"
}
```

## Entity-Specific Notes

### Skills Review

- Focus on workflow steps and tool usage
- Verify token efficiency (Skills should be lightweight)
- Check for proper error handling

### Subagents Review

- Focus on step-by-step process adherence
- Verify critical patterns followed
- Compare output vs original user input (requirement preservation)
- Check output brevity (specialists should return minimal summaries)

### Status Progression Skill (Critical)

**Special validation** - this is the most critical Skill to validate:

```javascript
if (entityType == "status-progression") {
  // CRITICAL: Was it actually used?
  if (statusChangedWithoutSkill) {
    return {
      severity: "CRITICAL",
      violation: "Status change bypassed mandatory Status Progression Skill",
      impact: "Prerequisite validation may have been skipped",
      action: "IMMEDIATE ALERT to user"
    }
  }

  // Verify it read config.yaml
  if (!mentions(output, "config")) {
    deviations.push({
      severity: "WARN",
      issue: "Status Progression Skill didn't mention config",
      expected: "Should read config.yaml for workflow validation"
    })
  }

  // Verify it validated prerequisites
  if (validationFailed && !mentions(output, "prerequisite" or "blocker")) {
    deviations.push({
      severity: "WARN",
      issue: "Validation failure without detailed prerequisites",
      expected: "Should explain what prerequisites are blocking"
    })
  }
}
```

## Output Structure

```javascript
{
  entity: "planning-specialist",
  category: "SUBAGENT",
  workflowAdherence: "8/8 steps (100%)",
  expectedOutputs: "7/7 present",
  checkpoints: "10/10 passed",
  tokenUsage: {
    actual: 1950,
    expected: [1800, 2200],
    withinRange: true
  },
  deviations: [],
  specializedAnalyses: {
    graphQuality: { score: 95, issues: [] },
    tagQuality: { score: 100, issues: [] }
  },
  success: true,
  message: "✅ All quality checks passed"
}
```
