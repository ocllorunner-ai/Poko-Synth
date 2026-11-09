package app.poko.audio

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** Colors shared by both apps (same as the web versions). */
object Pal {
    val BG = 0xFF120F1F.toInt()
    val PANEL = 0xFF1C1830.toInt()
    val LINE = 0xFF2E2848.toInt()
    val FG = 0xFFEFEAFF.toInt()
    val DIM = 0xFF9A91C2.toInt()
    val ACCENT = 0xFFFFB02E.toInt()
    val ACCENT2 = 0xFF4FE3C1.toInt()
    val PINK = 0xFFFF6F91.toInt()
    val KEY_WHITE = 0xFFECE8F7.toInt()
    val KEY_EDGE = 0xFFB9B2D4.toInt()
    val KEY_BLACK = 0xFF231D38.toInt()

    fun alpha(color: Int, a: Float): Int = (color and 0x00FFFFFF) or ((a * 255).toInt().coerceIn(0, 255) shl 24)
}

const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

fun Context.dp(v: Number): Float = v.toFloat() * resources.displayMetrics.density
fun Context.dpi(v: Number): Int = dp(v).toInt()

private fun Context.rounded(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
    setColor(fill)
    setStroke(dpi(1), stroke)
    cornerRadius = dp(10)
}

private fun Context.chip(label: String): TextView = TextView(this).apply {
    text = label
    textSize = 12f
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    setPadding(dpi(11), dpi(8), dpi(11), dpi(8))
}

/** A row of options where exactly one is chosen. */
class Segmented(
    ctx: Context,
    labels: List<String>,
    initial: Int,
    private val onSelect: (Int) -> Unit,
) : LinearLayout(ctx) {
    private val items = ArrayList<TextView>()
    var current = initial
        private set

    init {
        orientation = HORIZONTAL
        background = ctx.rounded(Pal.PANEL, Pal.LINE)
        clipToOutline = true
        labels.forEachIndexed { i, s ->
            val tv = ctx.chip(s)
            tv.setOnClickListener {
                current = i
                refresh()
                onSelect(i)
            }
            items.add(tv)
            addView(tv)
        }
        refresh()
    }

    private fun refresh() {
        items.forEachIndexed { i, tv ->
            val on = i == current
            tv.setBackgroundColor(if (on) Pal.ACCENT else Color.TRANSPARENT)
            tv.setTextColor(if (on) Pal.BG else Pal.DIM)
        }
    }
}

/** An on/off button. */
class Toggle(
    ctx: Context,
    label: String,
    initial: Boolean,
    private val onChange: (Boolean) -> Unit,
) : TextView(ctx) {
    var isOn = initial
        private set

    init {
        text = label
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(ctx.dpi(12), ctx.dpi(8), ctx.dpi(12), ctx.dpi(8))
        setOnClickListener {
            isOn = !isOn
            refresh()
            onChange(isOn)
        }
        refresh()
    }

    private fun refresh() {
        background = context.rounded(Pal.PANEL, if (isOn) Pal.ACCENT2 else Pal.LINE)
        setTextColor(if (isOn) Pal.ACCENT2 else Pal.DIM)
    }
}

/** "−  value  ＋" control. */
class Stepper(ctx: Context) : LinearLayout(ctx) {
    var onStep: (Int) -> Unit = {}
    private val valueView: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = ctx.rounded(Pal.PANEL, Pal.LINE)
        clipToOutline = true
        val down = ctx.chip("−").apply { setTextColor(Pal.DIM); setOnClickListener { onStep(-1) } }
        valueView = ctx.chip("").apply { setTextColor(Pal.FG); minWidth = ctx.dpi(64); textSize = 13f }
        val up = ctx.chip("＋").apply { setTextColor(Pal.DIM); setOnClickListener { onStep(1) } }
        addView(down)
        addView(valueView)
        addView(up)
    }

    fun setValue(text: String) {
        valueView.text = text
    }
}

/** A button that shows the current choice ("エレピ ▾") and opens a list when tapped. */
class PickerButton(ctx: Context) : TextView(ctx) {
    init {
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = ctx.dpi(120)
        setPadding(ctx.dpi(14), ctx.dpi(8), ctx.dpi(14), ctx.dpi(8))
        background = ctx.rounded(Pal.PANEL, Pal.ACCENT)
        setTextColor(Pal.ACCENT)
    }

    fun setValue(label: String) {
        text = "$label  ▾"
    }
}

fun Context.caption(s: String): TextView =TextView(this).apply {
    text = s
    textSize = 10f
    letterSpacing = 0.12f
    setTextColor(Pal.DIM)
}

/** Caption followed by a control. */
fun Context.group(label: String, child: View, childWidth: Int = WRAP): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(caption(label))
        addView(child, LinearLayout.LayoutParams(childWidth, WRAP).apply { marginStart = dpi(6) })
    }

/** A horizontally scrolling row, so controls never get cut off on narrow phones. */
fun Context.row(gap: Int, alignEnd: Boolean, vararg views: View): HorizontalScrollView {
    val inner = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = (if (alignEnd) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
    }
    views.forEachIndexed { i, v ->
        inner.addView(v, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) marginStart = dpi(gap) })
    }
    return HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(inner, ViewGroup.LayoutParams(MATCH, WRAP))
    }
}

/** "Poko" + accented app name, e.g. ポコ<シンセ>. */
fun Context.brandTitle(name: String): TextView = TextView(this).apply {
    val brand = getString(R.string.brand)
    val s = SpannableString(brand + name)
    s.setSpan(ForegroundColorSpan(Pal.ACCENT), brand.length, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    text = s
    textSize = 22f
    typeface = Typeface.DEFAULT_BOLD
    setTextColor(Pal.FG)
}

/** Draws behind the system bars and pads [root] so content stays clear of them. Keeps the screen on. */
@Suppress("DEPRECATION")
fun Activity.edgeToEdge(root: View) {
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    window.statusBarColor = Pal.BG
    window.navigationBarColor = Pal.BG
    if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false)
    } else {
        root.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
    val side = dpi(16)
    root.setOnApplyWindowInsetsListener { v, insets ->
        val l: Int
        val t: Int
        val r: Int
        val b: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            l = i.left; t = i.top; r = i.right; b = i.bottom
        } else {
            l = insets.systemWindowInsetLeft; t = insets.systemWindowInsetTop
            r = insets.systemWindowInsetRight; b = insets.systemWindowInsetBottom
        }
        v.setPadding(maxOf(side, l), t, maxOf(side, r), b)
        insets
    }
    root.requestApplyInsets()
}
