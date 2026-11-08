package com.twtrubiks.stockdesk.core

import java.net.URLDecoder

/**
 * 照集保真的規則回應的假伺服器（只有邏輯、不含 HTTP，JVM 測試與模擬器的 MockWebServer 共用）：
 *
 * - token 存在伺服器的 **session** 裡（cookie `JSESSIONID`），每回一頁就換新
 * - POST 沒帶 cookie、cookie 不認得（session 過期）、或 token 不是這個 session 最新的那個 → 回空表（[Samples.tdccExpired]）
 * - 沒有 session 的請求會開一個新的、回 `Set-Cookie`
 *
 * 手機上第一版就是壞在這裡：App 的 HTTP 沒存 cookie，每一發都是新 session，token 永遠對不上。
 * [levels]：(代號, 週) → 15 級；回 null 是「查無此資料」。
 */
class FakeTdcc(
    @Volatile var weeks: List<String>,
    private val levels: (code: String, ymd: String) -> List<Samples.Level>?,
) {
    /** 一個回應：HTML 與要不要回 `Set-Cookie`。 */
    data class Reply(val body: String, val setCookie: String?)

    private val sessions = mutableMapOf<String, String>() // session id → 這個 session 最新的 token
    private var sessionCount = 0
    private var tokenCount = 0

    /** 被當成 token 失效、回空表的 POST 次數。 */
    @Volatile var rejected = 0
        private set

    @Synchronized
    fun get(cookieHeader: String?): Reply {
        val (sid, setCookie) = session(cookieHeader)
        val token = newToken(sid)
        return Reply(Samples.tdccForm(token, weeks), setCookie)
    }

    @Synchronized
    fun post(cookieHeader: String?, form: Map<String, String>): Reply {
        val known = sessionOf(cookieHeader)?.takeIf { it in sessions }
        val valid = known != null && form["SYNCHRONIZER_TOKEN"] == sessions[known]
        val (sid, setCookie) = session(cookieHeader)
        val next = newToken(sid)
        val ymd = form["scaDate"].orEmpty()
        val body = if (!valid) {
            rejected++
            Samples.tdccExpired(next, weeks)
        } else {
            levels(form["stockNo"].orEmpty(), ymd)?.let { Samples.tdccWeek(next, weeks, ymd, it) } ?: Samples.tdccNotFound(next, weeks)
        }
        return Reply(body, setCookie)
    }

    /** 伺服器那邊的 session 全部過期（例如閒置太久）。 */
    @Synchronized
    fun expireSessions() = sessions.clear()

    /** 有人用同一個 session 在別處查了一次：手上的 token 變舊的。 */
    @Synchronized
    fun useUpTokens() = sessions.keys.forEach { newToken(it) }

    private fun session(cookieHeader: String?): Pair<String, String?> {
        val known = sessionOf(cookieHeader)?.takeIf { it in sessions }
        if (known != null) return known to null
        val sid = "S${++sessionCount}"
        sessions[sid] = ""
        return sid to "JSESSIONID=$sid; Path=/portal; HttpOnly"
    }

    private fun newToken(sid: String): String = "token-${++tokenCount}".also { sessions[sid] = it }

    companion object {
        /** `Cookie: a=1; JSESSIONID=S1` 裡的 session id。 */
        fun sessionOf(cookieHeader: String?): String? =
            cookieHeader?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("JSESSIONID=") }?.substringAfter('=')

        /** `application/x-www-form-urlencoded` 的 body。 */
        fun parseForm(body: String): Map<String, String> = body.split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
    }
}
