package app.poko.synth

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import app.poko.audio.MATCH
import app.poko.audio.Pal
import app.poko.audio.Segmented
import app.poko.audio.Stepper
import app.poko.audio.SynthEngine
import app.poko.audio.Toggle
import app.poko.audio.WRAP
import app.poko.audio.Wave
import app.poko.audio.brandTitle
import app.poko.audio.dpi
import app.poko.audio.edgeToEdge
import app.poko.audio.group
import app.poko.audio.row
import app.poko.audio.R as AR

class MainActivity : Activity() {
    private lateinit var engine: SynthEngine
    private lateinit var pad: SynthView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = SynthEngine(this).apply {
            masterVolume = 0.4f
            releaseMs = 15f
        }
        val st = SynthState(getSharedPreferences("poko-synth", MODE_PRIVATE))
        pad = SynthView(this, engine, st)

        val toggles = row(
            8, true,
            Toggle(this, getString(AR.string.hints), st.showHelp) { st.showHelp = it },
            Toggle(this, getString(AR.string.lines), st.showGrid) { st.showGrid = it },
            Toggle(this, getString(AR.string.notes), st.showNames) { st.showNames = it },
            Toggle(this, getString(AR.string.poko), st.showPoko) { st.showPoko = it },
        )

        val waves = Wave.values()
        val waveSeg = Segmented(
            this,
            listOf(getString(AR.string.wave_sine), getString(AR.string.wave_square), getString(AR.string.wave_triangle)),
            st.wave.ordinal,
        ) {
            st.wave = waves[it]
            pad.setWave(waves[it])
        }

        val lowStep = Stepper(this)
        lowStep.setValue(pad.noteName(st.low))
        lowStep.onStep = { step ->
            st.low = (st.low + step).coerceIn(24, 84)
            lowStep.setValue(pad.noteName(st.low))
        }

        val rangeSeg = Segmented(this, listOf("1oct", "2oct", "3oct", "4oct"), st.oct - 1) { st.oct = it + 1 }

        val pitchSeg = Segmented(
            this,
            listOf(getString(AR.string.pitch_free), getString(AR.string.pitch_snap)),
            if (st.snap) 1 else 0,
        ) { st.snap = it == 1 }

        val controls = row(
            12, false,
            group(getString(AR.string.wave), waveSeg),
            group(getString(AR.string.lowest), lowStep),
            group(getString(AR.string.range), rangeSeg),
            group(getString(AR.string.pitch), pitchSeg),
        )

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(brandTitle(getString(AR.string.synth_name)))
            addView(toggles, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dpi(12) })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.BG)
            addView(header, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dpi(10)
                bottomMargin = dpi(6)
            })
            addView(controls, LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dpi(4)
                bottomMargin = dpi(10)
            })
            addView(pad, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { bottomMargin = dpi(12) })
        }
        setContentView(root)
        edgeToEdge(root)
    }

    override fun onResume() {
        super.onResume()
        engine.start()
    }

    override fun onPause() {
        pad.releaseAll()
        engine.stop()
        super.onPause()
    }
}
