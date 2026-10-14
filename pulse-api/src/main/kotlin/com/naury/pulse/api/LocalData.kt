package com.naury.pulse.api

/**
 * 인스펙터에서 읽기 전용으로 볼 앱 저장소. 등록한 것만 보이고, 값을 바꾸는 기능은 없다.
 * 이름이 민감 단어(`RedactionPolicy.sensitiveNames`)를 담은 키·열의 값은 가리고, 그 밖의 값도 글자 속 비밀은 가린다.
 *
 * @property sharedPreferences SharedPreferences 파일 이름(`getSharedPreferences(name, …)`의 name).
 * @property databases SQLite·Room DB 파일 이름(`getDatabasePath(name)`의 name). 읽기 전용으로 연다.
 * @property maxRows 한 번에 읽을 행 수, 1..1,000.
 * @property allowPreferenceEdits true면 등록한 SharedPreferences의 값을 인스펙터에서 바꾸거나 지울 수 있다(확인 후, 기존 형식 유지).
 *   바꾼 기록은 키 이름만 이벤트로 남는다. DB는 언제나 읽기 전용이다.
 */
public class DataInspection @JvmOverloads constructor(
    public val sharedPreferences: Set<String> = emptySet(),
    public val databases: Set<String> = emptySet(),
    public val maxRows: Int = DEFAULT_MAX_ROWS,
    public val allowPreferenceEdits: Boolean = false,
) {
    init {
        require(maxRows in 1..MAX_ROWS_LIMIT) { "maxRows는 1..$MAX_ROWS_LIMIT 범위여야 합니다." }
    }

    private fun values(): List<Any> = listOf(sharedPreferences, databases, maxRows, allowPreferenceEdits)

    override fun equals(other: Any?): Boolean = other is DataInspection && values() == other.values()

    override fun hashCode(): Int = values().hashCode()

    override fun toString(): String = "DataInspection(sharedPreferences=$sharedPreferences, databases=$databases, maxRows=$maxRows, " +
        "allowPreferenceEdits=$allowPreferenceEdits)"

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

    /** [DataInspection.allowPreferenceEdits]가 켜져 있는지. */
    public val canEditPreferences: Boolean
        get() = false

    /**
     * [name]의 [key] 값을 [value]로 바꾼다. null이면 지운다. 기존 형식(Boolean·Int·Long·Float·String)으로 바꾸며, 형식이 맞지 않거나
     * 바꿀 수 없는 형식(Set)이거나 편집을 허용하지 않았으면 [IllegalArgumentException]·[IllegalStateException].
     */
    public suspend fun editPreference(name: String, key: String, value: String?): Unit = error("편집을 허용하지 않았습니다.")
}
