package com.example.flappybirdgame

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Звук игры. Ассетов нет — всё синтезируется процедурно в WAV (в духе проекта,
 * где и графика рисуется кодом). Короткие эффекты играются через SoundPool
 * (низкая задержка, наложение), спокойная фоновая музыка — зациклённый MediaPlayer.
 *
 * Громкость музыки/эффектов и мьюты живут в GameView (и в prefs); сюда попадают
 * через публичные поля + apply-методы.
 */
class SoundEngine(private val context: Context) {

    private val sfxRate = 44100
    private val musicRate = 22050

    private var soundPool: SoundPool? = null
    private var flapId = 0
    private var scoreId = 0

    private var music: MediaPlayer? = null
    private var musicPrepared = false
    private var wantPlaying = false

    // Управляются извне (GameView), значения из prefs.
    @Volatile var musicVolume = 0.6f
    @Volatile var soundVolume = 0.8f
    @Volatile var musicMuted = false
    @Volatile var soundMuted = false

    /** Тяжёлую часть (генерация + запись WAV) вызывать в фоновом потоке. */
    fun init() {
        // Версия в имени: при смене синтеза старый кэш не переиспользуется.
        val cache = context.cacheDir
        val flapF = File(cache, "sfx_flap_v2.wav")
        val scoreF = File(cache, "sfx_score_v2.wav")
        val musicF = File(cache, "bg_music_v2.wav")
        try {
            if (!flapF.exists()) writeWav(flapF, genFlap(sfxRate), sfxRate)
            if (!scoreF.exists()) writeWav(scoreF, genScore(sfxRate), sfxRate)
            if (!musicF.exists()) writeWav(musicF, genMusic(musicRate), musicRate)
        } catch (_: Exception) {
            return
        }

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attrs).build().also { sp ->
            flapId = sp.load(flapF.absolutePath, 1)
            scoreId = sp.load(scoreF.absolutePath, 1)
        }

        try {
            music = MediaPlayer().apply {
                setDataSource(musicF.absolutePath)
                isLooping = true
                setOnPreparedListener {
                    musicPrepared = true
                    applyMusicVolume()
                    if (wantPlaying) try { start() } catch (_: Exception) {}
                }
                prepareAsync()
            }
        } catch (_: Exception) {
        }
    }

    fun playFlap() {
        if (soundMuted) return
        val v = (soundVolume * 0.5f).coerceIn(0f, 1f)   // взмах — тихий
        soundPool?.play(flapId, v, v, 1, 0, 1f)
    }

    fun playScore() {
        if (soundMuted) return
        val v = (soundVolume * 0.9f).coerceIn(0f, 1f)
        soundPool?.play(scoreId, v, v, 1, 0, 1f)
    }

    fun applyMusicVolume() {
        val v = if (musicMuted) 0f else musicVolume.coerceIn(0f, 1f)
        try { music?.setVolume(v, v) } catch (_: Exception) {}
    }

    fun onResume() {
        wantPlaying = true
        try { if (musicPrepared && music?.isPlaying != true) music?.start() } catch (_: Exception) {}
    }

    fun onPause() {
        wantPlaying = false
        try { if (music?.isPlaying == true) music?.pause() } catch (_: Exception) {}
    }

    fun release() {
        try { music?.release() } catch (_: Exception) {}
        music = null
        musicPrepared = false
        soundPool?.release()
        soundPool = null
    }

    // ---- Синтез ----

    /**
     * Обычная фоновая мелодия: прогрессия C – G – Am – F, в каждом такте бас-пульс,
     * тихий аккордовый пэд и ведущая мелодия из четвертных нот. Петля ~8 c.
     */
    private fun genMusic(sr: Int): ShortArray {
        // Triple(бас, аккорд-триада, мелодия из 4 нот).
        val bars = arrayOf(
            Triple(130.81f, floatArrayOf(261.63f, 329.63f, 392.00f), floatArrayOf(659.25f, 783.99f, 523.25f, 587.33f)), // C
            Triple(98.00f, floatArrayOf(293.66f, 392.00f, 493.88f), floatArrayOf(587.33f, 493.88f, 392.00f, 493.88f)),  // G
            Triple(110.00f, floatArrayOf(220.00f, 261.63f, 329.63f), floatArrayOf(523.25f, 659.25f, 440.00f, 493.88f)), // Am
            Triple(87.31f, floatArrayOf(174.61f, 220.00f, 261.63f), floatArrayOf(440.00f, 523.25f, 349.23f, 392.00f))   // F
        )
        val qN = (0.5f * sr).toInt()      // четверть = 0.5 c
        val barN = qN * 4
        val out = ShortArray(barN * bars.size)
        var off = 0
        for ((root, chord, mel) in bars) {
            for (i in 0 until barN) {
                val t = i.toFloat() / sr
                // Пэд: тихий, «дышит» на протяжении такта.
                val padEnv = sin(PI * i / barN).toFloat()
                var pad = 0f
                for (f in chord) pad += sin(2.0 * PI * f * t).toFloat()
                pad = pad / chord.size * padEnv * 0.12f
                // Бас: мягкий пульс на каждую четверть.
                val qi = i % qN
                val qt = qi.toFloat() / sr
                val bassEnv = exp(-5f * qt) * (qi / (0.01f * sr)).coerceAtMost(1f)
                val bass = sin(2.0 * PI * root * t).toFloat() * bassEnv * 0.16f
                // Мелодия: щипковая нота на каждую четверть (+ обертон для яркости).
                val mf = mel[(i / qN).coerceIn(0, 3)]
                val melEnv = exp(-6f * qt) * (qi / (0.008f * sr)).coerceAtMost(1f)
                val melody = (sin(2.0 * PI * mf * t).toFloat() +
                    0.3f * sin(2.0 * PI * 2 * mf * t).toFloat()) * melEnv * 0.22f
                val v = (pad + bass + melody) * 32767f
                out[off + i] = v.coerceIn(-32767f, 32767f).toInt().toShort()
            }
            off += barN
        }
        return out
    }

    /** Взмах крыла: воздушный «шшш» — шумовой всплеск с плавным подъёмом-спадом. */
    private fun genFlap(sr: Int): ShortArray {
        val dur = 0.2f
        val n = (dur * sr).toInt()
        val out = ShortArray(n)
        val rnd = java.util.Random(7)
        var lp = 0f
        var lp2 = 0f
        for (i in 0 until n) {
            val env = sin(PI * i / n).toFloat()           // мягкий бугор — как выдох воздуха
            val white = rnd.nextFloat() * 2f - 1f
            lp += (white - lp) * 0.35f                     // сглаживаем шум
            lp2 += (lp - lp2) * 0.05f
            val band = lp - lp2                            // полосовой → «воздушный» тембр
            out[i] = (band * env * 0.9f * 32767f).coerceIn(-32767f, 32767f).toInt().toShort()
        }
        return out
    }

    /** Набор очков: приятный двухнотный «дзинь» (G5 → C6). */
    private fun genScore(sr: Int): ShortArray {
        val dur = 0.26f
        val n = (dur * sr).toInt()
        val out = ShortArray(n)
        val f1 = 784.0f     // G5
        val f2 = 1046.5f    // C6
        val split = (n * 0.42f).toInt()
        for (i in 0 until n) {
            val t = i.toFloat() / sr
            val f = if (i < split) f1 else f2
            val dec = if (i < split) exp(-6f * (i.toFloat() / split))
            else exp(-5f * ((i - split).toFloat() / (n - split)))
            val s = sin(2.0 * PI * f * t).toFloat()
            out[i] = (s * dec * 0.32f * 32767f).toInt().toShort()
        }
        return out
    }

    private fun writeWav(file: File, samples: ShortArray, sampleRate: Int) {
        val dataSize = samples.size * 2
        BufferedOutputStream(FileOutputStream(file)).use { bos ->
            fun wInt(v: Int) {
                bos.write(v and 0xff); bos.write((v shr 8) and 0xff)
                bos.write((v shr 16) and 0xff); bos.write((v shr 24) and 0xff)
            }
            fun wShort(v: Int) { bos.write(v and 0xff); bos.write((v shr 8) and 0xff) }
            bos.write("RIFF".toByteArray()); wInt(36 + dataSize); bos.write("WAVE".toByteArray())
            bos.write("fmt ".toByteArray()); wInt(16); wShort(1); wShort(1)
            wInt(sampleRate); wInt(sampleRate * 2); wShort(2); wShort(16)
            bos.write("data".toByteArray()); wInt(dataSize)
            val buf = ByteArray(dataSize)
            for (i in samples.indices) {
                val s = samples[i].toInt()
                buf[i * 2] = (s and 0xff).toByte()
                buf[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
            }
            bos.write(buf)
        }
    }
}
