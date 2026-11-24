package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.authenticate
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.domain.extension.model.ContentWarning
import mihon.feature.recommended.RecommendedSetup
import mihon.feature.recommended.RecommendedSetupDialog
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

object SettingsBrowseScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.browse

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val sourcePreferences = remember { context.appGraph.sourcePreferences }
        val getExtensionStoreCountAsFlow = remember { context.appGraph.getExtensionStoreCountAsFlow }

        val reposCount by getExtensionStoreCountAsFlow().collectAsState(0)

        val recommendedSetup = remember { context.appGraph.recommendedSetup }
        val scope = rememberCoroutineScope()
        var suggestion by remember { mutableStateOf<RecommendedSetup?>(null) }
        suggestion?.let { setup ->
            RecommendedSetupDialog(
                setup = setup,
                onConfirm = { selected ->
                    recommendedSetup.markHandled()
                    recommendedSetup.install(selected)
                    suggestion = null
                    context.toast(MR.strings.recommended_setup_installing)
                },
                onDismissRequest = { suggestion = null },
            )
        }

        val recommendedSetupItems: List<Preference.PreferenceItem<out Any, out Any>> = if (
            recommendedSetup.isAvailable
        ) {
            listOf(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.recommended_setup),
                    subtitle = stringResource(MR.strings.recommended_setup_summary),
                    onClick = {
                        scope.launch {
                            try {
                                val setup = recommendedSetup.fetch()
                                val missing = setup?.let { recommendedSetup.missing(it) }
                                when {
                                    setup == null -> context.toast(MR.strings.recommended_setup_none)
                                    missing == null || missing.isEmpty -> {
                                        context.toast(MR.strings.recommended_setup_complete)
                                    }
                                    else -> suggestion = missing
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logcat(LogPriority.WARN, e) { "Couldn't get the recommended setup" }
                                context.toast(MR.strings.recommended_setup_failed)
                            }
                        }
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.recommended_setup_share),
                    subtitle = stringResource(MR.strings.recommended_setup_share_summary),
                    onClick = {
                        scope.launch {
                            val file = recommendedSetup.export()
                            context.startActivity(
                                file.getUriCompat(context).toShareIntent(context, type = "application/json"),
                            )
                        }
                    },
                ),
            )
        } else {
            emptyList()
        }

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.label_sources),
                preferenceItems = listOf<Preference.PreferenceItem<out Any, out Any>>(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.hideInLibraryItems,
                        title = stringResource(MR.strings.pref_hide_in_library_items),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.migrationAutoMissingSources,
                        title = stringResource(MR.strings.pref_auto_migrate_missing_sources),
                        subtitle = stringResource(MR.strings.pref_auto_migrate_missing_sources_summary),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.extensionStores),
                        subtitle = pluralStringResource(MR.plurals.num_repos, reposCount.toInt(), reposCount),
                        onClick = {
                            navigator.push(ExtensionStoresScreen())
                        },
                    ),
                ) + recommendedSetupItems,
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_extensions),
                preferenceItems = listOf(
                    Preference.PreferenceItem.MultiSelectListPreference(
                        preference = sourcePreferences.enabledContentWarnings,
                        entries = mapOf(
                            ContentWarning.SAFE to stringResource(MR.strings.ext_content_warning_safe),
                            ContentWarning.MIXED to stringResource(MR.strings.ext_content_warning_mixed),
                            ContentWarning.NSFW to stringResource(MR.strings.ext_content_warning_nsfw),
                        ),
                        title = stringResource(MR.strings.pref_allowed_content_warnings),
                        subtitleProvider = { value, entries ->
                            remember(value, entries) {
                                entries.filterKeys { it in value }.values.joinToString()
                            }
                                .takeUnless { it.isBlank() }
                                ?: stringResource(MR.strings.none)
                        },
                        onValueChanged = { newValue ->
                            val added = newValue - sourcePreferences.enabledContentWarnings.get()
                            if (added.any { it != ContentWarning.SAFE }) {
                                (context as FragmentActivity).authenticate(
                                    title = context.stringResource(MR.strings.pref_allowed_content_warnings),
                                )
                            } else {
                                true
                            }
                        },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = sourcePreferences.applyContentWarningsToInstalled,
                        title = stringResource(MR.strings.pref_apply_content_warnings_to_installed),
                        subtitle = stringResource(MR.strings.pref_apply_content_warnings_to_installed_summary),
                        onValueChanged = { newValue ->
                            if (newValue) {
                                true
                            } else {
                                (context as FragmentActivity).authenticate(
                                    title = context.stringResource(
                                        MR.strings.pref_apply_content_warnings_to_installed,
                                    ),
                                )
                            }
                        },
                    ),
                    Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.content_warnings_info)),
                ),
            ),
        )
    }
}
