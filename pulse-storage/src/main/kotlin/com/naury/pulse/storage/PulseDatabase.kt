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
    ],
    version = 6,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
    ],
)
internal abstract class PulseDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao

    abstract fun events(): EventDao
}
