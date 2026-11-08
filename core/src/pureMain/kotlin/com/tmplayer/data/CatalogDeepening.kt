package com.tmplayer.data

/**
 * How deep Home reads a chat for its catalog shelves alone (owner, v1.0.18: "Catagory
 * il valiya database ulla channel koduthittund, catagory maryadakk list avunnilla").
 *
 * A chat's own row reads a window ([HomeReads]: a page or a few, stopping at a row's
 * worth), and the shelves were built from those windows alone, so a large channel's
 * older catalogue never reached any category: the categories of a big database
 * channel listed only its newest handful. The Series row had the same gap and its
 * own answer ([SeriesDeepening], a search per show). The shelves' answer is
 * plainer: page the chat itself, newest first, to a bounded depth, and feed the
 * extra videos to the shelves' pool only, never to the chat's own row, Recently
 * added or the carousel, whose windows stay as they are.
 *
 * The bounds, so a very large channel stays a bounded amount of work and memory:
 * [PAGES] pages of the grid's page size per chat, [MAX_ITEMS_PER_CHAT] videos held
 * per chat, and only Home's own picked chats are read this way, at most the first
 * few of them (see HomeViewModel). The read runs after the rows stand, never
 * before them, so a slow deepening delays no first paint.
 */
object CatalogDeepening {

    /** Pages of one chat read for the shelves, the grid's page (40) at a time. */
    const val PAGES = 6

    /** The most videos one chat's deepening holds. */
    const val MAX_ITEMS_PER_CHAT = PAGES * 40
}
