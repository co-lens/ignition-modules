package io.colens.report.common

/**
 * Historian path handling.
 *
 * The 8.3 historian addresses data with a fully qualified path:
 *
 *     histprov:<historian>:/sys:<gateway>:/prov:<tagProvider>:/tag:<path>
 *
 * Nobody is going to type that into a spreadsheet cell. `=IGN.HISTORY("Line1/Temp", ...)` is the
 * whole point, so short forms are expanded here against defaults the caller supplies, and the
 * expansion lives in `common` so the add-in, the REST layer and the headless renderer cannot
 * disagree about what a bare path means.
 *
 * Three accepted spellings, most explicit first:
 *   histprov:Plant:/sys:gw1:/prov:default:/tag:Line1/Temp   fully qualified, passed through
 *   [default]Line1/Temp                                     legacy tag-path form, provider named
 *   Line1/Temp                                              bare, everything defaulted
 */
object HistorianPaths {

    private const val QUALIFIED_PREFIX = "histprov:"
    private val BRACKETED = Regex("""^\[([^]]*)]\s*(.*)$""")

    data class Defaults(
        val historian: String,
        val gateway: String,
        val tagProvider: String,
    )

    fun isQualified(path: String): Boolean = path.trimStart().startsWith(QUALIFIED_PREFIX)

    /**
     * Expands a short path. A path that is already qualified is returned untouched — including
     * one naming a different historian or gateway, so a report can legitimately span sources.
     */
    fun qualify(path: String, defaults: Defaults): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("Empty tag path.")
        if (isQualified(trimmed)) return trimmed

        val match = BRACKETED.matchEntire(trimmed)
        val provider = match?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: defaults.tagProvider
        val tag = match?.groupValues?.get(2)?.trim() ?: trimmed

        if (tag.isEmpty()) throw IllegalArgumentException("Tag path '$path' names a provider but no tag.")

        return "histprov:${defaults.historian}:/sys:${defaults.gateway}:/prov:$provider:/tag:$tag"
    }

    /**
     * A short, human label for a qualified path — the `/tag:` segment.
     *
     * Used as the default spreadsheet column heading. A column headed with the full qualified path
     * would be unreadable and would push every column to 80 characters wide.
     */
    fun displayName(path: String): String =
        path.substringAfter("/tag:", missingDelimiterValue = path).ifBlank { path }
}
