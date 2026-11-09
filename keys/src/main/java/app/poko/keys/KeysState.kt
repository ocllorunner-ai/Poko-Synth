package app.poko.keys

import android.content.SharedPreferences
import app.poko.audio.Wave

/** Settings, saved as soon as they change. */
class KeysState(private val p: SharedPreferences) {
    /** MIDI note of the leftmost key, always a C (48 = C3). */
    var low: Int = p.getInt("low", 48).let { if (it in 24..84 && it % 12 == 0) it else 48 }
        set(v) { field = v; p.edit().putInt("low", v).apply() }

    /** Octaves of keys on screen, 1 to 3. */
    var oct: Int = p.getInt("oct", 2).coerceIn(1, 3)
        set(v) { field = v; p.edit().putInt("oct", v).apply() }

    var wave: Wave = runCatching { Wave.valueOf(p.getString("wave", "SINE") ?: "SINE") }.getOrDefault(Wave.SINE)
        set(v) { field = v; p.edit().putString("wave", v.name).apply() }

    var showNames: Boolean = p.getBoolean("names", true)
        set(v) { field = v; p.edit().putBoolean("names", v).apply() }

    /** 0 to 1. */
    var volume: Float = p.getFloat("vol", 0.7f).coerceIn(0f, 1f)
        set(v) { field = v; p.edit().putFloat("vol", v).apply() }
}
