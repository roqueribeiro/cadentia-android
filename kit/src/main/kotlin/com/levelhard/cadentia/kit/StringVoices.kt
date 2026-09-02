package com.levelhard.cadentia.kit

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Modelos de corda dedilhada: cinco guitarras e três baixos — port 1:1 do
 * `StringVoices.swift`.
 *
 * A base é Karplus-Strong, mas a versão pura de 1983 só entrega "algo
 * dedilhado". Quatro coisas fazem virar instrumento: um CORPO (as
 * ressonâncias da caixa), a POSIÇÃO DA PALHETA (comb na excitação),
 * amortecimento DEPENDENTE DE FREQUÊNCIA (one-pole no laço) e DUAS cordas
 * levemente desafinadas no estéreo. A afinação compensa o atraso de fase do
 * filtro do laço: sem isso toca quase meio semitom abaixo, e o afinador
 * deste próprio app mostra o erro na tela.
 */
object StringVoices {
    enum class Model(val id: String) {
        GuitarClean("guitar-clean"),
        GuitarAcoustic("guitar-acoustic"),
        GuitarNylon("guitar-nylon"),
        GuitarJazz("guitar-jazz"),
        GuitarDistorted("guitar-distorted"),
        BassFingered("bass-fingered"),
        BassPicked("bass-picked"),
        BassSlap("bass-slap");

        companion object {
            fun from(id: String?): Model? = entries.firstOrNull { it.id == id }
        }
    }

    fun render(
        model: Model,
        frequency: Double,
        duration: Double,
        velocity: Float = 0.8f,
        gain: Float = 1f,
        sampleRate: Double,
    ): StereoBuffer {
        if (frequency <= 0 || duration <= 0) return StereoBuffer(0)
        val vel = velocity.coerceIn(0.05f, 1f)
        val parameters = parameters(model, vel)

        val release = 0.22
        val length = ((duration + release) * sampleRate).toInt()
        if (length <= 2) return StereoBuffer(0)

        // Duas cordas a poucos cents, abertas no pan. Uma corda só é parada;
        // o par bate suavemente contra si como uma de verdade.
        val detunes: List<Double> = if (parameters.doubling) listOf(-0.035, 0.035) else listOf(0.0)
        val left = FloatArray(length)
        val right = FloatArray(length)

        for ((index, cents) in detunes.withIndex()) {
            val stringFrequency = frequency * 2.0.pow(cents / 100 / 12)
            val voice = pluck(
                frequency = stringFrequency, length = length, velocity = vel,
                parameters = parameters,
                seed = (frequency * 17).toULong() + (index * 7919).toULong(),
                releaseStart = (duration * sampleRate).toInt(), sampleRate = sampleRate,
            )
            AudioDSP.removeDC(voice)

            val pan = if (detunes.size > 1) {
                if (index == 0) -parameters.stereoSpread else parameters.stereoSpread
            } else {
                0f
            }
            val angle = (pan + 1) * Math.PI.toFloat() / 4
            val gainL = cos(angle) * 1.41421356f
            val gainR = sin(angle) * 1.41421356f
            for (i in 0 until length) {
                left[i] += voice[i] * gainL / detunes.size
                right[i] += voice[i] * gainR / detunes.size
            }
        }

        // Amp e gabinete para o modelo com drive: distorção depois da corda,
        // gabinete depois da distorção, senão vira chiado.
        if (parameters.distortion > 0) {
            for (buffer in listOf(left, right)) {
                AudioDSP.saturate(buffer, drive = parameters.distortion.toFloat(), mix = 1f)
                AudioDSP.Biquad(AudioDSP.Biquad.Kind.Lowpass, 4200.0, 0.8, sampleRate).process(buffer)
                AudioDSP.Biquad(AudioDSP.Biquad.Kind.Peaking, 2100.0, 1.1, sampleRate, gainDB = 4.0).process(buffer)
                AudioDSP.Biquad(AudioDSP.Biquad.Kind.Highpass, 95.0, 0.7, sampleRate).process(buffer)
            }
        }

        // Ressonâncias do corpo.
        for ((freq, q, gainDB) in parameters.body) {
            AudioDSP.Biquad(AudioDSP.Biquad.Kind.Peaking, freq, q, sampleRate, gainDB).process(left)
            AudioDSP.Biquad(AudioDSP.Biquad.Kind.Peaking, freq, q, sampleRate, gainDB).process(right)
        }

        // Slap vive de compressão: o polegar e o pop têm que sentar no mesmo
        // nível das notas entre eles.
        if (parameters.compress) {
            AudioDSP.compress(left, -18f, 4.5f, 0.003, 0.09, 5f, sampleRate)
            AudioDSP.compress(right, -18f, 4.5f, 0.003, 0.09, 5f, sampleRate)
        }

        val out = StereoBuffer(left, right)
        val peak = out.peak
        if (peak > 0) {
            out.applyGain(parameters.level * gain * (0.42f + 0.58f * vel) / peak)
        }
        out.trimTail()
        return out
    }

    // MARK: a corda em si

    private fun pluck(
        frequency: Double,
        length: Int,
        velocity: Float,
        parameters: Parameters,
        seed: ULong,
        releaseStart: Int,
        sampleRate: Double,
    ): FloatArray {
        // Filtro de amortecimento do laço: corda mais brilhante mantém mais
        // agudo a cada volta.
        val dampingCutoff = minOf(sampleRate * 0.45, frequency * parameters.damping + 400)
        val a = exp(-2 * Math.PI * dampingCutoff / sampleRate)

        // Afinação: o filtro do laço atrasa o sinal, então a linha de atraso
        // encurta exatamente esse tanto (atraso de fase do one-pole na
        // fundamental).
        val omega = 2 * Math.PI * frequency / sampleRate
        val filterDelay = atan2(a * sin(omega), 1 - a * cos(omega)) / omega
        val totalDelay = maxOf(2.5, sampleRate / frequency - filterDelay)
        val delayInt = totalDelay.toInt()
        // O laço lê e escreve no mesmo slot: buffer de L guarda L amostras e
        // o slot seguinte guarda L-1. A mistura dá `L - peso`, então o peso é
        // o COMPLEMENTO da parte fracionária. Invertido (e com buffer de dois
        // slots a mais), as cordas tocavam um sexto de semitom abaixo.
        val interpolationWeight = (1 - (totalDelay - delayInt)).toFloat()
        val lineLength = delayInt + 1

        // Excitação: ruído filtrado, penteado por onde a palheta cai.
        val excitation = AudioDSP.whiteNoise(lineLength, seed)
        AudioDSP.Biquad(
            AudioDSP.Biquad.Kind.Lowpass,
            minOf(sampleRate * 0.45, frequency * parameters.pickFilter),
            0.6,
            sampleRate,
        ).process(excitation)
        // Comb raso: profundo demais também REFORÇA os harmônicos entre os
        // nós; a 0,85 o segundo harmônico saiu mais alto que a fundamental no
        // baixo de palheta, e o ouvido (e o detector) subiu uma oitava.
        val combOffset = maxOf(1, (delayInt * parameters.pickPosition).toInt())
        val combed = excitation.copyOf()
        for (i in combOffset until combed.size) {
            combed[i] -= excitation[i - combOffset] * parameters.combDepth
        }

        val line = combed
        var writeIndex = 0
        val out = FloatArray(length)
        var filterState = 0f
        var feedback = parameters.feedback.toFloat()
        val aFloat = a.toFloat()

        for (i in 0 until length) {
            // Leitura fracionária mantém o pitch exato entre amostras.
            val readIndex = writeIndex
            val nextIndex = (writeIndex + 1) % line.size
            val sample = line[readIndex] * (1 - interpolationWeight) +
                line[nextIndex] * interpolationWeight
            out[i] = sample

            // O abafador cai quando a nota é solta.
            if (i == releaseStart) feedback *= 0.55f

            filterState = sample * (1 - aFloat) + filterState * aFloat
            line[readIndex] = filterState * feedback
            writeIndex = nextIndex
        }

        // Transiente de ataque: palheta em corda entorchada, ou carne do dedo.
        val attack = AudioDSP.whiteNoise((0.012 * sampleRate).toInt(), seed + 977uL)
        AudioDSP.Biquad(
            AudioDSP.Biquad.Kind.Bandpass, parameters.attackTone, 0.9, sampleRate,
        ).process(attack)
        AudioDSP.applyPercussiveEnvelope(
            attack, attack = 0.0002, decay = 0.008,
            peak = parameters.attackNoise * velocity, sampleRate = sampleRate,
        )
        AudioDSP.normalize(out, 0.8f)
        AudioDSP.add(attack, out, atFrame = 0)

        // Batida do polegar no slap: um toque grave antes de a nota falar.
        if (parameters.thump > 0) {
            val thump = FloatArray((0.05 * sampleRate).toInt())
            var phase = 0.0
            for (i in thump.indices) {
                phase += (frequency * 0.5) / sampleRate
                thump[i] = sin(2 * Math.PI * phase).toFloat()
            }
            AudioDSP.applyPercussiveEnvelope(
                thump, attack = 0.0006, decay = 0.03,
                peak = parameters.thump * velocity, sampleRate = sampleRate,
            )
            AudioDSP.add(thump, out, atFrame = 0)
        }

        // Fade de release para nota parada não estalar.
        val fadeSamples = maxOf(1, (0.12 * sampleRate).toInt())
        if (releaseStart < out.size) {
            for (i in releaseStart until out.size) {
                val t = (i - releaseStart).toFloat() / fadeSamples
                out[i] *= if (t >= 1) 0f else (1 - t)
            }
        }
        AudioDSP.deClick(out, sampleRate, milliseconds = 0.8)
        return out
    }

    // MARK: voicing

    private class Parameters(
        val damping: Double,
        val feedback: Double,
        val pickPosition: Double,
        /** Quanto o comb da palheta morde. Baixos ficam suaves. */
        val combDepth: Float = 0.55f,
        val pickFilter: Double,
        val attackNoise: Float,
        val attackTone: Double,
        val body: List<Triple<Double, Double, Double>>,
        val distortion: Double = 0.0,
        val stereoSpread: Float = 0.25f,
        val doubling: Boolean = true,
        val compress: Boolean = false,
        val thump: Float = 0f,
        val level: Float = 0.8f,
    )

    private fun parameters(model: Model, velocity: Float): Parameters {
        // Toque mais brilhante mantém o laço aberto um pouco mais.
        val brightness = 1 + velocity * 0.6
        return when (model) {
            Model.GuitarClean -> Parameters(
                damping = 9 * brightness, feedback = 0.9965, pickPosition = 0.22,
                pickFilter = 8.0, attackNoise = 0.16f, attackTone = 3200.0,
                body = listOf(Triple(240.0, 0.8, -2.0), Triple(2600.0, 0.9, 3.0)),
                stereoSpread = 0.22f, level = 0.8f,
            )
            Model.GuitarAcoustic -> Parameters(
                damping = 11 * brightness, feedback = 0.997, pickPosition = 0.16,
                pickFilter = 10.0, attackNoise = 0.26f, attackTone = 4200.0,
                body = listOf(
                    Triple(104.0, 1.4, 7.0), Triple(208.0, 1.1, 5.0),
                    Triple(400.0, 1.0, 3.0), Triple(2800.0, 0.8, 3.0),
                ),
                stereoSpread = 0.32f, level = 0.82f,
            )
            Model.GuitarNylon -> Parameters(
                damping = 5.5 * brightness, feedback = 0.9955, pickPosition = 0.2,
                pickFilter = 4.5, attackNoise = 0.1f, attackTone = 1800.0,
                body = listOf(Triple(96.0, 1.3, 6.0), Triple(190.0, 1.2, 4.0), Triple(430.0, 1.0, 2.0)),
                stereoSpread = 0.28f, level = 0.82f,
            )
            Model.GuitarJazz -> Parameters(
                damping = 3.6 * brightness, feedback = 0.996, pickPosition = 0.42,
                pickFilter = 3.2, attackNoise = 0.08f, attackTone = 1400.0,
                body = listOf(Triple(150.0, 1.2, 4.0), Triple(320.0, 1.0, 3.0), Triple(1500.0, 0.7, -3.0)),
                stereoSpread = 0.2f, level = 0.84f,
            )
            Model.GuitarDistorted -> Parameters(
                damping = 8 * brightness, feedback = 0.9975, pickPosition = 0.14,
                pickFilter = 7.0, attackNoise = 0.14f, attackTone = 3000.0,
                body = listOf(Triple(180.0, 0.9, 2.0), Triple(900.0, 0.8, -3.0)),
                distortion = 7.5, stereoSpread = 0.3f, level = 0.62f,
            )
            Model.BassFingered -> Parameters(
                damping = 3.2 * brightness, feedback = 0.9985, pickPosition = 0.3,
                combDepth = 0.32f, pickFilter = 3.5, attackNoise = 0.09f, attackTone = 900.0,
                body = listOf(Triple(70.0, 1.1, 5.0), Triple(180.0, 0.9, 2.0), Triple(700.0, 0.8, -2.0)),
                stereoSpread = 0.12f, doubling = false, level = 0.88f,
            )
            Model.BassPicked -> Parameters(
                damping = 6 * brightness, feedback = 0.998, pickPosition = 0.18,
                combDepth = 0.3f, pickFilter = 6.0, attackNoise = 0.28f, attackTone = 2600.0,
                body = listOf(Triple(75.0, 1.1, 4.0), Triple(200.0, 0.9, 2.0), Triple(1800.0, 0.8, 2.0)),
                stereoSpread = 0.12f, doubling = false, level = 0.85f,
            )
            Model.BassSlap -> Parameters(
                damping = 9 * brightness, feedback = 0.9975, pickPosition = 0.1,
                combDepth = 0.4f, pickFilter = 9.0, attackNoise = 0.34f, attackTone = 3400.0,
                body = listOf(Triple(80.0, 1.2, 4.0), Triple(900.0, 0.9, 4.0), Triple(2600.0, 1.0, 5.0)),
                stereoSpread = 0.14f, doubling = false, compress = true,
                thump = 0.35f, level = 0.8f,
            )
        }
    }
}
