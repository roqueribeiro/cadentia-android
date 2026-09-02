package com.levelhard.cadentia.kit

import kotlinx.serialization.Serializable

/**
 * Um trecho da música marcado para repetir: o loop de estudo — port do
 * `PracticeLoop.swift`.
 *
 * Nasceu do primeiro feedback público do app: quem estuda um solo precisa
 * repetir DOIS compassos, não a música inteira. A regra mora no Kit porque é
 * pura aritmética de tempo, e cada canto (motor, tela, restauração) precisa da
 * mesma resposta para "esse instante está dentro do loop?".
 */
@Serializable
data class PracticeLoop private constructor(
    val start: Double,
    val end: Double,
) {
    fun contains(time: Double): Boolean = time >= start && time < end

    /**
     * Ajusta o loop à duração real da música (um loop salvo pode ter vindo de
     * outra edição do arquivo). Se não sobrar trecho válido, não há loop.
     */
    fun clamped(toDuration: Double): PracticeLoop? =
        of(start = minOf(start, toDuration), end = minOf(end, toDuration))

    companion object {
        /**
         * Menos que isto não é um trecho, é um soluço: o player voltaria ao
         * início tão rápido que nada se ouve. Meio segundo é o menor pedaço
         * audível de música em qualquer andamento praticável.
         */
        const val MIN_LENGTH: Double = 0.5

        /**
         * Aceita os dois pontos em QUALQUER ordem: quem está tocando marca o
         * fim antes do começo com frequência (ouviu o solo terminar e só então
         * pensou no início). Rejeitar por ordem seria punir o uso real.
         */
        fun of(start: Double, end: Double): PracticeLoop? {
            val a = minOf(start, end)
            val b = maxOf(start, end)
            if (b - a < MIN_LENGTH || a < 0) return null
            return PracticeLoop(a, b)
        }
    }
}
