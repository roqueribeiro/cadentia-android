package com.levelhard.cadentia.features.drums

import com.levelhard.cadentia.kit.SampleBank
import java.util.Locale

/**
 * Como uma pancada vira chave de cache e ganho — port do `DrumVoicing.swift`.
 *
 * **Chave.** Quando a pancada vem de sample, a chave é o ARQUIVO que ela vai
 * usar, não o pedido: o app pede 15 pads × 4 dinâmicas × 4 variações, e o
 * pack responde com muito menos arquivos. Medido no iOS com chave por pedido:
 * 33,2 MB dos 49,6 MB do cache eram cópias idênticas, o teto estourava já na
 * levada vazia e o sequenciador renderizava 6,5 pancadas por segundo sem o
 * cache nunca convergir. Com chave por arquivo a mesma levada cabe em 15 MB.
 *
 * **Ganho.** Quando vem de sample, o acento entra como ganho contínuo na voz
 * (`amp_veltrack`), porque a camada de dinâmica sozinha é grossa demais. Na
 * síntese ele não entra — `DrumKitHD.render` já aplica a própria curva dentro
 * do buffer, e somar as duas contaria a dinâmica duas vezes.
 *
 * O volume da tela também vai na voz, não no buffer: mexer no slider não
 * pode invalidar o cache inteiro.
 *
 * `sampled` é o que o chamador repassa a `DrumSynth.renderStereo(
 * velocityGainApplied = …)`, para o caminho de erro (arquivo prometido que
 * não abre) não contar a dinâmica duas vezes.
 */
internal object DrumVoicing {
    data class Voicing(val key: String, val gain: Float, val sampled: Boolean)

    fun of(kit: String, pad: String, velocity: Float, variation: Int, volume: Float): Voicing {
        val generation = SampleBank.shared.soundGeneration
        SampleBank.shared.drumSlot(kit, pad, velocity, variation)?.let { slot ->
            return Voicing("$generation/s:$slot", volume * SampleBank.drumVelocityGain(velocity), true)
        }
        return Voicing(
            "$generation/$kit/$pad/" + String.format(Locale.ROOT, "%.2f/%d", velocity, variation),
            volume,
            false,
        )
    }
}
