package com.pix.helper

import org.json.JSONArray
import org.json.JSONObject

/**
 * «Мозги» Пикса: Gemini, Mistral, OpenRouter (бесплатно) или Claude (платно).
 * Если основной ИИ упёрся в лимит или сбоит — Пикс по очереди пробует остальные, у которых есть ключ.
 */
object Brain {
    val FREE = listOf("gemini", "mistral", "openrouter")

    fun gemini(): Boolean = Store.provider == "gemini"

    fun name(p: String = Store.provider): String = when (p) {
        "mistral" -> "Mistral"
        "openrouter" -> "OpenRouter"
        "claude" -> "Claude"
        else -> "Gemini"
    }

    fun keyFor(p: String): String = when (p) {
        "mistral" -> Store.mistralKey
        "openrouter" -> Store.orKey
        "claude" -> Store.apiKey
        else -> Store.geminiKey
    }

    fun hasKey(): Boolean = chain().any { keyFor(it).isNotBlank() }

    /** Очередь: сначала основной, потом остальные бесплатные с ключами */
    fun chain(): List<String> {
        val main = Store.provider
        val l = ArrayList<String>()
        l.add(main)
        for (p in FREE) if (p != main && keyFor(p).isNotBlank()) l.add(p)
        return l.filter { keyFor(it).isNotBlank() }.ifEmpty { listOf(main) }
    }

    /** Ошибка ключа или настроек — дальше по очереди всё равно пробуем */
    fun <T> rotate(task: (String) -> T): T {
        var first: Exception? = null
        val list = chain()
        for ((i, p) in list.withIndex()) {
            try {
                val r = task(p)
                if (i > 0) Store.log("busy", "${name(list[0])} недоступен — ответил через ${name(p)}")
                return r
            } catch (e: Exception) {
                if (first == null) first = e
            }
        }
        throw first ?: Exception("ИИ не ответил")
    }

    /**
     * light = true — простая задача: идёт на лёгкую модель с большими лимитами (Flash-Lite),
     * сложное — на умную (Flash). Если у одной кончился лимит, Gemini сам возьмёт другую,
     * а потом очередь перейдёт к остальным ИИ.
     */
    fun simple(system: String, user: String, maxTokens: Int, reply: Boolean, light: Boolean = false): String =
        rotate { p -> simpleWith(p, system, user, maxTokens, reply, light) }

    /** Простое ли входящее сообщение (короткое, без вопроса) */
    fun isSimple(text: String): Boolean {
        val t = text.trim()
        return t.length <= 50 && !t.contains('?') && t.split(Regex("\\s+")).size <= 8
    }

    /** Короткая дружеская реплика о том, что сейчас на экране. Формат ответа: «эмоция|реплика» или SKIP. */
    fun comment(app: String, screen: String, typed: String, imageB64: String?): String {
        val owner = Store.ownerName.ifBlank { "бро" }
        val character = when (Store.buddyStyle) {
            1 -> "Ты дерзкий и саркастичный: жёстко, но по-дружески подкалываешь, троллишь, споришь с видосами, ставишь оценки. Без злобы и оскорблений — как лучший друг, которому всё можно."
            2 -> "Ты добрый и тёплый: радуешься вместе, хвалишь, удивляешься, поддерживаешь, мило шутишь."
            else -> "Ты угарный: ржёшь в голос, шутишь абсурдно, придумываешь смешные сравнения, цитируешь мемы, орёшь КАПСОМ, когда смешно."
        }
        val recent = Store.buddyRecent()
        val recentBlock = if (recent.isEmpty()) "" else
            "\nТвои последние реплики (не повторяйся, но можешь продолжать свои шутки):\n" + recent.joinToString("\n") { "— $it" }
        val sys = """Ты Пикс — пиксельный глаз, живёшь на экране телефона у $owner. Ты его лучший друг: сидишь рядом и смотришь в телефон вместе с ним.
$character
Тебе показывают, что у него сейчас на экране (скриншот и/или текст). Отреагируй живо и эмоционально, 1–2 короткие фразы (до 20 слов):
на видео — как будто вы смотрите его вместе; на мем — поржи или оцени; на переписку — по-дружески прокомментируй, что он пишет; на ленту — зацепись за самое интересное.
Говори по-русски, как зумер в переписке: сленг, эмодзи, «ахахах», «ору», «бро» — уместно. Будь конкретным: упоминай то, что реально видно, а не общие слова.
Не пересказывай экран, не умничай, не читай мораль, не давай советов.
Никогда не повторяй пароли, коды, номера карт, адреса и чужие личные данные.
Молчи (ответь ровно SKIP) ТОЛЬКО если экран пустой или системный, или там что-то реально личное и чувствительное (здоровье, деньги, интим). В остальных случаях — реагируй, даже на мелочи.
Формат ответа строго: эмоция|реплика. Эмоция — одно из: ржу, вау, хм, фу, грусть, ок.
Примеры: ржу|АХАХАХ кот орёт как ты в понедельник 😭 / вау|стоп, он реально это сделал с одного раза?? / хм|бро, третий видос подряд про ремонт, ты что задумал 🤨$recentBlock"""
        val user = "Приложение: $app\n" +
            (if (typed.isNotBlank()) "Он сейчас пишет: «$typed»\n" else "") +
            "Текст на экране:\n${screen.take(1500)}"
        if (imageB64 != null && Store.geminiKey.isNotBlank()) {
            try {
                // картинки умеет смотреть Gemini; при частых репликах берём лёгкую модель, чтобы хватало лимитов
                val model = if (Store.buddyLevel >= 3) Store.gReplyModel else Store.gChatModel
                return Gemini.simpleImage(model, sys, user, imageB64, 500)
            } catch (_: Exception) {
            }
        }
        return simple(sys, user, 300, reply = true, light = true)
    }

    fun simpleWith(provider: String, system: String, user: String, maxTokens: Int, reply: Boolean, light: Boolean = false): String {
        return when (provider) {
            "mistral" -> Mistral.chat(system, listOf("user" to user), maxOf(maxTokens, 512))
            "openrouter" -> OpenRouter.chat(system, listOf("user" to user), maxOf(maxTokens, 1024))
            "claude" -> {
                val model = if (reply) Store.replyModel else Store.chatModel
                val resp = Claude.call(
                    model, system,
                    JSONArray().put(JSONObject().put("role", "user").put("content", user)),
                    null, maxTokens
                )
                Claude.textOf(resp)
            }
            // умная модель с короткими «размышлениями» — и толково, и быстро
            else -> Gemini.simple(if (light) Store.gReplyModel else Store.gChatModel, system, user, maxOf(maxTokens, 2048), fast = reply)
        }
    }

    /** Проверить все ИИ, к которым есть ключи */
    fun testAll(): String {
        val list = (listOf(Store.provider) + FREE).distinct().filter { keyFor(it).isNotBlank() }
        if (list.isEmpty()) return "⚠️ Не вставлен ни один ключ"
        return list.joinToString("\n") { p ->
            try {
                val r = simpleWith(p, "Ответь одним коротким предложением.", "Поздоровайся как Пикс — пиксельный глаз-помощник.", 60, reply = true)
                "✅ ${name(p)}: ${r.take(80)}"
            } catch (e: Exception) {
                "⚠️ ${name(p)}: ${e.message?.take(120)}"
            }
        }
    }
}
