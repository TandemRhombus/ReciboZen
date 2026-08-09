package com.recibozen.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.recibozen.app.core.designsystem.AppThemeMode
import com.recibozen.app.core.model.HistoryReadingEntry
import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.ConfidenceLevel
import com.recibozen.app.core.model.OpportunityLevel
import com.recibozen.app.core.model.HistoryUnit
import com.recibozen.app.core.model.PlanActionStatus
import com.recibozen.app.core.model.ReadingMethod
import com.recibozen.app.demo.data.DemoReciboZenData
import com.recibozen.app.demo.repository.DemoReciboZenRepository
import com.recibozen.app.demo.repository.ReciboZenSessionState
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val Context.reciboZenDataStore by preferencesDataStore(name = "recibozen_user_state")

/**
 * Local-first repository used by the real app. DataStore owns the durable copy;
 * the inherited StateFlow keeps every Compose screen reactive and preserves the
 * narrow repository interface left by the design handoff.
 */
class PersistentReciboZenRepository(context: Context) : DemoReciboZenRepository() {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateKey = stringPreferencesKey("session_json_v1")

    init {
        mutableSession.value = ReciboZenSessionState(isHydrated = false)
        scope.launch {
            val stored = appContext.reciboZenDataStore.data.first()[stateKey]
            mutableSession.value = SessionJsonCodec.decode(stored).copy(isHydrated = true)

            // Start after hydration so merely opening the app never overwrites
            // a durable state with defaults.
            mutableSession.drop(1).collect { state ->
                appContext.reciboZenDataStore.edit { preferences ->
                    preferences[stateKey] = SessionJsonCodec.encode(state)
                }
            }
        }
    }
}

internal object SessionJsonCodec {
    fun encode(state: ReciboZenSessionState): String = JSONObject().apply {
        put("onboardingCompleted", state.onboardingCompleted)
        put("themeMode", state.themeMode.name)
        put("userName", state.userName)
        put("householdName", state.householdName)
        put("location", state.location)
        put("tariff", state.tariff)
        put("billingCycle", state.billingCycle)
        put("goalMxn", state.goalMxn)
        put("meterType", state.meterType)
        put("applianceHours", JSONObject(state.applianceHoursPerWeek))
        put("customAppliances", JSONArray().apply {
            state.customAppliances.forEach { appliance ->
                put(JSONObject().apply {
                    put("id", appliance.id)
                    put("name", appliance.name)
                    put("room", appliance.room)
                    put("costMxn", appliance.costMxn)
                    put("kwh", appliance.kwh)
                    put("hours", appliance.hoursPerWeek ?: JSONObject.NULL)
                    put("confidence", appliance.confidence.name)
                    put("opportunity", appliance.opportunity.name)
                })
            }
        })
        put("savedSimulatorScenario", state.savedSimulatorScenario)
        put("planStatuses", JSONObject(state.planActions.associate { it.id to it.status.name }))
        put("dialAngles", JSONArray(state.dialAngles))
        put("dialSelectedIndex", state.dialSelectedIndex)
        put("dialVisitedFaces", JSONArray(state.dialVisitedFaces.sorted()))
        put("dialUnsure", state.dialUnsure)
        put("solarScenarioIndex", state.solarScenarioIndex)
        put("historyUnit", state.historyUnit.name)
        put("onboardingStep", state.onboardingStep)
        put("lastConfirmedReadingKwh", state.lastConfirmedReadingKwh)
        put("manualReadingDigits", JSONArray(state.manualReadingDigits))
        put("pendingReadingKwh", state.pendingReadingKwh ?: JSONObject.NULL)
        put("pendingReadingMethod", state.pendingReadingMethod?.name ?: JSONObject.NULL)
        put("readings", JSONArray().apply {
            state.readings.forEach { reading ->
                put(JSONObject().apply {
                    put("kwh", reading.kwh)
                    put("dateLabel", reading.dateLabel)
                    put("method", reading.method.name)
                    put("note", reading.note)
                    put("timestamp", reading.timestampEpochMillis ?: JSONObject.NULL)
                })
            }
        })
    }.toString()

    fun decode(raw: String?): ReciboZenSessionState {
        if (raw.isNullOrBlank()) return ReciboZenSessionState()
        return runCatching {
            val json = JSONObject(raw)
            val defaults = ReciboZenSessionState()
            val hours = json.optJSONObject("applianceHours").toIntMap(defaults.applianceHoursPerWeek)
            val statuses = json.optJSONObject("planStatuses")
            val readingsJson = json.optJSONArray("readings")
            defaults.copy(
                onboardingCompleted = json.optBoolean("onboardingCompleted", false),
                themeMode = enumValueOr(json.optString("themeMode"), defaults.themeMode),
                userName = json.optString("userName", defaults.userName),
                householdName = json.optString("householdName", defaults.householdName),
                location = json.optString("location", defaults.location),
                tariff = json.optString("tariff", defaults.tariff),
                billingCycle = json.optString("billingCycle", defaults.billingCycle),
                goalMxn = json.optInt("goalMxn", defaults.goalMxn).coerceAtLeast(1),
                meterType = json.optString("meterType", defaults.meterType),
                applianceHoursPerWeek = hours,
                customAppliances = json.optJSONArray("customAppliances").toAppliances(),
                savedSimulatorScenario = json.optBoolean("savedSimulatorScenario", false),
                planActions = DemoReciboZenData.planActions.map { action ->
                    action.copy(status = enumValueOr(statuses?.optString(action.id), action.status))
                },
                dialAngles = json.optJSONArray("dialAngles").toFloatList(defaults.dialAngles),
                dialSelectedIndex = json.optInt("dialSelectedIndex", defaults.dialSelectedIndex).coerceIn(0, 4),
                dialVisitedFaces = json.optJSONArray("dialVisitedFaces").toIntList(defaults.dialVisitedFaces.toList()).toSet(),
                dialUnsure = json.optBoolean("dialUnsure", defaults.dialUnsure),
                solarScenarioIndex = json.optInt("solarScenarioIndex", defaults.solarScenarioIndex)
                    .coerceIn(DemoReciboZenData.solarScenarios.indices),
                historyUnit = enumValueOr(json.optString("historyUnit"), defaults.historyUnit),
                onboardingStep = json.optInt("onboardingStep", defaults.onboardingStep).coerceIn(0, 3),
                lastConfirmedReadingKwh = json.optInt("lastConfirmedReadingKwh", defaults.lastConfirmedReadingKwh),
                readings = readingsJson.toReadings(defaults.readings),
                manualReadingDigits = json.optJSONArray("manualReadingDigits").toIntList(defaults.manualReadingDigits),
                pendingReadingKwh = json.optNullableInt("pendingReadingKwh"),
                pendingReadingMethod = json.optNullableString("pendingReadingMethod")
                    ?.let { enumValueOr(it, ReadingMethod.DIAL) },
            )
        }.getOrElse { ReciboZenSessionState() }
    }

    private inline fun <reified T : Enum<T>> enumValueOr(raw: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw?.uppercase(Locale.ROOT) } ?: fallback

    private fun JSONObject?.toIntMap(fallback: Map<String, Int>): Map<String, Int> {
        if (this == null) return fallback
        return keys().asSequence().associateWith { key -> optInt(key, fallback[key] ?: 0).coerceIn(0, 168) }
    }

    private fun JSONArray?.toIntList(fallback: List<Int>): List<Int> {
        if (this == null || length() == 0) return fallback
        return List(length()) { index -> optInt(index) }
    }

    private fun JSONArray?.toFloatList(fallback: List<Float>): List<Float> {
        if (this == null || length() != fallback.size) return fallback
        return List(length()) { index -> optDouble(index, fallback[index].toDouble()).toFloat() }
    }

    private fun JSONArray?.toReadings(fallback: List<HistoryReadingEntry>): List<HistoryReadingEntry> {
        if (this == null || length() == 0) return fallback
        return buildList {
            repeat(length()) { index ->
                val item = optJSONObject(index) ?: return@repeat
                add(
                    HistoryReadingEntry(
                        kwh = item.optInt("kwh"),
                        dateLabel = item.optString("dateLabel"),
                        method = enumValueOr(item.optString("method"), ReadingMethod.MANUAL),
                        note = item.optString("note"),
                        timestampEpochMillis = item.optNullableLong("timestamp"),
                    ),
                )
            }
        }.ifEmpty { fallback }
    }

    private fun JSONArray?.toAppliances(): List<Appliance> {
        if (this == null) return emptyList()
        return buildList {
            repeat(length()) { index ->
                val item = optJSONObject(index) ?: return@repeat
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@repeat
                add(
                    Appliance(
                        id = id,
                        name = item.optString("name", "Aparato"),
                        room = item.optString("room", "Hogar"),
                        costMxn = item.optInt("costMxn").coerceAtLeast(0),
                        kwh = item.optInt("kwh").coerceAtLeast(0),
                        sharePercent = 0,
                        hoursPerWeek = item.optNullableInt("hours"),
                        confidence = enumValueOr(item.optString("confidence"), ConfidenceLevel.MEDIUM),
                        opportunity = enumValueOr(item.optString("opportunity"), OpportunityLevel.NONE),
                    ),
                )
            }
        }
    }

    private fun JSONObject.optNullableInt(key: String): Int? = if (isNull(key)) null else optInt(key)
    private fun JSONObject.optNullableLong(key: String): Long? = if (isNull(key)) null else optLong(key)
    private fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
