package io.github.jpicklyk.mcptask.current.item

import io.github.jpicklyk.mcptask.current.item.internal.wire.ItemsReadGet
import io.github.jpicklyk.mcptask.current.item.internal.wire.ItemsReadList
import io.github.jpicklyk.mcptask.current.item.internal.wire.ItemsReadOverview

/** The item slice's contribution to the tool registry: the `get`, `list` and `overview` selects of `items_read`. */
object ItemTools {
    /** The `items_read` `get` select. */
    val itemsReadGet = ItemsReadGet.select

    /** The `items_read` `list` select. */
    val itemsReadList = ItemsReadList.select

    /** The `items_read` `overview` select. */
    val itemsReadOverview = ItemsReadOverview.select
}
