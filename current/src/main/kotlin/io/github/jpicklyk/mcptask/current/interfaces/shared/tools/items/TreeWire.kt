package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.deps.DepType
import io.github.jpicklyk.mcptask.current.application.api.deps.UnblockAt
import io.github.jpicklyk.mcptask.current.application.support.IdRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Field documentation of the tree ops (`create_tree`, `complete_tree`) and their nested request classes. */
internal object TreeDocs {
    const val ROOT = "Tree root: id attaches, else title and type create."
    const val ROOT_ID = "Item to attach to. Not with parentId."
    const val ROOT_TITLE = "Title; required without id."
    const val ROOT_TYPE = "Declared type; required without id."
    const val REF = "Unique ref; not root."
    const val CHILD_TYPE = "Declared item type."
    const val PARENT_REF = "Parent ref; default root."
    const val CHILDREN = "Child items, any order; nest with parentRef."
    const val DEPS = "Dependencies between refs."
    const val DEP_FROM = "From ref (the blocker)."
    const val DEP_TO = "Target ref."
    const val DEP_TYPE = "blocks (default) or relates_to."
    const val DEP_UNBLOCK_AT = "Unblocks at this role; default terminal."
    const val NOTES = "Notes; win over noteAnchors and createNotes."
    const val NOTE_ITEM_REF = "Item ref."
    const val NOTE_KEY = "Note key."
    const val NOTE_ROLE = "Note role."
    const val NOTE_BODY = "Note text."
    const val NOTE_ANCHORS = "Notes cut from docRef sections."
    const val ANCHOR = "Heading to cut."
    const val CREATE_NOTES = "true: blank notes for unwritten schema keys."
    const val DOC_REF = "Plan document for noteAnchors; root adopts it."
    const val DOC_SLUG = "Slug (not rule/)."
    const val DOC_ROOT_ID = "Must be the tree's root (default)."
    const val TREE_ROOT_ID = "Complete its descendants. Exactly one of rootId, itemIds."
    const val ITEM_IDS = "Complete exactly these items."
    const val TRIGGER = "complete (default) or cancel."
    const val INCLUDE_ROOT = "With rootId: the root too, last. Default true."
}

/** A note role on the wire. */
@Serializable
enum class NoteRoleWire(
    val wire: String
) {
    @SerialName("queue")
    QUEUE("queue"),

    @SerialName("work")
    WORK("work"),

    @SerialName("review")
    REVIEW("review")
}

/** The triggers `complete_tree` applies. */
@Serializable
enum class TreeTrigger(
    val wire: String
) {
    @SerialName("complete")
    COMPLETE("complete"),

    @SerialName("cancel")
    CANCEL("cancel")
}

/** One note of a tree item cut from the `docRef` document. */
@Serializable
data class TreeNoteAnchor(
    @Doc(TreeDocs.NOTE_KEY) val noteKey: String,
    @Doc(TreeDocs.NOTE_ROLE) val role: NoteRoleWire,
    @Doc(TreeDocs.ANCHOR) val anchor: String
)

/** The root of a `create_tree`: [id] attaches to an existing item, otherwise [title] and [type] create one. */
@Serializable
data class TreeRootSpec(
    @Doc(TreeDocs.ROOT_ID) val id: IdRef? = null,
    @Doc(TreeDocs.ROOT_TITLE) val title: String? = null,
    @Doc(TreeDocs.ROOT_TYPE) val type: String? = null,
    @Doc(ItemDocs.PRIORITY) val priority: PriorityWire = PriorityWire.MEDIUM,
    @Doc(ItemDocs.TAGS) val tags: List<String> = emptyList(),
    @Doc(ItemDocs.TRAITS) val traits: List<String> = emptyList(),
    @Doc(ItemDocs.SUMMARY) val summary: String = "",
    @Doc(ItemDocs.DESCRIPTION) val description: String? = null,
    @Doc(ItemDocs.STATUS_LABEL) val statusLabel: String? = null,
    @Doc(ItemDocs.COMPLEXITY) val complexity: Int? = null,
    @Doc(ItemDocs.REQUIRES_VERIFICATION) val requiresVerification: Boolean = false,
    @Doc(ItemDocs.METADATA) val metadata: String? = null,
    @Doc(ItemDocs.PROPERTIES) val properties: JsonObject? = null,
    @Doc(TreeDocs.NOTE_ANCHORS) val noteAnchors: List<TreeNoteAnchor> = emptyList()
)

/** One child of a `create_tree`, placed under [parentRef]. */
@Serializable
data class TreeChildSpec(
    @Doc(TreeDocs.REF) val ref: String,
    @Doc(ItemDocs.TITLE) val title: String,
    @Doc(TreeDocs.CHILD_TYPE) val type: String,
    @Doc(TreeDocs.PARENT_REF) val parentRef: String = TREE_ROOT_REF,
    @Doc(ItemDocs.PRIORITY) val priority: PriorityWire = PriorityWire.MEDIUM,
    @Doc(ItemDocs.TAGS) val tags: List<String> = emptyList(),
    @Doc(ItemDocs.TRAITS) val traits: List<String> = emptyList(),
    @Doc(ItemDocs.SUMMARY) val summary: String = "",
    @Doc(ItemDocs.DESCRIPTION) val description: String? = null,
    @Doc(ItemDocs.STATUS_LABEL) val statusLabel: String? = null,
    @Doc(ItemDocs.COMPLEXITY) val complexity: Int? = null,
    @Doc(ItemDocs.REQUIRES_VERIFICATION) val requiresVerification: Boolean = false,
    @Doc(ItemDocs.METADATA) val metadata: String? = null,
    @Doc(ItemDocs.PROPERTIES) val properties: JsonObject? = null,
    @Doc(TreeDocs.NOTE_ANCHORS) val noteAnchors: List<TreeNoteAnchor> = emptyList()
)

/** One dependency of a `create_tree`, between two refs. */
@Serializable
data class TreeDepSpec(
    @Doc(TreeDocs.DEP_FROM) val from: String,
    @Doc(TreeDocs.DEP_TO) val to: String,
    @Doc(TreeDocs.DEP_TYPE) val type: DepType? = null,
    @Doc(TreeDocs.DEP_UNBLOCK_AT) val unblockAt: UnblockAt? = null
)

/** One note of a `create_tree`, on the item named [itemRef]. */
@Serializable
data class TreeNoteSpec(
    @Doc(TreeDocs.NOTE_ITEM_REF) val itemRef: String,
    @Doc(TreeDocs.NOTE_KEY) val key: String,
    @Doc(TreeDocs.NOTE_ROLE) val role: NoteRoleWire,
    @Doc(TreeDocs.NOTE_BODY) val body: String = ""
)

/** The plan document a `create_tree` cuts its `noteAnchors` from and adopts. */
@Serializable
data class TreeDocRef(
    @Doc(TreeDocs.DOC_SLUG) val slug: String,
    @Doc(TreeDocs.DOC_ROOT_ID) val rootId: IdRef? = null
)

/** An `items_write` `create_tree` element: a root (created or attached), its children, dependencies and notes. */
@Serializable
data class CreateTreeRequest(
    @Doc(TreeDocs.ROOT) val root: TreeRootSpec,
    @Doc(ItemDocs.PARENT_ID) val parentId: IdRef? = null,
    @Doc(TreeDocs.CHILDREN) val children: List<TreeChildSpec> = emptyList(),
    @Doc(TreeDocs.DEPS) val deps: List<TreeDepSpec> = emptyList(),
    @Doc(TreeDocs.NOTES) val notes: List<TreeNoteSpec> = emptyList(),
    @Doc(TreeDocs.CREATE_NOTES) val createNotes: Boolean = false,
    @Doc(TreeDocs.DOC_REF) val docRef: TreeDocRef? = null
)

/** An `items_write` `complete_tree` element: exactly one of [rootId] and [itemIds]. */
@Serializable
data class CompleteTreeRequest(
    @Doc(TreeDocs.TREE_ROOT_ID) val rootId: IdRef? = null,
    @Doc(TreeDocs.ITEM_IDS) val itemIds: List<IdRef>? = null,
    @Doc(TreeDocs.TRIGGER) val trigger: TreeTrigger = TreeTrigger.COMPLETE,
    @Doc(TreeDocs.INCLUDE_ROOT) val includeRoot: Boolean = true
)

/** The ref that names the tree root in `parentRef`, `deps` and `notes`. */
internal const val TREE_ROOT_REF = "root"
