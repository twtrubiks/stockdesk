package com.twtrubiks.stockdesk

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.NavigationDelegate.LoadRequest
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BasePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

/** 網頁要使用者回應的對話框（alert、confirm、prompt、`<select>`），回應時 [complete]。 */
class PendingPrompt(val prompt: BasePrompt, private val result: GeckoResult<PromptResponse>) {
    fun complete(response: PromptResponse) = result.complete(response)

    fun dismiss() = result.complete(prompt.dismiss())
}

/**
 * 內建瀏覽器頁的一個分頁：一個或幾個疊起來的 [Page] 與畫面要用的狀態。放在 [AppContainer]，轉螢幕不會重新載入。
 * delegate 都在主執行緒被呼叫，直接改 Compose 的狀態。
 * 下載、上傳檔案不做；定位、相機等權限一律拒絕；不存帳號密碼、不做任何自動登入。
 */
class BrowserTab(private val context: Context, private val runtime: GeckoRuntime, private val ublock: Ublock, initialUrl: String?) {
    /** 一個 GeckoSession 與它自己的狀態。 */
    inner class Page(val session: GeckoSession) {
        var url by mutableStateOf("")
        var title by mutableStateOf("")
        var progress by mutableIntStateOf(0)
        var loading by mutableStateOf(false)
        var canGoBack by mutableStateOf(false)
        var canGoForward by mutableStateOf(false)

        /** uBO 在這一頁自己的按鈕狀態（擋了幾個的 badge），畫面用 [Ublock.actionFor] 跟預設值合起來。 */
        var ublockAction by mutableStateOf<WebExtension.Action?>(null)

        /** 要它載入、還沒換過去的網址。session 剛開時 GeckoView 會先報一次 `about:blank`，那時網址列不要跟著變。 */
        var requested: String? = null

        val isCurrent get() = pages.lastOrNull() === this
    }

    /**
     * 最後一個是看得到的那頁。網頁開新視窗（`target=_blank`、`window.open`）時疊一頁上去，原本的留著：
     * 回上一頁回得去，新視窗的 `window.opener` 也接得上（例如用 Google 帳號登入的小視窗，登入完會 `window.close()`）。
     */
    val pages = mutableStateListOf<Page>()
    val page get() = pages.last()
    val session get() = page.session
    val url get() = page.url
    val title get() = page.title
    val progress get() = page.progress
    val loading get() = page.loading
    val canGoBack get() = page.canGoBack || pages.size > 1
    val canGoForward get() = page.canGoForward
    val ublockAction get() = page.ublockAction

    var fullscreen by mutableStateOf(false)
        private set
    var prompt by mutableStateOf<PendingPrompt?>(null)
        private set

    /** 給使用者看的一句話（例如「不支援下載」），顯示完設回 null。 */
    var message by mutableStateOf<String?>(null)

    /** 網頁自己（不是使用者點的）要用別的 App 打開的網址：先問使用者，顯示完設回 null。 */
    var externalRequest by mutableStateOf<String?>(null)

    /** 顯示這個分頁的 GeckoView（畫面上的 `AndroidView`），換頁時跟著換 session。 */
    private var view: GeckoView? = null
    private val main = Handler(Looper.getMainLooper())

    init {
        ublock.register(this)
        pages += newPage(open = true)
        if (!initialUrl.isNullOrEmpty()) load(initialUrl)
    }

    /** [open]：網頁開的新視窗由 GeckoView 自己 open（`onNewSession` 要回傳還沒 open 的）。 */
    private fun newPage(open: Boolean): Page {
        val page = Page(GeckoSession())
        page.session.navigationDelegate = Navigation(page)
        page.session.progressDelegate = Progress(page)
        page.session.contentDelegate = Content(page)
        page.session.promptDelegate = Prompts(page)
        page.session.permissionDelegate = Permissions()
        if (open) page.session.open(runtime)
        ublock.attach(page)
        return page
    }

    fun load(uri: String) {
        page.url = uri
        page.requested = uri
        session.loadUri(uri)
    }

    /** 這一頁能回上一頁就回；不能的話，這頁是網頁開的新視窗就關掉它，回到開它的那頁。 */
    fun goBack() {
        if (page.canGoBack) session.goBack() else if (pages.size > 1) closePage(page)
    }

    /** 畫面上的 GeckoView 接上目前的 session；換頁之後再呼叫一次就會換過去。 */
    fun attach(view: GeckoView) {
        this.view = view
        if (view.session !== session) {
            view.releaseSession()
            view.setSession(session)
        }
    }

    fun detach(view: GeckoView) {
        if (this.view === view) this.view = null
        view.releaseSession()
    }

    fun close() {
        prompt?.dismiss()
        ublock.unregister(this)
        view?.releaseSession()
        view = null
        pages.forEach { it.session.close() }
    }

    /** 換到另一頁時，上一頁的對話框、全螢幕都不留。 */
    private fun leaveCurrentPage() {
        prompt?.dismiss()
        fullscreen = false
    }

    private fun closePage(page: Page) {
        if (pages.size <= 1 || page !in pages) return
        if (page.isCurrent) leaveCurrentPage()
        pages.remove(page)
        // GeckoView 先換到開它的那頁，再關掉這頁的 session
        view?.let(::attach)
        page.session.close()
    }

    /**
     * 網頁內容的行程掛掉或被系統收掉（切到背景久了常發生）：session 重開、再載入一次。
     * 畫面上的 GeckoView 也要重新接上，不然重新載入了畫面還是一片白。
     */
    private fun recover(page: Page) {
        page.loading = false
        if (page.isCurrent) fullscreen = false
        val shown = view?.takeIf { it.session === page.session }
        shown?.releaseSession()
        if (page.session.isOpen) page.session.close()
        page.session.open(runtime)
        shown?.setSession(page.session)
        if (page.url.isNotEmpty()) {
            page.requested = page.url
            page.session.loadUri(page.url)
        }
    }

    /**
     * 交給手機上別的 App（`mailto:`、`tel:`、`intent:`…）。
     * `intent:` 照 Chrome 的做法：拿掉指定的元件，找不到 App 時改開 `browser_fallback_url`。
     */
    fun openExternally(uri: String) {
        try {
            val intent = if (uri.startsWith("intent:", ignoreCase = true)) {
                Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).apply {
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, uri.toUri())
            }
            intent.addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                val fallback = intent.getStringExtra("browser_fallback_url")
                if (fallback != null && BrowserRules.isHttp(fallback)) load(fallback) else message = "手機上沒有可以打開這個連結的 App"
            }
        } catch (e: Exception) {
            Log.w(TAG, "打不開 $uri", e)
            message = "打不開這個連結"
        }
    }

    private inner class Navigation(private val page: Page) : GeckoSession.NavigationDelegate {
        override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<ContentPermission>, hasUserGesture: Boolean) {
            if (url == null) return
            if (url == BLANK && page.requested != null && page.requested != BLANK) return
            page.requested = null
            page.url = url
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            page.canGoBack = canGoBack
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            page.canGoForward = canGoForward
        }

        override fun onLoadRequest(session: GeckoSession, request: LoadRequest): GeckoResult<AllowOrDeny>? {
            Log.d(TAG, "onLoadRequest target=${request.target} gesture=${request.hasUserGesture} redirect=${request.isRedirect} ${request.uri}")
            if (!BrowserRules.isWeb(request.uri)) {
                // 使用者點的，或點了之後伺服器轉址過去的（例如 line.me 轉到 LINE）直接開；
                // 網頁自己跳的先問一聲，不然畫面停在一片白也不知道為什麼
                if (page.isCurrent) {
                    if (request.hasUserGesture || request.isRedirect) openExternally(request.uri) else externalRequest = request.uri
                }
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }
            // 新視窗交給 onNewSession 疊一頁。沒有使用者點擊就跳出來的（廣告）Gecko 自己會擋（PromptDelegate.onPopupPrompt 預設拒絕），
            // 這裡不看 hasUserGesture：點了之後先做非同步的事才 window.open 的（登入的小視窗常這樣）會是 false
            return null
        }

        // 新視窗疊在這個分頁上，GeckoView 會自己 open 它、載入網址（window.open() 之後再設 location 的也接得上）
        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
            Log.d(TAG, "onNewSession $uri")
            if (pages.size >= MAX_PAGES) pages.removeAt(0).session.close()
            leaveCurrentPage()
            val opened = newPage(open = false)
            if (BrowserRules.isHttp(uri)) {
                opened.url = uri
                opened.requested = uri
            }
            pages += opened
            return GeckoResult.fromValue(opened.session)
        }

        // GeckoView 沒有內建的錯誤頁，不給的話畫面停在一片白
        override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String> {
            Log.w(TAG, "網頁載入失敗 code=${error.code} $uri")
            return GeckoResult.fromValue(BrowserRules.errorPage(uri.orEmpty(), reason(error)))
        }
    }

    private inner class Progress(private val page: Page) : GeckoSession.ProgressDelegate {
        // session 剛開時先載入一次 about:blank（比我們要的網址早開始、早結束）：不算，不然還沒開始載就說載完了
        private var blank = false

        override fun onPageStart(session: GeckoSession, url: String) {
            blank = url == BLANK && page.requested != null && page.requested != BLANK
            if (blank) return
            page.loading = true
            page.progress = 0
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            if (!blank) page.progress = progress
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            if (blank) {
                blank = false
                return
            }
            page.loading = false
            // release 冒煙測試看 logcat 的這一行，確認壓過的 GeckoView 真的載入了網頁
            Log.i(TAG, "網頁載入完成 success=$success ${page.url}")
        }
    }

    private inner class Content(private val page: Page) : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            page.title = title.orEmpty()
        }

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            if (page.isCurrent) fullscreen = fullScreen
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            if (page.isCurrent) message = "不支援下載檔案"
        }

        // 網頁自己 window.close()：疊上來的那頁關掉，回到開它的那頁（最底下那頁不關）。
        // 等這個 callback 回去之後再關，不在 GeckoView 通知我們的途中關掉它的 session
        override fun onCloseRequest(session: GeckoSession) {
            main.post { closePage(page) }
        }

        override fun onCrash(session: GeckoSession) {
            Log.w(TAG, "網頁的行程掛了，重新載入 ${page.url}")
            recover(page)
        }

        override fun onKill(session: GeckoSession) {
            Log.w(TAG, "網頁的行程被系統收掉了，重新載入 ${page.url}")
            recover(page)
        }
    }

    /** alert、confirm、prompt、`<select>`、離開頁面與重新送出表單的確認。其他（上傳檔案、選日期…）交給預設：直接取消。 */
    private inner class Prompts(private val page: Page) : PromptDelegate {
        private fun ask(p: BasePrompt): GeckoResult<PromptResponse> {
            // 看不到的那頁跳出來的不理
            if (!page.isCurrent) return GeckoResult.fromValue(p.dismiss())
            this@BrowserTab.prompt?.dismiss()
            val result = GeckoResult<PromptResponse>()
            val pending = PendingPrompt(p, result)
            // 網頁自己把對話框收掉（例如換頁了）時，畫面上的也收掉
            p.delegate = object : PromptDelegate.PromptInstanceDelegate {
                override fun onPromptDismiss(prompt: BasePrompt) {
                    if (this@BrowserTab.prompt === pending) this@BrowserTab.prompt = null
                }
            }
            this@BrowserTab.prompt = pending
            return result.then { response ->
                if (this@BrowserTab.prompt === pending) this@BrowserTab.prompt = null
                GeckoResult.fromValue(response)
            }
        }

        override fun onAlertPrompt(session: GeckoSession, prompt: PromptDelegate.AlertPrompt) = ask(prompt)

        override fun onButtonPrompt(session: GeckoSession, prompt: PromptDelegate.ButtonPrompt) = ask(prompt)

        override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt) = ask(prompt)

        override fun onChoicePrompt(session: GeckoSession, prompt: PromptDelegate.ChoicePrompt) = ask(prompt)

        override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: PromptDelegate.BeforeUnloadPrompt) = ask(prompt)

        override fun onRepostConfirmPrompt(session: GeckoSession, prompt: PromptDelegate.RepostConfirmPrompt) = ask(prompt)
    }

    private class Permissions : PermissionDelegate {
        override fun onAndroidPermissionsRequest(session: GeckoSession, permissions: Array<out String>?, callback: PermissionDelegate.Callback) {
            callback.reject()
        }

        // 靜音的自動播放（很多網站的影片預覽）放行，其他（定位、通知、有聲自動播放、DRM…）一律拒絕
        override fun onContentPermissionRequest(session: GeckoSession, perm: ContentPermission): GeckoResult<Int> =
            GeckoResult.fromValue(
                if (perm.permission == PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_DENY,
            )

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<out PermissionDelegate.MediaSource>?,
            audio: Array<out PermissionDelegate.MediaSource>?,
            callback: PermissionDelegate.MediaCallback,
        ) {
            callback.reject()
        }
    }

    private companion object {
        /** 一個分頁最多疊幾頁新視窗，超過就關掉最底下的。 */
        const val MAX_PAGES = 6

        const val BLANK = "about:blank"

        /** 錯誤頁上給使用者看的原因。 */
        fun reason(error: WebRequestError): String = when (error.code) {
            WebRequestError.ERROR_UNKNOWN_HOST -> "找不到這個網站：網址可能打錯了，或是手機沒有連上網路。"
            WebRequestError.ERROR_OFFLINE -> "手機沒有連上網路。"
            WebRequestError.ERROR_CONNECTION_REFUSED,
            WebRequestError.ERROR_NET_RESET,
            WebRequestError.ERROR_NET_INTERRUPT,
            WebRequestError.ERROR_NET_TIMEOUT,
            WebRequestError.ERROR_PORT_BLOCKED,
            WebRequestError.ERROR_UNKNOWN_SOCKET_TYPE,
            -> "連不上這個網站：可能是網站暫時有問題，或是網路不穩。"
            WebRequestError.ERROR_REDIRECT_LOOP -> "這個網頁一直轉址，停不下來。"
            WebRequestError.ERROR_SECURITY_SSL,
            WebRequestError.ERROR_SECURITY_BAD_CERT,
            WebRequestError.ERROR_BAD_HSTS_CERT,
            -> "這個網站的安全憑證有問題，為了安全不打開。"
            WebRequestError.ERROR_MALFORMED_URI, WebRequestError.ERROR_UNKNOWN_PROTOCOL -> "網址的格式不對，或是瀏覽器不支援這種網址。"
            WebRequestError.ERROR_CONTENT_CRASHED -> "網頁當掉了。"
            WebRequestError.ERROR_CORRUPTED_CONTENT,
            WebRequestError.ERROR_INVALID_CONTENT_ENCODING,
            WebRequestError.ERROR_UNSAFE_CONTENT_TYPE,
            -> "網站送來的內容有問題。"
            WebRequestError.ERROR_SAFEBROWSING_MALWARE_URI,
            WebRequestError.ERROR_SAFEBROWSING_UNWANTED_URI,
            WebRequestError.ERROR_SAFEBROWSING_HARMFUL_URI,
            WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI,
            -> "這個網站被標為不安全的網站。"
            else -> "網頁載入失敗（錯誤代碼 ${error.code}）。"
        }
    }
}
