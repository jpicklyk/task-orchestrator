package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.port.DependencyStore
import io.github.jpicklyk.mcptask.current.application.port.LeaseStore
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.AdvanceOutcome
import io.github.jpicklyk.mcptask.current.application.service.AdvanceService
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.mockk.coEvery
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Store-read stubs for MockK suites written against the pre-P11 advance pipeline.
 *
 * Since P11 the advance re-reads its item by id inside its unit (`getById`), reads an item's dependency rows
 * through `DependencyStore.findByItemId`, blocker roles through `ItemStore.findByIds`, and lease contention
 * through `LeaseStore.findActiveByKeys`. These stubs keep such suites' existing per-direction fixtures
 * meaningful: `findByItemId` is the union of the suite's `findByToItemId` / `findByFromItemId` stubs,
 * `findByIds` resolves through the suite's `getById` stubs, and `getById` returns the copy of an item the
 * suite last advanced through [advanceSeeded]. Declared first in a suite's setUp, so any stub the suite (or a
 * test) declares afterwards for the same call takes precedence.
 */
class AdvanceMockStores(
    private val workItemRepo: WorkItemRepository,
    depRepo: DependencyStore? = null,
    leaseRepo: LeaseStore? = null
) {
    private val seeded = ConcurrentHashMap<UUID, WorkItem>()

    init {
        coEvery { workItemRepo.getById(any()) } answers { seeded[firstArg()] }
        coEvery { workItemRepo.findByIds(any()) } coAnswers {
            firstArg<Set<UUID>>().mapNotNull { id -> runCatching { workItemRepo.getById(id) }.getOrNull() }
        }
        if (depRepo != null) stubDependencyUnion(depRepo)
        if (leaseRepo != null) coEvery { leaseRepo.findActiveByKeys(any()) } returns emptyList()
    }

    /** Makes [item] what `getById(item.id)` returns (unless a more specific stub says otherwise). */
    fun seed(item: WorkItem): WorkItem {
        seeded[item.id] = item
        return item
    }

    companion object {
        /**
         * The dependency union ([stubDependencyUnion]) plus findByIds resolved through the suite's getById
         * stubs, for suites that stub getById per item themselves (no seeding).
         */
        fun stubReads(
            workItemRepo: WorkItemRepository,
            depRepo: DependencyStore
        ) {
            stubDependencyUnion(depRepo)
            coEvery { workItemRepo.findByIds(any()) } coAnswers {
                firstArg<Set<UUID>>().mapNotNull { id -> runCatching { workItemRepo.getById(id) }.getOrNull() }
            }
        }

        /** `findByItemId(id)` = the union of the suite's `findByToItemId(id)` and `findByFromItemId(id)` stubs. */
        fun stubDependencyUnion(depRepo: DependencyStore) {
            coEvery { depRepo.findByItemId(any()) } coAnswers {
                val id = firstArg<UUID>()
                val incoming: List<Dependency> = runCatching { depRepo.findByToItemId(id) }.getOrDefault(emptyList())
                val outgoing: List<Dependency> = runCatching { depRepo.findByFromItemId(id) }.getOrDefault(emptyList())
                (incoming + outgoing).distinctBy { it.id }
            }
        }
    }
}

/** [AdvanceService.advance] after seeding [item] into [stores], so the in-unit re-read sees the caller's copy. */
suspend fun AdvanceService.advanceSeeded(
    stores: AdvanceMockStores,
    item: WorkItem,
    trigger: String,
    summary: String?,
    actorClaim: ActorClaim?,
    verification: VerificationResult?,
    degradedModePolicy: DegradedModePolicy,
    enforceOwnership: Boolean,
    credentialRefs: List<String> = emptyList(),
    enforceResourceLeases: Boolean = true
): AdvanceOutcome {
    stores.seed(item)
    return advance(
        item,
        trigger,
        summary,
        actorClaim,
        verification,
        degradedModePolicy,
        enforceOwnership,
        credentialRefs,
        enforceResourceLeases
    )
}
