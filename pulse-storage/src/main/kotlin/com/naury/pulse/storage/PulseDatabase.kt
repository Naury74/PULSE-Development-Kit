package com.naury.pulse.storage

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SessionEntity::class, TransactionEntity::class, MessageEntity::class, HeaderEntity::class, TimingEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class PulseDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao
}
