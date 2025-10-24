# Task Orchestrator - Workflow State Diagram

**Last Updated**: 2025-01-24
**Version**: v2.0 (Event-Driven Status Progression)

This document provides comprehensive state diagrams showing how the Task Orchestrator system manages features, tasks, and workflows through Skills and Subagents.

**Key v2.0 Update**: Status transitions are now **event-driven** and **config-agnostic**. Skills detect universal workflow events (e.g., `first_task_started`, `all_tasks_complete`) and delegate to Status Progression Skill, which reads the user's `config.yaml` to determine the next status. The diagrams below show the **default_flow** as an example, but actual flows are determined by user configuration and entity tags.

## High-Level Architecture

```mermaid
graph TB
    User[User Request] --> Orchestrator[Task Orchestrator<br/>Output Style]
    Orchestrator --> DecisionGate{Request Type?}

    DecisionGate -->|Coordination| Skills[Skills Layer<br/>Lightweight 300-800 tokens]
    DecisionGate -->|Implementation| AskUser{Ask User:<br/>Direct vs Specialist?}
    DecisionGate -->|Information| DirectResponse[Direct Response]

    AskUser -->|Direct Work| DirectImpl[Orchestrator Does Work<br/>+ Manages Lifecycle]
    AskUser -->|Specialist| Subagents[Subagent Layer<br/>Complex 1500-2200 tokens]

    Skills --> SkillTypes[Feature Orchestration<br/>Task Orchestration<br/>Status Progression<br/>Dependency Analysis]

    Subagents --> SubagentTypes[Feature Architect Opus<br/>Planning Specialist Sonnet<br/>Implementation Specialist Haiku<br/>Senior Engineer Sonnet]

    SkillTypes --> Tools[MCP Tools]
    SubagentTypes --> Tools
    DirectImpl --> Tools

    Tools --> Database[(SQLite Database)]

    style Skills fill:#e1f5e1
    style Subagents fill:#e1e5f5
    style DecisionGate fill:#fff3cd
    style AskUser fill:#fff3cd
```

## Entity Status Lifecycles

### Feature Lifecycle (default_flow example)

**Note**: This shows `default_flow` as configured in `.taskorchestrator/config.yaml`. Actual flow depends on feature tags and user configuration (e.g., `rapid_prototype_flow` skips testing, `with_review_flow` adds pending-review status).

```mermaid
stateDiagram-v2
    [*] --> draft: Feature Created

    draft --> planning: Status Progression Skill<br/>(manual or auto)

    planning --> in_development: Event: first_task_started<br/>(delegated to Status Progression Skill)

    in_development --> testing: Event: all_tasks_complete<br/>(delegated to Status Progression Skill)
    in_development --> in_development: Tasks In Progress

    testing --> validating: Event: tests_passed<br/>(delegated to Status Progression Skill)
    testing --> in_development: Event: tests_failed<br/>(backward if allow_backward: true)

    validating --> completed: Event: completion_requested<br/>(delegated to Status Progression Skill)
    validating --> in_development: Issues Found<br/>(backward if allow_backward: true)

    completed --> [*]

    note right of draft
        Initial Status:
        ✅ Feature created with name
        ✅ Can add description/sections
        ❌ No tasks required yet
    end note

    note right of planning
        Prerequisites (config-driven):
        ✅ Feature created
        ❌ No task requirement yet
        (Optional: planning sections populated)
    end note

    note right of in_development
        Prerequisites (enforced by StatusValidator):
        ✅ ≥1 task created
        ❌ Blocked if no tasks

        Event Detection:
        first_task_started triggered when
        taskCounts.byStatus["in-progress"] == 1
    end note

    note right of testing
        Prerequisites (enforced by StatusValidator):
        ✅ All tasks completed/cancelled
        ❌ Blocked if any task incomplete

        Event Detection:
        all_tasks_complete triggered when
        pending == 0 && in-progress == 0
    end note

    note right of completed
        Prerequisites (enforced by StatusValidator):
        ✅ All tasks completed/cancelled
        ✅ Tests passed (if quality gates enabled)
        ❌ Blocked if validation fails

        Event: completion_requested
        User asks to complete or all validation passed
    end note
```

### Task Lifecycle (default_flow example)

**Note**: This shows `default_flow` as configured in `.taskorchestrator/config.yaml`. Actual flow depends on task tags and user configuration (e.g., `with_review_flow` adds in-review status, `documentation_flow` may skip testing).

```mermaid
stateDiagram-v2
    [*] --> backlog: Task Created

    backlog --> in_progress: Event: work_started<br/>(delegated to Status Progression Skill)

    in_progress --> testing: Event: implementation_complete<br/>(delegated to Status Progression Skill)
    in_progress --> blocked: Event: blocker_detected<br/>(emergency transition)

    testing --> completed: Event: tests_passed<br/>(delegated to Status Progression Skill)
    testing --> in_progress: Event: tests_failed<br/>(backward if allow_backward: true)

    blocked --> in_progress: Blocker Resolved<br/>(resume work)
    blocked --> cancelled: Task No Longer Needed<br/>(emergency transition)

    backlog --> cancelled: Task Not Needed<br/>(emergency transition)
    in_progress --> cancelled: Scope Change<br/>(emergency transition)

    completed --> [*]
    cancelled --> [*]

    note right of backlog
        Initial Status:
        ✅ Task created with title
        ✅ Feature assigned (optional)
        ✅ Can be prioritized/organized
        ❌ No blockers checked yet
    end note

    note right of in_progress
        Prerequisites (enforced by StatusValidator):
        ✅ All BLOCKS dependencies completed
        ❌ Blocked if any blocker incomplete

        Event Detection:
        work_started triggered when
        specialist begins implementation
    end note

    note right of testing
        Prerequisites (config-driven):
        ✅ Summary populated (300-500 chars)
        ✅ Sections updated by specialist

        Event Detection:
        implementation_complete triggered when
        summary length valid && sections updated
    end note

    note right of completed
        Prerequisites (enforced by StatusValidator):
        ✅ Summary 300-500 characters
        ✅ No incomplete BLOCKS dependencies
        ❌ Blocked if summary missing/wrong length
        ❌ Blocked if blockers incomplete

        Event: tests_passed or completion_requested
        Flow determines if testing required
    end note
```

### Project Lifecycle

```mermaid
stateDiagram-v2
    [*] --> planning: Project Created

    planning --> active: Features In Progress

    active --> completed: All Features Complete
    active --> active: Features In Progress

    completed --> [*]

    note right of planning
        Prerequisites:
        ✅ Project created with name
        ❌ No features required yet
    end note

    note right of completed
        Prerequisites:
        ✅ All features completed
        ❌ Blocked if any feature incomplete
    end note
```

## Skill Coordination Workflows

### Feature Orchestration Skill Flow

```mermaid
flowchart TD
    Start([User Request:<br/>Feature Work]) --> AssessComplexity{Assess<br/>Complexity}

    AssessComplexity -->|Simple<br/>&lt; 200 chars<br/>&lt; 3 tasks| CreateDirect[Create Feature Directly<br/>+ 2-3 Basic Tasks]
    AssessComplexity -->|Complex<br/>&gt; 200 chars<br/>&gt; 5 tasks| LaunchArchitect[Recommend:<br/>Launch Feature Architect<br/>Opus]

    CreateDirect --> CheckProgress{Check<br/>Progress}
    LaunchArchitect --> ArchitectComplete[Architect Creates<br/>Structured Feature]
    ArchitectComplete --> CheckProgress

    CheckProgress -->|First Task Started| AutoInDev[Auto Move to<br/>in-development]
    CheckProgress -->|All Tasks Complete| AutoTesting[Auto Move to<br/>testing]
    CheckProgress -->|Tests Pass| AskComplete{Ask User:<br/>Mark Complete?}

    AskComplete -->|Yes| Complete[Mark Feature<br/>completed]
    AskComplete -->|No| Wait[Wait for Approval]

    AutoInDev --> Monitor[Monitor Task<br/>Progress]
    AutoTesting --> RunTests[Trigger Tests<br/>via hooks]
    RunTests --> CheckProgress
    Monitor --> CheckProgress

    Complete --> End([Feature Complete])

    style CreateDirect fill:#e1f5e1
    style LaunchArchitect fill:#e1e5f5
    style AutoInDev fill:#ffe1a8
    style AutoTesting fill:#ffe1a8
    style AskComplete fill:#fff3cd
```

### Task Orchestration Skill Flow

```mermaid
flowchart TD
    Start([User Request:<br/>Execute Tasks]) --> GetTasks[Query All Tasks<br/>in Feature]

    GetTasks --> AnalyzeDeps[Analyze Dependencies<br/>for Each Task]

    AnalyzeDeps --> CreateBatches[Create Execution<br/>Batches by Dependency Level]

    CreateBatches --> Batch1{Batch 1<br/>Parallel?}

    Batch1 -->|Yes| LaunchParallel[Launch Multiple<br/>Specialists in Parallel]
    Batch1 -->|No| LaunchSequential[Launch Single<br/>Specialist]

    LaunchParallel --> Monitor1[Monitor Progress<br/>via query_container]
    LaunchSequential --> Monitor1

    Monitor1 --> CheckBatch{Batch<br/>Complete?}

    CheckBatch -->|No| Monitor1
    CheckBatch -->|Yes| CheckCascade{More<br/>Batches?}

    CheckCascade -->|Yes| NextBatch[Launch Next Batch]
    NextBatch --> Monitor1
    CheckCascade -->|No| FeatureCheck[Check if Feature<br/>Can Progress]

    FeatureCheck --> End([Task Execution<br/>Complete])

    style LaunchParallel fill:#e1f5e1
    style LaunchSequential fill:#e1f5e1
    style FeatureCheck fill:#ffe1a8
```

### Status Progression Skill Flow

```mermaid
flowchart TD
    Start([User Request:<br/>Status Change]) --> GetNext[Call get_next_status<br/>Read-Only Tool]

    GetNext --> Analyze{Analysis<br/>Result?}

    Analyze -->|Ready| ShowRecommendation[Show: Recommended Status<br/>Active Flow & Position<br/>Matched Tags]
    Analyze -->|Blocked| ShowBlockers[Show: Blocking Prerequisites<br/>What's Missing<br/>How to Resolve]
    Analyze -->|Terminal| ShowTerminal[Show: At Final Status<br/>No Further Progression]

    ShowRecommendation --> UserTries[User Tries Transition<br/>via manage_container]
    ShowBlockers --> UserResolves[User Resolves Blockers]
    UserResolves --> GetNext

    UserTries --> Validator{StatusValidator<br/>Write-Time Check}

    Validator -->|Pass| Success[Status Changed<br/>✅ Success]
    Validator -->|Fail| InterpretError[Interpret Error<br/>Explain Config Rules<br/>Show Fix Steps]

    InterpretError --> UserFixes[User Fixes Issues]
    UserFixes --> GetNext

    Success --> End([Status Updated])
    ShowTerminal --> End

    style GetNext fill:#e1f5e1
    style Validator fill:#ffe1a8
    style InterpretError fill:#fff3cd
```

### Dependency Analysis Skill Flow

```mermaid
flowchart TD
    Start([User Request:<br/>Dependency Analysis]) --> RequestType{Request<br/>Type?}

    RequestType -->|Find Blocked Tasks| GetAllTasks[Query All Tasks<br/>in Feature]
    RequestType -->|Analyze Chain| GetSingleTask[Query Single Task<br/>Dependencies]
    RequestType -->|Find Bottlenecks| GetAllTasks

    GetAllTasks --> CheckEach[For Each Task:<br/>query_dependencies]
    GetSingleTask --> BuildChain[Build Dependency<br/>Chain Recursively]

    CheckEach --> FilterBlocked[Filter Tasks with<br/>Incomplete Blockers]
    BuildChain --> IdentifyPath[Identify Critical<br/>Path]

    FilterBlocked --> CountImpact[Count Impact:<br/>How Many Unblocked]
    IdentifyPath --> ShowChain[Show Chain<br/>Visualization]

    CountImpact --> Prioritize[Prioritize by:<br/>Impact × Priority ÷ Complexity]

    Prioritize --> Recommend[Recommend:<br/>Which Tasks to Complete First]
    ShowChain --> Recommend

    Recommend --> End([Analysis Complete])

    style CheckEach fill:#e1f5e1
    style FilterBlocked fill:#ffe1a8
    style Prioritize fill:#ffe1a8
```

## Subagent Workflows

### Feature Architect (Opus) - Feature Design

```mermaid
flowchart TD
    Launch([Launched by:<br/>Feature Orchestration Skill]) --> ReadContext[Read User Request<br/>Identify Requirements]

    ReadContext --> DiscoverTemplates[query_templates:<br/>Find Applicable Templates]

    DiscoverTemplates --> CreateFeature[Create Feature with:<br/>- Clear name/description<br/>- Appropriate templates<br/>- Priority & tags]

    CreateFeature --> CreateSections[Create Feature Sections:<br/>- Requirements<br/>- Context & Background<br/>- Acceptance Criteria]

    CreateSections --> HandoffPlanning[Return: Feature Created<br/>Recommend Planning Specialist<br/>for Task Breakdown]

    HandoffPlanning --> End([Opus Work Complete<br/>50-100 token summary])

    style DiscoverTemplates fill:#e1e5f5
    style CreateFeature fill:#e1e5f5
    style HandoffPlanning fill:#fff3cd
```

### Planning Specialist (Sonnet) - Task Breakdown

```mermaid
flowchart TD
    Launch([Launched by:<br/>Feature Orchestration Skill]) --> ReadFeature[Read Feature via<br/>query_container]

    ReadFeature --> IdentifyDomains[Identify Domains:<br/>database, backend,<br/>frontend, testing, docs]

    IdentifyDomains --> CreateTasks[Create Domain-Isolated<br/>Tasks with Templates]

    CreateTasks --> CreateDeps[Create Dependencies<br/>via manage_dependency]

    CreateDeps --> BuildGraph[Build Execution Graph:<br/>Batches by Dependency Level]

    BuildGraph --> UpdateSections[Update Feature Sections:<br/>- Task Breakdown<br/>- Execution Graph]

    UpdateSections --> ReturnGraph[Return: Execution Graph<br/>Batch 1: [T1, T3] parallel<br/>Batch 2: [T2] depends on T1<br/>Next: Task Orchestration Skill]

    ReturnGraph --> End([Sonnet Work Complete<br/>50-100 token summary])

    style CreateTasks fill:#e1e5f5
    style CreateDeps fill:#e1e5f5
    style BuildGraph fill:#ffe1a8
    style ReturnGraph fill:#fff3cd
```

### Implementation Specialist (Haiku) - Standard Implementation

```mermaid
flowchart TD
    Launch([Launched by:<br/>Task Orchestration Skill]) --> LoadSkill{Load Domain Skill<br/>Based on Tags}

    LoadSkill -->|backend| BackendSkill[backend-implementation<br/>Skill]
    LoadSkill -->|frontend| FrontendSkill[frontend-implementation<br/>Skill]
    LoadSkill -->|database| DatabaseSkill[database-implementation<br/>Skill]
    LoadSkill -->|testing| TestingSkill[testing-implementation<br/>Skill]
    LoadSkill -->|documentation| DocsSkill[documentation-implementation<br/>Skill]

    BackendSkill --> ReadTask[Read Task via<br/>query_container<br/>includeSections=true]
    FrontendSkill --> ReadTask
    DatabaseSkill --> ReadTask
    TestingSkill --> ReadTask
    DocsSkill --> ReadTask

    ReadTask --> DoWork[Perform Implementation:<br/>Write code, tests, docs]

    DoWork --> CheckBlock{Blocked?}

    CheckBlock -->|Yes| EscalateToSenior[Escalate to<br/>Senior Engineer Sonnet<br/>Report Blocker]
    CheckBlock -->|No| UpdateSections[Update Task Sections:<br/>- Implementation Details<br/>- Files Changed]

    EscalateToSenior --> End2([Return: BLOCKED<br/>Senior Engineer handles])

    UpdateSections --> UpdateSummary[Update Task Summary<br/>300-500 characters]

    UpdateSummary --> UseStatusSkill[Use Status Progression Skill<br/>Mark Task Complete]

    UseStatusSkill --> ValidatePrereqs{Prerequisites<br/>Met?}

    ValidatePrereqs -->|No| FixIssues[Fix Issues:<br/>Add/adjust summary,<br/>complete dependencies]
    ValidatePrereqs -->|Yes| MarkComplete[Task Marked<br/>completed ✅]

    FixIssues --> UpdateSummary

    MarkComplete --> End([Return: Brief Summary<br/>50-100 tokens])

    style LoadSkill fill:#e1f5e1
    style DoWork fill:#e1e5f5
    style UseStatusSkill fill:#e1f5e1
    style ValidatePrereqs fill:#ffe1a8
    style EscalateToSenior fill:#fff3cd
```

### Senior Engineer (Sonnet) - Complex Problem Solving

```mermaid
flowchart TD
    Launch([Launched by:<br/>Implementation Specialist<br/>or Task Orchestration]) --> ReadContext[Read Task + Blocker<br/>Context via query_container]

    ReadContext --> AnalyzeProblem[Analyze Problem:<br/>- Debug issue<br/>- Investigate error<br/>- Explore codebase]

    AnalyzeProblem --> DetermineApproach{Can<br/>Resolve?}

    DetermineApproach -->|Yes| ImplementFix[Implement Solution:<br/>- Fix bug<br/>- Optimize performance<br/>- Refactor code]
    DetermineApproach -->|No| ReportCantResolve[Report: Cannot Resolve<br/>Suggest User Action]

    ImplementFix --> UpdateSections[Update Task Sections:<br/>- Solution Details<br/>- Files Changed<br/>- Root Cause Analysis]

    ReportCantResolve --> End2([Return: BLOCKED<br/>User intervention needed])

    UpdateSections --> UpdateSummary[Update Task Summary<br/>300-500 characters]

    UpdateSummary --> UseStatusSkill[Use Status Progression Skill<br/>Mark Task Complete]

    UseStatusSkill --> End([Return: Brief Summary<br/>50-100 tokens])

    style AnalyzeProblem fill:#e1e5f5
    style ImplementFix fill:#e1e5f5
    style UseStatusSkill fill:#e1f5e1
    style ReportCantResolve fill:#fff3cd
```

## Decision Trees

### Main Orchestrator Decision Tree

```mermaid
flowchart TD
    UserRequest([User Request]) --> IdentifyType{Identify<br/>Work Type}

    IdentifyType -->|Feature lifecycle| FeatureOrch[MANDATORY:<br/>Feature Orchestration Skill]
    IdentifyType -->|Task execution| TaskOrch[MANDATORY:<br/>Task Orchestration Skill]
    IdentifyType -->|Status change| StatusProg[MANDATORY:<br/>Status Progression Skill]
    IdentifyType -->|Dependency check| DepAnalysis[MANDATORY:<br/>Dependency Analysis Skill]
    IdentifyType -->|Implementation| AskUser{ASK USER:<br/>Direct vs Specialist?}
    IdentifyType -->|Information| DirectResponse[Direct Response]

    AskUser -->|Direct| DirectWork[Orchestrator Works Directly<br/>Then Manages Lifecycle]
    AskUser -->|Specialist| RouteToSpecialist[Route via Skill<br/>to Subagent]

    DirectWork --> ManageLifecycle[MUST:<br/>1. Use Status Progression Skill<br/>2. Populate summary 300-500 chars<br/>3. Create Files Changed section<br/>4. Use Status Progression Skill to complete]

    RouteToSpecialist --> SpecialistManages[Specialist Manages<br/>Own Lifecycle]

    FeatureOrch --> SkillOutput[Skill Returns<br/>300-800 tokens]
    TaskOrch --> SkillOutput
    StatusProg --> SkillOutput
    DepAnalysis --> SkillOutput
    ManageLifecycle --> Done1([Work Complete])
    SpecialistManages --> VerifyComplete[Verify Specialist<br/>Completed Task]
    VerifyComplete --> Done2([Work Complete])
    SkillOutput --> Done3([Coordination Complete])
    DirectResponse --> Done4([Response Complete])

    style FeatureOrch fill:#e1f5e1
    style TaskOrch fill:#e1f5e1
    style StatusProg fill:#e1f5e1
    style DepAnalysis fill:#e1f5e1
    style AskUser fill:#fff3cd
    style ManageLifecycle fill:#ffe1a8
```

### Feature Creation Decision Tree

```mermaid
flowchart TD
    Request([Create Feature Request]) --> FeatureSkill[Feature Orchestration Skill<br/>MANDATORY]

    FeatureSkill --> Assess{Assess<br/>Complexity}

    Assess -->|Simple| CheckIndicators{Check Indicators}
    Assess -->|Complex| LaunchArchitect[Launch Feature Architect<br/>Opus]

    CheckIndicators -->|Request &lt; 200 chars<br/>Clear purpose<br/>Expected tasks &lt; 3| CreateSimple[Create Feature Directly:<br/>1. query_templates<br/>2. Create with templates<br/>3. Create 2-3 tasks]

    CheckIndicators -->|Request &gt; 200 chars<br/>Multiple components<br/>Expected tasks ≥ 5| LaunchArchitect

    CreateSimple --> Done1([Simple Feature Created<br/>Ready for Execution])

    LaunchArchitect --> ArchitectWork[Architect:<br/>1. Formalizes requirements<br/>2. Discovers templates<br/>3. Creates structured feature]

    ArchitectWork --> HandoffPlanning[Handoff to<br/>Planning Specialist<br/>Sonnet]

    HandoffPlanning --> PlanningWork[Planning Specialist:<br/>1. Breaks into domain tasks<br/>2. Creates dependencies<br/>3. Builds execution graph]

    PlanningWork --> Done2([Complex Feature Created<br/>With Execution Graph<br/>Ready for Task Orchestration])

    style FeatureSkill fill:#e1f5e1
    style CreateSimple fill:#e1f5e1
    style LaunchArchitect fill:#e1e5f5
    style ArchitectWork fill:#e1e5f5
    style PlanningWork fill:#e1e5f5
```

### Task Execution Decision Tree

```mermaid
flowchart TD
    Request([Execute Tasks Request]) --> TaskSkill[Task Orchestration Skill<br/>MANDATORY]

    TaskSkill --> AnalyzeDeps[Analyze Dependencies:<br/>1. Get all pending tasks<br/>2. Check dependencies<br/>3. Create batches]

    AnalyzeDeps --> Batches{Execution<br/>Strategy?}

    Batches -->|All Sequential| Sequential[Launch One at a Time:<br/>Task 1 → Task 2 → Task 3]
    Batches -->|Full Parallel| Parallel[Launch All Simultaneously:<br/>T1, T2, T3 in parallel]
    Batches -->|Hybrid Batches| Hybrid[Batch 1: T1, T3 parallel<br/>Batch 2: T2 depends on T1<br/>Batch 3: T4 depends on T2, T3]

    Sequential --> RouteSpecialist[For Each Task:<br/>recommend_agent<br/>Route to Specialist]
    Parallel --> RouteSpecialist
    Hybrid --> RouteSpecialist

    RouteSpecialist --> SpecialistType{Specialist<br/>Type?}

    SpecialistType -->|Standard Work<br/>70-80%| ImplHaiku[Implementation Specialist<br/>Haiku + Domain Skill<br/>4-5x faster, 1/3 cost]
    SpecialistType -->|Complex/Blocked<br/>10-20%| SeniorSonnet[Senior Engineer<br/>Sonnet<br/>Debugging, optimization]

    ImplHaiku --> CheckBlock{Implementation<br/>Blocked?}

    CheckBlock -->|Yes| EscalateToSenior[Escalate to<br/>Senior Engineer]
    CheckBlock -->|No| CompleteTask[Complete Task:<br/>Self-service lifecycle]

    EscalateToSenior --> SeniorSonnet
    SeniorSonnet --> CompleteOrReport{Can<br/>Resolve?}

    CompleteOrReport -->|Yes| CompleteTask
    CompleteOrReport -->|No| ReportBlock[Report Blocker<br/>User intervention needed]

    CompleteTask --> Cascade[Check Cascade:<br/>Newly unblocked tasks?]
    ReportBlock --> Done2([Task Blocked<br/>Awaiting Resolution])

    Cascade --> NextBatch{More<br/>Batches?}

    NextBatch -->|Yes| LaunchNext[Launch Next Batch]
    NextBatch -->|No| FeatureCheck[Check Feature Progress:<br/>All tasks complete?]

    LaunchNext --> RouteSpecialist
    FeatureCheck --> Done1([Execution Complete<br/>Check Feature Status])

    style TaskSkill fill:#e1f5e1
    style ImplHaiku fill:#e1e5f5
    style SeniorSonnet fill:#e1e5f5
    style Cascade fill:#ffe1a8
    style FeatureCheck fill:#ffe1a8
```

### Implementation Work Decision Tree

```mermaid
flowchart TD
    Request([Implementation Work Needed]) --> AskUser{ASK USER:<br/>Direct vs Specialist?}

    AskUser --> ShowOptions[Show Options:<br/>1. Direct: faster, interactive<br/>2. Specialist: structured, documented]

    ShowOptions --> UserChoice{User<br/>Chooses?}

    UserChoice -->|Direct| DirectChecklist{Check<br/>Complexity}
    UserChoice -->|Specialist| RouteViaSkill[Route via<br/>Task Orchestration Skill]

    DirectChecklist -->|Simple<br/>&lt; 5 lines<br/>Single file| OrchestratorWorks[Orchestrator Does Work:<br/>1. Make changes<br/>2. Populate summary 300-500 chars<br/>3. Create Files Changed section]

    DirectChecklist -->|Complex<br/>&gt; 50 lines<br/>Multiple files| WarnUser[Warn: This is complex<br/>Recommend specialist instead]

    WarnUser --> Confirm{User<br/>Confirms<br/>Direct?}

    Confirm -->|Yes| OrchestratorWorks
    Confirm -->|No| RouteViaSkill

    OrchestratorWorks --> UseStatusSkill[Use Status Progression Skill<br/>to Update Status]

    UseStatusSkill --> CheckPrereqs{Prerequisites<br/>Met?}

    CheckPrereqs -->|No| FixPrereqs[Fix Prerequisites:<br/>- Adjust summary length<br/>- Complete dependencies<br/>- Add required sections]
    CheckPrereqs -->|Yes| MarkComplete[Mark Complete via<br/>Status Progression Skill]

    FixPrereqs --> UseStatusSkill

    RouteViaSkill --> SpecialistWorks[Specialist:<br/>1. Reads task<br/>2. Does work<br/>3. Updates sections<br/>4. Uses Status Progression Skill<br/>5. Marks complete]

    MarkComplete --> Done1([Direct Work Complete])
    SpecialistWorks --> VerifyComplete[Verify Completion]
    VerifyComplete --> Done2([Specialist Work Complete])

    style AskUser fill:#fff3cd
    style ShowOptions fill:#fff3cd
    style OrchestratorWorks fill:#ffe1a8
    style UseStatusSkill fill:#e1f5e1
    style SpecialistWorks fill:#e1e5f5
```

## Parallel Execution Flow

### Dependency-Aware Batching

```mermaid
graph TD
    subgraph "Batch 1: Parallel Execution"
        T1[Task 1: Database Schema<br/>Implementation Specialist Haiku<br/>database-implementation Skill<br/>Status: in-progress]
        T3[Task 3: UI Components<br/>Implementation Specialist Haiku<br/>frontend-implementation Skill<br/>Status: in-progress]
    end

    subgraph "Batch 2: Sequential (depends on Batch 1)"
        T2[Task 2: Backend API<br/>Implementation Specialist Haiku<br/>backend-implementation Skill<br/>Status: pending<br/>Depends on: T1]
    end

    subgraph "Batch 3: Sequential (depends on Batch 2)"
        T4[Task 4: Integration Tests<br/>Implementation Specialist Haiku<br/>testing-implementation Skill<br/>Status: pending<br/>Depends on: T2, T3]
    end

    T1 -->|Completes| UnblockT2[Unblock Task 2]
    T3 -->|Completes| CascadeCheck{All Batch 1<br/>Complete?}

    UnblockT2 --> CascadeCheck
    CascadeCheck -->|Yes| LaunchBatch2[Launch Batch 2:<br/>Start T2]

    LaunchBatch2 --> T2
    T2 -->|Completes| UnblockT4[Unblock Task 4]

    UnblockT4 --> CheckBatch3{All dependencies<br/>for Batch 3<br/>Complete?}

    CheckBatch3 -->|Yes| LaunchBatch3[Launch Batch 3:<br/>Start T4]

    LaunchBatch3 --> T4
    T4 -->|Completes| FeatureComplete[Feature:<br/>All Tasks Complete<br/>Auto move to testing]

    style T1 fill:#e1f5e1
    style T3 fill:#e1f5e1
    style T2 fill:#ffe1a8
    style T4 fill:#ffe1a8
    style CascadeCheck fill:#fff3cd
    style FeatureComplete fill:#e1e5f5
```

## Configuration-Driven Status Validation

### Status Progression with get_next_status

```mermaid
flowchart TD
    UserRequest([User Asks:<br/>"What's next?"<br/>"Can I complete?"]) --> StatusSkill[Status Progression Skill<br/>MANDATORY]

    StatusSkill --> CallTool[Call get_next_status tool<br/>Read-Only Analysis]

    CallTool --> ToolReads[Tool Reads:<br/>1. .taskorchestrator/config.yaml<br/>2. Entity status & tags<br/>3. Task counts, dependencies]

    ToolReads --> AnalyzeFlow[Analyze Flow:<br/>1. Match tags to flow<br/>2. Find current position<br/>3. Check prerequisites<br/>4. Recommend next status]

    AnalyzeFlow --> Result{Result<br/>Type?}

    Result -->|Ready| ShowReady[Return:<br/>✅ recommendedStatus: "testing"<br/>✅ activeFlow: "bug_fix_flow"<br/>✅ currentPosition: 2/4<br/>✅ matchedTags: ["bug", "backend"]]

    Result -->|Blocked| ShowBlocked[Return:<br/>❌ blockers:<br/>- "Task summary required"<br/>- "2 tasks not completed"<br/>✅ currentStatus: "in-progress"]

    Result -->|Terminal| ShowTerminal[Return:<br/>✅ terminalStatus: "completed"<br/>ℹ️ No further progression]

    ShowReady --> SkillGuides[Skill Guides User:<br/>"Try manage_container<br/>with status=testing"<br/>"StatusValidator will check"]

    ShowBlocked --> SkillExplains[Skill Explains:<br/>"Your config requires:<br/>- Summary 300-500 chars<br/>- Complete dependencies first"]

    ShowTerminal --> SkillInforms[Skill Informs:<br/>"At final status<br/>No more transitions"]

    SkillGuides --> UserTries[User Tries Transition<br/>via manage_container]
    SkillExplains --> UserFixes[User Resolves<br/>Blockers]
    UserFixes --> CallTool

    UserTries --> WriteTime{StatusValidator<br/>Write-Time Check}

    WriteTime -->|Pass| Success[✅ Status Changed]
    WriteTime -->|Fail| ValidationError[❌ Validation Error]

    ValidationError --> SkillInterprets[Skill Interprets Error:<br/>"Your config has:<br/>enforce_sequential: true<br/>You must go through in-progress"]

    SkillInterprets --> UserAdjusts[User Adjusts Approach]
    UserAdjusts --> CallTool

    Success --> Done([Status Updated])
    SkillInforms --> Done

    style StatusSkill fill:#e1f5e1
    style CallTool fill:#e1f5e1
    style ToolReads fill:#ffe1a8
    style WriteTime fill:#fff3cd
    style SkillInterprets fill:#fff3cd
```

## Complete Feature Lifecycle Example

### End-to-End: Feature Creation → Task Execution → Completion

```mermaid
sequenceDiagram
    actor User
    participant Orchestrator as Task Orchestrator<br/>Output Style
    participant FeatSkill as Feature Orchestration<br/>Skill
    participant Architect as Feature Architect<br/>Opus
    participant Planning as Planning Specialist<br/>Sonnet
    participant TaskSkill as Task Orchestration<br/>Skill
    participant ImplSpec as Implementation Specialist<br/>Haiku (×3)
    participant StatusSkill as Status Progression<br/>Skill
    participant Database as SQLite Database

    User->>Orchestrator: "Create OAuth integration feature"
    Orchestrator->>FeatSkill: Activate (complex feature)
    FeatSkill->>FeatSkill: Assess: Complex (>200 chars, multiple components)
    FeatSkill-->>Orchestrator: Recommend: Launch Feature Architect
    Orchestrator->>Architect: Launch with user request

    Architect->>Database: query_templates(targetEntityType="FEATURE")
    Database-->>Architect: Templates list
    Architect->>Database: Create feature with Security + API templates
    Database-->>Architect: Feature created (planning status)
    Architect->>Database: Create feature sections (Requirements, Context)
    Architect-->>Orchestrator: Feature created, recommend Planning Specialist

    Orchestrator->>Planning: Launch for task breakdown
    Planning->>Database: query_container(feature-id)
    Database-->>Planning: Feature details
    Planning->>Planning: Identify domains: database, backend, frontend, testing
    Planning->>Database: Create 8 domain-isolated tasks with templates
    Planning->>Database: Create BLOCKS dependencies
    Planning->>Database: Update feature sections (execution graph)
    Planning-->>Orchestrator: Execution graph: Batch 1 (T1, T3), Batch 2 (T2), Batch 3 (T4-T8)

    User->>Orchestrator: "Execute tasks"
    Orchestrator->>TaskSkill: Activate (task execution)
    TaskSkill->>Database: Query all tasks + dependencies
    Database-->>TaskSkill: Tasks with dependency info
    TaskSkill->>TaskSkill: Trust Planning Specialist's graph (no re-analysis)
    TaskSkill-->>Orchestrator: Launch Batch 1 in parallel: T1 (database), T3 (frontend)

    Orchestrator->>ImplSpec: Launch Specialist #1 (T1: database schema)
    Note over ImplSpec: Loads database-implementation Skill
    ImplSpec->>Database: query_container(task-id, includeSections=true)
    Database-->>ImplSpec: Task details
    ImplSpec->>ImplSpec: Implement database schema
    ImplSpec->>Database: Update sections (Implementation Details, Files Changed)
    ImplSpec->>Database: Update summary (300-500 chars)
    ImplSpec->>StatusSkill: Mark task complete
    StatusSkill->>Database: get_next_status(task-id)
    Database-->>StatusSkill: Ready for completed (prerequisites met)
    StatusSkill->>Database: manage_container(setStatus, completed)
    Database-->>StatusSkill: ✅ Status changed
    StatusSkill-->>ImplSpec: Task marked complete
    ImplSpec-->>Orchestrator: T1 complete (brief summary)

    Orchestrator->>ImplSpec: Launch Specialist #2 (T3: UI components)
    Note over ImplSpec: Loads frontend-implementation Skill
    Note over ImplSpec: Similar self-service lifecycle as T1
    ImplSpec-->>Orchestrator: T3 complete (brief summary)

    Orchestrator->>TaskSkill: Batch 1 complete, cascade check
    TaskSkill->>Database: query_dependencies for newly unblocked tasks
    Database-->>TaskSkill: T2 now ready (T1 complete)
    TaskSkill-->>Orchestrator: Launch Batch 2: T2 (backend API)

    Orchestrator->>ImplSpec: Launch Specialist #3 (T2: backend API)
    Note over ImplSpec: Loads backend-implementation Skill
    Note over ImplSpec: Self-service lifecycle
    ImplSpec-->>Orchestrator: T2 complete (brief summary)

    Note over Orchestrator,ImplSpec: ... Continue Batch 3 execution ...

    Orchestrator->>FeatSkill: All tasks complete, check feature
    FeatSkill->>Database: query_container(operation="overview", feature-id)
    Database-->>FeatSkill: taskCounts.completed = 8 (all tasks done)
    FeatSkill->>StatusSkill: Auto move to testing
    StatusSkill->>Database: get_next_status(feature-id)
    Database-->>StatusSkill: Ready for testing (all tasks complete)
    StatusSkill->>Database: manage_container(setStatus, testing)
    Database-->>StatusSkill: ✅ Status changed
    StatusSkill-->>FeatSkill: Feature in testing
    FeatSkill-->>Orchestrator: Feature moved to testing, tests triggered

    Note over Orchestrator: Tests run (via hooks/manual)

    Orchestrator->>FeatSkill: Tests passed, complete feature?
    FeatSkill-->>Orchestrator: Ask user for final approval
    Orchestrator->>User: "Mark feature complete?"
    User->>Orchestrator: "Yes"
    Orchestrator->>FeatSkill: User approved completion
    FeatSkill->>StatusSkill: Mark feature complete
    StatusSkill->>Database: get_next_status(feature-id)
    Database-->>StatusSkill: Ready for completed
    StatusSkill->>Database: manage_container(setStatus, completed)
    Database-->>StatusSkill: ✅ Status changed
    StatusSkill-->>FeatSkill: Feature marked complete
    FeatSkill-->>Orchestrator: Feature complete ✅
    Orchestrator->>User: "Feature complete! 8 tasks done, all tests passing"
```

## Prerequisite Validation Matrix

### Task Prerequisites

| Status Transition | Prerequisites Checked | Validation Timing | Who Validates |
|---|---|---|---|
| pending → in-progress | All BLOCKS dependencies completed | Write-time | StatusValidator |
| in-progress → testing | None | Write-time | StatusValidator |
| testing → completed | Summary 300-500 chars<br/>No incomplete BLOCKS dependencies | Write-time | StatusValidator |
| * → blocked | None (emergency) | Write-time | StatusValidator |
| * → cancelled | None (emergency) | Write-time | StatusValidator |

### Feature Prerequisites

| Status Transition | Prerequisites Checked | Validation Timing | Who Validates |
|---|---|---|---|
| planning → in-development | ≥1 task created | Write-time | StatusValidator |
| in-development → testing | All tasks completed/cancelled | Write-time | StatusValidator |
| testing → validating | All tasks completed/cancelled | Write-time | StatusValidator |
| validating → completed | All tasks completed/cancelled | Write-time | StatusValidator |
| * → cancelled | None (emergency) | Write-time | StatusValidator |

### Project Prerequisites

| Status Transition | Prerequisites Checked | Validation Timing | Who Validates |
|---|---|---|---|
| planning → active | None | Write-time | StatusValidator |
| active → completed | All features completed | Write-time | StatusValidator |

## Token Optimization Strategies

### Query Pattern Efficiency

| Pattern | Tokens | Use Case |
|---|---|---|
| `query_container(operation="get", includeSections=true)` | ~18,500 | Full documentation needed |
| `query_container(operation="overview")` | ~1,200 | Status check, task counts (91% savings) |
| `query_container(operation="search")` | ~30/task | Finding tasks by criteria (89% savings) |

### Specialist Communication

| Pattern | Tokens | Description |
|---|---|---|
| Return full code in response | ~2,000-5,000 | ❌ Token waste |
| Return brief summary, code in sections | ~50-100 | ✅ Efficient (95% savings) |
| Planning Specialist execution graph | ~80-120 | ✅ Reusable by Task Orchestration |
| Re-analyze dependencies | ~300-400 | ❌ Redundant if graph exists |

## Key Patterns Summary

### Always Use Skills For (Mandatory)
- ✅ Feature lifecycle → Feature Orchestration Skill
- ✅ Task execution → Task Orchestration Skill
- ✅ Status changes → Status Progression Skill
- ✅ Dependency checks → Dependency Analysis Skill

### Always Ask User For (User Choice)
- ⚠️ Implementation work → Direct vs Specialist?
- ⚠️ Blocker resolution → Quick fix vs Specialist?
- ⚠️ Small edits → Direct vs Specialist?
- ⚠️ Feature completion → Final approval needed

### Specialist Self-Service Pattern
1. Read task via `query_container(includeSections=true)`
2. Perform work (code, tests, docs)
3. Update sections via `manage_sections`
4. Update summary (300-500 chars) via `manage_container`
5. Use Status Progression Skill to mark complete (validates prerequisites)
6. Return brief summary (50-100 tokens), NOT full implementation

### Prerequisite Validation Pattern
1. Attempt status change via Status Progression Skill
2. Skill delegates to `get_next_status` (read-only recommendation)
3. User tries transition via `manage_container(setStatus)`
4. StatusValidator validates prerequisites at write-time
5. If validation fails → Skill interprets error, explains config rules
6. User resolves blocker, retries via Status Progression Skill

---

**Legend:**
- 🟢 Green = Skills (lightweight coordination, 300-800 tokens)
- 🔵 Blue = Subagents (complex implementation, 1500-2200 tokens)
- 🟡 Yellow = Decision points / validation gates
- 🟠 Orange = Automatic transitions
