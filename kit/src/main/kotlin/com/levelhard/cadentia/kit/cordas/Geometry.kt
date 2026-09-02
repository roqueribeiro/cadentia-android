package com.levelhard.cadentia.kit.cordas

import kotlin.math.hypot

/**
 * A geometria mínima que o Cordas precisa — o papel de CGPoint/CGSize/
 * CGVector/CGRect no Kit do iOS. Em Double, sem Android: o `:kit` é JVM puro
 * e a tela converte para o que o Compose usa.
 */
data class Point(val x: Double, val y: Double) {
    companion object {
        val zero = Point(0.0, 0.0)
    }
}

data class Size(val width: Double, val height: Double) {
    companion object {
        val zero = Size(0.0, 0.0)
    }
}

data class Vector(val dx: Double, val dy: Double)

data class Rect(val x: Double, val y: Double, val width: Double, val height: Double) {
    val minX: Double get() = x
    val minY: Double get() = y
    val maxX: Double get() = x + width
    val maxY: Double get() = y + height
    val midX: Double get() = x + width / 2
    val midY: Double get() = y + height / 2

    fun contains(point: Point): Boolean =
        point.x >= minX && point.x < maxX && point.y >= minY && point.y < maxY
}

fun distance(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)
