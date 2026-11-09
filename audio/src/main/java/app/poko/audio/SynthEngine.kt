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
    /** Current loudness, written by the audio thread. 0 means the voice is free. */
    @Volatile var level = 0f

    internal var freq = 440.0
    internal var gain = 0.0
    internal var phase = 0.0
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
    fun allocate(): Voice? = voices.firstOrNull { !it.on && it.level < 1e-4f }

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
        }
        peak = 0f
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
