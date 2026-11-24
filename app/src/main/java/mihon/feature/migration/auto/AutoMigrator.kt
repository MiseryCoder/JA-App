package mihon.feature.migration.auto

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.sync.ExtensionSync
import eu.kanade.tachiyomi.data.sync.SyncPreferences
import eu.kanade.tachiyomi.data.sync.SyncWorker
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import mihon.domain.migration.usecases.MigrateMangaUseCase
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.feature.migration.list.search.SmartSourceSearchEngine
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapterSummaryByMangaId
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.UpsertTrack
import tachiyomi.i18n.MR
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Moves library entries whose source isn't installed to the best match among the sources that are, without
 * asking first. What it moved is kept in [results] for the user to look over and [revert].
 *
 * Nobody checks a match before it's used, so it only takes one whose title is practically the same. Anything
 * less certain is left for the migration the user starts and reviews themselves.
 */
@Inject
@SingleIn(AppScope::class)
class AutoMigrator(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val context: Context,
    private val preferences: SourcePreferences,
    private val syncPreferences: SyncPreferences,
    private val extensionSync: ExtensionSync,
    private val extensionManager: ExtensionManager,
    private val sourceManager: SourceManager,
    private val getFavorites: GetFavorites,
    private val getManga: GetManga,
    private val networkToLocalManga: NetworkToLocalManga,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val getChapterSummaryByMangaId: GetChapterSummaryByMangaId,
    private val migrateManga: MigrateMangaUseCase,
    private val updateManga: UpdateManga,
    private val getTracks: GetTracks,
    private val upsertTrack: UpsertTrack,
    private val json: Json,
) {

    private val searchEngine = SmartSourceSearchEngine(null)

    private val isRunning = MutableStateFlow(false)

    private val resultsLock = Mutex()

    /**
     * The migrations the user hasn't been shown yet. Empty while a run is in progress, so that they are shown
     * together once it's done.
     */
    val results: Flow<List<AutoMigration>> = combine(
        preferences.autoMigrationResults.changes(),
        isRunning,
    ) { encoded, running ->
        if (running) emptyList() else decodeResults(encoded)
    }
        .distinctUntilChanged()

    /**
     * Looks for entries with a missing source and migrates them, unless that's turned off or already going on.
     * Returns right away; the work carries on even if the screen that asked for it goes away.
     */
    fun start() {
        if (!preferences.migrationAutoMissingSources.get()) return
        if (!isRunning.compareAndSet(expect = false, update = true)) return

        scope.launch(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            try {
                showChecking()
                migrateMissingSources()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.ERROR, e) { "Automatic migration failed" }
            } finally {
                // A check that finds nothing is over before its notification could be noticed
                val remaining = MIN_PROGRESS_VISIBLE_MILLIS - (System.currentTimeMillis() - startedAt)
                if (remaining > 0) {
                    withContext(NonCancellable) { delay(remaining) }
                }
                context.cancelNotification(Notifications.ID_AUTO_MIGRATION_PROGRESS)
                isRunning.value = false
            }
        }
    }

    /**
     * Forgets the migrations in [results] once the user has seen them.
     */
    fun acknowledge() {
        preferences.autoMigrationResults.set("")
    }

    /**
     * Puts the original entry of [migration] back in the library in place of the one it was migrated to, and
     * makes sure it isn't migrated again.
     */
    suspend fun revert(migration: AutoMigration) {
        withContext(Dispatchers.IO) {
            val original = getManga.await(migration.originalId)
            val target = getManga.await(migration.targetId)
            if (original != null && target != null) {
                // The migration moved the tracking over rather than copying it
                getTracks.await(target.id)
                    .map { it.copy(mangaId = original.id) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { upsertTrack.awaitAll(it) }

                updateManga.awaitAll(
                    listOf(
                        MangaUpdate(original.id) {
                            favoriteAt = migration.favoriteAt
                                ?.let { Instant.fromEpochMilliseconds(it) }
                                ?: Clock.System.now()
                        },
                        MangaUpdate(target.id) {
                            favoriteAt = null
                        },
                    ),
                )
            }

            preferences.autoMigrationExcluded.set(
                preferences.autoMigrationExcluded.get() + migration.originalId.toString(),
            )
            updateResults { results -> results.filterNot { it.originalId == migration.originalId } }
        }
    }

    private suspend fun migrateMissingSources() {
        if (syncPreferences.enabled.get()) {
            // A sync that started with the app may still be bringing in entries and extensions of other devices
            delay(SYNC_START_DELAY)
            withTimeoutOrNull(SYNC_TIMEOUT) {
                while (SyncWorker.isRunning(context)) delay(SYNC_POLL_INTERVAL)
            }

            // An extension the sync is about to install here isn't missing
            if (extensionSync.hasPendingInstalls()) return
        }

        // Entries nothing was found for aren't searched again every time the app is opened
        val now = System.currentTimeMillis()
        val lastSearch = preferences.autoMigrationLastSearch.get()
        val retryUnmatched = now - lastSearch >= UNMATCHED_RETRY_INTERVAL.inWholeMilliseconds
        val previouslyUnmatched = preferences.autoMigrationUnmatched.get()
        val skipped = buildSet {
            addAll(preferences.autoMigrationExcluded.get())
            if (!retryUnmatched) addAll(previouslyUnmatched)
        }

        val missing = getFavorites.await()
            .filter { it.id.toString() !in skipped && sourceManager.get(it.source) == null }
        if (missing.isEmpty()) return

        val unloadedSources = getSourcesOfUnloadedExtensions() ?: return
        val sources = getEnabledSources()
        if (sources.isEmpty()) return

        val unmatched = HashSet<String>()
        for ((index, manga) in missing.withIndex()) {
            if (!preferences.migrationAutoMissingSources.get()) break
            if (manga.source in unloadedSources) continue

            showProgress(done = index, total = missing.size)

            val search = findMatch(manga, sources)
            val match = search.match
            if (match == null) {
                // Only give up on it for now if the sources could actually be asked
                if (search.isConclusive) unmatched += manga.id.toString()
                continue
            }

            // It may have been removed or migrated by hand in the meantime
            val current = getManga.await(manga.id)?.takeIf { it.favorite } ?: continue
            migrateManga(current = current, target = match.manga, replace = true)

            // The migration doesn't say how it went, so look at what it left behind
            if (getManga.await(match.manga.id)?.favorite != true) {
                unmatched += manga.id.toString()
                continue
            }

            val result = AutoMigration(
                originalId = current.id,
                targetId = match.manga.id,
                title = current.title,
                fromSource = sourceManager.getOrStub(current.source).displayName(),
                toSource = sourceManager.getOrStub(match.manga.source).displayName(),
                favoriteAt = current.favoriteAt?.toEpochMilliseconds(),
            )
            updateResults { it + result }
        }

        if (retryUnmatched) {
            preferences.autoMigrationUnmatched.set(unmatched)
            preferences.autoMigrationLastSearch.set(now)
        } else {
            preferences.autoMigrationUnmatched.set(previouslyUnmatched + unmatched)
        }
    }

    /**
     * Shows that the library is being looked over, which happens by itself every time the app is opened.
     */
    private fun showChecking() {
        context.notify(Notifications.ID_AUTO_MIGRATION_PROGRESS, Notifications.CHANNEL_LIBRARY_PROGRESS) {
            setSmallIcon(R.drawable.ic_mihon)
            setContentTitle(context.stringResource(MR.strings.auto_migration_checking_title))
            setProgress(0, 0, true)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }
    }

    /**
     * Shows how far along the run is, since it starts by itself and searching every source takes a while.
     */
    private fun showProgress(done: Int, total: Int) {
        context.notify(Notifications.ID_AUTO_MIGRATION_PROGRESS, Notifications.CHANNEL_LIBRARY_PROGRESS) {
            setSmallIcon(R.drawable.ic_mihon)
            setContentTitle(context.stringResource(MR.strings.auto_migration_progress_title))
            setContentText("$done/$total")
            setProgress(total, done, false)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }
    }

    /**
     * Searches [sources] for [manga] and picks the best of what they have.
     */
    private suspend fun findMatch(manga: Manga, sources: List<Source>): Search = coroutineScope {
        val language = sourceManager.getOrStub(manga.source).lang
        val semaphore = Semaphore(MAX_CONCURRENT_SEARCHES)
        val outcomes = sources
            .map { source ->
                async { semaphore.withPermit { searchSource(manga, source) } }
            }
            .awaitAll()

        // With the series already in the library from another source, migrating would merge the two entries
        val isInLibrary = outcomes.any { it == Outcome.InLibrary }
        val matches = outcomes.filterIsInstance<Outcome.Found>().map { it.match }

        Search(
            match = matches
                .takeUnless { isInLibrary }
                ?.maxWithOrNull(
                    missingSourceMatchComparator<Match>(
                        language = language,
                        sourceLanguage = { it.language },
                        latestChapter = { it.latestChapter },
                        chapterCount = { it.chapterCount },
                    ),
                ),
            isConclusive = outcomes.any { it != Outcome.Failed },
        )
    }

    private suspend fun searchSource(manga: Manga, source: Source): Outcome {
        return try {
            val found = searchEngine.regularSearch(source, manga.title)
            if (found == null || !isSameTitle(manga.title, found.title)) return Outcome.NotFound

            val localManga = networkToLocalManga(found)
            if (localManga.favorite) return Outcome.InLibrary

            updateMangaFromRemote(localManga, fetchChapters = true).getOrThrow()
            val summary = getChapterSummaryByMangaId.await(localManga.id)
            if (summary.chapterCount == 0L) return Outcome.NotFound

            Outcome.Found(
                Match(
                    manga = localManga,
                    language = source.lang,
                    latestChapter = summary.latestChapterNumber,
                    chapterCount = summary.chapterCount.toInt(),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Outcome.Failed
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

    /**
     * Returns the sources of extensions that are installed but not loaded, for instance until they are trusted.
     * Their entries aren't orphaned. Null if there are such extensions and no way to tell what's in them.
     */
    private suspend fun getSourcesOfUnloadedExtensions(): Set<Long>? {
        val unloaded = extensionManager.getNotLoadedExtensions().mapTo(HashSet()) { it.pkgName }
        if (unloaded.isEmpty()) return emptySet()

        val listings = withTimeoutOrNull(LISTINGS_TIMEOUT) {
            extensionManager.availableExtensionsFlow.first { it.isNotEmpty() }
        }
            ?: return null

        return listings
            .filter { it.pkgName in unloaded }
            .flatMapTo(HashSet()) { listing -> listing.sources.map { it.id } }
    }

    private suspend fun updateResults(action: (List<AutoMigration>) -> List<AutoMigration>) {
        resultsLock.withLock {
            val updated = action(decodeResults(preferences.autoMigrationResults.get()))
            preferences.autoMigrationResults.set(json.encodeToString(resultsSerializer, updated))
        }
    }

    private fun decodeResults(encoded: String): List<AutoMigration> {
        if (encoded.isEmpty()) return emptyList()
        return try {
            json.decodeFromString(resultsSerializer, encoded)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Unreadable automatic migration results" }
            emptyList()
        }
    }

    private fun Source.displayName(): String {
        return when {
            name.isBlank() -> id.toString()
            lang.isBlank() -> name
            else -> "$name (${lang.uppercase()})"
        }
    }

    private class Match(
        val manga: Manga,
        val language: String,
        val latestChapter: Double?,
        val chapterCount: Int,
    )

    /**
     * @param isConclusive whether any source answered, as opposed to all of them failing, e.g. while offline.
     */
    private class Search(val match: Match?, val isConclusive: Boolean)

    private sealed interface Outcome {
        data class Found(val match: Match) : Outcome
        data object NotFound : Outcome
        data object InLibrary : Outcome
        data object Failed : Outcome
    }
}

private val resultsSerializer = ListSerializer(AutoMigration.serializer())

private const val MAX_CONCURRENT_SEARCHES = 5

private const val MIN_PROGRESS_VISIBLE_MILLIS = 3_000L

// Time for a sync started along with the app to get going, and how long to wait for it to finish
private val SYNC_START_DELAY = 5.seconds
private val SYNC_POLL_INTERVAL = 2.seconds
private val SYNC_TIMEOUT = 3.minutes

private val LISTINGS_TIMEOUT = 30.seconds

private val UNMATCHED_RETRY_INTERVAL = 24.hours
