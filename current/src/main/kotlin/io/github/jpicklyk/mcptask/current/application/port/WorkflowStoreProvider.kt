package io.github.jpicklyk.mcptask.current.application.port

/**
 * The stores the workflow slice reads outside a unit. Today that is the knowledge set (work items, plan documents and
 * the links the tree service writes). `RepositoryProvider` implements it.
 */
interface WorkflowStoreProvider : KnowledgeStoreProvider
