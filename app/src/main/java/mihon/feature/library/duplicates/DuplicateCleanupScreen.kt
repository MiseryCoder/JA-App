package mihon.feature.library.duplicates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Lists the series that are in the library more than once, with the entry that would be kept, and removes
 * the rest when the user confirms.
 */
class DuplicateCleanupScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<DuplicateCleanupViewModel>()
        val state by viewModel.state.collectAsState()
        val scope = rememberCoroutineScope()
        val s = state

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_clean_duplicates),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            when {
                s !is DuplicateCleanupViewModel.State.Ready || s.isRemoving -> {
                    LoadingScreen(Modifier.padding(contentPadding))
                }
                s.groups.isEmpty() -> {
                    EmptyScreen(
                        message = stringResource(MR.strings.duplicates_empty),
                        modifier = Modifier.padding(contentPadding),
                    )
                }
                else -> {
                    LazyColumnWithAction(
                        contentPadding = contentPadding,
                        actionLabel = stringResource(MR.strings.duplicates_action_remove, s.removeCount),
                        actionEnabled = s.removeCount > 0,
                        onClickAction = viewModel::showConfirmation,
                    ) {
                        items(
                            items = s.groups,
                            key = { it.key },
                        ) { group ->
                            DuplicateGroupItem(
                                group = group,
                                isSelected = group.key !in s.skipped,
                                onClickSelect = { viewModel.toggle(group) },
                            )
                        }
                    }
                }
            }
        }

        if (s is DuplicateCleanupViewModel.State.Ready && s.showConfirmation) {
            AlertDialog(
                onDismissRequest = viewModel::hideConfirmation,
                title = {
                    Text(text = stringResource(MR.strings.are_you_sure))
                },
                text = {
                    Text(text = stringResource(MR.strings.duplicates_confirm_text))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                viewModel.removeDuplicates()
                                context.toast(MR.strings.duplicates_completed)
                            }
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::hideConfirmation) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
    }

    @Composable
    private fun DuplicateGroupItem(
        group: DuplicateCleanupViewModel.Group,
        isSelected: Boolean,
        onClickSelect: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClickSelect)
                .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(MR.strings.duplicates_keep, entryInfo(group.keep)),
                    style = MaterialTheme.typography.bodySmall,
                )
                group.remove.forEach { entry ->
                    Text(
                        text = stringResource(MR.strings.duplicates_remove, entryInfo(entry)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClickSelect() },
            )
        }
    }

    @Composable
    private fun entryInfo(entry: DuplicateCleanupViewModel.Entry): String {
        val source = if (entry.isSourceInstalled) {
            entry.sourceName
        } else {
            stringResource(MR.strings.duplicates_source_not_installed, entry.sourceName)
        }
        val latestChapter = entry.latestChapter?.let { formatChapterNumber(it) } ?: "-"
        return stringResource(MR.strings.duplicates_entry_info, source, entry.chapterCount, latestChapter)
    }
}
