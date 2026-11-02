package com.example.musefloatx

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 只放内存的屏幕快照，不写磁盘。 */
object ScreenSnapshotHub {
    @Volatile var lastText: String = ""
    @Volatile var lastPackage: String = ""
    @Volatile var lastTimeMs: Long = 0L

    fun update(text: String, pkg: String) {
        lastText = text
        lastPackage = pkg
        lastTimeMs = System.currentTimeMillis()
    }

    fun freshText(): String? {
        if (lastText.isBlank()) return null
        if (System.currentTimeMillis() - lastTimeMs > 30_000) return null
        return lastText
    }
}

/** 截图 OCR 结果回传通道，只放内存。 */
object CaptureHub {
    private val _ocrText = MutableStateFlow<String?>(null)
    val ocrText: StateFlow<String?> = _ocrText
    // 框选截图时的裁剪框（屏幕像素坐标），CaptureActivity 认完后清掉。
    @Volatile var cropRect: android.graphics.Rect? = null
    // 进入框选前先冻住的整屏截图，框选层就画在这张图上，X 页面不会动。
    @Volatile var frozenBitmap: android.graphics.Bitmap? = null
    @Volatile var freezeCancelled: Boolean = false
    @Volatile var freezeTimedOut: Boolean = false
    // 框选完成后要给用户看的实际裁剪图（像素证据），面板里直接显示。
    @Volatile var croppedBitmap: android.graphics.Bitmap? = null
    // 识别失败的具体原因，不允许静默失败：取消许可、超时、OCR 失败等
    // 都要写清原因，面板里原样显示。
    @Volatile var lastOcrError: String? = null
    fun post(text: String) { _ocrText.value = text }
    fun clear() { _ocrText.value = null }
}
