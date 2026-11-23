package io.github.jpicklyk.mcptask.current.application.port

/**
 * The stores the workflow slice reads: the knowledge set (work items, plan documents and the links the tree service
 * writes) and the lifecycle set the tree completion reads. [RepositoryProvider] implements it.
 */
interface WorkflowStoreProvider :
    KnowledgeStoreProvider,
    LifecycleStoreProvider
