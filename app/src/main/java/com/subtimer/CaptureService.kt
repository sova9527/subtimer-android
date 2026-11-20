package com.subtimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import kotlin.math.max

class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.subtimer.action.START"
        const val ACTION_STOP = "com.subtimer.action.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "subtimer_capture"
        private const val NOTIF_ID = 1101

        @Volatile
        var running: Boolean = false
            private set
    }

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null
    private var overlay: OverlayView? = null
    private var wm: WindowManager? = null

    private val detector = SmokeDetector()
    private val main = Handler(Looper.getMainLooper())
    private var lastAnalyzeAt = 0L
    private var pixels: IntArray? = null
    private var frameCount = 0L
    private var eventCount = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startAsForeground()
                detector.sensitivity = Prefs.sensitivity(this)
                startProjection(code, data)
            }

            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "替身计时器", NotificationManager.IMPORTANCE_LOW)
            ch.description = "保持录屏识别与悬浮计时窗运行"
            nm.createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notif = builder
            .setContentTitle("替身计时器运行中")
            .setContentText("悬浮窗显示对手替身倒计时")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(resultCode, data) ?: run {
            stopSelf()
            return
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)
        running = true
        buildPipeline(proj)
        showOverlay()
    }

    private fun buildPipeline(proj: MediaProjection) {
        releasePipeline()
        val metrics = DisplayMetrics()
        val windowManager = wm ?: return
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val scale = 0.32f
        val capW = max(200, (metrics.widthPixels * scale).toInt())
        val capH = max(200, (metrics.heightPixels * scale).toInt())

        val ht = HandlerThread("capture-worker").also { it.start() }
        workerThread = ht
        val h = Handler(ht.looper)
        worker = h

        val reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3)
        imageReader = reader
        reader.setOnImageAvailableListener({ r -> onFrame(r) }, h)

        virtualDisplay = proj.createVirtualDisplay(
            "subtimer-capture",
            capW, capH, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, h
        )
    }

    private fun releasePipeline() {
        try {
            virtualDisplay?.release()
        } catch (t: Throwable) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (t: Throwable) {
        }
        imageReader = null
        workerThread?.quitSafely()
        workerThread = null
        worker = null
        pixels = null
    }

    private fun onFrame(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (t: Throwable) {
            null
        } ?: return

        try {
            val now = System.currentTimeMillis()
            if (now - lastAnalyzeAt < 120) return
            lastAnalyzeAt = now

            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            if (rowPadding < 0 || pixelStride <= 0) return

            val bmpW = image.width + rowPadding / pixelStride
            val bmpH = image.height
            if (bmpW <= 0 || bmpH <= 0) return

            val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)

            val arr = pixels
            val need = bmpW * bmpH
            val dst = if (arr != null && arr.size == need) arr else IntArray(need)
            pixels = dst
            bitmap.getPixels(dst, 0, bmpW, 0, 0, bmpW, bmpH)
            bitmap.recycle()

            frameCount++
            val ratio = detector.whiteRatio(dst, bmpW, bmpH)
            if (detector.isEvent(ratio)) {
                eventCount++
                main.post { overlay?.triggerOpponent() }
            }
        } catch (t: Throwable) {
            // 单帧失败不影响整体
        } finally {
            try {
                image.close()
            } catch (t: Throwable) {
            }
        }
    }

    private fun showOverlay() {
        if (overlay != null) return
        val w = wm ?: return
        val view = OverlayView(this)
        view.cdMs = Prefs.cdSeconds(this) * 1000L
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 24
        lp.y = 120
        try {
            w.addView(view, lp)
            overlay = view
        } catch (t: Throwable) {
            overlay = null
        }
    }

    private fun removeOverlay() {
        val w = wm ?: return
        overlay?.let {
            try {
                w.removeView(it)
            } catch (t: Throwable) {
            }
        }
        overlay = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val proj = projection ?: return
        main.post { buildPipeline(proj) }
    }

    override fun onDestroy() {
        running = false
        releasePipeline()
        removeOverlay()
        try {
            projection?.stop()
        } catch (t: Throwable) {
        }
        projection = null
        super.onDestroy()
    }
}
