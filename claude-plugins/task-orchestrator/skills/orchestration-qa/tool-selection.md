# Tool Selection Efficiency

**Purpose**: Verify optimal tool selection for the task at hand.

**When**: Optional post-execution (controlled by enableEfficiencyAnalysis parameter)

**Token Cost**: ~300-500 tokens

## Optimal Tool Selection Patterns

### Pattern 1: query_container Overview vs Get

**Optimal**: Use `operation="overview"` for hierarchical views without section content

**Suboptimal**: Use `operation="get"` with `includeSections=true` when only need metadata + child list

**Detection**:
```javascript
if (usedGet && includeSections && !needsFullSections) {
  return {
    pattern: "Used get with sections when overview would suffice",
    current: "query_container(operation='get', includeSections=true)",
    optimal: "query_container(operation='overview', id='...')",
    savings: "85-93% tokens",
    when: "Need: feature metadata + task list (no section content)"
  }
}
```

### Pattern 2: Search vs Filtered Query

**Optimal**: Use `query_container` with filters for known criteria

**Suboptimal**: Use `operation="search"` when exact filters would work

**Detection**:
```javascript
if (usedSearch && hasExactCriteria) {
  return {
    pattern: "Used search when filtered query more efficient",
    current: "query_container(operation='search', query='pending tasks')",
    optimal: "query_container(operation='search', status='pending')",
    savings: "Query filters are faster and more precise"
  }
}
```

### Pattern 3: Bulk Operations vs Multiple Singles

**Optimal**: Use `operation="bulkUpdate"` for multiple updates

**Suboptimal**: Loop calling `update` multiple times

**Detection**:
```javascript
updateCount = countToolCalls(output, "manage_container", "update")
if (updateCount >= 3) {
  return {
    pattern: "Multiple update calls instead of bulkUpdate",
    current: `${updateCount} separate update calls`,
    optimal: "1 bulkUpdate call",
    savings: `${updateCount - 1} round trips eliminated`
  }
}
```

### Pattern 4: Scoped Overview vs Multiple Gets

**Optimal**: Single scoped overview for hierarchical view

**Suboptimal**: Multiple get calls for related entities

**Detection**:
```javascript
if (getCallCount >= 2 && queriedRelatedEntities) {
  return {
    pattern: "Multiple gets for related entities",
    current: `${getCallCount} get calls`,
    optimal: "1 scoped overview (returns entity + children)",
    savings: `${getCallCount - 1} tool calls eliminated`
  }
}
```

### Pattern 5: recommend_agent vs Manual Routing

**Optimal**: Use `recommend_agent` for specialist routing

**Suboptimal**: Manual tag analysis and routing logic

**Detection**:
```javascript
if (taskOrchestration && !usedRecommendAgent && launchedSpecialists) {
  return {
    pattern: "Manual specialist routing instead of recommend_agent",
    current: "Manual tag → specialist mapping",
    optimal: "recommend_agent(taskId) → automatic routing",
    benefit: "Centralized routing logic, consistent with agent-mapping.yaml"
  }
}
```

## Analysis Workflow

```javascript
toolSelectionIssues = []

// Check each pattern
checkOverviewVsGet()
checkSearchVsFiltered()
checkBulkOpsVsMultiple()
checkScopedOverviewVsGets()
checkRecommendAgentUsage()

// Generate report if issues found
if (toolSelectionIssues.length > 0) {
  return {
    issuesFound: toolSelectionIssues.length,
    issues: toolSelectionIssues,
    recommendations: prioritizeRecommendations(toolSelectionIssues)
  }
}
```

## Report Template

```markdown
## 🔧 Tool Selection Efficiency

**Suboptimal Patterns**: [count]

### Issues Detected

**ℹ️ INFO**: Use overview instead of get
- Current: `query_container(operation='get', includeSections=true)`
- Optimal: `query_container(operation='overview', id='...')`
- Savings: 85-93% tokens

**ℹ️ INFO**: Use bulkUpdate instead of multiple updates
- Current: [X] separate update calls
- Optimal: 1 bulkUpdate call
- Savings: [X-1] round trips

### Recommendations
1. [Most impactful change]
2. [Secondary optimization]
```

## When to Report

- **Only if** enableEfficiencyAnalysis=true
- **INFO** level (observations, not violations)
- Include in efficiency analysis section
