package com.tabdraw.native_client

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*

/**
 * SettingsOverlayView — 深紫色系科技感設定面板（精緻緊湊卡片版）
 * 寬度限定為 360dp，置於右上角，不遮擋核心手繪畫布區域
 */
class SettingsOverlayView(
    private val activity: Activity,
    private val transmitter: UdpTransmitter
) : LinearLayout(activity) {

    companion object {
        const val PREFS_NAME = "tabdraw_settings"
        const val KEY_AUTO_SLEEP = "pref_auto_sleep"
    }

    private val ipInput: EditText
    private val portInput: EditText
    private val statusIndicator: TextView
    private val autoSleepCheckBox: CheckBox
    private val bodyLayout: LinearLayout

    var onPanelVisibilityChanged: ((Boolean) -> Unit)? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.END
        elevation = 25f

        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 預設是開 (true)：允許平板跟隨系統自動休眠
        val initialAutoSleep = prefs.getBoolean(KEY_AUTO_SLEEP, true)
        applyScreenLockPolicy(initialAutoSleep)

        // ── 面板主體卡片（寬度鎖定 360dp，精緻適中） ────────────────
        val panelWidth = dp(360)
        bodyLayout = LinearLayout(activity).apply {
            orientation = VERTICAL
            visibility = View.GONE
            layoutParams = LayoutParams(panelWidth, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
            }
            setPadding(dp(18), dp(16), dp(18), dp(18))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5100B1E")) // 96% 深紫黑背景
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1.5f), Color.parseColor("#7C3AED")) // 亮霓虹紫邊框
            }
        }

        val titleView = TextView(activity).apply {
            text = "⚙ TABDRAW SETTINGS"
            setTextColor(Color.parseColor("#FAF5FF"))
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }

        statusIndicator = TextView(activity).apply {
            text = getStatusDescription()
            setTextColor(Color.parseColor("#38BDF8")) // 科技天青色
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(10))
        }

        // ── 螢幕自動鎖定（休眠）開關 ────────────────────────────────
        val screenLockSection = LinearLayout(activity).apply {
            orientation = VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }

        autoSleepCheckBox = CheckBox(activity).apply {
            text = "允許平板自動鎖定螢幕 (休眠)"
            isChecked = initialAutoSleep
            setTextColor(Color.parseColor("#E9D5FF"))
            textSize = 11.5f
            typeface = Typeface.create(Typeface.DEFAULT, FontStyleBold())
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(KEY_AUTO_SLEEP, isChecked).apply()
                applyScreenLockPolicy(isChecked)
                val tip = if (isChecked) "已啟用：平板將隨系統定時休眠" else "已關閉：保持螢幕常亮不休眠"
                Toast.makeText(context, tip, Toast.LENGTH_SHORT).show()
            }
        }

        val autoSleepSubText = TextView(activity).apply {
            text = "• 開：跟隨系統定時鎖屏\n• 關：繪圖期間保持螢幕常亮"
            setTextColor(Color.parseColor("#9CA3AF"))
            textSize = 9.5f
            setPadding(dp(28), 0, 0, 0)
        }

        screenLockSection.addView(autoSleepCheckBox)
        screenLockSection.addView(autoSleepSubText)

        // ── 分隔線 ────────────────────────────────────────────────
        val divider = View(activity).apply {
            setBackgroundColor(Color.parseColor("#2E1B4E"))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
                setMargins(0, dp(4), 0, dp(8))
            }
        }

        // ── IP 輸入區 ─────────────────────────────────────────────
        val ipLabel = TextView(activity).apply {
            text = "TARGET IP (電腦主機位址)"
            setTextColor(Color.parseColor("#C084FC"))
            textSize = 10.5f
            setPadding(dp(2), dp(2), dp(2), dp(3))
        }

        ipInput = EditText(activity).apply {
            setText(transmitter.targetIp)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6B7280"))
            hint = "192.168.x.x"
            inputType = InputType.TYPE_CLASS_PHONE
            textSize = 13f
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#251B42"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#581C87"))
            }
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }

        // ── Port 輸入區 ───────────────────────────────────────────
        val portLabel = TextView(activity).apply {
            text = "UDP PORT"
            setTextColor(Color.parseColor("#C084FC"))
            textSize = 10.5f
            setPadding(dp(2), dp(8), dp(2), dp(3))
        }

        portInput = EditText(activity).apply {
            setText(transmitter.targetPort.toString())
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 13f
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#251B42"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#581C87"))
            }
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }

        // ── 套用連線按鈕 ──────────────────────────────────────────
        val applyBtn = Button(activity).apply {
            text = "✦ APPLY CONNECT (套用連線)"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#7C3AED")) // 科技亮紫
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setOnClickListener { applySettings() }
        }

        val applyBtnParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(12)
        }

        // ── 收合關閉面板按鈕 ──────────────────────────────────────
        val closeBtn = Button(activity).apply {
            text = "✕ 收合面板 (Close)"
            setTextColor(Color.parseColor("#C084FC"))
            textSize = 11f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1B122C"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#4C1D95"))
            }
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { togglePanel(false) }
        }

        val closeBtnParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(6)
        }

        // 組裝 body
        bodyLayout.addView(titleView)
        bodyLayout.addView(statusIndicator)
        bodyLayout.addView(screenLockSection)
        bodyLayout.addView(divider)
        bodyLayout.addView(ipLabel)
        bodyLayout.addView(ipInput)
        bodyLayout.addView(portLabel)
        bodyLayout.addView(portInput)
        bodyLayout.addView(applyBtn, applyBtnParams)
        bodyLayout.addView(closeBtn, closeBtnParams)

        addView(bodyLayout)
    }

    private fun applyScreenLockPolicy(allowAutoSleep: Boolean) {
        activity.runOnUiThread {
            if (allowAutoSleep) {
                // 允許自動休眠（清除保持常亮 Flag）
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                // 關閉自動休眠（保持螢幕常亮）
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    fun togglePanel(forceVisible: Boolean? = null) {
        val willBeVisible = forceVisible ?: (bodyLayout.visibility != View.VISIBLE)
        if (willBeVisible) {
            bodyLayout.visibility = View.VISIBLE
            statusIndicator.text = getStatusDescription()
            ipInput.setText(transmitter.targetIp)
            portInput.setText(transmitter.targetPort.toString())
        } else {
            bodyLayout.visibility = View.GONE
        }
        onPanelVisibilityChanged?.invoke(willBeVisible)
    }

    val isPanelOpen: Boolean
        get() = bodyLayout.visibility == View.VISIBLE

    fun refreshStatus() {
        if (bodyLayout.visibility == View.VISIBLE) {
            statusIndicator.text = getStatusDescription()
        }
    }

    private fun getStatusDescription(): String {
        return if (transmitter.isConnected) {
            "⚡ 已連線主機\nHOST: ${transmitter.targetIp}:${transmitter.targetPort}\nMONITOR: ${transmitter.hostWidth}x${transmitter.hostHeight}"
        } else {
            "● 搜尋主機中 (待命狀態)\nHOST: ${transmitter.targetIp}:${transmitter.targetPort}"
        }
    }

    private fun applySettings() {
        val ip   = ipInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull() ?: UdpTransmitter.DEFAULT_PORT
        if (ip.isNotEmpty()) {
            transmitter.updateTarget(ip, port)
            transmitter.autoDiscovered = false
            statusIndicator.text = getStatusDescription()
            Toast.makeText(context, "✓ Linked to $ip:$port", Toast.LENGTH_SHORT).show()
            togglePanel(false) // 套用後自動收合
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        super.onTouchEvent(event)
        return true
    }

    private fun FontStyleBold(): Int = Typeface.BOLD

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
