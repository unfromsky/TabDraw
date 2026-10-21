package com.tabdraw.native_client

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import java.util.Locale

/**
 * StylusCanvasView — 硬體手寫筆事件攔截與專業等比例數位板畫布
 *
 * 特性：
 * 1. 精準過濾 TOOL_TYPE_STYLUS，攔截 Hover 與 Touch
 * 2. 自動偵測電腦螢幕解析度與長寬比，在中央建立 1:1 等比例手寫感應區（Aspect Ratio Matching）
 * 3. 瞬態筆跡（筆下筆時即時顯示壓感反饋，提筆即清除，畫布不凌亂）
 * 4. 工程輔助格線與中心十字青色基準軸（限定於有效感應區內）
 * 5. 深紫高端科技調色盤（參考圖 3）
 */
class StylusCanvasView(
    context: Context,
    private val transmitter: UdpTransmitter
) : View(context) {

    // ── 瞬態筆跡 Bitmap ──────────────────────────────────────────
    private var drawBitmap: Bitmap? = null
    private var drawCanvas: Canvas? = null

    // ── 螢幕比例映射 ──────────────────────────────────────────────
    var hostWidth: Int = 1920
        private set
    var hostHeight: Int = 1080
        private set
    private val activeRect = RectF()

    // ── 深紫色系調色盤 ────────────────────────────────────────────
    // 留黑邊區域底色：極黑夜色 #07050E
    private val outerBgPaint = Paint().apply {
        color = Color.parseColor("#07050E")
        style = Paint.Style.FILL
    }

    // 有效繪圖感應區背景：深紫黑 #0E0B18
    private val innerBgPaint = Paint().apply {
        color = Color.parseColor("#0E0B18")
        style = Paint.Style.FILL
    }

    // 感應區霓虹邊框：微光紫 #8B5CF6
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7C3AED")
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    // 四角定位標記與中軸十字：明亮天青色 #38BDF8
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    // 細格線顏色：清晰科技微光紫 #443572（明亮可辨識）
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#443572")
        strokeWidth = 1.3f
        style = Paint.Style.STROKE
    }

    // 瞬態筆跡顏色：高雅亮紫白 #FAF5FF
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FAF5FF")
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    // 懸浮游標顏色：霓虹青藍半透光環
    private val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 56, 189, 248)
        style = Paint.Style.FILL
    }
    private val hoverRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    // 畫布內部標註文字（亮紫清晰易讀）
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C084FC")
        textSize = 24f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private var lastX = 0f
    private var lastY = 0f
    private var hoverX = 0f
    private var hoverY = 0f
    private var isHovering = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true

        hostWidth = transmitter.hostWidth
        hostHeight = transmitter.hostHeight

        transmitter.onHostResolutionChanged = { w, h ->
            post {
                updateHostResolution(w, h)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
    }

    fun updateHostResolution(w: Int, h: Int) {
        if (w > 0 && h > 0) {
            hostWidth = w
            hostHeight = h
            recalcActiveRect()
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            drawBitmap?.recycle()
            drawBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawCanvas = Canvas(drawBitmap!!)
            recalcActiveRect()
        }
    }

    /**
     * 核心演算法：根據電腦端螢幕寬高比，在畫布中央計算出 1:1 最佳感應區
     * 支援邊框安全留白（避免實體螢幕倒角/圓角裁切，保持四周清爽完整）
     */
    private fun recalcActiveRect() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 預留四邊安全邊距，徹底避開平板實體螢幕圓角裁切 (約 20dp)
        val safeMargin = 20f * resources.displayMetrics.density
        val availW = (w - safeMargin * 2f).coerceAtLeast(100f)
        val availH = (h - safeMargin * 2f).coerceAtLeast(100f)

        val targetAspect = hostWidth.toFloat() / hostHeight.toFloat().coerceAtLeast(1f)
        val availAspect = availW / availH

        if (availAspect > targetAspect) {
            // 可用空間較寬：依據可用高度配寬度
            val activeW = availH * targetAspect
            val padX = (w - activeW) / 2f
            val padY = (h - availH) / 2f
            activeRect.set(padX, padY, padX + activeW, padY + availH)
        } else {
            // 可用空間較高：依據可用寬度配高度
            val activeH = availW / targetAspect
            val padX = (w - availW) / 2f
            val padY = (h - activeH) / 2f
            activeRect.set(padX, padY, padX + availW, padY + activeH)
        }
    }

    // ── 事件攔截：懸浮（Hover） ──────────────────────────────────
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val pointerIndex = event.actionIndex
        if (event.getToolType(pointerIndex) != MotionEvent.TOOL_TYPE_STYLUS) {
            return super.onHoverEvent(event)
        }
        processHoverBatch(event)
        return true
    }

    private fun processHoverBatch(event: MotionEvent) {
        val historySize = event.historySize
        val pointerIndex = 0
        val rw = activeRect.width().coerceAtLeast(1f)
        val rh = activeRect.height().coerceAtLeast(1f)

        for (h in 0 until historySize) {
            val x = event.getHistoricalX(pointerIndex, h)
            val y = event.getHistoricalY(pointerIndex, h)
            val pressure = event.getHistoricalPressure(pointerIndex, h)
            val tilt = event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, h)

            // 精準映射到有效工作區 activeRect
            val normX = ((x - activeRect.left) / rw).coerceIn(0f, 1f)
            val normY = ((y - activeRect.top) / rh).coerceIn(0f, 1f)
            transmitter.send(StylusPacket(StylusPacket.FLAG_HOVER, normX, normY, pressure, tilt))
        }

        val x = event.x
        val y = event.y
        val pressure = event.pressure
        val tilt = event.getAxisValue(MotionEvent.AXIS_TILT)
        val normX = ((x - activeRect.left) / rw).coerceIn(0f, 1f)
        val normY = ((y - activeRect.top) / rh).coerceIn(0f, 1f)

        when (event.action) {
            MotionEvent.ACTION_HOVER_ENTER -> {
                isHovering = true
            }
            MotionEvent.ACTION_HOVER_MOVE -> {
                isHovering = true
                hoverX = x
                hoverY = y
                invalidate()
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                isHovering = false
                invalidate()
            }
        }
        transmitter.send(StylusPacket(StylusPacket.FLAG_HOVER, normX, normY, pressure, tilt))
    }

    // ── 事件攔截：觸控（Touch） ──────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pointerIndex = event.actionIndex
        if (event.getToolType(pointerIndex) != MotionEvent.TOOL_TYPE_STYLUS) {
            return true
        }
        processTouchBatch(event)
        return true
    }

    private fun processTouchBatch(event: MotionEvent) {
        val historySize = event.historySize
        val pointerIndex = 0
        val rw = activeRect.width().coerceAtLeast(1f)
        val rh = activeRect.height().coerceAtLeast(1f)

        for (h in 0 until historySize) {
            val x = event.getHistoricalX(pointerIndex, h)
            val y = event.getHistoricalY(pointerIndex, h)
            val pressure = event.getHistoricalPressure(pointerIndex, h)
            val tilt = event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, h)
            val flag = actionToFlag(event.actionMasked)

            val normX = ((x - activeRect.left) / rw).coerceIn(0f, 1f)
            val normY = ((y - activeRect.top) / rh).coerceIn(0f, 1f)
            transmitter.send(StylusPacket(flag, normX, normY, pressure, tilt))
            drawStroke(event.actionMasked, x, y, pressure)
        }

        val x = event.x
        val y = event.y
        val pressure = event.pressure
        val tilt = event.getAxisValue(MotionEvent.AXIS_TILT)
        val flag = actionToFlag(event.actionMasked)
        val normX = ((x - activeRect.left) / rw).coerceIn(0f, 1f)
        val normY = ((y - activeRect.top) / rh).coerceIn(0f, 1f)

        transmitter.send(StylusPacket(flag, normX, normY, pressure, tilt))
        drawStroke(event.actionMasked, x, y, pressure)
        invalidate()
    }

    private fun actionToFlag(action: Int): Byte = when (action) {
        MotionEvent.ACTION_DOWN -> StylusPacket.FLAG_DOWN
        MotionEvent.ACTION_MOVE -> StylusPacket.FLAG_MOVE
        MotionEvent.ACTION_UP   -> StylusPacket.FLAG_UP
        else -> StylusPacket.FLAG_MOVE
    }

    // ── 瞬態筆跡：下筆畫線，提筆自動消隱 ─────────────────────────
    private fun drawStroke(action: Int, x: Float, y: Float, pressure: Float) {
        val c = drawCanvas ?: return
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = x; lastY = y
            }
            MotionEvent.ACTION_MOVE -> {
                strokePaint.strokeWidth = (pressure * 20f).coerceIn(2f, 30f)
                c.drawLine(lastX, lastY, x, y, strokePaint)
                lastX = x; lastY = y
            }
            MotionEvent.ACTION_UP -> {
                // 提筆立即清空筆跡，避免平板被亂畫積累
                c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. 繪製全畫面深空底色
        canvas.drawRect(0f, 0f, w, h, outerBgPaint)

        // 2. 繪製 1:1 有效感應工作區
        canvas.drawRect(activeRect, innerBgPaint)

        // 3. 在感應區內繪製工程格線（步進 60px）
        canvas.save()
        canvas.clipRect(activeRect)

        val gridSize = 60f
        var curX = activeRect.left + (activeRect.width() % gridSize) / 2f
        while (curX < activeRect.right) {
            canvas.drawLine(curX, activeRect.top, curX, activeRect.bottom, gridPaint)
            curX += gridSize
        }
        var curY = activeRect.top + (activeRect.height() % gridSize) / 2f
        while (curY < activeRect.bottom) {
            canvas.drawLine(activeRect.left, curY, activeRect.right, curY, gridPaint)
            curY += gridSize
        }

        // 4. 繪製感應區中心十字天青基準軸
        val midX = activeRect.centerX()
        val midY = activeRect.centerY()
        canvas.drawLine(midX, activeRect.top, midX, activeRect.bottom, axisPaint)
        canvas.drawLine(activeRect.left, midY, activeRect.right, midY, axisPaint)

        // 5. 繪製當前下筆中的瞬態筆跡
        drawBitmap?.let { bmp ->
            canvas.drawBitmap(bmp, 0f, 0f, null)
        }

        canvas.restore()

        // 6. 繪製感應區霓虹外框與四角科技裝飾
        canvas.drawRect(activeRect, borderPaint)
        drawCornerBrackets(canvas, activeRect)

        // 7. 繪製螢幕比例規格標籤
        val aspectVal = hostWidth.toFloat() / hostHeight.toFloat().coerceAtLeast(1f)
        val badgeText = String.format(Locale.US, "🖥 TARGET MONITOR: %dx%d (%.2f:1)", hostWidth, hostHeight, aspectVal)
        canvas.drawText(badgeText, activeRect.left + 16f, activeRect.top + 32f, badgePaint)

        // 8. 懸浮游標圓點與光環
        if (isHovering) {
            canvas.drawCircle(hoverX, hoverY, 6f, hoverPaint)
            canvas.drawCircle(hoverX, hoverY, 16f, hoverRingPaint)
        }
    }

    private fun drawCornerBrackets(canvas: Canvas, r: RectF) {
        val len = 20f
        // 左上
        canvas.drawLine(r.left, r.top, r.left + len, r.top, axisPaint)
        canvas.drawLine(r.left, r.top, r.left, r.top + len, axisPaint)
        // 右上
        canvas.drawLine(r.right - len, r.top, r.right, r.top, axisPaint)
        canvas.drawLine(r.right, r.top, r.right, r.top + len, axisPaint)
        // 左下
        canvas.drawLine(r.left, r.bottom, r.left + len, r.bottom, axisPaint)
        canvas.drawLine(r.left, r.bottom - len, r.left, r.bottom, axisPaint)
        // 右下
        canvas.drawLine(r.right - len, r.bottom, r.right, r.bottom, axisPaint)
        canvas.drawLine(r.right, r.bottom - len, r.right, r.bottom, axisPaint)
    }
}
