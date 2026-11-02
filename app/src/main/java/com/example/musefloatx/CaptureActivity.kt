package com.example.musefloatx

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

class CaptureActivity : Activity() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var done = false
    private var freezeMode = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CaptureHub.clear()
        CaptureHub.croppedBitmap = null
        CaptureHub.lastOcrError = null
        CaptureHub.frozenBitmap = null
        CaptureHub.freezeCancelled = false
        CaptureHub.freezeTimedOut = false
        freezeMode = intent?.getBooleanExtra("freeze", false) == true
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), 1001)
        // 注意：这里不能再放 10 秒超时。点球后系统要先弹两步许可
        // （下一步 -> 选 X -> 开始），用户慢慢点很容易超过 10 秒，
        // 超时一到 Activity 自己就关了，之后就算点了开始也拿不到图，
        // 框要么不出，要么很久以后才迟到别的 App 上。超时改到
        // 拿到许可之后才开始算，见 onActivityResult。
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1001 || resultCode != RESULT_OK || data == null) {
            // 取消录屏也要回传一个空结果 + 具体原因，否则面板会一直停在“正在截图识别”。
            CaptureHub.lastOcrError = "用户取消了录屏许可：没点开始，App 拿不到截图"
            if (freezeMode) CaptureHub.freezeCancelled = true
            CaptureHub.post("")
            finishIfNotDone()
            return
        }
        // 许可拿到之后才开始算超时：8 秒内没拿到第一帧才算超时，
        // 这样用户在许可对话框里花多久都不影响。
        handler.postDelayed({
            if (!done) {
                if (freezeMode) CaptureHub.freezeTimedOut = true
                else CaptureHub.lastOcrError = "截图超时：8 秒内没拿到截图，请重试并点开始"
                CaptureHub.post("")
            }
            finishIfNotDone()
        }, 8000)
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(resultCode, data)
            val dm = resources.displayMetrics
            val width = dm.widthPixels
            val height = dm.heightPixels
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            reader.setOnImageAvailableListener({ r ->
                try {
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val bitmap = imageToBitmap(image)
                    image.close()
                    if (bitmap != null) recognize(bitmap) else finishIfNotDone()
                } catch (e: Exception) {
                    finishIfNotDone()
                }
            }, handler)
            virtualDisplay = projection?.createVirtualDisplay(
                "MuseFloatXCapture", width, height, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
        } catch (e: Exception) {
            finishIfNotDone()
        }
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        } catch (e: Exception) {
            null
        }
    }

    private fun recognize(bitmap: Bitmap) {
        if (freezeMode) {
            CaptureHub.frozenBitmap = bitmap
            finishIfNotDone()
            return
        }
        try {
            // 框选截图：只认框内像素，不认框外评论区；框外像素裁掉，
            // 哪怕无障碍树里根本没有主帖文字，像素里也能认出来。
            val crop = CaptureHub.cropRect
            CaptureHub.cropRect = null
            val target = if (crop != null) {
                val left = crop.left.coerceIn(0, bitmap.width - 1)
                val top = crop.top.coerceIn(0, bitmap.height - 1)
                val w = minOf(crop.width(), bitmap.width - left)
                val h = minOf(crop.height(), bitmap.height - top)
                if (w > 0 && h > 0) Bitmap.createBitmap(bitmap, left, top, w, h) else bitmap
            } else bitmap
            // 把实际裁剪图留给面板显示，用户能一眼看到框住的是不是要认的内容。
            CaptureHub.croppedBitmap = target
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            recognizer.process(InputImage.fromBitmap(target, 0))
                .addOnSuccessListener { result ->
                    CaptureHub.lastOcrError = null
                    CaptureHub.post(result.text ?: "")
                    finishIfNotDone()
                }
                .addOnFailureListener { e ->
                    CaptureHub.lastOcrError = "本地 OCR 识别失败：${e.message ?: "未知错误"}"
                    CaptureHub.post("")
                    finishIfNotDone()
                }
        } catch (e: Exception) {
            finishIfNotDone()
        }
    }

    private fun finishIfNotDone() {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)
        try { virtualDisplay?.release() } catch (e: Exception) {}
        try { imageReader?.close() } catch (e: Exception) {}
        try { projection?.stop() } catch (e: Exception) {}
        finish()
    }

    override fun onDestroy() {
        finishIfNotDone()
        super.onDestroy()
    }
}
