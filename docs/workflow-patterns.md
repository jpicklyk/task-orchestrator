# Workflow Patterns and Deployment Automation

## Table of Contents

- [Introduction](#introduction)
- [Three Workflow Patterns](#three-workflow-patterns)
  - [Pattern 1: Vibe Coder (Quick Iteration)](#pattern-1-vibe-coder-quick-iteration)
  - [Pattern 2: Solo Dev (Full Cycle)](#pattern-2-solo-dev-full-cycle)
  - [Pattern 3: Small Team (Review Gates)](#pattern-3-small-team-review-gates)
- [Status Progression Examples](#status-progression-examples)
- [Deployment Integration with Hooks](#deployment-integration-with-hooks)
  - [Environment Tags Convention](#environment-tags-convention)
  - [Hook Example 1: Auto-Deploy to Staging](#hook-example-1-auto-deploy-to-staging)
  - [Hook Example 2: Production Deployment Gate](#hook-example-2-production-deployment-gate)
  - [Hook Example 3: QA Signoff Workflow](#hook-example-3-qa-signoff-workflow)
  - [Hook Example 4: Deployment Metrics Tracking](#hook-example-4-deployment-metrics-tracking)
- [Complete Workflow Examples](#complete-workflow-examples)
- [Best Practices](#best-practices)

---

## Introduction

Task Orchestrator supports flexible workflow patterns to match different team sizes and development styles. This guide describes three common patterns and shows how to integrate deployment automation using Claude Code Hooks.

### Key Concepts

**Status Values**: Tasks can have these statuses:
- Core: `pending`, `in-progress`, `completed`
- Optional: `cancelled`, `deferred`
- Custom: Any additional statuses your workflow needs (e.g., `deployed`, `in-review`, `ready-for-qa`)

**Environment Tags**: Use tags to specify deployment environments:
- `staging` - Staging/test environment
- `production` - Production environment
- `dev` - Development environment
- `qa` - QA environment

**Hooks**: Claude Code hooks enable automated deployment based on status changes and tags.

---

## Three Workflow Patterns

### Pattern 1: Vibe Coder (Quick Iteration)

**Best for**: Solo developers, rapid prototyping, personal projects

**Philosophy**: Minimal overhead, fast feedback loops, focus on shipping

**Status Flow**:
```
pending → in-progress → completed
```

**Characteristics**:
- Three statuses only
- No review gates
- No deployment stages
- Immediate deployment on completion
- Fast iteration cycles

**When to Use**:
- Solo developer working alone
- Prototyping new ideas
- Personal projects
- Hackathons or time-constrained development

**Example Task Lifecycle**:

```bash
# Create task
manage_container(
  operation="create",
  containerType="task",
  title="Add user login endpoint",
  status="pending",
  priority="high",
  complexity=5,
  tags="backend,api,auth"
)

# Start work
manage_container(
  operation="setStatus",
  containerType="task",
  id="task-uuid",
  status="in-progress"
)

# Complete and done
manage_container(
  operation="setStatus",
  containerType="task",
  id="task-uuid",
  status="completed"
)
```

**Optional Hook**: Auto-commit on completion

```bash
# .claude/hooks/vibe-coder-commit.sh
#!/bin/bash
# Auto-commit when tasks complete (vibe coder workflow)

INPUT=$(cat)
STATUS=$(echo "$INPUT" | jq -r '.tool_input.status // empty')

if [ "$STATUS" != "completed" ]; then
  exit 0
fi

TASK_ID=$(echo "$INPUT" | jq -r '.tool_input.id // empty')
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
TASK_TITLE=$(sqlite3 "$DB_PATH" \
  "SELECT title FROM Tasks WHERE id=x'$(echo "$TASK_ID" | tr -d '-')'" 2>/dev/null || echo "")

cd "$CLAUDE_PROJECT_DIR"

if git diff --quiet && git diff --cached --quiet; then
  exit 0
fi

git add -A
git commit -m "feat: $TASK_TITLE

Task-ID: $TASK_ID
Workflow: Vibe Coder (quick iteration)"

echo "✓ Auto-committed: $TASK_TITLE"
exit 0
```

---

### Pattern 2: Solo Dev (Full Cycle)

**Best for**: Solo developers who want production-quality processes

**Philosophy**: Quality gates, testing, and deployment stages without team overhead

**Status Flow**:
```
pending → in-progress → testing → deployed:staging → deployed:production → completed
```

**Characteristics**:
- Testing stage before deployment
- Staging environment validation
- Production deployment as final step
- Environment tags for deployment tracking
- Automated quality checks

**When to Use**:
- Solo developer building production applications
- SaaS products with real users
- Projects requiring deployment stages
- Quality-conscious development

**Example Task Lifecycle**:

```bash
# 1. Create task
manage_container(
  operation="create",
  containerType="task",
  title="Implement password reset flow",
  status="pending",
  priority="high",
  complexity=7,
  tags="backend,auth,security"
)

# 2. Start implementation
manage_container(
  operation="setStatus",
  containerType="task",
  id="task-uuid",
  status="in-progress"
)

# 3. Implementation complete, start testing
manage_container(
  operation="setStatus",
  containerType="task",
  id="task-uuid",
  status="testing"
)

# 4. Tests pass, deploy to staging
manage_container(
  operation="update",
  containerType="task",
  id="task-uuid",
  status="deployed",
  tags="backend,auth,security,staging"  # Add staging tag
)

# 5. Staging validated, deploy to production
manage_container(
  operation="update",
  containerType="task",
  id="task-uuid",
  status="deployed",
  tags="backend,auth,security,production"  # Change to production tag
)

# 6. Production verified, mark complete
manage_container(
  operation="setStatus",
  containerType="task",
  id="task-uuid",
  status="completed"
)
```

**Hook Integration**: See [Hook Example 1](#hook-example-1-auto-deploy-to-staging) for staging deployment automation.

---

### Pattern 3: Small Team (Review Gates)

**Best for**: Small teams (2-10 people) with code review and QA processes

**Philosophy**: Collaboration, review gates, QA validation, controlled deployment

**Status Flow**:
```
pending → in-progress → in-review → testing → ready-for-qa →
validated → deployed:staging → deployed:production → completed
```

**Characteristics**:
- Code review gate (`in-review`)
- Testing phase separate from implementation
- QA validation stage (`ready-for-qa`, `validated`)
- Deployment stages with team signoff
- Notification hooks for team coordination

**When to Use**:
- Small development teams
- Projects requiring code review
- Applications with dedicated QA
- Regulated industries requiring approval trails

**Example Task Lifecycle**:

```bash
# 1. Create task
manage_container(
  operation="create",
  containerType="task",
  title="Add payment processing integration",
  status="pending",
  priority="high",
  complexity=9,
  tags="backend,payments,stripe,critical"
)

# 2. Developer starts work
manage_container(
  operation="setStatus",
  containerType="task",
  id="task-uuid",
  status="in-progress"
)

# 3. Implementation done, submit for review
manage_container(
  operation="setStatus",
  containerType="task",
  id="task-uuid",
  status="in-review"
)
# Hook sends notification to team: "Task ready for review"

# 4. Review approved, move to testing
manage_container(
  operation="setStatus",
  containerType="task",
  id="task-uuid",
  status="testing"
)

# 5. Tests pass, ready for QA
manage_container(
  operation="setStatus",
  containerType="task",
  id="task-uuid",
  status="ready-for-qa"
)
# Hook sends notification to QA: "Task ready for testing"

# 6. QA validated
manage_container(
  operation="setStatus",
  containerType="task",
  id="task-uuid",
  status="validated"
)

# 7. Deploy to staging
manage_container(
  operation="update",
  containerType="task",
  id="task-uuid",
  status="deployed",
  tags="backend,payments,stripe,critical,staging"
)
# Hook triggers staging deployment script

# 8. Staging verified, deploy to production (requires approval)
manage_container(
  operation="update",
  containerType="task",
  id="task-uuid",
  status="deployed",
  tags="backend,payments,stripe,critical,production"
)
# Hook requires QA signoff before deploying

# 9. Production verified, mark complete
manage_container(
  operation="setStatus",
  containerType="task",
  id="task-uuid",
  status="completed"
)
# Hook sends team notification: "Task completed in production"
```

**Hook Integration**: See [Hook Example 2](#hook-example-2-production-deployment-gate) and [Hook Example 3](#hook-example-3-qa-signoff-workflow).

---

## Status Progression Examples

### Vibe Coder Statuses

```
pending       → in-progress  → completed
(backlog)       (active)       (done)
```

### Solo Dev Statuses

```
pending → in-progress → testing → deployed → completed
          (coding)      (tests)   (staging   (verified
                                   →prod)     in prod)
```

### Small Team Statuses

```
pending → in-progress → in-review → testing → ready-for-qa → validated
          (dev work)    (code       (unit      (QA testing)   (QA
                        review)     tests)                    approved)
                                              ↓
                                         deployed:staging → deployed:production → completed
                                         (staging env)      (prod env)           (done)
```

### Custom Status Examples

You can define any statuses your workflow needs:

```bash
# DevOps workflow
"pending" → "in-progress" → "needs-config" → "deployed:dev" →
"deployed:staging" → "deployed:production" → "completed"

# Bug fix workflow
"reported" → "triaged" → "in-progress" → "fixed" →
"deployed:staging" → "verified" → "deployed:production" → "closed"

# Documentation workflow
"draft" → "in-review" → "approved" → "published" → "completed"
```

---

## Deployment Integration with Hooks

### Environment Tags Convention

**Recommended tagging pattern** for deployment tracking:

```
tags="domain-tag,other-tags,ENVIRONMENT"
```

**Environment tag values**:
- `dev` - Development environment
- `staging` - Staging/test environment
- `qa` - QA environment
- `production` - Production environment

**Examples**:

```bash
# Backend task deployed to staging
tags="backend,api,auth,staging"

# Frontend task deployed to production
tags="frontend,ui,dashboard,production"

# Database migration in QA
tags="database,migration,schema,qa"
```

**Why environment tags?**
- Hooks can filter by environment
- Query deployed tasks per environment
- Track deployment history
- Prevent accidental production deploys

---

### Hook Example 1: Auto-Deploy to Staging

**Purpose**: Automatically deploy to staging environment when task status changes to `deployed` with `staging` tag.

**Triggers**: `mcp__task-orchestrator__manage_container` (operation=update, status=deployed, tags contains "staging")

**File**: `.claude/hooks/auto-deploy-staging.sh`

```bash
#!/bin/bash
# Auto-deploy to staging when task marked deployed with staging tag
#
# Hook Event: PostToolUse
# Matcher: mcp__task-orchestrator__manage_container
# Trigger: status=deployed AND tags contains "staging"

set -euo pipefail

# Read JSON input
INPUT=$(cat)

# Extract fields
OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation // empty')
STATUS=$(echo "$INPUT" | jq -r '.tool_input.status // empty')
TAGS=$(echo "$INPUT" | jq -r '.tool_input.tags // empty')
TASK_ID=$(echo "$INPUT" | jq -r '.tool_input.id // empty')

# Only proceed if operation is update or setStatus
if [[ "$OPERATION" != "update" && "$OPERATION" != "setStatus" ]]; then
  exit 0
fi

# Only proceed if status is deployed
if [ "$STATUS" != "deployed" ]; then
  exit 0
fi

# Only proceed if staging tag present
if [[ ! "$TAGS" =~ staging ]]; then
  exit 0
fi

# Get task details from database
DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
if [ ! -f "$DB_PATH" ]; then
  echo "⚠️  Database not found, skipping deployment"
  exit 0
fi

TASK_TITLE=$(sqlite3 "$DB_PATH" \
  "SELECT title FROM Tasks WHERE id=x'$(echo "$TASK_ID" | tr -d '-')'" 2>/dev/null || echo "")

if [ -z "$TASK_TITLE" ]; then
  echo "⚠️  Could not retrieve task title"
  exit 0
fi

echo "=========================================="
echo "Staging Deployment Triggered"
echo "=========================================="
echo "Task: $TASK_TITLE"
echo "Task ID: $TASK_ID"
echo "Environment: STAGING"
echo ""

cd "$CLAUDE_PROJECT_DIR"

# Check for deployment script
DEPLOY_SCRIPT="./deploy-staging.sh"
if [ ! -f "$DEPLOY_SCRIPT" ]; then
  echo "⚠️  Deployment script not found: $DEPLOY_SCRIPT"
  echo "   Create this script to enable automated staging deployment"
  exit 0
fi

# Run deployment script
echo "Running deployment script..."
if bash "$DEPLOY_SCRIPT" 2>&1; then
  echo ""
  echo "✓ Successfully deployed to STAGING"
  echo "  Task: $TASK_TITLE"
  echo "  Environment: staging"

  # Log deployment
  LOG_DIR="$CLAUDE_PROJECT_DIR/.claude/logs"
  mkdir -p "$LOG_DIR"
  echo "[$(date -u +"%Y-%m-%dT%H:%M:%SZ")] STAGING deployment: $TASK_TITLE (Task ID: $TASK_ID)" >> "$LOG_DIR/deployments.log"
else
  echo ""
  echo "⚠️  Staging deployment failed"
  echo "   Check deployment script logs for details"
fi

echo "=========================================="
exit 0
```

**Configuration** (add to `.claude/settings.local.json`):

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__manage_container",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/auto-deploy-staging.sh",
            "timeout": 120
          }
        ]
      }
    ]
  }
}
```

**Example Deployment Script** (`deploy-staging.sh`):

```bash
#!/bin/bash
# Example staging deployment script
set -e

echo "Building application..."
./gradlew build

echo "Running tests..."
./gradlew test

echo "Deploying to staging server..."
# Replace with your deployment method:
# - Docker: docker-compose -f docker-compose.staging.yml up -d
# - SSH: scp build/libs/*.jar user@staging-server:/app/
# - Cloud: gcloud app deploy --project=myapp-staging
# - Kubernetes: kubectl apply -f k8s/staging/

echo "Deployment complete!"
```

---

### Hook Example 2: Production Deployment Gate

**Purpose**: Require QA approval before allowing production deployment. Blocks deployment if QA hasn't signed off.

**Triggers**: `mcp__task-orchestrator__manage_container` (operation=update, tags changing from "staging" to "production")

**File**: `.claude/hooks/production-gate.sh`

```bash
#!/bin/bash
# Production deployment gate - requires QA signoff
#
# Hook Event: PostToolUse
# Matcher: mcp__task-orchestrator__manage_container
# Trigger: tags changing to include "production"
# Blocking: YES (exit 2 if validation fails)

set -euo pipefail

INPUT=$(cat)

OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation // empty')
TAGS=$(echo "$INPUT" | jq -r '.tool_input.tags // empty')
STATUS=$(echo "$INPUT" | jq -r '.tool_input.status // empty')
TASK_ID=$(echo "$INPUT" | jq -r '.tool_input.id // empty')

# Only check when updating tags to include production
if [[ "$OPERATION" != "update" ]]; then
  exit 0
fi

if [[ ! "$TAGS" =~ production ]]; then
  exit 0
fi

echo "=========================================="
echo "Production Deployment Gate"
echo "=========================================="
echo "Task ID: $TASK_ID"
echo "Checking QA approval..."
echo ""

DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"

# Get task details
TASK_DETAILS=$(sqlite3 "$DB_PATH" -json \
  "SELECT title, status FROM Tasks WHERE id=x'$(echo "$TASK_ID" | tr -d '-')'" 2>/dev/null || echo "[]")

TASK_TITLE=$(echo "$TASK_DETAILS" | jq -r '.[0].title // "Unknown"')
TASK_STATUS=$(echo "$TASK_DETAILS" | jq -r '.[0].status // "unknown"')

echo "Task: $TASK_TITLE"
echo "Status: $TASK_STATUS"
echo ""

# Check if QA approval file exists
QA_APPROVAL_FILE="$CLAUDE_PROJECT_DIR/.claude/qa-approvals/$TASK_ID.approved"

if [ ! -f "$QA_APPROVAL_FILE" ]; then
  echo "❌ PRODUCTION DEPLOYMENT BLOCKED"
  echo ""
  echo "Reason: QA approval required for production deployment"
  echo ""
  echo "To approve this deployment:"
  echo "  1. QA team tests the task in staging environment"
  echo "  2. QA creates approval file:"
  echo "     mkdir -p .claude/qa-approvals"
  echo "     echo \"Approved by: [QA Name]\" > .claude/qa-approvals/$TASK_ID.approved"
  echo "     echo \"Date: \$(date -u)\" >> .claude/qa-approvals/$TASK_ID.approved"
  echo "  3. Retry production deployment"
  echo ""

  cat << EOF
{
  "decision": "block",
  "reason": "Production deployment blocked - QA approval required.\n\nTask: $TASK_TITLE\n\nThis task has not been approved for production deployment by QA.\n\nRequired action:\n1. QA team validates task in staging environment\n2. QA creates approval file: .claude/qa-approvals/$TASK_ID.approved\n3. Retry production deployment\n\nFor more information, see docs/workflow-patterns.md"
}
EOF
  exit 2
fi

# QA approval exists - verify it
QA_APPROVER=$(grep "Approved by:" "$QA_APPROVAL_FILE" 2>/dev/null || echo "Unknown")
QA_DATE=$(grep "Date:" "$QA_APPROVAL_FILE" 2>/dev/null || echo "Unknown")

echo "✓ QA Approval Found"
echo "  $QA_APPROVER"
echo "  $QA_DATE"
echo ""
echo "Production deployment allowed to proceed"
echo "=========================================="

# Log approval
LOG_DIR="$CLAUDE_PROJECT_DIR/.claude/logs"
mkdir -p "$LOG_DIR"
echo "[$(date -u +"%Y-%m-%dT%H:%M:%SZ")] PRODUCTION gate passed: $TASK_TITLE (Task ID: $TASK_ID) - $QA_APPROVER" >> "$LOG_DIR/production-gates.log"

exit 0
```

**Configuration**:

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__manage_container",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/production-gate.sh",
            "timeout": 30
          }
        ]
      }
    ]
  }
}
```

**QA Approval Workflow**:

```bash
# 1. Task deployed to staging
manage_container(operation="update", containerType="task", id="task-uuid",
                status="deployed", tags="backend,api,staging")

# 2. QA tests in staging environment
# 3. QA approves for production
mkdir -p .claude/qa-approvals
cat > .claude/qa-approvals/550e8400-e29b-41d4-a716-446655440000.approved << EOF
Approved by: Jane Smith (QA Lead)
Date: 2025-10-22T14:30:00Z
Staging URL: https://staging.example.com/api/v1
Test Results: All acceptance criteria met
Notes: Tested on Chrome, Firefox, Safari. Performance acceptable.
EOF

# 4. Attempt production deployment (now allowed)
manage_container(operation="update", containerType="task", id="task-uuid",
                status="deployed", tags="backend,api,production")
# ✓ Production gate passes, deployment proceeds
```

---

### Hook Example 3: QA Signoff Workflow

**Purpose**: Notify QA team when tasks are ready for testing and log QA signoffs.

**Triggers**: `mcp__task-orchestrator__manage_container` (status=ready-for-qa or status=validated)

**File**: `.claude/hooks/qa-workflow.sh`

```bash
#!/bin/bash
# QA workflow automation
#
# Hook Event: PostToolUse
# Matcher: mcp__task-orchestrator__manage_container
# Actions:
#   - status=ready-for-qa: Notify QA team
#   - status=validated: Log QA signoff

set -euo pipefail

INPUT=$(cat)

OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation // empty')
STATUS=$(echo "$INPUT" | jq -r '.tool_input.status // empty')
TASK_ID=$(echo "$INPUT" | jq -r '.tool_input.id // empty')

# Only proceed on setStatus or update operations
if [[ "$OPERATION" != "setStatus" && "$OPERATION" != "update" ]]; then
  exit 0
fi

DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"
TASK_TITLE=$(sqlite3 "$DB_PATH" \
  "SELECT title FROM Tasks WHERE id=x'$(echo "$TASK_ID" | tr -d '-')'" 2>/dev/null || echo "")

if [ -z "$TASK_TITLE" ]; then
  exit 0
fi

# Handle ready-for-qa status
if [ "$STATUS" = "ready-for-qa" ]; then
  echo "=========================================="
  echo "QA Notification"
  echo "=========================================="
  echo "Task ready for QA testing"
  echo "Task: $TASK_TITLE"
  echo "Task ID: $TASK_ID"
  echo ""

  # Create QA queue file
  QA_DIR="$CLAUDE_PROJECT_DIR/.claude/qa-queue"
  mkdir -p "$QA_DIR"

  QA_ITEM="$QA_DIR/$TASK_ID.json"
  cat > "$QA_ITEM" << EOF
{
  "task_id": "$TASK_ID",
  "task_title": "$TASK_TITLE",
  "status": "ready-for-qa",
  "timestamp": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
}
EOF

  echo "✓ Added to QA queue: $QA_ITEM"

  # Optional: Send notification (Slack, email, etc.)
  if [ -n "${SLACK_QA_WEBHOOK:-}" ]; then
    PAYLOAD=$(cat <<EOF
{
  "text": "🧪 Task ready for QA testing",
  "blocks": [
    {
      "type": "section",
      "text": {
        "type": "mrkdwn",
        "text": "*Task Ready for QA*\n\n*Title:* $TASK_TITLE\n*Task ID:* \`$TASK_ID\`"
      }
    }
  ]
}
EOF
)
    curl -X POST "$SLACK_QA_WEBHOOK" \
      -H "Content-Type: application/json" \
      -d "$PAYLOAD" 2>&1 || echo "⚠️  Slack notification failed"
  fi

  echo "=========================================="
fi

# Handle validated status (QA approved)
if [ "$STATUS" = "validated" ]; then
  echo "=========================================="
  echo "QA Validation Complete"
  echo "=========================================="
  echo "Task validated by QA"
  echo "Task: $TASK_TITLE"
  echo "Task ID: $TASK_ID"
  echo ""

  # Remove from QA queue
  QA_ITEM="$CLAUDE_PROJECT_DIR/.claude/qa-queue/$TASK_ID.json"
  if [ -f "$QA_ITEM" ]; then
    rm "$QA_ITEM"
    echo "✓ Removed from QA queue"
  fi

  # Log QA signoff
  LOG_DIR="$CLAUDE_PROJECT_DIR/.claude/logs"
  mkdir -p "$LOG_DIR"
  echo "[$(date -u +"%Y-%m-%dT%H:%M:%SZ")] QA validated: $TASK_TITLE (Task ID: $TASK_ID)" >> "$LOG_DIR/qa-signoffs.log"

  # Optional: Notify team
  if [ -n "${SLACK_TEAM_WEBHOOK:-}" ]; then
    PAYLOAD=$(cat <<EOF
{
  "text": "✅ QA Validation Complete: $TASK_TITLE"
}
EOF
)
    curl -X POST "$SLACK_TEAM_WEBHOOK" \
      -H "Content-Type: application/json" \
      -d "$PAYLOAD" 2>&1 || echo "⚠️  Slack notification failed"
  fi

  echo "=========================================="
fi

exit 0
```

**Configuration**:

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__manage_container",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/qa-workflow.sh",
            "timeout": 30
          }
        ]
      }
    ]
  }
}
```

**Usage**:

```bash
# Developer marks task ready for QA
manage_container(operation="setStatus", containerType="task",
                id="task-uuid", status="ready-for-qa")
# → Hook creates QA queue item, sends Slack notification to QA team

# QA team tests the task
# QA approves and marks validated
manage_container(operation="setStatus", containerType="task",
                id="task-uuid", status="validated")
# → Hook removes from QA queue, logs signoff, notifies team
```

---

### Hook Example 4: Deployment Metrics Tracking

**Purpose**: Track all deployments across environments with detailed metrics.

**Triggers**: `mcp__task-orchestrator__manage_container` (status=deployed)

**File**: `.claude/hooks/deployment-metrics.sh`

```bash
#!/bin/bash
# Deployment metrics tracking
#
# Hook Event: PostToolUse
# Matcher: mcp__task-orchestrator__manage_container
# Trigger: status=deployed
# Tracks: Environment, timestamp, task details, deployment frequency

set -euo pipefail

INPUT=$(cat)

OPERATION=$(echo "$INPUT" | jq -r '.tool_input.operation // empty')
STATUS=$(echo "$INPUT" | jq -r '.tool_input.status // empty')
TAGS=$(echo "$INPUT" | jq -r '.tool_input.tags // empty')
TASK_ID=$(echo "$INPUT" | jq -r '.tool_input.id // empty')

# Only track deployed status
if [ "$STATUS" != "deployed" ]; then
  exit 0
fi

# Determine environment from tags
ENVIRONMENT="unknown"
if [[ "$TAGS" =~ production ]]; then
  ENVIRONMENT="production"
elif [[ "$TAGS" =~ staging ]]; then
  ENVIRONMENT="staging"
elif [[ "$TAGS" =~ qa ]]; then
  ENVIRONMENT="qa"
elif [[ "$TAGS" =~ dev ]]; then
  ENVIRONMENT="dev"
fi

DB_PATH="$CLAUDE_PROJECT_DIR/data/tasks.db"

# Get task details
TASK_DATA=$(sqlite3 "$DB_PATH" -json \
  "SELECT title, priority, complexity FROM Tasks WHERE id=x'$(echo "$TASK_ID" | tr -d '-')'" 2>/dev/null || echo "[]")

TASK_TITLE=$(echo "$TASK_DATA" | jq -r '.[0].title // "Unknown"')
PRIORITY=$(echo "$TASK_DATA" | jq -r '.[0].priority // "unknown"')
COMPLEXITY=$(echo "$TASK_DATA" | jq -r '.[0].complexity // 0')

# Create metrics directory
METRICS_DIR="$CLAUDE_PROJECT_DIR/.claude/metrics"
mkdir -p "$METRICS_DIR"

TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
DATE_ONLY=$(date -u +"%Y-%m-%d")

# Initialize CSV if needed
CSV_FILE="$METRICS_DIR/deployments.csv"
if [ ! -f "$CSV_FILE" ]; then
  echo "timestamp,date,environment,task_id,task_title,priority,complexity,tags" > "$CSV_FILE"
fi

# Append deployment record
echo "$TIMESTAMP,$DATE_ONLY,$ENVIRONMENT,$TASK_ID,\"$TASK_TITLE\",$PRIORITY,$COMPLEXITY,\"$TAGS\"" >> "$CSV_FILE"

# Update summary statistics
TOTAL_DEPLOYMENTS=$(tail -n +2 "$CSV_FILE" | wc -l)
PROD_DEPLOYMENTS=$(tail -n +2 "$CSV_FILE" | grep -c ",production," || echo "0")
STAGING_DEPLOYMENTS=$(tail -n +2 "$CSV_FILE" | grep -c ",staging," || echo "0")
TODAY_DEPLOYMENTS=$(tail -n +2 "$CSV_FILE" | grep -c ",$DATE_ONLY," || echo "0")

# Generate summary
cat > "$METRICS_DIR/deployment-summary.txt" << EOF
Deployment Metrics Summary
Generated: $TIMESTAMP

Total Deployments: $TOTAL_DEPLOYMENTS
Production Deployments: $PROD_DEPLOYMENTS
Staging Deployments: $STAGING_DEPLOYMENTS
Today's Deployments: $TODAY_DEPLOYMENTS

Latest Deployment:
  Environment: $ENVIRONMENT
  Task: $TASK_TITLE
  Priority: $PRIORITY
  Complexity: $COMPLEXITY
  Timestamp: $TIMESTAMP
EOF

echo "✓ Deployment metrics tracked"
echo "  Environment: $ENVIRONMENT"
echo "  Task: $TASK_TITLE"
echo "  Total deployments: $TOTAL_DEPLOYMENTS"
echo "  Production deployments: $PROD_DEPLOYMENTS"
echo "  Today's deployments: $TODAY_DEPLOYMENTS"
echo ""
echo "Metrics: $METRICS_DIR/deployments.csv"
echo "Summary: $METRICS_DIR/deployment-summary.txt"

exit 0
```

**Configuration**:

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "mcp__task-orchestrator__manage_container",
        "hooks": [
          {
            "type": "command",
            "command": "\"$CLAUDE_PROJECT_DIR\"/.claude/hooks/deployment-metrics.sh",
            "timeout": 30
          }
        ]
      }
    ]
  }
}
```

**Metrics Output** (`.claude/metrics/deployments.csv`):

```csv
timestamp,date,environment,task_id,task_title,priority,complexity,tags
2025-10-22T10:30:00Z,2025-10-22,staging,550e8400-e29b-41d4-a716-446655440000,"Add user authentication",high,7,"backend,auth,staging"
2025-10-22T14:15:00Z,2025-10-22,production,550e8400-e29b-41d4-a716-446655440000,"Add user authentication",high,7,"backend,auth,production"
2025-10-22T15:00:00Z,2025-10-22,staging,660e9511-f39c-52e5-b827-557766551111,"Implement password reset",medium,6,"backend,auth,staging"
```

**Analysis Queries**:

```bash
# Deployments per environment
cut -d',' -f3 .claude/metrics/deployments.csv | tail -n +2 | sort | uniq -c

# Average complexity deployed to production
awk -F',' '$3=="production" {sum+=$7; count++} END {print "Avg complexity:", sum/count}' .claude/metrics/deployments.csv

# High priority deployments
grep ",high," .claude/metrics/deployments.csv | wc -l

# Deployments per day
cut -d',' -f2 .claude/metrics/deployments.csv | tail -n +2 | sort | uniq -c
```

---

## Complete Workflow Examples

### Example 1: Solo Dev Full Cycle

```bash
# Feature: Password Reset Flow
# Workflow: Solo Dev (full cycle with staging/production)

# 1. Create feature
manage_container(
  operation="create",
  containerType="feature",
  name="Password Reset Flow",
  status="planning",
  priority="high",
  projectId="project-uuid",
  tags="backend,auth,security"
)

# 2. Create task
manage_container(
  operation="create",
  containerType="task",
  title="Implement password reset API endpoint",
  status="pending",
  priority="high",
  complexity=7,
  featureId="feature-uuid",
  tags="backend,api,auth"
)

# 3. Start implementation
manage_container(
  operation="setStatus",
  containerType="task",
  id="task-uuid",
  status="in-progress"
)
# Work on implementation...

# 4. Implementation complete, run tests
manage_container(
  operation="setStatus",
  containerType="task",
  id="task-uuid",
  status="testing"
)
# ./gradlew test

# 5. Tests pass, deploy to staging
manage_container(
  operation="update",
  containerType="task",
  id="task-uuid",
  status="deployed",
  tags="backend,api,auth,staging"
)
# → Hook triggers staging deployment script
# → Test in staging environment

# 6. Staging validated, deploy to production
manage_container(
  operation="update",
  containerType="task",
  id="task-uuid",
  status="deployed",
  tags="backend,api,auth,production"
)
# → Hook triggers production deployment script

# 7. Verify in production, mark complete
manage_container(
  operation="setStatus",
  containerType="task",
  id="task-uuid",
  status="completed"
)
```

### Example 2: Small Team with Review Gates

```bash
# Feature: Stripe Payment Integration
# Workflow: Small Team (review + QA gates)

# 1. Create task
manage_container(
  operation="create",
  containerType="task",
  title="Integrate Stripe payment processing",
  status="pending",
  priority="high",
  complexity=9,
  featureId="feature-uuid",
  tags="backend,payments,stripe,critical"
)

# 2. Developer implements
manage_container(
  operation="setStatus",
  containerType="task",
  id="task-uuid",
  status="in-progress"
)
# Code implementation...

# 3. Submit for code review
manage_container(
  operation="setStatus",
  containerType="task",
  id="task-uuid",
  status="in-review"
)
# → Hook notifies team reviewer
# Reviewer checks code, approves

# 4. Code approved, run tests
manage_container(
  operation="setStatus",
  containerType="task",
  id="task-uuid",
  status="testing"
)
# ./gradlew test

# 5. Tests pass, ready for QA
manage_container(
  operation="setStatus",
  containerType="task",
  id="task-uuid",
  status="ready-for-qa"
)
# → Hook creates QA queue item, notifies QA team

# 6. QA validates functionality
manage_container(
  operation="setStatus",
  containerType="task",
  id="task-uuid",
  status="validated"
)
# → Hook logs QA signoff

# 7. Deploy to staging
manage_container(
  operation="update",
  containerType="task",
  id="task-uuid",
  status="deployed",
  tags="backend,payments,stripe,critical,staging"
)
# → Hook deploys to staging
# QA tests in staging

# 8. QA approves for production
mkdir -p .claude/qa-approvals
echo "Approved by: QA Lead" > .claude/qa-approvals/task-uuid.approved
echo "Date: $(date -u)" >> .claude/qa-approvals/task-uuid.approved

# 9. Deploy to production
manage_container(
  operation="update",
  containerType="task",
  id="task-uuid",
  status="deployed",
  tags="backend,payments,stripe,critical,production"
)
# → Hook checks QA approval (passes)
# → Hook deploys to production

# 10. Verify and complete
manage_container(
  operation="setStatus",
  containerType="task",
  id="task-uuid",
  status="completed"
)
# → Hook notifies team of completion
```

---

## Best Practices

### Choosing Your Workflow Pattern

**Use Vibe Coder if**:
- Solo developer
- Rapid iteration is priority
- Comfortable with fewer guardrails
- Personal projects or prototypes

**Use Solo Dev if**:
- Solo developer building production apps
- Need deployment stages
- Want quality gates without team overhead
- SaaS products with real users

**Use Small Team if**:
- Team of 2-10 developers
- Need code review process
- Have dedicated QA
- Regulated industry or critical systems

### Status Design Guidelines

**Keep statuses meaningful**:
- Each status should represent a distinct state
- Status names should be self-explanatory
- Avoid too many statuses (cognitive overhead)
- Typical range: 3-8 statuses

**Status naming conventions**:
- Use lowercase with hyphens: `in-progress`, `ready-for-qa`
- Be consistent across your project
- Document custom statuses in project README

### Environment Tag Guidelines

**Always use environment tags for deployed status**:
```bash
# Good
status="deployed", tags="backend,staging"
status="deployed", tags="frontend,production"

# Bad (environment unclear)
status="deployed", tags="backend"
```

**One environment tag per task**:
```bash
# Good
tags="backend,api,staging"  # Clear: staging environment

# Bad (ambiguous)
tags="backend,api,staging,production"  # Which environment?
```

**Update tags when promoting**:
```bash
# Deploy to staging
manage_container(operation="update", id="task-uuid",
                status="deployed", tags="backend,api,staging")

# Promote to production (replace staging tag)
manage_container(operation="update", id="task-uuid",
                status="deployed", tags="backend,api,production")
```

### Hook Integration Best Practices

**Start simple**: Begin with logging/metrics hooks before blocking hooks

**Test hooks thoroughly**: Use the testing patterns from [hooks-guide.md](hooks-guide.md)

**Graceful degradation**: Hooks should never break Claude's workflow

**Document your hooks**: Add clear comments and README files

**Use timeouts**: Set appropriate timeouts for deployment hooks (60-300 seconds)

**Log everything**: Create audit trails for deployments and approvals

### Security Considerations

**QA approval files**:
- Store in `.claude/qa-approvals/` (add to .gitignore)
- Include approver name and timestamp
- Require manual creation (prevents accidental approvals)

**Production deployment**:
- Always use blocking hooks for production gates
- Require explicit QA signoff
- Log all production deployments
- Consider adding rollback hooks

**Secrets management**:
- Never hardcode deployment credentials in hooks
- Use environment variables for API keys
- Store webhook URLs in environment variables
- Add `.claude/settings.local.json` to .gitignore

---

## Summary

This guide covered three workflow patterns and deployment automation:

**Patterns**:
1. **Vibe Coder** - Fast iteration (3 statuses)
2. **Solo Dev** - Full cycle with deployment stages (5-6 statuses)
3. **Small Team** - Review gates and QA validation (8+ statuses)

**Deployment Hooks**:
1. Auto-deploy to staging on `deployed` + `staging` tag
2. Production gate requiring QA approval
3. QA workflow with notifications and signoffs
4. Deployment metrics tracking

**Key Concepts**:
- Custom statuses for your workflow
- Environment tags (`staging`, `production`, `qa`, `dev`)
- Hook integration for automation
- Quality gates with blocking hooks

**Next Steps**:
- Choose your workflow pattern
- Implement basic hooks (staging deployment, metrics)
- Add quality gates if needed (production gate, QA workflow)
- Customize for your team's needs

For more information:
- [hooks-guide.md](hooks-guide.md) - Comprehensive hook development guide
- [ai-guidelines.md](ai-guidelines.md) - AI usage patterns
- [api-reference.md](api-reference.md) - Complete API documentation
