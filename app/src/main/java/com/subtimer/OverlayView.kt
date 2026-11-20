package com.subtimer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.ceil

/**
 * 悬浮计时窗：左侧大圈 = 对手替身（自动识别 / 点一下手动记录），右侧小圈 = 我方替身。
 * 拖动可移动位置，长按可清零。
 */
class OverlayView(context: Context) : View(context) {

    var cdMs: Long = 15000L
        set(value) {
            field = value
            invalidate()
        }

    private var oppEnd = 0L
    private var myEnd = 0L
    private var lastOppSetAt = 0L

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (t: Throwable) {
        null
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isAntiAlias = true
        arcPaint.style = Paint.Style.STROKE
        ringPaint.style = Paint.Style.STROKE
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(116f).toInt(), dp(66f).toInt())
    }

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (oppEnd > 0 && now >= oppEnd) {
                oppEnd = 0L
                buzz(longArrayOf(0, 60, 70, 60))
            }
            if (myEnd > 0 && now >= myEnd) myEnd = 0L
            invalidate()
            postDelayed(this, 100)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    /** 自动识别命中时调用 */
    fun triggerOpponent() {
        oppEnd = SystemClock.uptimeMillis() + cdMs
        lastOppSetAt = SystemClock.uptimeMillis()
        buzz(longArrayOf(0, 90))
        invalidate()
    }

    fun resetAll() {
        oppEnd = 0L
        myEnd = 0L
        invalidate()
    }

    private fun buzz(pattern: LongArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        } catch (t: Throwable) {
            // 忽略振动失败
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        drawBubble(
            canvas, dp(32f), dp(33f), dp(26f),
            if (oppEnd > 0) oppEnd - now else 0L,
            Color.parseColor("#7C4DFF"), "敌"
        )
        drawBubble(
            canvas, dp(85f), dp(36f), dp(16f),
            if (myEnd > 0) myEnd - now else 0L,
            Color.parseColor("#FF9800"), "我"
        )
    }

    private fun drawBubble(canvas: Canvas, cx: Float, cy: Float, r: Float, remain: Long, color: Int, label: String) {
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.parseColor("#88000000")
        canvas.drawCircle(cx, cy, r, fillPaint)

        ringPaint.strokeWidth = dp(2.5f)
        ringPaint.color = Color.parseColor("#55FFFFFF")
        canvas.drawCircle(cx, cy, r, ringPaint)

        if (remain > 0) {
            val frac = (remain.toFloat() / cdMs.toFloat()).coerceIn(0f, 1f)
            arcPaint.strokeWidth = dp(3.5f)
            arcPaint.color = color
            canvas.drawArc(rect, -90f, 360f * frac, false, arcPaint)
            textPaint.color = Color.WHITE
            textPaint.textSize = r * 0.85f
            val sec = ceil(remain / 1000.0).toInt().toString()
            val ty = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(sec, cx, ty, textPaint)
        } else {
            ringPaint.strokeWidth = dp(3.5f)
            ringPaint.color = color
            canvas.drawCircle(cx, cy, r, ringPaint)
            textPaint.color = color
            textPaint.textSize = r * 0.8f
            val ty = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, cx, ty, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                downAt = SystemClock.uptimeMillis()
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                if (dragging) {
                    val lp = layoutParams as? WindowManager.LayoutParams
                    if (lp != null) {
                        lp.x += dx.toInt()
                        lp.y += dy.toInt()
                        downX = event.rawX
                        downY = event.rawY
                        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                        try {
                            wm?.updateViewLayout(this, lp)
                        } catch (t: Throwable) {
                            // 忽略窗口更新失败
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val dur = SystemClock.uptimeMillis() - downAt
                if (!dragging) {
                    if (dur >= 600) {
                        resetAll()
                    } else if (event.x < width / 2f) {
                        triggerOpponent()
                    } else {
                        myEnd = SystemClock.uptimeMillis() + cdMs
                        val now = SystemClock.uptimeMillis()
                        if (lastOppSetAt > 0 && now - lastOppSetAt < 2500) {
                            oppEnd = 0L
                            lastOppSetAt = 0L
                        }
                        buzz(longArrayOf(0, 40))
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
