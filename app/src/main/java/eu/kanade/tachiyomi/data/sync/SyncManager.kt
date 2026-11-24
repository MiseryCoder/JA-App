package eu.kanade.tachiyomi.data.sync

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.BackupWriter
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import logcat.LogPriority
import okio.buffer
import okio.gzip
import okio.sink
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.i18n.MR
import java.io.File
import java.security.MessageDigest
import kotlin.time.Clock

/**
 * Keeps the library, reading progress, settings and extensions of this device in step with a backup file in
 * the user's Google Drive, which every device signed in to the same account shares.
 *
 * A sync downloads that file, merges it into this device the way restoring a backup does, and uploads a new
 * backup of the result.
 */
@Inject
@SingleIn(AppScope::class)
class SyncManager(
    private val context: Context,
    private val preferences: SyncPreferences,
    private val auth: GoogleDriveAuth,
    private val drive: GoogleDriveClient,
    private val extensionSync: ExtensionSync,
    private val backupCreatorFactory: BackupCreator.Factory,
    private val backupRestorerFactory: BackupRestorer.Factory,
    private val backupDecoder: BackupDecoder,
    private val notifier: BackupNotifier,
    private val parser: ProtoBuf,
    private val json: Json,
    private val getFavorites: GetFavorites,
    private val updateManga: UpdateManga,
) {

    private val mutex = Mutex()

    private val workDir = File(context.cacheDir, "sync")

    /**
     * The library entries this device had when it last synced. Comparing against it tells an entry that was
     * removed on one device from one that was added on the other.
     */
    private val snapshotFile = File(context.filesDir, "sync_library_snapshot.json")

    /**
     * Runs a sync, one at a time. What went wrong is kept in [SyncPreferences.lastError] and thrown.
     */
    suspend fun sync() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val startedAt = System.currentTimeMillis()
                try {
                    showProgress()
                    workDir.deleteRecursively()
                    workDir.mkdirs()

                    val token = auth.getAccessToken()
                    try {
                        sync(token)
                    } catch (e: GoogleDriveClient.UnauthorizedException) {
                        logcat(LogPriority.WARN, e) { "Retrying sync with a new access token" }
                        auth.clearToken(token)
                        sync(auth.getAccessToken())
                    }

                    preferences.lastSyncTimestamp.set(Clock.System.now().toEpochMilliseconds())
                    preferences.lastError.set("")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    preferences.lastError.set(e.message ?: e.toString())
                    throw e
                } finally {
                    // A sync with nothing to do is over before its notification could be noticed
                    val remaining = MIN_PROGRESS_VISIBLE_MILLIS - (System.currentTimeMillis() - startedAt)
                    if (remaining > 0) {
                        withContext(NonCancellable) { delay(remaining) }
                    }
                    context.cancelNotification(Notifications.ID_SYNC_PROGRESS)
                    context.cancelNotification(Notifications.ID_RESTORE_PROGRESS)
                    workDir.deleteRecursively()
                }
            }
        }
    }

    /**
     * Shows that a sync is going on, since it starts by itself and can take a while.
     */
    private fun showProgress() {
        context.notify(Notifications.ID_SYNC_PROGRESS, Notifications.CHANNEL_BACKUP_RESTORE_PROGRESS) {
            setSmallIcon(R.drawable.ic_mihon)
            setContentTitle(context.stringResource(MR.strings.syncing_library))
            setProgress(0, 0, true)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }
    }

    private suspend fun sync(token: String) {
        val remote = drive.findFile(token, SYNC_FILE_NAME)

        // If the file in Drive is still the one this device uploaded, there's nothing new in it to merge
        val remoteChanged = remote != null && remote.md5 != preferences.lastSyncedChecksum.get()
        if (remote != null && remoteChanged) {
            val remoteFile = File(workDir, "remote.tachibk")
            drive.download(token, remote.id, remoteFile)
            merge(remoteFile)
        }

        // Has to follow the merge, which brings the extension list of the other devices, and come before the
        // backup, which takes the updated list back to them
        val extensionPlan = extensionSync.prepare()

        // The backup creator writes over an existing file
        val localFile = File(workDir, "local.tachibk")
        localFile.delete()
        localFile.createNewFile()
        backupCreatorFactory.create(isAutoBackup = false).backup(Uri.fromFile(localFile), BackupOptions())
        val localChecksum = localFile.md5()

        when {
            remote == null -> {
                val folderId = drive.findFile(token, SYNC_FOLDER_NAME, GoogleDriveClient.FOLDER_MIME_TYPE)?.id
                    ?: drive.createFolder(token, SYNC_FOLDER_NAME)
                drive.createFile(token, SYNC_FILE_NAME, folderId, localFile)
            }
            // Nothing changed here either since the last upload
            !remoteChanged && remote.md5 == localChecksum -> Unit
            else -> drive.updateFile(token, remote.id, localFile)
        }
        preferences.lastSyncedChecksum.set(localChecksum)

        writeSnapshot(getFavorites.await().mapTo(HashSet()) { syncKey(it.source, it.url) })
        extensionSync.apply(extensionPlan)
    }

    /**
     * Merges the backup in [remoteFile] into this device.
     */
    private suspend fun merge(remoteFile: File) {
        var fileToRestore = remoteFile

        // Restoring only ever adds, so removals are worked out here from what changed since the last sync.
        // A device that hasn't synced before has nothing to compare against and just merges.
        val snapshot = readSnapshot()
        if (snapshot != null) {
            val remoteKeys = HashSet<String>()
            backupDecoder.decodeMetadata(Uri.fromFile(remoteFile), SyncEntry.serializer()) { entry ->
                if (entry.favorite) remoteKeys += syncKey(entry.source, entry.url)
            }
            val localFavorites = getFavorites.await()
            val localKeys = localFavorites.mapTo(HashSet()) { syncKey(it.source, it.url) }

            val removals = resolveRemovals(snapshot = snapshot, local = localKeys, remote = remoteKeys)

            localFavorites
                .filter { syncKey(it.source, it.url) in removals.removedRemotely }
                .forEach { updateManga.awaitUpdateFavorite(it.id, false) }

            if (removals.removedLocally.isNotEmpty()) {
                fileToRestore = File(workDir, "merged.tachibk")
                copyWithoutFavorites(remoteFile, fileToRestore, removals.removedLocally)
            }
        }

        backupRestorerFactory.create(notifier = notifier, isSync = true)
            .restore(Uri.fromFile(fileToRestore), RestoreOptions())
    }

    /**
     * Copies the backup in [source] to [target], with the entries in [keys] no longer marked as in the library.
     * Their chapters and history are kept, like for any other read entry that isn't in the library.
     */
    private suspend fun copyWithoutFavorites(source: File, target: File, keys: Set<String>) {
        val uri = Uri.fromFile(source)
        val metadata = backupDecoder.decodeMetadata(uri).backup

        target.sink().gzip().buffer().use { sink ->
            val writer = BackupWriter(sink, parser)
            backupDecoder.decodeEntries(uri).collect { entry ->
                if (syncKey(entry.source, entry.url) in keys) entry.favorite = false
                writer.writeEntry(entry)
            }
            writer.writeMetadata(metadata)
        }
    }

    private fun readSnapshot(): Set<String>? {
        if (!snapshotFile.exists()) return null
        return try {
            json.decodeFromString(SetSerializer(String.serializer()), snapshotFile.readText())
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Unreadable sync snapshot, merging without it" }
            null
        }
    }

    private fun writeSnapshot(keys: Set<String>) {
        snapshotFile.writeText(json.encodeToString(SetSerializer(String.serializer()), keys))
    }

    // Only the fields the sync compares, so decoding skips the rest of each entry
    @Serializable
    private class SyncEntry(
        @ProtoNumber(1) val source: Long,
        @ProtoNumber(2) val url: String,
        @ProtoNumber(100) val favorite: Boolean = true,
    )
}

/**
 * Returns the MD5 of the content as lowercase hex, the way Google Drive reports it.
 */
internal fun File.md5(): String {
    val digest = MessageDigest.getInstance("MD5")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * What identifies the same library entry on every device.
 */
private fun syncKey(source: Long, url: String) = "$source:$url"

internal class Removals(
    /**
     * Entries removed from the library of another device since this one last synced.
     */
    val removedRemotely: Set<String>,
    /**
     * Entries removed from the library of this device since it last synced.
     */
    val removedLocally: Set<String>,
)

/**
 * Works out which library entries were removed since the last sync. An entry that was in the library then
 * ([snapshot]) and is now missing on one side ([local] or [remote]) was removed there, rather than added on
 * the other side.
 */
internal fun resolveRemovals(snapshot: Set<String>, local: Set<String>, remote: Set<String>): Removals {
    val removedLocally = snapshot.filterTo(HashSet()) { it in remote && it !in local }
    val removedRemotely = snapshot.filterTo(HashSet()) { it in local && it !in remote }

    // A remote copy that lost most of the library is far more likely to be a new or replaced sync file than
    // someone clearing their library, and emptying this device over it can't be undone. Keep the entries then;
    // the next upload puts them back in the remote copy.
    val isSuspicious = remote.isEmpty() ||
        (removedRemotely.size > MAX_UNQUESTIONED_REMOVALS && removedRemotely.size > local.size / 2)

    return Removals(
        removedRemotely = if (isSuspicious) emptySet() else removedRemotely,
        removedLocally = removedLocally,
    )
}

private const val SYNC_FOLDER_NAME = "Mihon sync"
private const val SYNC_FILE_NAME = "mihon_sync.tachibk"

private const val MAX_UNQUESTIONED_REMOVALS = 10

private const val MIN_PROGRESS_VISIBLE_MILLIS = 3_000L
