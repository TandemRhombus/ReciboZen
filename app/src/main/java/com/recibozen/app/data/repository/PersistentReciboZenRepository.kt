package com.recibozen.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.recibozen.app.core.designsystem.AppThemeMode
import com.recibozen.app.core.model.HistoryReadingEntry
import com.recibozen.app.core.model.Appliance
import com.recibozen.app.core.model.ApplianceCategory
import com.recibozen.app.core.model.ApplianceDataSource
import com.recibozen.app.core.model.ConfidenceLevel
import com.recibozen.app.core.model.OpportunityLevel
import com.recibozen.app.core.model.HistoryUnit
import com.recibozen.app.core.model.PlanActionStatus
import com.recibozen.app.core.model.ReadingMethod
import com.recibozen.app.core.model.EntitlementTier
import com.recibozen.app.core.model.ElectricServiceIdentity
import com.recibozen.app.core.model.ServiceCreationResult
import com.recibozen.app.core.model.BillPeriod
import com.recibozen.app.data.local.ElectricServiceEntity
import com.recibozen.app.data.local.HouseholdEntity
import com.recibozen.app.data.local.MeterEntity
import com.recibozen.app.data.local.ReciboZenDatabase
import com.recibozen.app.data.local.BillEntity
import com.recibozen.app.data.local.BillChargeEntity
import com.recibozen.app.data.local.BillHistorySummaryEntity
import com.recibozen.app.core.importer.ParsedCfeBill
import com.recibozen.app.core.importer.BillImportResult
import com.recibozen.app.core.model.EntitlementPolicy
import com.recibozen.app.core.security.PremiumEntitlementAuthority
import com.recibozen.app.demo.data.DemoReciboZenData
import com.recibozen.app.core.calculation.TariffCalibration
import com.recibozen.app.demo.repository.DemoReciboZenRepository
import com.recibozen.app.demo.repository.ReciboZenSessionState
import com.recibozen.app.demo.repository.TariffCalibrationState
import java.util.Locale
import java.util.UUID
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong
import kotlin.math.roundToInt

private val Context.reciboZenDataStore by preferencesDataStore(name = "recibozen_user_state")

/**
 * Local-first repository used by the real app. DataStore owns the durable copy;
 * the inherited StateFlow keeps every Compose screen reactive and preserves the
 * narrow repository interface left by the design handoff.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PersistentReciboZenRepository(context: Context) : DemoReciboZenRepository() {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateKey = stringPreferencesKey("session_json_v1")
    private val entitlementLeaseKey = stringPreferencesKey("server_entitlement_lease_v1")
    private val database = ReciboZenDatabase.get(appContext)
    private val dao = database.dao()
    private val premiumEntitlementAuthority = PremiumEntitlementAuthority(appContext)

    init {
        mutableSession.value = ReciboZenSessionState(isHydrated = false)
        scope.launch {
            val preferences = appContext.reciboZenDataStore.data.first()
            val stored = preferences[stateKey]
            val trustedTier = premiumEntitlementAuthority.effectiveTier(
                token = preferences[entitlementLeaseKey],
                // There is no authenticated account yet, so premium must stay
                // closed. Billing integration will supply the subject from its
                // authenticated account session, never from mutable preferences.
                expectedSubject = null,
            )
            val hydrated = SessionJsonCodec.decode(stored).copy(
                entitlementTier = trustedTier,
                isHydrated = true,
            )
            migrateLegacyServiceIfNeeded()
            mutableSession.value = hydrated

            launch {
                dao.observeActiveServices().collect { rows ->
                    val services = rows.map { row ->
                        val currentMeter = row.meters.firstOrNull { it.isCurrent } ?: row.meters.maxByOrNull { it.validFromEpochDay ?: Long.MIN_VALUE }
                        ElectricServiceIdentity(
                            id = row.service.id,
                            alias = row.service.displayAlias,
                            serviceNumber = row.service.serviceNumber.orEmpty(),
                            rmu = row.service.rmu.orEmpty(),
                            meterNumber = currentMeter?.meterNumber.orEmpty(),
                            tariffCode = row.service.tariffCode,
                            billingCycle = row.service.billingCycle,
                            isActive = row.service.isActive,
                        )
                    }
                    mutableElectricServices.value = services
                    if (services.isEmpty() && mutableSession.value.activeServiceId.isNotBlank()) {
                        mutableSession.update {
                            it.copy(
                                activeServiceId = "",
                                serviceAlias = "",
                                serviceNumber = "",
                                rmu = "",
                                meterNumber = "",
                                tariff = "",
                                billingCycle = "",
                            )
                        }
                    }
                    val selected = services.firstOrNull { it.id == mutableSession.value.activeServiceId }
                        ?: services.firstOrNull()
                    selected?.let { selectElectricService(it.id) }
                }
            }

            launch {
                mutableSession
                    .map { it.activeServiceId }
                    .distinctUntilChanged()
                    .flatMapLatest { serviceId ->
                        combine(
                            dao.observeBills(serviceId),
                            dao.observeBillHistory(serviceId),
                        ) { bills, summaries ->
                            val detailedPeriods = bills.associate { bill ->
                                (bill.periodStartEpochDay to bill.periodEndEpochDay) to BillPeriod(
                                    monthLabel = periodLabel(bill.periodStartEpochDay, bill.periodEndEpochDay),
                                    amountMxn = (bill.amountMxnMinor / 100.0).roundToInt(),
                                    consumptionKwh = bill.consumptionKwh,
                                    periodStartEpochDay = bill.periodStartEpochDay,
                                    periodEndEpochDay = bill.periodEndEpochDay,
                                )
                            }
                            (summaries.associate { summary ->
                                (summary.periodStartEpochDay to summary.periodEndEpochDay) to BillPeriod(
                                    monthLabel = periodLabel(summary.periodStartEpochDay, summary.periodEndEpochDay),
                                    amountMxn = (summary.amountMxnMinor / 100.0).roundToInt(),
                                    consumptionKwh = summary.consumptionKwh,
                                    periodStartEpochDay = summary.periodStartEpochDay,
                                    periodEndEpochDay = summary.periodEndEpochDay,
                                )
                            } + detailedPeriods)
                                .toList()
                                .sortedBy { it.first.second }
                                .map { it.second }
                        }
                    }
                    .collect { mutableImportedBillHistory.value = it }
            }

            // Rates the estimate actually bills with, recovered from the newest
            // imported bill so the projection uses the user's real $/kWh.
            launch {
                mutableSession
                    .map { it.activeServiceId }
                    .distinctUntilChanged()
                    .flatMapLatest { serviceId -> dao.observeBills(serviceId) }
                    .collect { bills ->
                        mutableTariffCalibration.value = bills
                            .maxByOrNull { it.periodEndEpochDay }
                            ?.let(::calibrationFrom)
                    }
            }

            // Start after hydration so merely opening the app never overwrites
            // a durable state with defaults.
            mutableSession.drop(1).collect { state ->
                appContext.reciboZenDataStore.edit { preferences ->
                    preferences[stateKey] = SessionJsonCodec.encode(state)
                }
            }
        }
    }

    override fun billHistory(state: ReciboZenSessionState): List<BillPeriod> {
        val imported = importedBillHistory.value
        if (imported.isEmpty()) return super.billHistory(state)
        val latestEndMillis = imported.lastOrNull()?.periodEndEpochDay?.times(MILLIS_PER_DAY)
        val hasReadingAfterLatestBill = latestEndMillis != null && state.readings.any { reading ->
            reading.method != ReadingMethod.PDF && (reading.timestampEpochMillis ?: Long.MIN_VALUE) > latestEndMillis
        }
        val projection = if (hasReadingAfterLatestBill) super.billHistory(state).lastOrNull { it.isProjected } else null
        return imported + listOfNotNull(projection)
    }

    /**
     * Rebuilds the tariff snapshot persisted alongside [bill]. Returns null when
     * the bill carries no usable tier rates, which leaves the estimate on the
     * generic blocks rather than on half-real numbers.
     */
    private fun calibrationFrom(bill: BillEntity): TariffCalibrationState? {
        val json = bill.tariffSnapshotJson ?: return null
        val charges = runCatching {
            val array = JSONObject(json).optJSONArray("charges") ?: return@runCatching emptyList()
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                TariffCalibration.CalibrationCharge(
                    type = item.optString("type"),
                    season = item.optString("season"),
                    quantityKwh = item.optDouble("quantityKwh").takeIf { !it.isNaN() },
                    unitRateMxn = item.optDouble("unitRateMxn").takeIf { !it.isNaN() },
                )
            }
        }.getOrDefault(emptyList())
        if (charges.isEmpty()) return null

        val label = periodLabel(bill.periodStartEpochDay, bill.periodEndEpochDay)
        val result = TariffCalibration.fromCharges(
            tariffCode = bill.tariffCode,
            effectivePeriod = label,
            charges = charges,
            sourceBillId = bill.id,
        )
        return TariffCalibrationState(result.snapshot, result.coverage, label)
    }

    override fun updateProfile(userName: String, householdName: String, location: String, goalMxn: Int) {
        super.updateProfile(userName, householdName, location, goalMxn)
        val state = mutableSession.value
        if (state.householdName.isNotBlank() || state.location.isNotBlank()) {
            scope.launch { persistHousehold(state) }
        }
    }

    override fun setTariff(tariff: String) {
        super.setTariff(tariff)
        persistService(mutableSession.value.activeServiceId)
    }

    override fun updateElectricService(
        alias: String,
        serviceNumber: String,
        rmu: String,
        meterNumber: String,
        tariffCode: String,
    ) {
        super.updateElectricService(alias, serviceNumber, rmu, meterNumber, tariffCode)
        persistService(mutableSession.value.activeServiceId)
    }

    override fun addElectricService(
        alias: String,
        serviceNumber: String,
        rmu: String,
        meterNumber: String,
        tariffCode: String,
    ): ServiceCreationResult {
        val result = super.addElectricService(alias, serviceNumber, rmu, meterNumber, tariffCode)
        result.serviceId?.let(::persistService)
        return result
    }

    override suspend fun importCfeBill(bill: ParsedCfeBill, sourceFileHash: String): BillImportResult {
        if (dao.hasImportedFile(sourceFileHash)) {
            return BillImportResult(accepted = false, duplicate = true, message = "Este PDF ya fue importado.")
        }
        val existingMatch = dao.findService(bill.serviceNumber, bill.rmu)
        val activeCurrent = dao.service(mutableSession.value.activeServiceId)
        val service = when {
            existingMatch != null -> existingMatch
            activeCurrent != null && activeCurrent.serviceNumber == null && activeCurrent.rmu == null -> activeCurrent.copy(
                displayAlias = serviceAliasForBill(activeCurrent.displayAlias, bill.serviceNumber),
                serviceNumber = bill.serviceNumber,
                rmu = bill.rmu,
                tariffCode = bill.tariffCode,
                wireCount = bill.wireCount,
            )
            else -> {
                val count = dao.activeServiceCount()
                val limit = EntitlementPolicy.activeServiceLimit(mutableSession.value.entitlementTier)
                if (count >= limit) {
                    val required = if (count < EntitlementPolicy.activeServiceLimit(EntitlementTier.PLUS)) {
                        EntitlementTier.PLUS
                    } else EntitlementTier.FAMILY
                    return BillImportResult(accepted = false, requiredTier = required, message = "El plan actual alcanzó su límite de suministros.")
                }
                ElectricServiceEntity(
                    id = UUID.randomUUID().toString(),
                    householdId = LEGACY_HOUSEHOLD_ID,
                    displayAlias = "Servicio ${bill.serviceNumber.takeLast(4)}",
                    serviceNumber = bill.serviceNumber,
                    rmu = bill.rmu,
                    tariffCode = bill.tariffCode,
                    billingCycle = if (bill.periodEnd.toEpochDay() - bill.periodStart.toEpochDay() > 45) "Bimestral" else "Mensual",
                    wireCount = bill.wireCount,
                    createdAtEpochMillis = System.currentTimeMillis(),
                )
            }
        }
        val billId = UUID.randomUUID().toString()
        return runCatching {
            database.withTransaction {
                dao.upsertHousehold(householdEntity(mutableSession.value))
                dao.upsertService(service)
                val existingMeter = dao.meter(service.id, bill.meterNumber)
                dao.upsertMeter(
                    existingMeter?.copy(multiplier = bill.multiplier, isCurrent = true) ?: MeterEntity(
                        id = UUID.randomUUID().toString(),
                        electricServiceId = service.id,
                        meterNumber = bill.meterNumber,
                        multiplier = bill.multiplier,
                        validFromEpochDay = bill.periodStart.toEpochDay(),
                    ),
                )
                val snapshot = JSONObject().apply {
                    put("tariffCode", bill.tariffCode)
                    put("periodStart", bill.periodStart.toString())
                    put("periodEnd", bill.periodEnd.toString())
                    put("charges", JSONArray().apply {
                        bill.charges.forEach { charge ->
                            put(JSONObject().apply {
                                put("type", charge.type)
                                put("season", charge.season)
                                put("quantityKwh", charge.quantityKwh ?: JSONObject.NULL)
                                put("unitRateMxn", charge.unitRateMxn ?: JSONObject.NULL)
                                put("amountMxn", charge.amountMxn ?: JSONObject.NULL)
                            })
                        }
                    })
                }.toString()
                dao.insertBill(
                    BillEntity(
                        id = billId,
                        electricServiceId = service.id,
                        periodStartEpochDay = bill.periodStart.toEpochDay(),
                        periodEndEpochDay = bill.periodEnd.toEpochDay(),
                        currentReadingKwh = bill.currentReadingKwh,
                        previousReadingKwh = bill.previousReadingKwh,
                        consumptionKwh = bill.consumptionKwh,
                        amountMxnMinor = (bill.totalToPayMxn * 100).roundToLong(),
                        tariffCode = bill.tariffCode,
                        tariffSnapshotJson = snapshot,
                        source = "PDF_IMPORTED",
                        sourceFileHash = sourceFileHash,
                        parseConfidence = bill.confidence,
                        importedAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
                dao.insertCharges(
                    bill.charges.map { charge ->
                        BillChargeEntity(
                            id = UUID.randomUUID().toString(),
                            billId = billId,
                            type = charge.type,
                            season = charge.season,
                            quantityKwh = charge.quantityKwh,
                            unitRateMxn = charge.unitRateMxn,
                            amountMxnMinor = charge.amountMxn?.let { (it * 100).roundToLong() },
                            labelFromDocument = charge.documentLabel,
                        )
                    },
                )
                dao.upsertHistory(
                    bill.history.map { row ->
                        BillHistorySummaryEntity(
                            electricServiceId = service.id,
                            periodStartEpochDay = row.periodStart.toEpochDay(),
                            periodEndEpochDay = row.periodEnd.toEpochDay(),
                            consumptionKwh = row.consumptionKwh,
                            amountMxnMinor = (row.amountMxn * 100).roundToLong(),
                            paidMxnMinor = row.paidMxn?.let { (it * 100).roundToLong() },
                            sourceBillId = billId,
                        )
                    },
                )
            }
            mutableSession.update {
                val pdfReadings = listOf(
                    HistoryReadingEntry(
                        kwh = bill.currentReadingKwh,
                        dateLabel = "Recibo · ${bill.periodEnd}",
                        method = ReadingMethod.PDF,
                        note = "Lectura final extraída del PDF",
                        timestampEpochMillis = bill.periodEnd.toEpochDay() * MILLIS_PER_DAY,
                    ),
                    HistoryReadingEntry(
                        kwh = bill.previousReadingKwh,
                        dateLabel = "Recibo · ${bill.periodStart}",
                        method = ReadingMethod.PDF,
                        note = "Lectura inicial extraída del PDF",
                        timestampEpochMillis = bill.periodStart.toEpochDay() * MILLIS_PER_DAY,
                    ),
                )
                val mergedReadings = (it.readings + pdfReadings)
                    .distinctBy { reading -> reading.kwh to reading.timestampEpochMillis }
                    .sortedByDescending { reading -> reading.timestampEpochMillis ?: Long.MIN_VALUE }
                it.copy(
                    activeServiceId = service.id,
                    serviceAlias = service.displayAlias,
                    serviceNumber = bill.serviceNumber,
                    rmu = bill.rmu.orEmpty(),
                    meterNumber = bill.meterNumber,
                    tariff = bill.tariffCode,
                    billingCycle = service.billingCycle,
                    lastConfirmedReadingKwh = maxOf(it.lastConfirmedReadingKwh, bill.currentReadingKwh),
                    readings = mergedReadings,
                )
            }
            BillImportResult(
                accepted = true,
                serviceId = service.id,
                message = "Se guardó el recibo y ${bill.history.size} periodos anteriores.",
            )
        }.getOrElse { error ->
            BillImportResult(accepted = false, message = error.message ?: "No se pudo guardar el recibo.")
        }
    }

    private fun persistService(serviceId: String) {
        val service = electricServices.value.firstOrNull { it.id == serviceId } ?: return
        scope.launch {
            val existing = dao.service(service.id)
            dao.upsertHousehold(householdEntity(mutableSession.value))
            dao.upsertService(
                ElectricServiceEntity(
                    id = service.id,
                    householdId = LEGACY_HOUSEHOLD_ID,
                    displayAlias = service.alias,
                    serviceNumber = service.serviceNumber.ifBlank { null },
                    rmu = service.rmu.ifBlank { null },
                    tariffCode = service.tariffCode,
                    billingCycle = service.billingCycle,
                    isActive = service.isActive,
                    createdAtEpochMillis = existing?.createdAtEpochMillis ?: System.currentTimeMillis(),
                ),
            )
            if (service.meterNumber.isNotBlank()) {
                dao.upsertMeter(
                    MeterEntity(
                        id = existing?.let { "meter_${it.id}_${service.meterNumber}" } ?: UUID.randomUUID().toString(),
                        electricServiceId = service.id,
                        meterNumber = service.meterNumber,
                    ),
                )
            }
        }
    }

    private suspend fun migrateLegacyServiceIfNeeded() {
        val legacy = dao.service(LEGACY_SERVICE_ID) ?: return
        val billCount = dao.billCountForService(legacy.id)
        if (billCount == 0 && legacy.serviceNumber.isNullOrBlank() && legacy.rmu.isNullOrBlank()) {
            dao.deleteService(legacy.id)
            return
        }
        if (legacy.displayAlias == DemoReciboZenData.HOUSEHOLD_NAME && !legacy.serviceNumber.isNullOrBlank()) {
            dao.upsertService(legacy.copy(displayAlias = serviceAliasForBill(legacy.displayAlias, legacy.serviceNumber)))
        }
    }

    private suspend fun persistHousehold(state: ReciboZenSessionState) {
        dao.upsertHousehold(householdEntity(state))
    }

    private fun householdEntity(state: ReciboZenSessionState) = HouseholdEntity(
        id = LEGACY_HOUSEHOLD_ID,
        displayName = state.householdName.ifBlank { "Mi hogar" },
        city = state.location,
        createdAtEpochMillis = System.currentTimeMillis(),
    )

    private fun serviceAliasForBill(currentAlias: String, serviceNumber: String): String =
        currentAlias.takeUnless { it.isBlank() || it == DemoReciboZenData.HOUSEHOLD_NAME }
            ?: "Servicio ${serviceNumber.takeLast(4)}"

    private companion object {
        const val LEGACY_HOUSEHOLD_ID = "legacy_household"
        const val LEGACY_SERVICE_ID = "legacy_service"
        const val MILLIS_PER_DAY = 86_400_000L

        fun periodLabel(startEpochDay: Long, endEpochDay: Long): String {
            val start = LocalDate.ofEpochDay(startEpochDay)
            val end = LocalDate.ofEpochDay(endEpochDay)
            val months = arrayOf("Ene", "Feb", "Mar", "Abr", "May", "Jun", "Jul", "Ago", "Sep", "Oct", "Nov", "Dic")
            return "${months[start.monthValue - 1]}–${months[end.monthValue - 1]} ${end.year.toString().takeLast(2)}"
        }
    }
}

internal object SessionJsonCodec {
    fun encode(state: ReciboZenSessionState): String = JSONObject().apply {
        put("schemaVersion", CURRENT_SCHEMA_VERSION)
        put("onboardingCompleted", state.onboardingCompleted)
        put("themeMode", state.themeMode.name)
        put("userName", state.userName)
        put("householdName", state.householdName)
        put("serviceAlias", state.serviceAlias)
        put("serviceNumber", state.serviceNumber)
        put("rmu", state.rmu)
        put("meterNumber", state.meterNumber)
        put("activeServiceId", state.activeServiceId)
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
                    put("category", appliance.category.name)
                    put("powerWatts", appliance.powerWatts ?: JSONObject.NULL)
                    put("standbyWatts", appliance.standbyWatts ?: JSONObject.NULL)
                    put("dataSource", appliance.dataSource.name)
                    put("calculationNote", appliance.calculationNote ?: JSONObject.NULL)
                })
            }
        })
        put("savedSimulatorScenario", state.savedSimulatorScenario)
        put("simulatorScenarioName", state.simulatorScenarioName)
        put("simulatorScenarioWatts", state.simulatorScenarioWatts)
        put("simulatorScenarioHoursPerWeek", state.simulatorScenarioHoursPerWeek)
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
            val schemaVersion = json.optInt("schemaVersion", 1)
            val migrateFixtureState = schemaVersion < CURRENT_SCHEMA_VERSION
            val serviceNumber = json.optString("serviceNumber", defaults.serviceNumber)
            val hours = if (migrateFixtureState) emptyMap() else {
                json.optJSONObject("applianceHours").toIntMap(defaults.applianceHoursPerWeek)
            }
            val statuses = json.optJSONObject("planStatuses")
            val readingsJson = json.optJSONArray("readings")
            val decodedReadings = readingsJson.toReadings(defaults.readings).filterNot { reading ->
                migrateFixtureState && reading.timestampEpochMillis == null
            }
            fun fixtureOrEmpty(key: String, fixture: String): String {
                val value = json.optString(key)
                return if (migrateFixtureState && value == fixture) "" else value
            }
            defaults.copy(
                // Version 1 never collected a real profile and silently injected
                // design fixtures. Run the honest onboarding once after migration.
                onboardingCompleted = if (migrateFixtureState) false else json.optBoolean("onboardingCompleted", false),
                themeMode = enumValueOr(json.optString("themeMode"), defaults.themeMode),
                userName = fixtureOrEmpty("userName", DemoReciboZenData.USER_NAME),
                householdName = fixtureOrEmpty("householdName", DemoReciboZenData.HOUSEHOLD_NAME),
                serviceAlias = fixtureOrEmpty("serviceAlias", DemoReciboZenData.HOUSEHOLD_NAME)
                    .ifBlank { serviceNumber.takeIf(String::isNotBlank)?.let { "Servicio ${it.takeLast(4)}" }.orEmpty() },
                serviceNumber = serviceNumber,
                rmu = json.optString("rmu", defaults.rmu),
                meterNumber = json.optString("meterNumber", defaults.meterNumber),
                activeServiceId = json.optString("activeServiceId", defaults.activeServiceId),
                // Premium is intentionally never restored from mutable local JSON.
                // Only a future server-verified entitlement authority may set it.
                entitlementTier = EntitlementTier.FREE,
                location = fixtureOrEmpty("location", DemoReciboZenData.LOCATION),
                tariff = json.optString("tariff", defaults.tariff).let {
                    if (migrateFixtureState && serviceNumber.isBlank() && it == DemoReciboZenData.TARIFF) "" else it
                },
                billingCycle = json.optString("billingCycle", defaults.billingCycle).let {
                    if (migrateFixtureState && serviceNumber.isBlank() && it == DemoReciboZenData.BILLING_CYCLE) "" else it
                },
                goalMxn = json.optInt("goalMxn", defaults.goalMxn).let {
                    if (migrateFixtureState && it == DemoReciboZenData.GOAL_MXN) 0 else it.coerceAtLeast(0)
                },
                meterType = fixtureOrEmpty("meterType", DemoReciboZenData.METER_TYPE_SELECTED),
                applianceHoursPerWeek = hours,
                customAppliances = json.optJSONArray("customAppliances").toAppliances(),
                savedSimulatorScenario = json.optBoolean("savedSimulatorScenario", false),
                simulatorScenarioName = json.optString("simulatorScenarioName", defaults.simulatorScenarioName),
                simulatorScenarioWatts = json.optInt("simulatorScenarioWatts", defaults.simulatorScenarioWatts).coerceAtLeast(0),
                simulatorScenarioHoursPerWeek = json.optDouble(
                    "simulatorScenarioHoursPerWeek",
                    defaults.simulatorScenarioHoursPerWeek,
                ).coerceIn(0.0, 168.0),
                planActions = defaults.planActions.map { action ->
                    action.copy(status = enumValueOr(statuses?.optString(action.id), action.status))
                },
                dialAngles = json.optJSONArray("dialAngles").toFloatList(defaults.dialAngles),
                dialSelectedIndex = json.optInt("dialSelectedIndex", defaults.dialSelectedIndex).coerceIn(0, 4),
                dialVisitedFaces = json.optJSONArray("dialVisitedFaces").toIntList(defaults.dialVisitedFaces.toList()).toSet(),
                dialUnsure = json.optBoolean("dialUnsure", defaults.dialUnsure),
                solarScenarioIndex = json.optInt("solarScenarioIndex", defaults.solarScenarioIndex)
                    .coerceIn(DemoReciboZenData.solarScenarios.indices),
                historyUnit = enumValueOr(json.optString("historyUnit"), defaults.historyUnit),
                onboardingStep = if (migrateFixtureState) 0 else json.optInt("onboardingStep", defaults.onboardingStep).coerceIn(0, 3),
                lastConfirmedReadingKwh = decodedReadings.firstOrNull()?.kwh ?: 0,
                readings = decodedReadings,
                manualReadingDigits = json.optJSONArray("manualReadingDigits").toIntList(defaults.manualReadingDigits),
                pendingReadingKwh = json.optNullableInt("pendingReadingKwh"),
                pendingReadingMethod = json.optNullableString("pendingReadingMethod")
                    ?.let { enumValueOr(it, ReadingMethod.DIAL) },
            )
        }.getOrElse { ReciboZenSessionState() }
    }

    private const val CURRENT_SCHEMA_VERSION = 2

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
                        category = enumValueOr(item.optString("category"), ApplianceCategory.OTHER),
                        powerWatts = item.optNullableInt("powerWatts"),
                        standbyWatts = item.optNullableInt("standbyWatts"),
                        dataSource = enumValueOr(item.optString("dataSource"), ApplianceDataSource.USER_ESTIMATE),
                        calculationNote = item.optNullableString("calculationNote"),
                    ),
                )
            }
        }
    }

    private fun JSONObject.optNullableInt(key: String): Int? = if (isNull(key)) null else optInt(key)
    private fun JSONObject.optNullableLong(key: String): Long? = if (isNull(key)) null else optLong(key)
    private fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
