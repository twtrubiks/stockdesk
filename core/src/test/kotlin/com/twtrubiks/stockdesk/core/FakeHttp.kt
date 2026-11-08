package com.twtrubiks.stockdesk.core

import com.twtrubiks.stockdesk.core.http.Http
import com.twtrubiks.stockdesk.core.http.HttpResponse
import java.io.IOException

/**
 * 照網址回固定內容的假 [Http]；沒設定的網址丟 IOException（當作連不上）。**後設定的規則優先**（測試中途可以換回應）。
 * 記下每一個請求（方法、網址、表單、[clock] 的時間），測試看順序、帶的 token 與節流。
 * [clock] 在 `runTest` 裡接 `testScheduler.currentTime`：Pacer 的 `delay` 走虛擬時間，請求的間隔看得到、測試不用真的等。
 */
class FakeHttp(private val clock: () -> Long = { 0L }) : Http {
    data class Request(val method: String, val url: String, val form: Map<String, String>, val headers: Map<String, String>, val at: Long) {
        /** 網址 query 裡的參數（不解碼，櫃買的日期就是「2026/11/06」）。 */
        fun param(name: String): String? = url.substringAfter('?', "").split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it[0] == name }?.getOrNull(1)
    }

    private val routes = mutableListOf<Pair<(Request) -> Boolean, (Request) -> HttpResponse>>()
    val requests = mutableListOf<Request>()

    fun on(match: (Request) -> Boolean, response: (Request) -> HttpResponse) {
        routes += match to response
    }

    fun on(urlContains: String, code: Int = 200, body: String) = on({ urlContains in it.url }) { HttpResponse(code, body, it.url) }

    /** 這個網址沒回應（逾時、被限流）：丟 IOException。 */
    fun down(urlContains: String) = on({ urlContains in it.url }) { throw IOException("timeout: ${it.url}") }

    fun requestsTo(urlContains: String): List<Request> = requests.filter { urlContains in it.url }

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = answer(Request("GET", url, emptyMap(), headers, clock()))

    override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse =
        answer(Request("POST", url, form, headers, clock()))

    private fun answer(request: Request): HttpResponse {
        requests += request
        val route = routes.lastOrNull { it.first(request) } ?: throw IOException("FakeHttp：沒有設定 ${request.method} ${request.url}")
        return route.second(request)
    }
}
