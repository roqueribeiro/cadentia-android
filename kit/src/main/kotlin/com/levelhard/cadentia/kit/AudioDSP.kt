package com.levelhard.cadentia.kit

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * A caixa de ferramentas HD: ressoadores, filtros, saturação e envelopes
 * compartilhados pelo kit de bateria, pelas vozes de instrumento e pelos
 * modelos de corda — port 1:1 do `AudioDSP.swift`.
 *
 * Tudo aqui é offline e determinístico: mesmas entradas, mesmas amostras,
 * sempre — é o que torna o round robin reproduzível e o DSP testável.
 */
object AudioDSP {
    // MARK: ruído

    /** Ruído branco determinístico (xorshift64), semeado por voz e variação. */
    class Rng(seed: ULong) {
        private var state: ULong = if (seed == 0uL) 0x9E3779B97F4A7C15uL else seed

        fun nextUniform(): Float {
            state = state xor (state shl 13)
            state = state xor (state shr 7)
            state = state xor (state shl 17)
            return ((state shr 11).toDouble() / (1L shl 53).toDouble()).toFloat() * 2 - 1
        }

        /** Uniforme em 0…1. */
        fun next01(): Float = (nextUniform() + 1) * 0.5f

        /** Uniforme em -range…+range. */
        fun jitter(range: Float): Float = nextUniform() * range
    }

    fun whiteNoise(count: Int, seed: ULong): FloatArray {
        val rng = Rng(seed)
        return FloatArray(maxOf(count, 0)) { rng.nextUniform() }
    }

    // MARK: síntese modal

    /**
     * Um modo ressonante de um corpo percutido: frequência, tempo de
     * decaimento (até -60 dB) e amplitude na mistura.
     */
    data class Mode(
        val frequency: Double,
        /** Segundos para cair 60 dB. */
        val decay: Double,
        val amplitude: Float,
        /** Fase inicial em ciclos, para os modos não estourarem juntos. */
        val phase: Double = 0.0,
    )

    /** Ressoador de dois polos: a forma eficiente de uma senoide que decai. */
    class Resonator(frequency: Double, decay: Double, sampleRate: Double) {
        private val coefficient1: Float
        private val coefficient2: Float
        private var y1 = 0f
        private var y2 = 0f

        init {
            val nyquist = sampleRate / 2
            val safeFreq = frequency.coerceIn(1.0, nyquist * 0.98)
            val omega = 2 * Math.PI * safeFreq / sampleRate
            // r^n chega a -60 dB depois de `decay` segundos.
            val radius = exp(-6.907755 / (maxOf(decay, 0.001) * sampleRate))
            coefficient1 = (2 * radius * cos(omega)).toFloat()
            coefficient2 = (-radius * radius).toFloat()
        }

        fun process(x: Float): Float {
            val y = x + coefficient1 * y1 + coefficient2 * y2
            y2 = y1
            y1 = y
            return y
        }
    }

    /** Renderiza um banco modal excitado por `excitation` — o miolo dos drums. */
    fun renderModes(
        modes: List<Mode>,
        excitation: FloatArray,
        sampleRate: Double,
        length: Int,
    ): FloatArray {
        if (modes.isEmpty() || length <= 0) return FloatArray(0)
        val out = FloatArray(length)
        for (mode in modes) {
            if (mode.amplitude == 0f || mode.frequency <= 0) continue
            val resonator = Resonator(mode.frequency, mode.decay, sampleRate)
            // Normaliza: um ressoador de dois polos tem ganho enorme na
            // ressonância; (1 - r) mantém modos comparáveis entre decays.
            val radius = exp(-6.907755 / (maxOf(mode.decay, 0.001) * sampleRate))
            val norm = (1 - radius).toFloat() * mode.amplitude
            for (i in 0 until length) {
                val drive = if (i < excitation.size) excitation[i] else 0f
                out[i] += resonator.process(drive) * norm
            }
        }
        return out
    }

    /** Banco aditivo de senos: cada parcial com seu decaimento exponencial. */
    fun renderPartials(
        modes: List<Mode>,
        sampleRate: Double,
        length: Int,
    ): FloatArray {
        if (modes.isEmpty() || length <= 0) return FloatArray(0)
        val out = FloatArray(length)
        val nyquist = sampleRate / 2
        for (mode in modes) {
            if (mode.amplitude == 0f || mode.frequency <= 0 || mode.frequency >= nyquist * 0.95) continue
            val step = mode.frequency / sampleRate
            val decayPerSample = exp(-6.907755 / (maxOf(mode.decay, 0.001) * sampleRate)).toFloat()
            var phase = mode.phase
            var envelope = mode.amplitude
            for (i in 0 until length) {
                out[i] += envelope * sin(2 * Math.PI * phase).toFloat()
                phase += step
                if (phase > 1) phase -= kotlin.math.floor(phase)
                envelope *= decayPerSample
                if (envelope < 1e-7f) break
            }
        }
        return out
    }

    // MARK: filtros

    /** Biquad RBJ com o conjunto completo de tipos que as vozes HD usam. */
    class Biquad(
        kind: Kind,
        frequency: Double,
        q: Double,
        sampleRate: Double,
        gainDB: Double = 0.0,
    ) {
        enum class Kind { Lowpass, Highpass, Bandpass, Notch, Peaking, LowShelf, HighShelf, Allpass }

        private val b0: Float
        private val b1: Float
        private val b2: Float
        private val a1: Float
        private val a2: Float
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        init {
            val nyquist = sampleRate / 2
            val omega = 2 * Math.PI * frequency.coerceIn(1.0, nyquist * 0.95) / sampleRate
            val sinw = sin(omega)
            val cosw = cos(omega)
            val alpha = sinw / (2 * maxOf(q, 0.0001))
            val amplitude = 10.0.pow(gainDB / 40)

            var b0d = 0.0
            var b1d = 0.0
            var b2d = 0.0
            var a0d = 1.0
            var a1d = 0.0
            var a2d = 0.0
            when (kind) {
                Kind.Lowpass -> {
                    b0d = (1 - cosw) / 2; b1d = 1 - cosw; b2d = (1 - cosw) / 2
                    a0d = 1 + alpha; a1d = -2 * cosw; a2d = 1 - alpha
                }
                Kind.Highpass -> {
                    b0d = (1 + cosw) / 2; b1d = -(1 + cosw); b2d = (1 + cosw) / 2
                    a0d = 1 + alpha; a1d = -2 * cosw; a2d = 1 - alpha
                }
                Kind.Bandpass -> {
                    b0d = alpha; b1d = 0.0; b2d = -alpha
                    a0d = 1 + alpha; a1d = -2 * cosw; a2d = 1 - alpha
                }
                Kind.Notch -> {
                    b0d = 1.0; b1d = -2 * cosw; b2d = 1.0
                    a0d = 1 + alpha; a1d = -2 * cosw; a2d = 1 - alpha
                }
                Kind.Allpass -> {
                    b0d = 1 - alpha; b1d = -2 * cosw; b2d = 1 + alpha
                    a0d = 1 + alpha; a1d = -2 * cosw; a2d = 1 - alpha
                }
                Kind.Peaking -> {
                    b0d = 1 + alpha * amplitude; b1d = -2 * cosw; b2d = 1 - alpha * amplitude
                    a0d = 1 + alpha / amplitude; a1d = -2 * cosw; a2d = 1 - alpha / amplitude
                }
                Kind.LowShelf -> {
                    val sqrtA = 2 * sqrt(amplitude) * alpha
                    b0d = amplitude * ((amplitude + 1) - (amplitude - 1) * cosw + sqrtA)
                    b1d = 2 * amplitude * ((amplitude - 1) - (amplitude + 1) * cosw)
                    b2d = amplitude * ((amplitude + 1) - (amplitude - 1) * cosw - sqrtA)
                    a0d = (amplitude + 1) + (amplitude - 1) * cosw + sqrtA
                    a1d = -2 * ((amplitude - 1) + (amplitude + 1) * cosw)
                    a2d = (amplitude + 1) + (amplitude - 1) * cosw - sqrtA
                }
                Kind.HighShelf -> {
                    val sqrtA = 2 * sqrt(amplitude) * alpha
                    b0d = amplitude * ((amplitude + 1) + (amplitude - 1) * cosw + sqrtA)
                    b1d = -2 * amplitude * ((amplitude - 1) + (amplitude + 1) * cosw)
                    b2d = amplitude * ((amplitude + 1) + (amplitude - 1) * cosw - sqrtA)
                    a0d = (amplitude + 1) - (amplitude - 1) * cosw + sqrtA
                    a1d = 2 * ((amplitude - 1) - (amplitude + 1) * cosw)
                    a2d = (amplitude + 1) - (amplitude - 1) * cosw - sqrtA
                }
            }
            b0 = (b0d / a0d).toFloat()
            b1 = (b1d / a0d).toFloat()
            b2 = (b2d / a0d).toFloat()
            a1 = (a1d / a0d).toFloat()
            a2 = (a2d / a0d).toFloat()
        }

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }

        fun process(buffer: FloatArray) {
            for (i in buffer.indices) buffer[i] = process(buffer[i])
        }
    }

    /** Passa-baixa de um polo: o damping dentro do laço de corda dedilhada. */
    class OnePole(cutoff: Double, sampleRate: Double) {
        private val a: Float = exp(-2 * Math.PI * cutoff.coerceIn(1.0, sampleRate / 2) / sampleRate).toFloat()
        private var z = 0f

        fun process(input: Float): Float {
            z = input * (1 - a) + z * a
            return z
        }
    }

    /** Remove o passeio DC que saturação e excitação assimétrica deixam. */
    fun removeDC(buffer: FloatArray) {
        if (buffer.isEmpty()) return
        var x1 = 0f
        var y1 = 0f
        val r = 0.9995f
        for (i in buffer.indices) {
            val x = buffer[i]
            val y = x - x1 + r * y1
            x1 = x
            y1 = y
            buffer[i] = y
        }
    }

    // MARK: saturação e dinâmica

    /** Saturação de joelho macio: punch sem o estalo digital do hard clip. */
    fun saturate(buffer: FloatArray, drive: Float, mix: Float = 1f) {
        if (drive <= 0) return
        val norm = tanh(drive)
        for (i in buffer.indices) {
            val wet = tanh(buffer[i] * drive) / norm
            buffer[i] = buffer[i] * (1 - mix) + wet * mix
        }
    }

    /** Compressor feed-forward offline com release dependente do programa. */
    fun compress(
        buffer: FloatArray,
        thresholdDB: Float,
        ratio: Float,
        attack: Double,
        release: Double,
        makeupDB: Float,
        sampleRate: Double,
    ) {
        if (buffer.isEmpty() || ratio <= 1) return
        val attackCoefficient = exp(-1.0 / (maxOf(attack, 0.0001) * sampleRate)).toFloat()
        val releaseCoefficient = exp(-1.0 / (maxOf(release, 0.001) * sampleRate)).toFloat()
        val makeup = 10f.pow(makeupDB / 20)
        var envelope = 0f
        for (i in buffer.indices) {
            val rectified = abs(buffer[i])
            val coefficient = if (rectified > envelope) attackCoefficient else releaseCoefficient
            envelope = rectified + coefficient * (envelope - rectified)
            val levelDB = 20 * log10(maxOf(envelope, 1e-6f))
            var gainDB = 0f
            if (levelDB > thresholdDB) {
                gainDB = (thresholdDB - levelDB) * (1 - 1 / ratio)
            }
            buffer[i] *= 10f.pow(gainDB / 20) * makeup
        }
    }

    // MARK: envelopes

    /** Ataque linear em decaimento exponencial rumo a -60 dB. */
    fun applyPercussiveEnvelope(
        buffer: FloatArray,
        attack: Double,
        decay: Double,
        peak: Float,
        sampleRate: Double,
    ) {
        if (peak <= 0) {
            buffer.fill(0f)
            return
        }
        val attackSamples = maxOf(1, (attack * sampleRate).toInt())
        val decayCoefficient = exp(-6.907755 / (maxOf(decay, 0.0005) * sampleRate))
        var envelope = 0f
        for (i in buffer.indices) {
            envelope = when {
                i < attackSamples -> peak * i / attackSamples
                i == attackSamples -> peak
                else -> envelope * decayCoefficient.toFloat()
            }
            buffer[i] *= envelope
        }
    }

    /**
     * ADSR com decay e release exponenciais. O release começa quando a tecla
     * sobe, ponto final — esperar o decay terminar deixava o piano elétrico
     * (decay 2,6 s sob nota de 0,6 s) parado num terço do nível, sem soltar.
     */
    fun applyADSR(
        buffer: FloatArray,
        attack: Double,
        decay: Double,
        sustain: Float,
        hold: Double,
        release: Double,
        peak: Float,
        sampleRate: Double,
    ) {
        if (peak <= 0 || buffer.isEmpty()) return
        val attackSamples = maxOf(1, (attack * sampleRate).toInt())
        val decaySamples = maxOf(1, (decay * sampleRate).toInt())
        val releaseStart = maxOf(attackSamples, (hold * sampleRate).toInt())
        val releaseSamples = maxOf(1, (release * sampleRate).toInt())

        /** Envelope enquanto a tecla ainda está apertada. */
        fun heldLevel(index: Int): Float {
            if (index < attackSamples) {
                // Ataque levemente curvo lê mais macio que rampa reta.
                val t = index.toFloat() / attackSamples
                return t * t * (3 - 2 * t)
            }
            if (index < attackSamples + decaySamples) {
                val t = (index - attackSamples).toFloat() / decaySamples
                return sustain + (1 - sustain) * expDecayShape(t)
            }
            return sustain
        }

        // O release parte de onde o envelope realmente estava.
        val levelAtRelease = heldLevel(releaseStart)
        for (i in buffer.indices) {
            val gain: Float = if (i < releaseStart) {
                peak * heldLevel(i)
            } else {
                val t = (i - releaseStart).toFloat() / releaseSamples
                if (t >= 1) 0f else peak * levelAtRelease * expDecayShape(t)
            }
            buffer[i] *= gain
        }
    }

    /** 1 em t=0 caindo a ~0 em t=1 numa curva exponencial. */
    private fun expDecayShape(t: Float): Float {
        if (t >= 1) return 0f
        val value = (exp(-4 * t) - 0.0183156f) / 0.9816844f
        return maxOf(0f, value)
    }

    /** Esvanece começo e fim para matar o clique de buffer não-zero. */
    fun deClick(buffer: FloatArray, sampleRate: Double, milliseconds: Double = 1.5) {
        val ramp = maxOf(1, (milliseconds / 1000 * sampleRate).toInt())
        if (buffer.size <= ramp * 2) return
        for (i in 0 until ramp) {
            val gain = i.toFloat() / ramp
            buffer[i] *= gain
            buffer[buffer.size - 1 - i] *= gain
        }
    }

    // MARK: modulação

    /** Valor do LFO de vibrato/tremolo num índice de amostra. */
    fun lfo(index: Int, rate: Double, sampleRate: Double, phase: Double = 0.0): Float =
        sin(2 * Math.PI * (rate * index / sampleRate + phase)).toFloat()

    /** Leitura com atraso fracionário: chorus, dobra e combs de palheta. */
    fun delayed(buffer: FloatArray, samples: Double): FloatArray {
        if (samples <= 0 || buffer.isEmpty()) return buffer
        val out = FloatArray(buffer.size)
        val whole = samples.toInt()
        val frac = (samples - whole).toFloat()
        for (i in buffer.indices) {
            val a = i - whole
            val b = a - 1
            val sampleA = if (a >= 0) buffer[a] else 0f
            val sampleB = if (b >= 0) buffer[b] else 0f
            out[i] = sampleA * (1 - frac) + sampleB * frac
        }
        return out
    }

    // MARK: ajudantes

    fun mix(buffers: List<FloatArray>): FloatArray {
        val length = buffers.maxOfOrNull { it.size } ?: 0
        val out = FloatArray(length)
        for (buffer in buffers) {
            for (i in buffer.indices) out[i] += buffer[i]
        }
        return out
    }

    /** Soma `source` em `target` a partir de um offset de frames. */
    fun add(source: FloatArray, target: FloatArray, atFrame: Int, gain: Float = 1f) {
        if (gain == 0f) return
        for (i in source.indices) {
            val index = atFrame + i
            if (index < 0 || index >= target.size) continue
            target[index] += source[i] * gain
        }
    }

    /** Escala o buffer para o pico mais alto sentar em `target`. */
    fun normalize(buffer: FloatArray, target: Float) {
        var peak = 0f
        for (value in buffer) peak = maxOf(peak, abs(value))
        if (peak <= 1e-6f) return
        val gain = target / peak
        for (i in buffer.indices) buffer[i] *= gain
    }
}
