package com.example.flappybirdgame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withSave
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ядро игры: SurfaceView + игровой поток. Локация — дневной город. Вся графика
 * пиксельная. В главном меню есть кнопка настроек (звук) и кнопка магазина скинов.
 *
 * Технический агент — состояние, физика, коллизии, ввод, рекорд, скины, цикл.
 * Визуальный агент — методы draw*, спрайты, палитры скинов, магазин, экран загрузки.
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

    /** Выбранный скин птицы: 0 — базовая, 1 — человек-паук, 2 — бэтмен. */
    private var skin = 0

    // ---- Монеты и покупка скинов ----
    private var coins = 0
    /** Стоимость скинов в монетах (индекс = скин). Базовый бесплатный. */
    private val skinPrices = intArrayOf(0, 10, 20)
    /** Открыт ли скин (куплен). Базовый открыт всегда. */
    private val unlocked = booleanArrayOf(true, false, false)

    /** Единый замок для синхронизации игрового потока и обработчика касаний. */
    private val lock = Any()

    private var loadStart = 0L
    private val loadDuration = 2400L
    private var hasLoaded = false

    private val prefs = context.getSharedPreferences("flappy", Context.MODE_PRIVATE)

    // ---- Звук ----
    private val sound = SoundEngine(context)

    // ---- Настройки (окно на главном меню) ----
    private var settingsOpen = false
    private var activeSlider = 0                 // 0 — нет, 1 — музыка, 2 — звуки
    private val gearBtn = RectF()                // шестерёнка на главном меню
    private val panel = RectF()
    private val closeBtn = RectF()
    private val musicTrack = RectF()
    private val soundTrack = RectF()
    private val musicMuteBtn = RectF()
    private val soundMuteBtn = RectF()

    // ---- Магазин скинов ----
    private var shopOpen = false
    private val shopBtn = RectF()                // кнопка магазина на главном меню (под шестернёй)
    private val shopBackBtn = RectF()            // стрелка «назад» в магазине
    private val skinSlots = Array(3) { RectF() } // ряд иконок-скинов в магазине

    // Палитра шестерёнки/контролов (серые).
    private val gearBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(150, 152, 158) }
    private val gearInk = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 20, 22) }
    private val dimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val ctrlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(104, 108, 118) }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(245, 246, 250) }
    private val muteOffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(196, 66, 60) }
    private val slashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(250, 250, 250); style = Paint.Style.STROKE
    }

    // Палитра магазина: кнопка/экран жёлтые, иконка/стрелка — белые.
    private val shopBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 200, 40) }
    private val shopInk = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 255, 255) }
    private val shopScreenPaint = Paint().apply { color = Color.rgb(255, 200, 40) }
    private val skinSlotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 214, 92) }
    private val skinSelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 255, 255); style = Paint.Style.STROKE
    }
    // Оранжевые полоски, обрамляющие ряд скинов в магазине.
    private val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 150, 20) }
    // Затемнение поверх закрытого (не купленного) скина.
    private val lockDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }

    // Монета: золотой кружок с ободком, внутренним кольцом и бликом.
    private val coinRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(190, 140, 20) }
    private val coinFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 206, 55) }
    private val coinEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(210, 158, 28); style = Paint.Style.STROKE
    }
    private val coinShinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 242, 190) }

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

    // Скины-тела: тот же силуэт и размер (13×11), что у базовой птицы, но с
    // характерными деталями. Цвета символов берутся из палитры скина.
    // Человек-паук: маска с крупным белым глазом и диагональные нити паутины (K).
    private val spiderBody = arrayOf(
        "....KKKK.....",
        "..KKHHHHKK...",
        ".KHYKYYWWPK..",
        ".KYKYYYWWPKO.",
        "KHYYKYYWWPKOO",
        "KYKYYKYYYYKO.",
        "KYYKYYKYYYK..",
        ".KYYKYYKYYK..",
        ".KKYYKYYKK...",
        "..KKYYYYKK...",
        "....KKKK....."
    )

    // Бэтмен: вид сбоку, смотрит вправо (как базовая птица) — один белый глаз.
    // Остроконечные «уши» сверху, жёлтая эмблема на груди, клюв — жёлтый акцент.
    private val batmanBody = arrayOf(
        "....K..K.....",
        "..KKHHHHKK...",
        ".KHHHYYYYWK..",
        ".KHYYYYYWPKO.",
        "KHYYYYYYWPKOO",
        "KYYYYYYYYYKO.",
        "KYYYOOOYYYK..",
        ".KYYYOYYYYK..",
        ".KKYYYYYYK...",
        "..KKYYYYKK...",
        "....KKKK....."
    )

    // Индекс совпадает с skinPalettes: 0 — базовая, 1 — человек-паук, 2 — бэтмен.
    private val skinBodies = arrayOf(birdBody, spiderBody, batmanBody)

    // ---- Paint ----
    // Общая палитра (облака и т.п.).
    private val px = HashMap<Char, Paint>().apply {
        put('w', Paint().apply { color = Color.rgb(248, 252, 255) })
        put('c', Paint().apply { color = Color.rgb(210, 230, 248) })
    }

    /**
     * Палитры скинов. Силуэт птицы у всех одинаковый (birdBody/wing*), меняются
     * только цвета символов: K — контур, H — светлый корпус, Y — корпус, W — глаз,
     * P — зрачок, O — клюв/акцент, G — крыло.
     */
    private fun palette(vararg pairs: Pair<Char, Int>) = HashMap<Char, Paint>().apply {
        for ((ch, col) in pairs) put(ch, Paint().apply { color = col })
    }

    private val skinPalettes = arrayOf(
        // 0 — базовая жёлтая птица.
        palette(
            'Y' to Color.rgb(255, 205, 45), 'H' to Color.rgb(255, 233, 130),
            'W' to Color.rgb(255, 255, 255), 'P' to Color.rgb(35, 30, 35),
            'K' to Color.rgb(70, 45, 25), 'O' to Color.rgb(255, 150, 30),
            'G' to Color.rgb(235, 160, 35)
        ),
        // 1 — человек-паук: красный корпус, чёрные нити паутины/контур, белый
        // глаз, синее крыло; клюв под цвет корпуса.
        palette(
            'Y' to Color.rgb(206, 38, 38), 'H' to Color.rgb(232, 74, 74),
            'W' to Color.rgb(245, 245, 250), 'P' to Color.rgb(12, 12, 16),
            'K' to Color.rgb(24, 22, 30), 'O' to Color.rgb(198, 34, 34),
            'G' to Color.rgb(40, 78, 200)
        ),
        // 2 — бэтмен: тёмно-серый корпус, чёрный «плащ»-крыло, жёлтый акцент.
        palette(
            'Y' to Color.rgb(60, 64, 74), 'H' to Color.rgb(92, 98, 112),
            'W' to Color.rgb(236, 240, 255), 'P' to Color.rgb(10, 10, 14),
            'K' to Color.rgb(14, 15, 20), 'O' to Color.rgb(242, 200, 42),
            'G' to Color.rgb(20, 22, 28)
        )
    )

    private val bandPaint = Paint()
    private val celestialPaint = Paint()

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

    // Небо (полосы).
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

    // Обычный сглаженный шрифт для всего текста, кроме крупных заголовков.
    private val uiTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    init {
        holder.addCallback(this)
        isFocusable = true
        best = prefs.getInt("best", 0)
        coins = prefs.getInt("coins", 0)
        val mask = prefs.getInt("unlocked", 1)
        for (i in unlocked.indices) unlocked[i] = i == 0 || (mask and (1 shl i)) != 0
        skin = prefs.getInt("skin", 0).coerceIn(0, skinPalettes.size - 1)
        if (!unlocked[skin]) skin = 0   // на всякий случай: не даём носить незакрытый скин
        sound.musicVolume = prefs.getFloat("musicVol", 0.6f)
        sound.soundVolume = prefs.getFloat("soundVol", 0.8f)
        sound.musicMuted = prefs.getBoolean("musicMuted", false)
        sound.soundMuted = prefs.getBoolean("soundMuted", false)
        Thread { sound.init() }.start()   // генерация WAV не должна блокировать UI
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
        layoutSettings()

        applyTheme()
        resetGame()
        if (!hasLoaded) {
            state = State.LOADING
            loadStart = System.currentTimeMillis()
        }
    }

    /** Дневная палитра города. */
    private fun applyTheme() {
        skyBands = intArrayOf(
            Color.rgb(58, 140, 230), Color.rgb(86, 162, 240),
            Color.rgb(120, 190, 250), Color.rgb(162, 214, 255)
        )
        celestialPaint.color = Color.rgb(255, 231, 120)
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
        obsBody.color = Color.rgb(180, 186, 194)   // тело дома-препятствия серое
        obsEdge.color = Color.rgb(150, 156, 164)   // боковая грань темнее
        obsRoof.color = Color.rgb(120, 126, 134)   // крыша самая тёмная
        obsWinLit.color = Color.rgb(224, 228, 234)
        obsWinDark.color = Color.rgb(138, 144, 152)
        titleTop = Color.rgb(255, 224, 90)         // жёлто-оранжевый
        titleBottom = Color.rgb(232, 120, 24)
        textCache.clear()
    }

    /** Геометрия шестерёнки, кнопки магазина и окна настроек (зависит от размеров экрана). */
    private fun layoutSettings() {
        val g = w * 0.11f
        gearBtn.set(w - g - w * 0.04f, h * 0.03f, w - w * 0.04f, h * 0.03f + g)
        // Кнопка магазина — под шестернёй, тот же размер и правый отступ.
        val shopTop = gearBtn.bottom + g * 0.28f
        shopBtn.set(gearBtn.left, shopTop, gearBtn.right, shopTop + g)

        // Экран магазина: стрелка «назад» слева сверху и ряд скинов по центру.
        shopBackBtn.set(w * 0.05f, h * 0.05f, w * 0.05f + g, h * 0.05f + g)
        val slot = w * 0.22f
        val gap = w * 0.06f
        val n = skinSlots.size
        val rowW = n * slot + (n - 1) * gap
        val startX = (w - rowW) / 2f
        val cy = h * 0.46f
        for (i in skinSlots.indices) {
            val l = startX + i * (slot + gap)
            skinSlots[i].set(l, cy - slot / 2f, l + slot, cy + slot / 2f)
        }

        val pw = w * 0.86f
        val ph = h * 0.42f
        val pl = (w - pw) / 2f
        val pt = (h - ph) / 2f
        panel.set(pl, pt, pl + pw, pt + ph)

        val cb = pw * 0.10f
        closeBtn.set(pl + pw - cb - pw * 0.04f, pt + pw * 0.04f, pl + pw - pw * 0.04f, pt + pw * 0.04f + cb)

        val trackLeft = pl + pw * 0.08f
        val trackRight = pl + pw * 0.64f
        val trackH = h * 0.014f
        val musicY = pt + ph * 0.36f
        val soundY = pt + ph * 0.60f
        musicTrack.set(trackLeft, musicY - trackH / 2f, trackRight, musicY + trackH / 2f)
        soundTrack.set(trackLeft, soundY - trackH / 2f, trackRight, soundY + trackH / 2f)

        val ms = ph * 0.12f          // квадратная кнопка мьюта
        val muteL = pl + pw * 0.74f
        musicMuteBtn.set(muteL, musicY - ms / 2f, muteL + ms, musicY + ms / 2f)
        soundMuteBtn.set(muteL, soundY - ms / 2f, muteL + ms, soundY + ms / 2f)
    }

    private fun resetGame() {
        bird = Bird(w * 0.28f, groundY / 2f, birdRadius, gravity, flapVelocity)
        pipes.clear()
        score = 0
        state = State.READY
    }

    private fun selectSkin(index: Int) {
        if (index !in skinPalettes.indices) return
        skin = index
        prefs.edit { putInt("skin", skin) }
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
                    if (!p.passed && p.x + p.width < bird.x) {
                        p.passed = true; score++; sound.playScore()
                        if (score % 5 == 0) {   // каждые 5 очков — монета
                            coins++
                            prefs.edit { putInt("coins", coins) }
                        }
                    }
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
            prefs.edit { putInt("best", best) }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x; val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                synchronized(lock) {
                    when (state) {
                        State.LOADING -> {}
                        State.READY ->
                            if (shopOpen) shopDown(x, y)
                            else if (settingsOpen) settingsDown(x, y)
                            else if (gearBtn.contains(x, y)) settingsOpen = true
                            else if (shopBtn.contains(x, y)) shopOpen = true
                            else { state = State.PLAYING; bird.reset(bird.y); bird.flap() }
                        State.PLAYING -> bird.flap()
                        State.GAME_OVER -> resetGame()
                    }
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (settingsOpen && activeSlider != 0) { sliderTo(x); return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeSlider != 0) {
                    prefs.edit {
                        putFloat("musicVol", sound.musicVolume)
                        putFloat("soundVol", sound.soundVolume)
                    }
                    activeSlider = 0
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Обработка нажатия внутри окна настроек. */
    private fun settingsDown(x: Float, y: Float) {
        if (closeBtn.contains(x, y)) { settingsOpen = false; return }
        if (musicMuteBtn.contains(x, y)) {
            sound.musicMuted = !sound.musicMuted
            prefs.edit { putBoolean("musicMuted", sound.musicMuted) }
            sound.applyMusicVolume(); return
        }
        if (soundMuteBtn.contains(x, y)) {
            sound.soundMuted = !sound.soundMuted
            prefs.edit { putBoolean("soundMuted", sound.soundMuted) }; return
        }
        if (hitTrack(musicTrack, x, y)) { activeSlider = 1; sliderTo(x); return }
        if (hitTrack(soundTrack, x, y)) { activeSlider = 2; sliderTo(x); return }
        if (!panel.contains(x, y)) settingsOpen = false   // тап мимо окна — закрыть
    }

    /**
     * Системная кнопка «Назад»: закрывает открытое окно (магазин/настройки).
     * Возвращает true, если событие поглощено; false — обрабатывать по умолчанию.
     */
    fun onBackPressed(): Boolean = synchronized(lock) {
        when {
            shopOpen -> { shopOpen = false; true }
            settingsOpen -> { settingsOpen = false; activeSlider = 0; true }
            else -> false
        }
    }

    /** Обработка нажатия на экране магазина. */
    private fun shopDown(x: Float, y: Float) {
        if (shopBackBtn.contains(x, y)) { shopOpen = false; return }
        for (i in skinSlots.indices) {
            if (skinSlots[i].contains(x, y)) { tapSkin(i); return }
        }
    }

    /** Тап по иконке скина: если открыт — надеть, иначе купить (при хватке монет). */
    private fun tapSkin(index: Int) {
        if (index !in unlocked.indices) return
        if (unlocked[index]) { selectSkin(index); return }
        if (coins >= skinPrices[index]) {
            coins -= skinPrices[index]
            unlocked[index] = true
            var mask = 0
            for (j in unlocked.indices) if (unlocked[j]) mask = mask or (1 shl j)
            prefs.edit { putInt("coins", coins); putInt("unlocked", mask) }
            selectSkin(index)
        }
        // недостаточно монет — ничего не делаем
    }

    private fun hitTrack(t: RectF, x: Float, y: Float): Boolean {
        val padY = t.height() * 2f
        return x >= t.left - t.height() && x <= t.right + t.height() &&
            y >= t.top - padY && y <= t.bottom + padY
    }

    private fun sliderTo(x: Float) {
        val t = if (activeSlider == 1) musicTrack else soundTrack
        val v = ((x - t.left) / t.width()).coerceIn(0f, 1f)
        if (activeSlider == 1) { sound.musicVolume = v; sound.applyMusicVolume() }
        else sound.soundVolume = v
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ---- Рендеринг ----
    fun render(canvas: Canvas) {
        if (w == 0f) return
        // Магазин — отдельный полноэкранный экран поверх игры.
        if (shopOpen && state == State.READY) { drawShop(canvas); return }
        drawSky(canvas)
        drawClouds(canvas)
        drawCity(canvas, cityFarPaint, w * 0.26f, 0.30f, 0.22f, 0.25f)
        drawCity(canvas, cityNearPaint, w * 0.17f, 0.16f, 0.20f, 0.45f)
        if (state != State.LOADING) for (p in pipes) drawPipe(canvas, p)
        drawGround(canvas)
        if (state != State.LOADING) drawBird(canvas)
        if (state == State.LOADING) drawLoading(canvas) else { drawHud(canvas); drawCoinHud(canvas) }
        if (settingsOpen && state == State.READY) drawSettings(canvas)
    }

    private fun drawSky(canvas: Canvas) {
        val n = skyBands.size
        val bandH = groundY / n
        for (i in 0 until n) {
            bandPaint.color = skyBands[i]
            canvas.drawRect(0f, i * bandH, w, (i + 1) * bandH + 1f, bandPaint)
        }
        // Солнце слева сверху.
        val s = w * 0.10f
        val sx = w * 0.10f; val sy = h * 0.10f
        canvas.drawRect(sx, sy, sx + s, sy + s, celestialPaint)
        val c = s * 0.22f
        canvas.drawRect(sx - c, sy + c, sx, sy + s - c, celestialPaint)
        canvas.drawRect(sx + s, sy + c, sx + s + c, sy + s - c, celestialPaint)
        canvas.drawRect(sx + c, sy - c, sx + s - c, sy, celestialPaint)
        canvas.drawRect(sx + c, sy + s, sx + s - c, sy + s + c, celestialPaint)
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
        canvas.withSave {
            translate(bird.x, bird.y)
            val tilt = (bird.velocity / (h * 1.4f)).coerceIn(-0.5f, 0.9f)
            rotate(Math.toDegrees(tilt.toDouble()).toFloat())
            val body = skinBodies[skin]
            val ox = -body[0].length * p / 2f
            val oy = -body.size * p / 2f
            val palette = skinPalettes[skin]
            drawSprite(this, body, ox, oy, p, palette)
            val phase = (System.currentTimeMillis() / 90) % 4
            val wing = when (phase) {
                0L -> wingUp; 1L -> wingMid; 2L -> wingDown; else -> wingMid
            }
            drawSprite(this, wing, ox + wingCol * p, oy + wingRow * p, p, palette)
        }
    }

    private fun drawSprite(
        canvas: Canvas, rows: Array<String>, ox: Float, oy: Float, p: Float,
        palette: HashMap<Char, Paint> = px
    ) {
        for (r in rows.indices) {
            val row = rows[r]
            for (c in row.indices) {
                val paint = palette[row[c]] ?: continue
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
                drawGear(canvas)
                drawShopButton(canvas)
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

    /** Кнопка настроек: серый скруглённый квадрат-фон + чёрная пиксельная шестерёнка. */
    private fun drawGear(canvas: Canvas) {
        val bgR = gearBtn.width() * 0.22f
        canvas.drawRoundRect(gearBtn, bgR, bgR, gearBgPaint)

        val cx = gearBtn.centerX(); val cy = gearBtn.centerY()
        val r = gearBtn.width() * 0.34f     // уменьшена, чтобы был отступ от краёв фона
        val tw = r * 0.26f                  // полуширина зуба
        // 8 зубьев (4 по осям + 4 по диагоналям).
        canvas.drawRect(cx - tw, cy - r, cx + tw, cy - r * 0.5f, gearInk)
        canvas.drawRect(cx - tw, cy + r * 0.5f, cx + tw, cy + r, gearInk)
        canvas.drawRect(cx - r, cy - tw, cx - r * 0.5f, cy + tw, gearInk)
        canvas.drawRect(cx + r * 0.5f, cy - tw, cx + r, cy + tw, gearInk)
        val d = r * 0.5f; val ds = r * 0.22f
        canvas.drawRect(cx - d - ds, cy - d - ds, cx - d + ds, cy - d + ds, gearInk)
        canvas.drawRect(cx + d - ds, cy - d - ds, cx + d + ds, cy - d + ds, gearInk)
        canvas.drawRect(cx - d - ds, cy + d - ds, cx - d + ds, cy + d + ds, gearInk)
        canvas.drawRect(cx + d - ds, cy + d - ds, cx + d + ds, cy + d + ds, gearInk)
        // Тело + отверстие (серое — как «дырка» на фоне).
        val br = r * 0.6f
        canvas.drawRect(cx - br, cy - br, cx + br, cy + br, gearInk)
        val hr = r * 0.24f
        canvas.drawRect(cx - hr, cy - hr, cx + hr, cy + hr, gearBgPaint)
    }

    /** Кнопка магазина: жёлтый скруглённый квадрат + белая иконка витрины. */
    private fun drawShopButton(canvas: Canvas) {
        val b = shopBtn
        val bgR = b.width() * 0.22f
        canvas.drawRoundRect(b, bgR, bgR, shopBgPaint)
        val cx = b.centerX(); val cy = b.centerY()
        val s = b.width() * 0.30f
        // Навес.
        canvas.drawRect(cx - s, cy - s * 0.9f, cx + s, cy - s * 0.4f, shopInk)
        // Корпус.
        canvas.drawRect(cx - s * 0.8f, cy - s * 0.4f, cx + s * 0.8f, cy + s, shopInk)
        // Дверной проём (вырез цветом фона).
        canvas.drawRect(cx - s * 0.25f, cy - s * 0.05f, cx + s * 0.25f, cy + s, shopBgPaint)
    }

    /** Экран магазина: жёлтый фон, стрелка «назад», счётчик монет, ряд скинов. */
    private fun drawShop(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, shopScreenPaint)
        drawBackArrow(canvas)
        drawShopCoins(canvas)

        // Оранжевые полоски сверху и снизу ряда скинов.
        val row = skinSlots[0]
        val left = w * 0.08f
        val right = w - w * 0.08f
        val barH = h * 0.010f
        canvas.drawRect(left, row.top - h * 0.05f, right, row.top - h * 0.05f + barH, stripePaint)
        canvas.drawRect(left, row.bottom + h * 0.05f, right, row.bottom + h * 0.05f + barH, stripePaint)

        for (i in skinSlots.indices) {
            val slot = skinSlots[i]
            val r = slot.height() * 0.16f
            canvas.drawRoundRect(slot, r, r, skinSlotPaint)
            drawSkinPreview(canvas, i, slot)
            if (!unlocked[i]) {
                drawLockedOverlay(canvas, i, slot, r)
            } else if (i == skin) {
                skinSelPaint.strokeWidth = slot.height() * 0.06f
                canvas.drawRoundRect(slot, r, r, skinSelPaint)
            }
        }
    }

    /** Затемнение поверх закрытого скина + его цена (монета + число) по центру. */
    private fun drawLockedOverlay(canvas: Canvas, index: Int, slot: RectF, r: Float) {
        canvas.drawRoundRect(slot, r, r, lockDimPaint)
        val priceTxt = "${skinPrices[index]}"
        val ps = slot.height() * 0.22f
        uiTextPaint.textSize = ps
        val tw = uiTextPaint.measureText(priceTxt)
        val cr = ps * 0.55f
        val gap = ps * 0.25f
        val total = cr * 2f + gap + tw
        val startX = slot.centerX() - total / 2f
        val cy = slot.centerY()
        drawCoin(canvas, startX + cr, cy, cr)
        leftText(canvas, priceTxt, startX + cr * 2f + gap, cy, ps, textWhite)
    }

    /** Счётчик монет в магазине: справа сверху, на уровне стрелки «назад». */
    private fun drawShopCoins(canvas: Canvas) {
        val cy = shopBackBtn.centerY()
        val r = shopBackBtn.height() * 0.46f
        val size = shopBackBtn.height() * 0.78f
        val txt = "$coins"
        uiTextPaint.textSize = size
        val tw = uiTextPaint.measureText(txt)
        val numX = w - w * 0.06f - tw
        drawCoin(canvas, numX - r * 1.3f, cy, r)
        leftText(canvas, txt, numX, cy, size, Color.rgb(70, 45, 0))
    }

    /** Счётчик монет в игре: монета + число в левом верхнем углу. */
    private fun drawCoinHud(canvas: Canvas) {
        val r = w * 0.048f
        val cx = w * 0.11f
        val cy = h * 0.06f
        drawCoin(canvas, cx, cy, r)
        leftText(canvas, "$coins", cx + r * 1.5f, cy, r * 2.1f, textWhite)
    }

    /** Пиксель-независимая золотая монета радиуса r с центром (cx, cy). */
    private fun drawCoin(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        canvas.drawCircle(cx, cy, r, coinRimPaint)
        canvas.drawCircle(cx, cy, r * 0.80f, coinFillPaint)
        coinEdgePaint.strokeWidth = r * 0.13f
        canvas.drawCircle(cx, cy, r * 0.52f, coinEdgePaint)
        canvas.drawCircle(cx - r * 0.30f, cy - r * 0.34f, r * 0.15f, coinShinePaint)
    }

    /** Белая стрелка влево в левом верхнем углу магазина. */
    private fun drawBackArrow(canvas: Canvas) {
        val b = shopBackBtn
        val cx = b.centerX(); val cy = b.centerY()
        val s = b.width() * 0.30f
        val head = Path().apply {
            moveTo(cx - s, cy)
            lineTo(cx, cy - s)
            lineTo(cx, cy + s)
            close()
        }
        canvas.drawPath(head, shopInk)
        canvas.drawRect(cx - s * 0.2f, cy - s * 0.28f, cx + s, cy + s * 0.28f, shopInk)
    }

    /** Превью скина: тот же силуэт птицы, вписанный в ячейку, палитрой скина. */
    private fun drawSkinPreview(canvas: Canvas, index: Int, slot: RectF) {
        val palette = skinPalettes[index]
        val body = skinBodies[index]
        val cols = body[0].length
        val rows = body.size
        val p = slot.width() * 0.72f / cols
        val ox = slot.centerX() - cols * p / 2f
        val oy = slot.centerY() - rows * p / 2f
        drawSprite(canvas, body, ox, oy, p, palette)
        drawSprite(canvas, wingMid, ox + wingCol * p, oy + wingRow * p, p, palette)
    }

    private fun drawSettings(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, dimPaint)
        val pr = panel.height() * 0.06f
        canvas.drawRoundRect(panel, pr, pr, btnPanelPaint)
        centeredText(canvas, "Настройки", panel.centerX(), panel.top + panel.height() * 0.11f,
            panel.height() * 0.08f, btnTextColor)

        drawSlider(canvas, "Музыка", musicTrack, sound.musicVolume, musicMuteBtn, sound.musicMuted)
        drawSlider(canvas, "Звуки", soundTrack, sound.soundVolume, soundMuteBtn, sound.soundMuted)

        // Крестик закрытия.
        val cr = closeBtn.height() * 0.28f
        canvas.drawRoundRect(closeBtn, cr, cr, ctrlPaint)
        centeredText(canvas, "X", closeBtn.centerX(), closeBtn.centerY(), closeBtn.height() * 0.5f, knobPaint.color)
    }

    private fun drawSlider(canvas: Canvas, label: String, track: RectF, value: Float, mute: RectF, muted: Boolean) {
        val cy = track.centerY()
        // Метка над дорожкой + процент справа.
        val labelSize = panel.height() * 0.06f
        leftText(canvas, label, track.left, cy - panel.height() * 0.09f, labelSize, btnTextColor)
        centeredText(canvas, "${(value * 100).toInt()}%", track.right - track.width() * 0.06f,
            cy - panel.height() * 0.09f, labelSize, btnTextColor)
        // Дорожка + заполнение + ползунок.
        val tr = track.height() / 2f
        canvas.drawRoundRect(track, tr, tr, barBgPaint)
        val fillW = track.width() * value
        if (fillW > 0f) canvas.drawRoundRect(
            RectF(track.left, track.top, track.left + fillW, track.bottom), tr, tr, barFillPaint
        )
        val knobX = track.left + fillW
        val kr = track.height() * 1.5f
        canvas.drawRect(knobX - kr * 0.5f, cy - kr, knobX + kr * 0.5f, cy + kr, knobPaint)
        // Кнопка мьюта: динамик; при муте — красный фон и перечёркивание.
        val mr = mute.height() * 0.24f
        canvas.drawRoundRect(mute, mr, mr, if (muted) muteOffPaint else ctrlPaint)
        drawSpeaker(canvas, mute)
        if (muted) {
            slashPaint.strokeWidth = mute.height() * 0.12f
            canvas.drawLine(mute.left + mute.width() * 0.22f, mute.top + mute.height() * 0.22f,
                mute.right - mute.width() * 0.22f, mute.bottom - mute.height() * 0.22f, slashPaint)
        }
    }

    private fun drawSpeaker(canvas: Canvas, b: RectF) {
        val cx = b.centerX(); val cy = b.centerY()
        val s = b.height()
        // Корпус + раструб динамика.
        canvas.drawRect(cx - s * 0.26f, cy - s * 0.1f, cx - s * 0.12f, cy + s * 0.1f, knobPaint)
        canvas.drawRect(cx - s * 0.12f, cy - s * 0.2f, cx + s * 0.06f, cy + s * 0.2f, knobPaint)
        // Звуковые волны.
        canvas.drawRect(cx + s * 0.14f, cy - s * 0.12f, cx + s * 0.2f, cy + s * 0.12f, knobPaint)
        canvas.drawRect(cx + s * 0.26f, cy - s * 0.2f, cx + s * 0.32f, cy + s * 0.2f, knobPaint)
    }

    private fun centeredText(canvas: Canvas, text: String, cx: Float, cy: Float, size: Float, color: Int = textWhite) =
        drawUiText(canvas, text, cx, cy, size, color, Paint.Align.CENTER)

    private fun leftText(canvas: Canvas, text: String, x: Float, cy: Float, size: Float, color: Int = textWhite) =
        drawUiText(canvas, text, x, cy, size, color, Paint.Align.LEFT)

    /** Обычный текст: тонкая тёмная обводка для читаемости на любом фоне. */
    private fun drawUiText(canvas: Canvas, text: String, x: Float, cy: Float, size: Float, color: Int, align: Paint.Align) {
        uiTextPaint.textSize = size
        uiTextPaint.textAlign = align
        val fm = uiTextPaint.fontMetrics
        val baseY = cy - (fm.ascent + fm.descent) / 2f
        val o = size * 0.06f
        uiTextPaint.color = Color.BLACK
        canvas.drawText(text, x - o, baseY, uiTextPaint)
        canvas.drawText(text, x - o, baseY - o, uiTextPaint)
        canvas.drawText(text, x + o, baseY + o, uiTextPaint)
        canvas.drawText(text, x - o, baseY + o, uiTextPaint)
        canvas.drawText(text, x + o, baseY - o, uiTextPaint)
        canvas.drawText(text, x + o, baseY, uiTextPaint)
        canvas.drawText(text, x, baseY - o, uiTextPaint)
        canvas.drawText(text, x, baseY + o, uiTextPaint)
        uiTextPaint.color = color
        canvas.drawText(text, x, baseY, uiTextPaint)
    }

    private fun pixelTitle(canvas: Canvas, text: String, cx: Float, cy: Float, size: Float) =
        blitText(canvas, buildText(text, size), cx, cy, size)

    private fun blitText(canvas: Canvas, bmp: Bitmap, cx: Float, cy: Float, size: Float) {
        val scale = (size / 11f).toInt().coerceIn(2, 12)
        val dw = bmp.width * scale.toFloat()
        val dh = bmp.height * scale.toFloat()
        val left = cx - dw / 2f
        val top = cy - dh / 2f
        canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), blitPaint)
    }

    /** Мелкий Bitmap строки заголовка: тёмная обводка + градиентная заливка. */
    private fun buildText(text: String, size: Float): Bitmap {
        val scale = (size / 11f).toInt().coerceIn(2, 12)
        val src = size / scale
        val key = "$text|${src.toInt()}"
        textCache[key]?.let { return it }
        if (textCache.size > 128) textCache.clear()

        srcTextPaint.textSize = src
        srcTextPaint.shader = null
        val fm = srcTextPaint.fontMetricsInt
        val pad = 2
        val tw = Math.ceil(srcTextPaint.measureText(text).toDouble()).toInt().coerceAtLeast(1) + pad * 2
        val th = (fm.descent - fm.ascent).coerceAtLeast(1) + pad * 2
        val bmp = createBitmap(tw, th)
        val c = Canvas(bmp)
        val baseX = pad.toFloat()
        val baseY = (-fm.ascent + pad).toFloat()
        srcTextPaint.color = outlineColor
        for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            c.drawText(text, baseX + dx, baseY + dy, srcTextPaint)
        }
        srcTextPaint.shader = LinearGradient(
            0f, baseY + fm.ascent, 0f, baseY + fm.descent, titleTop, titleBottom, Shader.TileMode.CLAMP
        )
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
        synchronized(lock) {
            w = width.toFloat(); h = height.toFloat()
            setupWorld()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopThread()
    }

    fun pause() {
        synchronized(lock) { gameOver() }
        sound.onPause()
    }

    fun resume() {
        sound.onResume()
    }

    fun release() {
        sound.release()
    }

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
                // Единый замок: обновление состояния и рендер не должны пересекаться
                // с обработчиком касаний (UI-поток), иначе редкий вылет на списке труб.
                synchronized(lock) { update(dt) }
                var canvas: Canvas? = null
                try {
                    canvas = holder.lockCanvas()
                    if (canvas != null) synchronized(lock) { render(canvas) }
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
