package com.example.musefloatx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingService : Service() {
    private lateinit var wm: WindowManager
    private val lifecycleOwner = ServiceLifecycleOwner()
    private var ballView: ComposeView? = null
    private var panelView: ComposeView? = null
    private var selectionView: ComposeView? = null
    private var boxFallbackText = ""
    private var cropBox by mutableStateOf<Rect?>(null)
    private var cropScreenW by mutableStateOf(0)
    private var cropScreenH by mutableStateOf(0)
    private var cropBottomBarH by mutableStateOf(0)
    private var frozenShotBitmap: android.graphics.Bitmap? = null
    private fun cropMaxBottom(): Int {
        val limit = if (cropBottomBarH > 0) cropScreenH - cropBottomBarH - 48 else cropScreenH
        return limit.coerceAtLeast(0)
    }
    private var cropDragMode = "NONE"
    private var cropDragHint by mutableStateOf("")
    private var cropDragStartBox: Rect? = null
    private var cropDragStartPos = Offset.Zero
    private var selectionReturnToPanel = false

    private fun defaultCropBox(w: Int, h: Int): Rect {
        if (w <= 0 || h <= 0) return Rect(0, 0, 0, 0)
        val maxB = cropMaxBottom()
        val bottom = if (maxB > 0) minOf((h * 0.85f).toInt(), maxB) else (h * 0.85f).toInt()
        return Rect((w * 0.08f).toInt(), (h * 0.15f).toInt(), (w * 0.92f).toInt(), bottom)
    }
    private var ballParams: WindowManager.LayoutParams? = null
    private var ballX = 0
    private var ballY = 0
    private var screenW = 0
    private var ballSizePx = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // 防“长回复被短回复覆盖”：生成没有加锁时，点两次生成、换一次风格、
    // 再点一次读取剪贴板，就会同时跑两三个请求，最后完成的那个（往往
    // 更短）会覆盖先完成的长回复，看起来就是“先长、跳两次、变短”。
    // 每次生成都换一个序号，旧请求的结果（哪怕晚到）一律丢弃，
    // 只认最新一次生成的结果；同时取消上一个任务。
    private var generateJob: Job? = null
    private var generateSeq = 0
    // 根本逻辑：提交一次、生成一次、传输一次。
    // 以前“取消旧的再发新的 + 序号丢弃旧结果”只是遮掩：
    // 旧请求已经发到服务端、已经占锁、已经在生成，遮住不显示
    // 并不能让它不花时间，速度还是三通相加。
    // 现在改为单飞行：生成中，任何新的生成入口一律不提交，
    // 按钮也置灰点不动；等当前这一通完成（或关面板取消）后，
    // 下一次点击才提交新的一通。一次点就是一次提交，绝不叠加。
    private var isGenerating by mutableStateOf(false)
    private lateinit var store: SettingsStore

    private var postText by mutableStateOf("")
    // 粘贴模式：用户在 X 里复制帖子后，在这里粘贴，不走无障碍、不走截图，
    // 所以不再受“确认时前台是谁的窗口”影响，这是最稳的最终办法。
    private var pasteText by mutableStateOf("")
    private var boxImageBitmap by mutableStateOf<android.graphics.Bitmap?>(null)
    private var replyText by mutableStateOf("")
    private var statusText by mutableStateOf("先在 X 里复制帖子，再点悬浮球，面板会读取剪贴板生成")
    private var styleState by mutableStateOf("自然")

    override fun onCreate() {
        super.onCreate()
        store = SettingsStore(this)
        styleState = store.style
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        ballSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 64f, dm).toInt()
        startForegroundNotification()
        lifecycleOwner.onCreate()
        lifecycleOwner.onStart()
        lifecycleOwner.onResume()
        // 本地版加载策略：悬浮球启动时加载模型，不是 App 一打开就常驻。
        // 这里只在后台触发加载，面板生成时 ApiClient 还会再确认一次；
        // 模型没下好时这里不报错，面板生成时会明说“请先下载模型”。
        scope.launch {
            try {
                if (LocalServer.isModelDownloaded(this@FloatingService)) {
                    LocalServer.ensureStarted(this@FloatingService, store.contextTokens)
                }
            } catch (_: Exception) { }
        }
    }

    private fun attachOwners(view: ComposeView) {
        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
    }

    private fun startForegroundNotification() {
        val channelId = "musefloatx"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "悬浮助手", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val noti = Notification.Builder(this, channelId)
            .setContentTitle("MuseFloatX 正在运行")
            .setContentText("点 X 里的悬浮球生成回复建议")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .build()
        startForeground(1, noti)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> {
                removeViews()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> showBall()
        }
        return START_STICKY
    }

    private fun showBall() {
        if (ballView != null) return
        ballX = screenW - ballSizePx - 16
        ballY = resources.displayMetrics.heightPixels / 3
        val params = WindowManager.LayoutParams(
            ballSizePx, ballSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballX
            y = ballY
        }
        ballParams = params
        val view = ComposeView(this)
        attachOwners(view)
        view.setContent {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF7C4DFF))
                    .pointerInput(Unit) {
                        // 直接跟踪手指按下到抬起：24dp 内抬起都算点按，
                        // 超过才算拖动。上一版靠拖动识别的取消回调判断点按，
                        // 在真机上并不可靠，点球经常被当成移动。
                        // 只用 PointerInputChange 最基础的字段，避免接口版本差异。
                        val tapThreshold = 24.dp.toPx()
                        awaitPointerEventScope {
                            while (true) {
                                var downId: PointerId? = null
                                while (downId == null) {
                                    val downEvent = awaitPointerEvent()
                                    val downChange = downEvent.changes.firstOrNull { !it.previousPressed && it.pressed }
                                    if (downChange != null) downId = downChange.id
                                }
                                var moved = 0f
                                var dragging = false
                                var done = false
                                while (!done) {
                                    val event = awaitPointerEvent()
                                    for (change in event.changes) {
                                        if (change.id != downId) continue
                                        if (!change.pressed && change.previousPressed) {
                                            if (!dragging && moved < tapThreshold) onBallTap() else snapToEdge()
                                            done = true
                                        } else if (change.pressed) {
                                            val delta = change.position - change.previousPosition
                                            if (delta != Offset.Zero) {
                                                change.consume()
                                                moved += abs(delta.x) + abs(delta.y)
                                                if (moved >= tapThreshold) dragging = true
                                                if (dragging) {
                                                    ballX = (ballX + delta.x.toInt()).coerceIn(0, screenW - ballSizePx)
                                                    ballY = (ballY + delta.y.toInt()).coerceIn(0, resources.displayMetrics.heightPixels - ballSizePx)
                                                    params.x = ballX
                                                    params.y = ballY
                                                    try { wm.updateViewLayout(view, params) } catch (e: Exception) {}
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("AI", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        ballView = view
        wm.addView(view, params)
    }

    private fun snapToEdge() {
        ballX = if (ballX + ballSizePx / 2 < screenW / 2) 16 else screenW - ballSizePx - 16
        ballParams?.x = ballX
        ballParams?.y = ballY
        try { ballView?.let { wm.updateViewLayout(it, ballParams) } } catch (e: Exception) {}
    }

    private fun onBallTap() {
        if (selectionView != null) {
            cancelSelection()
            return
        }
        if (panelView != null) {
            removePanel()
            return
        }
        // 简化后的主流程（第 24 版）：点球直接出面板，不再出框选、
        // 不截图、不 OCR。先读一次剪贴板（这是用户点球这个动作
        // 触发的，不是后台持续监听），读到就直接生成，读不到就
        // 只提示、不发无效请求，留手动粘贴输入框作备用。
        // 恢复之前那版（第 25 版）的顺序：先出面板，再读剪贴板。
        // 第 30 版改成先读再出面板后，自动读不到、面板直接空白、
        // 点什么都没反应——先读的顺序在真机上不如先出面板稳，
        // 先出面板时面板已经在前台，读剪贴板那一下才跟之前一样能读到。
        showPanel()
        readClipboardAndGenerate()
    }

    /**
     * 剪贴板主流程：用户点球后读一次最新剪贴板。
     * 安卓 12+ 对后台读剪贴板有限制，但这是用户点球触发的当次读取，
     * 且服务是前台服务；系统若不允许，primaryClip 会是空或抛异常，
     * 那就只提示、不生成，用户改用面板里的手动粘贴输入框。
     * 绝不后台持续监听剪贴板。
     */
    private fun readClipboardAndGenerate() {
        // 点球是新的一次面板，必须每次都重新读剪贴板。
        // 第 30 版在这里加了“生成中直接返回”，一旦上一次的生成标志
        // 没清掉，点球就直接静默返回、面板空白、点什么都没反应。
        // 这个 guard 已删掉：点球本身就是新提交，重复提交的防线
        // 在 generate() 的单飞行里，不在这里。
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            val t = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0)?.text?.toString()?.trim() ?: ""
            } else ""
            // 短帖也要生成：剪贴板是你亲手复制的，不是自动读到的界面按钮字，
            // 所以不需要 5 个字过滤——2、3 个字也直接生成，空才不发。
            if (t.isNotEmpty()) {
                postText = t
                pasteText = t
                replyText = ""
                generate()
            } else {
                postText = ""
                replyText = ""
                statusText = "剪贴板是空的：先在 X 里复制帖子，再点悬浮球"
            }
        } catch (e: Exception) {
            postText = ""
            replyText = ""
            statusText = "系统不允许直接读剪贴板：请关面板、重新复制、再点悬浮球"
        }
    }

    private fun showPanel() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 粘贴模式必须能输入：面板不能再 NOT_FOCUSABLE，否则输入框
            // 拿不到焦点、弹不出键盘、长按也粘贴不了。只留 NOT_TOUCH_MODAL，
            // 面板外的触摸仍还给 X，面板开着时 X 也能滑。
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // 键盘弹起时把面板顶上去，别盖住输入框和生成按钮。
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val view = ComposeView(this)
        attachOwners(view)
        view.setContent {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    // 顶部固定栏：关闭按钮永远在上面，不用滑到底才能关。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("AI 回复助手（本地 / 离线）", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { removePanel() }) { Text("关闭") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text("当前帖子", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(6.dp))
                        // 帖子放进框框里：带边框、浅紫底、圆角、内边距，看起来整洁，
                        // 不再是一行裸文字贴在面板上。
                        // 框里最多显示 300 字预览，超过 300 字的帖子显示前 300 字
                        // 加省略号，全文仍用于生成；生成时原文最多取前 2000 字。
                        // 面板整体最高 420dp、可上下滑，所以长帖子不会把面板撑出屏幕。
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color(0xFFB39DDB), RoundedCornerShape(12.dp))
                                .background(Color(0xFFF5F3FF), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                if (postText.isBlank()) "（暂无帖子内容：先在 X 里复制帖子，再点悬浮球）"
                                else if (postText.length > 300) postText.take(300) + "…（全文已用于生成）"
                                else postText,
                                fontSize = 13.sp
                            )
                        }
                        // 通用框选证据：显示实际裁剪图，用户能一眼看到框住的是不是要认的内容，
                        // 是 X 帖子、ChatGPT 聊天还是浏览器文章，而不是只信一行文字结果。
                        val cropImg = boxImageBitmap
                        if (cropImg != null) {
                            Spacer(Modifier.height(8.dp))
                            Text("框选图片：", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Image(
                                bitmap = cropImg.asImageBitmap(),
                                contentDescription = "框选裁剪图片",
                                modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)
                            )
                        }
                        // “用粘贴的内容生成”按钮已直接删掉，连同手动粘贴区一起删掉：
                        // 流程就是复制内容、点小球，已经自动粘贴好、已经开始生成，
                        // 就这样，不需要再手动贴一次、再点一次生成。
                        // 自动读不到时，面板会提示先复制再点球，关面板、重新复制、
                        // 再点球就是新的一次提交，不再留一个多余的生成按钮。
                        Text("风格", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Row {
                            listOf("自然", "幽默", "简短", "友好").forEach { s ->
                                val selected = styleState == s
                                if (selected) {
                                    // 已选中的风格永远保持紫色、不变灰：之前生成中把它置灰，
                                    // 看起来就是点了变灰、很难看。重复提交的防线在 setStyle
                                    // 代码里（生成中再点已选风格直接返回），不需要靠变灰来挡。
                                    Button(onClick = { setStyle(s) }, modifier = Modifier.padding(end = 8.dp)) { Text(s) }
                                } else {
                                    // 正在生成时，点另一个风格是换风格重来，要能点：
                                    // 它会先取消当前那一通，再用新风格提交一次，
                                    // 不是在旧的上面再叠加一通。
                                    OutlinedButton(onClick = { setStyle(s) }, modifier = Modifier.padding(end = 8.dp)) { Text(s) }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("AI 回复", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(statusText, fontSize = 12.sp, color = Color.Gray)
                        Spacer(Modifier.height(6.dp))
                        // 不再用长按选中容器：它会和面板滑动抢手势，是滑不动、卡顿的原因之一。
                        // 复制统一走下面的“复制”按钮。
                        Text(if (replyText.isBlank()) "" else replyText, fontSize = 16.sp)
                        Spacer(Modifier.height(12.dp))
                        Row {
                            Button(onClick = { copyReply() }, modifier = Modifier.padding(end = 8.dp)) { Text("复制") }
                            // 重新生成只对当前原文再生成一次，不重读剪贴板，
                            // 避免面板开着时剪贴板已被别的内容覆盖。
                            OutlinedButton(onClick = { generate() }, modifier = Modifier.padding(end = 8.dp)) { Text("重新生成") }
                            // 取消（2026-11-02 用户要求）：生成进行中（“生成中…”或
                            // “正在思考…”）时才出现。点取消只取消这次生成：
                            // 代号先 +1，旧流后续块和最终结果因代号不符一律
                            // 被挡、写不回面板；再断 HTTP、取消协程，界面
                            // 立刻恢复空闲，不用等 60 秒超时。不改风格、
                            // 不改思考模式、不停服务、不删模型。
                            if (isGenerating) {
                                OutlinedButton(onClick = { cancelGeneration() }, modifier = Modifier.padding(end = 8.dp)) { Text("取消") }
                            }
                            // 面板整洁：点球已经读过剪贴板了，“读取剪贴板”就是多余的，
                            // 留着只会让人手滑再提交一次，已删掉。新帖子请关面板、
                            // 重新复制、再点球，那才是新的一次提交。
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("不会自动发送，请自己复制粘贴。点悬浮球可关闭面板。", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }
        }
        panelView = view
        wm.addView(view, params)
    }

    private fun setStyle(s: String) {
        // 换风格重来的逻辑，分三种，不能混成一种：
        // 1. 没在生成时：点自然就提交一次自然，点幽默就提交一次幽默，
        //    重复点自然也是再提交一次自然，原文不变、不重读剪贴板。
        // 2. 正在生成时，点已选中的风格：是重复提交，直接忽略，
        //    按钮本身也置灰点不动。
        // 3. 正在生成时，点另一个风格：是用户明确要换风格重来，
        //    不是重复提交。先把当前那一通取消（断 HTTP、服务端释锁，
        //    旧的不会继续跑完），再用新风格提交一次，同一原文。
        //    这是替换，不是叠加：任何时刻服务端只跑一个。
        if (isGenerating && generateJob?.isActive == true && s == styleState) return
        if (isGenerating && generateJob?.isActive != true) isGenerating = false
        if (postText.isBlank()) {
            statusText = "先在 X 里复制帖子，再点悬浮球"
            return
        }
        if (isGenerating) {
            generateJob?.cancel()
            ApiClient.cancelActive()
            isGenerating = false
        }
        styleState = s
        store.style = s
        if (postText.isNotBlank()) generate()
    }
    private fun cancelGeneration() {
        // 取消这次生成：代号先 +1 是关键——旧流协程醒来后先查代号，
        // 代号不符就直接 return，晚到的块和最终结果都写不回面板；
        // 再断 HTTP、取消协程、恢复空闲。只取消这次，不改风格、
        // 不改思考模式、不停服务、不删模型。点“重新生成”或换风格
        // 仍可正常提交新的一通。
        generateSeq += 1
        generateJob?.cancel()
        ApiClient.cancelGeneration()
        isGenerating = false
        statusText = "已取消生成，点重新生成可再试一次"
    }

    private fun generate() {
        val text = postText
        if (text.isBlank()) {
            statusText = "先在 X 里复制帖子，再点悬浮球"
            return
        }
        // 根本逻辑：提交一次、生成一次、传输一次。
        // 生成中，任何新的生成入口都直接返回，不提交、不取消重发、
        // 不用序号遮掩。取消重发表面上只显示一次，实际上旧请求
        // 已经在服务端占锁生成，新请求还是要等它，速度还是相加。
        // 只有等当前这一通完成，或关面板真正取消后，下一次点击
        // 才提交新的一通。唯一例外是换风格重来：setStyle 会先取消
        // 当前那一通、把 isGenerating 清掉，再调 generate() 提交新风格，
        // 那是替换一通，不是叠加一通。序号守卫只留作关面板竞态的安全网，
        // 不是用来遮掩多次提交的。
        // 关键修复：不能只看 isGenerating 标志就挡住——万一上一次的
        // 任务已经结束但标志没清掉（比如被取消后没走到清标志那行），
        // 之后点球、手动粘贴、点风格都会静默没反应，看起来就是“啥用没有”。
        // 只有任务真的还在跑时才挡；任务已经不在跑了，就把标志清掉、正常提交。
        if (isGenerating && generateJob?.isActive == true) {
            statusText = "正在生成，等一下"
            return
        }
        if (isGenerating) isGenerating = false
        generateSeq += 1
        val mySeq = generateSeq
        isGenerating = true
        // 思考/自动模式一开始就明说在思考：旧版面板一直只写“生成中…”、
        // 思考阶段又一个字都不出，用户看到的就是“根本没反应”。
        // 思考关时仍写“生成中…”，行为原样不动。
        statusText = if (ApiClient.reasoningMode(this@FloatingService) == "off") "生成中…"
            else "正在思考…（思考模式已开，模型先想一遍再回答，可能会慢，请稍等）"
        replyText = ""
        generateJob = scope.launch {
            // 流式一次：字一出来就先显示，不用等全文。
            // 流式失败且一字没出时，不再自动发第二通非流式兜底——
            // 那就是第二次提交、第二次生成、第二次传输，只会更慢。
            // 直接报错，用户等当前结束、再点重新生成，那才是新的一次提交。
            val streamResult = ApiClient.generateStream(this@FloatingService, store.contextTokens, styleState, text, { piece ->
                if (mySeq == generateSeq) {
                    replyText += piece
                    // 第二层防闪烁：ApiClient 流里已经按块去开头 null，
                    // 这里再兜底一次——万一累积文本开头出现 null，
                    // 立刻清掉，绝不让 null 在面板上闪一下再被最终文本覆盖。
                    // 句中 null 不动，只清开头。
                    val cleaned = ApiClient.stripLeadingNull(replyText)
                    if (cleaned != replyText) replyText = cleaned
                }
            }, {
                // 思考回调：模型一开始流式返回思考内容就立刻显示
                // “正在思考…”，而不是等到正文才有动静。
                if (mySeq == generateSeq && replyText.isBlank()) {
                    statusText = "正在思考…（思考模式已开，模型先想一遍再回答，可能会慢，请稍等）"
                }
            })
            if (mySeq != generateSeq) return@launch
            if (streamResult.isSuccess) {
                replyText = streamResult.getOrNull()?.trim() ?: replyText
                statusText = "已生成"
            } else if (replyText.isNotBlank()) {
                statusText = "已生成（部分）"
            } else {
                statusText = "生成失败：${streamResult.exceptionOrNull()?.message ?: "请求失败"}，等一下再点重新生成"
            }
            if (mySeq == generateSeq) isGenerating = false
        }
    }

    private fun ocrFlow(cropRect: Rect? = null) {
        if (cropRect == null) boxFallbackText = ""
        CaptureHub.cropRect = cropRect
        statusText = if (cropRect != null) "正在截图识别框内文字（本地识别，不上传）…" else "正在截图识别（本地识别，不上传）…"
        val intent = Intent(this, CaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        // 超时保护：20 秒还没结果就提示原因，不再一直转。
        // 之前取消录屏、没点开始时 App 收不到任何结果，就会卡在这里。
        scope.launch {
            delay(20000)
            if (statusText.startsWith("正在截图识别")) {
                if (boxFallbackText.length >= 5) {
                    postText = boxFallbackText
                    boxFallbackText = ""
                    statusText = "截图识别超时，改用无障碍结果"
                    generate()
                } else {
                    statusText = "截图识别超时：请确认刚才是否点了允许录屏，没点就重新点截图识别并点开始"
                }
            }
        }
        scope.launch {
            CaptureHub.ocrText.collectLatest { ocr ->
                if (ocr == null) return@collectLatest
                if (ocr.isBlank()) {
                    val err = CaptureHub.lastOcrError
                    if (boxFallbackText.length >= 5) {
                        postText = boxFallbackText
                        boxFallbackText = ""
                        statusText = if (err != null) "识别失败：$err，改用无障碍结果" else "截图没认出，改用无障碍结果"
                        generate()
                    } else if (err != null) {
                        statusText = "识别失败：$err"
                    } else {
                        statusText = "截图识别没有认出文字：框内可能没有文字，或文字太小、太模糊，请把框调大一点、只盖文字，再点确认"
                    }
                } else {
                    postText = ocr.take(4000)
                    boxImageBitmap = CaptureHub.croppedBitmap
                    boxFallbackText = ""
                    CaptureHub.lastOcrError = null
                    statusText = "截图识别完成"
                    generate()
                }
            }
        }
    }

    private fun copyReply() {
        if (replyText.isBlank()) {
            Toast.makeText(this, "还没有可复制的回复", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("reply", replyText))
        Toast.makeText(this, "已复制，去 X 粘贴即可", Toast.LENGTH_SHORT).show()
    }

    private fun pasteFromClipboard() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            val t = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0)?.text?.toString()?.trim() ?: ""
            } else ""
            if (t.isNotEmpty()) {
                pasteText = t
                postText = t
                // 手动粘贴也要一键生成：之前只填框、不生成，还要再点
                // 一次生成按钮，用户以为粘贴完就该生成了，结果就是“没反应”。
                // 现在粘贴完直接提交一次，跟点球自动粘贴、自动生成一样。
                generate()
            } else {
                statusText = "剪贴板是空的：先在 X 里复制帖子，再点这个按钮；或点输入框长按粘贴"
            }
        } catch (e: Exception) {
            statusText = "读不到剪贴板：点输入框长按粘贴（系统粘贴菜单不受这个限制）"
        }
    }

    private fun removePanel() {
        // 关面板就停掉正在跑的生成：否则关了面板、服务端还在跑，
        // 下次点球的新请求又要排队，越用越慢。
        try { generateJob?.cancel() } catch (e: Exception) { }
        try { ApiClient.cancelActive() } catch (e: Exception) { }
        isGenerating = false
        try { panelView?.let { wm.removeView(it) } } catch (e: Exception) {}
        panelView = null
        boxImageBitmap = null
    }

    /**
     * 框选识别：先收起面板，铺一层全屏透明层让用户拖一个框，
     * 松手后只读框内中心点落入的无障碍文字，不读框外评论区。
     * 框太小算取消，回到面板。
     */
    private fun showSelectionOverlay(frozenBitmap: android.graphics.Bitmap? = null) {
        selectionReturnToPanel = (panelView != null)
        removePanel()
        frozenShotBitmap = frozenBitmap
        if (selectionView != null) return
        cropBox = null
        cropScreenW = 0
        cropScreenH = 0
        cropBottomBarH = 0
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        val view = ComposeView(this)
        attachOwners(view)
        view.setContent {
            // 裁剪式框选：像手机相册裁图一样用。
            // 进入就先给一个大框，不用从头画；8 个大手柄随便拖改大小，
            // 按住框里随便拖就是整体移动；松手框留住，不自动退出；
            // 只有点底部“确认”才结束并识别，点“全屏”“重置”“取消”各有各的用。
            // 框选层吃掉全部手势，X 页面在框选时不会滑动、不会返回，
            // 相当于在冻结的一层上操作；手柄在边缘时自动往里缩一点，
            // 但框的边线仍然可以贴到屏幕最边缘。
            val density = LocalDensity.current.density
            val handleVisual = 9f * density
            val handleTouch = 24f * density
            val screenW = cropScreenW
            val screenH = cropScreenH
            val box = cropBox
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        cropScreenW = size.width
                        cropScreenH = size.height
                        if (cropBox == null && size.width > 0 && size.height > 0) {
                            cropBox = defaultCropBox(size.width, size.height)
                        }
                    }
            ) {
                // 冻屏背景：进入框选前截的那张整屏图，框就画在这张图上，
                // 不是活的 X 页面，所以拖框时 X 不会滑动、不会返回。
                val frozen = frozenShotBitmap
                if (frozen != null) {
                    Image(
                        bitmap = frozen.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            // 不再用 detectDragGestures：悬浮球那种直接跟踪手指
                            // 按下到抬起的办法在真机上是通的，框选改成同一套。
                            // 按下那一刻就判手柄/框内移动，不用等滑动阈值；
                            // 手指每动一次就按当前位置重算框，框跟手指走。
                            awaitPointerEventScope {
                                while (true) {
                                    var downId: PointerId? = null
                                    var downPos = Offset.Zero
                                    while (downId == null) {
                                        val downEvent = awaitPointerEvent()
                                        val downChange = downEvent.changes.firstOrNull { !it.previousPressed && it.pressed }
                                        if (downChange != null) {
                                            downId = downChange.id
                                            downPos = downChange.position
                                        }
                                    }
                                    val startBox = cropBox
                                    cropDragStartBox = startBox
                                    cropDragStartPos = downPos
                                    cropDragMode = "NONE"
                                    cropDragHint = ""
                                    if (startBox != null && cropScreenW > 0 && cropScreenH > 0) {
                                        val handles = listOf(
                                            "TL" to Offset(startBox.left.toFloat(), startBox.top.toFloat()),
                                            "TR" to Offset(startBox.right.toFloat(), startBox.top.toFloat()),
                                            "BL" to Offset(startBox.left.toFloat(), startBox.bottom.toFloat()),
                                            "BR" to Offset(startBox.right.toFloat(), startBox.bottom.toFloat()),
                                            "L" to Offset(startBox.left.toFloat(), (startBox.top + startBox.bottom) / 2f),
                                            "R" to Offset(startBox.right.toFloat(), (startBox.top + startBox.bottom) / 2f),
                                            "T" to Offset((startBox.left + startBox.right) / 2f, startBox.top.toFloat()),
                                            "B" to Offset((startBox.left + startBox.right) / 2f, startBox.bottom.toFloat())
                                        )
                                        var best: String? = null
                                        var bestDist = Float.MAX_VALUE
                                        for ((name, raw) in handles) {
                                            val vx = raw.x.coerceIn(handleVisual, cropScreenW - handleVisual)
                                            val vy = raw.y.coerceIn(handleVisual, cropScreenH - handleVisual)
                                            val d = (downPos - Offset(vx, vy)).getDistance()
                                            if (d <= handleTouch && d < bestDist) {
                                                best = name
                                                bestDist = d
                                            }
                                        }
                                        cropDragMode = when {
                                            best != null -> best
                                            startBox.contains(downPos.x.toInt(), downPos.y.toInt()) -> "MOVE"
                                            else -> "NONE"
                                        }
                                        cropDragHint = when (cropDragMode) {
                                            "TL" -> "正在拖：左上角"
                                            "TR" -> "正在拖：右上角"
                                            "BL" -> "正在拖：左下角"
                                            "BR" -> "正在拖：右下角"
                                            "L" -> "正在拖：左边"
                                            "R" -> "正在拖：右边"
                                            "T" -> "正在拖：上边"
                                            "B" -> "正在拖：下边"
                                            "MOVE" -> "正在拖：移动整框"
                                            else -> ""
                                        }
                                    }
                                    var done = false
                                    while (!done) {
                                        val event = awaitPointerEvent()
                                        for (change in event.changes) {
                                            if (change.id != downId) continue
                                            if (!change.pressed && change.previousPressed) {
                                                done = true
                                            } else if (change.pressed) {
                                                val mode = cropDragMode
                                                val sb = cropDragStartBox
                                                if (mode == "NONE" || sb == null) continue
                                                if (change.position == change.previousPosition) continue
                                                change.consume()
                                                val currentPos = change.position
                                                val bw = sb.width()
                                                val bh = sb.height()
                                                val minSize = 80
                                                val newBox = when (mode) {
                                                    "MOVE" -> {
                                                        val dx = (currentPos.x - cropDragStartPos.x).toInt()
                                                        val dy = (currentPos.y - cropDragStartPos.y).toInt()
                                                        val nl = (sb.left + dx).coerceIn(0, cropScreenW - bw)
                                                        val nt = (sb.top + dy).coerceIn(0, cropMaxBottom() - bh)
                                                        Rect(nl, nt, nl + bw, nt + bh)
                                                    }
                                                    "TL" -> Rect(currentPos.x.toInt().coerceIn(0, sb.right - minSize), currentPos.y.toInt().coerceIn(0, sb.bottom - minSize), sb.right, sb.bottom)
                                                    "TR" -> Rect(sb.left, currentPos.y.toInt().coerceIn(0, sb.bottom - minSize), currentPos.x.toInt().coerceIn(sb.left + minSize, cropScreenW), sb.bottom)
                                                    "BL" -> Rect(currentPos.x.toInt().coerceIn(0, sb.right - minSize), sb.top, sb.right, currentPos.y.toInt().coerceIn(sb.top + minSize, cropMaxBottom()))
                                                    "BR" -> Rect(sb.left, sb.top, currentPos.x.toInt().coerceIn(sb.left + minSize, cropScreenW), currentPos.y.toInt().coerceIn(sb.top + minSize, cropMaxBottom()))
                                                    "L" -> Rect(currentPos.x.toInt().coerceIn(0, sb.right - minSize), sb.top, sb.right, sb.bottom)
                                                    "R" -> Rect(sb.left, sb.top, currentPos.x.toInt().coerceIn(sb.left + minSize, cropScreenW), sb.bottom)
                                                    "T" -> Rect(sb.left, currentPos.y.toInt().coerceIn(0, sb.bottom - minSize), sb.right, sb.bottom)
                                                    "B" -> Rect(sb.left, sb.top, sb.right, currentPos.y.toInt().coerceIn(sb.top + minSize, cropMaxBottom()))
                                                    else -> sb
                                                }
                                                cropBox = newBox
                                            }
                                        }
                                    }
                                    cropDragMode = "NONE"
                                    cropDragHint = ""
                                }
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val b = box ?: return@Canvas
                        val dim = Color.Black.copy(alpha = 0.5f)
                        drawRect(dim, Offset(0f, 0f), Size(size.width, b.top.toFloat()))
                        drawRect(dim, Offset(0f, b.bottom.toFloat()), Size(size.width, size.height - b.bottom))
                        drawRect(dim, Offset(0f, b.top.toFloat()), Size(b.left.toFloat(), (b.bottom - b.top).toFloat()))
                        drawRect(dim, Offset(b.right.toFloat(), b.top.toFloat()), Size(size.width - b.right, (b.bottom - b.top).toFloat()))
                        drawRect(
                            color = Color(0xFF7C4DFF),
                            topLeft = Offset(b.left.toFloat(), b.top.toFloat()),
                            size = Size((b.right - b.left).toFloat(), (b.bottom - b.top).toFloat()),
                            style = Stroke(width = 6f)
                        )
                        val raws = listOf(
                            Offset(b.left.toFloat(), b.top.toFloat()),
                            Offset(b.right.toFloat(), b.top.toFloat()),
                            Offset(b.left.toFloat(), b.bottom.toFloat()),
                            Offset(b.right.toFloat(), b.bottom.toFloat()),
                            Offset(b.left.toFloat(), (b.top + b.bottom) / 2f),
                            Offset(b.right.toFloat(), (b.top + b.bottom) / 2f),
                            Offset((b.left + b.right) / 2f, b.top.toFloat()),
                            Offset((b.left + b.right) / 2f, b.bottom.toFloat())
                        )
                        for (raw in raws) {
                            val vx = raw.x.coerceIn(handleVisual, size.width - handleVisual)
                            val vy = raw.y.coerceIn(handleVisual, size.height - handleVisual)
                            drawCircle(Color.White, radius = handleVisual, center = Offset(vx, vy))
                            drawCircle(Color(0xFF7C4DFF), radius = handleVisual, center = Offset(vx, vy), style = Stroke(width = 4f))
                        }
                    }
                }
                if (cropDragHint.isNotEmpty()) {
                    Text(
                        cropDragHint,
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.TopCenter).background(Color.Black.copy(alpha = 0.7f)).padding(8.dp)
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .onSizeChanged { cropBottomBarH = it.height }
                        .background(Color.Black.copy(alpha = 0.85f))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { if (cropScreenW > 0 && cropMaxBottom() > 0) cropBox = Rect(0, 0, cropScreenW, cropMaxBottom()) },
                            modifier = Modifier.weight(1f).padding(end = 4.dp)
                        ) { Text("全屏") }
                        OutlinedButton(
                            onClick = { if (cropScreenW > 0 && cropScreenH > 0) cropBox = defaultCropBox(cropScreenW, cropScreenH) },
                            modifier = Modifier.weight(1f).padding(end = 4.dp)
                        ) { Text("重置") }
                        OutlinedButton(
                            onClick = { cancelSelection() },
                            modifier = Modifier.weight(1f).padding(end = 4.dp)
                        ) { Text("取消") }
                        Button(
                            onClick = { cropBox?.let { finishSelection(it) } },
                            modifier = Modifier.weight(1f)
                        ) { Text("确认") }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 粘贴入口：点球出框后，不想框选就点这个，直接进面板粘贴，
                    // 不用先取消再找面板。面板里有输入框和“用粘贴的内容生成”。
                    Button(
                        onClick = { removeSelection(); showPanel() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("粘贴帖子生成（不用框选）") }
                }
            }
        }
        selectionView = view
        try {
            wm.addView(view, params)
            // 边缘返回排除：左右边缘各 120px 不再触发系统返回手势。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                view.post {
                    try {
                        view.systemGestureExclusionRects = listOf(
                            Rect(0, 0, 120, view.height),
                            Rect(view.width - 120, 0, view.width, view.height)
                        )
                    } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {
            selectionView = null
            Toast.makeText(this, "框选打开失败：${e.message}", Toast.LENGTH_SHORT).show()
            showPanel()
        }
    }

    private fun cancelSelection() {
        removeSelection()
        // 从面板里进框选、取消了就回面板；从球直接进框选、取消了就直接关，不出面板。
        if (selectionReturnToPanel) {
            statusText = "已取消框选"
            showPanel()
        }
    }

    private fun finishSelection(rect: Rect) {
        // 用户要求：确认也不弹录屏许可。X 帖子文字走无障碍直接读，
        // 不截图、不启动 CaptureActivity、不切任务、不弹录屏许可。
        // 只有无障碍实在读不到时，才在面板里明说，并让用户自己
        // 点面板里的“截图识别”再走截图路线（那一步才会弹许可，
        // 是用户主动选的，不是确认默认带的）。
        val svc = MuseAccessibilityService.instance
        val accessText = svc?.captureRegion(rect) ?: ""
        boxFallbackText = accessText
        boxImageBitmap = null
        removeSelection()
        postText = ""
        showPanel()
        if (svc == null) {
            statusText = "无障碍未开启：App 读不到任何文字，请回 App 主页开无障碍（侧载应用先点应用信息右上角三个点→允许受限设置，再开），开了再框选"
        } else if (accessText.length >= 5) {
            postText = accessText.take(4000)
            statusText = "框选识别完成"
            generate()
        } else {
            statusText = "无障碍已开，但框内没读到文字：请把框调大一点、只盖正文再确认；如果还是读不到，再点面板里的截图识别（那会弹录屏许可）"
        }
    }

    private fun removeSelection() {
        try { selectionView?.let { wm.removeView(it) } } catch (e: Exception) {}
        selectionView = null
        cropBox = null
        cropScreenW = 0
        cropScreenH = 0
        cropBottomBarH = 0
        frozenShotBitmap = null
    }

    private fun removeViews() {
        removePanel()
        removeSelection()
        try { ballView?.let { wm.removeView(it) } } catch (e: Exception) {}
        ballView = null
    }

    override fun onDestroy() {
        removeViews()
        // 悬浮球关闭时卸载模型，释放内存；10 分钟闲置也会自动卸载（见 LocalServer）。
        try { LocalServer.stop() } catch (_: Exception) {}
        lifecycleOwner.onPause()
        lifecycleOwner.onStop()
        lifecycleOwner.onDestroy()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/**
 * Service 里没有 Activity 的 Lifecycle，ComposeView 必须手动挂一个，
 * 否则点悬浮球展开面板时可能因找不到 LifecycleOwner 而闪退。
 */
private class ServiceLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun onCreate() {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onStart() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    fun onResume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onPause() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    fun onStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
