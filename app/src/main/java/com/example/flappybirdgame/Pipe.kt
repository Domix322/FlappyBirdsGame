package com.example.flappybirdgame

/**
 * Пара труб с вертикальным зазором (технический агент).
 *
 * gapTop — нижняя граница верхней трубы (низ верхней трубы).
 * gapBottom — верхняя граница нижней трубы (верх нижней трубы).
 * Между ними птица должна пролететь.
 */
class Pipe(
    var x: Float,
    val width: Float,
    var gapTop: Float,
    val gapSize: Float
) {
    /** Верхняя граница нижней трубы. */
    val gapBottom: Float
        get() = gapTop + gapSize

    /** Засчитано ли очко за прохождение этой трубы. */
    var passed: Boolean = false

    /** Движение влево за dt секунд. */
    fun update(dt: Float, speed: Float) {
        x -= speed * dt
    }

    /** Труба полностью ушла за левый край. */
    fun isOffScreen(): Boolean = x + width < 0f

    /**
     * Коллизия круга птицы (bx, by, r) с одной из двух труб пары.
     * Проверяем ближайшую точку прямоугольника к центру круга.
     */
    fun collidesWith(bx: Float, by: Float, r: Float, screenHeight: Float): Boolean {
        if (bx + r < x || bx - r > x + width) return false
        // Верхняя труба: от 0 до gapTop. Нижняя: от gapBottom до низа экрана.
        return circleIntersectsRect(bx, by, r, x, 0f, x + width, gapTop) ||
            circleIntersectsRect(bx, by, r, x, gapBottom, x + width, screenHeight)
    }

    private fun circleIntersectsRect(
        cx: Float, cy: Float, r: Float,
        left: Float, top: Float, right: Float, bottom: Float
    ): Boolean {
        val nearestX = cx.coerceIn(left, right)
        val nearestY = cy.coerceIn(top, bottom)
        val dx = cx - nearestX
        val dy = cy - nearestY
        return dx * dx + dy * dy <= r * r
    }
}
