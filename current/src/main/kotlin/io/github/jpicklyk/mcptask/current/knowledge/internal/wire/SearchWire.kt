package io.github.jpicklyk.mcptask.current.knowledge.internal.wire

import io.github.jpicklyk.mcptask.current.knowledge.Corpus
import io.github.jpicklyk.mcptask.current.knowledge.SearchMatchMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A search match mode on the wire. */
@Serializable
enum class MatchModeWire(
    val mode: SearchMatchMode
) {
    @SerialName("auto")
    AUTO(SearchMatchMode.AUTO),

    @SerialName("substring")
    SUBSTRING(SearchMatchMode.SUBSTRING),

    @SerialName("text")
    TEXT(SearchMatchMode.TEXT),
}

/** A searchable kind of content on the wire. */
@Serializable
enum class KindWire(
    val corpus: Corpus
) {
    @SerialName("item")
    ITEM(Corpus.ITEM),

    @SerialName("note")
    NOTE(Corpus.NOTE),

    @SerialName("document")
    DOCUMENT(Corpus.DOCUMENT),
}
