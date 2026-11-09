package app.poko.audio

/** Oscillator types a [Patch] can use. */
enum class Osc { SINE, TRIANGLE, SAW, SQUARE, PULSE25, ORGAN, FM }

/**
 * One instrument sound.
 *
 * - Amp envelope: [attackMs] rises linearly, then [decayMs] falls toward [sustain]
 *   (0 = the note dies away even while held, like a piano), [releaseMs] after the key is let go.
 * - Filter: low-pass at the note frequency times [cutoffMul] (0 = no filter). [filterEnv]
 *   opens it further at the start of the note, closing over [filterDecayMs].
 * - FM ([Osc.FM]): a sine modulator at [fmRatio] times the note. The modulation depth starts
 *   at [fmIndex] and falls to [fmIndex] × [fmSustain] over [filterDecayMs].
 * - [unison] copies of the oscillator spread across [detuneCents] make the sound wider.
 * - [vibratoCents] / [vibratoHz] add a gentle pitch wobble.
 * - [gain] evens out loudness between patches.
 */
data class Patch(
    val id: String,
    val nameRes: Int,
    val osc: Osc,
    val gain: Double,
    val attackMs: Double = 4.0,
    val decayMs: Double = 0.0,
    val sustain: Double = 1.0,
    val releaseMs: Double = 25.0,
    val unison: Int = 1,
    val detuneCents: Double = 0.0,
    val cutoffMul: Double = 0.0,
    val filterEnv: Double = 0.0,
    val filterDecayMs: Double = 300.0,
    val resonance: Double = 0.0,
    val fmRatio: Double = 1.0,
    val fmIndex: Double = 0.0,
    val fmSustain: Double = 0.0,
    val vibratoCents: Double = 0.0,
    val vibratoHz: Double = 5.0,
)

object Patches {
    val ALL: List<Patch> = listOf(
        Patch("sine", R.string.wave_sine, Osc.SINE, 1.0),
        Patch("triangle", R.string.wave_triangle, Osc.TRIANGLE, 1.0),
        Patch("square", R.string.wave_square, Osc.SQUARE, 0.45),
        Patch("saw", R.string.tone_saw, Osc.SAW, 0.75),
        Patch("chip", R.string.tone_chip, Osc.PULSE25, 0.5, releaseMs = 12.0),
        Patch("organ", R.string.tone_organ, Osc.ORGAN, 1.3, attackMs = 6.0, releaseMs = 45.0),
        Patch(
            "epiano", R.string.tone_epiano, Osc.FM, 1.0,
            attackMs = 2.0, decayMs = 2600.0, sustain = 0.0, releaseMs = 260.0,
            fmRatio = 1.0, fmIndex = 1.8, fmSustain = 0.15, filterDecayMs = 700.0,
        ),
        Patch(
            "bell", R.string.tone_bell, Osc.FM, 0.75,
            attackMs = 1.0, decayMs = 5000.0, sustain = 0.0, releaseMs = 900.0,
            fmRatio = 3.5, fmIndex = 4.0, fmSustain = 0.1, filterDecayMs = 1300.0,
        ),
        Patch(
            "harp", R.string.tone_harp, Osc.SAW, 1.1,
            attackMs = 1.0, decayMs = 1300.0, sustain = 0.0, releaseMs = 220.0,
            cutoffMul = 1.0, filterEnv = 10.0, filterDecayMs = 220.0,
        ),
        Patch(
            "strings", R.string.tone_strings, Osc.SAW, 0.95,
            attackMs = 250.0, releaseMs = 500.0,
            unison = 2, detuneCents = 10.0, cutoffMul = 5.0,
            vibratoCents = 12.0, vibratoHz = 5.5,
        ),
        Patch(
            "pad", R.string.tone_pad, Osc.SAW, 1.1,
            attackMs = 700.0, releaseMs = 1200.0,
            unison = 3, detuneCents = 14.0, cutoffMul = 2.5, resonance = 0.2,
        ),
        Patch(
            "brass", R.string.tone_brass, Osc.SAW, 1.2,
            attackMs = 35.0, decayMs = 250.0, sustain = 0.8, releaseMs = 120.0,
            cutoffMul = 1.5, filterEnv = 5.0, filterDecayMs = 350.0,
        ),
        Patch(
            "flute", R.string.tone_flute, Osc.TRIANGLE, 0.95,
            attackMs = 70.0, releaseMs = 120.0,
            cutoffMul = 3.0, vibratoCents = 15.0, vibratoHz = 5.0,
        ),
        Patch(
            "bass", R.string.tone_bass, Osc.SQUARE, 0.85,
            attackMs = 2.0, decayMs = 500.0, sustain = 0.5, releaseMs = 60.0,
            cutoffMul = 2.0, filterEnv = 5.0, filterDecayMs = 180.0, resonance = 0.3,
        ),
    )

    fun byId(id: String?): Patch = ALL.firstOrNull { it.id == id } ?: ALL[0]
}
