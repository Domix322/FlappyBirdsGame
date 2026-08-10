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
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withSave
import kotlin.math.PI
import kotlin.math.cos
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

    // PAUSED — игра приостановлена кнопкой паузы; всё замирает до возобновления.
    private enum class State { LOADING, READY, PLAYING, PAUSED, GAME_OVER }

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

    /** Выбранный скин птицы. Индексы: 0 база, 1 паук, 2 бэтмен, 3 робот, 4 ниндзя, 5 феникс. */
    private var skin = 0

    /** Кол-во скинов (для достижения «все скины» и раскладки магазина). */
    private val skinCount = 6

    // ---- Монеты и разблокировка скинов ----
    private var coins = 0
    /** Монеты, собранные за текущий забег (для таблицы на экране Game Over). */
    private var coinsThisRun = 0
    /**
     * Сколько труб осталось до следующей трубы с монетой. Монета появляется не на
     * каждой трубе, а случайно раз в 4–7 труб (см. spawnPipe/resetGame).
     */
    private var pipesUntilCoin = 0

    /**
     * Стоимость скина в монетах (индекс = скин). 0 означает, что скин НЕ
     * покупается за монеты — он открывается за рекорд (см. skinRecordReq).
     */
    private val skinPrices = intArrayOf(0, 10, 20, 35, 0, 0)

    /**
     * Порог рекорда для разблокировки скина. 0 — скин покупной за монеты.
     * >0 — скин открывается автоматически, когда лучший счёт достигнет порога.
     */
    private val skinRecordReq = intArrayOf(0, 0, 0, 0, 15, 30)

    /** Открыт ли скин (куплен монетами или разблокирован рекордом). База открыта всегда. */
    private val unlocked = BooleanArray(skinCount) { it == 0 }

    /** true — скин открывается за рекорд (а не покупается за монеты). */
    private fun isRecordSkin(i: Int) = skinRecordReq[i] > 0

    // ---- Прогресс: задания и достижения ----
    // Инициализируется в init (после prefs); поэтому val без lateinit.
    private val progress: Progress
    /** Названия недавно открытых достижений — всплывающая плашка (title, времяДоСкрытия). */
    private var achToast: String? = null
    private var achToastTime = 0f

    // ---- Смена времени суток / погоды ----
    /** Текущая тема оформления: 0 день, 1 закат, 2 ночь. Меняется по мере роста счёта. */
    private var themeIndex = 0
    private val themeCount = 3
    /** Звёзды для ночной темы: пары (x, y) в долях экрана. Заполняется в setupWorld. */
    private var starField = FloatArray(0)

    // ---- Частицы (анимации) ----
    /** Кратковременные эффекты: искры монет, брызги удара, пёрышки взмаха. */
    private val particles = ArrayList<Particle>()

    // ---- Вибрация (полировка) ----
    // VIBRATOR_SERVICE помечен deprecated на новых API, но работает на всех
    // поддерживаемых версиях — этого достаточно для короткого «тик»-отклика.
    @Suppress("DEPRECATION")
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

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
    private val skinSlots = Array(skinCount) { RectF() } // сетка иконок-скинов в магазине (2×3)

    // ---- Экран заданий и достижений ----
    private var trophyOpen = false
    private val trophyBtn = RectF()              // кнопка «цели» на главном меню (под магазином)
    private val trophyBackBtn = RectF()          // стрелка «назад» на экране целей

    // ---- Пауза ----
    private val pauseBtn = RectF()               // кнопка паузы (в игре, правый верх)
    private val resumeBtn = RectF()              // кнопка «продолжить» в оверлее паузы
    private val pauseHomeBtn = RectF()           // кнопка «в меню» в оверлее паузы

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

    // Палитра экрана целей (задания/достижения): зелёные акценты.
    private val trophyBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 170, 100) }
    private val trophyScreenPaint = Paint().apply { color = Color.rgb(48, 96, 150) }

    // Звезда, всплывающая плашка достижения.
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val toastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 44, 60) }

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

    // Робот: металлический корпус, горизонтальный визор (W) вместо глаза,
    // красный «глазок»-индикатор (O).
    private val robotBody = arrayOf(
        "....KKKK.....",
        "..KKHHHHKK...",
        ".KHHHHHHHWK..",
        ".KWWWWWWWWKO.",
        "KHHHHHHHWPKOO",
        "KYYYYYYYYYKO.",
        "KYYYYYYYYYK..",
        ".KYYYYYYYYK..",
        ".KKYYYYYYK...",
        "..KKYYYYKK...",
        "....KKKK....."
    )

    // Ниндзя: тёмная маска, узкая белая прорезь для глаз (W), красная повязка (O).
    private val ninjaBody = arrayOf(
        "....KKKK.....",
        "..KKKKKKKK...",
        ".KKKKKKKKKK..",
        ".KKWWWWWWKKO.",
        "KKKKKKKKKKKOO",
        "KYYYYYYYYYKO.",
        "KYYYYYYYYYK..",
        ".KYYYYYYYYK..",
        ".KKYYYYYYK...",
        "..KKYYYYKK...",
        "....KKKK....."
    )

    // Феникс: огненная палитра, маленький хохолок-язычок пламени (O) сверху.
    private val phoenixBody = arrayOf(
        "....OKKKK....",
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

    // Индекс совпадает с skinPalettes: 0 база, 1 паук, 2 бэтмен, 3 робот, 4 ниндзя, 5 феникс.
    private val skinBodies = arrayOf(birdBody, spiderBody, batmanBody, robotBody, ninjaBody, phoenixBody)

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
        ),
        // 3 — робот: металлический серый корпус, голубой визор, красный индикатор.
        palette(
            'Y' to Color.rgb(150, 158, 170), 'H' to Color.rgb(190, 198, 210),
            'W' to Color.rgb(90, 220, 235), 'P' to Color.rgb(15, 18, 24),
            'K' to Color.rgb(40, 46, 58), 'O' to Color.rgb(230, 70, 60),
            'G' to Color.rgb(120, 128, 140)
        ),
        // 4 — ниндзя: тёмно-синяя маска, белая прорезь глаз, красная повязка.
        palette(
            'Y' to Color.rgb(38, 44, 66), 'H' to Color.rgb(52, 60, 86),
            'W' to Color.rgb(235, 240, 250), 'P' to Color.rgb(10, 12, 18),
            'K' to Color.rgb(16, 18, 28), 'O' to Color.rgb(210, 50, 50),
            'G' to Color.rgb(28, 32, 50)
        ),
        // 5 — феникс: огненный оранжево-жёлтый корпус, красное крыло-пламя.
        palette(
            'Y' to Color.rgb(240, 120, 30), 'H' to Color.rgb(255, 200, 80),
            'W' to Color.rgb(255, 255, 255), 'P' to Color.rgb(40, 20, 10),
            'K' to Color.rgb(120, 30, 20), 'O' to Color.rgb(255, 225, 90),
            'G' to Color.rgb(220, 60, 30)
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

    // Панель таблицы Game Over — постоянно серая; текст берёт цвет темы времени суток.
    private val goPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(206, 209, 216) }

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
        progress = Progress(prefs)
        // Восстанавливаем купленные скины из битовой маски.
        val mask = prefs.getInt("unlocked", 1)
        for (i in unlocked.indices) unlocked[i] = i == 0 || (mask and (1 shl i)) != 0
        refreshRecordUnlocks()          // скины, открываемые за рекорд, — по текущему best
        skin = prefs.getInt("skin", 0).coerceIn(0, skinPalettes.size - 1)
        if (!unlocked[skin]) skin = 0   // на всякий случай: не даём носить незакрытый скин
        sound.musicVolume = prefs.getFloat("musicVol", 0.6f)
        sound.soundVolume = prefs.getFloat("soundVol", 0.8f)
        sound.musicMuted = prefs.getBoolean("musicMuted", false)
        sound.soundMuted = prefs.getBoolean("soundMuted", false)
        Thread { sound.init() }.start()   // генерация WAV не должна блокировать UI
    }

    /**
     * Пересчёт всех размеров и настроек механики под текущий экран (w × h).
     * Все величины заданы как доли ширины/высоты, поэтому игра одинаково
     * ощущается на разных экранах. Это главные «ручки» баланса — крути их,
     * чтобы сделать игру легче/сложнее.
     */
    private fun setupWorld() {
        // --- Геометрия земли ---
        groundHeight = h * 0.14f          // высота полосы «тротуара» снизу (доля высоты экрана)
        groundY = h - groundHeight        // Y-координата верха земли = «пол», по который падает птица

        // --- Физика птицы (px и px/сек) ---
        gravity = h * 3.4f                // сила притяжения вниз. Больше → птица падает быстрее, играть сложнее
        flapVelocity = -h * 0.92f         // импульс вверх по касанию (минус = вверх). Сильнее → выше подскок

        // --- Трубы-препятствия ---
        pipeSpeed = w * 0.55f             // скорость движения труб влево, px/сек. Больше → быстрее, сложнее
        pipeWidth = w * 0.18f             // ширина трубы (дома). Больше → уже коридор по горизонтали
        pipeGap = h * 0.30f               // высота проёма между верхней и нижней трубой. Меньше → сложнее пролететь
        pipeSpacing = w * 0.62f           // горизонтальный интервал между парами труб. Меньше → трубы чаще

        // --- Размер птицы и пикселей спрайтов ---
        birdRadius = w * 0.045f           // радиус круга птицы для коллизий и отрисовки
        birdPixel = birdRadius * 0.185f   // размер одного «пикселя» спрайта птицы (13×11 клеток)
        cloudPixel = w * 0.022f           // размер «пикселя» облаков на фоне
        buildStarField()                  // звёзды для ночной темы (детерминированные позиции)
        layoutSettings()

        applyTheme(themeIndex)
        resetGame()
        if (!hasLoaded) {
            state = State.LOADING
            loadStart = System.currentTimeMillis()
        }
    }

    /** Заполняет звёздное поле (пары x,y в долях экрана) для ночной темы. */
    private fun buildStarField() {
        val n = 40
        val arr = FloatArray(n * 2)
        val rnd = Random(1234)            // фиксированный seed → звёзды не «прыгают» при рестарте
        for (i in 0 until n) {
            arr[i * 2] = rnd.nextFloat()
            arr[i * 2 + 1] = rnd.nextFloat() * 0.6f   // только в верхних 60% экрана
        }
        starField = arr
    }

    /**
     * Применяет палитру города для темы [t]: 0 — день, 1 — закат, 2 — ночь.
     * Тема переключается по мере роста счёта (см. update).
     *
     * Как редактировать: правьте цвета в нужной ветке when, а порядок/число тем
     * — через themeCount и логику выбора темы в update().
     */
    private fun applyTheme(t: Int) {
        when (t) {
            1 -> {   // ---- ЗАКАТ: тёплое оранжево-розовое небо ----
                skyBands = intArrayOf(
                    Color.rgb(252, 140, 70), Color.rgb(250, 170, 110),
                    Color.rgb(248, 196, 150), Color.rgb(250, 220, 180)
                )
                celestialPaint.color = Color.rgb(255, 180, 90)      // низкое оранжевое солнце
                cityFarPaint.color = Color.rgb(170, 130, 140)
                cityNearPaint.color = Color.rgb(140, 100, 120)
                winLitPaint.color = Color.rgb(255, 224, 170)
                winDarkPaint.color = Color.rgb(120, 90, 110)
                winThreshold = 0.45f
                pavementPaint.color = Color.rgb(150, 130, 130)
                curbPaint.color = Color.rgb(180, 158, 150)
                tilePaint.color = Color.rgb(120, 104, 104)
                btnPanelPaint.color = Color.rgb(255, 236, 214)
                btnTextColor = Color.rgb(120, 60, 30)
                obsBody.color = Color.rgb(190, 150, 140)
                obsEdge.color = Color.rgb(160, 122, 116)
                obsRoof.color = Color.rgb(128, 96, 92)
                obsWinLit.color = Color.rgb(255, 226, 176)
                obsWinDark.color = Color.rgb(150, 112, 108)
                titleTop = Color.rgb(255, 224, 90)
                titleBottom = Color.rgb(232, 100, 40)
            }
            2 -> {   // ---- НОЧЬ: тёмно-синее небо, луна, звёзды, снег ----
                skyBands = intArrayOf(
                    Color.rgb(18, 24, 60), Color.rgb(28, 36, 82),
                    Color.rgb(40, 52, 104), Color.rgb(56, 70, 128)
                )
                celestialPaint.color = Color.rgb(232, 236, 250)     // бледная луна
                cityFarPaint.color = Color.rgb(48, 56, 92)
                cityNearPaint.color = Color.rgb(36, 44, 76)
                winLitPaint.color = Color.rgb(255, 226, 140)
                winDarkPaint.color = Color.rgb(30, 38, 66)
                winThreshold = 0.6f
                pavementPaint.color = Color.rgb(46, 52, 72)
                curbPaint.color = Color.rgb(64, 72, 96)
                tilePaint.color = Color.rgb(34, 40, 58)
                btnPanelPaint.color = Color.rgb(40, 50, 80)
                btnTextColor = Color.rgb(220, 228, 250)
                obsBody.color = Color.rgb(52, 58, 88)
                obsEdge.color = Color.rgb(40, 46, 72)
                obsRoof.color = Color.rgb(28, 34, 56)
                obsWinLit.color = Color.rgb(255, 224, 138)
                obsWinDark.color = Color.rgb(36, 44, 70)
                titleTop = Color.rgb(180, 200, 255)
                titleBottom = Color.rgb(120, 130, 220)
            }
            else -> {  // ---- ДЕНЬ (тема 0): голубое небо, солнце ----
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
            }
        }
        // Ночью — это луна (isNight используется в drawSky для звёзд/формы).
        isNight = t == 2
        textCache.clear()
    }

    /** Ночная тема — рисуем звёзды и луну вместо солнца. */
    private var isNight = false

    /** Геометрия шестерёнки, кнопки магазина и окна настроек (зависит от размеров экрана). */
    private fun layoutSettings() {
        val g = w * 0.11f
        gearBtn.set(w - g - w * 0.04f, h * 0.03f, w - w * 0.04f, h * 0.03f + g)
        // Кнопка магазина — под шестернёй, тот же размер и правый отступ.
        val shopTop = gearBtn.bottom + g * 0.28f
        shopBtn.set(gearBtn.left, shopTop, gearBtn.right, shopTop + g)
        // Кнопка «цели» (задания/достижения) — под магазином.
        val trophyTop = shopBtn.bottom + g * 0.28f
        trophyBtn.set(gearBtn.left, trophyTop, gearBtn.right, trophyTop + g)

        // Кнопка паузы во время игры — левый верхний угол (под счётчиком монет).
        // Кнопка паузы — правый верхний угол (крупнее, на месте шестерёнки).
        val pb = g * 1.15f
        pauseBtn.set(w - pb - w * 0.04f, h * 0.03f, w - w * 0.04f, h * 0.03f + pb)
        // Кнопки в оверлее паузы — по центру, друг под другом.
        val bw2 = w * 0.5f; val bh2 = h * 0.08f
        resumeBtn.set((w - bw2) / 2f, h * 0.44f, (w + bw2) / 2f, h * 0.44f + bh2)
        pauseHomeBtn.set((w - bw2) / 2f, h * 0.56f, (w + bw2) / 2f, h * 0.56f + bh2)

        // Экран магазина: стрелка «назад» слева сверху и сетка скинов 2×3 по центру.
        shopBackBtn.set(w * 0.05f, h * 0.05f, w * 0.05f + g, h * 0.05f + g)
        trophyBackBtn.set(shopBackBtn)            // тот же угол на экране целей
        val cols = 3
        val slot = w * 0.24f
        val gapX = w * 0.05f
        val gapY = h * 0.03f
        val rowW = cols * slot + (cols - 1) * gapX
        val startX = (w - rowW) / 2f
        val startY = h * 0.34f
        for (i in skinSlots.indices) {
            val r = i / cols                      // ряд (0 верхний, 1 нижний)
            val c = i % cols                      // колонка
            val l = startX + c * (slot + gapX)
            val t = startY + r * (slot + gapY)
            skinSlots[i].set(l, t, l + slot, t + slot)
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
        particles.clear()
        score = 0
        coinsThisRun = 0
        pipesUntilCoin = Random.nextInt(4, 8)   // до первой монеты — 4..7 труб
        if (themeIndex != 0) { themeIndex = 0; applyTheme(0) }   // каждая партия стартует с дневной темы
        state = State.READY
    }

    private fun selectSkin(index: Int) {
        if (index !in skinPalettes.indices) return
        skin = index
        prefs.edit { putInt("skin", skin) }
    }

    /** Открывает скины, чей порог рекорда уже достигнут текущим best. */
    private fun refreshRecordUnlocks() {
        for (i in unlocked.indices) {
            if (isRecordSkin(i) && best >= skinRecordReq[i]) unlocked[i] = true
        }
    }

    /** Сколько скинов открыто (для достижения «все скины»). */
    private fun skinsOwnedCount(): Int = unlocked.count { it }

    // ---- Растущая сложность ----
    // Как это работает: со счётом растёт «уровень сложности» diffLevel (0..6),
    // от него зависят скорость труб и ширина проёма. Три ручки настройки:
    //   • делитель 18f — КАК БЫСТРО растёт сложность. Больше делитель → медленнее
    //     (новая ступень примерно раз в столько очков). Меньше → резче.
    //   • потолок 6f — СКОЛЬКО ступеней максимум (ограничивает предельную сложность).
    //   • множители 0.06f и 0.03f — НА СКОЛЬКО меняются скорость/проём за ступень.
    // Пример: 6 ступеней × 0.06 = до +36% к скорости; × 0.03 = до −18% к проёму.
    private fun diffLevel(): Float = (score / 18f).coerceAtMost(6f)              // текущая ступень сложности 0..6
    private fun currentSpeed(): Float = pipeSpeed * (1f + diffLevel() * 0.06f)   // трубы едут быстрее с ростом ступени
    private fun currentGap(): Float = pipeGap * (1f - diffLevel() * 0.03f)       // проём сужается с ростом ступени

    // ---- Частицы (анимации) ----
    /** Разлетающиеся квадратики из точки (cx, cy): взрыв удара, искры монеты. */
    private fun burst(cx: Float, cy: Float, count: Int, color: Int, spread: Float, grav: Float, life: Float) {
        repeat(count) {
            val ang = Random.nextFloat() * (2f * PI.toFloat())
            val spd = Random.nextFloat() * spread
            particles.add(
                Particle(
                    cx, cy,
                    cos(ang) * spd, sin(ang) * spd,
                    life * (0.6f + Random.nextFloat() * 0.4f),
                    birdRadius * (0.18f + Random.nextFloat() * 0.18f),
                    color, grav
                )
            )
        }
    }

    /**
     * Короткая вибрация (полировка). Работает при наличии вибромотора.
     * Длительность [ms] по умолчанию 60 мс — увеличьте для более «тяжёлого» отклика.
     */
    private fun vibrate(ms: Long = 60L) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") v.vibrate(ms)
        }
    }

    /** Взмах птицы: звук + пара «пёрышек» позади. Единая точка вызова взмаха. */
    private fun doFlap() {
        bird.flap()
        sound.playFlap()
        // Пёрышки летят назад-вниз от птицы.
        repeat(3) {
            particles.add(
                Particle(
                    bird.x - birdRadius * 0.4f, bird.y,
                    -Random.nextFloat() * w * 0.12f - w * 0.02f,
                    Random.nextFloat() * h * 0.05f,
                    0.4f, birdRadius * 0.16f,
                    skinPalettes[skin]['H']?.color ?: Color.WHITE, h * 0.4f
                )
            )
        }
    }

    /**
     * Создаёт новую пару труб у правого края (atX). Высота проёма фиксирована
     * (pipeGap), а его вертикальное положение выбирается случайно, но с отступом
     * margin от потолка и от земли — чтобы проём не прижимался к краям.
     */
    private fun spawnPipe(atX: Float) {
        val gap = currentGap()                        // текущая высота проёма (уменьшается со сложностью)
        val margin = h * 0.12f                        // «мёртвая зона» у верха и низа, куда проём не ставится
        val minTop = margin                           // самое верхнее положение проёма
        val maxTop = groundY - gap - margin           // самое нижнее (чтобы проём целиком помещался над землёй)
        val gapTop = Random.nextFloat() * (maxTop - minTop) + minTop
        val pipe = Pipe(atX, pipeWidth, gapTop, gap, Random.nextInt())
        // Монета появляется не на каждой трубе, а раз в несколько труб.
        // Как редактировать частоту монет: меняйте диапазон Random.nextInt(4, 8)
        // — это «через сколько труб» появится следующая монета. nextInt(4, 8) даёт
        // 4..7 (верхняя граница не включается). Хотите чаще — уменьшите числа
        // (напр. 2, 5 → каждые 2..4 трубы); реже — увеличьте (напр. 6, 11).
        if (pipesUntilCoin <= 0) {
            pipe.hasCoin = true
            pipesUntilCoin = Random.nextInt(4, 8)     // отсчёт до следующей монеты
        } else {
            pipesUntilCoin--
        }
        pipes.add(pipe)
    }

    /**
     * Один шаг игровой логики за dt секунд (вызывается из игрового потока каждый
     * кадр). Поведение зависит от текущего состояния (state):
     *  LOADING   — экран загрузки, только едет фон.
     *  READY     — главное меню, птица «парит» на месте.
     *  PLAYING   — идёт игра: физика, спавн/движение труб, очки, коллизии.
     *  GAME_OVER — всё замерло до касания (рестарт).
     */
    fun update(dt: Float) {
        if (state == State.PAUSED) return            // на паузе всё замирает
        updateParticles(dt)                          // эффекты живут во всех остальных состояниях
        if (achToastTime > 0f) achToastTime -= dt    // таймер плашки достижения
        when (state) {
            State.LOADING -> {
                scroll += pipeSpeed * 0.2f * dt          // фон медленно едет для «живости» экрана загрузки
                if (System.currentTimeMillis() - loadStart >= loadDuration) {
                    hasLoaded = true
                    state = State.READY                  // загрузка закончилась → главное меню
                }
            }
            State.READY -> {
                scroll += pipeSpeed * 0.2f * dt          // фон едет медленно, как в меню
                // Птица плавно покачивается вверх-вниз (синус) вокруг центра экрана.
                bird.y = groundY / 2f + sin(System.currentTimeMillis() / 200.0).toFloat() * (h * 0.01f)
            }
            State.PLAYING -> {
                val spd = currentSpeed()                 // скорость растёт со счётом (растущая сложность)
                bird.update(dt)                          // гравитация + перемещение птицы
                scroll += spd * dt                       // фон едет со скоростью труб (параллакс от scroll)

                updateTheme()                            // смена времени суток по мере роста счёта

                // Спавн новой пары труб, когда последняя отъехала на pipeSpacing от края.
                if (pipes.isEmpty() || pipes.last().x < w - pipeSpacing) spawnPipe(w)

                val iter = pipes.iterator()
                while (iter.hasNext()) {
                    val p = iter.next()
                    p.update(dt, spd)                    // двигаем трубу влево

                    // НАЧИСЛЕНИЕ ОЧКА: труба целиком прошла левее птицы и ещё не засчитана.
                    // Хотите давать больше очков за трубу — измените score++ (напр. score += 2).
                    // Награды-монеты от заданий прибавляются к coins и сохраняются в prefs.
                    if (!p.passed && p.x + p.width < bird.x) {
                        p.passed = true; score++; sound.playScore()
                        coins += progress.onPipe()       // задания «пройди N труб» могут начислить монеты
                        coins += progress.reportScore(score)  // задания «набери N очков»
                        prefs.edit { putInt("coins", coins) }
                    }

                    // ПОДБОР МОНЕТЫ в проёме: если круг птицы накрыл монету — забираем.
                    // coinPickR — радиус подбора (насколько близко надо подлететь); задаётся ниже.
                    // coins++ — сколько даём за одну монету (поменяйте, чтобы монета стоила больше).
                    if (p.hasCoin && !p.coinCollected) {
                        val dx = bird.x - p.coinX; val dy = bird.y - p.coinY
                        val rr = bird.radius + coinPickR
                        if (dx * dx + dy * dy <= rr * rr) {
                            p.coinCollected = true
                            coins++; coinsThisRun++      // +1 к общим монетам и к монетам за забег
                            coins += progress.onCoin()   // задания «собери N монет»
                            prefs.edit { putInt("coins", coins) }
                            sound.playScore()
                            burst(p.coinX, p.coinY, 10, Color.rgb(255, 214, 92), w * 0.35f, 0f, 0.5f)  // искры подбора
                        }
                    }

                    if (p.collidesWith(bird.x, bird.y, bird.radius, groundY)) gameOver()  // врезались в трубу
                    if (p.isOffScreen()) iter.remove()   // ушедшую за левый край убираем из списка
                }

                // Столкновение с землёй — конец игры (птицу прижимаем к полу).
                if (bird.y + bird.radius >= groundY) { bird.y = groundY - bird.radius; gameOver() }
                // Потолок не убивает, но не даём улететь за верх экрана.
                if (bird.y - bird.radius < 0f) bird.y = bird.radius
            }
            State.PAUSED -> {}                            // всё замерло, ждём «продолжить»
            State.GAME_OVER -> {}                         // ждём касания; рестарт делает onTouchEvent
        }
    }

    /** Радиус подбора монеты (доля радиуса птицы). */
    private val coinPickR get() = birdRadius * 0.75f

    /** Обновление и удаление отживших эффект-частиц. */
    private fun updateParticles(dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.update(dt)
            if (p.dead) it.remove()
        }
    }

    /**
     * Смена времени суток по счёту: день → закат → ночь → день → …
     *
     * Как редактировать:
     *   • число 12 — через сколько очков меняется тема. Больше → тема держится
     *     дольше; меньше → меняется чаще.
     *   • themeCount — сколько тем в цикле (сами палитры — в applyTheme).
     * Порядок тем задаётся индексом: 0 день, 1 закат, 2 ночь.
     */
    private fun updateTheme() {
        val target = (score / 12) % themeCount
        if (target != themeIndex) {
            themeIndex = target
            applyTheme(themeIndex)
        }
    }

    /** Завершение партии: рекорд, достижения, эффект удара, вибрация. */
    private fun gameOver() {
        if (state != State.PLAYING) return   // защита от повторного вызова (труба + земля в одном кадре)
        state = State.GAME_OVER
        burst(bird.x, bird.y, 18, skinPalettes[skin]['Y']?.color ?: Color.WHITE, w * 0.5f, h * 0.6f, 0.7f)  // брызги удара
        vibrate()                            // тактильный отклик (длительность по умолчанию)
        if (score > best) {
            best = score
            prefs.edit { putInt("best", best) }   // рекорд переживает перезапуск приложения
            refreshRecordUnlocks()                // возможно, открылся скин за рекорд
        }
        progress.onGameFinished()
        val newly = progress.refreshAchievements(best, skinsOwnedCount())
        if (newly.isNotEmpty()) { achToast = newly.first(); achToastTime = 3.5f }  // покажем плашку
    }

    /**
     * Главный обработчик ввода. Одно касание (тап) — универсальное действие,
     * смысл которого зависит от состояния игры:
     *   READY     — тап по кнопке открывает магазин/настройки, иначе начинает игру.
     *   PLAYING   — «взмах»: толчок птицы вверх.
     *   GAME_OVER — рестарт.
     * ACTION_MOVE/UP используются только для перетаскивания ползунков громкости.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x; val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                synchronized(lock) {
                    when (state) {
                        State.LOADING -> {}                              // во время загрузки касания игнорируем
                        State.READY ->
                            if (shopOpen) shopDown(x, y)                 // открыт магазин → его обработчик
                            else if (trophyOpen) trophyDown(x, y)        // открыт экран целей → его обработчик
                            else if (settingsOpen) settingsDown(x, y)    // открыты настройки → их обработчик
                            else if (gearBtn.contains(x, y)) settingsOpen = true   // тап по шестерёнке
                            else if (shopBtn.contains(x, y)) shopOpen = true       // тап по кнопке магазина
                            else if (trophyBtn.contains(x, y)) trophyOpen = true   // тап по кнопке целей
                            else { state = State.PLAYING; bird.reset(bird.y); doFlap() }  // старт игры + первый взмах
                        State.PLAYING ->
                            if (pauseBtn.contains(x, y)) state = State.PAUSED   // тап по паузе
                            else doFlap()                                // взмах вверх (звук + пёрышки)
                        State.PAUSED ->
                            if (resumeBtn.contains(x, y)) state = State.PLAYING       // продолжить
                            else if (pauseHomeBtn.contains(x, y)) resetGame()         // выйти в меню
                        State.GAME_OVER -> resetGame()                   // тап после проигрыша → новая попытка
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
            trophyOpen -> { trophyOpen = false; true }
            settingsOpen -> { settingsOpen = false; activeSlider = 0; true }
            state == State.PAUSED -> { state = State.PLAYING; true }   // «назад» из паузы — продолжить
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

    /** Обработка нажатия на экране целей: только кнопка «назад». */
    private fun trophyDown(x: Float, y: Float) {
        if (trophyBackBtn.contains(x, y)) trophyOpen = false
    }

    /**
     * Тап по иконке скина:
     *  - открытый скин — просто надеваем;
     *  - скин за рекорд (isRecordSkin) — купить нельзя, открывается сам по рекорду;
     *  - покупной скин — покупаем, если хватает монет, и сразу надеваем.
     */
    private fun tapSkin(index: Int) {
        if (index !in unlocked.indices) return
        if (unlocked[index]) { selectSkin(index); return }
        if (isRecordSkin(index)) return                  // такой скин открывается только рекордом
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
        // Полноэкранные экраны поверх игры (магазин / цели).
        if (shopOpen && state == State.READY) { drawShop(canvas); return }
        if (trophyOpen && state == State.READY) { drawTrophy(canvas); return }
        drawSky(canvas)
        drawClouds(canvas)
        drawCity(canvas, cityFarPaint, w * 0.26f, 0.30f, 0.22f, 0.25f)
        drawCity(canvas, cityNearPaint, w * 0.17f, 0.16f, 0.20f, 0.45f)
        if (state != State.LOADING) for (p in pipes) drawPipe(canvas, p)
        drawGround(canvas)
        if (state != State.LOADING) drawBird(canvas)
        drawParticles(canvas)                            // эффект-частицы
        if (state == State.LOADING) drawLoading(canvas) else { drawHud(canvas); drawCoinHud(canvas) }
        if (state == State.PLAYING) drawPauseButton(canvas)
        if (state == State.PAUSED) drawPauseOverlay(canvas)
        if (settingsOpen && state == State.READY) drawSettings(canvas)
        if (achToastTime > 0f) drawAchToast(canvas)      // плашка нового достижения
    }

    private fun drawSky(canvas: Canvas) {
        val n = skyBands.size
        val bandH = groundY / n
        for (i in 0 until n) {
            bandPaint.color = skyBands[i]
            canvas.drawRect(0f, i * bandH, w, (i + 1) * bandH + 1f, bandPaint)
        }
        if (isNight) drawStars(canvas)                   // ночью — звёздное поле
        // Небесное светило слева сверху: днём/на закате — солнце, ночью — луна.
        val s = w * 0.10f
        val sx = w * 0.10f; val sy = h * 0.10f
        canvas.drawRect(sx, sy, sx + s, sy + s, celestialPaint)
        val c = s * 0.22f
        if (isNight) {
            // Луна: «откусываем» уголок цветом неба, чтобы получился полумесяц.
            bandPaint.color = skyBands[0]
            canvas.drawRect(sx + s * 0.45f, sy - c, sx + s + c, sy + s * 0.55f, bandPaint)
        } else {
            // Лучи солнца по четырём сторонам.
            canvas.drawRect(sx - c, sy + c, sx, sy + s - c, celestialPaint)
            canvas.drawRect(sx + s, sy + c, sx + s + c, sy + s - c, celestialPaint)
            canvas.drawRect(sx + c, sy - c, sx + s - c, sy, celestialPaint)
            canvas.drawRect(sx + c, sy + s, sx + s - c, sy + s + c, celestialPaint)
        }
    }

    /** Звёзды ночной темы: мерцают по синусу со своей фазой. */
    private fun drawStars(canvas: Canvas) {
        val t = System.currentTimeMillis() / 400.0
        var i = 0
        while (i < starField.size) {
            val sx = starField[i] * w
            val sy = starField[i + 1] * groundY
            val tw = 0.6f + 0.4f * sin(t + i).toFloat()   // пульсация размера
            val r = w * 0.006f * tw
            canvas.drawRect(sx - r, sy - r, sx + r, sy + r, celestialPaint)
            i += 2
        }
    }

    /** Отрисовка эффект-частиц (квадратики), гаснут по мере жизни. */
    private fun drawParticles(canvas: Canvas) {
        for (p in particles) {
            fxPaint.color = p.color
            fxPaint.alpha = (255 * p.fade).toInt().coerceIn(0, 255)
            val s = p.size * (0.4f + 0.6f * p.fade)
            canvas.drawRect(p.x - s, p.y - s, p.x + s, p.y + s, fxPaint)
        }
        fxPaint.alpha = 255
    }

    // Общий Paint для частиц (без сглаживания — пиксельный стиль).
    private val fxPaint = Paint()

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
        // Собираемая монета в центре проёма (если есть и не подобрана), слегка покачивается.
        if (p.hasCoin && !p.coinCollected) {
            val bob = sin(System.currentTimeMillis() / 220.0 + p.seed).toFloat() * h * 0.008f
            drawCoin(canvas, p.coinX, p.coinY + bob, birdRadius * 0.6f)
        }
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
                drawTrophyButton(canvas)
            }
            State.PLAYING -> centeredText(canvas, "$score", w / 2f, h * 0.16f, w * 0.14f)
            State.PAUSED -> centeredText(canvas, "$score", w / 2f, h * 0.16f, w * 0.14f)
            State.GAME_OVER -> drawGameOver(canvas)
            else -> {}
        }
    }

    /**
     * Экран Game Over: заголовок + панель-таблица с медалью, счётом, рекордом и
     * монетами за забег. Значения и медаль обновляются в gameOver().
     */
    private fun drawGameOver(canvas: Canvas) {
        pixelTitle(canvas, "GAME", w / 2f, h * 0.16f, w * 0.13f)
        pixelTitle(canvas, "OVER", w / 2f, h * 0.16f + w * 0.13f * 1.2f, w * 0.13f)

        // Панель-таблица результатов: серый фон, три ровные строки «подпись — значение».
        val pw = w * 0.72f
        val ph = h * 0.30f
        val pl = (w - pw) / 2f
        val pt = h * 0.40f
        val rad = ph * 0.08f
        canvas.drawRoundRect(RectF(pl, pt, pl + pw, pt + ph), rad, rad, goPanelPaint)

        // Цвет текста — под текущую тему времени суток (день/закат/ночь).
        val txt = btnTextColor
        val labelX = pl + pw * 0.10f            // левая колонка (подписи, выравнивание по левому краю)
        val valueX = pl + pw * 0.72f            // правая колонка (значения отодвинуты правее)
        val labelSize = ph * 0.14f
        val y1 = pt + ph * 0.27f                // Счёт
        val y2 = pt + ph * 0.53f                // Рекорд
        val y3 = pt + ph * 0.79f                // Монеты за забег

        leftText(canvas, "Счёт", labelX, y1, labelSize, txt)
        leftText(canvas, "$score", valueX, y1, labelSize, txt)
        leftText(canvas, "Рекорд", labelX, y2, labelSize, txt)
        leftText(canvas, "$best", valueX, y2, labelSize, txt)
        leftText(canvas, "Монеты", labelX, y3, labelSize, txt)
        // Значение монет отодвинуто правее остальных: иконка на линии valueX, число — за ней.
        // Чтобы придвинуть/отодвинуть, меняйте множители при coinR ниже.
        val coinR = ph * 0.06f
        drawCoin(canvas, valueX + coinR, y3, coinR)
        leftText(canvas, "$coinsThisRun", valueX + coinR * 2.6f, y3, labelSize, txt)

        if (score >= best && score > 0) centeredText(canvas, "Новый рекорд!", w / 2f, pt - h * 0.03f, w * 0.05f)
        centeredText(canvas, "Тап — заново", w / 2f, pt + ph + h * 0.05f, w * 0.05f)
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

    /** Экран магазина: жёлтый фон, стрелка «назад», счётчик монет, сетка скинов. */
    private fun drawShop(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, shopScreenPaint)
        drawBackArrow(canvas)
        drawShopCoins(canvas)
        centeredText(canvas, "СКИНЫ", w / 2f, h * 0.22f, w * 0.08f, Color.rgb(70, 45, 0))

        // Оранжевые полоски сверху первого ряда и снизу последнего.
        val left = w * 0.06f
        val right = w - w * 0.06f
        val barH = h * 0.008f
        canvas.drawRect(left, skinSlots.first().top - h * 0.03f, right, skinSlots.first().top - h * 0.03f + barH, stripePaint)
        canvas.drawRect(left, skinSlots.last().bottom + h * 0.03f, right, skinSlots.last().bottom + h * 0.03f + barH, stripePaint)

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

    /**
     * Затемнение поверх закрытого скина + условие открытия по центру:
     *  - покупной скин — монета + цена;
     *  - скин за рекорд — «★ N» (нужный рекорд).
     */
    private fun drawLockedOverlay(canvas: Canvas, index: Int, slot: RectF, r: Float) {
        canvas.drawRoundRect(slot, r, r, lockDimPaint)
        val ps = slot.height() * 0.20f
        val cy = slot.centerY()
        if (isRecordSkin(index)) {
            // Требуется рекорд: звезда + число.
            val txt = "${skinRecordReq[index]}"
            uiTextPaint.textSize = ps
            val tw = uiTextPaint.measureText(txt)
            val star = ps * 0.6f
            val total = star + ps * 0.3f + tw
            val startX = slot.centerX() - total / 2f
            drawStar(canvas, startX + star / 2f, cy, star, Color.rgb(255, 214, 92))
            leftText(canvas, txt, startX + star + ps * 0.3f, cy, ps, textWhite)
        } else {
            // Покупной: монета + цена.
            val priceTxt = "${skinPrices[index]}"
            uiTextPaint.textSize = ps
            val tw = uiTextPaint.measureText(priceTxt)
            val cr = ps * 0.55f
            val gap = ps * 0.25f
            val total = cr * 2f + gap + tw
            val startX = slot.centerX() - total / 2f
            drawCoin(canvas, startX + cr, cy, cr)
            leftText(canvas, priceTxt, startX + cr * 2f + gap, cy, ps, textWhite)
        }
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

    // ---- Кнопка и экран целей (задания + достижения) ----

    /** Кнопка целей на главном меню: зелёный квадрат + белая звезда. */
    private fun drawTrophyButton(canvas: Canvas) {
        val b = trophyBtn
        val bgR = b.width() * 0.22f
        canvas.drawRoundRect(b, bgR, bgR, trophyBgPaint)
        drawStar(canvas, b.centerX(), b.centerY(), b.width() * 0.5f, Color.WHITE)
    }

    /**
     * Экран целей: сверху — ежедневные задания с прогресс-барами, снизу —
     * достижения (галочка/замок). Данные берутся из [progress].
     */
    private fun drawTrophy(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, trophyScreenPaint)
        // Стрелка «назад» (та же геометрия, что в магазине).
        run {
            val bb = trophyBackBtn
            val cx = bb.centerX(); val cy = bb.centerY()
            val s = bb.width() * 0.30f
            val head = Path().apply { moveTo(cx - s, cy); lineTo(cx, cy - s); lineTo(cx, cy + s); close() }
            canvas.drawPath(head, shopInk)
            canvas.drawRect(cx - s * 0.2f, cy - s * 0.28f, cx + s, cy + s * 0.28f, shopInk)
        }

        centeredText(canvas, "ЗАДАНИЯ ДНЯ", w / 2f, h * 0.13f, w * 0.06f)
        val quests = progress.quests
        var qy = h * 0.20f
        for (q in quests) {
            leftText(canvas, q.title, w * 0.08f, qy, w * 0.044f)
            // Прогресс p/target и награда (монета + число) справа в строке заголовка.
            leftText(canvas, "${q.progress.coerceAtMost(q.target)}/${q.target}", w * 0.58f, qy, w * 0.044f)
            val cr = w * 0.022f
            drawCoin(canvas, w * 0.80f, qy, cr)
            leftText(canvas, "+${q.reward}", w * 0.80f + cr * 1.4f, qy, w * 0.044f,
                if (q.done) Color.rgb(255, 214, 92) else textWhite)
            // Прогресс-бар.
            val bx = w * 0.08f; val bw = w * 0.84f; val by = qy + h * 0.02f; val bh = h * 0.012f
            canvas.drawRoundRect(RectF(bx, by, bx + bw, by + bh), bh / 2f, bh / 2f, barBgPaint)
            val frac = (q.progress.toFloat() / q.target).coerceIn(0f, 1f)
            if (frac > 0f) canvas.drawRoundRect(RectF(bx, by, bx + bw * frac, by + bh), bh / 2f, bh / 2f,
                if (q.done) trophyBgPaint else barFillPaint)
            qy += h * 0.09f
        }

        centeredText(canvas, "ДОСТИЖЕНИЯ", w / 2f, h * 0.50f, w * 0.06f)
        // Достижения — сетка 2 колонки.
        val achs = progress.achievements
        val col2 = 2
        val cellW = w * 0.44f
        val startX = w * 0.06f
        var ax = startX; var ay = h * 0.55f
        for ((i, a) in achs.withIndex()) {
            val cx = ax + h * 0.02f
            // Значок: звезда (открыто) или тёмный кружок (закрыто).
            if (a.unlocked) drawStar(canvas, cx, ay, w * 0.05f, Color.rgb(255, 214, 92))
            else canvas.drawCircle(cx, ay, w * 0.022f, lockDimPaint)
            leftText(canvas, a.title, cx + w * 0.05f, ay,
                w * 0.036f, if (a.unlocked) textWhite else Color.rgb(180, 190, 210))
            if (i % col2 == col2 - 1) { ax = startX; ay += h * 0.075f } else ax += cellW
        }
    }

    // ---- Звезда ----

    /** Пятиконечная звезда с центром (cx, cy) и «размахом» size. */
    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        val outer = size / 2f
        val inner = outer * 0.42f
        val path = Path()
        for (k in 0 until 10) {
            val rr = if (k % 2 == 0) outer else inner
            val ang = (-PI / 2 + k * PI / 5).toFloat()
            val px2 = cx + cos(ang) * rr
            val py2 = cy + sin(ang) * rr
            if (k == 0) path.moveTo(px2, py2) else path.lineTo(px2, py2)
        }
        path.close()
        starPaint.color = color
        canvas.drawPath(path, starPaint)
    }

    // ---- Пауза ----

    /** Кнопка паузы во время игры: две вертикальные полоски. */
    private fun drawPauseButton(canvas: Canvas) {
        val b = pauseBtn
        val bgR = b.width() * 0.2f
        canvas.drawRoundRect(b, bgR, bgR, gearBgPaint)
        val bw = b.width() * 0.14f
        val cy0 = b.top + b.height() * 0.28f
        val cy1 = b.bottom - b.height() * 0.28f
        val x1 = b.centerX() - b.width() * 0.16f
        val x2 = b.centerX() + b.width() * 0.16f
        canvas.drawRect(x1 - bw / 2f, cy0, x1 + bw / 2f, cy1, gearInk)
        canvas.drawRect(x2 - bw / 2f, cy0, x2 + bw / 2f, cy1, gearInk)
    }

    /** Оверлей паузы: затемнение + «ПАУЗА» + кнопки «Продолжить» и «В меню». */
    private fun drawPauseOverlay(canvas: Canvas) {
        canvas.drawRect(0f, 0f, w, h, dimPaint)
        pixelTitle(canvas, "ПАУЗА", w / 2f, h * 0.28f, w * 0.12f)
        drawTextButton(canvas, resumeBtn, "Продолжить")
        drawTextButton(canvas, pauseHomeBtn, "В меню")
    }

    /** Скруглённая кнопка с центрированной подписью (для оверлея паузы). */
    private fun drawTextButton(canvas: Canvas, b: RectF, text: String) {
        val r = b.height() * 0.28f
        canvas.drawRoundRect(b, r, r, btnPanelPaint)
        centeredText(canvas, text, b.centerX(), b.centerY(), b.height() * 0.42f, btnTextColor)
    }

    /** Всплывающая плашка нового достижения (гаснет по таймеру achToastTime). */
    private fun drawAchToast(canvas: Canvas) {
        val t = achToast ?: return
        val alpha = (achToastTime / 3.5f).coerceIn(0f, 1f)
        val bw = w * 0.7f; val bh = h * 0.07f
        val b = RectF((w - bw) / 2f, h * 0.06f, (w + bw) / 2f, h * 0.06f + bh)
        toastPaint.alpha = (220 * alpha).toInt()
        val r = bh * 0.3f
        canvas.drawRoundRect(b, r, r, toastPaint)
        drawStar(canvas, b.left + bh * 0.6f, b.centerY(), bh * 0.6f, Color.rgb(255, 214, 92))
        leftText(canvas, "Достижение: $t", b.left + bh, b.centerY(), bh * 0.34f, textWhite)
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
