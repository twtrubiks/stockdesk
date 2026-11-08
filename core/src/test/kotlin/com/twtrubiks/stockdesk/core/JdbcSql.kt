package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.store.Row
import com.twtrubiks.stockdesk.core.store.Sql
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

/** JVM 單元測試用的 [Sql]：sqlite-jdbc 的記憶體資料庫，SQL 跑在真的 SQLite 上（版本比 Android 9 新，新語法擋不下來）。 */
class JdbcSql : Sql, AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    private var depth = 0

    private fun prepare(sql: String, args: Array<out Any?>): PreparedStatement =
        connection.prepareStatement(sql).apply { args.forEachIndexed { i, v -> setObject(i + 1, v) } }

    override fun execute(sql: String, vararg args: Any?) {
        prepare(sql, args).use { it.executeUpdate() }
    }

    override fun query(sql: String, vararg args: Any): List<Row> = prepare(sql, args).use { statement ->
        statement.executeQuery().use { rs ->
            val meta = rs.metaData
            buildList {
                while (rs.next()) {
                    add(Row((1..meta.columnCount).associate { meta.getColumnLabel(it) to rs.getObject(it) }))
                }
            }
        }
    }

    override fun <T> transaction(block: () -> T): T {
        if (depth == 0) connection.autoCommit = false
        depth++
        var ok = false
        try {
            return block().also { ok = true }
        } finally {
            depth--
            if (depth == 0) {
                if (ok) connection.commit() else connection.rollback()
                connection.autoCommit = true
            }
        }
    }

    override fun close() = connection.close()
}
