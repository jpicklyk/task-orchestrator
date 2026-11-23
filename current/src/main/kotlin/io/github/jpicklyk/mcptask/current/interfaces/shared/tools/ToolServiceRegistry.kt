package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.docs.KnowledgeToolServices
import io.github.jpicklyk.mcptask.current.application.api.notes.NoteToolServices
import io.github.jpicklyk.mcptask.current.application.decision.DecisionService
import io.github.jpicklyk.mcptask.current.application.run.RunLimits
import io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.advance.LifecycleToolServices
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.config.ConfigToolServices
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.deps.DependencyToolServices
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.EventToolServices
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.ItemToolServices
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.WorkflowToolServices
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.report.ReportToolServices
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.RunToolServices
import io.github.jpicklyk.mcptask.current.platform.tool.ToolServiceLookup
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.reflect.KClass

/**
 * The [ToolServiceLookup] over one [ToolExecutionContext]: builds each per-slice services holder from the context on
 * first lookup, and builds the [DecisionService] and [RunLimits] the run holder carries (here in the tool layer, since
 * the context cannot reference the decision package). A holder takes the context's lazy services as [Lazy] values, so
 * building it touches no store. It also serves [ToolExecutionContext] itself, for test fixture specs only.
 *
 * One registry per context ([of]), so every dispatcher over one context shares one [DecisionService].
 */
class ToolServiceRegistry private constructor(
    private val tec: ToolExecutionContext
) : ToolServiceLookup {
    private val decisionService: Lazy<DecisionService> =
        lazy {
            DecisionService(
                tec.repositoryProvider,
                tec.unitOfWork,
                decisionClasses = { rootId -> tec.configResolver.resolveDecisionClasses(rootId) },
                effects = tec.decisionEffects
            )
        }

    private val runLimits: Lazy<RunLimits> =
        lazy { RunLimits(tec.repositoryProvider, tec.unitOfWork, tec.runService, tec.seatService, decisionService.value) }

    private val holders: Map<KClass<*>, Lazy<Any>> =
        mapOf(
            ToolExecutionContext::class to lazyOf(tec),
            ItemToolServices::class to lazy { item() },
            EventToolServices::class to lazy { event() },
            WorkflowToolServices::class to lazy { workflow() },
            LifecycleToolServices::class to lazy { lifecycle() },
            DependencyToolServices::class to lazy { dependency() },
            ConfigToolServices::class to lazy { config() },
            RunToolServices::class to lazy { run() },
            ReportToolServices::class to lazy { ReportToolServices(tec.unitOfWork, tec.clock) },
            NoteToolServices::class to lazy { note() },
            KnowledgeToolServices::class to lazy { knowledge() }
        )

    override fun <S : Any> get(type: KClass<S>): S {
        val holder = holders[type] ?: error("No tool services holder is registered for ${type.qualifiedName}")
        return type.java.cast(holder.value)
    }

    private fun item() =
        ItemToolServices(
            items = { tec.workItemRepository() },
            unitOfWork = tec.unitOfWork,
            workViewQueries = tec.workViewQueries,
            schemaPin = lazy { tec.schemaPinService }
        )

    private fun event() =
        EventToolServices(
            items = { tec.workItemRepository() },
            events = { tec.repositoryProvider.eventStore() }
        )

    private fun workflow() =
        WorkflowToolServices(
            stores = tec.repositoryProvider,
            unitOfWork = tec.unitOfWork,
            configResolver = tec.configResolver,
            itemCommand = lazy { tec.itemCommandService },
            dependencyCommand = lazy { tec.dependencyCommandService },
            noteCommand = lazy { tec.noteCommandService },
            planDocument = lazy { tec.planDocumentService },
            schemaPin = lazy { tec.schemaPinService },
            advanceFactory = lazy { tec.advanceServiceFactory() }
        )

    private fun lifecycle() =
        LifecycleToolServices(
            stores = tec.repositoryProvider,
            unitOfWork = tec.unitOfWork,
            clock = tec.clock,
            configResolver = tec.configResolver,
            workViewQueries = tec.workViewQueries,
            nextItemRecommender = tec.nextItemRecommender,
            claim = lazy { tec.claimService },
            advanceFactory = lazy { tec.advanceServiceFactory() }
        )

    private fun dependency() =
        DependencyToolServices(
            stores = tec.repositoryProvider,
            unitOfWork = tec.unitOfWork,
            dependencyCommand = lazy { tec.dependencyCommandService }
        )

    private fun config() =
        ConfigToolServices(
            stores = tec.repositoryProvider,
            unitOfWork = tec.unitOfWork,
            configResolver = tec.configResolver,
            configService = tec.configService
        )

    private fun run() =
        RunToolServices(
            stores = tec.repositoryProvider,
            unitOfWork = tec.unitOfWork,
            clock = tec.clock,
            configResolver = tec.configResolver,
            run = lazy { tec.runService },
            seat = lazy { tec.seatService },
            decision = decisionService,
            limits = runLimits,
            advanceFactory = lazy { tec.advanceServiceFactory() }
        )

    private fun note() =
        NoteToolServices(
            stores = tec.repositoryProvider,
            unitOfWork = tec.unitOfWork,
            bodySource = tec.bodySource,
            noteCommand = lazy { tec.noteCommandService }
        )

    private fun knowledge() =
        KnowledgeToolServices(
            stores = tec.repositoryProvider,
            unitOfWork = tec.unitOfWork,
            bodySource = tec.bodySource,
            document = lazy { tec.documentService },
            link = lazy { tec.linkService },
            search = lazy { tec.searchService },
            related = lazy { tec.relatedService }
        )

    companion object {
        /**
         * One registry per context, identity keyed. A registry holds its context, so the value is weak too: an entry is
         * released once neither the context nor its registry is held (a strong value would pin the weak key for good).
         */
        private val registries: MutableMap<ToolExecutionContext, WeakReference<ToolServiceRegistry>> = WeakHashMap()

        /** The registry of [tec]: built on first use, then reused while anything (a dispatcher) holds it. */
        fun of(tec: ToolExecutionContext): ToolServiceRegistry =
            synchronized(registries) {
                registries[tec]?.get() ?: ToolServiceRegistry(tec).also { registries[tec] = WeakReference(it) }
            }
    }
}
