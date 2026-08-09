package com.recibozen.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recibozen.app.core.model.PlanActionStatus
import com.recibozen.app.demo.data.DemoReciboZenData
import com.recibozen.app.demo.repository.ReciboZenRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(private val repository: ReciboZenRepository) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = repository.session
        .map { state ->
            val projection = repository.billProjection(state)
            val appliances = repository.appliances(state)
            val minisplit = appliances.find { it.id == "minisplit" }
            val refrigerador = appliances.find { it.id == "refrigerador" }
            val ps5 = appliances.find { it.id == "ps5" }
            val minisplitAction = state.planActions.find { it.id == DemoReciboZenData.MINISPLIT_ACTION_ID }

            HomeUiState(
                projectedMxn = projection.projectedMxn,
                rangeLowMxn = projection.rangeLowMxn,
                rangeHighMxn = projection.rangeHighMxn,
                isWithinGoal = projection.isWithinGoal,
                minisplitStatus = minisplitAction?.status ?: PlanActionStatus.SUGGESTED,
                minisplitSavingsLabel = minisplitAction?.savingsRangeLabel.orEmpty(),
                minisplitCostMxn = minisplit?.costMxn ?: 0,
                minisplitSharePercent = minisplit?.sharePercent ?: 0,
                refrigeradorCostMxn = refrigerador?.costMxn ?: 0,
                refrigeradorSharePercent = refrigerador?.sharePercent ?: 0,
                ps5CostMxn = ps5?.costMxn ?: 0,
                ps5SharePercent = ps5?.sharePercent ?: 0,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    fun onToggleMinisplitAction() {
        repository.togglePlanAction(DemoReciboZenData.MINISPLIT_ACTION_ID)
    }
}
