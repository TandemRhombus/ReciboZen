package com.recibozen.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        HouseholdEntity::class,
        ElectricServiceEntity::class,
        MeterEntity::class,
        BillEntity::class,
        BillChargeEntity::class,
        BillHistorySummaryEntity::class,
        ReferenceDatasetEntity::class,
        ApplianceReferenceEntity::class,
        TariffRateReferenceEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ReciboZenDatabase : RoomDatabase() {
    abstract fun dao(): ReciboZenDao

    companion object {
        @Volatile private var instance: ReciboZenDatabase? = null

        fun get(context: Context): ReciboZenDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ReciboZenDatabase::class.java,
                "recibozen.db",
            ).build().also { instance = it }
        }
    }
}
