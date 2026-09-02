package com.levelhard.cadentia.kit

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Síntese de bateria em alta definição — port 1:1 do `DrumKitHD.swift`.
 *
 * O motor anterior era um port direto dos geradores TR-808 do web: mono, um
 * render fixo por pad, pratos de ruído rosa filtrado. Lia como brinquedo.
 * Este modela os instrumentos:
 *
 * - **Membranas** (bumbo, caixa, tons, congas): bancos modais nas razões de
 *   Bessel de uma pele circular (1, 1.59, 2.14, 2.30, 2.65, 2.92), batidas
 *   por um pulso de baqueta e vergadas por um envelope de pitch.
 * - **Metal** (chimbais, ride, crash, cowbell): banco inarmônico denso.
 * - **Velocity** muda o timbre, não só o nível.
 * - **Round robin**: quatro variações determinísticas por pad.
 * - **Estéreo**: o kit tem lugar na imagem (chimbal à direita, tons na
 *   frente, pratos abertos).
 */
object DrumKitHD {
    /** Quantos renders distintos existem por pad. */
    const val roundRobinCount = 4

    /** Renderiza uma batida. `velocity` 0…1; `variation` escolhe o slot. */
    fun render(
        kit: String,
        pad: String,
        velocity: Float = 0.85f,
        variation: Int = 0,
        sampleRate: Double,
        gain: Float = 1f,
    ): StereoBuffer {
        val vel = velocity.coerceIn(0.05f, 1f)
        val seed = seedFor(kit, pad, variation)
        val rng = AudioDSP.Rng(seed)
        // Jitter do round robin: pequeno o bastante para ler como humano,
        // grande o bastante para quebrar o efeito metralhadora.
        val pitchJitter = (1 + rng.jitter(0.018f)).toDouble()
        val decayJitter = (1 + rng.jitter(0.07f)).toDouble()
        val levelJitter = 1 + rng.jitter(0.06f)
        val panJitter = rng.jitter(0.05f)

        val piece = piece(kit, pad)
        val mono = renderPiece(piece, vel, seed, pitchJitter, decayJitter, sampleRate)
        if (mono.isEmpty()) return StereoBuffer(0)

        AudioDSP.removeDC(mono)
        AudioDSP.deClick(mono, sampleRate, milliseconds = 0.6)

        // Gain staging: toda peça renderiza a unidade primeiro e depois toma
        // o lugar dela na mistura do kit.
        AudioDSP.normalize(mono, 1f)
        val level = gain * levelJitter * piece.level * velocityGain(vel)
        val out = StereoBuffer.panned(mono, pan = piece.pan + panJitter, gain = level)
        if (piece.width > 0) {
            out.widen(byFrames = (piece.width * sampleRate).toInt(), amount = 0.55f)
        }
        out.trimTail()
        return out
    }

    // MARK: definição das peças

    /** O que um pad é em cada kit (o mesmo id → instrumento físico diferente). */
    internal class Piece(
        val body: Body,
        val pan: Float = 0f,
        /** Pico da peça na mistura do kit em velocity cheia. */
        val level: Float = 1f,
        /** Alargamento Haas em segundos (0 = centrado). */
        val width: Double = 0.0,
    ) {
        sealed interface Body {
            data class Kick(val tuning: Double, val sweep: Double, val punch: Float, val sub: Boolean) : Body
            data class Snare(val tuning: Double, val wires: Float, val wood: Boolean) : Body
            data class Tom(val tuning: Double, val resonance: Double) : Body
            data class Conga(val tuning: Double, val slap: Boolean) : Body
            data class Hat(val open: Boolean, val size: Double) : Body
            data class Ride(val bell: Boolean) : Body
            data class Crash(val size: Double) : Body
            data class Cowbell(val tuning: Double) : Body
            data class Shaker(val bright: Double) : Body
            data object Clap : Body
            data class Rim(val tuning: Double) : Body
            data class Timbale(val tuning: Double) : Body
        }
    }

    /** Velocity → nível, curvada para o toque leve ter espaço. */
    /** A curva de dinâmica da síntese; pública para o caminho de erro de `DrumSynth.renderStereo` desfazer o acento do chamador. */
    fun velocityGain(velocity: Float): Float = 0.22f + 0.78f * velocity.pow(1.35f)

    internal fun piece(kit: String, pad: String): Piece = when {
        // bumbo
        kit == "electronic" && pad == "kick" ->
            Piece(Piece.Body.Kick(52.0, 0.11, 0.5f, sub = true), level = 0.76f)
        kit == "latin" && pad == "kick" ->
            // Surdo: maior, mais amadeirado, menos sub que um bumbo de rock.
            Piece(Piece.Body.Kick(62.0, 0.05, 0.75f, sub = false), level = 0.68f)
        pad == "kick" ->
            Piece(Piece.Body.Kick(48.0, 0.035, 1f, sub = false), level = 0.72f)

        // caixa
        kit == "latin" && pad == "snare" ->
            Piece(Piece.Body.Timbale(330.0), pan = -0.12f, level = 0.58f)
        kit == "electronic" && pad == "snare" ->
            Piece(Piece.Body.Snare(210.0, 0.85f, wood = false), pan = -0.05f, level = 0.62f)
        pad == "snare" ->
            Piece(Piece.Body.Snare(185.0, 1f, wood = true), pan = -0.05f, level = 0.64f, width = 0.004)

        // chimbais
        kit == "latin" && pad == "hihat-c" ->
            Piece(Piece.Body.Shaker(1.0), pan = 0.3f, level = 0.26f)
        kit == "latin" && pad == "hihat-o" ->
            Piece(Piece.Body.Shaker(0.7), pan = 0.32f, level = 0.3f)
        kit == "electronic" && pad == "hihat-c" ->
            Piece(Piece.Body.Hat(open = false, size = 0.85), pan = 0.32f, level = 0.29f)
        kit == "electronic" && pad == "hihat-o" ->
            Piece(Piece.Body.Hat(open = true, size = 0.85), pan = 0.32f, level = 0.31f)
        pad == "hihat-c" ->
            Piece(Piece.Body.Hat(open = false, size = 1.0), pan = 0.35f, level = 0.3f)
        pad == "hihat-o" ->
            Piece(Piece.Body.Hat(open = true, size = 1.0), pan = 0.35f, level = 0.32f, width = 0.006)

        // pratos
        kit == "latin" && pad == "crash" ->
            Piece(Piece.Body.Cowbell(555.0), pan = 0.2f, level = 0.42f)
        pad == "crash" ->
            Piece(Piece.Body.Crash(1.0), pan = -0.42f, level = 0.48f, width = 0.011)
        kit == "latin" && pad == "ride" ->
            Piece(Piece.Body.Cowbell(720.0), pan = 0.4f, level = 0.38f)
        pad == "ride" ->
            Piece(Piece.Body.Ride(bell = false), pan = 0.44f, level = 0.36f, width = 0.008)

        // percussão de mão
        pad == "clap" -> Piece(Piece.Body.Clap, pan = 0.1f, level = 0.52f, width = 0.007)
        pad == "rim" -> Piece(Piece.Body.Rim(780.0), pan = -0.18f, level = 0.46f)

        // tons
        kit == "acoustic" && pad == "tom-low" ->
            Piece(Piece.Body.Tom(92.0, 1.0), pan = -0.3f, level = 0.62f)
        kit == "acoustic" && pad == "tom-mid" ->
            Piece(Piece.Body.Tom(132.0, 0.92), pan = 0f, level = 0.6f)
        kit == "acoustic" && pad == "tom-high" ->
            Piece(Piece.Body.Tom(188.0, 0.85), pan = 0.26f, level = 0.58f)
        kit == "electronic" && pad == "tom-low" ->
            Piece(Piece.Body.Tom(68.0, 1.35), pan = -0.3f, level = 0.62f)
        kit == "electronic" && pad == "tom-mid" ->
            Piece(Piece.Body.Tom(104.0, 1.25), pan = 0f, level = 0.6f)
        kit == "electronic" && pad == "tom-high" ->
            Piece(Piece.Body.Tom(158.0, 1.15), pan = 0.26f, level = 0.58f)
        kit == "latin" && pad == "tom-low" ->
            Piece(Piece.Body.Conga(108.0, slap = false), pan = -0.24f, level = 0.56f)
        kit == "latin" && pad == "tom-mid" ->
            Piece(Piece.Body.Conga(176.0, slap = false), pan = 0.02f, level = 0.54f)
        kit == "latin" && pad == "tom-high" ->
            Piece(Piece.Body.Conga(268.0, slap = true), pan = 0.24f, level = 0.52f)

        // cowbell e shaker
        kit == "latin" && pad == "cowbell" ->
            Piece(Piece.Body.Cowbell(540.0), pan = 0.16f, level = 0.46f)
        pad == "cowbell" -> Piece(Piece.Body.Cowbell(540.0), pan = 0.16f, level = 0.4f)
        kit == "latin" && pad == "shaker" ->
            Piece(Piece.Body.Shaker(1.15), pan = -0.28f, level = 0.3f)
        pad == "shaker" -> Piece(Piece.Body.Shaker(1.0), pan = -0.28f, level = 0.26f)

        // congas
        kit == "electronic" && pad == "conga-low" ->
            Piece(Piece.Body.Conga(124.0, slap = false), pan = -0.2f, level = 0.56f)
        kit == "electronic" && pad == "conga-mid" ->
            Piece(Piece.Body.Conga(184.0, slap = false), pan = 0.04f, level = 0.54f)
        kit == "electronic" && pad == "conga-high" ->
            Piece(Piece.Body.Conga(262.0, slap = true), pan = 0.22f, level = 0.52f)
        pad == "conga-low" -> Piece(Piece.Body.Conga(142.0, slap = false), pan = -0.2f, level = 0.56f)
        pad == "conga-mid" -> Piece(Piece.Body.Conga(214.0, slap = false), pan = 0.04f, level = 0.54f)
        pad == "conga-high" -> Piece(Piece.Body.Conga(306.0, slap = true), pan = 0.22f, level = 0.52f)

        else -> Piece(Piece.Body.Rim(700.0), level = 0f)
    }

    // MARK: renderizadores

    private fun renderPiece(
        piece: Piece,
        velocity: Float,
        seed: ULong,
        pitchScale: Double,
        decayScale: Double,
        sampleRate: Double,
    ): FloatArray = when (val body = piece.body) {
        is Piece.Body.Kick -> kick(
            body.tuning * pitchScale, body.sweep, body.punch, body.sub,
            velocity, decayScale, seed, sampleRate,
        )
        is Piece.Body.Snare -> snare(
            body.tuning * pitchScale, body.wires, body.wood, velocity, decayScale, seed, sampleRate,
        )
        is Piece.Body.Tom -> tom(body.tuning * pitchScale, body.resonance * decayScale, velocity, seed, sampleRate)
        is Piece.Body.Conga -> conga(body.tuning * pitchScale, body.slap, velocity, decayScale, seed, sampleRate)
        is Piece.Body.Hat -> hat(body.open, body.size * pitchScale, velocity, decayScale, seed, sampleRate)
        is Piece.Body.Ride -> ride(body.bell, velocity, pitchScale, decayScale, seed, sampleRate)
        is Piece.Body.Crash -> crash(body.size * pitchScale, velocity, decayScale, seed, sampleRate)
        is Piece.Body.Cowbell -> cowbell(body.tuning * pitchScale, velocity, decayScale, sampleRate)
        is Piece.Body.Shaker -> shaker(body.bright, velocity, decayScale, seed, sampleRate)
        is Piece.Body.Clap -> clap(velocity, seed, sampleRate)
        is Piece.Body.Rim -> rim(body.tuning * pitchScale, velocity, seed, sampleRate)
        is Piece.Body.Timbale -> timbale(body.tuning * pitchScale, velocity, decayScale, seed, sampleRate)
    }

    /** Bumbo: fundamental varrida, clique do batedor e modos do casco. */
    private fun kick(
        tuning: Double, sweep: Double, punch: Float, sub: Boolean,
        velocity: Float, decayScale: Double, seed: ULong, sampleRate: Double,
    ): FloatArray {
        val decay = (if (sub) 0.72 else 0.42) * decayScale * (0.8 + 0.3 * velocity)
        val length = ((decay + 0.25) * sampleRate).toInt()
        val out = FloatArray(length)

        // Corpo: o pitch da pele cai da tensão do golpe até a afinação.
        val startFreq = tuning * (if (sub) 3.4 else 3.9)
        var phase = 0.0
        for (i in 0 until length) {
            val t = i / sampleRate
            val bend = if (t >= sweep) 0.0 else (1 - t / sweep).pow(2)
            val freq = tuning + (startFreq - tuning) * bend
            phase += freq / sampleRate
            out[i] = sin(2 * Math.PI * phase).toFloat()
        }
        AudioDSP.applyPercussiveEnvelope(out, attack = 0.0008, decay = decay, peak = 0.92f, sampleRate = sampleRate)

        // Clique do batedor: o que faz o bumbo cortar em alto-falante pequeno.
        val click = AudioDSP.whiteNoise((0.02 * sampleRate).toInt(), seed + 11uL)
        AudioDSP.Biquad(
            AudioDSP.Biquad.Kind.Bandpass, 2600 + velocity * 1800.0, 0.8, sampleRate,
        ).process(click)
        AudioDSP.applyPercussiveEnvelope(
            click, attack = 0.0003, decay = 0.012,
            peak = punch * (0.06f + 0.72f * velocity * velocity), sampleRate = sampleRate,
        )
        AudioDSP.add(click, out, atFrame = 0)

        // Casco: modos graves curtos dão tamanho ao tambor.
        val shell = AudioDSP.renderModes(
            listOf(
                AudioDSP.Mode(tuning * 2.4, 0.06 * decayScale, 0.5f),
                AudioDSP.Mode(tuning * 4.1, 0.035 * decayScale, 0.3f),
            ),
            excitation = malletPulse((0.004 * sampleRate).toInt()),
            sampleRate = sampleRate, length = length,
        )
        AudioDSP.add(shell, out, atFrame = 0, gain = 0.5f * punch)

        AudioDSP.saturate(out, drive = 1.5f + velocity, mix = 0.7f)
        return out
    }

    /** Caixa: duas peles desafinadas entre si + a esteira por baixo. */
    private fun snare(
        tuning: Double, wires: Float, wood: Boolean, velocity: Float,
        decayScale: Double, seed: ULong, sampleRate: Double,
    ): FloatArray {
        val headDecay = 0.13 * decayScale
        val wireDecay = (0.14 + 0.1 * velocity) * decayScale
        val length = ((wireDecay + 0.3) * sampleRate).toInt()

        // Pele de cima + resposta desafinada ~7%: o "wobble" curto da caixa.
        val ratios = listOf(
            Triple(1.0, 1.0f, 1.0), Triple(1.59, 0.55f, 0.65), Triple(2.14, 0.38f, 0.45),
            Triple(2.30, 0.28f, 0.4), Triple(2.65, 0.2f, 0.3), Triple(2.92, 0.14f, 0.25),
        )
        val modes = mutableListOf<AudioDSP.Mode>()
        for ((ratio, amplitude, decayFactor) in ratios) {
            modes.add(AudioDSP.Mode(tuning * ratio, headDecay * decayFactor, amplitude))
            modes.add(AudioDSP.Mode(tuning * ratio * 1.072, headDecay * decayFactor * 0.8, amplitude * 0.6f))
        }
        val head = AudioDSP.renderModes(
            modes,
            excitation = malletPulse(strikeLength(0.0025, velocity, sampleRate)),
            sampleRate = sampleRate, length = length,
        )
        AudioDSP.normalize(head, 0.55f)

        // Esteira: ruído brilhante com modulação de chocalho; golpe forte
        // empurra muito mais esteira e menos pele, como o instrumento real.
        val wire = AudioDSP.whiteNoise(length, seed + 23uL)
        AudioDSP.Biquad(
            AudioDSP.Biquad.Kind.Highpass, 900 + velocity * 2000.0, 0.7, sampleRate,
        ).process(wire)
        AudioDSP.Biquad(AudioDSP.Biquad.Kind.Peaking, 4800.0, 0.9, sampleRate, gainDB = 5.0).process(wire)
        val rattle = AudioDSP.Rng(seed + 71uL)
        for (i in wire.indices) {
            val modulation = 1 + 0.22f * rattle.nextUniform() *
                minOf(1.0, i / (0.02 * sampleRate)).toFloat()
            wire[i] *= modulation
        }
        AudioDSP.applyPercussiveEnvelope(
            wire, attack = 0.0006, decay = wireDecay,
            peak = wires * (0.12f + 0.85f * velocity * velocity), sampleRate = sampleRate,
        )

        val out = AudioDSP.mix(listOf(head, wire))

        // Estalo de baqueta no aro: o transiente que diz "madeira".
        if (wood) {
            val crack = AudioDSP.whiteNoise((0.01 * sampleRate).toInt(), seed + 37uL)
            AudioDSP.Biquad(AudioDSP.Biquad.Kind.Bandpass, 6200.0, 1.4, sampleRate).process(crack)
            AudioDSP.applyPercussiveEnvelope(
                crack, attack = 0.0002, decay = 0.006,
                peak = 0.42f * velocity * velocity, sampleRate = sampleRate,
            )
            AudioDSP.add(crack, out, atFrame = 0)
        }

        AudioDSP.saturate(out, drive = 1.3f, mix = 0.45f)
        return out
    }

    /** Tom: modos de membrana circular + o bend de pitch descendente. */
    private fun tom(
        tuning: Double, resonance: Double, velocity: Float, seed: ULong, sampleRate: Double,
    ): FloatArray {
        val decay = (0.55 + 0.35 * velocity) * resonance
        val length = ((decay + 0.3) * sampleRate).toInt()

        val out = FloatArray(length)
        val bendDepth = 0.22
        val bendTime = 0.07
        var phase = 0.0
        for (i in 0 until length) {
            val t = i / sampleRate
            val bend = if (t >= bendTime) 0.0 else (1 - t / bendTime).pow(1.6)
            phase += (tuning * (1 + bendDepth * bend)) / sampleRate
            out[i] = sin(2 * Math.PI * phase).toFloat()
        }
        AudioDSP.applyPercussiveEnvelope(out, attack = 0.001, decay = decay, peak = 0.62f, sampleRate = sampleRate)

        val ratios = listOf(
            Triple(1.59, 0.42f, 0.55), Triple(2.14, 0.3f, 0.42), Triple(2.30, 0.22f, 0.36),
            Triple(2.65, 0.16f, 0.3), Triple(2.92, 0.12f, 0.24), Triple(3.16, 0.08f, 0.2),
        )
        val modes = ratios.map { (ratio, amplitude, factor) ->
            AudioDSP.Mode(tuning * ratio, decay * factor, amplitude * (0.6f + 0.6f * velocity))
        }
        val shell = AudioDSP.renderModes(
            modes,
            excitation = malletPulse(strikeLength(0.003, velocity, sampleRate)),
            sampleRate = sampleRate, length = length,
        )
        AudioDSP.normalize(shell, 0.4f)
        AudioDSP.add(shell, out, atFrame = 0)

        // Ataque de baqueta na pele.
        val attack = AudioDSP.whiteNoise((0.008 * sampleRate).toInt(), seed + 53uL)
        AudioDSP.Biquad(AudioDSP.Biquad.Kind.Bandpass, 3400.0, 1.0, sampleRate).process(attack)
        AudioDSP.applyPercussiveEnvelope(
            attack, attack = 0.0003, decay = 0.007, peak = 0.22f * velocity, sampleRate = sampleRate,
        )
        AudioDSP.add(attack, out, atFrame = 0)

        AudioDSP.saturate(out, drive = 1.25f, mix = 0.4f)
        return out
    }

    /** Conga/bongô: pele pequena de alta tensão; slap é abafado e brilhante. */
    private fun conga(
        tuning: Double, slap: Boolean, velocity: Float, decayScale: Double,
        seed: ULong, sampleRate: Double,
    ): FloatArray {
        val decay = (if (slap) 0.16 else 0.42) * decayScale
        val length = ((decay + 0.2) * sampleRate).toInt()
        val ratios = listOf(
            Triple(1.0, 1.0f, 1.0), Triple(1.5, 0.4f, 0.6), Triple(2.0, 0.26f, 0.45),
            Triple(2.45, 0.18f, 0.32), Triple(3.1, 0.1f, 0.22),
        )
        val modes = ratios.map { (ratio, amplitude, factor) ->
            AudioDSP.Mode(tuning * ratio, decay * factor, amplitude)
        }
        val out = AudioDSP.renderModes(
            modes,
            excitation = malletPulse(strikeLength(if (slap) 0.0012 else 0.0025, velocity, sampleRate)),
            sampleRate = sampleRate, length = length,
        )
        AudioDSP.normalize(out, 0.72f)

        val hand = AudioDSP.whiteNoise((0.012 * sampleRate).toInt(), seed + 67uL)
        AudioDSP.Biquad(
            AudioDSP.Biquad.Kind.Bandpass, if (slap) 5200.0 else 2200.0, 1.1, sampleRate,
        ).process(hand)
        AudioDSP.applyPercussiveEnvelope(
            hand, attack = 0.0002, decay = if (slap) 0.02 else 0.008,
            peak = (if (slap) 0.42f else 0.18f) * velocity, sampleRate = sampleRate,
        )
        AudioDSP.add(hand, out, atFrame = 0)
        return out
    }

    /** Chimbal: banco inarmônico denso + chiado, nunca só ruído filtrado. */
    private fun hat(
        open: Boolean, size: Double, velocity: Float, decayScale: Double,
        seed: ULong, sampleRate: Double,
    ): FloatArray {
        val decay = (if (open) 0.46 else 0.055) * decayScale * (0.75 + 0.4 * velocity)
        val length = ((decay + 0.12) * sampleRate).toInt()

        // Parciais inarmônicos na região de 3 a 12 kHz.
        val rng = AudioDSP.Rng(seed + 101uL)
        val modes = mutableListOf<AudioDSP.Mode>()
        for (index in 0 until 14) {
            val base = 3200.0 * size * 1.19.pow(index)
            val detune = (1 + rng.jitter(0.07f)).toDouble()
            modes.add(
                AudioDSP.Mode(
                    base * detune,
                    decay * (1 - index * 0.035),
                    0.85f / (1 + index / 3),
                ),
            )
        }
        val metal = AudioDSP.renderModes(
            modes,
            excitation = AudioDSP.whiteNoise((0.002 * sampleRate).toInt(), seed + 103uL),
            sampleRate = sampleRate, length = length,
        )
        AudioDSP.normalize(metal, 0.6f)

        // Chiado: o ar de banda larga entre os parciais.
        val sizzle = AudioDSP.whiteNoise(length, seed + 107uL)
        AudioDSP.Biquad(
            AudioDSP.Biquad.Kind.Highpass, 6000 + velocity * 2500.0, 0.7, sampleRate,
        ).process(sizzle)
        AudioDSP.applyPercussiveEnvelope(
            sizzle, attack = 0.0004, decay = decay * 0.8, peak = 0.34f, sampleRate = sampleRate,
        )

        val out = AudioDSP.mix(listOf(metal, sizzle))
        // Fechado leva um choke extra para a linha de semicolcheias ficar seca.
        if (!open) {
            AudioDSP.Biquad(AudioDSP.Biquad.Kind.Highpass, 4200.0, 0.7, sampleRate).process(out)
        }
        // Toque leve é visivelmente mais escuro.
        AudioDSP.Biquad(
            AudioDSP.Biquad.Kind.Lowpass, 5000 + velocity * 13000.0, 0.6, sampleRate,
        ).process(out)
        return out
    }

    /** Ride: ping claro de baqueta sobre um wash longo. */
    private fun ride(
        bell: Boolean, velocity: Float, pitchScale: Double, decayScale: Double,
        seed: ULong, sampleRate: Double,
    ): FloatArray {
        val decay = 2.1 * decayScale
        val length = ((decay + 0.4) * sampleRate).toInt()

        // Espaçamento irregular, ou o wash vira um pitch e briga com o ping.
        val rng = AudioDSP.Rng(seed + 211uL)
        val modes = mutableListOf<AudioDSP.Mode>()
        var frequency = 420.0 * pitchScale
        for (index in 0 until 34) {
            frequency *= 1.08 + rng.next01() * 0.2
            if (frequency >= sampleRate / 2.2) break
            modes.add(
                AudioDSP.Mode(
                    frequency,
                    decay * (1 - index * 0.02),
                    0.45f / (1 + index / 6),
                    phase = rng.next01().toDouble(),
                ),
            )
        }
        val metal = AudioDSP.renderModes(
            modes,
            excitation = AudioDSP.whiteNoise((0.0035 * sampleRate).toInt(), seed + 213uL),
            sampleRate = sampleRate, length = length,
        )
        AudioDSP.normalize(metal, 0.3f)

        // Ar por baixo do metal, como um ride real sustenta.
        val air = AudioDSP.whiteNoise(length, seed + 217uL)
        AudioDSP.Biquad(AudioDSP.Biquad.Kind.Highpass, 2200.0, 0.6, sampleRate).process(air)
        AudioDSP.applyPercussiveEnvelope(
            air, attack = 0.003, decay = decay * 0.7, peak = 0.3f, sampleRate = sampleRate,
        )
        for (i in air.indices) {
            air[i] *= 1 + 0.14f * AudioDSP.lfo(i, 4.1, sampleRate)
        }
        val wash = AudioDSP.mix(listOf(metal, air))
        AudioDSP.normalize(wash, 0.42f)

        // O ping: golpe curto e definido por cima do wash.
        val ping = AudioDSP.renderModes(
            listOf(
                AudioDSP.Mode(1180 * pitchScale, 0.16, 1f),
                AudioDSP.Mode(2360 * pitchScale, 0.1, 0.5f),
                AudioDSP.Mode(3510 * pitchScale, 0.07, 0.3f),
            ),
            excitation = malletPulse((0.0012 * sampleRate).toInt()),
            sampleRate = sampleRate, length = length,
        )
        AudioDSP.normalize(ping, if (bell) 0.7f else 0.45f * (0.6f + 0.6f * velocity))

        return AudioDSP.mix(listOf(wash, ping))
    }

    /**
     * Crash: um wash de ar colorido por metal, florescendo em vez de nascer
     * no pico. Os parciais andam por passos irregulares (razão constante é
     * série regular, série regular é período, período é PITCH: a primeira
     * versão saiu um sino e o próprio YIN leu 133 Hz com confiança).
     */
    private fun crash(
        size: Double, velocity: Float, decayScale: Double, seed: ULong, sampleRate: Double,
    ): FloatArray {
        val decay = (2.6 + velocity) * decayScale
        val length = ((decay + 0.5) * sampleRate).toInt()

        val rng = AudioDSP.Rng(seed + 307uL)
        val modes = mutableListOf<AudioDSP.Mode>()
        var frequency = 340.0 / size
        for (index in 0 until 48) {
            frequency *= 1.07 + rng.next01() * 0.23
            if (frequency >= sampleRate / 2.2) break
            // O crash vive entre 1 e 6 kHz; abaixo disso, atenua.
            val tilt = if (frequency < 900) (frequency / 900).toFloat() else 1f
            modes.add(
                AudioDSP.Mode(
                    frequency,
                    decay * (1 - index * 0.014),
                    tilt * 0.5f / (1 + index / 8),
                    phase = rng.next01().toDouble(),
                ),
            )
        }
        val metal = AudioDSP.renderModes(
            modes,
            excitation = AudioDSP.whiteNoise((0.006 * sampleRate).toInt(), seed + 311uL),
            sampleRate = sampleRate, length = length,
        )
        AudioDSP.normalize(metal, 0.42f)

        // O wash: a maior parte do som.
        val wash = AudioDSP.whiteNoise(length, seed + 313uL)
        AudioDSP.Biquad(AudioDSP.Biquad.Kind.Highpass, 900.0, 0.6, sampleRate).process(wash)
        AudioDSP.Biquad(AudioDSP.Biquad.Kind.Peaking, 3200.0, 0.5, sampleRate, gainDB = 5.0).process(wash)
        AudioDSP.Biquad(AudioDSP.Biquad.Kind.HighShelf, 8000.0, 0.7, sampleRate, gainDB = 3.0).process(wash)
        AudioDSP.applyPercussiveEnvelope(
            wash, attack = 0.004, decay = decay * 0.85, peak = 0.72f, sampleRate = sampleRate,
        )
        // Shimmer: a cauda respira em vez de decair reta.
        for (i in wash.indices) {
            val slow = AudioDSP.lfo(i, 3.3, sampleRate)
            val fast = AudioDSP.lfo(i, 7.9, sampleRate, phase = 0.3)
            wash[i] *= 1 + 0.16f * slow + 0.09f * fast
        }

        val out = AudioDSP.mix(listOf(metal, wash))

        // Swell: 12 ms de floração em vez de pico instantâneo.
        val swell = maxOf(1, (0.012 * sampleRate).toInt())
        for (i in 0 until minOf(swell, out.size)) {
            out[i] *= i.toFloat() / swell
        }
        return out
    }

    /** Cowbell/agogô: dois modos inarmônicos de metal, zero ruído. */
    private fun cowbell(
        tuning: Double, velocity: Float, decayScale: Double, sampleRate: Double,
    ): FloatArray {
        val decay = 0.32 * decayScale
        val length = ((decay + 0.15) * sampleRate).toInt()
        val out = AudioDSP.renderModes(
            listOf(
                AudioDSP.Mode(tuning, decay, 1f),
                AudioDSP.Mode(tuning * 1.482, decay * 0.85, 0.8f),
                AudioDSP.Mode(tuning * 2.68, decay * 0.4, 0.32f),
                AudioDSP.Mode(tuning * 3.41, decay * 0.25, 0.18f),
            ),
            excitation = malletPulse((0.0009 * sampleRate).toInt()),
            sampleRate = sampleRate, length = length,
        )
        AudioDSP.normalize(out, 0.62f * (0.7f + 0.4f * velocity))
        AudioDSP.saturate(out, drive = 1.4f, mix = 0.35f)
        return out
    }

    /** Shaker/cabasa: muitas continhas, envelope com grão. */
    private fun shaker(
        bright: Double, velocity: Float, decayScale: Double, seed: ULong, sampleRate: Double,
    ): FloatArray {
        val decay = 0.075 * decayScale
        val length = ((decay + 0.09) * sampleRate).toInt()
        val out = AudioDSP.whiteNoise(length, seed + 401uL)
        AudioDSP.Biquad(AudioDSP.Biquad.Kind.Bandpass, 7200 * bright, 1.1, sampleRate).process(out)
        AudioDSP.Biquad(AudioDSP.Biquad.Kind.HighShelf, 9000.0, 0.7, sampleRate, gainDB = 4.0).process(out)

        // Grão: as continhas não caem todas juntas.
        val rng = AudioDSP.Rng(seed + 409uL)
        for (i in out.indices) {
            out[i] *= 1 + 0.3f * rng.nextUniform()
        }
        AudioDSP.applyPercussiveEnvelope(
            out, attack = 0.0016, decay = decay,
            peak = 0.5f * (0.6f + 0.5f * velocity), sampleRate = sampleRate,
        )
        return out
    }

    /** Palma: várias mãos fora de sincronia + a sala em volta. */
    private fun clap(velocity: Float, seed: ULong, sampleRate: Double): FloatArray {
        val length = (0.42 * sampleRate).toInt()
        val out = FloatArray(length)
        val rng = AudioDSP.Rng(seed + 503uL)

        val offsets = doubleArrayOf(0.0, 0.009, 0.018, 0.029)
        for (index in 0 until 4) {
            val offset = offsets[index] * (1 + rng.jitter(0.15f))
            val burst = AudioDSP.whiteNoise((0.12 * sampleRate).toInt(), seed + index.toULong() + 509uL)
            AudioDSP.Biquad(
                AudioDSP.Biquad.Kind.Bandpass, 1250 * (1 + rng.jitter(0.1f)).toDouble(), 0.75, sampleRate,
            ).process(burst)
            AudioDSP.applyPercussiveEnvelope(
                burst, attack = 0.0004, decay = if (index == 3) 0.13 else 0.012,
                peak = (if (index == 3) 0.55f else 0.75f) * velocity, sampleRate = sampleRate,
            )
            AudioDSP.add(burst, out, atFrame = (offset * sampleRate).toInt())
        }

        // Cauda de sala: gente, não clique.
        val tail = AudioDSP.whiteNoise((0.3 * sampleRate).toInt(), seed + 521uL)
        AudioDSP.Biquad(AudioDSP.Biquad.Kind.Bandpass, 1800.0, 0.5, sampleRate).process(tail)
        AudioDSP.applyPercussiveEnvelope(
            tail, attack = 0.006, decay = 0.14, peak = 0.12f * velocity, sampleRate = sampleRate,
        )
        AudioDSP.add(tail, out, atFrame = (0.012 * sampleRate).toInt())
        return out
    }

    /** Cross stick / rim click: madeira em madeira, muito curto. */
    private fun rim(tuning: Double, velocity: Float, seed: ULong, sampleRate: Double): FloatArray {
        val length = (0.16 * sampleRate).toInt()
        val out = AudioDSP.renderModes(
            listOf(
                AudioDSP.Mode(tuning, 0.045, 1f),
                AudioDSP.Mode(tuning * 2.17, 0.03, 0.5f),
                AudioDSP.Mode(tuning * 3.6, 0.018, 0.25f),
            ),
            excitation = malletPulse((0.0006 * sampleRate).toInt()),
            sampleRate = sampleRate, length = length,
        )
        AudioDSP.normalize(out, 0.6f)

        val click = AudioDSP.whiteNoise((0.006 * sampleRate).toInt(), seed + 601uL)
        AudioDSP.Biquad(AudioDSP.Biquad.Kind.Bandpass, 4200.0, 1.6, sampleRate).process(click)
        AudioDSP.applyPercussiveEnvelope(
            click, attack = 0.0002, decay = 0.004, peak = 0.4f * velocity, sampleRate = sampleRate,
        )
        AudioDSP.add(click, out, atFrame = 0)
        return out
    }

    /** Timbale: casco de metal, afinação alta, quase sem sustento. */
    private fun timbale(
        tuning: Double, velocity: Float, decayScale: Double, seed: ULong, sampleRate: Double,
    ): FloatArray {
        val decay = 0.22 * decayScale
        val length = ((decay + 0.2) * sampleRate).toInt()
        val out = AudioDSP.renderModes(
            listOf(
                AudioDSP.Mode(tuning, decay, 1f),
                AudioDSP.Mode(tuning * 1.62, decay * 0.6, 0.45f),
                AudioDSP.Mode(tuning * 2.31, decay * 0.4, 0.3f),
                AudioDSP.Mode(tuning * 3.9, decay * 0.22, 0.18f),
            ),
            excitation = malletPulse((0.0012 * sampleRate).toInt()),
            sampleRate = sampleRate, length = length,
        )
        AudioDSP.normalize(out, 0.68f)

        val stick = AudioDSP.whiteNoise((0.01 * sampleRate).toInt(), seed + 701uL)
        AudioDSP.Biquad(AudioDSP.Biquad.Kind.Bandpass, 5600.0, 1.2, sampleRate).process(stick)
        AudioDSP.applyPercussiveEnvelope(
            stick, attack = 0.0002, decay = 0.008, peak = 0.35f * velocity, sampleRate = sampleRate,
        )
        AudioDSP.add(stick, out, atFrame = 0)
        return out
    }

    // MARK: ajudantes

    /** Golpe de cosseno levantado: controla a energia de modos altos do hit. */
    private fun malletPulse(length: Int): FloatArray {
        val count = maxOf(2, length)
        return FloatArray(count) { i ->
            val t = i.toDouble() / (count - 1)
            (0.5 * (1 - cos(2 * Math.PI * t))).toFloat()
        }
    }

    /**
     * Largura do golpe em samples para uma velocity: o toque leve espalha o
     * contato por mais tempo e excita menos modos altos.
     */
    private fun strikeLength(base: Double, velocity: Float, sampleRate: Double): Int {
        val widening = (2.1 - 1.1 * velocity).toDouble()
        return maxOf(2, (base * widening * sampleRate).toInt())
    }

    /** Semente estável por (kit, pad, variação) para o round robin reproduzir. */
    private fun seedFor(kit: String, pad: String, variation: Int): ULong {
        var hash: ULong = 0xCBF29CE484222325uL
        for (byte in "$kit/$pad".encodeToByteArray()) {
            hash = (hash xor byte.toUByte().toULong()) * 0x100000001B3uL
        }
        return hash + (abs(variation) % roundRobinCount).toULong() * 0x9E3779B97F4A7C15uL
    }
}
