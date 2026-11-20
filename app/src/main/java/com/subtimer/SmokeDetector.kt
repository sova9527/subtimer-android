package com.subtimer

/**
 * 替身检测器：通过「画面中突然出现大量高亮度、低饱和度像素」来识别替身术的白烟特效。
 * 适配不同机型/分辨率：全部按比例统计，不依赖固定坐标。
 */
class SmokeDetector {

    private var ema = -1.0
    private var lastEventMs = 0L
    var sensitivity = 2 // 0..4，越大越灵敏

    /** 统计角色活动区域（去掉顶部血条、底部技能栏）里「白色烟雾」像素的占比 */
    fun whiteRatio(pixels: IntArray, w: Int, h: Int): Double {
        val x0 = (w * 0.08).toInt().coerceAtLeast(0)
        val x1 = (w * 0.92).toInt().coerceAtMost(w)
        val y0 = (h * 0.12).toInt().coerceAtLeast(0)
        val y1 = (h * 0.82).toInt().coerceAtMost(h)
        var count = 0
        var total = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val c = pixels[y * w + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val lum = (r + g + b) / 3
                val mx = maxOf(r, maxOf(g, b))
                val mn = minOf(r, minOf(g, b))
                if (lum >= 185 && mx - mn <= 40) count++
                total++
                x += 2
            }
            y += 2
        }
        return if (total == 0) 0.0 else count.toDouble() / total
    }

    /** 判定本帧是否为一个新的替身事件 */
    fun isEvent(ratio: Double): Boolean {
        val now = System.currentTimeMillis()
        if (ema < 0.0) {
            ema = ratio
            return false
        }
        val delta = 0.034 - sensitivity * 0.006 // 灵敏度 2 -> 0.022
        val minRatio = 0.075 - sensitivity * 0.010 // 灵敏度 2 -> 0.055
        val hit = ratio > minRatio && (ratio - ema) > delta && ratio < 0.60 &&
                (now - lastEventMs) > 2200
        ema = ema * 0.88 + ratio * 0.12
        if (hit) {
            lastEventMs = now
            return true
        }
        return false
    }

    fun reset() {
        ema = -1.0
        lastEventMs = 0L
    }
}
