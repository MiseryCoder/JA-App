package mihon.feature.migration.auto

import kotlinx.serialization.Serializable

/**
 * A library entry that was moved off a missing source without being asked, kept until the user has seen it.
 *
 * @param originalId the entry on the missing source, which stays in the database outside the library.
 * @param targetId the entry it was migrated to.
 * @param favoriteAt when [originalId] was added to the library, in milliseconds, to put it back as it was.
 */
@Serializable
data class AutoMigration(
    val originalId: Long,
    val targetId: Long,
    val title: String,
    val fromSource: String,
    val toSource: String,
    val favoriteAt: Long? = null,
)

/**
 * Ranks the matches found for an entry whose source is missing. A match in the [language] the entry was read
 * in comes first, then the one that is furthest along, then the one with the most chapters.
 */
internal fun <T> missingSourceMatchComparator(
    language: String,
    sourceLanguage: (T) -> String?,
    latestChapter: (T) -> Double?,
    chapterCount: (T) -> Int,
): Comparator<T> {
    return compareBy<T>(
        { language.isNotBlank() && sourceLanguage(it) == language },
        { latestChapter(it) ?: 0.0 },
        { chapterCount(it) },
    )
}

/**
 * Whether two titles are close enough to migrate between them without anyone checking. The search the manual
 * migration uses accepts much looser matches, which is fine when the result is reviewed and not when it isn't.
 */
internal fun isSameTitle(original: String, found: String): Boolean {
    val a = original.normalizedTitle()
    val b = found.normalizedTitle()
    if (a.isEmpty() || b.isEmpty()) return false
    if (a == b) return true

    // A different number is a different season or sequel, however alike the rest is
    if (a.filter { it.isDigit() } != b.filter { it.isDigit() }) return false

    val similarity = 1.0 - levenshteinDistance(a, b).toDouble() / maxOf(a.length, b.length)
    return similarity >= MIN_TITLE_SIMILARITY
}

// Case, spacing and punctuation differ between sites for the same series
internal fun String.normalizedTitle(): String = lowercase().filter { it.isLetterOrDigit() }

private fun levenshteinDistance(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}

private const val MIN_TITLE_SIMILARITY = 0.9
