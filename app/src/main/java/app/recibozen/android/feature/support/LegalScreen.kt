package app.recibozen.android.feature.support

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavHostController
import app.recibozen.android.R
import app.recibozen.android.core.components.RzCard
import app.recibozen.android.core.components.RzFlowTopBar
import app.recibozen.android.core.components.RzSecondaryButton
import app.recibozen.android.core.designsystem.ReciboZenTheme
import app.recibozen.android.core.designsystem.RzSpacing
import java.io.File

/**
 * Privacy and terms, written for the person using the app rather than for a
 * compliance checklist: what is stored, what leaves the phone, and a button
 * that actually deletes the saved photos.
 */
@Composable
fun LegalRoute(navController: NavHostController) {
    val context = LocalContext.current
    var deletedCount by remember { mutableStateOf<Int?>(null) }
    LegalScreen(
        onBack = navController::popBackStack,
        deletedCount = deletedCount,
        onDeletePhotos = { deletedCount = deleteStoredPhotos(context) },
    )
}

/**
 * Removes every meter/bill image the app kept. Readings are stored separately,
 * so deleting the pictures never costs the user a measurement.
 */
internal fun deleteStoredPhotos(context: Context): Int {
    val folders = listOf(
        File(context.cacheDir, "reading_photos"),
        File(context.cacheDir, "bill_photos"),
        File(context.filesDir, "reading_photos"),
    )
    return folders.sumOf { folder ->
        folder.listFiles()?.count { file -> file.isFile && file.delete() } ?: 0
    }
}

@Composable
fun LegalScreen(
    onBack: () -> Unit,
    deletedCount: Int?,
    onDeletePhotos: () -> Unit,
) {
    Scaffold(topBar = { RzFlowTopBar(stringResource(R.string.legal_title), onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal, vertical = RzSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.md),
        ) {
            Text(
                stringResource(R.string.legal_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LegalSection(R.string.legal_data_title, R.string.legal_data_body)

            RzCard(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.legal_photos_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.legal_photos_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = RzSpacing.sm),
                )
                RzSecondaryButton(
                    text = stringResource(R.string.legal_photos_action),
                    onClick = onDeletePhotos,
                    modifier = Modifier.fillMaxWidth().padding(top = RzSpacing.md),
                )
                deletedCount?.let { count ->
                    Text(
                        if (count > 0) stringResource(R.string.legal_photos_deleted, count)
                        else stringResource(R.string.legal_photos_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = RzSpacing.sm),
                    )
                }
            }

            LegalSection(R.string.legal_network_title, R.string.legal_network_body)
            LegalSection(R.string.legal_permissions_title, R.string.legal_permissions_body)
            LegalSection(R.string.legal_rights_title, R.string.legal_rights_body)
            LegalSection(R.string.legal_disclaimer_title, R.string.legal_disclaimer_body)
            // Google Play requires a reachable link to the original source of any
            // government information an app presents. The tariffs, their blocks
            // and the DAC limits are all published by CFE; showing them without
            // citing where they come from is what got the listing rejected.
            SourcesSection()
        }
    }
}

@Composable
private fun LegalSection(titleRes: Int, bodyRes: Int) {
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.sm),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LegalScreenPreview() {
    ReciboZenTheme {
        LegalScreen(onBack = {}, deletedCount = null, onDeletePhotos = {})
    }
}

@Composable
private fun SourcesSection() {
    val uriHandler = LocalUriHandler.current
    RzCard(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.legal_sources_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.legal_sources_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = RzSpacing.xs),
        )
        listOf(
            R.string.legal_sources_cfe to R.string.legal_sources_cfe_url,
            R.string.legal_sources_cre to R.string.legal_sources_cre_url,
            R.string.legal_sources_nasa to R.string.legal_sources_nasa_url,
        ).forEach { (labelRes, urlRes) ->
            val url = stringResource(urlRes)
            Text(
                stringResource(labelRes),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = RzSpacing.sm)
                    .clickable { runCatching { uriHandler.openUri(url) } },
            )
        }
    }
}
