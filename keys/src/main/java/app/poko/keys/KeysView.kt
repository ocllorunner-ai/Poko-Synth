package app.poko.keys

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import app.poko.audio.Pal
import app.poko.audio.SynthEngine
import app.poko.audio.Voice
import app.poko.audio.Wave
import kotlin.math.pow
import app.poko.audio.R as AR

/** The keyboard. Multi-touch; sliding a finger moves the note to the next key. */
class KeysView(
    ctx: Context,
    private val engine: SynthEngine,
    private val st: KeysState,
) : View(ctx) {

    private class Key(val m: Int, val x: Float, val w: Float, val h: Float, val black: Boolean)
    private class Held(val m: Int, val voice: Voice)

    private var keys: List<Key> = emptyList()
    private val held = HashMap<Int, Held>()

    private val names: Array<String> = resources.getStringArray(AR.array.note_names)
    private val d = resources.displayMetrics.density
    private val fill = Paint()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    fun noteName(m: Int): String = names[m % 12] + (m / 12 - 1)

    private fun isBlack(m: Int): Boolean = when (m % 12) {
        1, 3, 6, 8, 10 -> true
        else -> false
    }

    private fun freq(m: Int): Float = (440.0 * 2.0.pow((m - 69) / 12.0)).toFloat()

    /** Rebuilds key positions. Call after the size or range changes. */
    fun relayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val top = st.low + st.oct * 12
        val whites = (st.low..top).filter { !isBlack(it) }
        val ww = w / whites.size
        val bw = ww * 0.62f
        val bh = h * 0.6f
        val list = ArrayList<Key>()
        whites.forEachIndexed { i, m -> list.add(Key(m, i * ww, ww, h, false)) }
        whites.forEachIndexed { i, m ->
            if (m + 1 <= top && isBlack(m + 1)) list.add(Key(m + 1, (i + 1) * ww - bw / 2, bw, bh, true))
        }
        keys = list
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        relayout()
    }

    private fun hit(x: Float, y: Float): Int? {
        for (k in keys) if (k.black && x >= k.x && x < k.x + k.w && y < k.h) return k.m
        for (k in keys) if (!k.black && x >= k.x && x < k.x + k.w) return k.m
        return null
    }

    // ---------- sound ----------

    private fun noteOn(id: Int, m: Int) {
        noteOff(id)
        val v = engine.allocate() ?: return
        v.wave = st.wave
        v.targetFreq = freq(m)
        v.jump = true
        v.targetGain = 0.3f
        v.on = true
        held[id] = Held(m, v)
        invalidate()
    }

    private fun noteOff(id: Int) {
        val h = held.remove(id) ?: return
        h.voice.on = false
        h.voice.targetGain = 0f
        invalidate()
    }

    fun releaseAll() {
        held.keys.toList().forEach { noteOff(it) }
    }

    fun setWave(w: Wave) {
        held.values.forEach { it.voice.wave = w }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                hit(e.getX(i), e.getY(i))?.let { noteOn(e.getPointerId(i), it) }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val m = hit(e.getX(i), e.getY(i))
                    if (m == null) noteOff(id) else if (held[id]?.m != m) noteOn(id, m)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> noteOff(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    // ---------- drawing ----------

    override fun onDraw(c: Canvas) {
        c.drawColor(Pal.PANEL)
        val on = held.values.map { it.m }.toSet()
        for (k in keys) {
            fill.color = when {
                k.m in on -> Pal.ACCENT
                k.black -> Pal.KEY_BLACK
                else -> Pal.KEY_WHITE
            }
            c.drawRect(k.x, 0f, k.x + k.w, k.h, fill)
            if (!k.black) {
                fill.color = Pal.KEY_EDGE
                c.drawRect(k.x + k.w - d, 0f, k.x + k.w, k.h, fill)
                if (st.showNames) {
                    text.textSize = (k.w * 0.32f).coerceIn(10 * d, 15 * d)
                    val isC = k.m % 12 == 0
                    text.color = if (isC) Pal.BG else Pal.DIM
                    c.drawText(if (isC) noteName(k.m) else names[k.m % 12], k.x + k.w / 2, k.h - 14 * d, text)
                }
            }
        }
    }
}
