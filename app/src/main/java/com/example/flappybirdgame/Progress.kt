package com.example.flappybirdgame

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Calendar
import kotlin.random.Random

/**
 * Прогресс игрока: ежедневные задания и достижения (технический агент).
 *
 * Хранит всё в SharedPreferences, поэтому переживает перезапуск. Логика
 * отделена от отрисовки: GameView сообщает о событиях (пройдена труба, собрана
 * монета, завершена партия), а Progress начисляет прогресс и возвращает
 * заработанные за это монеты-награды — их GameView добавляет игроку.
 *
 * Как редактировать:
 *  - список ежедневных заданий и их награды — в [buildQuests];
 *  - список достижений и пороги — в [achievements].
 */
class Progress(private val prefs: SharedPreferences) {

    // ---------- Ежедневные задания ----------

    /**
     * Одно задание на день. [kind] задаёт, что считать:
     *   0 — пройти N труб суммарно за день,
     *   1 — собрать N монет суммарно за день,
     *   2 — набрать счёт N за один забег (лучший результат дня).
     */
    class Quest(
        val title: String,
        val kind: Int,
        val target: Int,
        val reward: Int   // сколько монет даём за выполнение
    ) {
        var progress: Int = 0
        var claimed: Boolean = false        // награда уже выдана (чтобы не начислять дважды)
        val done: Boolean get() = progress >= target
    }

    /** Набор заданий на сегодня (детерминированно генерируется по дате). */
    var quests: List<Quest> = emptyList()
        private set

    /**
     * Пул шаблонов заданий. Каждый день из него детерминированно (по дате)
     * выбираются три штуки — так у всех игроков в один день одинаковые цели,
     * но список меняется день ото дня.
     */
    private fun questPool(): List<Quest> = listOf(
        Quest("Пройди 15 труб", 0, 15, 8),
        Quest("Пройди 30 труб", 0, 30, 15),
        Quest("Собери 8 монет", 1, 8, 6),
        Quest("Собери 20 монет", 1, 20, 12),
        Quest("Набери 10 очков", 2, 10, 8),
        Quest("Набери 20 очков", 2, 20, 16)
    )

    private fun buildQuests(daySeed: Int): List<Quest> {
        val pool = questPool()
        val rnd = Random(daySeed)
        return pool.shuffled(rnd).take(3)
    }

    // ---------- Достижения ----------

    /**
     * Достижение с порогом. [kind]:
     *   0 — лучший счёт >= target,
     *   1 — всего собрано монет >= target,
     *   2 — сыграно партий >= target,
     *   3 — открыто скинов >= target.
     */
    class Achievement(val id: String, val title: String, val kind: Int, val target: Int) {
        var unlocked: Boolean = false
    }

    val achievements: List<Achievement> = listOf(
        Achievement("first", "Первый полёт", 2, 1),
        Achievement("score10", "Счёт 10", 0, 10),
        Achievement("score25", "Счёт 25", 0, 25),
        Achievement("score50", "Счёт 50", 0, 50),
        Achievement("score100", "Счёт 100", 0, 100),
        Achievement("coins50", "50 монет всего", 1, 50),
        Achievement("coins200", "200 монет всего", 1, 200),
        Achievement("games25", "25 партий", 2, 25),
        Achievement("skins3", "3 скина", 3, 3),
        Achievement("skinsAll", "Все скины", 3, 6)
    )

    // ---------- Накопительная статистика (для достижений) ----------
    private var totalCoins = 0     // всего собрано монет за всё время
    private var games = 0          // всего сыграно партий

    init {
        totalCoins = prefs.getInt("stat_total_coins", 0)
        games = prefs.getInt("stat_games", 0)
        loadDay()
        loadAchievements()
    }

    /** Целочисленный ключ «сегодня»: год*1000 + день года. */
    private fun todayKey(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    /** Если наступил новый день — генерируем новые задания и обнуляем прогресс. */
    private fun loadDay() {
        val today = todayKey()
        val saved = prefs.getInt("quest_day", -1)
        quests = buildQuests(today)
        if (saved == today) {
            for (i in quests.indices) {
                quests[i].progress = prefs.getInt("quest_${i}_prog", 0)
                quests[i].claimed = prefs.getBoolean("quest_${i}_claim", false)
            }
        } else {
            // Новый день: сбрасываем прогресс и фиксируем дату.
            prefs.edit {
                putInt("quest_day", today)
                for (i in quests.indices) {
                    putInt("quest_${i}_prog", 0)
                    putBoolean("quest_${i}_claim", false)
                }
            }
        }
    }

    private fun saveQuests() {
        prefs.edit {
            for (i in quests.indices) {
                putInt("quest_${i}_prog", quests[i].progress)
                putBoolean("quest_${i}_claim", quests[i].claimed)
            }
        }
    }

    private fun loadAchievements() {
        for (a in achievements) a.unlocked = prefs.getBoolean("ach_${a.id}", false)
    }

    // ---------- События из игры ----------

    /**
     * Начисляет прогресс заданий указанного [kind] на [amount] и возвращает
     * суммарную награду в монетах за только что выполненные задания.
     * Задания вида «за один забег» (kind 2) обрабатываются через [reportScore].
     */
    private fun advance(kind: Int, amount: Int, absolute: Boolean = false): Int {
        var reward = 0
        for (q in quests) {
            if (q.kind != kind || q.claimed) continue
            q.progress = if (absolute) maxOf(q.progress, amount) else q.progress + amount
            if (q.done) { q.claimed = true; reward += q.reward }
        }
        if (reward > 0 || amount > 0) saveQuests()
        return reward
    }

    /** Пройдена одна труба (+1 к заданиям на трубы). Возвращает награду-монеты. */
    fun onPipe(): Int = advance(0, 1)

    /** Собрана монета (+1 к заданиям на монеты + к статистике). Возвращает награду. */
    fun onCoin(): Int {
        totalCoins++
        prefs.edit { putInt("stat_total_coins", totalCoins) }
        return advance(1, 1)
    }

    /** Достигнут счёт [score] в текущем забеге (для заданий «набери N»). Награда. */
    fun reportScore(score: Int): Int = advance(2, score, absolute = true)

    /** Завершена партия: увеличиваем счётчик сыгранных. */
    fun onGameFinished() {
        games++
        prefs.edit { putInt("stat_games", games) }
    }

    /**
     * Пересчитывает достижения по актуальным показателям и возвращает список
     * названий тех, что открылись только что (для всплывающего уведомления).
     */
    fun refreshAchievements(best: Int, skinsOwned: Int): List<String> {
        val newly = ArrayList<String>()
        for (a in achievements) {
            if (a.unlocked) continue
            val value = when (a.kind) {
                0 -> best
                1 -> totalCoins
                2 -> games
                3 -> skinsOwned
                else -> 0
            }
            if (value >= a.target) {
                a.unlocked = true
                prefs.edit { putBoolean("ach_${a.id}", true) }
                newly.add(a.title)
            }
        }
        return newly
    }
}
