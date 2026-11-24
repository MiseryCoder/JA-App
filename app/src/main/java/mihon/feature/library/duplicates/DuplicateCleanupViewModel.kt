package mihon.feature.library.duplicates

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.UpsertTrack

/**
 * Finds the series that are in the library more than once, and removes all but the best entry of each once
 * the user has gone over them.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class DuplicateCleanupViewModel(
    @AppCoroutineScope private val appScope: CoroutineScope,
    private val sourceManager: SourceManager,
    private val getFavorites: GetFavorites,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapter: UpdateChapter,
    private val getCategories: GetCategories,
    private val setMangaCategories: SetMangaCategories,
    private val getTracks: GetTracks,
    private val upsertTrack: UpsertTrack,
    private val updateManga: UpdateManga,
) : ViewModel() {

    val state: StateFlow<DuplicateCleanupViewModel.State>
        field = MutableStateFlow<DuplicateCleanupViewModel.State>(State.Loading)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val groups = findDuplicates()
            state.update { State.Ready(groups) }
        }
    }

    /**
     * Includes the group in the clean-up, or leaves it out if it was included.
     */
    fun toggle(group: Group) = state.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(skipped = if (group.key in state.skipped) state.skipped - group.key else state.skipped + group.key)
    }

    fun showConfirmation() = state.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(showConfirmation = true)
    }

    fun hideConfirmation() = state.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(showConfirmation = false)
    }

    /**
     * Removes the duplicates of the groups that weren't left out, then looks for what's left.
     */
    suspend fun removeDuplicates() {
        val ready = state.value as? State.Ready ?: return
        state.update { ready.copy(showConfirmation = false, isRemoving = true) }

        // Finishes even if the screen is left while it runs
        appScope.launch(Dispatchers.IO) {
            ready.selected.forEach { group ->
                group.remove.forEach { duplicate ->
                    try {
                        merge(duplicate = duplicate.manga, keep = group.keep.manga)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logcat(LogPriority.ERROR, e) { "Failed to remove a duplicate of ${group.title}" }
                    }
                }
            }
        }
            .join()

        val groups = withContext(Dispatchers.IO) { findDuplicates() }
        state.update { State.Ready(groups) }
    }

    private suspend fun findDuplicates(): List<Group> {
        return getFavorites.await()
            .groupByTitle { it.title }
            .map { mangas ->
                val entries = mangas
                    .map { toCandidate(it) }
                    .sortedWith(duplicateComparator<Entry>().reversed())
                    .map { it.entry }
                val keep = entries.first()
                Group(key = keep.manga.id, title = keep.manga.title, keep = keep, remove = entries.drop(1))
            }
            .sortedBy { it.title.lowercase() }
    }

    private suspend fun toCandidate(manga: Manga): DuplicateCandidate<Entry> {
        val source = sourceManager.getOrStub(manga.source)
        val chapters = getChaptersByMangaId.await(manga.id)
        val latestChapter = chapters
            .filter { it.isRecognizedNumber }
            .maxByOrNull { it.chapterNumber }

        val entry = Entry(
            manga = manga,
            sourceName = source.displayName(),
            isSourceInstalled = source !is StubSource,
            chapterCount = chapters.size,
            latestChapter = latestChapter?.chapterNumber,
        )
        return DuplicateCandidate(
            entry = entry,
            isSourceInstalled = entry.isSourceInstalled,
            latestChapter = entry.latestChapter,
            chapterCount = entry.chapterCount,
            // Sources that don't know the date report the start of time
            latestChapterUploadedAt = latestChapter?.dateUpload?.toEpochMilliseconds()?.takeIf { it > 0 },
        )
    }

    /**
     * Carries what the user did with [duplicate] over to [keep], then takes [duplicate] out of the library.
     * Nothing of [keep] is replaced, only added to.
     */
    private suspend fun merge(duplicate: Manga, keep: Manga) {
        // Read up to the same chapter and with the same bookmarks, the way a migration carries them over
        val duplicateChapters = getChaptersByMangaId.await(duplicate.id).filter { it.isRecognizedNumber }
        val maxChapterRead = duplicateChapters.filter { it.read }.maxOfOrNull { it.chapterNumber }
        val bookmarked = duplicateChapters.filter { it.bookmark }.mapTo(HashSet()) { it.chapterNumber }
        val chapterUpdates = getChaptersByMangaId.await(keep.id)
            .filter { it.isRecognizedNumber }
            .mapNotNull { chapter ->
                val markRead = !chapter.read && maxChapterRead != null && chapter.chapterNumber <= maxChapterRead
                val markBookmarked = !chapter.bookmark && chapter.chapterNumber in bookmarked
                if (!markRead && !markBookmarked) return@mapNotNull null

                ChapterUpdate(chapter.id) {
                    if (markRead) read = true
                    if (markBookmarked) bookmark = true
                }
            }
        if (chapterUpdates.isNotEmpty()) {
            updateChapter.awaitAll(chapterUpdates)
        }

        val categoryIds = (getCategories.await(keep.id) + getCategories.await(duplicate.id))
            .map { it.id }
            .distinct()
        setMangaCategories.await(keep.id, categoryIds)

        val trackers = getTracks.await(keep.id).mapTo(HashSet()) { it.trackerId }
        val tracks = getTracks.await(duplicate.id)
            .filter { it.trackerId !in trackers }
            .map { it.copy(mangaId = keep.id) }
        if (tracks.isNotEmpty()) {
            upsertTrack.awaitAll(tracks)
        }

        updateManga.awaitAll(
            listOf(
                MangaUpdate(duplicate.id) {
                    favoriteAt = null
                },
            ),
        )
    }

    private fun Source.displayName(): String {
        return when {
            name.isBlank() -> id.toString()
            lang.isBlank() -> name
            else -> "$name (${lang.uppercase()})"
        }
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        /**
         * @param skipped keys of the groups the user chose to leave as they are.
         */
        @Immutable
        data class Ready(
            val groups: List<Group>,
            val skipped: Set<Long> = emptySet(),
            val showConfirmation: Boolean = false,
            val isRemoving: Boolean = false,
        ) : State {
            val selected: List<Group> = groups.filterNot { it.key in skipped }

            val removeCount: Int = selected.sumOf { it.remove.size }
        }
    }

    /**
     * Library entries of one series.
     *
     * @param keep the one that stays.
     * @param remove the ones that are taken out of the library, best first.
     */
    @Immutable
    data class Group(
        val key: Long,
        val title: String,
        val keep: Entry,
        val remove: List<Entry>,
    )

    @Immutable
    data class Entry(
        val manga: Manga,
        val sourceName: String,
        val isSourceInstalled: Boolean,
        val chapterCount: Int,
        val latestChapter: Double?,
    )
}
