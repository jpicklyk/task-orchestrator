# Common Use Cases

This page documents the most common ways teams and individuals use MCP Task Orchestrator, with specific examples and recommended approaches.

## Software Development

### Feature Development Lifecycle

**Scenario**: Building a new user authentication system

**Workflow**:
1. **Project Setup**: Create project "User Management System"
2. **Feature Creation**: Use `create_feature_workflow` for "User Authentication"
3. **Task Breakdown**: Use `task_breakdown_workflow` to split into:
   - Database schema design
   - API endpoint implementation
   - Frontend integration
   - Security testing
   - Documentation

**Templates Applied**:
- `context-background` for business requirements
- `technical-approach` for architecture decisions
- `testing-strategy` for quality assurance

**Example Commands**:
```
"Create a project for our user management system"
"Use create_feature_workflow to set up user authentication"
"Break down the API implementation task using task_breakdown_workflow"
"Apply technical approach and testing strategy templates to the database task"
```

### Bug Triage and Resolution

**Scenario**: Managing incoming bug reports and systematic resolution

**Workflow**:
1. **Triage**: Use `bug_triage_workflow` for each new bug
2. **Investigation**: Apply `bug-investigation-workflow` template
3. **Classification**: Tag with severity and component
4. **Resolution**: Create fix tasks with dependencies

**Tagging Strategy**:
- Severity: `severity-critical`, `severity-high`, `severity-medium`, `severity-low`
- Component: `component-frontend`, `component-backend`, `component-database`
- Type: `bug-type-regression`, `bug-type-performance`, `bug-type-security`

**Example Commands**:
```
"Use bug_triage_workflow for the login timeout issue"
"Create a task to investigate the payment processing failure"
"Show me all high-severity bugs in the frontend component"
"Update the database connection bug to completed status"
```

### Code Review and Quality Assurance

**Scenario**: Ensuring code quality through structured review processes

**Workflow**:
1. **PR Preparation**: Use `github-pr-workflow` template
2. **Review Tasks**: Create tasks for code review activities
3. **Quality Gates**: Use `definition-of-done` template
4. **Follow-up**: Track and resolve review feedback

**Custom Templates**:
- "Code Review Checklist" with security, performance, and style checks
- "QA Testing Plan" with test case definitions and coverage requirements

## Project Management

### Sprint Planning

**Scenario**: 2-week development sprints with mixed feature and maintenance work

**Workflow**:
1. **Backlog Review**: Use `search_tasks` to analyze pending work
2. **Sprint Planning**: Apply `sprint_planning_workflow`
3. **Capacity Planning**: Balance complexity across team members
4. **Daily Tracking**: Monitor progress with status queries

**Sprint Setup Example**:
```
"Use sprint_planning_workflow to organize our next 2-week sprint"
"Show me all pending tasks with high priority"
"Create dependencies between the database migration and API updates"
"Tag all selected tasks with sprint-24 and assign team members"
```

**Daily Standup Queries**:
```
"Show me all in-progress tasks for sprint-24"
"List any tasks blocked by dependencies"
"What tasks were completed yesterday?"
```

### Release Management

**Scenario**: Coordinating feature releases across multiple teams

**Workflow**:
1. **Release Planning**: Create features for each release component
2. **Cross-team Dependencies**: Model dependencies between teams
3. **Quality Gates**: Ensure all features meet release criteria
4. **Release Tracking**: Monitor progress toward release goals

**Release Organization**:
- **Project**: "Q2 2024 Release"
- **Features**: "Mobile App Updates", "API v2.0", "Infrastructure Scaling"
- **Dependencies**: API changes BLOCK mobile app integration

## Research and Development

### Technical Research Projects

**Scenario**: Evaluating new technologies or approaches

**Workflow**:
1. **Research Planning**: Create project with research objectives
2. **Investigation Tasks**: Break down research into specific areas
3. **Proof of Concept**: Create implementation tasks
4. **Documentation**: Capture findings and recommendations

**Research Template Sections**:
- Background and objectives
- Methodology and approach
- Findings and analysis
- Recommendations and next steps

**Example Commands**:
```
"Create a project to evaluate microservices architecture"
"Break down the container orchestration research into specific tasks"
"Apply the technical approach template to the proof of concept task"
"Document our findings on database performance in the research sections"
```

### Experimental Features

**Scenario**: Building and testing experimental functionality

**Workflow**:
1. **Hypothesis Definition**: Clear problem statement and expected outcomes
2. **Experiment Design**: Structured approach to testing
3. **Implementation**: Incremental development with checkpoints
4. **Analysis**: Data collection and evaluation

**Experiment Tracking**:
- Tag experiments with `experiment-[name]`
- Use complexity ratings to estimate effort
- Create dependencies for sequential experiments
- Apply `testing-strategy` template for validation

## Personal Productivity

### Individual Task Management

**Scenario**: Solo developer managing multiple personal projects

**Workflow**:
1. **Project Organization**: Separate projects for different initiatives
2. **Priority Management**: Use priority levels to focus effort
3. **Progress Tracking**: Regular reviews of completed work
4. **Context Switching**: Organized task resumption

**Personal Productivity Tips**:
- Use `get_overview` daily to review current state
- Tag tasks by energy level: `energy-high`, `energy-medium`, `energy-low`
- Create dependencies to sequence work logically
- Apply templates consistently for knowledge retention

**Example Daily Workflow**:
```
"Show me today's overview of all my projects"
"What high-priority tasks are pending?"
"Update the blog redesign task to in-progress"
"Create a task to review this week's completed work"
```

### Learning and Skill Development

**Scenario**: Structured approach to learning new technologies

**Workflow**:
1. **Learning Goals**: Create project for skill development
2. **Curriculum Planning**: Break learning into specific topics
3. **Practice Projects**: Apply knowledge through implementation
4. **Progress Tracking**: Monitor learning milestones

**Learning Project Structure**:
- **Project**: "React Native Development"
- **Features**: "Core Concepts", "Navigation", "State Management", "API Integration"
- **Tasks**: Specific tutorials, exercises, and practice projects

## Content Creation

### Documentation Projects

**Scenario**: Creating comprehensive technical documentation

**Workflow**:
1. **Content Planning**: Outline documentation structure
2. **Writing Tasks**: Individual sections or topics
3. **Review Process**: Structured editing and feedback
4. **Publication**: Final review and release

**Documentation Templates**:
- "Technical Writing Template" with structure guidelines
- "Review Checklist Template" for quality assurance
- "Publication Template" with final steps

### Blog and Article Management

**Scenario**: Managing a technical blog with regular content

**Workflow**:
1. **Content Calendar**: Plan topics and publication schedule
2. **Research Tasks**: Information gathering and fact-checking
3. **Writing Process**: Structured writing and editing
4. **Promotion**: Social media and community sharing

**Content Lifecycle**:
- Idea → Research → Outline → Draft → Edit → Publish → Promote

## Team Collaboration

### Cross-functional Project Teams

**Scenario**: Product team with designers, developers, and product managers

**Workflow**:
1. **Unified Project**: Single project for entire initiative
2. **Role-based Features**: Features aligned with disciplines
3. **Dependency Management**: Model handoffs between roles
4. **Communication**: Shared understanding through documentation

**Role-based Tagging**:
- `role-design`, `role-frontend`, `role-backend`, `role-qa`, `role-product`
- `phase-discovery`, `phase-design`, `phase-development`, `phase-testing`

### Remote Team Coordination

**Scenario**: Distributed team across multiple time zones

**Workflow**:
1. **Async Communication**: Rich task documentation
2. **Status Transparency**: Clear progress indicators
3. **Handoff Procedures**: Detailed completion criteria
4. **Time Zone Coordination**: Dependencies that respect work schedules

**Remote Team Best Practices**:
- Use detailed task summaries for context
- Apply `definition-of-done` template consistently
- Create clear handoff criteria in task sections
- Tag tasks with time zone considerations

## Consulting and Client Work

### Client Project Management

**Scenario**: Managing multiple client projects with different requirements

**Workflow**:
1. **Client Separation**: Separate projects for each client
2. **Billing Tracking**: Tags for time tracking and billing
3. **Scope Management**: Clear feature boundaries
4. **Communication**: Client-friendly status reports

**Client Project Organization**:
- Tag structure: `client-[name]`, `billable`, `internal`
- Priority levels aligned with client commitments
- Dependencies to model client approval gates
- Templates for consistent deliverable quality

### Proposal and Estimation

**Scenario**: Creating project proposals with accurate estimates

**Workflow**:
1. **Requirements Gathering**: Use requirements templates
2. **Work Breakdown**: Detailed task analysis
3. **Effort Estimation**: Complexity-based estimation
4. **Proposal Generation**: Convert tasks to client proposals

**Estimation Process**:
- Break client requirements into detailed tasks
- Use complexity ratings (1-10) for effort estimation
- Create dependencies to model project timeline
- Generate effort summaries from task complexity

## Integration Patterns

### CI/CD Pipeline Integration

**Scenario**: Automating task updates based on code changes

**Integration Points**:
- PR creation updates task status to "in-progress"
- PR merge moves task to "completed"
- Failed builds create bug investigation tasks
- Deployment success triggers testing tasks

### External Tool Synchronization

**Scenario**: Keeping task orchestrator aligned with other project tools

**Common Integrations**:
- JIRA epic and story synchronization
- GitHub issue and milestone tracking
- Slack notifications for status changes
- Calendar integration for deadline tracking

## Scaling Patterns

### Small Team (2-5 people)

**Characteristics**:
- Shared understanding of work
- Informal communication
- Flexible processes

**Recommended Approach**:
- Single project with features for major work areas
- Consistent tagging for work type
- Weekly planning sessions
- Template usage for knowledge sharing

### Medium Team (6-20 people)

**Characteristics**:
- Multiple specializations
- Need for coordination
- Process standardization

**Recommended Approach**:
- Multiple projects for different products/areas
- Role-based and team-based tagging
- Dependency management for coordination
- Custom templates for team-specific needs

### Large Team (20+ people)

**Characteristics**:
- Complex interdependencies
- Formal processes
- Multiple stakeholder groups

**Recommended Approach**:
- Hierarchical project organization
- Standardized templates and workflows
- Integration with enterprise tools
- Regular dependency review processes

---

## Contributing Use Cases

To add your use case to this page:

1. **Describe the Scenario**: What problem are you solving?
2. **Document the Workflow**: Step-by-step process
3. **Include Examples**: Specific commands or configurations
4. **Share Lessons Learned**: What works well, what to avoid
5. **Provide Context**: Team size, industry, project type

Use the existing sections as templates for formatting and level of detail.