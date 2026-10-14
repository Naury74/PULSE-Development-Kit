package com.naury.pulse.storage

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SessionEntity::class,
        TransactionEntity::class,
        MessageEntity::class,
        HeaderEntity::class,
        TimingEntity::class,
        NetworkHeaderEntity::class,
        EventEntity::class,
        OutboxEntity::class,
        ReportEntity::class,
    ],
    version = 8,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
    ],
)
internal abstract class PulseDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao

    abstract fun events(): EventDao

    abstract fun outbox(): OutboxDao

    abstract fun reports(): ReportDao
}
