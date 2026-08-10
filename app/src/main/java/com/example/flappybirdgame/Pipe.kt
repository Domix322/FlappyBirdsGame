package com.example.flappybirdgame

/**
 * Пара домов-препятствий с вертикальным проёмом (технический агент).
 *
 * gapTop — низ верхнего дома (свисает сверху).
 * gapBottom — верх нижнего дома (стоит на земле).
 * Между ними птица должна пролететь.
 *
 * seed — устойчивый ключ узора окон (чтобы окна не мерцали при движении).
 */
class Pipe(
    var x: Float,
    val width: Float,
    var gapTop: Float,
    val gapSize: Float,
    val seed: Int = 0
) {
    /** Верхняя граница нижней трубы. */
    val gapBottom: Float
        get() = gapTop + gapSize

    /** Засчитано ли очко за прохождение этой трубы. */
    var passed: Boolean = false

    /**
     * Есть ли на этой трубе монета. Монеты появляются не на каждой трубе, а
     * случайно раз в 4–7 труб (решение принимает GameView.spawnPipe).
     */
    var hasCoin: Boolean = false

    /**
     * Собрана ли монета, висящая в проёме этой трубы. Монета одна на пару труб,
     * её центр — середина проёма (см. coinX/coinY). Собранная больше не рисуется.
     */
    var coinCollected: Boolean = false

    /** X центра монеты в проёме (по центру трубы). */
    val coinX: Float get() = x + width / 2f

    /** Y центра монеты в проёме (по вертикали — середина зазора). */
    val coinY: Float get() = gapTop + gapSize / 2f

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
