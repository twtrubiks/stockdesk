package com.twtrubiks.stockdesk

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.filterNotNull
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

/**
 * 內建的瀏覽器頁（GeckoView ＋ uBlock Origin），goodinfo、社群討論區、八大官股都在這裡開，不用離開 App。
 * 分頁本身（[BrowserTab]）放在 [AppContainer]，[tabKey] 是這層蓋頁的 key；關掉這一頁時 [StockDeskApp] 會 `closeTab`。
 * - 標題列：網頁的 title（還沒載好時顯示網址）、uBO 的按鈕（圖示＋這一頁擋了幾個，按了開 popup）、重新整理／停止、
 *   「在瀏覽器開啟」（丟給手機的瀏覽器，goodinfo 的 Cloudflare 驗證過不了時用）
 * - 系統返回鍵：全螢幕先退出；網頁還有上一頁就回上一頁（網頁開的新視窗沒有上一頁時關掉它）；都沒有才關掉這一頁
 * - `http`／`https` 以外的連結（`intent:`、`market:`…）交給系統開：使用者點的直接開，網頁自己跳的先問一聲
 * - 不存帳號密碼、不做任何自動登入；下載、上傳不做；權限一律拒絕
 * 轉螢幕由 `configChanges` 接住，GeckoView 不用重接畫面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(tabKey: String, url: String, onClose: () -> Unit) {
    val container = LocalContext.current.container
    // 行程被系統收掉再回來時，從最後看的那一頁重新開始
    var lastUrl by rememberSaveable { mutableStateOf(url) }
    val tab = remember(tabKey) { container.tab(tabKey) { lastUrl } }
    val ublock = container.ublock
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { ublock.start() }
    LaunchedEffect(tab.url) { if (tab.url.isNotEmpty()) lastUrl = tab.url }
    // 不能拿 tab.message 當 LaunchedEffect 的 key：在裡面把它設回 null，key 一變 effect 就被取消，提示剛出現就收掉
    LaunchedEffect(tab) {
        snapshotFlow { tab.message }.filterNotNull().collect {
            tab.message = null
            snackbar.showSnackbar(it)
        }
    }
    // 網頁自己要打開別的 App（沒有經過使用者點擊）：問一聲
    LaunchedEffect(tab) {
        snapshotFlow { tab.externalRequest }.filterNotNull().collect { uri ->
            tab.externalRequest = null
            val result = snackbar.showSnackbar("這個網頁要用其他 App 打開", actionLabel = "打開", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) tab.openExternally(uri)
        }
    }

    // 看得到的那頁才算「正在用」：GeckoView 在背景會降低耗電，uBO 的 popup 也靠這個知道是哪一頁。
    // 網頁開了新視窗（疊上一頁）或關掉它時，換成那一頁
    val lifecycleOwner = LocalLifecycleOwner.current
    val session = tab.session
    DisposableEffect(session, lifecycleOwner) {
        fun active(on: Boolean) {
            if (!session.isOpen) return
            session.setActive(on)
            ublock.setActive(session, on)
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> active(true)
                Lifecycle.Event.ON_STOP -> active(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        active(true)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            active(false)
        }
    }

    FullscreenEffect(tab.fullscreen)

    BackHandler {
        when {
            tab.fullscreen -> tab.session.exitFullScreen()
            tab.canGoBack -> tab.goBack()
            else -> onClose()
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (!tab.fullscreen) {
            TopAppBar(
                title = { Text(tab.title.ifEmpty { tab.url.ifEmpty { url } }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose, Modifier.testTag("browser_close")) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "關閉") }
                },
                actions = {
                    UblockButton(ublock, ublock.actionFor(tab))
                    if (tab.loading) {
                        IconButton(onClick = { tab.session.stop() }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "停止") }
                    } else {
                        IconButton(onClick = { tab.session.reload() }) { Icon(painterResource(R.drawable.ic_refresh), contentDescription = "重新整理") }
                    }
                    val external = tab.url.takeIf(BrowserRules::isHttp) ?: url
                    IconButton(onClick = { tab.openExternally(external) }, Modifier.testTag("browser_external")) {
                        Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = "在瀏覽器開啟")
                    }
                },
            )
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (tab.loading) LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxSize())
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = ::GeckoView,
                // 讀了 tab.session：換頁之後會再呼叫一次，換成那一頁的 session
                update = tab::attach,
                onRelease = tab::detach,
                modifier = Modifier.fillMaxSize().testTag("browser_web"),
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }

    tab.prompt?.let { PromptDialog(it) }
    ublock.popup?.let { UblockPopup(it, onDismiss = ublock::closePopup) }
}

/** 網頁要全螢幕（影片）時把系統列也藏起來，滑一下會暫時出現。 */
@Composable
private fun FullscreenEffect(fullscreen: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    DisposableEffect(fullscreen) {
        val controller = WindowCompat.getInsetsController(window, view)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** uBO 的按鈕：它自己的圖示（關掉過濾時會變灰）＋這一頁擋了幾個。按了打開 popup。uBO 還沒好就不顯示。 */
@Composable
private fun UblockButton(ublock: Ublock, action: WebExtension.Action?) {
    if (action == null) return
    var icon by remember { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(action.icon) {
        val result = action.icon?.getBitmap(96)
        result?.accept({ icon = it }, { })
        onDispose { result?.cancel() }
    }
    Box(
        Modifier
            .size(48.dp)
            .clickable(onClickLabel = action.title ?: "uBlock Origin") { action.click() }
            .testTag("ublock_button"),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = icon ?: ublock.icon
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = action.title ?: "uBlock Origin",
                modifier = Modifier.size(26.dp).alpha(if (action.enabled == false) 0.4f else 1f),
            )
        } else {
            Text("uBO", style = MaterialTheme.typography.labelMedium)
        }
        val badge = action.badgeText
        if (!badge.isNullOrEmpty()) {
            Text(
                badge,
                Modifier
                    .align(Alignment.BottomEnd)
                    .background(MaterialTheme.colorScheme.error, RoundedCornerShape(6.dp))
                    .padding(horizontal = 4.dp)
                    .testTag("ublock_badge"),
                color = MaterialTheme.colorScheme.onError,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
    }
}

/** alert、confirm、prompt、`<select>`、離開頁面與重新送出表單的確認。 */
@Composable
private fun PromptDialog(pending: PendingPrompt) {
    val title = pending.prompt.title?.takeIf { it.isNotBlank() }
    when (val p = pending.prompt) {
        is PromptDelegate.AlertPrompt -> AlertDialog(
            onDismissRequest = pending::dismiss,
            title = title?.let { { Text(it) } },
            text = { Text(p.message.orEmpty()) },
            confirmButton = { TextButton(onClick = pending::dismiss) { Text("確定") } },
        )
        is PromptDelegate.ButtonPrompt -> AlertDialog(
            onDismissRequest = { pending.complete(p.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE)) },
            title = title?.let { { Text(it) } },
            text = { Text(p.message.orEmpty()) },
            confirmButton = { TextButton(onClick = { pending.complete(p.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE)) }) { Text("確定") } },
            dismissButton = { TextButton(onClick = { pending.complete(p.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }) { Text("取消") } },
        )
        is PromptDelegate.TextPrompt -> {
            var value by remember(p) { mutableStateOf(p.defaultValue.orEmpty()) }
            AlertDialog(
                onDismissRequest = pending::dismiss,
                title = title?.let { { Text(it) } },
                text = {
                    Column {
                        p.message?.let { Text(it, Modifier.padding(bottom = 8.dp)) }
                        OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true)
                    }
                },
                confirmButton = { TextButton(onClick = { pending.complete(p.confirm(value)) }) { Text("確定") } },
                dismissButton = { TextButton(onClick = pending::dismiss) { Text("取消") } },
            )
        }
        is ChoicePrompt -> ChoiceDialog(pending, p)
        is PromptDelegate.BeforeUnloadPrompt -> AlertDialog(
            onDismissRequest = { pending.complete(p.confirm(AllowOrDeny.DENY)) },
            text = { Text("要離開這一頁嗎？輸入的資料可能不會保存。") },
            confirmButton = { TextButton(onClick = { pending.complete(p.confirm(AllowOrDeny.ALLOW)) }) { Text("離開") } },
            dismissButton = { TextButton(onClick = { pending.complete(p.confirm(AllowOrDeny.DENY)) }) { Text("留下") } },
        )
        is PromptDelegate.RepostConfirmPrompt -> AlertDialog(
            onDismissRequest = { pending.complete(p.confirm(AllowOrDeny.DENY)) },
            text = { Text("要再送出一次表單嗎？") },
            confirmButton = { TextButton(onClick = { pending.complete(p.confirm(AllowOrDeny.ALLOW)) }) { Text("再送一次") } },
            dismissButton = { TextButton(onClick = { pending.complete(p.confirm(AllowOrDeny.DENY)) }) { Text("取消") } },
        )
        else -> LaunchedEffect(p) { pending.dismiss() }
    }
}

/** `<select>`：單選點了就送出，多選勾完按確定。`<optgroup>` 的標題顯示成不能點的一行。 */
@Composable
private fun ChoiceDialog(pending: PendingPrompt, p: ChoicePrompt) {
    val multiple = p.type == ChoicePrompt.Type.MULTIPLE
    val rows = remember(p) { p.choices.flatMap { c -> if (c.items != null) listOf(c) + c.items!!.toList() else listOf(c) } }
    val selected = remember(p) { mutableStateListOf<ChoicePrompt.Choice>().apply { addAll(rows.filter { it.items == null && it.selected }) } }
    AlertDialog(
        onDismissRequest = pending::dismiss,
        title = p.title?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        text = {
            Column(Modifier.fillMaxHeight(0.6f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (choice in rows) {
                    when {
                        choice.separator -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        choice.items != null -> Text(choice.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        else -> Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !choice.disabled) {
                                    if (!multiple) {
                                        pending.complete(p.confirm(choice))
                                    } else if (choice in selected) {
                                        selected.remove(choice)
                                    } else {
                                        selected.add(choice)
                                    }
                                }
                                .padding(vertical = 10.dp)
                                .alpha(if (choice.disabled) 0.4f else 1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (multiple) Checkbox(checked = choice in selected, onCheckedChange = null, Modifier.padding(end = 8.dp))
                            Text(choice.label, color = if (!multiple && choice.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (multiple) {
                TextButton(onClick = { pending.complete(p.confirm(selected.toTypedArray())) }) { Text("確定") }
            } else {
                TextButton(onClick = pending::dismiss) { Text("取消") }
            }
        },
        dismissButton = if (multiple) ({ TextButton(onClick = pending::dismiss) { Text("取消") } }) else null,
    )
}

/**
 * uBO 的 popup（那顆大電源鈕：這個網站要不要過濾）。uBO 回傳的是一個 GeckoSession，放進 BottomSheet 裡的 GeckoView。
 * BottomSheet 的拖曳關掉：popup 裡可以捲，拖曳會跟它搶；點外面或按返回鍵關。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun UblockPopup(session: GeckoSession, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
    ) {
        // BottomSheet 是另一個視窗，App 根部開的 testTagsAsResourceId 管不到這裡
        Column(Modifier.semantics { testTagsAsResourceId = true }.testTag("ublock_popup")) {
            AndroidView(
                // 用預設的 SurfaceView：TextureView 在模擬器上整塊是黑的（網頁有畫，顯示不出來）
                factory = ::GeckoView,
                update = {
                    if (it.session !== session) {
                        it.setSession(session)
                        session.setActive(true)
                    }
                },
                onRelease = { it.releaseSession() },
                modifier = Modifier.fillMaxWidth().height(560.dp),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(end = 12.dp, bottom = 8.dp)) { Text("關閉") }
        }
    }
}
