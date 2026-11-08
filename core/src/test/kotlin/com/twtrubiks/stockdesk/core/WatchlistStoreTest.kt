package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.names.StockNames
import com.twtrubiks.stockdesk.core.store.DuplicateStockException
import com.twtrubiks.stockdesk.core.store.InvalidStockCodeException
import com.twtrubiks.stockdesk.core.store.WatchlistStore
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WatchlistStoreTest {
    private val names = StockNames(mapOf("2330" to "台積電", "6515" to "穎崴", "00631L" to "元大台灣50正2"))
    private val sql = JdbcSql()
    private val store = WatchlistStore(sql, names)

    @Test
    fun addsInOrderAndLooksUpNames() {
        store.add("6515")
        store.add(" 2330 ")
        store.add("9999")
        assertEquals(listOf("6515 穎崴", "2330 台積電", "9999"), store.all().map { it.label })
        assertNull(store.find("9999")!!.name)
    }

    @Test
    fun lettersAreUppercased() {
        assertEquals("00631L 元大台灣50正2", store.add("00631l").label)
        assertThrows(DuplicateStockException::class.java) { store.add("00631L") }
    }

    @Test
    fun rejectsBadCodesAndDuplicates() {
        listOf("台積電", "233", "1234567", "23 30", "").forEach { bad ->
            assertThrows("「$bad」", InvalidStockCodeException::class.java) { store.add(bad) }
        }
        store.add("2330")
        val e = assertThrows(DuplicateStockException::class.java) { store.add("2330") }
        assertEquals("2330 台積電", e.existing.label)
        assertEquals(1, store.all().size)
    }

    @Test
    fun givenNameWinsOverTheTable() {
        assertEquals("自己取的", store.add("2330", " 自己取的 ").name)
        // 空白的名字當作沒給：查對照表
        assertEquals("穎崴", store.add("6515", "  ").name)
    }

    @Test
    fun renameAndClear() {
        store.add("9999")
        store.rename("9999", " 新股 ")
        assertEquals("9999 新股", store.find("9999")!!.label)
        store.rename("9999", "  ")
        assertNull(store.find("9999")!!.name)
    }

    @Test
    fun movesAndRemoves() {
        listOf("1101", "1102", "1103").forEach { store.add(it) }
        store.move("1103", -1)
        assertEquals(listOf("1101", "1103", "1102"), store.all().map { it.code })
        store.move("1101", -1) // 已經在最上面
        store.move("1102", +5) // 已經在最下面
        store.move("8888", +1) // 不在清單裡
        assertEquals(listOf("1101", "1103", "1102"), store.all().map { it.code })
        store.remove("1101")
        assertEquals(listOf("1103", "1102"), store.all().map { it.code })
        // 刪掉之後加的排在最後
        store.add("1101")
        assertEquals(listOf("1103", "1102", "1101"), store.all().map { it.code })
    }

    @Test
    fun reorderPutsTheRestAfter() {
        listOf("1101", "1102", "1103", "1104").forEach { store.add(it) }
        store.reorder(listOf("1104", "1102"))
        assertEquals(listOf("1104", "1102", "1101", "1103"), store.all().map { it.code })
    }

    @Test
    fun exportFormatIsTheDocumentedOne() {
        store.add("2330")
        store.add("9999")
        val root = Json.parseToJsonElement(store.export()).jsonObject
        assertEquals(1, root.getValue("version").jsonPrimitive.int)
        val stocks = root.getValue("stocks").jsonArray.map { it.jsonObject }
        assertEquals(listOf("2330", "9999"), stocks.map { it.getValue("code").jsonPrimitive.content })
        assertEquals("台積電", stocks[0].getValue("name").jsonPrimitive.content)
    }

    @Test
    fun exportsAndImportsSkippingExisting() {
        store.add("2330")
        store.add("9999", "自己填的")
        val json = store.export()
        val other = WatchlistStore(JdbcSql(), names)
        other.add("2330", "別的名字")
        assertEquals(1, other.import(json))
        assertEquals(listOf("2330 別的名字", "9999 自己填的"), other.all().map { it.label })
    }

    @Test
    fun importSkipsBadCodesAndToleratesMissingNames() {
        val added = store.import("""{"version":1,"stocks":[{"code":"2330"},{"code":"台積電","name":"x"},{"code":"00631l"},{"code":"2330"}],"extra":true}""")
        assertEquals(2, added)
        assertEquals(listOf("2330 台積電", "00631L 元大台灣50正2"), store.all().map { it.label })
    }

    @Test
    fun importRejectsOtherFiles() {
        assertThrows(SerializationException::class.java) { store.import("""{"hello":"world"}""") }
        assertThrows(SerializationException::class.java) { store.import("not json") }
        assertEquals(0, store.all().size)
    }

    @Test
    fun reopeningKeepsTheList() {
        // App 重開、升級：同一個資料庫再建一次 store，自選股不能丟
        store.add("2330")
        store.add("6515")
        assertEquals(listOf("2330", "6515"), WatchlistStore(sql, names).all().map { it.code })
    }
}
