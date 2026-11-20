package com.subtimer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var cdInput: EditText
    private lateinit var sensLabel: TextView
    private lateinit var sensBar: SeekBar

    private val requestCapture = 2001
    private val requestNotif = 2002

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#0E0E12"))
        root.setPadding(dp(20), dp(28), dp(20), dp(28))

        root.addView(title())
        root.addView(hintText())

        statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 13f
            setPadding(0, dp(10), 0, dp(6))
        }
        root.addView(statusView)

        root.addView(button("① 打开悬浮窗权限", "#7C4DFF") {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (t: Throwable) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                } catch (t2: Throwable) {
                    toast("请到系统设置里手动开启悬浮窗权限")
                }
            }
        })
        root.addView(button("② 开始识别（会弹录屏授权）", "#7C4DFF") { startFlow() })
        root.addView(button("停止", "#3A3A44") { stopCapture() })

        root.addView(sectionTitle("替身冷却（秒）"))
        cdInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Prefs.cdSeconds(this@MainActivity).toString())
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E1E26"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(cdInput)
        root.addView(button("保存冷却时间", "#2A2A34") {
            val v = cdInput.text.toString().toIntOrNull()
            if (v == null || v < 5 || v > 40) {
                toast("请输入 5~40 之间的秒数")
            } else {
                Prefs.setCdSeconds(this, v)
                toast("已保存：$v 秒，重新开始识别后生效")
            }
        })

        root.addView(sectionTitle("识别灵敏度"))
        sensLabel = TextView(this).apply {
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 13f
        }
        root.addView(sensLabel)
        sensBar = SeekBar(this).apply {
            max = 4
            progress = Prefs.sensitivity(this@MainActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    sensLabel.text = "当前：" + label(p)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val p = sb?.progress ?: 2
                    Prefs.setSensitivity(this@MainActivity, p)
                    toast("灵敏度已保存，重新开始识别后生效")
                }
            })
        }
        sensLabel.text = "当前：" + label(Prefs.sensitivity(this))
        root.addView(sensBar)

        root.addView(sectionTitle("用法"))
        root.addView(infoText())

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        askNotificationPermission()
    }

    private fun label(p: Int): String = when (p) {
        0 -> "很保守（只认明显的白烟）"
        1 -> "保守"
        2 -> "默认"
        3 -> "灵敏"
        else -> "很灵敏（容易误触发）"
    }

    private fun title(): TextView = TextView(this).apply {
        text = "替身计时器"
        setTextColor(Color.WHITE)
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun hintText(): TextView = TextView(this).apply {
        text = "自动识别对手替身术的白烟特效，在悬浮窗上倒计时。"
        setTextColor(Color.parseColor("#9AA0A6"))
        textSize = 13f
        setPadding(0, dp(6), 0, 0)
    }

    private fun infoText(): TextView = TextView(this).apply {
        text = "1. 先授权悬浮窗，再点开始并允许录屏。\n" +
                "2. 悬浮窗留在屏幕上，切到游戏横屏打决斗场。\n" +
                "3. 大圈=对手替身，识别到白烟自动倒计时；小圈=我方替身。\n" +
                "4. 点大圈可手动记一次，点小圈记我方（刚误记成对手会自动纠正）。\n" +
                "5. 长按悬浮窗清零，拖动可换位置。\n" +
                "6. 识别过多就调低灵敏度，识别不到就调高。"
        setTextColor(Color.parseColor("#B0B4BA"))
        textSize = 13f
        setPadding(0, dp(8), 0, dp(10))
    }

    private fun sectionTitle(t: String): TextView = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 15f
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun button(text: String, colorHex: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.setTextColor(Color.WHITE)
        b.textSize = 15f
        b.setBackgroundColor(Color.parseColor(colorHex))
        b.setOnClickListener { action() }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(8)
        b.layoutParams = lp
        return b
    }

    private fun startFlow() {
        if (!Settings.canDrawOverlays(this)) {
            toast("请先打开悬浮窗权限")
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), requestCapture)
    }

    private fun stopCapture() {
        startService(Intent(this, CaptureService::class.java).apply { action = CaptureService.ACTION_STOP })
        toast("已停止")
        refreshStatus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestCapture) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val i = Intent(this, CaptureService::class.java).apply {
                    action = CaptureService.ACTION_START
                    putExtra(CaptureService.EXTRA_CODE, resultCode)
                    putExtra(CaptureService.EXTRA_DATA, data)
                }
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                toast("已启动，切到游戏开始使用")
            } else {
                toast("未授权录屏，无法识别")
            }
        }
        refreshStatus()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), requestNotif)
            } catch (t: Throwable) {
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        if (!::statusView.isInitialized) return
        val overlayOk = Settings.canDrawOverlays(this)
        val running = CaptureService.running
        statusView.text = "悬浮窗权限：" + (if (overlayOk) "已开启" else "未开启") +
                "　识别：" + (if (running) "运行中" else "未运行")
        statusView.setTextColor(
            if (overlayOk && running) Color.parseColor("#4ADE80") else Color.parseColor("#9AA0A6")
        )
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
