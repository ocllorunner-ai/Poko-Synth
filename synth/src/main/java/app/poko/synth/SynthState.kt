package app.poko.synth

import android.content.SharedPreferences
import app.poko.audio.Wave

/** Settings, saved as soon as they change. */
class SynthState(private val p: SharedPreferences) {
    /** MIDI note at the left edge (45 = A2). */
    var low: Int = p.getInt("low", 45).coerceIn(24, 84)
        set(v) { field = v; p.edit().putInt("low", v).apply() }

    /** Octaves across the pad, 1 to 4. */
    var oct: Int = p.getInt("oct", 3).coerceIn(1, 4)
        set(v) { field = v; p.edit().putInt("oct", v).apply() }

    /** true = snap pitch to the nearest semitone. */
    var snap: Boolean = p.getBoolean("snap", false)
        set(v) { field = v; p.edit().putBoolean("snap", v).apply() }

    var wave: Wave = runCatching { Wave.valueOf(p.getString("wave", "SINE") ?: "SINE") }.getOrDefault(Wave.SINE)
        set(v) { field = v; p.edit().putString("wave", v.name).apply() }

    var showHelp: Boolean = p.getBoolean("help", true)
        set(v) { field = v; p.edit().putBoolean("help", v).apply() }

    var showGrid: Boolean = p.getBoolean("grid", true)
        set(v) { field = v; p.edit().putBoolean("grid", v).apply() }

    var showNames: Boolean = p.getBoolean("names", true)
        set(v) { field = v; p.edit().putBoolean("names", v).apply() }

    var showPoko: Boolean = p.getBoolean("poko", true)
        set(v) { field = v; p.edit().putBoolean("poko", v).apply() }
}
