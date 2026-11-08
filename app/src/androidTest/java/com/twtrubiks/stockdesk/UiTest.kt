package com.twtrubiks.stockdesk

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.junit.Before
import java.io.File

/**
 * 用 UiAutomator 操作整個 App 的測試共用的東西。
 * 不用 ActivityScenario（會等主執行緒閒下來，畫面有進度條動畫時永遠等不到），
 * UiAutomator 也不等畫面「靜止」，找元件靠 [waitFor] 自己每 0.1 秒看一次。
 */
abstract class UiTest {
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val device: UiDevice = UiDevice.getInstance(instrumentation)
    protected val context = instrumentation.targetContext
    protected val app = context.applicationContext as StockDeskApplication

    // 用到才建（getter）：子類別可以先在 @Before 裡 useEndpointsForTest 換掉資料來源
    protected val container: AppContainer get() = context.container

    // record.sh 會把這個資料夾 pull 回來，放進 media branch 的 screenshots/
    private val screenshotDir = File(context.getExternalFilesDir(null), "screenshots")

    @Before
    fun prepareUi() {
        Configurator.getInstance().waitForIdleTimeout = 0
        screenshotDir.mkdirs()
    }

    protected fun launchApp() {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    protected fun waitFor(selector: BySelector, timeout: Long = 10_000): UiObject2 {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            device.findObject(selector)?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("等不到 $selector")
    }

    /** 等到畫面上沒有這個元件（例如卡片收起來之後，裡面的按鈕）。 */
    protected fun waitGone(selector: BySelector, timeout: Long = 10_000) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            if (device.findObject(selector) == null) return
            SystemClock.sleep(100)
        }
        throw AssertionError("$selector 一直沒有消失")
    }

    protected fun find(selector: BySelector): UiObject2 = waitFor(selector)

    protected fun pause(ms: Long) = SystemClock.sleep(ms)

    protected fun screenshot(name: String) {
        device.takeScreenshot(File(screenshotDir, "$name.png"))
    }

    protected fun hideKeyboard() {
        if (device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")) {
            device.pressBack()
            pause(500)
        }
    }

    protected companion object {
        /** Compose 的 testTag：`testTagsAsResourceId` 給的 resource-id 就是 tag 本身，沒有「套件:id/」前綴。 */
        fun res(tag: String): BySelector = By.res(tag)
    }
}
