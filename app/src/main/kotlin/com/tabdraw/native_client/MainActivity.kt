package com.tabdraw.native_client

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * MainActivity:
 * - 頂部控制列（非感應區）：專門顯示連線狀態與提供設定選單按鈕
 * - 下方手寫感應畫布：自動適配電腦螢幕長寬比 (Aspect Ratio Matching)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var canvasView: StylusCanvasView
    private lateinit var udpTransmitter: UdpTransmitter
    private lateinit var settingsOverlay: SettingsOverlayView
    private lateinit var statusBadgeText: TextView
    private lateinit var panelBtn: TextView

    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRunnable = object : Runnable {
        override fun run() {
            val isOnline = udpTransmitter.isConnected
            val ip = udpTransmitter.targetIp
            val port = udpTransmitter.targetPort
            val fps = udpTransmitter.fps
            val w = udpTransmitter.hostWidth
            val h = udpTransmitter.hostHeight

            if (isOnline) {
                statusBadgeText.text = "⚡ 已連線: $ip:$port ($fps Hz · $w x $h)"
                statusBadgeText.setTextColor(Color.parseColor("#10B981")) // 翡翠綠
            } else {
                statusBadgeText.text = "● 搜尋主機中 (UDP 9998)..."
                statusBadgeText.setTextColor(Color.parseColor("#38BDF8")) // 科技藍
            }
            settingsOverlay.refreshStatus()
            statusHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        udpTransmitter = UdpTransmitter(this)
        canvasView = StylusCanvasView(this, udpTransmitter)
        settingsOverlay = SettingsOverlayView(this, udpTransmitter)

        // ── 垂直根佈局 ────────────────────────────────────────────────
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#07050E"))
        }

        // ── 1. 頂部狀態與操作列（非感應區，獨立純 UI） ────────────────
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#120B24")) // 頂部科技深紫
                setStroke(dp(1), Color.parseColor("#2E1952"))
            }
        }

        val topBarParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(50)
        )

        // 標題 Logo
        val logoText = TextView(this).apply {
            text = "🎨 TabDraw"
            setTextColor(Color.parseColor("#FAF5FF"))
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, 0, dp(14), 0)
        }

        // 連線狀態膠囊標籤
        val statusPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#221538"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#4C1D95"))
            }
        }

        statusBadgeText = TextView(this).apply {
            text = "● 搜尋主機中 (UDP 9998)..."
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 11f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        statusPill.addView(statusBadgeText)

        // 彈性佔位
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1.0f)
        }

        // 設定面板展開按鈕
        panelBtn = TextView(this).apply {
            text = "⚙ TabDraw Panel"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(16), dp(7), dp(16), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#7C3AED")) // 科技紫按鈕
                cornerRadius = dp(16).toFloat()
            }
            setOnClickListener {
                settingsOverlay.togglePanel()
            }
        }

        settingsOverlay.onPanelVisibilityChanged = { isVisible ->
            panelBtn.text = if (isVisible) "✕ 關閉設定 (Close)" else "⚙ TabDraw Panel"
            panelBtn.background = GradientDrawable().apply {
                setColor(Color.parseColor(if (isVisible) "#371A68" else "#7C3AED"))
                cornerRadius = dp(16).toFloat()
                if (isVisible) setStroke(dp(1), Color.parseColor("#A855F7"))
            }
        }

        topBar.addView(logoText)
        topBar.addView(statusPill)
        topBar.addView(spacer)
        topBar.addView(panelBtn)

        // ── 2. 下方畫布區域（手寫感應區） ─────────────────────────────
        val canvasContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        canvasContainer.addView(canvasView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 設定浮層置於畫布上方右上角（限制於右上角浮層）
        val overlayParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(8)
            rightMargin = dp(16)
        }
        canvasContainer.addView(settingsOverlay, overlayParams)

        rootLayout.addView(topBar, topBarParams)
        rootLayout.addView(canvasContainer)

        setContentView(rootLayout)
        enterImmersiveMode()
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        udpTransmitter.start()
        statusHandler.post(statusRunnable)
    }

    override fun onPause() {
        super.onPause()
        udpTransmitter.stop()
        statusHandler.removeCallbacks(statusRunnable)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    private fun enterImmersiveMode() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.let { ctrl ->
                ctrl.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                ctrl.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
