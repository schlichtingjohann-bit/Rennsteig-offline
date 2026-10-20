package de.rennsteig.offline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Zur Laufzeit gezeichnete Kartensymbole (kein Sprite-Atlas nötig, alles offline). */
object Icons {
    private const val GOLD = 0xFFFFB300.toInt()

    fun all(density: Float): Map<String, Bitmap> {
        val m = HashMap<String, Bitmap>()
        for (cat in PoiCategory.entries) {
            m[cat.iconName(false)] = poi(cat, false, density)
            m[cat.iconName(true)] = poi(cat, true, density)
        }
        m["view-cone"] = viewCone(density)
        m["me-dot"] = meDot(density, 0xFF1F63D6.toInt())
        m["me-dot-stale"] = meDot(density, 0xFF8E8E8E.toInt())
        m["peak"] = peak(density)
        m["flag-start"] = flag(density, 0xFF2E7D32.toInt())
        m["flag-end"] = flag(density, 0xFFD1294D.toInt())
        return m
    }

    fun poi(cat: PoiCategory, top: Boolean, density: Float): Bitmap {
        val sizeDp = if (top) 34f else 28f
        val size = (sizeDp * density).roundToInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // leichter Schatten
        p.color = 0x40000000
        c.drawCircle(cx, cx + 0.8f * density, cx - 0.5f * density, p)
        p.color = if (top) GOLD else Color.WHITE
        val outer = cx - 1.2f * density
        c.drawCircle(cx, cx, outer, p)
        p.color = cat.color
        val inner = outer - (if (top) 3.2f else 2f) * density
        c.drawCircle(cx, cx, inner, p)
        drawGlyph(c, cat, cx, cx, inner * 0.62f)
        return bmp
    }

    /** Weißes Piktogramm je Kategorie, gezeichnet im Quadrat [cx-r, cx+r]. */
    private fun drawGlyph(c: Canvas, cat: PoiCategory, cx: Float, cy: Float, r: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = r * 0.22f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        fun x(f: Float) = cx + f * r
        fun y(f: Float) = cy + f * r
        when (cat) {
            PoiCategory.LODGING -> { // Bett
                c.drawRect(x(-0.9f), y(-0.55f), x(-0.68f), y(0.75f), fill)
                c.drawRect(x(-0.9f), y(0.05f), x(0.9f), y(0.38f), fill)
                c.drawRect(x(0.68f), y(0.05f), x(0.9f), y(0.75f), fill)
                c.drawRoundRect(RectF(x(-0.6f), y(-0.35f), x(-0.12f), y(0.0f)), r * 0.12f, r * 0.12f, fill)
                c.drawRoundRect(RectF(x(-0.05f), y(-0.3f), x(0.9f), y(0.0f)), r * 0.1f, r * 0.1f, fill)
            }
            PoiCategory.FOOD -> { // Messer und Gabel
                c.drawLine(x(-0.45f), y(-0.85f), x(-0.45f), y(0.85f), stroke)
                c.drawLine(x(-0.75f), y(-0.85f), x(-0.75f), y(-0.25f), stroke)
                c.drawLine(x(-0.15f), y(-0.85f), x(-0.15f), y(-0.25f), stroke)
                c.drawLine(x(-0.75f), y(-0.25f), x(-0.15f), y(-0.25f), stroke)
                val knife = Path().apply {
                    moveTo(x(0.45f), y(0.85f)); lineTo(x(0.45f), y(-0.85f))
                    quadTo(x(0.95f), y(-0.5f), x(0.75f), y(0.05f)); lineTo(x(0.45f), y(0.05f))
                }
                c.drawPath(knife, stroke)
                c.drawPath(knife, fill)
            }
            PoiCategory.SIGHT -> c.drawPath(star(cx, cy, r * 0.98f, r * 0.42f), fill)
            PoiCategory.VIEW -> { // Berg mit Gipfel
                val m = Path().apply {
                    moveTo(x(-0.95f), y(0.7f)); lineTo(x(-0.25f), y(-0.65f)); lineTo(x(0.12f), y(0.0f))
                    lineTo(x(0.38f), y(-0.35f)); lineTo(x(0.95f), y(0.7f)); close()
                }
                c.drawPath(m, fill)
            }
            PoiCategory.SHELTER -> { // Hütte
                val h = Path().apply {
                    moveTo(x(-0.95f), y(0.0f)); lineTo(x(0f), y(-0.85f)); lineTo(x(0.95f), y(0.0f))
                    lineTo(x(0.7f), y(0.0f)); lineTo(x(0.7f), y(0.8f)); lineTo(x(-0.7f), y(0.8f))
                    lineTo(x(-0.7f), y(0.0f)); close()
                }
                c.drawPath(h, fill)
            }
            PoiCategory.WATER -> { // Tropfen
                val d = Path().apply {
                    moveTo(x(0f), y(-0.95f))
                    cubicTo(x(0.25f), y(-0.45f), x(0.75f), y(0.0f), x(0.7f), y(0.35f))
                    cubicTo(x(0.65f), y(0.95f), x(-0.65f), y(0.95f), x(-0.7f), y(0.35f))
                    cubicTo(x(-0.75f), y(0.0f), x(-0.25f), y(-0.45f), x(0f), y(-0.95f))
                    close()
                }
                c.drawPath(d, fill)
            }
            PoiCategory.STATION -> { // Zug von vorn
                c.drawRoundRect(RectF(x(-0.65f), y(-0.9f), x(0.65f), y(0.55f)), r * 0.3f, r * 0.3f, fill)
                val win = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cat.color }
                c.drawRect(x(-0.45f), y(-0.65f), x(0.45f), y(-0.15f), win)
                c.drawCircle(x(-0.35f), y(0.22f), r * 0.12f, win)
                c.drawCircle(x(0.35f), y(0.22f), r * 0.12f, win)
                c.drawLine(x(-0.4f), y(0.6f), x(-0.7f), y(0.95f), stroke)
                c.drawLine(x(0.4f), y(0.6f), x(0.7f), y(0.95f), stroke)
            }
        }
    }

    private fun star(cx: Float, cy: Float, outer: Float, inner: Float): Path {
        val p = Path()
        for (i in 0 until 10) {
            val a = Math.toRadians(-90.0 + i * 36.0)
            val rr = if (i % 2 == 0) outer else inner
            val px = cx + (rr * cos(a)).toFloat()
            val py = cy + (rr * sin(a)).toFloat() + outer * 0.08f
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        p.close()
        return p
    }

    /** Sichtkegel: Spitze in der Bildmitte, öffnet sich nach oben (Norden), wird per icon-rotate gedreht. */
    fun viewCone(density: Float): Bitmap {
        val size = (150 * density).roundToInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val radius = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(cx, cx, radius,
            intArrayOf(0xB01F63D6.toInt(), 0x601F63D6, 0x001F63D6),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        val half = 32f
        c.drawArc(RectF(0f, 0f, size.toFloat(), size.toFloat()), -90f - half, 2 * half, true, p)
        return bmp
    }

    fun meDot(density: Float, color: Int): Bitmap {
        val size = (26 * density).roundToInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x50000000
        c.drawCircle(cx, cx + density, 10.5f * density, p)
        p.color = Color.WHITE
        c.drawCircle(cx, cx, 10.5f * density, p)
        p.color = color
        c.drawCircle(cx, cx, 7.5f * density, p)
        return bmp
    }

    fun peak(density: Float): Bitmap {
        val w = (11 * density).roundToInt()
        val h = (9 * density).roundToInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6B4A2B.toInt() }
        val path = Path().apply {
            moveTo(w / 2f, density); lineTo(w - density, h - density); lineTo(density, h - density); close()
        }
        c.drawPath(path, p)
        return bmp
    }

    fun flag(density: Float, color: Int): Bitmap {
        val w = (26 * density).roundToInt()
        val h = (32 * density).roundToInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF333333.toInt()
        c.drawRect(2f * density, 2f * density, 4.5f * density, h.toFloat(), p)
        p.color = Color.WHITE
        c.drawRect(4.5f * density, 2f * density, w - 1f * density, 17f * density, p)
        p.color = color
        c.drawRect(5.5f * density, 3f * density, w - 2f * density, 16f * density, p)
        p.color = Color.WHITE
        p.textSize = 11f * density
        p.isFakeBoldText = true
        p.textAlign = Paint.Align.CENTER
        c.drawText("R", (5.5f * density + w - 2f * density) / 2f, 13.5f * density, p)
        return bmp
    }
}
