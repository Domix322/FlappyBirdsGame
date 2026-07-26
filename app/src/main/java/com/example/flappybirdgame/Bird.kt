package com.example.flappybirdgame

/**
 * Физика птицы (технический агент).
 *
 * Все величины — в пикселях и пикселях/сек. Скорость интегрируется по времени
 * (delta-time), поэтому поведение одинаково на любой частоте кадров.
 */
class Bird(
    val x: Float,
    var y: Float,
    val radius: Float,
    private val gravity: Float,
    private val flapVelocity: Float
) {
    /** Вертикальная скорость, px/сек. Вниз — положительная. */
    var velocity: Float = 0f
        private set

    /** Импульс вверх по касанию. */
    fun flap() {
        velocity = flapVelocity
    }

    /** Обновление позиции за dt секунд. */
    fun update(dt: Float) {
        velocity += gravity * dt
        y += velocity * dt
    }

    /** Сброс в исходное состояние (для рестарта / стартового экрана). */
    fun reset(startY: Float) {
        y = startY
        velocity = 0f
    }
}
