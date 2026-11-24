package mihon.feature.migration.auto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Tells the user which entries were migrated off missing sources on their behalf, and lets them undo any of it.
 */
@Composable
fun AutoMigrationResultsDialog(
    migrations: List<AutoMigration>,
    onRevert: (AutoMigration) -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(MR.strings.auto_migration_dialog_title))
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                item {
                    Text(text = stringResource(MR.strings.auto_migration_dialog_message))
                }
                items(
                    items = migrations,
                    key = { it.originalId },
                ) { migration ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = migration.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = stringResource(
                                    MR.strings.auto_migration_dialog_sources,
                                    migration.fromSource,
                                    migration.toSource,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { onRevert(migration) }) {
                            Text(text = stringResource(MR.strings.auto_migration_action_revert))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}
