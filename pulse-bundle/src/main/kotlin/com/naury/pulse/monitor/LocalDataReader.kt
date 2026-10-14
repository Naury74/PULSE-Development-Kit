package com.naury.pulse.monitor

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.naury.pulse.api.DataEntry
import com.naury.pulse.api.DataInspection
import com.naury.pulse.api.DataRows
import com.naury.pulse.api.DataSource
import com.naury.pulse.api.DataSourceKind
import com.naury.pulse.api.DataTable
import com.naury.pulse.api.PulseLocalData
import com.naury.pulse.api.RedactionPolicy
import com.naury.pulse.security.Redactor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 등록한 SharedPreferences·SQLite DB를 읽기 전용으로 읽는다. DB는 `OPEN_READONLY`로 열고 쓰지 않는다.
 * 이름이 민감한 키·열은 값을 통째로 가리고, 나머지 값도 글자 속 비밀(URL 쿼리, `name=value`)을 가린다.
 */
internal class LocalDataReader(private val context: Context, private val options: DataInspection, policy: RedactionPolicy) :
    PulseLocalData {
    private val redactor = Redactor(policy)

    override fun sources(): List<DataSource> = options.sharedPreferences.sorted().map { name ->
        val file = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
        DataSource(DataSourceKind.SHARED_PREFERENCES, name, file.isFile, file.takeIf { it.isFile }?.length())
    } + options.databases.sorted().map { name ->
        val file = context.getDatabasePath(name)
        DataSource(DataSourceKind.DATABASE, name, file.isFile, file.takeIf { it.isFile }?.length())
    }

    override suspend fun preferences(name: String): List<DataEntry> = withContext(Dispatchers.IO) {
        require(name in options.sharedPreferences) { "등록하지 않은 SharedPreferences입니다: $name" }
        context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toSortedMap().map { (key, value) ->
            val masked = redactor.isSensitiveName(key)
            DataEntry(
                key = key,
                type = value?.javaClass?.simpleName ?: "null",
                value = if (masked) RedactionPolicy.REDACTED else redactor.sanitizeTextPublic(value.toString()),
                masked = masked,
            )
        }
    }

    override suspend fun tables(database: String): List<DataTable> = withContext(Dispatchers.IO) {
        open(database).use { db ->
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                null,
            ).use { cursor ->
                cursor.strings().map { table ->
                    DataTable(table, count(db, table), columns(db, table))
                }
            }
        }
    }

    override suspend fun rows(database: String, table: String, offset: Int): DataRows = withContext(Dispatchers.IO) {
        open(database).use { db ->
            require(table in tableNames(db)) { "표가 없습니다: $table" }
            db.rawQuery(
                "SELECT * FROM ${quote(table)} LIMIT ? OFFSET ?",
                arrayOf((options.maxRows + 1).toString(), offset.coerceAtLeast(0).toString()),
            )
                .use { cursor ->
                    val columns = cursor.columnNames.toList()
                    val masked = columns.filter(redactor::isSensitiveName).toSet()
                    val rows = ArrayList<List<String?>>()
                    while (cursor.moveToNext() && rows.size <= options.maxRows) rows += row(cursor, columns, masked)
                    DataRows(columns, rows.take(options.maxRows), masked, offset, rows.size > options.maxRows)
                }
        }
    }

    private fun open(database: String): SQLiteDatabase {
        require(database in options.databases) { "등록하지 않은 DB입니다: $database" }
        val file = context.getDatabasePath(database)
        require(file.isFile) { "DB 파일이 없습니다: $database" }
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    private fun row(cursor: Cursor, columns: List<String>, masked: Set<String>): List<String?> = columns.indices.map { index ->
        when {
            cursor.isNull(index) -> null
            columns[index] in masked -> RedactionPolicy.REDACTED
            cursor.getType(index) == Cursor.FIELD_TYPE_BLOB -> "BLOB ${cursor.getBlob(index).size} B"
            else -> redactor.sanitizeTextPublic(cursor.getString(index)).take(MAX_CELL_CHARS)
        }
    }

    private fun tableNames(db: SQLiteDatabase): Set<String> =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { it.strings().toSet() }

    private fun count(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM ${quote(table)}", null).use { if (it.moveToFirst()) it.getLong(0) else 0 }

    private fun columns(db: SQLiteDatabase, table: String): List<String> =
        db.rawQuery("PRAGMA table_info(${quote(table)})", null).use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            val type = cursor.getColumnIndexOrThrow("type")
            buildList { while (cursor.moveToNext()) add("${cursor.getString(name)} ${cursor.getString(type)}".trim()) }
        }

    private fun Cursor.strings(): List<String> = buildList { while (moveToNext()) add(getString(0)) }

    /** 표 이름은 sqlite_master에서 확인한 것만 쓰지만, 따옴표를 겹쳐 SQL로 해석되지 않게 한다. */
    private fun quote(name: String) = "\"" + name.replace("\"", "\"\"") + "\""

    private companion object {
        const val MAX_CELL_CHARS = 2_000
    }
}
