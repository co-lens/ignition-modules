package io.colens.mcp.common

/**
 * A post-edit finding list split by who is responsible for it. Together the two lists are the
 * post-edit list, each in its original order.
 */
data class FindingDiff(
    /** Findings this edit is responsible for. */
    val introduced: List<Finding>,
    /** Findings that were already in the document before the edit. */
    val preExisting: List<Finding>,
)

/**
 * Splits [after] against [before], so an edit tool can refuse the damage it would *cause* without
 * inheriting whatever was already wrong with the document.
 *
 * Validating the whole document and refusing on any error makes a file carrying one legacy or
 * schema-divergent finding permanently un-editable — including by the very call that would remove
 * the offending part.
 *
 * Findings are identified by `(code, message)`, counted as a **multiset**, and deliberately *not*
 * by [Finding.path]:
 *
 * - a move rewrites the path of the moved node and every descendant;
 * - a rename does the same for that node's subtree;
 * - paths fall back to a child index when a node has no name, so deleting or inserting a sibling
 *   shifts the paths of everything after it.
 *
 * Keying on path would report untouched findings under all three as newly introduced and refuse the
 * edit — the exact failure this split exists to prevent. `message` carries the discriminating
 * detail instead (the property key, the binding type, the schema violation), which is also why the
 * key is not `code` alone: fixing one violation while introducing a different one of the same code
 * leaves the count equal and would otherwise slip through.
 *
 * Counting rather than set membership is what still catches an edit that *duplicates* something
 * already broken: the key's count rises, and the surplus is introduced.
 */
fun diffFindings(before: List<Finding>, after: List<Finding>): FindingDiff {
    if (before.isEmpty()) return FindingDiff(introduced = after, preExisting = emptyList())
    if (after.isEmpty()) return FindingDiff(introduced = emptyList(), preExisting = emptyList())

    val budget = HashMap<Key, Int>()
    val pathsSeen = HashMap<Key, MutableSet<String>>()
    before.forEach {
        val key = it.key()
        budget.merge(key, 1, Int::plus)
        pathsSeen.getOrPut(key) { mutableSetOf() } += it.path
    }

    val carriedOver = BooleanArray(after.size)

    // Two passes so that when a key's count rises, the finding left over as "introduced" is the
    // one at the path the edit actually touched, rather than an arbitrary member of the group.
    // First claim the findings that also match on path, then let moved and renamed ones match.
    fun claim(requireSamePath: Boolean) {
        after.forEachIndexed { i, finding ->
            if (carriedOver[i]) return@forEachIndexed
            val key = finding.key()
            if ((budget[key] ?: 0) <= 0) return@forEachIndexed
            if (requireSamePath && finding.path !in pathsSeen[key].orEmpty()) return@forEachIndexed
            carriedOver[i] = true
            budget[key] = budget.getValue(key) - 1
        }
    }
    claim(requireSamePath = true)
    claim(requireSamePath = false)

    return FindingDiff(
        introduced = after.filterIndexed { i, _ -> !carriedOver[i] },
        preExisting = after.filterIndexed { i, _ -> carriedOver[i] },
    )
}

private typealias Key = Pair<String, String>

private fun Finding.key(): Key = code to message
