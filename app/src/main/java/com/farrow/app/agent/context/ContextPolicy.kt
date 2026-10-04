package com.farrow.app.agent.context

/** Pure, unit-testable rules for rolling summarisation. */
object ContextPolicy {
    const val TRIGGER_RATIO = 0.6
    const val KEEP_RECENT = 6

    fun estimateTokens(text: String?): Int = if (text.isNullOrEmpty()) 0 else (text.length + 3) / 4

    fun shouldSummarize(totalTokens: Int, budgetTokens: Int, ratio: Double = TRIGGER_RATIO): Boolean =
        totalTokens > budgetTokens * ratio

    data class Item(val id: Long, val isTool: Boolean, val tokens: Int)

    /**
     * Returns the ids of the oldest items to fold into a summary, keeping at least [keepRecent]
     * recent items. The kept window never starts with a tool result (so it is never separated from
     * the assistant message that requested it). Returns empty if there is nothing worth summarising.
     */
    fun selectForSummary(items: List<Item>, keepRecent: Int = KEEP_RECENT): List<Long> {
        if (items.size <= keepRecent) return emptyList()
        var boundary = items.size - keepRecent
        while (boundary > 0 && items[boundary].isTool) boundary--
        if (boundary < 2) return emptyList()
        return items.subList(0, boundary).map { it.id }
    }
}
