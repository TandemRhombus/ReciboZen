package com.recibozen.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "households")
data class HouseholdEntity(
    @androidx.room.PrimaryKey val id: String,
    val displayName: String,
    val city: String,
    val stateCode: String? = null,
    val addressEncrypted: ByteArray? = null,
    val createdAtEpochMillis: Long,
    val archivedAtEpochMillis: Long? = null,
)

@Entity(
    tableName = "electric_services",
    foreignKeys = [
        ForeignKey(
            entity = HouseholdEntity::class,
            parentColumns = ["id"],
            childColumns = ["householdId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("householdId"),
        Index(value = ["serviceNumber"], unique = true),
        Index(value = ["rmu"], unique = true),
    ],
)
data class ElectricServiceEntity(
    @androidx.room.PrimaryKey val id: String,
    val householdId: String,
    val displayAlias: String,
    val serviceNumber: String? = null,
    val rmu: String? = null,
    val tariffCode: String,
    val billingCycle: String,
    val wireCount: Int? = null,
    val isActive: Boolean = true,
    val createdAtEpochMillis: Long,
    val archivedAtEpochMillis: Long? = null,
)

@Entity(
    tableName = "meters",
    foreignKeys = [
        ForeignKey(
            entity = ElectricServiceEntity::class,
            parentColumns = ["id"],
            childColumns = ["electricServiceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("electricServiceId"), Index("meterNumber")],
)
data class MeterEntity(
    @androidx.room.PrimaryKey val id: String,
    val electricServiceId: String,
    val meterNumber: String,
    val multiplier: Int = 1,
    val validFromEpochDay: Long? = null,
    val validUntilEpochDay: Long? = null,
    val isCurrent: Boolean = true,
)

@Entity(
    tableName = "bills",
    foreignKeys = [
        ForeignKey(
            entity = ElectricServiceEntity::class,
            parentColumns = ["id"],
            childColumns = ["electricServiceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("electricServiceId"),
        Index(value = ["electricServiceId", "periodStartEpochDay", "periodEndEpochDay"], unique = true),
        Index(value = ["sourceFileHash"], unique = true),
    ],
)
data class BillEntity(
    @androidx.room.PrimaryKey val id: String,
    val electricServiceId: String,
    val periodStartEpochDay: Long,
    val periodEndEpochDay: Long,
    val dueDateEpochDay: Long? = null,
    val cutoffDateEpochDay: Long? = null,
    val currentReadingKwh: Int? = null,
    val previousReadingKwh: Int? = null,
    val consumptionKwh: Int,
    val amountMxnMinor: Long,
    val tariffCode: String,
    val tariffSnapshotJson: String? = null,
    val source: String,
    val sourceFileHash: String? = null,
    val parseConfidence: Double,
    val importedAtEpochMillis: Long,
)

@Entity(
    tableName = "bill_charges",
    foreignKeys = [
        ForeignKey(
            entity = BillEntity::class,
            parentColumns = ["id"],
            childColumns = ["billId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("billId")],
)
data class BillChargeEntity(
    @androidx.room.PrimaryKey val id: String,
    val billId: String,
    val type: String,
    val season: String,
    val quantityKwh: Double? = null,
    val unitRateMxn: Double? = null,
    val amountMxnMinor: Long? = null,
    val labelFromDocument: String,
)

@Entity(
    tableName = "bill_history_summaries",
    primaryKeys = ["electricServiceId", "periodStartEpochDay", "periodEndEpochDay"],
    foreignKeys = [
        ForeignKey(
            entity = ElectricServiceEntity::class,
            parentColumns = ["id"],
            childColumns = ["electricServiceId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = BillEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceBillId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sourceBillId")],
)
data class BillHistorySummaryEntity(
    val electricServiceId: String,
    val periodStartEpochDay: Long,
    val periodEndEpochDay: Long,
    val consumptionKwh: Int,
    val amountMxnMinor: Long,
    val paidMxnMinor: Long? = null,
    val pendingMxnMinor: Long? = null,
    val sourceBillId: String,
)

@Entity(
    tableName = "reference_datasets",
    indices = [Index(value = ["kind", "version"], unique = true), Index(value = ["sha256"], unique = true)],
)
data class ReferenceDatasetEntity(
    @androidx.room.PrimaryKey val id: String,
    val kind: String,
    val version: String,
    val sourceName: String,
    val sha256: String,
    val schemaVersion: Int,
    val rowCount: Int,
    val importedAtEpochMillis: Long,
)

@Entity(
    tableName = "appliance_references",
    primaryKeys = ["datasetId", "externalId"],
    foreignKeys = [
        ForeignKey(
            entity = ReferenceDatasetEntity::class,
            parentColumns = ["id"],
            childColumns = ["datasetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("datasetId"), Index("category")],
)
data class ApplianceReferenceEntity(
    val datasetId: String,
    val externalId: String,
    val name: String,
    val category: String,
    val powerMinWatts: Int? = null,
    val powerTypicalWatts: Int? = null,
    val powerMaxWatts: Int? = null,
    val typicalKwhPerCycle: Double? = null,
    val typicalKwhPer30Days: Double? = null,
    val standbyWatts: Double? = null,
    val sourceUrl: String? = null,
    val evidenceNote: String? = null,
)

@Entity(
    tableName = "tariff_rate_references",
    foreignKeys = [
        ForeignKey(
            entity = ReferenceDatasetEntity::class,
            parentColumns = ["id"],
            childColumns = ["datasetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("datasetId"),
        Index(value = ["tariffCode", "regionCode", "effectiveYearMonth", "season", "blockOrder"], unique = true),
    ],
)
data class TariffRateReferenceEntity(
    @androidx.room.PrimaryKey val id: String,
    val datasetId: String,
    val tariffCode: String,
    val regionCode: String? = null,
    val effectiveYearMonth: String,
    val season: String = "NONE",
    val blockOrder: Int,
    val blockLabel: String,
    val limitKwh: Int? = null,
    val rateMxnPerKwh: Double? = null,
    val fixedChargeMxn: Double? = null,
    val demandRateMxnPerKw: Double? = null,
    val sourceUrl: String? = null,
)
