package eu.kanade.tachiyomi.data.sync

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import tachiyomi.core.common.util.system.logcat
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the same extensions installed on every synced device.
 *
 * The list of extensions travels with the synced settings as [SyncPreferences.sharedExtensions]. Each sync
 * compares it with what's installed here, then installs what another device added and uninstalls what
 * another device removed.
 */
@Inject
class ExtensionSync(
    @AppCoroutineScope private val scope: CoroutineScope,
    private val preferences: SyncPreferences,
    private val extensionManager: ExtensionManager,
) {

    /**
     * Works out what changed since the last sync and updates the shared list to match. Call it after the
     * settings of the other devices are merged and before the backup to upload is made.
     *
     * @return what's left to do on this device, for [apply] once the upload went through.
     */
    suspend fun prepare(): Plan {
        val installed = installedExtensions().mapTo(HashSet()) { it.pkgName }
        val shared = preferences.sharedExtensions.get()
            .split(SEPARATOR)
            .filterTo(HashSet()) { it.isNotEmpty() }

        val changes = resolveExtensionChanges(
            snapshot = preferences.syncedExtensions.get(),
            installed = installed,
            shared = shared,
        )

        // Sorted so that an unchanged list makes an unchanged backup
        preferences.sharedExtensions.set(changes.shared.sorted().joinToString(SEPARATOR))

        return Plan(install = changes.install, uninstall = changes.uninstall, installed = installed)
    }

    /**
     * Installs and uninstalls what [plan] says, without waiting for it to finish. Android only lets an app ask
     * to install or uninstall while it's on screen, so in the background this is left for the next sync.
     */
    suspend fun apply(plan: Plan) {
        // Extensions that are no longer missing don't need remembering
        val attempted = preferences.attemptedExtensions.get().intersect(plan.install)
        preferences.attemptedExtensions.set(attempted)

        val isInForeground = withContext(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        if (!isInForeground) {
            preferences.syncedExtensions.set(plan.installed)
            return
        }

        // If an uninstall is turned down, the extension counts as installed again and returns to the other devices
        preferences.syncedExtensions.set(plan.installed - plan.uninstall)

        scope.launch {
            try {
                uninstall(plan.uninstall)
                install(plan.install - attempted)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to sync extensions" }
            }
        }
    }

    /**
     * Whether another device has extensions that are still to be installed here. Their sources only look
     * missing until then.
     */
    suspend fun hasPendingInstalls(): Boolean {
        val installed = installedExtensions().mapTo(HashSet()) { it.pkgName }
        val shared = preferences.sharedExtensions.get()
            .split(SEPARATOR)
            .filterTo(HashSet()) { it.isNotEmpty() }

        // One that was here at the last sync and is gone now was uninstalled here, not added elsewhere
        val pending = shared - installed - preferences.syncedExtensions.get() - preferences.attemptedExtensions.get()
        return pending.isNotEmpty()
    }

    private suspend fun install(pkgNames: Set<String>) {
        if (pkgNames.isEmpty()) return

        var listings = findListings(pkgNames)
        if (listings.size < pkgNames.size) {
            // The repos of the other device may only just have been merged in
            extensionManager.findAvailableExtensions()
            listings = findListings(pkgNames)
        }

        // One at a time, as each can come with a confirmation from Android
        for (listing in listings) {
            var reachedInstall = false
            val result = withTimeoutOrNull(INSTALL_TIMEOUT) {
                extensionManager.installExtension(listing)
                    .onEach { if (it == InstallStep.Installing) reachedInstall = true }
                    .first { it.isCompleted() }
            }

            // One that was turned down or left unanswered isn't asked about again. A failed download is.
            if (result != InstallStep.Installed && reachedInstall) {
                preferences.attemptedExtensions.set(preferences.attemptedExtensions.get() + listing.pkgName)
            }
        }
    }

    private suspend fun uninstall(pkgNames: Set<String>) {
        if (pkgNames.isEmpty()) return

        val extensions = installedExtensions().filter { it.pkgName in pkgNames }
        withContext(Dispatchers.Main) {
            extensions.forEach { extensionManager.uninstallExtension(it) }
        }
    }

    /**
     * Returns the newest listing in the user's repos for each of [pkgNames] that has one.
     */
    private suspend fun findListings(pkgNames: Set<String>): List<Extension.Available> {
        val available = withTimeoutOrNull(LISTINGS_TIMEOUT) {
            extensionManager.availableExtensionsFlow.first { it.isNotEmpty() }
        }
        return available.orEmpty()
            .filter { it.pkgName in pkgNames }
            .groupBy { it.pkgName }
            .map { (_, listings) -> listings.maxBy { it.versionCode } }
    }

    private suspend fun installedExtensions(): List<Extension.Installed> {
        return extensionManager.getLoadedExtensions() + extensionManager.getNotLoadedExtensions()
    }

    class Plan internal constructor(
        internal val install: Set<String>,
        internal val uninstall: Set<String>,
        internal val installed: Set<String>,
    )
}

internal class ExtensionChanges(
    /**
     * Extensions another device has that are missing here.
     */
    val install: Set<String>,
    /**
     * Extensions installed here that another device uninstalled.
     */
    val uninstall: Set<String>,
    /**
     * The list every device should end up with.
     */
    val shared: Set<String>,
)

/**
 * Compares the extensions [installed] on this device with the [shared] list of the other devices, using what
 * was installed here at the last sync ([snapshot]) to tell an uninstall on one side from an install on the other.
 */
internal fun resolveExtensionChanges(
    snapshot: Set<String>,
    installed: Set<String>,
    shared: Set<String>,
): ExtensionChanges {
    val removals = resolveRemovals(snapshot = snapshot, local = installed, remote = shared)
    val install = shared - installed - removals.removedLocally

    return ExtensionChanges(
        install = install,
        uninstall = removals.removedRemotely,
        shared = (installed - removals.removedRemotely) + install,
    )
}

private const val SEPARATOR = ","

private val LISTINGS_TIMEOUT = 15.seconds

// Long enough to download an extension and answer Android's confirmation
private val INSTALL_TIMEOUT = 3.minutes
