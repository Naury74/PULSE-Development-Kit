package com.naury.pulse.sample

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/** 로컬 데이터 보기를 보여 주려고 만드는 작은 저장소. 백그라운드에서 한 번 만든다. */
object DemoStore {
    const val PREFERENCES = "sample_settings"
    const val DATABASE = "sample.db"
    private val ORDERS = listOf("coffee" to 4_500, "bagel" to 3_200, "juice" to 5_000)

    fun prepare(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString("theme", "system")
            .putString("auth_token", "demo-auth-token")
            .putInt("launch_count", context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getInt("launch_count", 0) + 1)
            .apply()
        val file = context.getDatabasePath(DATABASE)
        if (file.exists()) return
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE orders (id INTEGER PRIMARY KEY, item TEXT, amount INTEGER, card_number TEXT)")
            ORDERS.forEach { (item, amount) ->
                db.execSQL(
                    "INSERT INTO orders (item, amount, card_number) VALUES (?, ?, '4111-1111-1111-1111')",
                    arrayOf<Any>(item, amount),
                )
            }
        }
    }
}
