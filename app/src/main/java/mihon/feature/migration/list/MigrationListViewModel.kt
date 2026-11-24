package mihon.feature.migration.list

import androidx.annotation.FloatRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.migration.usecases.MigrateMangaUseCase
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.feature.migration.auto.missingSourceMatchComparator
import mihon.feature.migration.list.models.MigratingManga
import mihon.feature.migration.list.models.MigratingManga.SearchResult
import mihon.feature.migration.list.search.SmartSourceSearchEngine
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapterSummaryByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

@AssistedInject
class MigrationListViewModel(
    @Assisted mangaIds: Collection<Long>,
    @Assisted extraSearchQuery: String?,
    @Assisted private val searchAllSources: Boolean,
    private val preferences: SourcePreferences,
    private val sourceManager: SourceManager,
    private val getManga: GetManga,
    private val networkToLocalManga: NetworkToLocalManga,
    private val getChapterSummaryByMangaId: GetChapterSummaryByMangaId,
    private val migrateManga: MigrateMangaUseCase,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
) : ViewModel() {

    val state: StateFlow<MigrationListViewModel.State>
        field = MutableStateFlow<MigrationListViewModel.State>(State())

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(
            mangaIds: Collection<Long>,
            extraSearchQuery: String?,
            searchAllSources: Boolean,
        ): MigrationListViewModel
    }

    private val smartSearchEngine = SmartSourceSearchEngine(extraSearchQuery)

    val items
        inline get() = state.value.items

    // Entries of a missing source are all listed: one without a match or without newer chapters still can't be
    // read where it is
    private val hideUnmatched = !searchAllSources && preferences.migrationHideUnmatched.get()
    private val hideWithoutUpdates = !searchAllSources && preferences.migrationHideWithoutUpdates.get()

    private val navigateBackChannel = Channel<Unit>()
    val navigateBackEvent = navigateBackChannel.receiveAsFlow()

    private var migrateJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val manga = mangaIds
                .map {
                    async {
                        val manga = getManga.await(it) ?: return@async null
                        val chapterInfo = getChapterInfo(it)
                        MigratingManga(
                            manga = manga,
                            chapterCount = chapterInfo.chapterCount,
                            latestChapter = chapterInfo.latestChapter,
                            source = sourceManager.getOrStub(manga.source).getNameForMangaInfo(),
                            parentContext = viewModelScope.coroutineContext,
                        )
                    }
                }
                .awaitAll()
                .filterNotNull()
            state.update { it.copy(items = manga) }
            runMigrations(manga)
        }
    }

    private suspend fun getChapterInfo(id: Long) = getChapterSummaryByMangaId.await(id).let { summary ->
        ChapterInfo(
            latestChapter = summary.latestChapterNumber,
            chapterCount = summary.chapterCount.toInt(),
        )
    }

    private suspend fun Manga.toSuccessSearchResult(): SearchResult.Success {
        val chapterInfo = getChapterInfo(id)
        val source = sourceManager.getOrStub(source).getNameForMangaInfo()
        return SearchResult.Success(
            manga = this,
            chapterCount = chapterInfo.chapterCount,
            latestChapter = chapterInfo.latestChapter,
            source = source,
        )
    }

    private suspend fun runMigrations(mangas: List<MigratingManga>) {
        val prioritizeByChapters = searchAllSources || preferences.migrationPrioritizeByChapters.get()
        val deepSearchMode = preferences.migrationDeepSearchMode.get()

        val sources: List<Source> = if (searchAllSources) {
            getEnabledSources()
        } else {
            preferences.migrationSources.get()
                .mapNotNull { sourceManager.get(it) }
        }
        val sourceLanguages = sources.associate { it.id to it.lang }

        for (manga in mangas) {
            if (!currentCoroutineContext().isActive) break
            if (manga.manga.id !in state.value.mangaIds) continue
            if (manga.searchResult.value != SearchResult.Searching) continue
            if (!manga.migrationScope.isActive) continue

            val result = try {
                manga.migrationScope.async {
                    if (prioritizeByChapters) {
                        val sourceSemaphore = Semaphore(5)
                        val language = sourceManager.getOrStub(manga.manga.source).lang
                        sources.map { source ->
                            async innerAsync@{
                                sourceSemaphore.withPermit {
                                    val result = searchSource(manga.manga, source, deepSearchMode)
                                    if (result == null || result.second.chapterCount == 0) return@innerAsync null
                                    result
                                }
                            }
                        }
                            .mapNotNull { it.await() }
                            .let { results ->
                                if (searchAllSources) {
                                    results.maxWithOrNull(bestMatchComparator(language, sourceLanguages))
                                } else {
                                    results.maxByOrNull { it.second.latestChapter ?: 0.0 }
                                }
                            }
                    } else {
                        sources.forEach { source ->
                            val result = searchSource(manga.manga, source, deepSearchMode)
                            if (result != null) return@async result
                        }
                        null
                    }
                }
                    .await()
            } catch (_: CancellationException) {
                continue
            }

            if (result != null && result.first.thumbnailUrl == null) {
                try {
                    updateMangaFromRemote(result.first, fetchDetails = true, manualFetch = true).getOrThrow().manga
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }

            manga.searchResult.value = result?.first?.toSuccessSearchResult() ?: SearchResult.NotFound

            if (result == null && hideUnmatched) {
                removeManga(manga)
            }
            if (result != null &&
                hideWithoutUpdates &&
                (result.second.latestChapter ?: 0.0) <= (manga.latestChapter ?: 0.0)
            ) {
                removeManga(manga)
            }

            updateMigrationProgress()
        }
    }

    /**
     * Returns the sources the user browses: the installed ones in an enabled language that aren't disabled.
     */
    private suspend fun getEnabledSources(): List<Source> {
        val languages = preferences.enabledLanguages.get()
        val disabledSources = preferences.disabledSources.get()
        return sourceManager.getAll()
            .filterIsInstance<HttpSource>()
            .filter { it.lang in languages && it.id.toString() !in disabledSources }
    }

    private fun bestMatchComparator(
        language: String,
        sourceLanguages: Map<Long, String>,
    ): Comparator<Pair<Manga, ChapterInfo>> {
        return missingSourceMatchComparator<Pair<Manga, ChapterInfo>>(
            language = language,
            sourceLanguage = { sourceLanguages[it.first.source] },
            latestChapter = { it.second.latestChapter },
            chapterCount = { it.second.chapterCount },
        )
    }

    private suspend fun searchSource(
        manga: Manga,
        source: Source,
        deepSearchMode: Boolean,
    ): Pair<Manga, ChapterInfo>? {
        return try {
            val searchResult = if (deepSearchMode) {
                smartSearchEngine.deepSearch(source, manga.title)
            } else {
                smartSearchEngine.regularSearch(source, manga.title)
            }

            if (searchResult == null || (searchResult.url == manga.url && source.id == manga.source)) return null

            val localManga = networkToLocalManga(searchResult)
            try {
                updateMangaFromRemote(localManga, fetchChapters = true).getOrThrow()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            }
            localManga to getChapterInfo(localManga.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun updateMigrationProgress() {
        state.update { state ->
            state.copy(
                finishedCount = items.count { it.searchResult.value != SearchResult.Searching },
                migrationComplete = migrationComplete(),
            )
        }
        if (items.isEmpty()) {
            navigateBack()
        }
    }

    private fun migrationComplete() = items.all { it.searchResult.value != SearchResult.Searching } &&
        items.any { it.searchResult.value is SearchResult.Success }

    fun useMangaForMigration(current: Long, target: Long, onMissingChapters: () -> Unit) {
        val migratingManga = items.find { it.manga.id == current } ?: return
        migratingManga.searchResult.value = SearchResult.Searching
        viewModelScope.launch(Dispatchers.IO) {
            val result = migratingManga.migrationScope.async {
                val manga = getManga.await(target) ?: return@async null
                try {
                    val source = sourceManager.get(manga.source)!!
                    updateMangaFromRemote(source = source, manga = manga, fetchChapters = true).getOrThrow().manga
                } catch (_: Exception) {
                    null
                }
            }
                .await()

            if (result == null) {
                migratingManga.searchResult.value = SearchResult.NotFound
                withContext(Dispatchers.Main) { onMissingChapters() }
                return@launch
            }

            migratingManga.searchResult.value = result.toSuccessSearchResult()
            updateMigrationProgress()
        }
    }

    fun migrateMangas() {
        migrateMangas(replace = true)
    }

    fun copyMangas() {
        migrateMangas(replace = false)
    }

    private fun migrateMangas(replace: Boolean) {
        migrateJob = viewModelScope.launch(Dispatchers.IO) {
            state.update { it.copy(dialog = Dialog.Progress(0f)) }
            val items = items
            try {
                items.forEachIndexed { index, manga ->
                    try {
                        ensureActive()
                        val target = manga.searchResult.value.let {
                            if (it is SearchResult.Success) {
                                it.manga
                            } else {
                                null
                            }
                        }
                        if (target != null) {
                            migrateManga(current = manga.manga, target = target, replace = replace)
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logcat(LogPriority.WARN, throwable = e)
                    }
                    state.update {
                        it.copy(dialog = Dialog.Progress((index.toFloat() / items.size).coerceAtMost(1f)))
                    }
                }

                navigateBack()
            } finally {
                state.update { it.copy(dialog = null) }
                migrateJob = null
            }
        }
    }

    fun cancelMigrate() {
        migrateJob?.cancel()
        migrateJob = null
    }

    private suspend fun navigateBack() {
        navigateBackChannel.send(Unit)
    }

    fun migrateNow(mangaId: Long, replace: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val manga = items.find { it.manga.id == mangaId } ?: return@launch
            val target = (manga.searchResult.value as? SearchResult.Success)?.manga ?: return@launch
            migrateManga(current = manga.manga, target = target, replace = replace)

            removeManga(mangaId)
        }
    }

    fun removeManga(mangaId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val item = items.find { it.manga.id == mangaId } ?: return@launch
            removeManga(item)
            item.migrationScope.cancel()
            updateMigrationProgress()
        }
    }

    private fun removeManga(item: MigratingManga) {
        state.update { it.copy(items = items.toMutableList().apply { remove(item) }) }
    }

    override fun onCleared() {
        items.forEach {
            it.migrationScope.cancel()
        }
    }

    fun showMigrateDialog(copy: Boolean) {
        state.update { state ->
            state.copy(
                dialog = Dialog.Migrate(
                    copy = copy,
                    totalCount = items.size,
                    skippedCount = items.count { it.searchResult.value == SearchResult.NotFound },
                ),
            )
        }
    }

    fun showExitDialog() {
        state.update {
            it.copy(dialog = Dialog.Exit)
        }
    }

    fun dismissDialog() {
        state.update { it.copy(dialog = null) }
    }

    data class ChapterInfo(
        val latestChapter: Double?,
        val chapterCount: Int,
    )

    sealed interface Dialog {
        data class Migrate(val copy: Boolean, val totalCount: Int, val skippedCount: Int) : Dialog
        data class Progress(@FloatRange(0.0, 1.0) val progress: Float) : Dialog
        data object Exit : Dialog
    }

    data class State(
        val items: List<MigratingManga> = listOf(),
        val finishedCount: Int = 0,
        val migrationComplete: Boolean = false,
        val dialog: Dialog? = null,
    ) {
        val mangaIds: List<Long> = items.map { it.manga.id }
    }
}
