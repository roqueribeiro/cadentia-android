package com.levelhard.cadentia.kit

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * FFT real sobre janela potência de dois — port do `RealFFT` do iOS.
 *
 * O vDSP escondia DC e Nyquist no mesmo slot e escalava por 2; o código iOS
 * desempacotava e re-escalava para o layout "um valor por bin" da DFT
 * padrão. Aqui a base é uma FFT complexa radix-2 iterativa própria e o
 * contrato é o MESMO layout final: `forward` devolve a DFT real exata
 * (X[k] = Σ x[n]·e^{-2πikn/N}, re/im de tamanho N/2+1, im[0] = im[N/2] = 0)
 * e `inverse` reconstrói o sinal com escala 1/N. Os fatores estão presos
 * pelo teste de paridade contra o PyTorch (fixtures verbatim do iOS).
 */
class RealFFT(val size: Int) {
    private val half = size / 2
    private val levels: Int
    private val cosTable: FloatArray
    private val sinTable: FloatArray

    init {
        require(size > 0 && size and (size - 1) == 0) { "FFT size must be a power of two" }
        levels = Integer.numberOfTrailingZeros(size)
        cosTable = FloatArray(half)
        sinTable = FloatArray(half)
        for (i in 0 until half) {
            cosTable[i] = cos(2 * Math.PI * i / size).toFloat()
            sinTable[i] = sin(2 * Math.PI * i / size).toFloat()
        }
    }

    /** FFT complexa in place (e^{-iωn}); a inversa usa o truque do conjugado. */
    private fun complexForward(re: FloatArray, im: FloatArray) {
        // Bit reversal.
        var j = 0
        for (i in 0 until size - 1) {
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
            var mask = size shr 1
            while (j and mask != 0) {
                j = j and mask.inv()
                mask = mask shr 1
            }
            j = j or mask
        }
        // Butterflies.
        var span = 1
        var step = half
        while (span < size) {
            var i = 0
            while (i < size) {
                var k = 0
                for (offset in i until i + span) {
                    val wr = cosTable[k]
                    val wi = -sinTable[k]
                    val target = offset + span
                    val tr = re[target] * wr - im[target] * wi
                    val ti = re[target] * wi + im[target] * wr
                    re[target] = re[offset] - tr
                    im[target] = im[offset] - ti
                    re[offset] += tr
                    im[offset] += ti
                    k += step
                }
                i += span shl 1
            }
            span = span shl 1
            step = step shr 1
        }
    }

    /** real[size] → re[size/2+1], im[size/2+1]. */
    fun forward(input: FloatArray, re: FloatArray, im: FloatArray) {
        val workRe = input.copyOf(size)
        val workIm = FloatArray(size)
        complexForward(workRe, workIm)
        for (k in 0..half) {
            re[k] = workRe[k]
            im[k] = workIm[k]
        }
        im[0] = 0f
        im[half] = 0f
    }

    /** re[size/2+1], im[size/2+1] → real[size], com a escala 1/size. */
    fun inverse(re: FloatArray, im: FloatArray, output: FloatArray) {
        // Espectro hermitiano completo, conjugado (iFFT = conj → FFT → conj/N).
        val workRe = FloatArray(size)
        val workIm = FloatArray(size)
        for (k in 0..half) {
            workRe[k] = re[k]
            workIm[k] = -im[k]
        }
        for (k in half + 1 until size) {
            workRe[k] = re[size - k]
            workIm[k] = im[size - k]
        }
        complexForward(workRe, workIm)
        val scale = 1f / size
        for (n in 0 until size) {
            output[n] = workRe[n] * scale
        }
    }
}

/**
 * Reflect padding, no sabor numpy/torch: a amostra da borda não repete —
 * [1,2,3] com 2 de cada lado vira [3,2,1,2,3,2,1].
 */
fun reflectPad(x: FloatArray, left: Int, right: Int): FloatArray {
    val n = x.size
    require(n > 1) { "reflect padding needs at least two samples" }
    val out = FloatArray(left + n + right)
    for (i in out.indices) {
        var j = i - left
        while (j < 0 || j >= n) {
            if (j < 0) j = -j
            if (j >= n) j = 2 * (n - 1) - j
        }
        out[i] = x[j]
    }
    return out
}

/**
 * O espectrograma exato que o HTDemucs espera — port 1:1 do
 * `DemucsSpectrogram`.
 *
 * Dois paddings se empilham e os dois importam: o `torch.stft(center:)`
 * reflete meia janela de cada lado; por cima, o demucs reflete mais 3/8 de
 * janela para a contagem de frames dar exatamente amostras/hop. Errar
 * qualquer um falha em silêncio: o modelo roda e devolve quatro stems, só
 * que separados de um sinal alguns milissegundos fora de si mesmo.
 */
class DemucsSpectrogram {
    companion object {
        const val NFFT = 4096
        const val HOP = 1024
        /** 2049 menos o bin de Nyquist que o demucs descarta. */
        const val BINS = 2048
    }

    private val fft = RealFFT(NFFT)

    // torch.hann_window é periódica por padrão.
    private val window = FloatArray(NFFT) {
        (0.5 - 0.5 * cos(2 * Math.PI * it / NFFT)).toFloat()
    }

    /** torch normalized: true. */
    private val normalization = (1.0 / sqrt(NFFT.toDouble())).toFloat()

    fun frameCount(samples: Int): Int =
        kotlin.math.ceil(samples.toDouble() / HOP).toInt()

    data class Forward(val re: FloatArray, val im: FloatArray, val frames: Int)

    /**
     * Um canal entra; planos real e imaginário saem, cada um `bins * frames`
     * em ordem frequência-major, casando com a entrada (F, T) do modelo.
     */
    fun forward(signal: FloatArray): Forward {
        val n = signal.size
        val frames = frameCount(n)
        val outerPad = HOP / 2 * 3
        val padded = reflectPad(signal, left = outerPad, right = outerPad + frames * HOP - n)
        // center: true acrescenta meia janela de cada lado.
        val centered = reflectPad(padded, left = NFFT / 2, right = NFFT / 2)

        val totalFrames = 1 + (centered.size - NFFT) / HOP
        check(totalFrames == frames + 4) { "unexpected frame count $totalFrames" }

        val re = FloatArray(BINS * frames)
        val im = FloatArray(BINS * frames)
        val frameRe = FloatArray(NFFT / 2 + 1)
        val frameIm = FloatArray(NFFT / 2 + 1)
        val windowed = FloatArray(NFFT)

        // O demucs guarda os frames 2 ..< 2 + frames, descartando os dois de
        // cada ponta que só viram padding.
        for (t in 0 until frames) {
            val start = (t + 2) * HOP
            for (i in 0 until NFFT) windowed[i] = centered[start + i] * window[i]
            fft.forward(windowed, frameRe, frameIm)
            for (f in 0 until BINS) {
                re[f * frames + t] = frameRe[f] * normalization
                im[f * frames + t] = frameIm[f] * normalization
            }
        }
        return Forward(re, im, frames)
    }

    /** O espelho do `forward`, de volta a `length` amostras. */
    fun inverse(re: FloatArray, im: FloatArray, frames: Int, length: Int): FloatArray {
        val outerPad = HOP / 2 * 3
        val padLength = HOP * frameCount(length) + 2 * outerPad
        val totalFrames = frames + 4
        val centeredLength = padLength + NFFT

        val acc = FloatArray(centeredLength + NFFT)
        val norm = FloatArray(centeredLength + NFFT)
        val frameRe = FloatArray(NFFT / 2 + 1)
        val frameIm = FloatArray(NFFT / 2 + 1)
        val samples = FloatArray(NFFT)
        val denormalization = sqrt(NFFT.toDouble()).toFloat()

        for (t in 0 until totalFrames) {
            val source = t - 2
            if (source in 0 until frames) {
                for (f in 0 until BINS) {
                    frameRe[f] = re[f * frames + source] * denormalization
                    frameIm[f] = im[f * frames + source] * denormalization
                }
            } else {
                // Os frames que o demucs cortou voltam como silêncio, que é o
                // que o F.pad repõe antes da transformada inversa.
                for (f in 0 until BINS) {
                    frameRe[f] = 0f
                    frameIm[f] = 0f
                }
            }
            frameRe[BINS] = 0f
            frameIm[BINS] = 0f

            fft.inverse(frameRe, frameIm, samples)
            val start = t * HOP
            for (i in 0 until NFFT) {
                acc[start + i] += samples[i] * window[i]
                norm[start + i] += window[i] * window[i]
            }
        }

        // Desfaz a sobreposição da janela de análise e tira os dois pads.
        val out = FloatArray(length)
        val offset = NFFT / 2 + outerPad
        for (i in 0 until length) {
            val w = norm[offset + i]
            out[i] = if (w > 1e-8f) acc[offset + i] / w else 0f
        }
        return out
    }
}
