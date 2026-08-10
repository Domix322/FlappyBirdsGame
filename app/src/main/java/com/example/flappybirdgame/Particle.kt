package com.example.flappybirdgame

/**
 * Одна частица-квадратик для эффектов (технический агент).
 *
 * Универсальный «кирпичик» анимаций: искры при подборе монеты, брызги при
 * столкновении, пёрышки при взмахе.
 *
 * Все координаты — в пикселях, скорости — в px/сек. Жизнь [life] убывает со
 * временем; когда доходит до нуля, частица удаляется. Прозрачность и размер
 * плавно гаснут пропорционально остатку жизни.
 *
 * Как редактировать: меняйте начальные vx/vy (разлёт), gravity (падение),
 * maxLife (длительность), size/color (вид).
 */
class Particle(
    var x: Float,
    var y: Float,
    private var vx: Float,
    private var vy: Float,
    private val maxLife: Float,   // полная длительность жизни, сек
    val size: Float,              // размер квадрата, px
    val color: Int,               // ARGB-цвет
    private val gravity: Float = 0f   // ускорение вниз, px/сек² (0 — не падает)
) {
    /** Остаток жизни, сек. */
    var life: Float = maxLife
        private set

    /** Доля прожитой жизни 1→0 (для затухания прозрачности/размера). */
    val fade: Float get() = (life / maxLife).coerceIn(0f, 1f)

    /** Мертва ли частица (пора удалять из списка). */
    val dead: Boolean get() = life <= 0f

    /** Шаг физики за dt секунд. */
    fun update(dt: Float) {
        vy += gravity * dt
        x += vx * dt
        y += vy * dt
        life -= dt
    }
}
