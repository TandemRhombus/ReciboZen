package com.recibozen.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Embedded
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReciboZenDao {
    @Transaction
    @Query("SELECT * FROM electric_services WHERE archivedAtEpochMillis IS NULL ORDER BY createdAtEpochMillis")
    fun observeActiveServices(): Flow<List<ElectricServiceWithMeters>>

    @Query("SELECT * FROM electric_services WHERE id = :id LIMIT 1")
    suspend fun service(id: String): ElectricServiceEntity?

    @Query("SELECT * FROM electric_services WHERE serviceNumber = :serviceNumber OR rmu = :rmu LIMIT 1")
    suspend fun findService(serviceNumber: String?, rmu: String?): ElectricServiceEntity?

    @Query("SELECT COUNT(*) FROM electric_services")
    suspend fun serviceCount(): Int

    @Query("SELECT COUNT(*) FROM bills WHERE electricServiceId = :serviceId")
    suspend fun billCountForService(serviceId: String): Int

    @Query("DELETE FROM electric_services WHERE id = :serviceId")
    suspend fun deleteService(serviceId: String)

    @Query("SELECT COUNT(*) FROM electric_services WHERE isActive = 1 AND archivedAtEpochMillis IS NULL")
    suspend fun activeServiceCount(): Int

    @Query("SELECT * FROM meters WHERE electricServiceId = :serviceId AND meterNumber = :meterNumber LIMIT 1")
    suspend fun meter(serviceId: String, meterNumber: String): MeterEntity?

    @Upsert
    suspend fun upsertHousehold(household: HouseholdEntity)

    @Upsert
    suspend fun upsertService(service: ElectricServiceEntity)

    @Upsert
    suspend fun upsertMeter(meter: MeterEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBill(bill: BillEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCharges(charges: List<BillChargeEntity>)

    @Upsert
    suspend fun upsertHistory(history: List<BillHistorySummaryEntity>)

    @Query("SELECT * FROM bills WHERE electricServiceId = :serviceId ORDER BY periodEndEpochDay DESC")
    fun observeBills(serviceId: String): Flow<List<BillEntity>>

    @Query("SELECT * FROM bill_history_summaries WHERE electricServiceId = :serviceId ORDER BY periodEndEpochDay DESC")
    fun observeBillHistory(serviceId: String): Flow<List<BillHistorySummaryEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM bills WHERE sourceFileHash = :sha256)")
    suspend fun hasImportedFile(sha256: String): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDataset(dataset: ReferenceDatasetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApplianceReferences(items: List<ApplianceReferenceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTariffReferences(items: List<TariffRateReferenceEntity>)
}

data class ElectricServiceWithMeters(
    @Embedded val service: ElectricServiceEntity,
    @Relation(parentColumn = "id", entityColumn = "electricServiceId")
    val meters: List<MeterEntity>,
)
