package com.example.flappybirdgame

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.random.Random

/**
 * Ядро игры: SurfaceView + игровой поток.
 *
 * Технический агент — состояние, физика, коллизии, ввод, рекорд, жизненный цикл.
 * Визуальный агент — методы draw* и все Paint.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private enum class State { READY, PLAYING, GAME_OVER }

    private var thread: GameThread? = null
    private var state = State.READY

    // Размеры мира (известны после surfaceCreated).
    private var w = 0f
    private var h = 0f
    private var groundY = 0f

    // Игровые объекты.
    private lateinit var bird: Bird
    private val pipes = ArrayList<Pipe>()

    // Параметры (пересчитываются от размера экрана в setupWorld()).
    private var gravity = 0f
    private var flapVelocity = 0f
    private var pipeSpeed = 0f
    private var pipeWidth = 0f
    private var pipeGap = 0f
    private var pipeSpacing = 0f
    private var birdRadius = 0f
    private var groundHeight = 0f

    private var score = 0
    private var best = 0

    private val prefs = context.getSharedPreferences("flappy", Context.MODE_PRIVATE)

    // ---- Paint (визуальный агент) ----
    private val pipePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(76, 175, 80) }
    private val pipeDarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(56, 142, 60) }
    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(222, 184, 135) }
    private val grassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 190, 90) }
    private val birdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 209, 0) }
    private val wingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 170, 0) }
    private val beakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 120, 0) }
    private val eyeWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 0, 0, 0)
        style = Paint.Style.STROKE
    }
    private var skyPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val textShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 0, 0)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    init {
        holder.addCallback(this)
        isFocusable = true
        best = prefs.getInt("best", 0)
    }

    // ---- Настройка мира под размер экрана (технический агент) ----
    private fun setupWorld() {
        groundHeight = h * 0.12f
        groundY = h - groundHeight
        gravity = h * 3.4f
        flapVelocity = -h * 0.92f
        pipeSpeed = w * 0.55f
        pipeWidth = w * 0.18f
        pipeGap = h * 0.30f
        pipeSpacing = w * 0.62f
        birdRadius = w * 0.045f

        skyPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, h,
                Color.rgb(112, 197, 255), Color.rgb(196, 236, 255),
                Shader.TileMode.CLAMP
            )
        }
        textPaint.strokeWidth = w * 0.006f
        outlinePaint.strokeWidth = w * 0.008f

        resetGame()
    }

    private fun resetGame() {
        bird = Bird(w * 0.28f, groundY / 2f, birdRadius, gravity, flapVelocity)
        pipes.clear()
        score = 0
        state = State.READY
    }

    private fun spawnPipe(atX: Float) {
        val margin = h * 0.12f
        val minTop = margin
        val maxTop = groundY - pipeGap - margin
        val gapTop = Random.nextFloat() * (maxTop - minTop) + minTop
        pipes.add(Pipe(atX, pipeWidth, gapTop, pipeGap))
    }

    // ---- Игровой цикл (технический агент) ----
    fun update(dt: Float) {
        when (state) {
            State.READY -> {
                // Птица мягко парит на стартовом экране.
                bird.y = groundY / 2f + kotlin.math.sin(System.currentTimeMillis() / 200.0).toFloat() * (h * 0.01f)
            }
            State.PLAYING -> {
                bird.update(dt)

                // Спавн труб по мере продвижения.
                if (pipes.isEmpty() || pipes.last().x < w - pipeSpacing) {
                    spawnPipe(w)
                }

                val iter = pipes.iterator()
                while (iter.hasNext()) {
                    val p = iter.next()
                    p.update(dt, pipeSpeed)

                    if (!p.passed && p.x + p.width < bird.x) {
                        p.passed = true
                        score++
                    }
                    if (p.collidesWith(bird.x, bird.y, bird.radius, groundY)) {
                        gameOver()
                    }
                    if (p.isOffScreen()) iter.remove()
                }

                // Пол и потолок.
                if (bird.y + bird.radius >= groundY) {
                    bird.y = groundY - bird.radius
                    gameOver()
                }
                if (bird.y - bird.radius < 0f) {
                    bird.y = bird.radius
                }
            }
            State.GAME_OVER -> { /* ждём тап для рестарта */ }
        }
    }

    private fun gameOver() {
        if (state != State.PLAYING) return
        state = State.GAME_OVER
        if (score > best) {
            best = score
            prefs.edit().putInt("best", best).apply()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            when (state) {
                State.READY -> {
                    state = State.PLAYING
                    bird.reset(bird.y)
                    bird.flap()
                }
                State.PLAYING -> bird.flap()
                State.GAME_OVER -> resetGame()
            }
            performClick()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ---- Рендеринг (визуальный агент) ----
    fun render(canvas: Canvas) {
        if (w == 0f) return
        canvas.drawRect(0f, 0f, w, h, skyPaint)
        for (p in pipes) drawPipe(canvas, p)
        drawGround(canvas)
        drawBird(canvas)
        drawHud(canvas)
    }

    private fun drawPipe(canvas: Canvas, p: Pipe) {
        val capH = h * 0.03f
        val capOverhang = pipeWidth * 0.12f
        // Верхняя труба.
        canvas.drawRect(p.x, 0f, p.x + p.width, p.gapTop, pipePaint)
        canvas.drawRect(p.x - capOverhang, p.gapTop - capH, p.x + p.width + capOverhang, p.gapTop, pipeDarkPaint)
        // Нижняя труба.
        canvas.drawRect(p.x, p.gapBottom, p.x + p.width, groundY, pipePaint)
        canvas.drawRect(p.x - capOverhang, p.gapBottom, p.x + p.width + capOverhang, p.gapBottom + capH, pipeDarkPaint)
    }

    private fun drawGround(canvas: Canvas) {
        canvas.drawRect(0f, groundY, w, h, groundPaint)
        canvas.drawRect(0f, groundY, w, groundY + h * 0.02f, grassPaint)
    }

    private fun drawBird(canvas: Canvas) {
        val r = bird.radius
        canvas.save()
        canvas.translate(bird.x, bird.y)
        // Наклон по вертикальной скорости (визуальный агент).
        val tilt = (bird.velocity / (h * 1.4f)).coerceIn(-0.5f, 0.9f)
        canvas.rotate(Math.toDegrees(tilt.toDouble()).toFloat())

        canvas.drawCircle(0f, 0f, r, birdPaint)
        canvas.drawCircle(0f, 0f, r, outlinePaint)
        // Крыло.
        canvas.drawOval(RectF(-r * 0.7f, -r * 0.1f, r * 0.15f, r * 0.6f), wingPaint)
        // Глаз.
        canvas.drawCircle(r * 0.35f, -r * 0.3f, r * 0.28f, eyeWhitePaint)
        canvas.drawCircle(r * 0.45f, -r * 0.3f, r * 0.13f, eyePaint)
        // Клюв.
        val beak = Path().apply {
            moveTo(r * 0.6f, -r * 0.05f)
            lineTo(r * 1.15f, r * 0.12f)
            lineTo(r * 0.6f, r * 0.3f)
            close()
        }
        canvas.drawPath(beak, beakPaint)
        canvas.restore()
    }

    private fun drawHud(canvas: Canvas) {
        when (state) {
            State.READY -> {
                textPaint.textSize = w * 0.08f
                centeredText(canvas, "Flappy Bird", w / 2f, h * 0.28f)
                textPaint.textSize = w * 0.05f
                centeredText(canvas, "Тап — взмах вверх", w / 2f, h * 0.36f)
                centeredText(canvas, "Нажми, чтобы начать", w / 2f, h * 0.44f)
                if (best > 0) centeredText(canvas, "Рекорд: $best", w / 2f, h * 0.52f)
            }
            State.PLAYING -> {
                textPaint.textSize = w * 0.14f
                centeredText(canvas, "$score", w / 2f, h * 0.16f)
            }
            State.GAME_OVER -> {
                textPaint.textSize = w * 0.14f
                centeredText(canvas, "$score", w / 2f, h * 0.16f)
                textPaint.textSize = w * 0.09f
                centeredText(canvas, "Игра окончена", w / 2f, h * 0.40f)
                textPaint.textSize = w * 0.055f
                centeredText(canvas, "Рекорд: $best", w / 2f, h * 0.48f)
                centeredText(canvas, "Тап — заново", w / 2f, h * 0.56f)
            }
        }
    }

    private fun centeredText(canvas: Canvas, text: String, cx: Float, cy: Float) {
        textShadow.textSize = textPaint.textSize
        val off = textPaint.textSize * 0.04f
        canvas.drawText(text, cx + off, cy + off, textShadow)
        canvas.drawText(text, cx, cy, textPaint)
    }

    // ---- Жизненный цикл поверхности (технический агент) ----
    override fun surfaceCreated(holder: SurfaceHolder) {
        w = width.toFloat()
        h = height.toFloat()
        setupWorld()
        thread = GameThread(holder).also {
            it.running = true
            it.start()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        w = width.toFloat()
        h = height.toFloat()
        setupWorld()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopThread()
    }

    fun pause() {
        // Замораживаем игру, чтобы после сворачивания не было «телепорта».
        gameOver()
    }

    fun resume() { /* поток стартует в surfaceCreated */ }

    private fun stopThread() {
        val t = thread ?: return
        t.running = false
        var retry = true
        while (retry) {
            try {
                t.join()
                retry = false
            } catch (_: InterruptedException) { /* повтор */ }
        }
        thread = null
    }

    /** Игровой поток с фиксацией dt. */
    private inner class GameThread(private val holder: SurfaceHolder) : Thread() {
        @Volatile var running = false
        private val targetFrameMs = 1000L / 60L

        override fun run() {
            var last = System.nanoTime()
            while (running) {
                val start = System.currentTimeMillis()
                val now = System.nanoTime()
                var dt = (now - last) / 1_000_000_000f
                last = now
                // Ограничиваем dt: после паузы/лагов не даём птице «прыгнуть».
                if (dt > 0.05f) dt = 0.05f

                update(dt)

                var canvas: Canvas? = null
                try {
                    canvas = holder.lockCanvas()
                    if (canvas != null) {
                        synchronized(holder) { render(canvas) }
                    }
                } finally {
                    if (canvas != null) holder.unlockCanvasAndPost(canvas)
                }

                val elapsed = System.currentTimeMillis() - start
                val sleep = targetFrameMs - elapsed
                if (sleep > 0) {
                    try {
                        sleep(sleep)
                    } catch (_: InterruptedException) { /* игнор */ }
                }
            }
        }
    }
}
