package com.naury.pulse.storage

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SessionEntity::class, TransactionEntity::class, MessageEntity::class, HeaderEntity::class, TimingEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
internal abstract class PulseDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao
}
