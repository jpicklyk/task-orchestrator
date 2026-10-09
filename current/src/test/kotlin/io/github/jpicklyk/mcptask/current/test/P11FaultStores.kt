package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.port.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.application.port.LeaseStore
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.TransitionStore
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Non-poisoning fault injection for the P11 (item 919d379e) atomicity tests, round r2. The faults are PLAIN
 * RuntimeExceptions thrown by a delegating store wrapper -- not SQL exceptions -- so the unit is not poisoned by a failed
 * statement: only an advance that really fails (rolls back) on the exception leaves the stores unchanged. An advance that
 * swallowed the fault and committed would persist the primary and surface as a changed role / row / event.
 */
internal const val P11_FAULT_LEASE_KEY = "p11-fault-db"

internal const val P11_FAULT_TRAIT = "needs-p11-fault-db"

/** Throws a plain [RuntimeException] on the [failOnNth] create() call after [arm]; passes everything else through. */
internal class P11FailingTransitionStore(
    private val delegate: TransitionStore,
    private val failOnNth: Int = 2,
) : TransitionStore by delegate {
    @Volatile var armed: Boolean = false
    private val calls = AtomicInteger()

    fun arm() {
        calls.set(0)
        armed = true
    }

    fun disarm() {
        armed = false
    }

    /** create() calls seen since the last [arm]. */
    val createCalls: Int get() = calls.get()

    override suspend fun create(transition: RoleTransition): RoleTransition {
        if (armed && calls.incrementAndGet() == failOnNth) {
            throw RuntimeException("p11 injected plain fault on create #$failOnNth (item ${transition.itemId})")
        }
        return delegate.create(transition)
    }
}

/** Throws a plain [RuntimeException] from releaseAllForItem while armed (the release a WORK exit performs). */
internal class P11FailingLeaseStore(
    private val delegate: LeaseStore,
) : LeaseStore by delegate {
    @Volatile var armed: Boolean = false
    private val calls = AtomicInteger()

    fun arm() {
        calls.set(0)
        armed = true
    }

    fun disarm() {
        armed = false
    }

    /** releaseAllForItem() calls seen while armed. */
    val releaseCalls: Int get() = calls.get()

    override suspend fun releaseAllForItem(holderItemId: UUID): LeaseReleaseResult {
        if (armed) {
            calls.incrementAndGet()
            throw RuntimeException("p11 injected plain fault on releaseAllForItem ($holderItemId)")
        }
        return delegate.releaseAllForItem(holderItemId)
    }
}

/** A provider that hands out the given (wrapped) stores and delegates everything else. */
internal class P11FaultProvider(
    private val delegate: RepositoryProvider,
    private val transitions: TransitionStore,
    private val leases: LeaseStore,
) : RepositoryProvider by delegate {
    override fun roleTransitionRepository(): TransitionStore = transitions

    override fun resourceLeaseRepository(): LeaseStore = leases
}

/** Maps [P11_FAULT_TRAIT] onto one exclusive resource with a 600s TTL (no note schemas). */
internal class P11FaultSchemaService : WorkItemSchemaService {
    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = null

    override fun getTraitResources(traitName: String): List<ResourceRequirement> =
        if (traitName == P11_FAULT_TRAIT) {
            listOf(ResourceRequirement(P11_FAULT_LEASE_KEY, ResourceMode.EXCLUSIVE, 600))
        } else {
            emptyList()
        }
}

/** Wrapped stores over a real SQLite provider, with a helper to build the cascading fixture and snapshot its state. */
internal class P11FaultRig(
    val raw: DefaultRepositoryProvider,
) {
    val transitionStore = P11FailingTransitionStore(raw.roleTransitionRepository())
    val leaseStore = P11FailingLeaseStore(raw.resourceLeaseRepository())
    val provider: RepositoryProvider = P11FaultProvider(raw, transitionStore, leaseStore)

    /** A WORK parent with one WORK child that holds the exclusive lease (taken directly in the store: no transition rows). */
    suspend fun fixture(name: String): Pair<WorkItem, WorkItem> {
        val items = raw.workItemRepository()
        val parent = items.create(WorkItem(title = "parent $name", role = Role.WORK))
        val child =
            items.create(
                WorkItem(
                    title = "child $name",
                    role = Role.WORK,
                    parentId = parent.id,
                    depth = 1,
                    properties = """{"traits":["$P11_FAULT_TRAIT"]}""",
                ),
            )
        raw.resourceLeaseRepository().acquireAll(child.id, "p11-holder", listOf(P11_FAULT_LEASE_KEY to 600))
        return parent to child
    }

    suspend fun snapshot(vararg items: WorkItem): P11FaultSnapshot =
        P11FaultSnapshot(
            items =
                items.map {
                    val current = raw.workItemRepository().getById(it.id)!!
                    P11ItemState(it.id, current.role, current.previousRole, current.statusLabel, current.roleChangedAt)
                },
            transitionRows = items.associate { it.id to raw.roleTransitionRepository().findByItemId(it.id, limit = 500).size },
            leases = items.associate { it.id to raw.resourceLeaseRepository().findActiveForItem(it.id).map { l -> l.resourceKey } },
            events = raw.eventStore().readAfter(0L, null, 100_000).map { it.seq to it.type },
        )

    suspend fun eventCount(type: String): Int = raw.eventStore().readAfter(0L, null, 100_000).count { it.type == type }
}

internal data class P11ItemState(
    val id: UUID,
    val role: Role,
    val previousRole: Role?,
    val statusLabel: String?,
    val roleChangedAt: Instant?,
)

internal data class P11FaultSnapshot(
    val items: List<P11ItemState>,
    val transitionRows: Map<UUID, Int>,
    val leases: Map<UUID, List<String>>,
    val events: List<Pair<Long, String>>,
)
