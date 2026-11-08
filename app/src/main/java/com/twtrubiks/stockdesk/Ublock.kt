package com.twtrubiks.stockdesk

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.util.concurrent.TimeUnit

/**
 * uBlock Origin。不打包進 APK，跟 Firefox 一樣從 Mozilla 附加元件網站（AMO）裝有簽章的版本：
 * 第一次打開瀏覽器時裝，之後裝好的會留在 GeckoView 的設定檔裡；App 打開時，距離上次檢查超過一天就問 AMO 有沒有新版
 * （跟 Firefox 桌面版一樣 24 小時），有才下載。檢查的結果寫進 logcat，版本與上次檢查的時間顯示在設定頁。
 * 擋了幾個（badge）每一頁各自記在 [BrowserTab.Page.ublockAction]；按下去的 popup（那顆大電源鈕）是 [popup]。
 */
class Ublock(private val runtime: GeckoRuntime, private val prefs: SharedPreferences, private val now: () -> Long = System::currentTimeMillis) {
    var extension by mutableStateOf<WebExtension?>(null)
        private set

    /** 裝好的版本（設定頁顯示），還沒裝好是 null。 */
    var version by mutableStateOf<String?>(null)
        private set

    /** 新版已經下載好、下次打開 App 才換上的版本（uBO 正在用，它自己要求延後）。沒有是 null。 */
    var pendingVersion by mutableStateOf<String?>(null)
        private set

    /** 上次成功問過 AMO 的時間（毫秒）：裝的時候、檢查更新的時候。0 是還沒有過。 */
    var checkedAt by mutableLongStateOf(prefs.getLong(CHECKED_AT, 0))
        private set
    var icon by mutableStateOf<Bitmap?>(null)
        private set

    /** 所有分頁共用的按鈕狀態（圖示、標題）；每一頁自己的（badge）在 [BrowserTab.Page.ublockAction]，用 [actionFor] 合起來。 */
    var defaultAction by mutableStateOf<WebExtension.Action?>(null)
        private set

    /** 打開中的 popup。關掉時呼叫 [closePopup]。 */
    var popup by mutableStateOf<GeckoSession?>(null)
        private set

    private val controller get() = runtime.webExtensionController
    private var started = false
    private val tabs = mutableListOf<BrowserTab>()

    /** 在主執行緒呼叫；重複呼叫沒關係。上次裝失敗（例如沒有網路）的話，這次再試一次。 */
    fun start() {
        if (started) return
        started = true
        controller.promptDelegate = object : WebExtensionController.PromptDelegate {
            // 只同意 uBO 自己的權限；不給無痕模式（沒有無痕）、不給 Mozilla 的資料收集
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<out String>,
                origins: Array<out String>,
                dataCollectionPermissions: Array<out String>,
            ) = GeckoResult.fromValue(WebExtension.PermissionPromptResponse(extension.id == ID, false, false))

            override fun onUpdatePrompt(
                extension: WebExtension,
                newPermissions: Array<out String>,
                newOrigins: Array<out String>,
                newDataCollectionPermissions: Array<out String>,
            ) = GeckoResult.fromValue(if (extension.id == ID) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
        }
        controller.list().accept({ list ->
            val installed = list?.firstOrNull { it.id == ID }
            if (installed == null) install() else ready(installed).also { maybeUpdate(installed) }
        }, { e ->
            Log.w(TAG, "列不出已經裝的擴充功能", e)
            install()
        })
    }

    private fun install() {
        Log.i(TAG, "從 AMO 安裝 uBlock Origin")
        controller.install(AMO_URL).accept({ installed ->
            if (installed != null) {
                markChecked()
                ready(installed)
            }
        }, { e ->
            Log.w(TAG, "uBlock Origin 裝不起來，下次打開瀏覽器再試", e)
            started = false
        })
    }

    /** 距離上次問 AMO 超過一天才問；沒有網路之類的失敗不記時間，下次打開 App 再試。 */
    private fun maybeUpdate(installed: WebExtension) {
        if (now() - checkedAt < TimeUnit.DAYS.toMillis(1)) return
        val old = installed.metaData.version
        Log.i(TAG, "檢查 uBlock Origin 有沒有新版（現在是 $old）")
        controller.update(installed).accept({ updated ->
            markChecked()
            // 沒有新版時 GeckoView 給 null
            if (updated == null || updated.metaData.version == old) {
                Log.i(TAG, "uBlock Origin 已經是最新（$old）")
            } else {
                Log.i(TAG, "uBlock Origin 從 $old 更新到 ${updated.metaData.version}")
                ready(updated)
            }
        }, { e ->
            if ((e as? WebExtension.InstallException)?.code == WebExtension.InstallException.ErrorCodes.ERROR_POSTPONED) {
                // uBO 有聽 runtime.onUpdateAvailable、正在用的時候不換：新版已經下載好，
                // 引擎下次啟動（下次打開 App）時才換上。這不是失敗，記下檢查的時間
                markChecked()
                pendingVersion = e.extensionVersion ?: "新版"
                Log.i(TAG, "uBlock Origin 有新版 $pendingVersion，已經下載好，下次打開 App 時換上（現在是 $old）")
            } else {
                Log.w(TAG, "uBlock Origin 檢查更新失敗，下次打開 App 再試", e)
            }
        })
    }

    private fun markChecked() {
        checkedAt = now()
        prefs.edit { putLong(CHECKED_AT, checkedAt) }
    }

    /** 測試用：當作 App 重新打開，重跑 [start]（找已經裝好的、距離上次檢查超過一天就檢查更新）。 */
    @VisibleForTesting
    fun restart() {
        checkedAt = prefs.getLong(CHECKED_AT, 0)
        started = false
        start()
    }

    private fun ready(installed: WebExtension) {
        // release 冒煙測試看 logcat 的這一行，確認壓過的 GeckoView 從 AMO 裝好了 uBO
        Log.i(TAG, "uBlock Origin ${installed.metaData.version} 可以用了")
        extension = installed
        version = installed.metaData.version
        installed.setActionDelegate(object : WebExtension.ActionDelegate {
            override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
                // session 是 null：所有分頁的預設值（圖示、標題）
                defaultAction = action
            }

            override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action) = openPopup()

            override fun onOpenPopup(extension: WebExtension, action: WebExtension.Action) = openPopup()
        })
        // popup 裡的「打開控制台」之類會開新分頁：只有一個分頁，在目前的分頁開
        installed.setTabDelegate(object : WebExtension.TabDelegate {
            override fun onNewTab(extension: WebExtension, details: WebExtension.CreateTabDetails): GeckoResult<GeckoSession>? {
                closePopup()
                details.url?.let { url -> tabs.lastOrNull()?.load(url) }
                return null
            }
        })
        installed.metaData.icon.getBitmap(96).accept({ icon = it }, { e -> Log.w(TAG, "uBO 的圖示讀不出來", e) })
        tabs.forEach { tab -> tab.pages.forEach(::attach) }
    }

    /** 分頁開起來時登記：popup 要開新分頁時在最後打開的分頁開，uBO 好了之後也要接上已經開著的頁面。 */
    fun register(tab: BrowserTab) {
        tabs += tab
    }

    fun unregister(tab: BrowserTab) {
        tabs -= tab
    }

    /** 正在看的那頁：uBO 的 popup 用 `tabs.query({active: true})` 找要顯示哪一頁的統計。 */
    fun setActive(session: GeckoSession, active: Boolean) = controller.setTabActive(session, active)

    /** 接上這一頁的 badge（擋了幾個）。uBO 還沒好的話，好了之後由 [ready] 補接。 */
    fun attach(page: BrowserTab.Page) {
        val ext = extension ?: return
        page.session.webExtensionController.setActionDelegate(ext, object : WebExtension.ActionDelegate {
            override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
                page.ublockAction = action
            }
        })
    }

    /** 這個分頁上 uBO 按鈕該長怎樣；uBO 還沒好是 null。 */
    fun actionFor(tab: BrowserTab): WebExtension.Action? {
        if (extension == null) return null
        val own = tab.ublockAction ?: return defaultAction
        return defaultAction?.let(own::withDefault) ?: own
    }

    private fun openPopup(): GeckoResult<GeckoSession> {
        closePopup()
        val session = GeckoSession()
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            // popup 自己呼叫 window.close()
            override fun onCloseRequest(session: GeckoSession) = closePopup()
        }
        session.open(runtime)
        popup = session
        return GeckoResult.fromValue(session)
    }

    fun closePopup() {
        popup?.close()
        popup = null
    }

    companion object {
        const val ID = "uBlock0@raymondhill.net"

        /** AMO 上最新的簽章版，會轉址到 `ublock_origin-<版本>.xpi`。 */
        const val AMO_URL = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
        /** 上次問 AMO 的時間，存在 SharedPreferences `browser`（測試改舊它，就會再檢查一次）。 */
        const val CHECKED_AT = "ublock_checked_at"
    }
}
