package com.naury.pulse.api

/**
 * 인스펙터에서 읽기 전용으로 볼 앱 저장소. 등록한 것만 보이고, 값을 바꾸는 기능은 없다.
 * 이름이 민감 단어(`RedactionPolicy.sensitiveNames`)를 담은 키·열의 값은 가리고, 그 밖의 값도 글자 속 비밀은 가린다.
 *
 * @property sharedPreferences SharedPreferences 파일 이름(`getSharedPreferences(name, …)`의 name).
 * @property databases SQLite·Room DB 파일 이름(`getDatabasePath(name)`의 name). 읽기 전용으로 연다.
 * @property maxRows 한 번에 읽을 행 수, 1..1,000.
 */
public class DataInspection @JvmOverloads constructor(
    public val sharedPreferences: Set<String> = emptySet(),
    public val databases: Set<String> = emptySet(),
    public val maxRows: Int = DEFAULT_MAX_ROWS,
) {
    init {
        require(maxRows in 1..MAX_ROWS_LIMIT) { "maxRows는 1..$MAX_ROWS_LIMIT 범위여야 합니다." }
    }

    override fun equals(other: Any?): Boolean =
        other is DataInspection && sharedPreferences == other.sharedPreferences && databases == other.databases && maxRows == other.maxRows

    override fun hashCode(): Int = listOf(sharedPreferences, databases, maxRows).hashCode()

    override fun toString(): String = "DataInspection(sharedPreferences=$sharedPreferences, databases=$databases, maxRows=$maxRows)"

    public companion object {
        public const val DEFAULT_MAX_ROWS: Int = 200
        public const val MAX_ROWS_LIMIT: Int = 1_000
    }
}

public enum class DataSourceKind { SHARED_PREFERENCES, DATABASE }

/** 등록한 저장소 하나. [exists]가 false면 아직 만들어지지 않은 것이다. [bytes]는 파일 크기. */
public class DataSource(public val kind: DataSourceKind, public val name: String, public val exists: Boolean, public val bytes: Long?)

/** 키 하나. [type]은 String·Int·Boolean·Set 등, [masked]면 값을 가렸다. */
public class DataEntry(public val key: String, public val type: String, public val value: String, public val masked: Boolean)

/** 표 하나. [columns]는 `이름 형식` 꼴. */
public class DataTable(public val name: String, public val rowCount: Long, public val columns: List<String>)

/** 표의 한 쪽. 값은 글자로 바꾼 것이고 BLOB은 크기만 보인다. [maskedColumns]는 값을 가린 열이다. */
public class DataRows(
    public val columns: List<String>,
    public val rows: List<List<String?>>,
    public val maskedColumns: Set<String>,
    public val offset: Int,
    public val hasMore: Boolean,
)

/** 등록한 앱 저장소를 읽는다. 모든 함수는 I/O 디스패처에서 실행되고 실패하면 예외를 던진다. */
public interface PulseLocalData {
    public fun sources(): List<DataSource>

    public suspend fun preferences(name: String): List<DataEntry>

    public suspend fun tables(database: String): List<DataTable>

    public suspend fun rows(database: String, table: String, offset: Int): DataRows
}
