package com.naury.pulse.api

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naury.pulse.api.RedactionPolicy.Companion.REDACTED
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalDataTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val original = Thread.getDefaultUncaughtExceptionHandler()
    private lateinit var data: PulseLocalData

    @Before
    fun setUp() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("theme", "dark")
            .putString("auth_token", "secret-1")
            .putString("last_url", "https://api.test/me?token=secret-2")
            .putInt("launches", 3)
            .commit()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("app.db").apply { parentFile?.mkdirs() }, null).use { db ->
            db.execSQL("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, password TEXT, avatar BLOB)")
            repeat(5) { db.execSQL("INSERT INTO users (name, password, avatar) VALUES ('kim$it', 'pw-$it', x'0102')") }
        }
        val handle = Pulse.initialize(
            context,
            PulseConfig.debugDefaults().newBuilder()
                .monitoring(AppMonitoring.OFF)
                .dataInspection(DataInspection(sharedPreferences = setOf("settings", "absent"), databases = setOf("app.db"), maxRows = 2))
                .build(),
        )
        data = requireNotNull(handle.localData)
    }

    @After
    fun tearDown() {
        Pulse.current()?.close()
        Thread.setDefaultUncaughtExceptionHandler(original)
    }

    @Test
    fun `등록한 저장소만 보이고 민감한 값은 가린다`() = runBlocking<Unit> {
        val sources = data.sources().associateBy { it.name }
        assertThat(sources.keys).containsExactly("settings", "absent", "app.db")
        assertThat(sources.getValue("absent").exists).isFalse()

        val prefs = data.preferences("settings").associateBy { it.key }
        assertThat(prefs.getValue("theme").value).isEqualTo("dark")
        assertThat(prefs.getValue("auth_token").value).isEqualTo(REDACTED)
        assertThat(prefs.getValue("auth_token").masked).isTrue()
        assertThat(prefs.getValue("last_url").value).isEqualTo("https://api.test/me?token=$REDACTED")
        assertThat(prefs.getValue("launches").type).isEqualTo("Integer")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { data.preferences("other") } }
    }

    @Test
    fun `DB 표를 쪽으로 읽고 민감한 열과 BLOB은 값을 보이지 않는다`() = runBlocking<Unit> {
        val table = data.tables("app.db").single { it.name == "users" }
        assertThat(table.rowCount).isEqualTo(5)
        assertThat(table.columns).contains("password TEXT")

        val first = data.rows("app.db", "users", 0)
        assertThat(first.rows).hasSize(2)
        assertThat(first.hasMore).isTrue()
        assertThat(first.maskedColumns).containsExactly("password")
        val row = first.columns.zip(first.rows.first()).toMap()
        assertThat(row["name"]).isEqualTo("kim0")
        assertThat(row["password"]).isEqualTo(REDACTED)
        assertThat(row["avatar"]).isEqualTo("BLOB 2 B")
        assertThat(data.rows("app.db", "users", 4).hasMore).isFalse()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { data.rows("app.db", "users; DROP TABLE users", 0) } }
    }
}
