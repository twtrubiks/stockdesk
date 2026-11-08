package com.twtrubiks.stockdesk.core.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 一次請求的結果。[body] 是文字（JSON 或 HTML），所有來源都是文字。 */
data class HttpResponse(val code: Int, val body: String, val finalUrl: String) {
    val ok: Boolean get() = code in 200..299
}

/**
 * 資料來源對外只透過這個介面發請求，測試時換成假的（或 MockWebServer）。
 * 連不上、逾時丟 [IOException]；HTTP 4xx／5xx 不丟，由呼叫端看 [HttpResponse.code]。
 */
interface Http {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse

    suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): HttpResponse
}

/**
 * 真的 [Http]：整個 App 一個 [OkHttpClient]（由 app 建、傳進來）。
 * 逾時抓短（連線 5 秒、讀取 8 秒）：官方來源正常回應都在 1 秒內，沒回應多半是被限流，早點停、提示使用者過一陣子再試。
 * cookie 只放記憶體（[MemoryCookies]）：集保的 token 綁在 session cookie 上，沒帶 cookie 的 POST 一律當成 token 失效。
 */
class OkHttp(client: OkHttpClient) : Http {
    private val client = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .cookieJar(MemoryCookies())
        .build()

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = send(
        Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build(),
    )

    override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return send(Request.Builder().url(url).post(body).apply { headers.forEach { (k, v) -> header(k, v) } }.build())
    }

    private suspend fun send(request: Request): HttpResponse = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            HttpResponse(response.code, response.body.string(), response.request.url.toString())
        }
    }
}

/**
 * 只放在記憶體的 cookie：App 關掉就沒了，不寫檔、不備份。官方來源用它記 session（集保的 `JSESSIONID`），
 * 不是登入用的；內建瀏覽器（GeckoView）的 cookie 是另一套，跟這裡無關。
 */
class MemoryCookies(private val now: () -> Long = System::currentTimeMillis) : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            if (c.expiresAt > now()) this.cookies += c
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt <= now() }
        return cookies.filter { it.matches(url) }
    }
}

/** 所有來源共用的 User-Agent：一般桌面瀏覽器的樣子（期交所、集保對奇怪的 UA 會回不同內容）。 */
const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
