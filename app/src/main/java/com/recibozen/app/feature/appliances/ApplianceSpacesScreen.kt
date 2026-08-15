package com.recibozen.app.feature.appliances

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzFlowTopBar
import com.recibozen.app.core.components.RzPrimaryButton
import com.recibozen.app.core.components.RzSecondaryButton
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.model.HouseSpace
import com.recibozen.app.core.model.HouseSpaceDefaults
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class SpaceRowUiState(
    val space: HouseSpace,
    val applianceCount: Int,
)

class ApplianceSpacesViewModel(private val repository: ReciboZenRepository) : ViewModel() {
    val spaces: StateFlow<List<SpaceRowUiState>> = repository.session
        .map { state ->
            state.houseSpaces.map { space ->
                SpaceRowUiState(
                    space = space,
                    applianceCount = state.customAppliances.count { appliance ->
                        appliance.spaceId == space.id ||
                            (appliance.spaceId == null && appliance.room.equals(space.name, ignoreCase = true))
                    },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(name: String) {
        repository.addHouseSpace(name)
    }

    fun rename(spaceId: String, name: String) {
        repository.renameHouseSpace(spaceId, name)
    }

    fun remove(spaceId: String) {
        repository.removeHouseSpace(spaceId)
    }
}

@Composable
fun ApplianceSpacesRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { ApplianceSpacesViewModel(repository) }
    val spaces by viewModel.spaces.collectAsStateWithLifecycle()
    ApplianceSpacesScreen(
        spaces = spaces,
        onBack = { navController.popBackStack() },
        onAdd = viewModel::add,
        onRename = viewModel::rename,
        onRemove = viewModel::remove,
    )
}

@Composable
fun ApplianceSpacesScreen(
    spaces: List<SpaceRowUiState>,
    onBack: () -> Unit,
    onAdd: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<HouseSpace?>(null) }
    var deleting by remember { mutableStateOf<SpaceRowUiState?>(null) }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        RzFlowTopBar(title = stringResource(R.string.appliances_spaces_title), onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal)
                .padding(bottom = RzSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.md),
        ) {
            Text(
                stringResource(R.string.appliances_spaces_headline),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(R.string.appliances_spaces_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            spaces.forEach { row ->
                RzCard(modifier = Modifier.fillMaxWidth()) {
                    Text(row.space.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.appliances_spaces_count, row.applianceCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.padding(top = RzSpacing.md),
                        horizontalArrangement = Arrangement.spacedBy(RzSpacing.sm),
                    ) {
                        RzSecondaryButton(
                            text = stringResource(R.string.appliances_spaces_rename),
                            onClick = { editing = row.space },
                            modifier = Modifier.weight(1f),
                        )
                        if (row.space.id != HouseSpaceDefaults.HOME_ID) {
                            RzSecondaryButton(
                                text = stringResource(R.string.appliances_spaces_remove),
                                onClick = { deleting = row },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            RzPrimaryButton(
                text = stringResource(R.string.appliances_spaces_add),
                onClick = { showAdd = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showAdd) {
        SpaceNameDialog(
            title = stringResource(R.string.appliances_spaces_add),
            initialValue = "",
            onDismiss = { showAdd = false },
            onConfirm = {
                onAdd(it)
                showAdd = false
            },
        )
    }
    editing?.let { space ->
        SpaceNameDialog(
            title = stringResource(R.string.appliances_spaces_rename),
            initialValue = space.name,
            onDismiss = { editing = null },
            onConfirm = {
                onRename(space.id, it)
                editing = null
            },
        )
    }
    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.appliances_spaces_remove_title, row.space.name)) },
            text = { Text(stringResource(R.string.appliances_spaces_remove_body, row.applianceCount)) },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(row.space.id)
                    deleting = null
                }) { Text(stringResource(R.string.appliances_spaces_remove_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun SpaceNameDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(40) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.appliances_spaces_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
