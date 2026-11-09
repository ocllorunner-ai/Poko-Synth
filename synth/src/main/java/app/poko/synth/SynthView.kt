package app.poko.synth

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import app.poko.audio.Pal
import app.poko.audio.SynthEngine
import app.poko.audio.Voice
import app.poko.audio.Wave
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import app.poko.audio.R as AR

/**
 * The touch pad: left-right is pitch, up-down is volume, up to 5 fingers.
 * Also draws the semitone lines and Poko.
 */
class SynthView(
    ctx: Context,
    private val engine: SynthEngine,
    private val st: SynthState,
) : View(ctx) {

    private class Finger(val voice: Voice) {
        var x = 0f
        var y = 0f
        var freq = 0f
    }

    private val fingers = LinkedHashMap<Int, Finger>()
    private var started = false

    private val names: Array<String> = resources.getStringArray(AR.array.note_names)
    private val sAxisVolume = ctx.getString(AR.string.axis_volume)
    private val sAxisPitch = ctx.getString(AR.string.axis_pitch)
    private val sTouch = ctx.getString(AR.string.touch_to_play)
    private val sHint = ctx.getString(AR.string.touch_hint)

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        color = Pal.DIM
        textSize = 12 * d
    }
    private var hintLayout: StaticLayout? = null
    private val path = Path()
    private val rf = RectF()

    private val reduceMotion = Settings.Global.getFloat(
        ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) == 0f
    private var mouth = 0f
    private var blink = 0f
    private var blinkTimer = 120

    fun noteName(m: Int): String = names[m % 12] + (m / 12 - 1)

    // ---------- sound ----------

    private fun freqAt(x: Float): Float {
        var s = (x / max(1, width)).coerceIn(0f, 1f) * st.oct * 12
        if (st.snap) s = s.roundToInt().toFloat()
        return (440.0 * 2.0.pow((st.low + s - 69) / 12.0)).toFloat()
    }

    private fun update(f: Finger) {
        f.freq = freqAt(f.x)
        val b = 1f - (f.y / max(1, height)).coerceIn(0f, 1f)
        f.voice.targetFreq = f.freq
        f.voice.targetGain = b * b * 0.4f
    }

    private fun release(id: Int) {
        fingers.remove(id)?.voice?.let {
            it.on = false
            it.targetGain = 0f
        }
    }

    fun releaseAll() {
        fingers.keys.toList().forEach { release(it) }
    }

    fun setWave(w: Wave) {
        fingers.values.forEach { it.voice.wave = w }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                started = true
                val i = e.actionIndex
                if (fingers.size < 5) {
                    engine.allocate()?.let { v ->
                        val f = Finger(v)
                        f.x = e.getX(i)
                        f.y = e.getY(i)
                        v.wave = st.wave
                        update(f)
                        v.jump = true
                        v.on = true
                        fingers[e.getPointerId(i)] = f
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    fingers[e.getPointerId(i)]?.let {
                        it.x = e.getX(i)
                        it.y = e.getY(i)
                        update(it)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> release(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    // ---------- drawing ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val width = max(1, w - (32 * d).toInt())
        hintLayout = StaticLayout.Builder.obtain(sHint, 0, sHint.length, hintPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawColor(Pal.PANEL)

        // semitone lines, thick on C
        if (st.showGrid) {
            val n = st.oct * 12
            text.textSize = 11 * d
            text.textAlign = Paint.Align.LEFT
            for (i in 0..n) {
                val m = st.low + i
                val x = i / n.toFloat() * w
                val isC = m % 12 == 0
                line.strokeWidth = (if (isC) 3f else 1.75f) * d
                line.color = if (isC) Pal.alpha(Pal.ACCENT, 0.55f) else Pal.alpha(Pal.DIM, 0.22f)
                c.drawLine(x, 0f, x, h, line)
                if (isC && st.showNames) {
                    text.color = Pal.alpha(Pal.ACCENT, 0.8f)
                    c.drawText(noteName(m), x + 5 * d, h - 26 * d, text)
                }
            }
        }

        // fingers
        val r = 46 * d
        text.textSize = 11 * d
        text.textAlign = Paint.Align.LEFT
        for (f in fingers.values) {
            fill.shader = RadialGradient(
                f.x, f.y, r,
                intArrayOf(Pal.alpha(Pal.ACCENT, 0.9f), Pal.alpha(Pal.ACCENT, 0f)),
                null, Shader.TileMode.CLAMP
            )
            c.drawCircle(f.x, f.y, r, fill)
            fill.shader = null
            text.color = Pal.FG
            c.drawText("${f.freq.roundToInt()}Hz", f.x + 18 * d, f.y - 18 * d, text)
        }

        mouth += (engine.peak - mouth) * 0.25f
        if (st.showPoko) drawPoko(c, w, h)

        if (st.showHelp) {
            text.textSize = 10 * d
            text.color = Pal.DIM
            text.textAlign = Paint.Align.LEFT
            c.drawText(sAxisVolume, 12 * d, 20 * d, text)
            text.textAlign = Paint.Align.RIGHT
            c.drawText(sAxisPitch, w - 12 * d, h - 10 * d, text)
            if (!started) {
                text.textAlign = Paint.Align.CENTER
                text.textSize = 18 * d
                text.color = Pal.FG
                val hl = hintLayout
                val hintH = hl?.height ?: 0
                c.drawText(sTouch, w / 2, h - 40 * d - hintH, text)
                if (hl != null) {
                    c.save()
                    c.translate(16 * d, h - 28 * d - hintH)
                    hl.draw(c)
                    c.restore()
                }
            }
        }

        postInvalidateOnAnimation()
    }

    private fun oval(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float) {
        rf.set(cx - rx, cy - ry, cx + rx, cy + ry)
        c.drawOval(rf, fill)
    }

    private fun drawPoko(c: Canvas, w: Float, h: Float) {
        val t = SystemClock.uptimeMillis().toDouble()
        val cx = w / 2
        val cy = min(h * 0.42f, h / 2 - 20 * d)
        val rr = min(w, h) * 0.17f
        val bob = if (reduceMotion) 0f else (sin(t / 600.0) * 4 * d).toFloat()
        val s = 1f + mouth * 0.25f

        c.save()
        c.translate(cx, cy + bob)
        c.scale(s, 1f / s + mouth * 0.05f)

        // body
        path.reset()
        var a = 0.0
        var first = true
        while (a <= 2 * PI + 0.01) {
            val wob = if (reduceMotion) 0.0 else sin(a * 5 + t / 300.0) * rr * 0.03 * (1 + mouth * 4)
            val rad = rr + wob
            val px = (cos(a) * rad).toFloat()
            val py = (sin(a) * rad * 0.9).toFloat()
            if (first) {
                path.moveTo(px, py)
                first = false
            } else {
                path.lineTo(px, py)
            }
            a += 0.2
        }
        path.close()
        fill.color = Pal.ACCENT2
        c.drawPath(path, fill)

        // eyes look toward the fingers
        var tx = 0f
        var ty = 0f
        if (fingers.isNotEmpty()) {
            tx = fingers.values.map { it.x }.average().toFloat() - cx
            ty = fingers.values.map { it.y }.average().toFloat() - cy
        }
        val dist = hypot(tx, ty).let { if (it == 0f) 1f else it }
        val ex = tx / dist * rr * 0.08f
        val ey = ty / dist * rr * 0.08f
        blinkTimer--
        if (blinkTimer < 0) {
            blink = 1f
            blinkTimer = 180 + (Math.random() * 200).toInt()
        }
        blink *= 0.8f
        fill.color = Pal.BG
        for (k in intArrayOf(-1, 1)) {
            oval(c, k * rr * 0.35f + ex, -rr * 0.2f + ey, rr * 0.11f, rr * 0.15f * (1 - blink))
        }

        // mouth opens with the sound
        oval(c, 0f, rr * 0.25f, rr * 0.22f, rr * 0.04f + mouth * rr * 0.5f)

        // cheeks
        fill.color = Pal.PINK
        oval(c, -rr * 0.6f, rr * 0.08f, rr * 0.1f, rr * 0.06f)
        oval(c, rr * 0.6f, rr * 0.08f, rr * 0.1f, rr * 0.06f)

        c.restore()
    }
}
