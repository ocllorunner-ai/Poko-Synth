package app.poko.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tanh

/** Waveforms. [gain] evens out loudness: a square wave sounds much louder than a sine. */
enum class Wave(val gain: Double) { SINE(1.0), SQUARE(0.45), TRIANGLE(1.0) }

/**
 * One sounding note. The UI thread writes the @Volatile targets; the audio thread
 * glides toward them sample by sample, so changes never jump (no clicks).
 */
class Voice {
    @Volatile var targetFreq = 440f
    @Volatile var targetGain = 0f
    @Volatile var on = false
    /** Set to true to start at [targetFreq] immediately instead of gliding to it. */
    @Volatile var jump = false
    @Volatile var wave = Wave.SINE
    /** When set, the voice plays this instrument (with envelope and filter) instead of [wave]. */
    @Volatile var patch: Patch? = null
    /** Set to true to restart the patch envelope (a new key press). */
    @Volatile var trigger = false
    /** Current loudness, written by the audio thread. 0 means the voice is free. */
    @Volatile var level = 0f

    internal var freq = 440.0
    internal var gain = 0.0
    internal var phase = 0.0

    // patch state (audio thread only)
    internal var env = 0.0
    internal var stage = 2 // 0 attack, 1 decay/sustain, 2 release
    internal var fenv = 0.0
    internal val phases = DoubleArray(3)
    internal var fmPhase = 0.0
    internal var vibPhase = 0.0
    internal var ic1 = 0.0
    internal var ic2 = 0.0
}

/**
 * Small low-latency synth on AudioTrack.
 *
 * It asks Android for the device's native sample rate and burst size and opens the
 * track in low-latency mode, so it can use the fast audio path. It starts with a
 * two-burst buffer and adds one burst each time an underrun happens, which keeps
 * latency as low as the device allows without crackling.
 */
class SynthEngine(context: Context, maxVoices: Int = 10) {
    val voices = Array(maxVoices) { Voice() }

    @Volatile var masterVolume = 0.4f
    @Volatile var attackMs = 5f
    @Volatile var releaseMs = 15f
    @Volatile var glideMs = 5f

    /** Peak of the last block, for meters and animation. */
    @Volatile var peak = 0f
        private set

    private val sampleRate: Int
    private val burst: Int

    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    init {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sampleRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
            ?.takeIf { it > 0 } ?: 192
    }

    /** Returns a silent voice, or null when all are busy. Call from the UI thread. */
    fun allocate(): Voice? =
        voices.firstOrNull { !it.on && it.level < 1e-4f }
            // all busy: take the quietest note that is already fading out
            ?: voices.filter { !it.on }.minByOrNull { it.level }

    fun start() {
        if (running) return
        val t = buildTrack(lowLatency = true) ?: buildTrack(lowLatency = false) ?: return
        track = t
        try {
            t.setBufferSizeInFrames(burst * 2)
        } catch (_: Exception) {
        }
        t.play()
        running = true
        thread = Thread({ render(t) }, "PokoAudio").also { it.start() }
    }

    fun stop() {
        running = false
        try {
            thread?.join(500)
        } catch (_: InterruptedException) {
        }
        thread = null
        track?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        track = null
        for (v in voices) {
            v.on = false
            v.targetGain = 0f
            v.gain = 0.0
            v.level = 0f
            v.env = 0.0
            v.stage = 2
            v.trigger = false
        }
        peak = 0f
    }

    private val detuneMul = DoubleArray(3)

    /** Basic band-limited oscillators. [ph] is 0..1, [dt] the phase step per sample. */
    private fun basic(osc: Osc, ph: Double, dt: Double): Double = when (osc) {
        Osc.SINE -> sin(2.0 * PI * ph)
        Osc.TRIANGLE -> 4.0 * abs(ph - 0.5) - 1.0
        Osc.SAW -> 2.0 * ph - 1.0 - blep(ph, dt)
        Osc.SQUARE -> {
            var q = if (ph < 0.5) 1.0 else -1.0
            q += blep(ph, dt)
            var p2 = ph + 0.5
            if (p2 >= 1.0) p2 -= 1.0
            q - blep(p2, dt)
        }
        Osc.PULSE25 -> {
            var q = if (ph < 0.25) 1.0 else -1.0
            q += blep(ph, dt)
            var p2 = ph + 0.75
            if (p2 >= 1.0) p2 -= 1.0
            q - blep(p2, dt) + 0.5 // remove the DC offset of a 25% pulse
        }
        Osc.ORGAN, Osc.FM -> 0.0 // handled in renderPatch
    }

    private fun renderPatch(v: Voice, p: Patch, mix: DoubleArray, frames: Int, sr: Double, cf: Double) {
        if (v.trigger) {
            v.trigger = false
            v.freq = v.targetFreq.toDouble()
            // Remember the note's loudness now. The UI zeroes targetGain on key release,
            // but a patch fades out through its own envelope instead.
            v.gain = v.targetGain.toDouble()
            v.stage = 0
            v.fenv = 1.0
            v.ic1 = 0.0
            v.ic2 = 0.0
            v.fmPhase = 0.0
            v.vibPhase = 0.0
            for (k in 0 until 3) v.phases[k] = k / 3.0
        }
        // Struck sounds (they die away even while held) keep decaying at the same pace
        // after the key is let go, like a real piano, bell or marimba.
        val letRing = p.sustain == 0.0 && p.decayMs > 0.0
        if (!v.on && v.stage != 2 && !letRing) v.stage = 2
        if (!v.on && v.env < 1e-5) {
            v.env = 0.0
            v.level = 0f
            return
        }

        val attackInc = 1.0 / (max(p.attackMs, 0.5) * 0.001 * sr)
        val dCoef = if (p.decayMs > 0.0) 1.0 - exp(-1.0 / (p.decayMs * 0.001 * sr)) else 1.0
        val rCoef = 1.0 - exp(-1.0 / (max(p.releaseMs, 1.0) * 0.001 * sr))
        val fCoef = 1.0 - exp(-1.0 / (max(p.filterDecayMs, 1.0) * 0.001 * sr))
        val level = v.gain * p.gain
        val tf = v.targetFreq.toDouble()

        val uni = p.unison.coerceIn(1, 3)
        for (k in 0 until uni) {
            val spread = if (uni == 1) 0.0 else (k.toDouble() / (uni - 1) * 2.0 - 1.0) * p.detuneCents
            detuneMul[k] = Math.pow(2.0, spread / 1200.0)
        }
        val uniNorm = 1.0 / Math.sqrt(uni.toDouble())
        val vibDepth = p.vibratoCents * 0.000577623 // ln(2)/1200: cents to frequency ratio
        val vibInc = p.vibratoHz / sr

        // low-pass filter coefficients, updated once per block (TPT state-variable filter)
        val filtered = p.cutoffMul > 0.0
        var a1 = 0.0
        var a2 = 0.0
        var a3 = 0.0
        if (filtered) {
            val fc = (v.freq * p.cutoffMul * (1.0 + p.filterEnv * v.fenv)).coerceIn(20.0, sr * 0.45)
            val g = Math.tan(PI * fc / sr)
            val k = 2.0 - 2.0 * p.resonance.coerceIn(0.0, 0.95)
            a1 = 1.0 / (1.0 + g * (g + k))
            a2 = g * a1
            a3 = g * a2
        }

        var env = v.env
        var stage = v.stage
        var fenv = v.fenv
        var f = v.freq
        var ic1 = v.ic1
        var ic2 = v.ic2
        var fmPh = v.fmPhase
        var vibPh = v.vibPhase
        val ph = v.phases

        for (i in 0 until frames) {
            when (stage) {
                0 -> {
                    env += attackInc
                    if (env >= 1.0) {
                        env = 1.0
                        stage = 1
                    }
                }
                1 -> env += (p.sustain - env) * dCoef
                else -> env -= env * rCoef
            }
            fenv -= fenv * fCoef
            f += (tf - f) * cf

            var fv = f
            if (vibDepth > 0.0) {
                fv *= 1.0 + vibDepth * sin(2.0 * PI * vibPh)
                vibPh += vibInc
                if (vibPh >= 1.0) vibPh -= 1.0
            }

            var s: Double
            when (p.osc) {
                Osc.FM -> {
                    val idx = p.fmIndex * (p.fmSustain + (1.0 - p.fmSustain) * fenv)
                    s = sin(2.0 * PI * ph[0] + idx * sin(2.0 * PI * fmPh))
                    fmPh += fv * p.fmRatio / sr
                    if (fmPh >= 1.0) fmPh -= Math.floor(fmPh)
                    ph[0] += fv / sr
                    if (ph[0] >= 1.0) ph[0] -= 1.0
                }
                Osc.ORGAN -> {
                    val a = 2.0 * PI * ph[0]
                    s = (sin(a) + 0.7 * sin(2 * a) + 0.5 * sin(3 * a) +
                        0.35 * sin(4 * a) + 0.25 * sin(6 * a) + 0.2 * sin(8 * a)) / 3.0
                    ph[0] += fv / sr
                    if (ph[0] >= 1.0) ph[0] -= 1.0
                }
                else -> {
                    s = 0.0
                    for (k in 0 until uni) {
                        val dt = fv * detuneMul[k] / sr
                        s += basic(p.osc, ph[k], dt)
                        ph[k] += dt
                        if (ph[k] >= 1.0) ph[k] -= 1.0
                    }
                    s *= uniNorm
                }
            }

            if (filtered) {
                val v3 = s - ic2
                val v1 = a1 * ic1 + a2 * v3
                val v2 = ic2 + a2 * ic1 + a3 * v3
                ic1 = 2.0 * v1 - ic1
                ic2 = 2.0 * v2 - ic2
                s = v2
            }

            mix[i] += s * env * level
        }

        v.env = env
        v.stage = stage
        v.fenv = fenv
        v.freq = f
        v.ic1 = ic1
        v.ic2 = ic2
        v.fmPhase = fmPh
        v.vibPhase = vibPh
        v.level = (env * level).toFloat()
    }

    private fun buildTrack(lowLatency: Boolean): AudioTrack? = try {
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBytes, burst * 2 * 4 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (lowLatency) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        builder.build()
    } catch (_: Exception) {
        null
    }

    private fun coef(ms: Float, sr: Double): Double = 1.0 - exp(-1.0 / (max(ms, 0.1f) * 0.001 * sr))

    /** PolyBLEP correction: rounds off the jumps in the square wave to cut aliasing. */
    private fun blep(t: Double, dt: Double): Double = when {
        t < dt -> {
            val x = t / dt
            x + x - x * x - 1.0
        }
        t > 1.0 - dt -> {
            val x = (t - 1.0) / dt
            x * x + x + x + 1.0
        }
        else -> 0.0
    }

    /** Linear up to 0.8, then rounds off smoothly so many voices never hard-clip. */
    private fun softClip(x: Double): Double {
        val a = abs(x)
        return if (a <= 0.8) x else sign(x) * (0.8 + 0.2 * tanh((a - 0.8) / 0.2))
    }

    private fun render(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frames = burst
        val mix = DoubleArray(frames)
        val out = FloatArray(frames * 2)
        val sr = sampleRate.toDouble()
        var lastUnderruns = t.underrunCount
        var bufferFrames = t.bufferSizeInFrames
        val capacity = t.bufferCapacityInFrames

        while (running) {
            val ca = coef(attackMs, sr)
            val cr = coef(releaseMs, sr)
            val cf = coef(glideMs, sr)
            java.util.Arrays.fill(mix, 0.0)

            for (v in voices) {
                val p = v.patch
                if (p != null) {
                    renderPatch(v, p, mix, frames, sr, cf)
                    continue
                }
                val w = v.wave
                val target = v.targetGain * w.gain
                if (!v.on && target == 0.0 && v.gain < 1e-5) {
                    v.gain = 0.0
                    v.level = 0f
                    continue
                }
                if (v.jump) {
                    v.jump = false
                    v.freq = v.targetFreq.toDouble()
                }
                val gc = if (v.on) ca else cr
                val tf = v.targetFreq.toDouble()
                var g = v.gain
                var f = v.freq
                var ph = v.phase
                for (i in 0 until frames) {
                    g += (target - g) * gc
                    f += (tf - f) * cf
                    val dt = f / sr
                    val s = when (w) {
                        Wave.SINE -> sin(2.0 * PI * ph)
                        Wave.SQUARE -> {
                            var q = if (ph < 0.5) 1.0 else -1.0
                            q += blep(ph, dt)
                            var p2 = ph + 0.5
                            if (p2 >= 1.0) p2 -= 1.0
                            q -= blep(p2, dt)
                            q
                        }
                        Wave.TRIANGLE -> 4.0 * abs(ph - 0.5) - 1.0
                    }
                    ph += dt
                    if (ph >= 1.0) ph -= 1.0
                    mix[i] += s * g
                }
                v.gain = g
                v.freq = f
                v.phase = ph
                v.level = g.toFloat()
            }

            val mv = masterVolume.toDouble()
            var pk = 0f
            for (i in 0 until frames) {
                val x = softClip(mix[i] * mv).toFloat()
                out[2 * i] = x
                out[2 * i + 1] = x
                val a = abs(x)
                if (a > pk) pk = a
            }
            peak = pk

            val written = t.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
            if (written < 0) break

            val underruns = t.underrunCount
            if (underruns > lastUnderruns && bufferFrames + burst <= capacity) {
                bufferFrames += burst
                try {
                    t.setBufferSizeInFrames(bufferFrames)
                } catch (_: Exception) {
                }
            }
            lastUnderruns = underruns
        }
    }
}
