package com.twtrubiks.stockdesk.core.store

/** 查詢結果的一列：欄名 → 值（INTEGER 是 Long、REAL 是 Double、TEXT 是 String、NULL 是 null）。 */
class Row(private val values: Map<String, Any?>) {
    fun string(name: String): String = values.getValue(name) as String
    fun stringOrNull(name: String): String? = values[name] as String?
    fun long(name: String): Long = (values.getValue(name) as Number).toLong()
    fun longOrNull(name: String): Long? = (values[name] as Number?)?.toLong()
    fun int(name: String): Int = long(name).toInt()
    fun intOrNull(name: String): Int? = longOrNull(name)?.toInt()
    fun double(name: String): Double = (values.getValue(name) as Number).toDouble()
    fun doubleOrNull(name: String): Double? = (values[name] as Number?)?.toDouble()
    fun bool(name: String): Boolean = long(name) != 0L
}

/**
 * 資料庫的最小介面：store 只用這三個方法寫 SQL。
 * 手機上是 app 的 `AndroidSql`（Android 內建 SQLite），JVM 測試是 sqlite-jdbc 的記憶體資料庫。
 * **Android 9 的 SQLite 是 3.22**：不要用 UPSERT、window function、`RETURNING`；JVM 測試擋不下來，要靠 `DeviceTest` 在模擬器上跑。
 */
interface Sql {
    fun execute(sql: String, vararg args: Any?)

    fun query(sql: String, vararg args: Any): List<Row>

    fun <T> transaction(block: () -> T): T
}
