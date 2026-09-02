package com.levelhard.cadentia.kit.cordas

import kotlinx.serialization.Serializable

/**
 * Onde um landmark da câmera cai na tela — port do `CameraFrameMapping.swift`.
 *
 * É uma álgebra pequena com um número constrangedor de formas de estar errada:
 * a captura pode girar e espelhar, a prévia aplica a própria transformação, e
 * a câmera frontal é espelhada. Cada uma é uma virada, nenhuma dá erro, e o
 * único sintoma é a mão desenhada em algum lugar onde a mão real não está.
 * Então é um valor: as viradas têm nome, o mapeamento é puro e as quatro
 * combinações são enumeráveis — dá para achar a certa **no aparelho em dois
 * toques**.
 *
 * No Android o landmark do MediaPipe chega normalizado com a origem no CANTO
 * SUPERIOR esquerdo e y para baixo — a convenção da tela. Por isso aqui não há
 * a virada de Y que o Vision exigia; `flipY` continua existindo para o botão.
 */
@Serializable
data class CameraFrameMapping(val flipX: Boolean, val flipY: Boolean) {
    val next: CameraFrameMapping
        get() {
            val index = all.indexOf(this).coerceAtLeast(0)
            return all[(index + 1) % all.size]
        }

    /** Duas setas, para o estado ser legível no botão. */
    val symbol: String
        get() = when {
            !flipX && !flipY -> "·"
            flipX && !flipY -> "↔"
            !flipX -> "↕"
            else -> "↔↕"
        }

    /**
     * Um landmark, do quadro normalizado (origem em cima à esquerda) para a
     * view. O ajuste é "cover", o mesmo que a prévia faz com `FILL_CENTER`: se
     * os pontos usassem outro ajuste, o instrumento ficaria onde as mãos não estão.
     */
    fun viewPoint(normalized: Point, imageSize: Size, viewSize: Size): Point {
        if (imageSize.width <= 0 || imageSize.height <= 0) return Point.zero
        val scale = maxOf(viewSize.width / imageSize.width, viewSize.height / imageSize.height)
        val drawWidth = imageSize.width * scale
        val drawHeight = imageSize.height * scale
        val originX = (viewSize.width - drawWidth) / 2
        val originY = (viewSize.height - drawHeight) / 2
        var x = normalized.x
        var y = normalized.y
        if (flipX) x = 1 - x
        if (flipY) y = 1 - y
        return Point(originX + x * drawWidth, originY + y * drawHeight)
    }

    companion object {
        val identity = CameraFrameMapping(flipX = false, flipY = false)
        val mirrored = CameraFrameMapping(flipX = true, flipY = false)
        val upsideDown = CameraFrameMapping(flipX = false, flipY = true)
        val turnedAround = CameraFrameMapping(flipX = true, flipY = true)

        /**
         * A câmera frontal se ESPELHA na prévia (é como a pessoa espera se ver),
         * e o landmark tem que ir junto. Continua ajustável: o custo de errar é
         * uma ida ao aparelho, o do botão é um toque.
         */
        val frontCameraDefault = mirrored

        val all: List<CameraFrameMapping> = listOf(identity, mirrored, upsideDown, turnedAround)
    }
}
