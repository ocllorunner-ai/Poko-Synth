package app.poko.keys

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import app.poko.audio.MATCH
import app.poko.audio.Pal
import app.poko.audio.Patches
import app.poko.audio.PickerButton
import app.poko.audio.Segmented
import app.poko.audio.Stepper
import app.poko.audio.SynthEngine
import app.poko.audio.Toggle
import app.poko.audio.WRAP
import app.poko.audio.brandTitle
import app.poko.audio.dpi
import app.poko.audio.edgeToEdge
import app.poko.audio.group
import app.poko.audio.row
import kotlin.math.roundToInt
import app.poko.audio.R as AR

class MainActivity : Activity() {
    private lateinit var engine: SynthEngine
    private lateinit var keyboard: KeysView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val st = KeysState(getSharedPreferences("poko-keys", MODE_PRIVATE))
        engine = SynthEngine(this).apply {
            masterVolume = st.volume * 0.5f
            releaseMs = 20f
        }
        keyboard = KeysView(this, engine, st)

        val toggles = row(
            8, true,
            Toggle(this, getString(AR.string.notes), st.showNames) {
                st.showNames = it
                keyboard.invalidate()
            },
        )

        val tonePicker = PickerButton(this)
        tonePicker.setValue(getString(st.patch.nameRes))
        tonePicker.setOnClickListener {
            val names: Array<CharSequence> = Patches.ALL.map { getString(it.nameRes) as CharSequence }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(getString(AR.string.wave))
                .setSingleChoiceItems(names, Patches.ALL.indexOf(st.patch)) { dialog, which ->
                    st.patch = Patches.ALL[which]
                    tonePicker.setValue(getString(st.patch.nameRes))
                    dialog.dismiss()
                }
                .show()
        }

        val lowStep = Stepper(this)
        lowStep.setValue(keyboard.noteName(st.low))
        lowStep.onStep = { step ->
            val next = (st.low + step * 12).coerceIn(24, 84)
            if (next != st.low) {
                keyboard.releaseAll()
                st.low = next
                lowStep.setValue(keyboard.noteName(st.low))
                keyboard.relayout()
            }
        }

        val countSeg = Segmented(this, listOf("1oct", "2oct", "3oct"), st.oct - 1) {
            keyboard.releaseAll()
            st.oct = it + 1
            keyboard.relayout()
        }

        val volume = SeekBar(this).apply {
            max = 100
            progress = (st.volume * 100).roundToInt()
            progressTintList = ColorStateList.valueOf(Pal.ACCENT)
            thumbTintList = ColorStateList.valueOf(Pal.ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    st.volume = value / 100f
                    engine.masterVolume = st.volume * 0.5f
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }

        val controls = row(
            12, false,
            group(getString(AR.string.wave), tonePicker),
            group(getString(AR.string.leftmost), lowStep),
            group(getString(AR.string.key_count), countSeg),
            group(getString(AR.string.volume), volume, dpi(140)),
        )

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(brandTitle(getString(AR.string.keys_name)))
            addView(toggles, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dpi(12) })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.BG)
            addView(header, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dpi(8)
                bottomMargin = dpi(4)
            })
            addView(controls, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dpi(4)
                bottomMargin = dpi(8)
            })
            addView(keyboard, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { bottomMargin = dpi(10) })
        }
        setContentView(root)
        edgeToEdge(root)
    }

    override fun onResume() {
        super.onResume()
        engine.start()
    }

    override fun onPause() {
        keyboard.releaseAll()
        engine.stop()
        super.onPause()
    }
}
