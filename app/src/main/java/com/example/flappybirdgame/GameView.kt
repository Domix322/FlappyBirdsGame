package com.example.flappybirdgame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ядро игры: SurfaceView + игровой поток. Локация — город, две темы (день/ночь),
 * переключаются кнопкой «Theme» в главном меню. Вся графика пиксельная.
 *
 * Технический агент — состояние, физика, коллизии, ввод, рекорд, темы, цикл.
 * Визуальный агент — методы draw*, спрайты, палитра тем, экран загрузки.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private enum class State { LOADING, READY, PLAYING, GAME_OVER }

    private var thread: GameThread? = null
    private var state = State.LOADING

    private var w = 0f
    private var h = 0f
    private var groundY = 0f
    private var groundHeight = 0f

    private lateinit var bird: Bird
    private val pipes = ArrayList<Pipe>()

    private var gravity = 0f
    private var flapVelocity = 0f
    private var pipeSpeed = 0f
    private var pipeWidth = 0f
    private var pipeGap = 0f
    private var pipeSpacing = 0f
    private var birdRadius = 0f
    private var birdPixel = 0f
    private var cloudPixel = 0f
    private var scroll = 0f

    private var score = 0
    private var best = 0
    private var nightMode = false

    private var loadStart = 0L
    private val loadDuration = 2400L
    private var hasLoaded = false

    private val prefs = context.getSharedPreferences("flappy", Context.MODE_PRIVATE)

    // Кнопка смены темы (главное меню).
    private val themeBtn = RectF()

    // ---- Спрайты ----
    private val birdBody = arrayOf(
        "....KKKK.....",
        "..KKHHHHKK...",
        ".KHHHYYYYWK..",
        ".KHYYYYYWPKO.",
        "KHYYYYYYWPKOO",
        "KYYYYYYYYYKO.",
        "KYYYYYYYYYK..",
        ".KYYYYYYYYK..",
        ".KKYYYYYYK...",
        "..KKYYYYKK...",
        "....KKKK....."
    )
    private val wingUp = arrayOf("..GGG.", ".GGGG.", "GGGG..", "......")
    private val wingMid = arrayOf("......", ".GGGG.", "GGGGGG", "......")
    private val wingDown = arrayOf("......", "......", "GGG...", ".GGGGG")
    private val wingCol = 2
    private val wingRow = 4

    private val cloud = arrayOf("..wwww..", ".wwwwww.", "wwwwwwww", ".cccccc.")

    // ---- Paint ----
    private val px = HashMap<Char, Paint>().apply {
        put('Y', Paint().apply { color = Color.rgb(255, 205, 45) })
        put('H', Paint().apply { color = Color.rgb(255, 233, 130) })
        put('W', Paint().apply { color = Color.rgb(255, 255, 255) })
        put('P', Paint().apply { color = Color.rgb(35, 30, 35) })
        put('K', Paint().apply { color = Color.rgb(70, 45, 25) })
        put('O', Paint().apply { color = Color.rgb(255, 150, 30) })
        put('G', Paint().apply { color = Color.rgb(235, 160, 35) })
        put('w', Paint().apply { color = Color.rgb(248, 252, 255) })
        put('c', Paint().apply { color = Color.rgb(210, 230, 248) })
    }

    private val bandPaint = Paint()
    private val celestialPaint = Paint()
    private val celestialShadePaint = Paint()
    private val starPaint = Paint().apply { color = Color.rgb(255, 255, 225) }

    private val cityFarPaint = Paint()
    private val cityNearPaint = Paint()
    private val winLitPaint = Paint()
    private val winDarkPaint = Paint()
    private var winThreshold = 0.5f

    private val pavementPaint = Paint()
    private val curbPaint = Paint()
    private val tilePaint = Paint()

    // Дома-препятствия (меняются по теме).
    private val obsBody = Paint()
    private val obsEdge = Paint()
    private val obsRoof = Paint()
    private val obsWinLit = Paint()
    private val obsWinDark = Paint()

    private val barFillPaint = Paint().apply { color = Color.rgb(255, 210, 40) }
    private val barBgPaint = Paint().apply { color = Color.rgb(60, 62, 74) }
    private val barBorderPaint = Paint().apply { color = Color.rgb(35, 26, 18) }
    private val btnPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var btnTextColor = Color.WHITE

    // Небо (полосы) по темам.
    private var skyBands = intArrayOf()

    // Цвета текста (градиент заголовка зависит от темы).
    private var titleTop = Color.rgb(255, 224, 90)
    private var titleBottom = Color.rgb(232, 120, 24)
    private val textWhite = Color.rgb(255, 255, 255)
    private val outlineColor = Color.rgb(45, 30, 20)

    private val srcTextPaint = Paint().apply {
        isAntiAlias = false
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.06f
        textAlign = Paint.Align.LEFT
    }
    private val blitPaint = Paint().apply { isFilterBitmap = false; isDither = false }
    private val textCache = HashMap<String, Bitmap>()

    // Звёзды (ночь) — фиксированные, без мерцания.
    private val starPts = arrayOf(
        floatArrayOf(0.14f, 0.12f), floatArrayOf(0.30f, 0.08f), floatArrayOf(0.44f, 0.16f),
        floatArrayOf(0.58f, 0.10f), floatArrayOf(0.70f, 0.20f), floatArrayOf(0.84f, 0.09f),
        floatArrayOf(0.90f, 0.24f), floatArrayOf(0.22f, 0.24f), floatArrayOf(0.52f, 0.28f)
    )

    init {
        holder.addCallback(this)
        isFocusable = true
        best = prefs.getInt("best", 0)
        nightMode = prefs.getBoolean("night", false)
    }

    private fun setupWorld() {
        groundHeight = h * 0.14f
        groundY = h - groundHeight
        gravity = h * 3.4f
        flapVelocity = -h * 0.92f
        pipeSpeed = w * 0.55f
        pipeWidth = w * 0.18f
        pipeGap = h * 0.30f
        pipeSpacing = w * 0.62f
        birdRadius = w * 0.045f
        birdPixel = birdRadius * 0.185f
        cloudPixel = w * 0.022f
        themeBtn.set(w * 0.32f, h * 0.75f, w * 0.68f, h * 0.82f)

        applyTheme()
        resetGame()
        if (!hasLoaded) {
            state = State.LOADING
            loadStart = System.currentTimeMillis()
        }
    }

    /** Палитра города под выбранную тему. */
    private fun applyTheme() {
        if (nightMode) {
            skyBands = intArrayOf(
                Color.rgb(12, 16, 46), Color.rgb(26, 26, 70),
                Color.rgb(48, 38, 88), Color.rgb(74, 54, 100)
            )
            celestialPaint.color = Color.rgb(236, 238, 216)
            celestialShadePaint.color = Color.rgb(210, 214, 190)
            cityFarPaint.color = Color.rgb(38, 42, 72)
            cityNearPaint.color = Color.rgb(26, 28, 52)
            winLitPaint.color = Color.rgb(255, 214, 120)
            winDarkPaint.color = Color.rgb(34, 38, 62)
            winThreshold = 0.55f
            pavementPaint.color = Color.rgb(46, 50, 66)
            curbPaint.color = Color.rgb(66, 72, 92)
            tilePaint.color = Color.rgb(34, 38, 52)
            btnPanelPaint.color = Color.rgb(30, 34, 56)
            btnTextColor = Color.rgb(240, 244, 255)
            obsBody.color = Color.rgb(74, 60, 92)
            obsEdge.color = Color.rgb(52, 42, 68)
            obsRoof.color = Color.rgb(38, 30, 52)
            obsWinLit.color = Color.rgb(255, 214, 120)
            obsWinDark.color = Color.rgb(46, 40, 64)
            titleTop = Color.rgb(78, 128, 240)      // ночь: верх синий
            titleBottom = Color.rgb(150, 72, 214)    // низ фиолетовый
        } else {
            skyBands = intArrayOf(
                Color.rgb(58, 140, 230), Color.rgb(86, 162, 240),
                Color.rgb(120, 190, 250), Color.rgb(162, 214, 255)
            )
            celestialPaint.color = Color.rgb(255, 231, 120)
            celestialShadePaint.color = Color.rgb(255, 214, 90)
            cityFarPaint.color = Color.rgb(150, 172, 206)
            cityNearPaint.color = Color.rgb(120, 148, 190)
            winLitPaint.color = Color.rgb(206, 230, 252)
            winDarkPaint.color = Color.rgb(96, 124, 164)
            winThreshold = 0.5f
            pavementPaint.color = Color.rgb(150, 156, 168)
            curbPaint.color = Color.rgb(182, 188, 200)
            tilePaint.color = Color.rgb(122, 128, 140)
            btnPanelPaint.color = Color.rgb(228, 240, 255)
            btnTextColor = Color.rgb(40, 54, 92)
            obsBody.color = Color.rgb(226, 170, 116)
            obsEdge.color = Color.rgb(196, 140, 90)
            obsRoof.color = Color.rgb(150, 100, 60)
            obsWinLit.color = Color.rgb(255, 246, 205)
            obsWinDark.color = Color.rgb(176, 126, 84)
            titleTop = Color.rgb(255, 224, 90)       // день: жёлто-оранжевый
            titleBottom = Color.rgb(232, 120, 24)
        }
        textCache.clear()   // цвет заголовка сменился — сбрасываем кэш текста
    }

    private fun resetGame() {
        bird = Bird(w * 0.28f, groundY / 2f, birdRadius, gravity, flapVelocity)
        pipes.clear()
        score = 0
        state = State.READY
    }

    private fun toggleTheme() {
        nightMode = !nightMode
        prefs.edit().putBoolean("night", nightMode).apply()
        applyTheme()
    }

    private fun spawnPipe(atX: Float) {
        val margin = h * 0.12f
        val minTop = margin
        val maxTop = groundY - pipeGap - margin
        val gapTop = Random.nextFloat() * (maxTop - minTop) + minTop
        pipes.add(Pipe(atX, pipeWidth, gapTop, pipeGap, Random.nextInt()))
    }

    fun update(dt: Float) {
        when (state) {
            State.LOADING -> {
                scroll += pipeSpeed * 0.2f * dt
                if (System.currentTimeMillis() - loadStart >= loadDuration) {
                    hasLoaded = true
                    state = State.READY
                }
            }
            State.READY -> {
                scroll += pipeSpeed * 0.2f * dt
                bird.y = groundY / 2f + sin(System.currentTimeMillis() / 200.0).toFloat() * (h * 0.01f)
            }
            State.PLAYING -> {
                bird.update(dt)
                scroll += pipeSpeed * dt
                if (pipes.isEmpty() || pipes.last().x < w - pipeSpacing) spawnPipe(w)
                val iter = pipes.iterator()
                while (iter.hasNext()) {
                    val p = iter.next()
                    p.update(dt, pipeSpeed)
                    if (!p.passed && p.x + p.width < bird.x) { p.passed = true; score++ }
                    if (p.collidesWith(bird.x, bird.y, bird.radius, groundY)) gameOver()
                    if (p.isOffScreen()) iter.remove()
                }
                if (bird.y + bird.radius >= groundY) { bird.y = groundY - bird.radius; gameOver() }
                if (bird.y - bird.radius < 0f) bird.y = bird.radius
            }
            State.GAME_OVER -> {}
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
                State.LOADING -> {}
                State.READY ->
                    if (themeBtn.contains(event.x, event.y)) toggleTheme()
                    else { state = State.PLAYING; bird.reset(bird.y); bird.flap() }
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

    // ---- Рендеринг ----
    fun render(canvas: Canvas) {
        if (w == 0f) return
        drawSky(canvas)
        if (nightMode) drawStars(canvas) else drawClouds(canvas)
        drawCity(canvas, cityFarPaint, w * 0.26f, 0.30f, 0.22f, 0.25f)
        drawCity(canvas, cityNearPaint, w * 0.17f, 0.16f, 0.20f, 0.45f)
        if (state != State.LOADING) for (p in pipes) drawPipe(canvas, p)
        drawGround(canvas)
        if (state != State.LOADING) drawBird(canvas)
        if (state == State.LOADING) drawLoading(canvas) else drawHud(canvas)
    }

    private fun drawSky(canvas: Canvas) {
        val n = skyBands.size
        val bandH = groundY / n
        for (i in 0 until n) {
            bandPaint.color = skyBands[i]
            canvas.drawRect(0f, i * bandH, w, (i + 1) * bandH + 1f, bandPaint)
        }
        val s = w * 0.10f
        if (nightMode) {
            // Луна с кратерами, справа сверху.
            val cx = w * 0.82f; val cy = h * 0.11f
            canvas.drawRect(cx, cy, cx + s, cy + s, celestialPaint)
            val cc = s * 0.22f
            canvas.drawRect(cx - cc, cy + cc, cx, cy + s - cc, celestialPaint)
            canvas.drawRect(cx + s, cy + cc, cx + s + cc, cy + s - cc, celestialPaint)
            canvas.drawRect(cx + cc, cy - cc, cx + s - cc, cy, celestialPaint)
            canvas.drawRect(cx + cc, cy + s, cx + s - cc, cy + s + cc, celestialPaint)
            canvas.drawRect(cx + s * 0.2f, cy + s * 0.25f, cx + s * 0.42f, cy + s * 0.47f, celestialShadePaint)
            canvas.drawRect(cx + s * 0.55f, cy + s * 0.55f, cx + s * 0.72f, cy + s * 0.72f, celestialShadePaint)
        } else {
            // Солнце слева сверху.
            val sx = w * 0.10f; val sy = h * 0.10f
            canvas.drawRect(sx, sy, sx + s, sy + s, celestialPaint)
            val c = s * 0.22f
            canvas.drawRect(sx - c, sy + c, sx, sy + s - c, celestialPaint)
            canvas.drawRect(sx + s, sy + c, sx + s + c, sy + s - c, celestialPaint)
            canvas.drawRect(sx + c, sy - c, sx + s - c, sy, celestialPaint)
            canvas.drawRect(sx + c, sy + s, sx + s - c, sy + s + c, celestialPaint)
        }
    }

    private fun drawStars(canvas: Canvas) {
        val time = System.currentTimeMillis() / 360.0
        for (i in starPts.indices) {
            val p = starPts[i]
            val cx = p[0] * w
            val cy = p[1] * groundY
            // Мерцание: размер плавно пульсирует у каждой звезды со своей фазой.
            val tw = 0.5f + 0.5f * sin(time + i * 1.7).toFloat()
            val s = w * 0.018f * (0.45f + 0.55f * tw)
            val t = s * 0.32f
            canvas.drawRect(cx - t, cy - s, cx + t, cy + s, starPaint)
            canvas.drawRect(cx - s, cy - t, cx + s, cy + t, starPaint)
        }
    }

    private fun drawClouds(canvas: Canvas) {
        val slot = w * 0.6f
        val sc = scroll * 0.15f
        val first = floor(sc / slot).toInt()
        val offset = sc - first * slot
        var k = 0
        while (k * slot - offset < w + slot) {
            val idx = first + k
            val x = k * slot - offset
            val y = groundY * (0.10f + 0.11f * Math.floorMod(idx, 3))
            drawSprite(canvas, cloud, x, y, cloudPixel)
            k++
        }
    }

    private fun drawCity(canvas: Canvas, body: Paint, slotW: Float, minFrac: Float, spanFrac: Float, factor: Float) {
        val sc = scroll * factor
        val first = floor(sc / slotW).toInt()
        val offset = sc - first * slotW
        var k = 0
        while (k * slotW - offset < w + slotW) {
            val idx = first + k
            val x = k * slotW - offset
            val bw = slotW * 0.84f
            val top = groundY - (minFrac + hash(idx) * spanFrac) * h
            canvas.drawRect(x, top, x + bw, groundY, body)
            // Окна.
            val cols = 3
            val rows = ((groundY - top) / (h * 0.05f)).toInt().coerceIn(1, 9)
            val cw = bw / (cols + 1)
            val ch = (groundY - top) / (rows + 1)
            for (r in 0 until rows) for (c in 0 until cols) {
                val lit = hash(idx * 131 + r * 17 + c * 7) > winThreshold
                val wx = x + cw * (c + 1) - cw * 0.3f
                val wy = top + ch * (r + 1) - ch * 0.3f
                canvas.drawRect(wx, wy, wx + cw * 0.6f, wy + ch * 0.6f, if (lit) winLitPaint else winDarkPaint)
            }
            k++
        }
    }

    private fun drawPipe(canvas: Canvas, p: Pipe) {
        val capH = h * 0.028f
        val capOver = pipeWidth * 0.12f
        // Верхний дом свисает сверху (карниз у проёма снизу).
        drawTower(canvas, p, p.x, 0f, p.x + p.width, p.gapTop, capOver, capH, roofAtBottom = true)
        // Нижний дом стоит на земле (карниз у проёма сверху).
        drawTower(canvas, p, p.x, p.gapBottom, p.x + p.width, groundY, capOver, capH, roofAtBottom = false)
    }

    private fun drawTower(
        canvas: Canvas, p: Pipe,
        left: Float, top: Float, right: Float, bottom: Float,
        capOver: Float, capH: Float, roofAtBottom: Boolean
    ) {
        val bw = right - left
        val bh = bottom - top
        if (bh <= 0f) return
        // Корпус + тёмная грань справа для объёма.
        canvas.drawRect(left, top, right, bottom, obsBody)
        canvas.drawRect(right - bw * 0.16f, top, right, bottom, obsEdge)
        // Сетка окон (устойчивый узор по seed).
        val cols = 3
        val rowH = pipeWidth * 0.5f
        val rows = (bh / rowH).toInt().coerceAtLeast(1)
        val cw = bw / (cols + 1)
        val ch = bh / (rows + 1)
        for (r in 0 until rows) for (c in 0 until cols) {
            val lit = hash(p.seed + r * 17 + c * 5 + if (roofAtBottom) 900 else 0) > 0.5f
            val wx = left + cw * (c + 1) - cw * 0.28f
            val wy = top + ch * (r + 1) - ch * 0.28f
            canvas.drawRect(wx, wy, wx + cw * 0.56f, wy + ch * 0.56f, if (lit) obsWinLit else obsWinDark)
        }
        // Карниз-крыша у проёма (нависает).
        if (roofAtBottom) {
            canvas.drawRect(left - capOver, bottom - capH, right + capOver, bottom, obsRoof)
        } else {
            canvas.drawRect(left - capOver, top, right + capOver, top + capH, obsRoof)
        }
    }

    private fun drawGround(canvas: Canvas) {
        val block = w * 0.05f
        canvas.drawRect(0f, groundY, w, h, pavementPaint)
        canvas.drawRect(0f, groundY, w, groundY + block * 0.35f, curbPaint)
        // Вертикальные швы плитки (едут с прокруткой).
        val tile = w * 0.12f
        val off = scroll % tile
        var x = -off
        while (x < w) {
            canvas.drawRect(x, groundY + block * 0.5f, x + block * 0.14f, h, tilePaint)
            x += tile
        }
        // Горизонтальный шов.
        val y = groundY + groundHeight * 0.5f
        canvas.drawRect(0f, y, w, y + block * 0.14f, tilePaint)
    }

    private fun drawBird(canvas: Canvas) {
        val p = birdPixel
        canvas.save()
        canvas.translate(bird.x, bird.y)
        val tilt = (bird.velocity / (h * 1.4f)).coerceIn(-0.5f, 0.9f)
        canvas.rotate(Math.toDegrees(tilt.toDouble()).toFloat())
        val ox = -birdBody[0].length * p / 2f
        val oy = -birdBody.size * p / 2f
        drawSprite(canvas, birdBody, ox, oy, p)
        val wing = when ((System.currentTimeMillis() / 90) % 4) {
            0L -> wingUp; 1L -> wingMid; 2L -> wingDown; else -> wingMid
        }
        drawSprite(canvas, wing, ox + wingCol * p, oy + wingRow * p, p)
        canvas.restore()
    }

    private fun drawSprite(canvas: Canvas, rows: Array<String>, ox: Float, oy: Float, p: Float) {
        for (r in rows.indices) {
            val row = rows[r]
            for (c in row.indices) {
                val paint = px[row[c]] ?: continue
                val l = ox + c * p
                val t = oy + r * p
                canvas.drawRect(l, t, l + p + 0.6f, t + p + 0.6f, paint)
            }
        }
    }

    private fun drawLoading(canvas: Canvas) {
        val size = w * 0.14f
        val cx = w / 2f
        pixelTitle(canvas, "FLAPPY", cx, h * 0.30f, size)
        pixelTitle(canvas, "BIRD", cx, h * 0.30f + size * 1.2f, size)

        val barW = w * 0.62f
        val barH = h * 0.028f
        val barX = (w - barW) / 2f
        val barY = h * 0.56f
        val bd = w * 0.012f
        canvas.drawRect(barX - bd, barY - bd, barX + barW + bd, barY + barH + bd, barBorderPaint)
        canvas.drawRect(barX, barY, barX + barW, barY + barH, barBgPaint)
        val progress = ((System.currentTimeMillis() - loadStart).toFloat() / loadDuration).coerceIn(0f, 1f)
        canvas.drawRect(barX, barY, barX + barW * progress, barY + barH, barFillPaint)
    }

    private fun drawHud(canvas: Canvas) {
        when (state) {
            State.READY -> {
                pixelTitle(canvas, "FLAPPY", w / 2f, h * 0.16f, w * 0.12f)
                pixelTitle(canvas, "BIRD", w / 2f, h * 0.16f + w * 0.12f * 1.2f, w * 0.12f)
                centeredText(canvas, "Нажми, чтобы начать", w / 2f, h * 0.60f, w * 0.05f)
                if (best > 0) centeredText(canvas, "Рекорд: $best", w / 2f, h * 0.67f, w * 0.05f)
                drawThemeButton(canvas)
            }
            State.PLAYING -> centeredText(canvas, "$score", w / 2f, h * 0.16f, w * 0.14f)
            State.GAME_OVER -> {
                centeredText(canvas, "$score", w / 2f, h * 0.14f, w * 0.1f)
                pixelTitle(canvas, "GAME", w / 2f, h * 0.34f, w * 0.14f)
                pixelTitle(canvas, "OVER", w / 2f, h * 0.34f + w * 0.14f * 1.2f, w * 0.14f)
                centeredText(canvas, "Рекорд: $best", w / 2f, h * 0.60f, w * 0.055f)
                centeredText(canvas, "Тап — заново", w / 2f, h * 0.67f, w * 0.055f)
            }
            else -> {}
        }
    }

    private fun drawThemeButton(canvas: Canvas) {
        val r = themeBtn.height() * 0.28f
        canvas.drawRoundRect(themeBtn, r, r, btnPanelPaint)
        centeredText(canvas, "Theme", themeBtn.centerX(), themeBtn.centerY(), themeBtn.height() * 0.5f, btnTextColor)
    }

    private fun centeredText(canvas: Canvas, text: String, cx: Float, cy: Float, size: Float, color: Int = textWhite) =
        blitText(canvas, buildText(text, size, color, false), cx, cy, size)

    private fun pixelTitle(canvas: Canvas, text: String, cx: Float, cy: Float, size: Float) =
        blitText(canvas, buildText(text, size, 0, true), cx, cy, size)

    private fun blitText(canvas: Canvas, bmp: Bitmap, cx: Float, cy: Float, size: Float) {
        val scale = (size / 11f).toInt().coerceIn(2, 12)
        val dw = bmp.width * scale.toFloat()
        val dh = bmp.height * scale.toFloat()
        val left = cx - dw / 2f
        val top = cy - dh / 2f
        canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), blitPaint)
    }

    /** Мелкий Bitmap строки: тёмная обводка + заливка (сплошная или градиент). */
    private fun buildText(text: String, size: Float, fill: Int, gradient: Boolean): Bitmap {
        val scale = (size / 11f).toInt().coerceIn(2, 12)
        val src = size / scale
        val key = "$text|${src.toInt()}|$fill|$gradient"
        textCache[key]?.let { return it }
        if (textCache.size > 128) textCache.clear()

        srcTextPaint.textSize = src
        srcTextPaint.shader = null
        val fm = srcTextPaint.fontMetricsInt
        val pad = 2
        val tw = Math.ceil(srcTextPaint.measureText(text).toDouble()).toInt().coerceAtLeast(1) + pad * 2
        val th = (fm.descent - fm.ascent).coerceAtLeast(1) + pad * 2
        val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val baseX = pad.toFloat()
        val baseY = (-fm.ascent + pad).toFloat()
        srcTextPaint.color = outlineColor
        for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            c.drawText(text, baseX + dx, baseY + dy, srcTextPaint)
        }
        if (gradient) {
            srcTextPaint.shader = LinearGradient(
                0f, baseY + fm.ascent, 0f, baseY + fm.descent, titleTop, titleBottom, Shader.TileMode.CLAMP
            )
        } else {
            srcTextPaint.color = fill
        }
        c.drawText(text, baseX, baseY, srcTextPaint)
        srcTextPaint.shader = null
        textCache[key] = bmp
        return bmp
    }

    private fun hash(i: Int): Float {
        var x = i * 374761393 + 668265263
        x = (x xor (x shr 13)) * 1274126177
        x = x xor (x shr 16)
        return (x and 0x7fffffff) / 2147483647f
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        w = width.toFloat(); h = height.toFloat()
        setupWorld()
        thread = GameThread(holder).also { it.running = true; it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        w = width.toFloat(); h = height.toFloat()
        setupWorld()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopThread()
    }

    fun pause() {
        gameOver()
    }

    fun resume() {}

    private fun stopThread() {
        val t = thread ?: return
        t.running = false
        var retry = true
        while (retry) {
            try { t.join(); retry = false } catch (_: InterruptedException) {}
        }
        thread = null
    }

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
                if (dt > 0.05f) dt = 0.05f
                update(dt)
                var canvas: Canvas? = null
                try {
                    canvas = holder.lockCanvas()
                    if (canvas != null) synchronized(holder) { render(canvas) }
                } finally {
                    if (canvas != null) holder.unlockCanvasAndPost(canvas)
                }
                val elapsed = System.currentTimeMillis() - start
                val sleep = targetFrameMs - elapsed
                if (sleep > 0) {
                    try { sleep(sleep) } catch (_: InterruptedException) {}
                }
            }
        }
    }
}
