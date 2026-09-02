package com.levelhard.cadentia.kit

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Síntese de toda voz não-dedilhada — port 1:1 do `InstrumentSynth` do
 * `InstrumentVoices.swift`. Cordas dedilhadas vivem em [StringVoices];
 * `render` roteia para lá, então quem chama tem um ponto de entrada só.
 */
object InstrumentSynth {
    /** Renderiza uma nota. `duration` é o tempo segurado; a cauda de release soma por cima. */
    fun render(
        voice: InstrumentVoice,
        frequency: Double,
        duration: Double,
        velocity: Float = 0.8f,
        gain: Float = 1f,
        sampleRate: Double,
    ): StereoBuffer {
        if (frequency <= 0 || duration <= 0) return StereoBuffer(0)
        voice.stringModel?.let { model ->
            return StringVoices.render(model, frequency, duration, velocity, gain, sampleRate)
        }
        val vel = velocity.coerceIn(0.05f, 1f)

        val out: StereoBuffer = when (voice) {
            InstrumentVoice.Sine -> sine(frequency, duration, vel, sampleRate)
            InstrumentVoice.AcousticPiano -> acousticPiano(frequency, duration, vel, sampleRate)
            InstrumentVoice.ElectricPiano -> electricPiano(frequency, duration, vel, sampleRate)
            InstrumentVoice.Organ -> organ(frequency, duration, vel, sampleRate)
            InstrumentVoice.Lead -> lead(frequency, duration, vel, sampleRate)
            InstrumentVoice.Vibraphone -> malletBar(frequency, duration, vel, sampleRate, metal = true)
            InstrumentVoice.Marimba -> malletBar(frequency, duration, vel, sampleRate, metal = false)
            InstrumentVoice.Strings -> stringEnsemble(frequency, duration, vel, sampleRate)
            InstrumentVoice.Cello -> bowed(frequency, duration, vel, sampleRate, isCello = true)
            InstrumentVoice.Violin -> bowed(frequency, duration, vel, sampleRate, isCello = false)
            InstrumentVoice.Brass -> brass(frequency, duration, vel, sampleRate)
            InstrumentVoice.Saxophone -> saxophone(frequency, duration, vel, sampleRate)
            InstrumentVoice.Flute -> flute(frequency, duration, vel, sampleRate)
            else -> sine(frequency, duration, vel, sampleRate)
        }

        // Gain staging, a mesma disciplina do kit de bateria: cada voz
        // renderiza livre e depois toma o assento definido na mistura.
        out.trimTail()
        val peak = out.peak
        if (peak > 1e-6f) {
            out.applyGain(mixLevel(voice) * gain * velocityGain(vel) / peak)
        }
        return out
    }

    /** Pico de cada voz em velocity cheia. Pads sustentados sentam mais baixo. */
    private fun mixLevel(voice: InstrumentVoice): Float = when (voice) {
        InstrumentVoice.AcousticPiano -> 0.80f
        InstrumentVoice.ElectricPiano -> 0.74f
        InstrumentVoice.Marimba -> 0.72f
        InstrumentVoice.Brass -> 0.70f
        InstrumentVoice.Vibraphone -> 0.68f
        InstrumentVoice.Cello -> 0.68f
        InstrumentVoice.Saxophone -> 0.66f
        InstrumentVoice.Violin -> 0.64f
        InstrumentVoice.Organ -> 0.62f
        InstrumentVoice.Lead -> 0.62f
        InstrumentVoice.Flute -> 0.58f
        InstrumentVoice.Strings -> 0.54f
        InstrumentVoice.Sine -> 0.50f
        else -> 0.70f
    }

    private fun velocityGain(velocity: Float): Float = 0.34f + 0.66f * velocity.pow(1.2f)

    // MARK: piano acústico

    /**
     * Cordas reais de piano são rígidas: os parciais esticam por
     * `f(n) = n·f0·sqrt(1 + B·n²)` — é o que o ouvido lê como "piano" em vez
     * de "órgão tocando parte de piano". Por cima: duas ou três cordas em
     * uníssono levemente desafinadas, parciais altos morrendo antes, o baque
     * do martelo, e a força mudando o espectro, não só o nível.
     */
    private fun acousticPiano(
        frequency: Double, duration: Double, velocity: Float, sampleRate: Double,
    ): StereoBuffer {
        // Grave canta por muitos segundos, a oitava do topo mal sustenta.
        val register = ((log2(frequency) - log2(27.5)) / 7).coerceIn(0.0, 1.0)
        val baseDecay = 11.0 * 0.35.pow(register) + 0.6
        val release = minOf(0.9, 0.25 + baseDecay * 0.05)
        val length = ((duration + release) * sampleRate).toInt()
        if (length <= 0) return StereoBuffer(0)

        // A inarmonicidade cresce nas duas pontas do teclado.
        val inharmonicity = 0.00008 + register.pow(2.4) * 0.0022 + (1 - register).pow(5) * 0.0006
        val partialCount = (sampleRate / 2.2 / frequency).toInt().coerceIn(4, 20)
        // Golpe mais forte excita muito mais o topo do espectro.
        val brightness = 0.45 + 0.85 * velocity

        // Cordas em uníssono: três no meio e agudo, uma no grave.
        val unison: List<Double> = when {
            frequency < 120 -> listOf(1.0)
            frequency < 260 -> listOf(0.9997, 1.0004)
            else -> listOf(0.9994, 1.0, 1.0006)
        }

        val modes = mutableListOf<AudioDSP.Mode>()
        for (string in unison) {
            for (n in 1..partialCount) {
                val harmonic = n.toDouble()
                val stretched = harmonic * frequency * string *
                    sqrt(1 + inharmonicity * harmonic * harmonic)
                if (stretched >= sampleRate / 2.1) break
                val amplitude = (harmonic.pow(-1.35) * brightness.pow(if (harmonic > 1) 1.0 else 0.0)).toFloat() *
                    brightness.pow(minOf(harmonic - 1, 6.0) / 3).toFloat()
                modes.add(
                    AudioDSP.Mode(
                        frequency = stretched,
                        decay = baseDecay / (1 + 0.55 * harmonic),
                        amplitude = amplitude / unison.size,
                        phase = ((n * 37) % 100) / 100.0,
                    ),
                )
            }
        }
        val mono = AudioDSP.renderPartials(modes, sampleRate, length)
        AudioDSP.normalize(mono, 0.75f)

        // Martelo: feltro no arame, baque curto de banda larga sob o ataque.
        val hammer = AudioDSP.whiteNoise((0.02 * sampleRate).toInt(), (frequency * 7).toULong() + 13uL)
        AudioDSP.Biquad(AudioDSP.Biquad.Kind.Bandpass, frequency * 5 + 400, 0.8, sampleRate).process(hammer)
        AudioDSP.applyPercussiveEnvelope(
            hammer, attack = 0.0004, decay = 0.02,
            peak = 0.12f * velocity * velocity, sampleRate = sampleRate,
        )
        AudioDSP.add(hammer, mono, atFrame = 0)

        // Tampo: duas ressonâncias largas dão corpo.
        AudioDSP.Biquad(AudioDSP.Biquad.Kind.Peaking, 180.0, 0.8, sampleRate, gainDB = 3.0).process(mono)
        AudioDSP.Biquad(
            AudioDSP.Biquad.Kind.Peaking, 2400.0, 0.7, sampleRate,
            gainDB = velocity * 4.0 - 1,
        ).process(mono)

        applyNoteRelease(mono, duration, release, sampleRate)
        AudioDSP.deClick(mono, sampleRate, milliseconds = 1.0)

        // Piano é largo: grave à esquerda, agudo à direita, como sob as mãos.
        val out = StereoBuffer.panned(mono, pan = ((register - 0.5) * 0.5).toFloat(), gain = 0.85f)
        out.widen(byFrames = (0.004 * sampleRate).toInt(), amount = 0.3f)
        return out
    }

    // MARK: piano elétrico

    /** FM de dois operadores estilo Rhodes; o "bark" é o índice caindo em 80 ms. */
    private fun electricPiano(
        frequency: Double, duration: Double, velocity: Float, sampleRate: Double,
    ): StereoBuffer {
        val release = 0.3
        val decay = 2.6 + 2.0 * (1 - minOf(1.0, frequency / 900))
        val length = ((duration + release) * sampleRate).toInt()
        val mono = FloatArray(length)

        val indexStart = 2.2 + 5.0 * velocity
        val indexDecay = 0.075
        val modulatorRatio = 2.0
        var carrierPhase = 0.0
        var modulatorPhase = 0.0
        for (i in 0 until length) {
            val t = i / sampleRate
            val index = indexStart * exp(-t / indexDecay)
            val modulator = sin(2 * Math.PI * modulatorPhase) * index
            mono[i] = sin(2 * Math.PI * carrierPhase + modulator).toFloat()
            carrierPhase += frequency / sampleRate
            modulatorPhase += frequency * modulatorRatio / sampleRate
        }

        // Tine: o clique metálico antes de o tom assentar.
        val tine = AudioDSP.whiteNoise((0.012 * sampleRate).toInt(), frequency.toULong() + 91uL)
        AudioDSP.Biquad(AudioDSP.Biquad.Kind.Bandpass, 3800.0, 1.3, sampleRate).process(tine)
        AudioDSP.applyPercussiveEnvelope(
            tine, attack = 0.0003, decay = 0.01, peak = 0.18f * velocity, sampleRate = sampleRate,
        )
        AudioDSP.add(tine, mono, atFrame = 0)

        AudioDSP.applyADSR(
            mono, attack = 0.004, decay = decay, sustain = 0.28f, hold = duration,
            release = release, peak = 0.8f, sampleRate = sampleRate,
        )
        AudioDSP.deClick(mono, sampleRate, milliseconds = 1.0)

        // Tremolo estéreo: a assinatura do Rhodes é o tom passeando no pan.
        val left = mono.copyOf()
        val right = mono.copyOf()
        for (i in 0 until length) {
            val lfo = AudioDSP.lfo(i, 4.6, sampleRate)
            left[i] *= 1 - 0.22f * (1 + lfo) * 0.5f
            right[i] *= 1 - 0.22f * (1 - lfo) * 0.5f
        }
        return StereoBuffer(left, right)
    }

    // MARK: órgão

    /**
     * Drawbars de Hammond + caixa giratória. O 16' fica FORA de propósito:
     * energia em metade da fundamental torna a forma de onda periódica uma
     * oitava abaixo, e a nota escrita deixa de soar na altura escrita.
     */
    private fun organ(
        frequency: Double, duration: Double, velocity: Float, sampleRate: Double,
    ): StereoBuffer {
        val release = 0.09
        val length = ((duration + release) * sampleRate).toInt()
        val drawbars = listOf(
            1.0 to 1.0f, 1.5 to 0.2f, 2.0 to 0.55f,
            3.0 to 0.24f, 4.0 to 0.3f, 5.0 to 0.14f, 6.0 to 0.1f, 8.0 to 0.16f,
        )
        val mono = FloatArray(length)
        for ((ratio, amplitude) in drawbars) {
            val partial = frequency * ratio
            if (partial >= sampleRate / 2.1) continue
            val step = partial / sampleRate
            var phase = ratio * 0.13
            for (i in 0 until length) {
                mono[i] += amplitude * sin(2 * Math.PI * phase).toFloat()
                phase += step
            }
        }
        AudioDSP.normalize(mono, 0.66f)

        // Key click: o pulo de contato que faz um Hammond ser percussivo.
        val click = AudioDSP.whiteNoise((0.008 * sampleRate).toInt(), frequency.toULong() + 131uL)
        AudioDSP.Biquad(AudioDSP.Biquad.Kind.Bandpass, 2600.0, 0.9, sampleRate).process(click)
        AudioDSP.applyPercussiveEnvelope(
            click, attack = 0.0002, decay = 0.005, peak = 0.22f * velocity, sampleRate = sampleRate,
        )
        AudioDSP.add(click, mono, atFrame = 0)

        AudioDSP.applyADSR(
            mono, attack = 0.006, decay = 0.02, sustain = 0.95f, hold = duration,
            release = release, peak = 0.8f, sampleRate = sampleRate,
        )

        // Leslie: cada lado com modulação de amplitude e pitch em fase
        // oposta. Doppler em 0.00012: perto de meio semitom de balanço
        // (0.0009 balançava uma quinta, órgão quebrado).
        val left = FloatArray(length)
        val right = FloatArray(length)
        val rotorRate = 5.6
        val dopplerDepth = 0.00012 * sampleRate
        for (i in 0 until length) {
            val lfo = AudioDSP.lfo(i, rotorRate, sampleRate)
            val doppler = (lfo * dopplerDepth).toInt()
            val sourceL = (i - doppler).coerceIn(0, length - 1)
            val sourceR = (i + doppler).coerceIn(0, length - 1)
            left[i] = mono[sourceL] * (0.78f + 0.22f * lfo)
            right[i] = mono[sourceR] * (0.78f - 0.22f * lfo)
        }
        AudioDSP.deClick(left, sampleRate, milliseconds = 1.0)
        AudioDSP.deClick(right, sampleRate, milliseconds = 1.0)
        return StereoBuffer(left, right)
    }

    // MARK: mallets

    /** Vibrafone (metal, 1:4:10) e marimba (madeira, 1:4:9.2, morre rápido). */
    private fun malletBar(
        frequency: Double, duration: Double, velocity: Float, sampleRate: Double, metal: Boolean,
    ): StereoBuffer {
        val ratios: List<Triple<Double, Float, Double>> = if (metal) {
            listOf(
                Triple(1.0, 1.0f, 1.0), Triple(3.984, 0.32f, 0.55),
                Triple(10.7, 0.12f, 0.3), Triple(17.6, 0.05f, 0.16),
            )
        } else {
            listOf(Triple(1.0, 1.0f, 1.0), Triple(3.93, 0.28f, 0.4), Triple(9.2, 0.1f, 0.22))
        }
        val baseDecay = if (metal) 3.4 else 0.62
        val release = if (metal) 0.9 else 0.18
        val length = ((duration + release) * sampleRate).toInt()
        if (length <= 0) return StereoBuffer(0)

        val modes = ratios.mapNotNull { (ratio, amplitude, decayFactor) ->
            val partial = frequency * ratio
            if (partial >= sampleRate / 2.1) return@mapNotNull null
            AudioDSP.Mode(
                frequency = partial,
                decay = baseDecay * decayFactor,
                amplitude = amplitude * (0.5f + 0.6f * velocity),
            )
        }
        val mono = AudioDSP.renderPartials(modes, sampleRate, length)
        AudioDSP.normalize(mono, 0.7f)

        // Contato da baqueta: dura clica, macia dá baque.
        val strike = AudioDSP.whiteNoise(
            (0.01 * sampleRate).toInt(),
            frequency.toULong() + (if (metal) 151uL else 157uL),
        )
        AudioDSP.Biquad(
            AudioDSP.Biquad.Kind.Bandpass, if (metal) 4200.0 else 1500.0, 0.9, sampleRate,
        ).process(strike)
        AudioDSP.applyPercussiveEnvelope(
            strike, attack = 0.0003, decay = if (metal) 0.008 else 0.016,
            peak = (if (metal) 0.14f else 0.3f) * velocity, sampleRate = sampleRate,
        )
        AudioDSP.add(strike, mono, atFrame = 0)

        if (metal) {
            // Motor do vibrafone: as hélices sobre os ressonadores, ~5 Hz.
            for (i in mono.indices) {
                mono[i] *= 1 + 0.3f * AudioDSP.lfo(i, 5.2, sampleRate)
            }
        } else {
            // Tubo ressonador da marimba reforça a fundamental.
            AudioDSP.Biquad(
                AudioDSP.Biquad.Kind.Peaking, frequency, 2.2, sampleRate, gainDB = 4.0,
            ).process(mono)
        }

        applyNoteRelease(mono, duration, release, sampleRate)
        AudioDSP.deClick(mono, sampleRate, milliseconds = 1.0)
        val out = StereoBuffer.panned(mono, pan = 0f, gain = 0.8f)
        out.widen(byFrames = (0.005 * sampleRate).toInt(), amount = 0.35f)
        return out
    }

    // MARK: arco e sopro

    /** Cello e violino: sawtooth do arco filtrado pelo corpo (os formantes decidem qual é). */
    private fun bowed(
        frequency: Double, duration: Double, velocity: Float, sampleRate: Double, isCello: Boolean,
    ): StereoBuffer {
        val attack = 0.07 + 0.05 * (1 - velocity)
        val release = 0.22
        val length = ((duration + release) * sampleRate).toInt()
        if (length <= 0) return StereoBuffer(0)

        val mono = FloatArray(length)
        var phase = 0.0
        val rng = AudioDSP.Rng((frequency * 3).toULong() + (if (isCello) 401uL else 409uL))
        // O vibrato entra depois de a nota assentar, como um músico real.
        for (i in 0 until length) {
            val t = i / sampleRate
            val vibratoDepth = ((t - 0.22) / 0.35).coerceIn(0.0, 1.0) * 0.006
            val vibrato = 1 + vibratoDepth * AudioDSP.lfo(i, 5.4, sampleRate)
            phase += frequency * vibrato / sampleRate
            if (phase > 1) phase -= floor(phase)
            val saw = 2 * phase - 1
            mono[i] = saw.toFloat() + rng.nextUniform() * 0.02f
        }

        // Corpo: as ressonâncias que separam cello de violino.
        val formants: List<Triple<Double, Double, Double>> = if (isCello) {
            listOf(
                Triple(196.0, 1.2, 6.0), Triple(300.0, 1.5, 4.0),
                Triple(450.0, 2.0, 3.0), Triple(1100.0, 1.2, -3.0),
            )
        } else {
            listOf(
                Triple(280.0, 1.3, 5.0), Triple(460.0, 1.6, 5.0),
                Triple(700.0, 1.8, 3.0), Triple(2600.0, 0.9, 4.0),
            )
        }
        for ((freq, q, gainDB) in formants) {
            AudioDSP.Biquad(AudioDSP.Biquad.Kind.Peaking, freq, q, sampleRate, gainDB).process(mono)
        }
        AudioDSP.Biquad(
            AudioDSP.Biquad.Kind.Lowpass, 2200 + velocity * 3400.0, 0.7, sampleRate,
        ).process(mono)

        // Ruído de arco no ataque: o breu agarrando a corda.
        val scratch = AudioDSP.whiteNoise((0.08 * sampleRate).toInt(), frequency.toULong() + 419uL)
        AudioDSP.Biquad(AudioDSP.Biquad.Kind.Bandpass, 2400.0, 0.7, sampleRate).process(scratch)
        AudioDSP.applyPercussiveEnvelope(
            scratch, attack = 0.004, decay = 0.05, peak = 0.09f * velocity, sampleRate = sampleRate,
        )
        AudioDSP.add(scratch, mono, atFrame = 0)

        AudioDSP.applyADSR(
            mono, attack = attack, decay = 0.18, sustain = 0.82f, hold = duration,
            release = release, peak = 0.6f, sampleRate = sampleRate,
        )
        AudioDSP.removeDC(mono)
        AudioDSP.deClick(mono, sampleRate, milliseconds = 2.0)

        val out = StereoBuffer.panned(mono, pan = if (isCello) -0.15f else 0.15f, gain = 0.85f)
        out.widen(byFrames = (0.006 * sampleRate).toInt(), amount = 0.3f)
        return out
    }

    /** Naipe de cordas: vários músicos, nunca perfeitamente afinados entre si. */
    private fun stringEnsemble(
        frequency: Double, duration: Double, velocity: Float, sampleRate: Double,
    ): StereoBuffer {
        val release = 0.55
        val length = ((duration + release) * sampleRate).toInt()
        if (length <= 0) return StereoBuffer(0)

        val detunes = listOf(-9.5, -5.5, -2.0, 0.0, 2.5, 6.0, 10.0)
        val left = FloatArray(length)
        val right = FloatArray(length)
        for ((index, cents) in detunes.withIndex()) {
            val ratio = 2.0.pow(cents / 1200)
            var phase = index * 0.137
            val pan = index.toFloat() / (detunes.size - 1) * 2 - 1
            val angle = (pan + 1) * Math.PI.toFloat() / 4
            val gainL = kotlin.math.cos(angle)
            val gainR = kotlin.math.sin(angle)
            // Cada músico deriva no próprio LFO lento.
            for (i in 0 until length) {
                val drift = 1 + 0.0012 * AudioDSP.lfo(
                    i, 0.7 + index * 0.23, sampleRate, phase = index * 0.31,
                )
                phase += frequency * ratio * drift / sampleRate
                if (phase > 1) phase -= floor(phase)
                val saw = (2 * phase - 1).toFloat()
                left[i] += saw * gainL
                right[i] += saw * gainR
            }
        }

        AudioDSP.Biquad(
            AudioDSP.Biquad.Kind.Lowpass, 1900 + velocity * 2600.0, 0.6, sampleRate,
        ).process(left)
        AudioDSP.Biquad(
            AudioDSP.Biquad.Kind.Lowpass, 1900 + velocity * 2600.0, 0.6, sampleRate,
        ).process(right)
        AudioDSP.normalize(left, 0.7f)
        AudioDSP.normalize(right, 0.7f)

        for (buffer in listOf(left, right)) {
            AudioDSP.applyADSR(
                buffer, attack = 0.16, decay = 0.3, sustain = 0.85f, hold = duration,
                release = release, peak = 0.62f, sampleRate = sampleRate,
            )
            AudioDSP.deClick(buffer, sampleRate, milliseconds = 3.0)
        }
        return StereoBuffer(left, right)
    }

    /** Metais: FM com índice seguindo o envelope, porque trompa fica mais brilhante quando fica mais alta. */
    private fun brass(
        frequency: Double, duration: Double, velocity: Float, sampleRate: Double,
    ): StereoBuffer {
        val release = 0.16
        val attack = 0.045
        val length = ((duration + release) * sampleRate).toInt()
        if (length <= 0) return StereoBuffer(0)

        val mono = FloatArray(length)
        var carrierPhase = 0.0
        var modulatorPhase = 0.0
        val peakIndex = 1.6 + 3.2 * velocity
        for (i in 0 until length) {
            val t = i / sampleRate
            // Leve overshoot no ataque, o "blat" da trompa.
            val shape = when {
                t < attack -> t / attack
                t < attack + 0.07 -> 1.18 - 0.18 * ((t - attack) / 0.07)
                else -> 1.0
            }
            val index = peakIndex * shape
            val vibrato = 1 + 0.0035 * AudioDSP.lfo(i, 5.1, sampleRate) *
                ((t - 0.25) / 0.4).coerceIn(0.0, 1.0)
            mono[i] = sin(2 * Math.PI * carrierPhase + index * sin(2 * Math.PI * modulatorPhase)).toFloat()
            carrierPhase += frequency * vibrato / sampleRate
            modulatorPhase += frequency * vibrato / sampleRate
        }

        AudioDSP.Biquad(AudioDSP.Biquad.Kind.Peaking, 1250.0, 0.9, sampleRate, gainDB = 5.0).process(mono)
        AudioDSP.Biquad(AudioDSP.Biquad.Kind.Highpass, 90.0, 0.7, sampleRate).process(mono)

        AudioDSP.applyADSR(
            mono, attack = attack, decay = 0.12, sustain = 0.86f, hold = duration,
            release = release, peak = 0.62f, sampleRate = sampleRate,
        )
        AudioDSP.deClick(mono, sampleRate, milliseconds = 2.0)
        val out = StereoBuffer.panned(mono, pan = 0f, gain = 0.9f)
        out.widen(byFrames = (0.005 * sampleRate).toInt(), amount = 0.25f)
        return out
    }

    /** Sax: trem de pulso do palheta + formantes + ar sempre presente. */
    private fun saxophone(
        frequency: Double, duration: Double, velocity: Float, sampleRate: Double,
    ): StereoBuffer {
        val release = 0.14
        val length = ((duration + release) * sampleRate).toInt()
        if (length <= 0) return StereoBuffer(0)

        val mono = FloatArray(length)
        var phase = 0.0
        val width = 0.32 - 0.1 * velocity
        for (i in 0 until length) {
            val t = i / sampleRate
            val vibrato = 1 + 0.005 * AudioDSP.lfo(i, 5.0, sampleRate) *
                ((t - 0.2) / 0.35).coerceIn(0.0, 1.0)
            phase += frequency * vibrato / sampleRate
            if (phase > 1) phase -= floor(phase)
            mono[i] = if (phase < width) 1f else -0.35f
        }
        AudioDSP.Biquad(
            AudioDSP.Biquad.Kind.Lowpass, 2600 + velocity * 4200.0, 0.8, sampleRate,
        ).process(mono)
        for ((freq, q, gainDB) in listOf(
            Triple(560.0, 1.4, 6.0), Triple(1650.0, 1.2, 5.0), Triple(2700.0, 1.6, 3.0),
        )) {
            AudioDSP.Biquad(AudioDSP.Biquad.Kind.Peaking, freq, q, sampleRate, gainDB).process(mono)
        }
        AudioDSP.normalize(mono, 0.66f)

        // Ar: sax sem sopro soa patch de sintetizador.
        val breath = AudioDSP.whiteNoise(length, frequency.toULong() + 601uL)
        AudioDSP.Biquad(AudioDSP.Biquad.Kind.Bandpass, 3200.0, 0.6, sampleRate).process(breath)
        for (i in breath.indices) breath[i] *= 0.055f * (1 + 0.5f * velocity)
        AudioDSP.add(breath, mono, atFrame = 0)

        AudioDSP.applyADSR(
            mono, attack = 0.02, decay = 0.1, sustain = 0.85f, hold = duration,
            release = release, peak = 0.6f, sampleRate = sampleRate,
        )
        AudioDSP.removeDC(mono)
        AudioDSP.deClick(mono, sampleRate, milliseconds = 2.0)
        return StereoBuffer.panned(mono, pan = -0.08f, gain = 0.9f)
    }

    /** Flauta: tom quase puro montado em muito ar (o sopro é a identidade). */
    private fun flute(
        frequency: Double, duration: Double, velocity: Float, sampleRate: Double,
    ): StereoBuffer {
        val release = 0.16
        val length = ((duration + release) * sampleRate).toInt()
        if (length <= 0) return StereoBuffer(0)

        val mono = FloatArray(length)
        var phase = 0.0
        val secondPartial = 0.12f + 0.18f * velocity
        val thirdPartial = 0.03f + 0.08f * velocity
        for (i in 0 until length) {
            val t = i / sampleRate
            val vibrato = 1 + 0.0045 * AudioDSP.lfo(i, 4.7, sampleRate) *
                ((t - 0.18) / 0.3).coerceIn(0.0, 1.0)
            phase += frequency * vibrato / sampleRate
            if (phase > 1) phase -= floor(phase)
            val angle = 2 * Math.PI * phase
            mono[i] = sin(angle).toFloat() + secondPartial * sin(2 * angle).toFloat() +
                thirdPartial * sin(3 * angle).toFloat()
        }
        AudioDSP.normalize(mono, 0.6f)

        val air = AudioDSP.whiteNoise(length, frequency.toULong() + 701uL)
        AudioDSP.Biquad(
            AudioDSP.Biquad.Kind.Bandpass, frequency * 2.4 + 900, 0.5, sampleRate,
        ).process(air)
        for (i in air.indices) air[i] *= 0.16f
        // Chiff: o sopro extra bem no começo da nota.
        val chiff = air.copyOf(minOf(air.size, (0.05 * sampleRate).toInt()))
        AudioDSP.applyPercussiveEnvelope(
            chiff, attack = 0.004, decay = 0.03, peak = 0.5f * velocity, sampleRate = sampleRate,
        )
        AudioDSP.add(air, mono, atFrame = 0)
        AudioDSP.add(chiff, mono, atFrame = 0)

        AudioDSP.applyADSR(
            mono, attack = 0.035, decay = 0.08, sustain = 0.9f, hold = duration,
            release = release, peak = 0.62f, sampleRate = sampleRate,
        )
        AudioDSP.deClick(mono, sampleRate, milliseconds = 2.0)
        val out = StereoBuffer.panned(mono, pan = 0.1f, gain = 0.9f)
        out.widen(byFrames = (0.004 * sampleRate).toInt(), amount = 0.25f)
        return out
    }

    // MARK: synth

    /** Lead analógico: dois saws desafinados + oitava ACIMA, filtro SVF por amostra. */
    private fun lead(
        frequency: Double, duration: Double, velocity: Float, sampleRate: Double,
    ): StereoBuffer {
        val release = 0.22
        val length = ((duration + release) * sampleRate).toInt()
        if (length <= 0) return StereoBuffer(0)

        val mono = FloatArray(length)
        var phaseA = 0.0
        var phaseB = 0.37
        var phaseOctave = 0.0
        val detune = 2.0.pow(7.0 / 1200)
        for (i in 0 until length) {
            phaseA += frequency / sampleRate
            phaseB += frequency * detune / sampleRate
            phaseOctave += frequency * 2 / sampleRate
            if (phaseA > 1) phaseA -= floor(phaseA)
            if (phaseB > 1) phaseB -= floor(phaseB)
            if (phaseOctave > 1) phaseOctave -= floor(phaseOctave)
            val saws = (2 * phaseA - 1).toFloat() * 0.5f + (2 * phaseB - 1).toFloat() * 0.5f
            // Oitava PARA CIMA para dar corte, nunca para baixo: sub deixa a
            // forma periódica abaixo da nota escrita e o ouvido vai atrás.
            val octave = if (phaseOctave < 0.5) 0.1f else -0.1f
            mono[i] = saws * 0.92f + octave
        }

        // Varredura de filtro por amostra (SVF), cutoff contínuo, sem zipper.
        var low = 0f
        var band = 0f
        val resonance = 0.72f
        val cutoffStart = frequency * (3 + 6 * velocity)
        val cutoffEnd = frequency * 2.4
        for (i in 0 until length) {
            val t = i / sampleRate
            val sweep = exp(-t / 0.28)
            val cutoff = cutoffEnd + (cutoffStart - cutoffEnd) * sweep
            val f = (2 * sin(Math.PI * minOf(cutoff, sampleRate * 0.45) / sampleRate)).toFloat()
            val high = mono[i] - low - resonance * band
            band += f * high
            low += f * band
            mono[i] = low
        }

        AudioDSP.applyADSR(
            mono, attack = 0.006, decay = 0.16, sustain = 0.68f, hold = duration,
            release = release, peak = 0.5f, sampleRate = sampleRate,
        )
        AudioDSP.saturate(mono, drive = 1.4f, mix = 0.35f)
        AudioDSP.removeDC(mono)
        AudioDSP.deClick(mono, sampleRate, milliseconds = 1.5)
        val out = StereoBuffer.panned(mono, pan = 0f, gain = 0.85f)
        out.widen(byFrames = (0.007 * sampleRate).toInt(), amount = 0.4f)
        return out
    }

    private fun sine(
        frequency: Double, duration: Double, velocity: Float, sampleRate: Double,
    ): StereoBuffer {
        val release = 0.18
        val length = ((duration + release) * sampleRate).toInt()
        if (length <= 0) return StereoBuffer(0)
        val mono = FloatArray(length)
        var phase = 0.0
        for (i in 0 until length) {
            mono[i] = sin(2 * Math.PI * phase).toFloat()
            phase += frequency / sampleRate
            if (phase > 1) phase -= floor(phase)
        }
        AudioDSP.applyADSR(
            mono, attack = 0.008, decay = 0.06, sustain = 0.8f, hold = duration,
            release = release, peak = 0.55f * (0.5f + 0.5f * velocity), sampleRate = sampleRate,
        )
        AudioDSP.deClick(mono, sampleRate, milliseconds = 2.0)
        return StereoBuffer(mono)
    }

    // MARK: ajudantes

    /** Release de nota para buffers que soam livres (pianos, mallets). */
    private fun applyNoteRelease(
        buffer: FloatArray, duration: Double, release: Double, sampleRate: Double,
    ) {
        val start = (duration * sampleRate).toInt()
        if (start >= buffer.size) return
        val releaseSamples = maxOf(1, (release * sampleRate).toInt())
        for (i in start until buffer.size) {
            val t = (i - start).toFloat() / releaseSamples
            buffer[i] *= if (t >= 1) 0f else (1 - t) * (1 - t)
        }
    }
}
