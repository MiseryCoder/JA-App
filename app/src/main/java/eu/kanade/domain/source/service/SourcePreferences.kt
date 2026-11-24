package eu.kanade.domain.source.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.tachiyomi.util.system.LocaleHelper
import mihon.domain.extension.model.ContentWarning
import mihon.domain.migration.models.MigrationFlag
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.preference.getEnumSet
import tachiyomi.core.common.preference.getLongArray
import tachiyomi.domain.library.model.LibraryDisplayMode

@Inject
@SingleIn(AppScope::class)
class SourcePreferences(
    preferenceStore: PreferenceStore,
) {

    val sourceDisplayMode: Preference<LibraryDisplayMode> = preferenceStore.getObjectFromString(
        "pref_display_mode_catalogue",
        LibraryDisplayMode.default,
        LibraryDisplayMode.Serializer::serialize,
        LibraryDisplayMode.Serializer::deserialize,
    )

    val enabledLanguages: Preference<Set<String>> = preferenceStore.getStringSet(
        "source_languages",
        LocaleHelper.getDefaultEnabledLanguages(),
    )

    val disabledSources: Preference<Set<String>> = preferenceStore.getStringSet("hidden_catalogues", emptySet())

    val incognitoExtensions: Preference<Set<String>> = preferenceStore.getStringSet("incognito_extensions", emptySet())

    val pinnedSources: Preference<Set<String>> = preferenceStore.getStringSet("pinned_catalogues", emptySet())

    val lastUsedSource: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("last_catalogue_source"),
        -1,
    )

    val enabledContentWarnings: Preference<Set<ContentWarning>> = preferenceStore.getEnumSet(
        "enabled_content_warnings",
        setOf(ContentWarning.SAFE, ContentWarning.MIXED, ContentWarning.NSFW),
    )

    val applyContentWarningsToInstalled: Preference<Boolean> = preferenceStore.getBoolean(
        "apply_content_warnings_to_installed",
        true,
    )

    val migrationSortingMode: Preference<SetMigrateSorting.Mode> = preferenceStore.getEnum(
        "pref_migration_sorting",
        SetMigrateSorting.Mode.ALPHABETICAL,
    )

    val migrationSortingDirection: Preference<SetMigrateSorting.Direction> = preferenceStore.getEnum(
        "pref_migration_direction",
        SetMigrateSorting.Direction.ASCENDING,
    )

    val hideInLibraryItems: Preference<Boolean> = preferenceStore.getBoolean("browse_hide_in_library_items", false)

    val extensionRepos: Preference<Set<String>> = preferenceStore.getStringSet("extension_repos", emptySet())

    val extensionUpdatesCount: Preference<Int> = preferenceStore.getInt("ext_updates_count", 0)

    val trustedExtensions: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("trusted_extensions"),
        emptySet(),
    )

    val globalSearchFilterState: Preference<Boolean> = preferenceStore.getBoolean(
        Preference.appStateKey("has_filters_toggle_state"),
        false,
    )

    val migrationSources: Preference<List<Long>> = preferenceStore.getLongArray("migration_sources", emptyList())

    val migrationFlags: Preference<Set<MigrationFlag>> = preferenceStore.getObjectFromInt(
        key = "migration_flags",
        defaultValue = MigrationFlag.entries.toSet(),
        serializer = { MigrationFlag.toBit(it) },
        deserializer = { value: Int -> MigrationFlag.fromBit(value) },
    )

    val migrationDeepSearchMode: Preference<Boolean> = preferenceStore.getBoolean("migration_deep_search", false)

    val migrationPrioritizeByChapters: Preference<Boolean> = preferenceStore.getBoolean(
        "migration_prioritize_by_chapters",
        false,
    )

    val migrationHideUnmatched: Preference<Boolean> = preferenceStore.getBoolean("migration_hide_unmatched", false)

    val migrationHideWithoutUpdates: Preference<Boolean> = preferenceStore.getBoolean(
        "migration_hide_without_updates",
        false,
    )

    val migrationAutoMissingSources: Preference<Boolean> = preferenceStore.getBoolean(
        "migration_auto_missing_sources",
        true,
    )

    /**
     * The automatic migrations the user hasn't been shown yet, as JSON.
     */
    val autoMigrationResults: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("auto_migration_results"),
        "",
    )

    /**
     * IDs of the entries whose automatic migration was reverted, which aren't migrated again.
     */
    val autoMigrationExcluded: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("auto_migration_excluded"),
        emptySet(),
    )

    /**
     * IDs of the entries no match was found for when they were last searched, at [autoMigrationLastSearch].
     */
    val autoMigrationUnmatched: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("auto_migration_unmatched"),
        emptySet(),
    )

    val autoMigrationLastSearch: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("auto_migration_last_search"),
        0L,
    )
}
