package com.example.blocker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.example.blocker.sensor.Thresholds

class OverlayView(
    context: Context,
    private val onBackPressed: () -> Unit,
    private val onCancel: () -> Unit,
) : View(context) {

    enum class Zone { UPRIGHT, LANDSCAPE, INVERTED }

    companion object {
        private const val STRIP_HEIGHT_DP  = 5f
        private val   STRIP_COLOR          = Color.argb(200, 160, 160, 160)
        private const val COUNTER_GAP_PX   = 16f
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.WHITE
        textSize = 68f
    }

    private val cancelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.WHITE
        textSize  = 22f
        textAlign = Paint.Align.CENTER
    }

    private val counterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.WHITE
        textSize  = textPaint.textSize - 3f
        textAlign = Paint.Align.CENTER
    }

    private val guardPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color    = Color.argb(255, 170, 170, 170)
        textSize = textPaint.textSize
    }

    private var guardMsg   = ""
    private var guardAlpha = 0f

    // ── Strip ─────────────────────────────────────────────────────────────────
    private val stripHeightPx = STRIP_HEIGHT_DP * context.resources.displayMetrics.density
    private val stripPaint    = Paint().apply { color = STRIP_COLOR }
    private var stripVisible  = false

    private var counterText = ""

    private var message        = ""
    private var canvasRotation = 0f
    private var zone           = Zone.UPRIGHT

    private var tiltDeg     = 0f
    private var invertedDeg = 0f
    private var alphaLocked = false

    private val cancelRect = RectF()

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable          = true
        isFocusableInTouchMode = true
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setStripVisible(visible: Boolean) {
        stripVisible = visible
        invalidate()
    }

    fun setGuardMessage(msg: String, alpha: Float) {
        guardMsg   = msg
        guardAlpha = alpha
        invalidate()
    }

    fun setProgress(done: Int, total: Int) {
        counterText = "$done / $total"
        invalidate()
    }

    fun setZone(newZone: Zone, msg: String, rotation: Float = 0f, lockAlpha: Boolean = false) {
        zone           = newZone
        message        = msg
        canvasRotation = rotation
        alphaLocked    = lockAlpha
        invalidate()
    }

    fun updateAngles(tilt: Float, inverted: Float) {
        tiltDeg     = tilt
        invertedDeg = inverted
        invalidate()
    }

    // ── Alpha (position-driven, symmetric in both directions) ─────────────────

    private fun computeAlpha(): Float {
        if (alphaLocked) return 1f
        return when (zone) {
        Zone.UPRIGHT -> minOf(
            ramp(tiltDeg,     Thresholds.UPRIGHT_FULL_DEG, Thresholds.UPRIGHT_FADE_END_DEG, lo = 1f, hi = 0f),
            ramp(invertedDeg, Thresholds.UPRIGHT_FULL_DEG, Thresholds.UPRIGHT_FADE_END_DEG, lo = 1f, hi = 0f)
        )
        Zone.LANDSCAPE ->
            ramp(tiltDeg,     Thresholds.LANDSCAPE_ENTER_DEG, Thresholds.LANDSCAPE_FULL_DEG, lo = 0f, hi = 1f)
        Zone.INVERTED ->
            ramp(invertedDeg, Thresholds.INVERTED_FADE_START_DEG, Thresholds.INVERTED_FULL_DEG, lo = 0f, hi = 1f)
        }
    }

    private fun ramp(v: Float, low: Float, high: Float, lo: Float, hi: Float): Float = when {
        v <= low  -> lo
        v >= high -> hi
        else      -> lo + (hi - lo) * (v - low) / (high - low)
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val bottom = h - 48f
        cancelRect.set(w / 2f - 80f, bottom - 72f, w / 2f + 80f, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width  / 2f
        val cy = height / 2f

        // Guard message takes full priority — instruction and counter are not drawn.
        if (guardMsg.isNotEmpty() && guardAlpha > 0f) {
            drawGuardMessage(canvas, cx, cy)
            cancelPaint.alpha = 120
            canvas.drawText("Cancel", cx, cancelRect.bottom - 20f, cancelPaint)
            drawTopStrip(canvas)
            return
        }

        val alpha = computeAlpha()

        if (width > 0 && message.isNotEmpty() && alpha > 0f) {
            val pad         = 80
            val layoutWidth = (if (canvasRotation != 0f) height else width) - pad * 2
            textPaint.alpha = (alpha * 255).toInt()

            val layout = StaticLayout.Builder
                .obtain(message, 0, message.length, textPaint, layoutWidth.coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.2f)
                .build()

            canvas.save()
            canvas.rotate(canvasRotation, cx, cy)
            canvas.translate(cx - layoutWidth / 2f, cy - layout.height / 2f)
            layout.draw(canvas)
            if (stripVisible && counterText.isNotEmpty()) {
                counterPaint.alpha = (alpha * 255).toInt()
                val fm = counterPaint.fontMetrics
                val counterY = layout.height.toFloat() + COUNTER_GAP_PX - fm.top
                canvas.drawText(counterText, layoutWidth / 2f, counterY, counterPaint)
            }
            canvas.restore()
        }

        cancelPaint.alpha = 120
        canvas.drawText("Cancel", cx, cancelRect.bottom - 20f, cancelPaint)

        drawTopStrip(canvas)
    }

    private fun drawTopStrip(canvas: Canvas) {
        if (!stripVisible) return
        canvas.drawRect(0f, 0f, width.toFloat(), stripHeightPx, stripPaint)
    }

    private fun drawGuardMessage(canvas: Canvas, cx: Float, cy: Float) {
        if (width <= 0) return
        val pad         = 80
        val layoutWidth = width - pad * 2
        guardPaint.alpha = (guardAlpha * 255).toInt()

        val layout = StaticLayout.Builder
            .obtain(guardMsg, 0, guardMsg.length, guardPaint, layoutWidth.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.2f)
            .build()

        canvas.save()
        canvas.translate(cx - layoutWidth / 2f, cy - layout.height / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && cancelRect.contains(event.x, event.y))
            onCancel()
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP)
            onBackPressed()
        return true
    }
}
