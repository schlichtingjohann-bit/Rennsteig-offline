package de.rennsteig.offline

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Höhenprofil der gesamten Route mit eigener Position und angetippter Stelle. */
class ProfileView(context: Context) : View(context) {
    var profile: Profile? = null
        set(value) { field = value; invalidate() }
    var lengthKm = 0.0
    var currentKm: Double? = null
        set(value) { field = value; invalidate() }
    var selectedKm: Double? = null
        set(value) { field = value; invalidate() }
    var onKmTapped: ((Double) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f * d; color = 0xFF5B4A32.toInt()
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000; strokeWidth = d }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF555048.toInt(); textSize = 10.5f * d }
    private val mePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F63D6.toInt(); strokeWidth = 2f * d }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFB300.toInt(); strokeWidth = 2f * d }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt() }
    private val path = Path()

    private val padL get() = 34f * d
    private val padR get() = 10f * d
    private val padT get() = 18f * d
    private val padB get() = 18f * d

    override fun onDraw(canvas: Canvas) {
        val p = profile ?: return
        if (p.ele.size < 2 || lengthKm <= 0) return
        val w = width - padL - padR
        val h = height - padT - padB
        val lo = floor(p.minM / 100.0) * 100
        val hi = maxOf(lo + 100, ceil(p.maxM / 100.0) * 100)
        fun x(km: Double) = (padL + km / lengthKm * w).toFloat()
        fun y(e: Double) = (padT + (1 - (e - lo) / (hi - lo)) * h).toFloat()

        // Raster: Höhenlinien alle 100/200 m, km alle 20 km
        val stepEle = if (hi - lo > 500) 200.0 else 100.0
        var e = lo
        while (e <= hi + 0.1) {
            canvas.drawLine(padL, y(e), padL + w, y(e), gridPaint)
            canvas.drawText("${e.roundToInt()}", 2f * d, y(e) + 4f * d, textPaint)
            e += stepEle
        }
        var k = 0.0
        while (k <= lengthKm) {
            canvas.drawLine(x(k), padT, x(k), padT + h, gridPaint)
            val label = "${k.roundToInt()} km"
            canvas.drawText(label, (x(k) - textPaint.measureText(label) / 2).coerceIn(padL, width - padR - textPaint.measureText(label)), height - 4f * d, textPaint)
            k += 20.0
        }

        path.reset()
        val n = p.ele.size
        val stepKm = p.stepM / 1000.0
        val stride = maxOf(1, n / maxOf(1, w.toInt()))
        path.moveTo(x(0.0), y(lo))
        var i = 0
        while (i < n) {
            path.lineTo(x(minOf(i * stepKm, lengthKm)), y(p.ele[i].toDouble()))
            i += stride
        }
        path.lineTo(x(lengthKm), y(p.ele[n - 1].toDouble()))
        path.lineTo(x(lengthKm), y(lo))
        path.close()
        fillPaint.shader = LinearGradient(0f, padT, 0f, padT + h, 0xFF9CC77E.toInt(), 0xFFE9E3C9.toInt(), Shader.TileMode.CLAMP)
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, linePaint)

        selectedKm?.let { km -> marker(canvas, km, p, x(km), y(p.eleAt(km)), selPaint, h) }
        currentKm?.let { km -> marker(canvas, km, p, x(km), y(p.eleAt(km)), mePaint, h) }

        val info = "↑ ${p.ascent} m   ↓ ${p.descent} m   ${p.minM}–${p.maxM} m"
        canvas.drawText(info, padL + 4f * d, padT - 5f * d, textPaint)
    }

    private fun marker(c: Canvas, km: Double, p: Profile, x: Float, y: Float, paint: Paint, h: Float) {
        c.drawLine(x, padT, x, padT + h, paint)
        c.drawCircle(x, y, 4.5f * d, paint)
        val text = "km ${Geo.formatKm(km)} · ${p.eleAt(km).roundToInt()} m"
        val tw = textPaint.measureText(text)
        val tx = (x + 6f * d).let { if (it + tw > width - padR) x - 6f * d - tw else it }
        val ty = (y - 8f * d).coerceAtLeast(padT + 10f * d)
        c.drawRect(tx - 2f * d, ty - 11f * d, tx + tw + 2f * d, ty + 3f * d, labelBg)
        val old = textPaint.color
        textPaint.color = Color.BLACK
        c.drawText(text, tx, ty, textPaint)
        textPaint.color = old
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (profile == null || lengthKm <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val w = width - padL - padR
                val km = ((event.x - padL) / w * lengthKm).coerceIn(0.0, lengthKm)
                selectedKm = km
                onKmTapped?.invoke(km)
                return true
            }
        }
        return true
    }
}
