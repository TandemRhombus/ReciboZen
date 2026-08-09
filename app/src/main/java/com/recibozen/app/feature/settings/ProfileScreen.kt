package com.recibozen.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.recibozen.app.R
import com.recibozen.app.core.components.RzCard
import com.recibozen.app.core.components.RzFlowTopBar
import com.recibozen.app.core.designsystem.RzExtraShapes
import com.recibozen.app.core.designsystem.RzIcons
import com.recibozen.app.core.designsystem.RzSpacing
import com.recibozen.app.core.designsystem.ReciboZenTheme
import com.recibozen.app.demo.data.DemoReciboZenData
import com.recibozen.app.demo.repository.ReciboZenRepository
import com.recibozen.app.navigation.Routes
import com.recibozen.app.navigation.rzViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Reached from the avatar button on any bottom-nav tab (via [com.recibozen.app.core.components.RzTabHeader]).
 * All copy is fixture data from [DemoReciboZenData]; the "Estados y errores" row is the only entry
 * point into [StatesCatalogRoute] anywhere in the app, per the handoff spec.
 */
data class ProfileUiState(
    val userName: String = DemoReciboZenData.USER_NAME,
    val userInitial: String = DemoReciboZenData.USER_INITIAL,
    val householdName: String = DemoReciboZenData.HOUSEHOLD_NAME,
    val tariff: String = DemoReciboZenData.TARIFF,
    val billingCycle: String = DemoReciboZenData.BILLING_CYCLE,
    val location: String = DemoReciboZenData.LOCATION,
    val goalMxn: Int = DemoReciboZenData.GOAL_MXN,
)

class ProfileViewModel(private val repository: ReciboZenRepository) : ViewModel() {
    val uiState: StateFlow<ProfileUiState> = repository.session
        .map { state ->
            ProfileUiState(
                userName = state.userName,
                userInitial = state.userName.firstOrNull()?.uppercase() ?: "?",
                householdName = state.householdName,
                tariff = state.tariff,
                billingCycle = state.billingCycle,
                location = state.location,
                goalMxn = state.goalMxn,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProfileUiState())

    fun update(userName: String, householdName: String, location: String, goalMxn: Int) =
        repository.updateProfile(userName, householdName, location, goalMxn)
}

@Composable
fun ProfileRoute(navController: NavHostController, repository: ReciboZenRepository) {
    val viewModel = rzViewModel { ProfileViewModel(repository) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    ProfileScreen(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onNavigateAppearance = { navController.navigate(Routes.Appearance) },
        onNavigateStatesCatalog = { navController.navigate(Routes.StatesCatalog) },
        onUpdateProfile = viewModel::update,
    )
}

@Composable
fun ProfileScreen(
    uiState: ProfileUiState,
    onBack: () -> Unit,
    onNavigateAppearance: () -> Unit,
    onNavigateStatesCatalog: () -> Unit,
    onUpdateProfile: (String, String, String, Int) -> Unit,
) {
    var showEditDialog by remember { mutableStateOf(false) }
    Scaffold(topBar = { RzFlowTopBar(title = stringResource(R.string.profile_title), onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RzSpacing.screenHorizontal, vertical = RzSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(RzSpacing.md),
        ) {
            RzCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RzSpacing.lg)) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(uiState.userInitial, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Column {
                        Text(uiState.userName, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            text = stringResource(
                                R.string.profile_subtitle,
                                uiState.householdName,
                                uiState.tariff,
                                uiState.billingCycle.lowercase(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            RzCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RzExtraShapes.cardMedium,
                contentPadding = PaddingValues(horizontal = RzSpacing.xl),
            ) {
                ProfileRow(
                    icon = RzIcons.DarkMode,
                    label = stringResource(R.string.profile_row_dark_mode),
                    trailing = stringResource(R.string.profile_row_dark_mode_trailing),
                    onClick = onNavigateAppearance,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ProfileRow(
                    icon = RzIcons.HomeWork,
                    label = stringResource(R.string.profile_row_household_goal),
                    onClick = { showEditDialog = true },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ProfileRow(
                    icon = RzIcons.Notifications,
                    label = stringResource(R.string.profile_row_reminders),
                    trailing = stringResource(R.string.profile_row_reminders_trailing),
                    onClick = null,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ProfileRow(
                    icon = RzIcons.Lock,
                    label = stringResource(R.string.profile_row_privacy),
                    onClick = null,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ProfileRow(
                    icon = RzIcons.Info,
                    label = stringResource(R.string.profile_row_states_catalog),
                    onClick = onNavigateStatesCatalog,
                )
            }

            RzCard(modifier = Modifier.fillMaxWidth(), shape = RzExtraShapes.cardMedium) {
                Text(
                    text = stringResource(R.string.profile_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }

    if (showEditDialog) {
        var userName by remember(uiState.userName) { mutableStateOf(uiState.userName) }
        var householdName by remember(uiState.householdName) { mutableStateOf(uiState.householdName) }
        var location by remember(uiState.location) { mutableStateOf(uiState.location) }
        var goal by remember(uiState.goalMxn) { mutableStateOf(uiState.goalMxn.toString()) }
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text(stringResource(R.string.profile_edit_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(RzSpacing.sm)) {
                    OutlinedTextField(userName, { userName = it }, label = { Text(stringResource(R.string.profile_edit_name)) })
                    OutlinedTextField(householdName, { householdName = it }, label = { Text(stringResource(R.string.profile_edit_household)) })
                    OutlinedTextField(location, { location = it }, label = { Text(stringResource(R.string.profile_edit_location)) })
                    OutlinedTextField(
                        goal,
                        { goal = it.filter(Char::isDigit).take(6) },
                        label = { Text(stringResource(R.string.profile_edit_goal)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = userName.isNotBlank() && householdName.isNotBlank() && location.isNotBlank() && goal.toIntOrNull() != null,
                    onClick = {
                        onUpdateProfile(userName, householdName, location, goal.toIntOrNull() ?: uiState.goalMxn)
                        showEditDialog = false
                    },
                ) { Text(stringResource(R.string.profile_edit_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun ProfileRow(
    icon: ImageVector,
    label: String,
    trailing: String? = null,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RzSpacing.minTouchTarget)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RzSpacing.md),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(text = trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        if (onClick != null) {
            Icon(RzIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Preview(showBackground = true, name = "Perfil")
@Composable
private fun ProfileScreenPreview() {
    ReciboZenTheme {
        ProfileScreen(
            uiState = ProfileUiState(),
            onBack = {},
            onNavigateAppearance = {},
            onNavigateStatesCatalog = {},
            onUpdateProfile = { _, _, _, _ -> },
        )
    }
}
