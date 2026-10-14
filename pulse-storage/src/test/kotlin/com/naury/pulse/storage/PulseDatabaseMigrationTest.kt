package com.naury.pulse.storage

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PulseDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PulseDatabase::class.java)

    @Test
    fun `v1에서 v2로 올려도 기존 기록이 남고 새 근거 열은 비어 있다`() {
        helper.createDatabase(DATABASE, 1).use { database ->
            database.execSQL(
                """INSERT INTO transactions (id, sessionId, source, method, url, scheme, host, path, startedAtEpochMillis, state)
                VALUES ('t1', 's1', 'ORIGINAL', 'GET', 'https://a.test/', 'https', 'a.test', '/', 1, 'COMPLETED')""",
            )
        }

        val migrated = helper.runMigrationsAndValidate(DATABASE, 2, true)

        migrated.query("SELECT id, responseSource, connectionReused FROM transactions").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("t1")
            assertThat(cursor.isNull(1)).isTrue()
            assertThat(cursor.isNull(2)).isTrue()
        }
        migrated.close()
    }

    @Test
    fun `v2에서 v3로 올리면 원본 연결 열이 비어 있다`() {
        helper.createDatabase(DATABASE, 2).use { database ->
            database.execSQL(
                """INSERT INTO transactions (id, sessionId, source, method, url, scheme, host, path, startedAtEpochMillis, state)
                VALUES ('t1', 's1', 'ORIGINAL', 'GET', 'https://a.test/', 'https', 'a.test', '/', 1, 'COMPLETED')""",
            )
        }

        helper.runMigrationsAndValidate(DATABASE, 3, true).use { migrated ->
            migrated.query("SELECT parentTransactionId FROM transactions WHERE id = 't1'").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.isNull(0)).isTrue()
            }
        }
    }

    @Test
    fun `v3에서 v4로 올리면 맥락 열이 비어 있다`() {
        helper.createDatabase(DATABASE, 3).use { database ->
            database.execSQL(
                """INSERT INTO transactions (id, sessionId, source, method, url, scheme, host, path, startedAtEpochMillis, state)
                VALUES ('t1', 's1', 'ORIGINAL', 'GET', 'https://a.test/', 'https', 'a.test', '/', 1, 'COMPLETED')""",
            )
        }

        helper.runMigrationsAndValidate(DATABASE, 4, true).use { migrated ->
            migrated.query("SELECT screen, foreground, networkType, label FROM transactions WHERE id = 't1'").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                (0..3).forEach { assertThat(cursor.isNull(it)).isTrue() }
            }
        }
    }

    @Test
    fun `v5에서 v6으로 올리면 세션 기록이 남고 이벤트 표가 생긴다`() {
        helper.createDatabase(DATABASE, 5).use { database ->
            database.execSQL("INSERT INTO sessions (id, startedAtEpochMillis) VALUES ('s1', 1)")
        }

        helper.runMigrationsAndValidate(DATABASE, 6, true).use { migrated ->
            migrated.query("SELECT foregroundMillis, crashed, lastSeenEpochMillis FROM sessions WHERE id = 's1'").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getLong(0)).isEqualTo(0)
                assertThat(cursor.getInt(1)).isEqualTo(0)
                assertThat(cursor.isNull(2)).isTrue()
            }
            migrated.query("SELECT COUNT(*) FROM app_events").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getInt(0)).isEqualTo(0)
            }
        }
    }

    private companion object {
        const val DATABASE = "migration-test.db"
    }
}
