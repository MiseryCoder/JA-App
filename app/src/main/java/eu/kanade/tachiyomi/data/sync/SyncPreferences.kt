package eu.kanade.tachiyomi.data.sync

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * State of the Google Drive sync on this device. Everything is kept as app state, so it's never part of the
 * settings that are synced between devices.
 */
@Inject
@SingleIn(AppScope::class)
class SyncPreferences(
    preferenceStore: PreferenceStore,
) {

    val enabled: Preference<Boolean> = preferenceStore.getBoolean(
        Preference.appStateKey("drive_sync_enabled"),
        false,
    )

    val lastSyncTimestamp: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("drive_sync_last_timestamp"),
        0L,
    )

    /**
     * MD5 of the backup this device last left in Google Drive. If the file there still has it, no other device
     * has synced since.
     */
    val lastSyncedChecksum: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("drive_sync_last_checksum"),
        "",
    )

    /**
     * Package names of the extensions installed across the synced devices, sorted and separated by commas.
     * Unlike everything else here it's a regular preference, which is what carries it to the other devices
     * along with the synced settings.
     */
    val sharedExtensions: Preference<String> = preferenceStore.getString("sync_shared_extensions", "")

    /**
     * Package names of the extensions that were installed on this device when it last synced.
     */
    val syncedExtensions: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("drive_sync_extension_snapshot"),
        emptySet(),
    )

    /**
     * Package names of missing extensions whose install was already offered here and not accepted.
     */
    val attemptedExtensions: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("drive_sync_extension_attempts"),
        emptySet(),
    )

    /**
     * Message of what went wrong in the last sync, or empty if it succeeded.
     */
    val lastError: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("drive_sync_last_error"),
        "",
    )
}
