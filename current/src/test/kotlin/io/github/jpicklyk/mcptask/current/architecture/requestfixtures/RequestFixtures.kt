package io.github.jpicklyk.mcptask.current.architecture.requestfixtures

import io.github.jpicklyk.mcptask.current.application.support.IdRef
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/*
 * Fixtures for RequestFieldConsumptionTest. Each scenario owns its own classes, so a read in one scenario's reader can never
 * satisfy a field in another's. The reader functions below are never called at runtime: the engine is a static bytecode scan,
 * so only the shape of the compiled call sites matters.
 */

// ---- S1: both fields read through their getters
@Serializable
data class S1Req(
    val a: String,
    val b: Int
)

fun readS1(x: S1Req): String = x.a + x.b

// ---- S2: b is read only by destructuring (component2); a through its getter
@Serializable
data class S2Req(
    val a: String,
    val b: Int
)

fun readS2(x: S2Req): String {
    val a = x.a
    val (_, b) = x
    return a + b
}

// ---- S2b: destructuring only the first component reads a, not b (component position matters)
@Serializable
data class S2PosReq(
    val a: String,
    val b: Int
)

fun readS2Pos(x: S2PosReq): String {
    val (first) = x
    return first
}

// ---- S3: is-prefixed Boolean, value-class (IdRef) and nullable value-class getters, plus non-recursed field types
@Serializable
enum class S3Mode { ON, OFF }

@Serializable
data class S3Req(
    val isOn: Boolean,
    val id: IdRef,
    val parent: IdRef?,
    val payload: JsonObject,
    val mode: S3Mode,
    val tags: List<String>
)

fun readS3(x: S3Req): List<Any?> = listOf(x.isOn, x.id, x.parent, x.payload, x.mode, x.tags)

// ---- S4: c is read only inside an owner member function that nothing calls
@Serializable
data class S4Req(
    val a: String,
    val c: Int
) {
    fun doubledC(): Int = c * 2
}

fun readS4(x: S4Req): String = x.a

// ---- S5: c is never referenced; a different class's same-named getter must not count
@Serializable
data class S5Req(
    val a: String,
    val c: Int
)

data class S5Other(
    val c: Int
)

fun readS5(
    x: S5Req,
    other: S5Other
): String = x.a + other.c

// ---- S6: c is touched only by members the read rule excludes
@Serializable
data class S6Req(
    val a: String,
    val c: Int
)

fun touchS6(
    x: S6Req,
    y: S6Req
): String {
    val text = x.toString()
    val same = x == y
    val hash = x.hashCode()
    val copied = x.copy(a = "z")
    val json = Json.encodeToString(S6Req.serializer(), x)
    return text + same + hash + copied.a + json
}

// ---- S7: c is read only in an init block
@Serializable
data class S7Req(
    val a: String,
    val c: Int
) {
    init {
        require(c > 0)
    }
}

fun readS7(x: S7Req): String = x.a

// ---- S8: nested object, list element and map value, each with one unread field
@Serializable
data class S8Outer(
    val inner: Inner?,
    val list: List<Item>,
    val map: Map<String, Val>
) {
    @Serializable
    data class Inner(
        val keep: Int,
        val x: Int
    )

    @Serializable
    data class Item(
        val keep: Int,
        val y: Int
    )

    @Serializable
    data class Val(
        val keep: Int,
        val z: Int
    )
}

fun readS8(o: S8Outer): Int {
    var total = o.inner?.keep ?: 0
    for (item in o.list) total += item.keep
    for (entry in o.map.entries) total += entry.value.keep
    return total
}

// ---- S9: one nested class reached from two roots
@Serializable
data class S9Shared(
    val keep: Int,
    val x: Int
)

@Serializable
data class S9RootA(
    val shared: S9Shared
)

@Serializable
data class S9RootB(
    val shared: S9Shared
)

fun readS9(
    a: S9RootA,
    b: S9RootB
): Int = a.shared.keep + b.shared.keep

// ---- S11: self-referential, v unread
@Serializable
data class S11Node(
    val next: S11Node?,
    val v: Int
)

fun readS11(n: S11Node): S11Node? = n.next

// ---- S14: zero-field requests
@Serializable
object S14Obj

@Serializable
class S14Empty

// ---- S15: public @JvmField properties are read through GETFIELD, there is no getter
@Serializable
class S15Req(
    @JvmField val a: String,
    @JvmField val c: Int
)

fun readS15(x: S15Req): String = x.a

@Serializable
class S15AllReadReq(
    @JvmField val a: String,
    @JvmField val c: Int
)

fun readS15All(x: S15AllReadReq): String = x.a + x.c

// ---- S16/S17: hand-written descriptors the engine cannot map onto properties or classes
class GhostHolder(
    val a: String
)

object GhostSerializer : KSerializer<GhostHolder> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("io.github.jpicklyk.mcptask.current.architecture.requestfixtures.GhostHolder") {
            element<String>("a")
            element<String>("ghost")
        }

    override fun serialize(
        encoder: Encoder,
        value: GhostHolder
    ): Unit = throw UnsupportedOperationException("descriptor-only fixture")

    override fun deserialize(decoder: Decoder): GhostHolder = throw UnsupportedOperationException("descriptor-only fixture")
}

fun readGhostHolder(x: GhostHolder): String = x.a

class MissingHolder

object MissingClassSerializer : KSerializer<MissingHolder> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("io.github.jpicklyk.mcptask.current.architecture.requestfixtures.DoesNotExistAnywhere") {
            element<String>("f")
        }

    override fun serialize(
        encoder: Encoder,
        value: MissingHolder
    ): Unit = throw UnsupportedOperationException("descriptor-only fixture")

    override fun deserialize(decoder: Decoder): MissingHolder = throw UnsupportedOperationException("descriptor-only fixture")
}
