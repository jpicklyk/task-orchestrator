package io.github.jpicklyk.mcptask.current.domain.lifecycle

/**
 * Every lifecycle trigger, in three families: [User] (sent by callers), [Cascade] (emitted by the
 * system when children move) and [Structural] (tree edits). [wire] is the stable audit/wire name.
 */
sealed interface Trigger {
    val wire: String

    /** Triggers a caller may send. Declaration order is the order of every `allowed` list. */
    enum class User(
        override val wire: String
    ) : Trigger {
        START("start"),
        COMPLETE("complete"),
        BLOCK("block"),
        HOLD("hold"),
        RESUME("resume"),
        CANCEL("cancel"),
        REOPEN("reopen");

        companion object {
            /** Case-insensitive parse; null for anything else, including "cascade". */
            fun parse(value: String): User? = entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
        }
    }

    /** System-internal parent transitions. All three share the audit wire name "cascade". */
    sealed interface Cascade : Trigger {
        override val wire: String get() = WIRE

        /**
         * Parent to TERMINAL because every child is terminal. [cancelOrigin] marks a cascade whose
         * chain began with a `cancel`; such cascades skip the note gate.
         */
        data class Complete(
            val cancelOrigin: Boolean = false
        ) : Cascade

        /** Parent QUEUE to WORK because a child entered WORK. */
        data object Start : Cascade

        /** Parent TERMINAL to WORK because a child was reopened. */
        data object Reopen : Cascade

        companion object {
            const val WIRE: String = "cascade"
        }
    }

    /** Tree edits. Not in the transition table; [TransitionPolicy] evaluates them directly. */
    sealed interface Structural : Trigger {
        /** Create a new item under [parent] (null = root level). Always lands in QUEUE. */
        data class Create(
            val parent: ParentFacts?
        ) : Structural {
            override val wire: String get() = "create"
        }

        /** Move the snapshot item under [newParent] (null = root level). */
        data class Reparent(
            val newParent: ParentFacts?
        ) : Structural {
            override val wire: String get() = "reparent"
        }

        /** Delete the snapshot item. */
        data object Delete : Structural {
            override val wire: String get() = "delete"
        }
    }
}
