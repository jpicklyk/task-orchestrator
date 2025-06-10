---
layout: default
title: Templates
---

# Template System

The MCP Task Orchestrator includes a comprehensive template system with **9 built-in templates** organized into 3 strategic categories. Templates provide reusable patterns for structuring task and feature documentation, ensuring consistency and completeness across your project management workflows.

## Table of Contents

- [Overview](#overview)
- [Template Categories](#template-categories)
  - [AI Workflow Instructions](#ai-workflow-instructions)
  - [Documentation Properties](#documentation-properties)
  - [Process & Quality](#process--quality)
- [Built-in Templates](#built-in-templates)
- [Using Templates](#using-templates)
- [Template Best Practices](#template-best-practices)
- [Custom Templates](#custom-templates)

---

## Overview

### What Are Templates?

Templates are predefined documentation structures that can be applied to tasks, features, and projects to create consistent, comprehensive documentation. Each template contains:

- **Section Definitions**: Structured content blocks with specific purposes
- **Usage Descriptions**: Guidance for AI assistants on how to use each section
- **Content Formats**: Support for Markdown, plain text, JSON, and code
- **Ordering**: Logical sequence for optimal information flow

### Benefits

- **Consistency**: Standardized documentation across all work items
- **Completeness**: Ensures all critical aspects are covered
- **AI-Optimized**: Designed for AI assistant interaction and understanding
- **Flexible**: Can be applied individually or in combination
- **Extensible**: Support for custom templates for specific needs

### Template Application

Templates can be applied:
- **During Creation**: Using `templateIds` parameter in `create_task` or `create_feature`
- **After Creation**: Using the `apply_template` tool
- **In Combination**: Multiple templates can be applied to the same entity

---

## Template Categories

The 9 built-in templates are organized into 3 strategic categories, each serving different aspects of project management:

### AI Workflow Instructions

These templates provide step-by-step process guidance with MCP tool integration, designed to automate and standardize complex workflows.

**Purpose**: Process automation and workflow standardization  
**Target Entities**: Primarily tasks, can be applied to features for workflow coordination  
**Integration**: Direct MCP tool calls and automation guidance

### Documentation Properties

These templates capture essential information about work items, providing comprehensive context and requirements documentation.

**Purpose**: Information capture and context preservation  
**Target Entities**: Both tasks and features, with feature-level emphasis  
**Integration**: Business context, technical requirements, and architectural decisions

### Process & Quality

These templates establish standards and completion criteria, ensuring quality gates and process adherence.

**Purpose**: Quality assurance and completion standards  
**Target Entities**: Primarily tasks, with feature-level quality coordination  
**Integration**: Testing strategies, acceptance criteria, and handoff requirements

---

## Built-in Templates

### AI Workflow Instructions

#### Local Git Branching Workflow
**Target**: Tasks  
**Purpose**: Step-by-step git operations and branch management

**Sections**:
1. **Branch Creation and Setup**
   - Naming conventions based on task type and scope
   - Git commands for branch creation and switching
   - Local environment setup verification

2. **Development Workflow**
   - Incremental commit strategies
   - Commit message conventions
   - Code quality checks and validation

3. **Integration and Completion**
   - Branch merging procedures
   - Conflict resolution approaches
   - Clean-up and maintenance tasks

**Best Used For**:
- Implementation tasks requiring version control
- Feature development with git integration
- Collaborative development workflows
- Code review preparation

#### GitHub PR Workflow
**Target**: Tasks  
**Purpose**: Pull request creation and management using GitHub MCP tools

**Sections**:
1. **PR Preparation**
   - Code review readiness checklist
   - Documentation update requirements
   - Testing verification steps

2. **PR Creation Process**
   - GitHub MCP tool integration
   - PR description templates
   - Reviewer assignment strategies

3. **Review and Merge Management**
   - Feedback incorporation process
   - CI/CD integration points
   - Post-merge verification

**Best Used For**:
- Feature completion and deployment
- Code review coordination
- GitHub-integrated development workflows
- Team collaboration on implementation

#### Task Implementation Workflow
**Target**: Tasks  
**Purpose**: Systematic approach for implementing tasks

**Sections**:
1. **Implementation Planning**
   - Technical approach validation
   - Resource and dependency verification
   - Risk assessment and mitigation

2. **Development Execution**
   - Incremental implementation strategy
   - Testing during development
   - Progress tracking and validation

3. **Completion and Validation**
   - Acceptance criteria verification
   - Quality gate confirmation
   - Handoff and documentation finalization

**Best Used For**:
- Complex implementation tasks
- New feature development
- System integration work
- Quality-critical implementations

#### Bug Investigation Workflow
**Target**: Tasks  
**Purpose**: Structured debugging and bug resolution process

**Sections**:
1. **Problem Analysis**
   - Symptom documentation and categorization
   - Reproduction steps and environment details
   - Impact assessment and user workflow analysis

2. **Technical Investigation**
   - Root cause analysis methodology
   - Code and configuration review
   - Testing and verification approaches

3. **Resolution Strategy**
   - Fix implementation planning
   - Testing and validation requirements
   - Deployment and monitoring considerations

**Best Used For**:
- Bug investigation and resolution
- System troubleshooting
- Performance issue analysis
- Quality assurance workflows

### Documentation Properties

#### Technical Approach
**Target**: Tasks and Features  
**Purpose**: Architecture decisions and implementation strategy

**Sections**:
1. **Architecture Overview**
   - High-level technical approach
   - Key architectural decisions and rationale
   - Technology stack and integration points

2. **Implementation Strategy**
   - Development approach and methodology
   - Resource requirements and timeline
   - Risk factors and mitigation strategies

3. **Integration Considerations**
   - System integration requirements
   - API and interface specifications
   - Testing and validation approaches

**Best Used For**:
- Technical planning and design
- Architecture documentation
- Implementation guidance
- Technical decision tracking

#### Requirements Specification
**Target**: Features (primarily), Tasks  
**Purpose**: Functional and non-functional requirements

**Sections**:
1. **Functional Requirements**
   - User stories and use cases
   - Feature specifications and behavior
   - Acceptance criteria and success metrics

2. **Non-Functional Requirements**
   - Performance, scalability, and reliability requirements
   - Security and compliance considerations
   - Usability and accessibility standards

3. **Constraints and Dependencies**
   - Technical constraints and limitations
   - External dependencies and integrations
   - Timeline and resource constraints

**Best Used For**:
- Feature planning and scoping
- Requirements documentation
- Stakeholder communication
- Project specification

#### Context & Background
**Target**: Features (primarily), Projects  
**Purpose**: Business context and stakeholder information

**Sections**:
1. **Business Context**
   - Problem statement and business need
   - Target users and stakeholder analysis
   - Business value and impact assessment

2. **Project Background**
   - Historical context and previous work
   - Related initiatives and dependencies
   - Strategic alignment and objectives

3. **Success Criteria**
   - Key performance indicators
   - Success metrics and measurement
   - Definition of completion and value delivery

**Best Used For**:
- Project initiation and planning
- Stakeholder communication
- Business case documentation
- Strategic alignment verification

### Process & Quality

#### Testing Strategy
**Target**: Tasks and Features  
**Purpose**: Comprehensive testing approach and quality gates

**Sections**:
1. **Testing Approach**
   - Testing methodology and strategy
   - Test coverage requirements and metrics
   - Testing tools and automation framework

2. **Test Planning**
   - Test case design and organization
   - Test environment setup and configuration
   - Test data management and preparation

3. **Quality Assurance**
   - Quality gates and acceptance criteria
   - Defect management and resolution
   - Performance and reliability testing

**Best Used For**:
- Quality assurance planning
- Test strategy documentation
- QA workflow standardization
- Quality gate establishment

#### Definition of Done
**Target**: Tasks and Features  
**Purpose**: Completion criteria and handoff requirements

**Sections**:
1. **Completion Criteria**
   - Functional completion requirements
   - Quality standards and verification
   - Documentation and communication requirements

2. **Acceptance Guidelines**
   - Review and approval processes
   - Stakeholder sign-off requirements
   - Deployment and release criteria

3. **Handoff Requirements**
   - Knowledge transfer and documentation
   - Support and maintenance considerations
   - Post-completion monitoring and validation

**Best Used For**:
- Work completion standardization
- Quality gate enforcement
- Handoff process clarity
- Acceptance criteria definition

---

## Using Templates

### Template Discovery

Use the `list_templates` tool to discover available templates:

```json
{
  "targetEntityType": "TASK",
  "isEnabled": true
}
```

### Single Template Application

Apply one template during entity creation:

```json
{
  "title": "Implement User Authentication",
  "summary": "Create secure authentication system with OAuth 2.0",
  "templateIds": ["technical-approach-uuid"]
}
```

### Multiple Template Application

Combine templates for comprehensive coverage:

```json
{
  "name": "User Management Feature",
  "summary": "Complete user management functionality",
  "templateIds": [
    "context-background-uuid",
    "requirements-specification-uuid",
    "technical-approach-uuid"
  ]
}
```

### Post-Creation Template Application

Apply templates to existing entities:

```json
{
  "entityType": "TASK",
  "entityId": "task-uuid",
  "templateIds": ["testing-strategy-uuid"]
}
```

### Template Selection Strategy

#### For Implementation Tasks
**Recommended Combination**:
- Technical Approach + Task Implementation Workflow + Testing Strategy

**Rationale**: Provides technical guidance, process structure, and quality assurance

#### For Bug Investigation
**Recommended Combination**:
- Bug Investigation Workflow + Technical Approach + Definition of Done

**Rationale**: Structured investigation process with technical analysis and clear completion criteria

#### For Feature Planning
**Recommended Combination**:
- Context & Background + Requirements Specification + Testing Strategy

**Rationale**: Business context, comprehensive requirements, and quality planning

#### For Complex Features
**Recommended Combination**:
- Requirements Specification + Technical Approach + Local Git Branching + Testing Strategy

**Rationale**: Complete coverage from requirements through implementation and quality assurance

---

## Template Best Practices

### Template Selection Guidelines

1. **Match Work Type**: Choose templates that align with the nature of your work
2. **Consider Audience**: Select templates based on who will consume the documentation
3. **Plan for Lifecycle**: Include templates that cover the entire work lifecycle
4. **Avoid Redundancy**: Don't apply templates with overlapping content unnecessarily

### Effective Template Combinations

**Workflow + Documentation Pattern**:
- Pair workflow templates (AI Workflow Instructions) with documentation templates (Documentation Properties)
- Example: Task Implementation Workflow + Technical Approach

**Context + Process Pattern**:
- Combine context templates with process templates for comprehensive coverage
- Example: Context & Background + Definition of Done

**Quality-Focused Pattern**:
- Emphasize quality and testing for critical work
- Example: Technical Approach + Testing Strategy + Definition of Done

### Template Customization

While built-in templates provide comprehensive coverage, you can:

1. **Create Custom Templates**: For organization-specific patterns
2. **Extend Existing Templates**: Add sections to existing templates
3. **Modify Content**: Update template content to match your context
4. **Disable Unused Templates**: Focus on templates that match your workflow

### Content Quality Guidelines

When working with template-generated sections:

1. **Complete All Sections**: Don't leave template sections empty
2. **Be Specific**: Replace generic template content with specific information
3. **Maintain Consistency**: Use templates consistently across similar work
4. **Update Regularly**: Keep template-generated content current as work progresses

---

## Custom Templates

### When to Create Custom Templates

- **Organization-Specific Processes**: Unique workflows or documentation requirements
- **Domain-Specific Needs**: Industry or technology-specific documentation patterns
- **Team Preferences**: Customized structures that match team working styles
- **Compliance Requirements**: Templates that ensure regulatory or policy compliance

### Creating Custom Templates

Use the `create_template` tool:

```json
{
  "name": "Security Review Template",
  "description": "Comprehensive security review and validation template",
  "targetEntityType": "TASK",
  "tags": "security,compliance,review"
}
```

### Adding Sections to Custom Templates

Use the `add_template_section` tool:

```json
{
  "templateId": "custom-template-uuid",
  "title": "Security Assessment",
  "usageDescription": "Document security considerations and validation steps",
  "contentSample": "## Security Considerations\n\n1. Authentication requirements\n2. Data protection measures\n3. Access control verification",
  "ordinal": 0
}
```

### Custom Template Best Practices

1. **Follow Naming Conventions**: Use clear, descriptive names
2. **Provide Usage Guidance**: Include helpful usage descriptions for AI assistants
3. **Structure Logically**: Order sections in a logical workflow sequence
4. **Test Thoroughly**: Validate custom templates with real work scenarios
5. **Document Purpose**: Clearly explain when and why to use custom templates

### Template Management

- **Enable/Disable**: Control template availability without deletion
- **Version Control**: Track template changes and improvements
- **Team Distribution**: Share effective custom templates across teams
- **Maintenance**: Regularly review and update template content

---

## Template Integration with Workflows

Templates are designed to work seamlessly with workflow prompts:

### Workflow-Template Relationships

- **create_feature_workflow**: Suggests Context & Background + Requirements Specification
- **task_breakdown_workflow**: Recommends Task Implementation Workflow
- **bug_triage_workflow**: Integrates Bug Investigation Workflow
- **sprint_planning_workflow**: Utilizes Testing Strategy + Definition of Done
- **project_setup_workflow**: Combines multiple templates for comprehensive setup

### Automated Template Application

Workflow prompts automatically suggest and apply appropriate templates based on:
- Work type and complexity
- Entity type (task vs feature)
- Process requirements
- Quality standards

### Template Evolution

Templates evolve based on:
- Usage patterns and feedback
- Process improvements and optimization
- New tool integrations and capabilities
- Team needs and workflow changes

This comprehensive template system ensures that all work items have structured, consistent documentation while providing the flexibility to adapt to specific needs and workflows.