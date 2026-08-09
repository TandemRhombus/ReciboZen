package com.recibozen.app.core.model

data class SolarScenario(
    val name: String,
    val tabLabel: String,
    val coverageLabel: String,
    val panels: String,
    val systemPower: String,
    val area: String,
    val production: String,
    val gridRemaining: String,
    val note: String,
)

data class MonthlyEnergyPoint(
    val monthLabel: String,
    val consumptionFraction: Float,
    val productionFraction: Float,
)
