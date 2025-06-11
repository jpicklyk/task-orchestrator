---
layout: default
title: Workflow Prompts
---

# MCP Workflow Prompts

The MCP Task Orchestrator includes **6 user-invokable workflow prompts** that provide structured guidance for common task orchestration scenarios. These prompts are designed to work seamlessly with the MCP tool ecosystem and offer step-by-step workflows for complex project management tasks.

## Table of Contents

- [Overview](#overview)
- [Available Workflow Prompts](#available-workflow-prompts)
  - [create_feature_workflow](#create_feature_workflow)
  - [task_breakdown_workflow](#task_breakdown_workflow)
  - [bug_triage_workflow](#bug_triage_workflow)
  - [sprint_planning_workflow](#sprint_planning_workflow)
  - [project_setup_workflow](#project_setup_workflow)
  - [implement_feature_workflow](#implement_feature_workflow)
- [Using Workflow Prompts](#using-workflow-prompts)
- [Integration with Templates](#integration-with-templates)

---

## Overview

Workflow prompts are AI-invokable guides that provide step-by-step instructions for complex project management scenarios. Each prompt integrates seamlessly with the MCP tool ecosystem and includes:

- **Step-by-step instructions** with specific MCP tool calls
- **Best practice guidance** for quality and consistency
- **JSON examples** for tool parameters
- **Quality validation** checklists
- **Integration points** with other workflows and templates

### Benefits

- **Standardized Processes**: Consistent approaches to common scenarios
- **AI-Friendly**: Designed for AI assistant interaction and automation
- **Tool Integration**: Direct integration with all 37 MCP tools
- **Template Coordination**: Works seamlessly with the template system
- **Progressive Complexity**: From simple task creation to complex project setup

---

## Available Workflow Prompts

### `create_feature_workflow`

**Guide for creating a comprehensive feature with templates, tasks, and proper organization**

This workflow provides a complete 7-step process for creating well-structured features with comprehensive documentation and associated tasks.

#### Workflow Steps

1. **Check Current State**
   - Use `get_overview` to understand existing project state
   - Review current features and work distribution
   - Identify potential relationships and dependencies

2. **Find Appropriate Templates**
   - Use `list_templates` with `targetEntityType="FEATURE"` and `isEnabled=true`
   - Look for templates like:
     - Context & Background (for business context)
     - Requirements Specification (for detailed requirements)
     - Technical Approach (for architecture planning)

3. **Create the Feature**
   - Use `create_feature` with comprehensive metadata:
     ```json
     {
       "name": "[Descriptive feature name]",
       "summary": "[Comprehensive summary with business value]",
       "status": "planning",
       "priority": "[high/medium/low based on importance]",
       "templateIds": ["context-template-id", "requirements-template-id"],
       "tags": "[functional-area,technical-stack,business-impact]"
     }
     ```

4. **Review Created Structure**
   - Use `get_feature` with `includeSections=true` to examine template structure
   - Verify that templates were applied correctly
   - Review the documentation foundation

5. **Create Associated Tasks**
   - **Git Detection**: Check for .git directory in project root (use file system tools)
   - Break down the feature into specific implementation tasks:
     ```json
     {
       "title": "[Specific implementation task]",
       "summary": "[Clear task description with acceptance criteria]",
       "featureId": "[feature-id-from-step-3]",
       "complexity": "[1-10 based on effort estimate]",
       "priority": "[based on implementation order]",
       "templateIds": ["task-implementation-workflow", "local-git-branching-workflow", "technical-approach"],
       "tags": "[task-type-feature,component-type,technical-area]"
     }
     ```
   - **Note**: If git detected, automatically include "local-git-branching-workflow" template
   - **Ask user**: "Do you use GitHub/GitLab PRs? If yes, I can also apply PR workflow template"

6. **Establish Dependencies (if needed)**
   - Use `create_dependency` to establish task relationships
   - Link prerequisite tasks with BLOCKS relationships
   - Create feature completion chains

7. **Final Review and Completion Validation**
   - Use `get_feature` with `includeTasks=true` and `includeSections=true`
   - Verify complete feature structure
   - Ensure all tasks have clear acceptance criteria
   - **Before marking feature as completed**: Use `get_sections` to review all feature sections and verify template guidance was followed

#### Best Practices Included

- **Template Selection Strategies**: Use multiple templates for comprehensive coverage
- **Task Sizing Guidelines**: Keep complexity between 3-7 for manageable work
- **Consistent Tagging Conventions**: Use standardized tag patterns for filtering
- **Clear Acceptance Criteria**: Define completion requirements upfront

---

### `task_breakdown_workflow`

**Guide for breaking down complex tasks into manageable, well-organized subtasks**

This workflow provides a systematic 7-step approach for decomposing complex work into manageable components.

#### Workflow Steps

1. **Analyze the Complex Task**
   - Use `get_task` with `includeSections=true` to review the complex task
   - Look for scope spanning multiple technical areas
   - Identify complexity ratings above 7
   - Review multiple acceptance criteria or dependencies

2. **Identify Natural Boundaries**
   - **By Component**: Frontend, Backend, Database, Testing
   - **By Feature Area**: Authentication, Data Processing, UI Components
   - **By Phase**: Research, Design, Implementation, Testing, Documentation
   - **By Skill Set**: Backend API, Frontend UI, DevOps, QA

3. **Create Feature Container (if beneficial)**
   - For task breakdowns with 4+ subtasks:
     ```json
     {
       "name": "[Original task name as feature]",
       "summary": "[Expanded description from original task]",
       "templateIds": ["context-background", "requirements-specification"]
     }
     ```

4. **Create Focused Subtasks**
   - **Git Detection**: Check for .git directory in project root (use file system tools)
   - Create manageable subtasks with complexity 3-6:
     ```json
     {
       "title": "[Specific, actionable task name]",
       "summary": "[Clear scope with specific acceptance criteria]",
       "featureId": "[from step 3, if created]",
       "complexity": "[3-6 for manageable tasks]",
       "priority": "[based on implementation dependencies]",
       "templateIds": ["task-implementation-workflow", "local-git-branching-workflow"],
       "tags": "[original-tags,component-type,implementation-area]"
     }
     ```
   - **Note**: If git detected, automatically include "local-git-branching-workflow" template
   - **Ask user**: "Do you use GitHub/GitLab PRs? If yes, I can also apply PR workflow template"

5. **Establish Task Dependencies**
   - Use `create_dependency` to establish implementation order
   - Example: Database schema BLOCKS API implementation
   - Create logical workflow sequences

6. **Update Original Task**
   - Transform the original complex task:
     ```json
     {
       "id": "[original-task-id]",
       "title": "[Updated to reflect coordination role]",
       "summary": "[Reference to subtasks and overall coordination]",
       "complexity": "[Reduced to 2-3 for coordination]",
       "featureId": "[link to feature if created]"
     }
     ```

7. **Review the Breakdown and Completion Validation**
   - Use `get_feature` (if created) or `search_tasks` to review all subtasks
   - Ensure total complexity is manageable
   - Verify dependencies don't create cycles
   - Confirm clear acceptance criteria for each subtask
   - **For task completion**: Before marking any subtask as completed, use `get_sections` to verify all template guidance was followed

#### Common Breakdown Patterns

**API Development**:
1. Database schema design
2. Core API endpoints implementation
3. Authentication/authorization integration
4. Input validation and error handling
5. API documentation and testing

**UI Feature**:
1. Component design and wireframes
2. Core component implementation
3. State management integration
4. Styling and responsive design
5. User testing and accessibility

**Integration Feature**:
1. External service research and setup
2. Authentication/connection implementation
3. Data mapping and transformation
4. Error handling and retry logic
5. Testing and monitoring setup

#### Quality Guidelines

**Good Subtask Characteristics**:
- Complexity rating 3-6 (manageable in 1-3 days)
- Single clear responsibility
- Specific, testable acceptance criteria
- Minimal dependencies on other subtasks
- Can be assigned to one person or skill set

---

### `bug_triage_workflow`

**Systematic approach to bug triage, investigation, and resolution planning**

This workflow provides a comprehensive 7-step bug management process from initial assessment to resolution tracking.

#### Workflow Steps

1. **Initial Bug Assessment**
   - Use `search_tasks` with `tag="task-type-bug"` and `priority="high"`
   - Review current bug load and priorities
   - Assess overall bug backlog health

2. **Create Bug Investigation Task**
   - **Git Detection**: Check for .git directory in project root (use file system tools)
   - Create structured task with bug investigation template:
     ```json
     {
       "title": "[Clear bug description with impact]",
       "summary": "[Detailed symptoms, reproduction steps, and initial impact assessment]",
       "priority": "[high for critical issues, medium for significant issues, low for minor issues]",
       "complexity": "[Initial estimate: 3-5 for investigation, adjust after analysis]",
       "templateIds": ["bug-investigation-workflow", "local-git-branching-workflow"],
       "tags": "task-type-bug,component-[affected-area],severity-[high/medium/low]"
     }
     ```
   - **Note**: If git detected, automatically include "local-git-branching-workflow" template
   - **Ask user**: "Do you use GitHub/GitLab PRs? If yes, I can also apply PR workflow template"

3. **Detailed Investigation**
   - **Problem Analysis**:
     - Document exact symptoms and error messages
     - Identify affected user workflows
     - Determine frequency and impact scope
     - List reproduction steps
   
   - **Technical Investigation**:
     - Review relevant logs and error traces
     - Identify potential root causes
     - Check recent code changes in affected areas
     - Test in different environments
   
   - **Impact Assessment**:
     - Determine user impact severity
     - Assess business impact and urgency
     - Identify workarounds or temporary solutions
     - Evaluate fix complexity and risk

4. **Determine Resolution Approach**
   - **For Simple Bugs (complexity 3-5)**:
     Update existing task with resolution plan:
     ```json
     {
       "id": "[bug-task-id]",
       "title": "[Updated with root cause]",
       "summary": "[Include resolution approach and testing plan]",
       "complexity": "[Refined based on investigation]"
     }
     ```
   
   - **For Complex Bugs (complexity 6+)**:
     Use `task_breakdown_workflow` to create focused subtasks:
     1. Root cause analysis task
     2. Fix implementation task
     3. Testing and validation task
     4. Deployment and monitoring task

5. **Prioritization and Planning**
   - **Priority Guidelines**:
     - **High**: Security issues, data corruption, complete feature breakdown
     - **Medium**: Significant functionality issues, user experience problems
     - **Low**: Minor UI issues, edge case problems, cosmetic issues
   
   - **Planning Considerations**:
     - Use `get_task_dependencies` to understand blocking relationships
     - Consider impact on other features
     - Evaluate fix risk vs. issue severity
     - Plan testing strategy and rollback procedures

6. **Implementation Workflow**
   - Apply implementation templates:
     - Use `apply_template` with "local-git-branching-workflow" or "github-pr-workflow"
     - Follow step-by-step guidance for branch creation, development, testing, and deployment

7. **Resolution Tracking and Completion Validation**
   - Use `update_task` to track progress:
     - **pending** → **in-progress** when starting investigation/fix
     - **in-progress** → **completed** when fix is deployed and verified
   - **Before marking as completed**: Use `get_sections` to verify all bug investigation template guidance was followed
   - Document resolution details and lessons learned in sections

#### Bug Classification System

**Severity Tags**:
- `severity-critical`: System down, data loss, security breach
- `severity-high`: Major functionality broken, significant user impact
- `severity-medium`: Feature partially broken, workaround available
- `severity-low`: Minor issue, cosmetic problem

**Component Tags**:
- `component-frontend`: UI, user experience issues
- `component-backend`: API, server-side logic issues
- `component-database`: Data integrity, query performance
- `component-integration`: Third-party service issues
- `component-infrastructure`: Deployment, environment issues

**Type Tags**:
- `bug-type-regression`: Previously working functionality broken
- `bug-type-performance`: Speed or resource usage issues
- `bug-type-security`: Security vulnerabilities
- `bug-type-data`: Data corruption or incorrect processing

---

### `sprint_planning_workflow`

**Comprehensive guide for sprint planning using task orchestrator tools and data**

This workflow provides a data-driven 8-step sprint planning process using the Task Orchestrator's analytical capabilities.

#### Workflow Steps

1. **Current State Analysis**
   - Use `get_overview` to see all features, tasks, and work distribution
   - Look for:
     - In-progress tasks that need completion
     - High-priority pending tasks
     - Feature completion status
     - Orphaned tasks needing organization

2. **Backlog Analysis**
   - Use `search_tasks` with various filters:
     - `status="pending"` + `priority="high"`
     - `status="pending"` + `priority="medium"`
   - Review:
     - Task complexity distribution
     - Feature associations
     - Dependencies between tasks
     - Bug vs. feature work balance

3. **Capacity Assessment**
   - **Review In-Progress Work**:
     - Use `search_tasks` with `status="in-progress"`
     - Estimate completion effort for current tasks
     - Identify potential blockers or delays
     - Consider carry-over work impact
   
   - **Analyze Task Complexity**:
     - Use `search_tasks` sorted by complexity
     - Group tasks by complexity for estimation
     - Consider team skill distribution
     - Plan for complexity variance

4. **Priority Setting**
   - **Business Value Assessment**:
     - Review feature summaries for business impact
     - Consider user-facing vs. internal improvements
     - Evaluate technical debt vs. new feature balance
   
   - **Dependency Analysis**:
     - Use `get_task_dependencies` for critical tasks
     - Identify blocking dependencies
     - Plan task sequencing
     - Consider cross-team dependencies

5. **Sprint Goal Definition**
   - **Feature-Based Goals**:
     - Use `get_feature` with `includeTasks=true` for key features
     - Complete specific features or feature phases
     - Address critical bug backlogs
     - Achieve technical milestones
   
   - **Create Sprint Feature (Optional)**:
     ```json
     {
       "name": "Sprint [X] - [Sprint Goal]",
       "summary": "[Sprint objectives and success criteria]",
       "status": "in-development",
       "priority": "high",
       "tags": "sprint-planning,sprint-[number],goal-[theme]"
     }
     ```

6. **Task Selection and Assignment**
   - **Selection Criteria**:
     - Aligns with sprint goals
     - Appropriate complexity for team capacity
     - Dependencies are satisfied or manageable
     - Clear acceptance criteria defined
     - Implementation tasks have git workflow templates applied (check for "local-git-branching-workflow" template)
   
   - **Task Updates for Sprint Commitment**:
     ```json
     {
       "id": "[task-id]",
       "tags": "[existing-tags],sprint-[number],assigned-[team-member]",
       "priority": "[adjusted for sprint priority]"
     }
     ```

7. **Sprint Backlog Organization**
   - **Create Dependencies**:
     - Use `create_dependency` to establish work sequences
     - Link prerequisite tasks
     - Create feature completion chains
     - Plan integration points
   
   - **Task Breakdown (if needed)**:
     - Use `task_breakdown_workflow` for complex tasks
     - Split large tasks into sprint-sized work
     - Enable parallel development
     - Reduce estimation uncertainty

8. **Sprint Monitoring Setup**
   - **Create Sprint Views**:
     - Use `search_tasks` with `tag="sprint-[number]"`
     - This becomes your primary sprint tracking view
   
   - **Define Daily Standup Queries**:
     - `status="in-progress"` + `tag="sprint-[number]"` for current work
     - `status="completed"` + `tag="sprint-[number]"` for recent completions
     - Tasks with blockers or dependency issues

#### Sprint Planning Best Practices

**Task Selection Guidelines**:
- Include a mix of complexities (avoid all high-complexity tasks)
- Reserve 20% capacity for unexpected work and support
- Balance feature development with technical debt
- Include testing and documentation tasks

**Risk Mitigation**:
- Identify tasks with external dependencies
- Plan alternatives for high-risk tasks
- Include buffer tasks for scope adjustment
- Consider team availability and skills

**Communication Planning**:
- Update task summaries with sprint-specific context
- Add acceptance criteria that align with sprint goals
- Plan integration points and review checkpoints
- Document assumptions and constraints

#### Sprint Execution Support

**Daily Progress Tracking**:
- Use `search_tasks` with various status filters
- Track task progression through statuses
- Identify blockers early
- Monitor scope creep

**Mid-Sprint Adjustments**:
- Use task priority updates for scope changes
- Move non-critical tasks out of sprint
- Break down tasks that prove more complex

**Sprint Review Preparation**:
- Use `search_tasks` with `status="completed"` and `tag="sprint-[number]"`
- Review completed work
- Document lessons learned in task sections
- Identify process improvements

---

### `project_setup_workflow`

**Complete guide for setting up a new project with proper structure, features, and initial tasks**

This workflow provides a comprehensive 8-step project initialization process for long-term success.

#### Workflow Steps

1. **Project Foundation**
   - Create the top-level project container:
     ```json
     {
       "name": "[Descriptive project name]",
       "summary": "[Comprehensive project description with goals, scope, and success criteria]",
       "status": "planning",
       "tags": "[project-type,technology-stack,business-domain,timeline]"
     }
     ```
   
   **Project Summary Best Practices**:
   - Include business objectives and user value
   - Define scope boundaries (what's included/excluded)
   - Mention key technologies and constraints
   - State success criteria and completion definition

2. **Project Documentation Structure**
   - Use `bulk_create_sections` for comprehensive project documentation:
     ```json
     {
       "sections": [
         {
           "entityType": "PROJECT",
           "entityId": "[project-id]",
           "title": "Project Charter",
           "usageDescription": "High-level project goals, stakeholders, and success criteria",
           "content": "[Business goals, target users, key stakeholders, success metrics]",
           "ordinal": 0,
           "tags": "charter,goals,stakeholders"
         },
         {
           "entityType": "PROJECT",
           "entityId": "[project-id]",
           "title": "Technical Architecture",
           "usageDescription": "Overall system architecture and technology decisions",
           "content": "[Architecture overview, technology stack, key design decisions]",
           "ordinal": 1,
           "tags": "architecture,technical,decisions"
         },
         {
           "entityType": "PROJECT",
           "entityId": "[project-id]",
           "title": "Development Standards",
           "usageDescription": "Coding standards, workflows, and quality requirements",
           "content": "[Coding guidelines, git workflow, testing requirements, review process]",
           "ordinal": 2,
           "tags": "standards,workflow,quality"
         }
       ]
     }
     ```

3. **Feature Planning and Structure**
   - **Feature Identification Strategy**:
     - Break project into 3-7 major functional areas
     - Each feature should represent cohesive user functionality
     - Features should be independently deliverable when possible
     - Consider technical architecture boundaries
   
   - **Create Core Features**:
     ```json
     {
       "name": "[Feature name representing user functionality]",
       "summary": "[Feature description with user value and technical scope]",
       "status": "planning",
       "priority": "[high for core features, medium for enhancements]",
       "projectId": "[project-id]",
       "templateIds": ["context-background", "requirements-specification"],
       "tags": "[feature-type,user-facing/internal,complexity-level]"
     }
     ```

4. **Initial Task Creation**
   - **Git Detection**: Check for .git directory in project root (use file system tools)
   - **Infrastructure and Setup Tasks**:
     ```json
     {
       "title": "Project Infrastructure Setup",
       "summary": "Set up development environment, CI/CD, and project tooling",
       "projectId": "[project-id]",
       "priority": "high",
       "complexity": 6,
       "templateIds": ["task-implementation-workflow", "local-git-branching-workflow", "technical-approach"],
       "tags": "task-type-infrastructure,setup,foundation"
     }
     ```
   
   - **Research and Planning Tasks**:
     ```json
     {
       "title": "[Technology/Approach] Research",
       "summary": "Research and validate [specific technology or approach] for [specific use case]",
       "projectId": "[project-id]",
       "priority": "high",
       "complexity": 4,
       "templateIds": ["technical-approach"],
       "tags": "task-type-research,planning,technology-validation"
     }
     ```
   
   - **Template Selection Notes**:
     - If git detected, include "local-git-branching-workflow" for implementation tasks
     - Research tasks may not need git templates unless they involve code prototyping
     - Ask user: "Do you use GitHub/GitLab PRs? If yes, I can also apply PR workflow template"

5. **Template Strategy Setup**
   - **Review Available Templates**:
     - Use `list_templates` to understand available templates
     - Identify templates that align with project needs
   
   - **Consider Custom Templates**:
     - For project-specific patterns, create custom templates:
       ```json
       {
         "name": "[Project-Specific] Documentation Template",
         "description": "Standardized documentation for [specific project context]",
         "targetEntityType": "TASK"
       }
       ```

6. **Development Workflow Setup**
   - **Git Workflow Configuration**:
     ```json
     {
       "title": "Establish Git Workflow Standards",
       "summary": "Set up branching strategy, commit conventions, and PR process",
       "templateIds": ["local-git-branching-workflow", "github-pr-workflow"],
       "tags": "task-type-process,git-workflow,standards"
     }
     ```
   
   - **Quality Assurance Setup**:
     ```json
     {
       "title": "Quality Assurance Framework",
       "summary": "Establish testing strategy, code review process, and quality gates",
       "templateIds": ["testing-strategy", "definition-of-done"],
       "tags": "task-type-process,qa,testing,standards"
     }
     ```

7. **Initial Dependencies and Sequencing**
   - Use `create_dependency` to establish foundational sequences:
     - Infrastructure setup BLOCKS feature development
     - Research tasks BLOCK implementation decisions
     - Architecture decisions BLOCK detailed design tasks

8. **Project Monitoring Setup**
   - **Create Project Views**:
     - Use `search_tasks` with `projectId="[project-id]"`
     - Use `search_features` with `projectId="[project-id]"`
   
   - **Define Progress Tracking**:
     - Feature completion metrics
     - Task status distribution
     - Complexity and effort tracking
     - Priority balance monitoring

#### Project Organization Best Practices

**Naming Conventions**:
- **Projects**: Business/product focused names
- **Features**: User-functionality focused names
- **Tasks**: Implementation-action focused names

**Tagging Strategy**:
- **Project-level**: Domain, technology stack, business area
- **Feature-level**: User impact, complexity, dependencies
- **Task-level**: Type, component, skill requirements

**Documentation Standards**:
- Keep project-level docs high-level and stable
- Feature docs should focus on user value and requirements
- Task docs should be implementation-focused and actionable

**Scalability Planning**:
- Design feature structure for team growth
- Plan for feature independence and parallel development
- Establish clear interfaces between features
- Consider long-term maintenance and evolution

#### Success Metrics

**Project Setup Completion Indicators**:
- Project has clear charter and architecture documentation
- All major features identified and documented
- Foundation tasks created and prioritized
- Development workflow established
- Team understands project structure and conventions

**Ongoing Health Indicators**:
- Tasks are consistently well-documented with templates
- Features show steady progression
- Dependencies are managed and don't create bottlenecks
- Project overview shows balanced work distribution

### `implement_feature_workflow`

**Guide for implementing features with automatic git detection and proper development workflow integration**

This workflow provides intelligent feature implementation guidance with automatic project context detection and workflow template suggestion.

#### Workflow Steps

1. **Check Current State & Git Detection**
   - Use `get_overview` to understand project context and current work priorities
   - Use file system tools to detect if project uses git (check for `.git` directory existence)
   - If git detected, automatically suggest "Local Git Branching Workflow" template application
   - Review existing in-progress tasks to avoid conflicts

2. **Select Implementation Target**
   - Use `search_tasks` with `status="pending"` and `priority="high"` to identify suitable work
   - Review available features using `search_features` for feature-level work
   - Suggest starting with highest priority, unblocked tasks
   - Verify prerequisites and dependencies are satisfied using `get_task_dependencies`

3. **Apply Appropriate Templates with Smart Detection**
   - **Always apply**: "Task Implementation Workflow" template for implementation tasks
   - **If git detected**: Automatically apply "Local Git Branching Workflow" template  
   - **Ask user**: "Do you use GitHub/GitLab pull requests? If yes, I can apply PR workflow template"
   - **If GitHub MCP available**: Mention GitHub MCP tools can automate PR creation and management
   - **For complex tasks**: Consider "Technical Approach" template for architectural guidance

4. **Execute Implementation with Template Guidance**
   - Follow template-provided step-by-step instructions from applied templates
   - Make incremental commits if using git workflows (following git template guidance)
   - Update task status to "in-progress" when starting work
   - Use `update_task` to track progress and add implementation notes

5. **Complete with Validation**
   - **Before marking task as completed**: Use `get_sections` to read all task sections
   - **Verify template compliance**: Ensure all instructional template guidance was followed
   - **Git workflow completion**: If using git templates, complete branch merge process
   - **Run tests and verification**: Follow testing guidance from applied templates
   - Update task status to "completed" only after full validation

#### Git Integration Best Practices

**Auto-Detection Logic**:
- Check for `.git` directory in project root or parent directories
- If found, always suggest "Local Git Branching Workflow" template
- Ask about PR workflows rather than assuming (different teams have different practices)

**GitHub Integration**:
- Detect if GitHub MCP server is available in the environment
- If available, mention it can automate PR creation, review management, and merge processes
- Only suggest GitHub PR workflow template if user confirms they use PRs

**Template Selection Strategy**:
- Implementation tasks (complexity > 3): Task Implementation + Git Branching workflows
- Complex features (complexity > 6): Task Implementation + Git Branching + Technical Approach templates
- Bug fixes: Bug Investigation + Git Branching workflows

#### Quality Validation Requirements

**Task Completion Validation**:
```bash
# Before marking any task as completed:
get_sections --entityType TASK --entityId [task-id]

# Review each section's guidance, especially:
# - Requirements compliance
# - Implementation approach validation  
# - Testing strategy completion
# - Git workflow steps (if applicable)
```

**Feature Completion Validation**:
```bash
# Before marking any feature as completed:
get_sections --entityType FEATURE --entityId [feature-id]
get_feature --id [feature-id] --includeTasks true --includeTaskCounts true

# Verify:
# - All associated tasks are completed
# - Feature-level requirements satisfied
# - Integration testing completed
# - Documentation updated
```

#### Integration with Other Workflows

This workflow complements other workflow prompts:
- Use with `task_breakdown_workflow` for complex features requiring decomposition
- Integrate with `sprint_planning_workflow` for systematic work organization
- Apply `bug_triage_workflow` principles for bug-related implementation work

---

## Using Workflow Prompts

Workflow prompts provide structured guidance for complex project management scenarios. You can use them in two ways:

### Usage Pattern 1: Guided Discovery (Run Prompt Alone)

Run the workflow prompt without additional details, and the AI will guide you through questions to understand your specific needs:

```bash
# Basic invocation - AI will ask questions to understand your needs
task-orchestrator:project_setup_workflow

# AI Response: "I'll help you set up a new project. What type of project are you building? 
# What technologies are you planning to use? What are your main business goals?"
```

```bash
# Another example
task-orchestrator:create_feature_workflow

# AI Response: "I'll help you create a comprehensive feature. What functionality do you want to build? 
# Do you have specific user stories or requirements in mind?"
```

**Best for:**
- Exploring options and getting structured guidance
- Learning about project setup best practices
- When you're not sure about all the details yet
- Getting AI assistance to think through requirements

### Usage Pattern 2: Direct Implementation (Provide Details with Prompt)

Provide your project details directly with the workflow prompt for immediate, targeted assistance:

```bash
task-orchestrator:project_setup_workflow

I'm setting up a new web API project using Node.js and Express. It needs user authentication, 
a PostgreSQL database, and REST endpoints for managing user profiles and documents. 
The target deployment is Docker containers on AWS.
```

```bash
task-orchestrator:create_feature_workflow

I want to create a user authentication feature that supports email/password login, 
social auth (Google, Apple), and JWT token management. It should integrate with our 
existing user database and include password reset functionality.
```

```bash
task-orchestrator:bug_triage_workflow

We have a critical bug where users can't upload files larger than 5MB. The error happens 
on both web and mobile clients. It started after yesterday's deployment of the new file 
processing service. Users get a "Request timeout" error after 30 seconds.
```

**Best for:**
- Getting immediate, focused assistance
- When you have clear requirements
- Faster workflow execution
- Specific problem-solving scenarios

### Choosing Your Approach

| Use Guided Discovery When: | Use Direct Implementation When: |
|---------------------------|--------------------------------|
| Learning the system | You have clear requirements |
| Exploring best practices | Working on time-sensitive tasks |
| Unsure about requirements | Following established patterns |
| Want comprehensive guidance | Need immediate action |

### Advanced Usage Examples

**Combining Workflows:**
```bash
task-orchestrator:project_setup_workflow

followed by

task-orchestrator:sprint_planning_workflow

Set up a React dashboard project with authentication and data visualization, 
then plan the first 2-week sprint focusing on basic auth and one chart type.
```

**Sequential Workflow Application:**
```bash
# First, break down a complex task
task-orchestrator:task_breakdown_workflow

This task involves building a complete CI/CD pipeline with testing, security scanning, 
and multi-environment deployment.

# Then implement the pieces
task-orchestrator:implement_feature_workflow

Now implement the first subtask from the breakdown.
```

### Integration Points

Workflow prompts integrate seamlessly with:
- **Template System**: Automatic template suggestions and application
- **MCP Tools**: Direct tool call examples and parameter guidance
- **Dependency Management**: Workflow sequencing and relationship establishment
- **Quality Gates**: Built-in validation and review checkpoints

---

## Integration with Templates

Workflow prompts are designed to work seamlessly with the template system:

### Template Application in Workflows
- Each workflow suggests appropriate templates for different scenarios
- Templates are applied during entity creation for optimal structure
- Multiple templates can be combined for comprehensive coverage

### Template Categories by Workflow
- **AI Workflow Instructions**: Used in implementation and git workflows
- **Documentation Properties**: Used in feature and project setup workflows
- **Process & Quality**: Used in bug triage and sprint planning workflows

### Custom Template Integration
- Workflows can incorporate custom templates created for specific project needs
- Project-specific templates enhance workflow standardization
- Template creation is guided by workflow requirements

This comprehensive workflow system ensures that complex project management scenarios are handled consistently, efficiently, and with AI-optimized guidance.