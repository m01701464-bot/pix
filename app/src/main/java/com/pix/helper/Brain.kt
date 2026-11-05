package com.pix.helper

import org.json.JSONArray
import org.json.JSONObject

/** Выбор «мозгов»: Gemini (бесплатно) или Claude (платно). */
object Brain {
    fun gemini(): Boolean = Store.provider != "claude"

    fun name(): String = if (gemini()) "Gemini" else "Claude"

    fun simple(system: String, user: String, maxTokens: Int, reply: Boolean): String {
        return if (gemini()) {
            val model = if (reply) Store.gReplyModel else Store.gChatModel
            // у Gemini в лимит входят и «размышления», поэтому запас побольше
            Gemini.simple(model, system, user, maxOf(maxTokens, 2048))
        } else {
            val model = if (reply) Store.replyModel else Store.chatModel
            val resp = Claude.call(
                model, system,
                JSONArray().put(JSONObject().put("role", "user").put("content", user)),
                null, maxTokens
            )
            Claude.textOf(resp)
        }
    }
}
