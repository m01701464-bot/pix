package com.pix.helper

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Простое событие: type = thinking/done/inbox/replied/remind/urgent/error/busy/log */
class Event(val type: String, val text: String = "")

object Bus {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Event) -> Unit>()

    fun on(l: (Event) -> Unit) {
        listeners.add(l)
    }

    fun off(l: (Event) -> Unit) {
        listeners.remove(l)
    }

    fun emit(e: Event) {
        main.post { for (l in listeners) l(e) }
    }
}
