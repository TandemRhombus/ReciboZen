package com.recibozen.app.feature.period

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recibozen.app.demo.repository.ReciboZenRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class PeriodAnalysisViewModel(repository: ReciboZenRepository) : ViewModel() {

    val uiState: StateFlow<PeriodAnalysisUiState> = repository.session
        .map { state ->
            PeriodAnalysisUiState(
                lastReadingKwh = state.readings.firstOrNull()?.kwh ?: PeriodAnalysisUiState().lastReadingKwh,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PeriodAnalysisUiState())
}
