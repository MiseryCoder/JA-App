package mihon.feature.library.duplicates

import mihon.feature.migration.auto.normalizedTitle

/**
 * What decides which of several library entries of the same series is the one worth keeping.
 *
 * @param latestChapterUploadedAt when the source published its latest chapter, in milliseconds, if it says.
 */
internal class DuplicateCandidate<T>(
    val entry: T,
    val isSourceInstalled: Boolean,
    val latestChapter: Double?,
    val chapterCount: Int,
    val latestChapterUploadedAt: Long?,
)

/**
 * Splits the entries into groups that share a title, ignoring case, spacing and punctuation. Entries that
 * have a title to themselves are left out.
 */
internal fun <T> List<T>.groupByTitle(title: (T) -> String): List<List<T>> {
    return this
        .groupBy { title(it).normalizedTitle() }
        .filter { (key, entries) -> key.isNotEmpty() && entries.size > 1 }
        .values
        .toList()
}

/**
 * Orders duplicates from the least to the most worth keeping. An entry that can still be read comes before
 * one whose source is gone, then the one that is furthest along, then the one with the most chapters, then
 * the one whose source had the latest chapter first, as it's likely to have the next one first too.
 */
internal fun <T> duplicateComparator(): Comparator<DuplicateCandidate<T>> {
    return compareBy<DuplicateCandidate<T>>(
        { it.isSourceInstalled },
        { it.latestChapter ?: -1.0 },
        { it.chapterCount },
        { -(it.latestChapterUploadedAt ?: Long.MAX_VALUE) },
    )
}
