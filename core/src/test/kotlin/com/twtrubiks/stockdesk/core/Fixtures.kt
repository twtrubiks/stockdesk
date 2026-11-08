package com.twtrubiks.stockdesk.core

import org.junit.Assume.assumeTrue

/**
 * `core/src/test/resources/fixtures/` 裡錄好的真實回應（`tools/fixtures/fetch_fixtures.py` 抓的）。
 * 還沒錄的 fixture：測試用 [require] 跳過（Assume），不算失敗，但 CI 的輸出會看到 skipped。
 */
object Fixtures {
    fun load(name: String): String? = Fixtures::class.java.getResourceAsStream("/fixtures/$name")?.bufferedReader()?.readText()

    fun require(name: String): String {
        val text = load(name)
        assumeTrue("還沒錄 fixture：$name（見 core/src/test/resources/fixtures/README.md）", text != null)
        return text!!
    }
}
