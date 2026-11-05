package com.pix.helper

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.media.AudioManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min

/** Держит Пикса поверх всех окон и показывает панель-чат по нажатию. */
class OverlayService : Service() {

    class Msg(val who: Int, var text: String) // 0 — ты, 1 — Пикс, 2 — событие

    companion object {
        const val ACTION_STOP = "com.pix.helper.STOP"
        const val ACTION_OPEN_PANEL = "com.pix.helper.OPEN_PANEL"
        const val ACTION_OFF = "com.pix.helper.OFF"
        @Volatile
        var running = false
        val feed = ArrayList<Msg>()

        fun start(ctx: Context, openPanel: Boolean = false) {
            val i = Intent(ctx, OverlayService::class.java)
            if (openPanel) i.action = ACTION_OPEN_PANEL
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }

        private val C_BG = 0xF2121A21.toInt()
        private val C_CARD = 0xFF1E2A33.toInt()
        private val C_ACCENT = 0xFF4FD1B5.toInt()
        private val C_TEXT = 0xFFE6EDF3.toInt()
        private val C_SUB = 0xFF8B98A5.toInt()
        private val SHAKE_LINES = arrayOf("Эй, не тряси! 😵", "Голова кружится…", "Ой-ой-ой! 🌀", "Я же глаз, а не погремушка!")
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var sprite: PixelSprite? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var panelRoot: LinearLayout? = null
    private var feedBox: LinearLayout? = null
    private var feedScroll: ScrollView? = null
    private var busyChip: TextView? = null
    private var sayView: TextView? = null

    private var thinking = 0
    private val rnd = java.util.Random()
    private var morningDay = ""

    /** Сценки, которые Пикс сам устраивает от скуки, и что он при этом может сказать */
    private val idleActs = listOf(
        PixelSprite.Act.POKE, PixelSprite.Act.PHONE, PixelSprite.Act.READ, PixelSprite.Act.PEEK,
        PixelSprite.Act.BALL, PixelSprite.Act.COOL, PixelSprite.Act.SNACK, PixelSprite.Act.SELFIE,
        PixelSprite.Act.SNEEZE, PixelSprite.Act.PHONE, PixelSprite.Act.POKE
    )
    private val actLines = mapOf(
        PixelSprite.Act.PHONE to listOf("да ты гонишь вообще 😤", "сам такой!", "ну всё, я обиделся", "кто так пишет блин", "щас я ему отвечу…", "не, ну это бан", "ДА КАК ТАК-ТО"),
        PixelSprite.Act.COOL to listOf("😎", "я слишком крут для этого экрана", "не завидуй"),
        PixelSprite.Act.SNACK to listOf("ням 🍪", "это моя печенька, не трогай"),
        PixelSprite.Act.SELFIE to listOf("чиииз 📸", "я сегодня хорош"),
        PixelSprite.Act.SNEEZE to listOf("апчхи! 🤧", "будь здоров, Пикс"),
        PixelSprite.Act.READ to listOf("тихо, я читаю 📖", "интересная книжка, кстати"),
        PixelSprite.Act.POKE to listOf("а что это за полоска такая?", "тык-тык"),
        PixelSprite.Act.BALL to listOf("смотри как умею ⚽", "раз-два-три…"),
        PixelSprite.Act.PEEK to listOf("ку-ку 👀", "я тут, если что")
    )

    private val idleLoop = object : Runnable {
        override fun run() {
            try {
                maybeIdleAct()
            } catch (_: Exception) {
            }
            handler.postDelayed(this, 6_000)
        }
    }

    /** Иногда Пикс сам чем-нибудь занимается; если играет музыка — слушает её в наушниках */
    private fun maybeIdleAct() {
        val s = sprite ?: return
        if (panelRoot != null || s.act != PixelSprite.Act.NONE || thinking > 0 || tempMood != null) return
        if (s.mood != PixelSprite.Mood.IDLE && s.mood != PixelSprite.Mood.DUTY) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (am.isMusicActive) {
            s.play(PixelSprite.Act.MUSIC)
            return
        }
        if (rnd.nextInt(100) < 13) {
            val a = idleActs[rnd.nextInt(idleActs.size)]
            s.play(a)
            if (rnd.nextInt(100) < 40) actLines[a]?.let { say(it[rnd.nextInt(it.size)], 4000) }
        }
    }

    /** Реакции на разблокировку, зарядку и садящуюся батарею */
    private val sysReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val s = sprite ?: return
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> onUnlock(s)
                Intent.ACTION_POWER_CONNECTED -> {
                    s.play(PixelSprite.Act.CHARGE)
                    say(listOf("мм, вкусненько ⚡", "о, зарядочка 😋", "заряжаюсь, не мешай")[rnd.nextInt(3)], 3500)
                }
                Intent.ACTION_BATTERY_LOW -> {
                    s.play(PixelSprite.Act.TIRED)
                    say("бро, я помираю… покорми меня 🔋", 5000)
                }
            }
        }
    }

    private fun onUnlock(s: PixelSprite) {
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val day = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
        when {
            h in 0..4 -> {
                s.play(PixelSprite.Act.YAWN)
                say(listOf("бро, ночь же… спать иди 😴", "ты чего не спишь? 🥱", "опять до утра в телефоне…")[rnd.nextInt(3)], 5000)
            }
            h in 6..11 && morningDay != day -> {
                morningDay = day
                handler.postDelayed({
                    sprite?.play(PixelSprite.Act.COFFEE)
                    say(listOf("доброе утро ☕", "утречко! кофе будешь?", "ммм, кофеек ☕")[rnd.nextInt(3)], 4000)
                }, 1500)
            }
            else -> if (s.act == PixelSprite.Act.NONE && rnd.nextInt(100) < 60) s.play(PixelSprite.Act.WAVE)
        }
    }
    private var awakeUntil = 0L
    private var lastShake = 0L
    private var sensors: SensorManager? = null
    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            if (!Store.shakeReact) return
            val x = e.values[0]
            val y = e.values[1]
            val z = e.values[2]
            val g = sqrt(x * x + y * y + z * z)
            val now = System.currentTimeMillis()
            if (abs(g - SensorManager.GRAVITY_EARTH) > 11f && now - lastShake > 4000) {
                lastShake = now
                awakeUntil = now + 5 * 60_000L
                flash(PixelSprite.Mood.DIZZY, 3500)
                say(SHAKE_LINES[(now % SHAKE_LINES.size).toInt()])
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    private var tempMood: PixelSprite.Mood? = null
    private val clearTemp = Runnable {
        tempMood = null
        applyMood()
    }
    private val hideSayR = Runnable { hideSay() }
    private val busyCheck = object : Runnable {
        override fun run() {
            Store.isBusy() // сам выключит режим по таймеру
            applyMood()
            handler.postDelayed(this, 30_000)
        }
    }
    private val listener: (Event) -> Unit = { onEvent(it) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        goForeground()
        running = true
        try {
            addBubble()
        } catch (e: Exception) {
            Store.log("error", "Нет разрешения «поверх окон»")
            stopSelf()
            return
        }
        Bus.on(listener)
        handler.post(busyCheck)
        handler.postDelayed(idleLoop, 15_000)
        try {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_BATTERY_LOW)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(sysReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(sysReceiver, f)
        } catch (_: Exception) {
        }
        if (!MessageListener.connected) MessageListener.rebind(this)
        try {
            val sm = getSystemService(SENSOR_SERVICE) as SensorManager
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sm.registerListener(shakeListener, it, SensorManager.SENSOR_DELAY_NORMAL)
                sensors = sm
            }
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Store.overlayEnabled = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_OFF -> {
                Store.turnOff()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_OPEN_PANEL -> handler.post { openPanel() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        sensors?.unregisterListener(shakeListener)
        try {
            unregisterReceiver(sysReceiver)
        } catch (_: Exception) {
        }
        Bus.off(listener)
        handler.removeCallbacksAndMessages(null)
        hideSay()
        closePanel()
        sprite?.let { safeRemove(it) }
        sprite = null
        super.onDestroy()
    }

    // ---------------- Уведомление сервиса ----------------

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, App.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (Store.isBusy()) "Пикс дежурит за тебя" else "Пикс на экране")
            .setContentText(Store.busyText())
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as Icon?, "Убрать с экрана", stop).build())
            .addAction(
                Notification.Action.Builder(
                    null as Icon?, "⏻ Выключить Пикса",
                    PendingIntent.getService(
                        this, 4, Intent(this, OverlayService::class.java).setAction(ACTION_OFF),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                ).build()
            )
            .build()
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(1, buildNotification())
    }

    // ---------------- Пикс поверх окон ----------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun screenW() = resources.displayMetrics.widthPixels
    private fun screenH() = resources.displayMetrics.heightPixels

    private fun overlayParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
    )

    private fun safeRemove(v: View) {
        try {
            wm.removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun addBubble() {
        val s = PixelSprite(this)
        s.px = dp(4).toFloat()
        val lp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        lp.gravity = Gravity.TOP or Gravity.START
        val size = (16 * s.px).toInt()
        lp.x = if (Store.overlayX >= 0) min(Store.overlayX, screenW() - size) else screenW() - size
        lp.y = if (Store.overlayY >= 0) min(Store.overlayY, screenH() - size) else dp(220)
        s.setOnTouchListener(BubbleTouch())
        wm.addView(s, lp)
        sprite = s
        bubbleParams = lp
        s.post { updateHomeLook() }
        applyMood()
    }

    private inner class BubbleTouch : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var longPressed = false
        private val longPress = Runnable {
            if (!moved) {
                longPressed = true
                sprite?.squish = false
                openApp()
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = bubbleParams ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    lastX = e.rawX
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    longPressed = false
                    sprite?.squish = true
                    handler.postDelayed(longPress, 650)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > dp(8)) {
                        moved = true
                        handler.removeCallbacks(longPress)
                        sprite?.squish = false
                        hideSay()
                    }
                    if (moved) {
                        val step = e.rawX - lastX
                        if (abs(step) > dp(2)) sprite?.lookOverride = if (step > 0) 2 else -2
                        lastX = e.rawX
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try {
                            wm.updateViewLayout(v, lp)
                        } catch (_: Exception) {
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    sprite?.squish = false
                    sprite?.lookOverride = null
                    if (moved) snapToEdge(v, lp)
                    else if (!longPressed && e.actionMasked == MotionEvent.ACTION_UP) togglePanel()
                }
            }
            return true
        }
    }

    private fun snapToEdge(v: View, lp: WindowManager.LayoutParams) {
        val sw = screenW()
        val target = if (lp.x + v.width / 2 < sw / 2) 0 else sw - v.width
        lp.y = lp.y.coerceIn(0, max(0, screenH() - v.height - dp(40)))
        val anim = ValueAnimator.ofInt(lp.x, target).setDuration(220)
        anim.addUpdateListener { a ->
            lp.x = a.animatedValue as Int
            try {
                wm.updateViewLayout(v, lp)
            } catch (_: Exception) {
            }
        }
        anim.start()
        sprite?.homeLook = if (target == 0) 1 else -1
        Store.overlayX = target
        Store.overlayY = lp.y
    }

    private fun openApp() {
        closePanel()
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
        } catch (_: Exception) {
        }
    }

    // ---------------- Настроение ----------------

    private fun applyMood() {
        val s = sprite ?: return
        s.mood = when {
            thinking > 0 -> PixelSprite.Mood.THINKING
            tempMood != null -> tempMood!!
            Store.isBusy() -> PixelSprite.Mood.DUTY
            sleepyTime() -> PixelSprite.Mood.SLEEP
            else -> PixelSprite.Mood.IDLE
        }
        s.badge = Store.unseenCount()
    }

    /** Ночью (23:00–7:00) Пикс дремлет, если ничего не происходит. */
    private fun sleepyTime(): Boolean {
        if (!Store.sleepAtNight || System.currentTimeMillis() < awakeUntil) return false
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= 23 || h < 7
    }

    private fun updateHomeLook() {
        val s = sprite ?: return
        val bp = bubbleParams ?: return
        val w = if (s.width > 0) s.width else dp(95)
        s.homeLook = if (bp.x + w / 2 < screenW() / 2) 1 else -1
    }

    private fun flash(m: PixelSprite.Mood, ms: Long) {
        tempMood = m
        applyMood()
        handler.removeCallbacks(clearTemp)
        handler.postDelayed(clearTemp, ms)
    }

    private fun onEvent(e: Event) {
        when (e.type) {
            "thinking" -> {
                thinking++
                applyMood()
            }
            "done" -> {
                thinking = max(0, thinking - 1)
                applyMood()
            }
            "replied" -> {
                flash(PixelSprite.Mood.HAPPY, 3000)
                addFeed(2, "Ответил за тебя → ${e.text}")
                say("💬 ${e.text}")
            }
            "inbox" -> {
                applyMood()
                val sp = sprite
                if (e.text.isNotEmpty() && sp != null && sp.act == PixelSprite.Act.NONE && sp.mood != PixelSprite.Mood.SLEEP) {
                    sp.play(PixelSprite.Act.JUMP)
                }
            }
            "urgent" -> {
                flash(PixelSprite.Mood.ALERT, 6000)
                addFeed(2, "❗ Срочное: ${e.text}")
                say("❗ Срочно: ${e.text}")
            }
            "buddy" -> {
                val i = e.text.indexOf('|')
                val emo = if (i > 0) e.text.substring(0, i) else "ок"
                val line = if (i > 0) e.text.substring(i + 1) else e.text
                when (emo) {
                    "ржу" -> flash(PixelSprite.Mood.HAPPY, 4000)
                    "вау" -> flash(PixelSprite.Mood.DIZZY, 2500)
                    "фу" -> flash(PixelSprite.Mood.ALERT, 2500)
                    "грусть" -> flash(PixelSprite.Mood.SLEEP, 3000)
                    "хм" -> flash(PixelSprite.Mood.THINKING, 2500)
                }
                awakeUntil = System.currentTimeMillis() + 10 * 60_000L
                say(line, 8000)
            }
            "summary" -> {
                addFeed(1, "📋 Сводка дня:\n${e.text}")
                flash(PixelSprite.Mood.HAPPY, 2500)
                say("📋 Сводка дня готова — нажми на меня")
            }
            "remind" -> {
                flash(PixelSprite.Mood.ALERT, 6000)
                say("⏰ ${e.text}")
            }
            "error" -> {
                flash(PixelSprite.Mood.ALERT, 3000)
                say("⚠️ ${e.text}")
            }
            "off" -> stopSelf()
            "busy" -> {
                applyMood()
                updateNotification()
                updateBusyChip()
                if (!Store.isBusy()) {
                    val n = Store.unseenCount()
                    if (n > 0) say("Пока ты был занят — $n сообщ. Нажми на меня → «Сводка»")
                }
            }
        }
    }

    // ---------------- Облачко с репликой ----------------

    private fun say(text: String, ms: Long = 6000) {
        if (panelRoot != null) return
        val bp = bubbleParams ?: return
        val s = sprite ?: return
        hideSay()
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(C_TEXT)
            textSize = 13f
            maxWidth = dp(230)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            background = rounded(C_BG, dp(12), C_ACCENT)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setOnClickListener {
                hideSay()
                openPanel()
            }
        }
        val lp = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        val spriteW = if (s.width > 0) s.width else dp(80)
        if (bp.x + spriteW / 2 < screenW() / 2) {
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = bp.x + spriteW
        } else {
            lp.gravity = Gravity.TOP or Gravity.END
            lp.x = max(0, screenW() - bp.x)
        }
        lp.y = bp.y + dp(10)
        try {
            wm.addView(tv, lp)
            sayView = tv
            handler.postDelayed(hideSayR, ms)
        } catch (_: Exception) {
        }
    }

    private fun hideSay() {
        handler.removeCallbacks(hideSayR)
        sayView?.let { safeRemove(it) }
        sayView = null
    }

    // ---------------- Панель-чат ----------------

    private fun rounded(color: Int, radius: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun chip(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(C_TEXT)
        textSize = 13f
        background = rounded(C_CARD, dp(14))
        setPadding(dp(12), dp(7), dp(12), dp(7))
        setOnClickListener { onClick() }
    }

    private fun togglePanel() {
        if (sprite?.mood == PixelSprite.Mood.SLEEP) {
            // разбудили
            awakeUntil = System.currentTimeMillis() + 10 * 60_000L
            flash(PixelSprite.Mood.HAPPY, 1500)
        }
        if (panelRoot != null) closePanel() else openPanel()
    }

    private fun openPanel() {
        if (panelRoot != null || sprite == null) return
        hideSay()
        val ctx = this
        val root = object : LinearLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) closePanel()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        root.orientation = LinearLayout.VERTICAL
        root.background = rounded(C_BG, dp(18), C_ACCENT)
        root.setPadding(dp(12), dp(10), dp(12), dp(12))

        // Шапка
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mini = PixelSprite(ctx).apply { px = dp(2).toFloat() }
        header.addView(mini)
        header.addView(TextView(ctx).apply {
            text = "Пикс"
            setTextColor(C_TEXT)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val bc = chip("") { toggleBusy() }
        busyChip = bc
        header.addView(bc)
        header.addView(chip("✕") { closePanel() }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(6) })
        root.addView(header)

        // Лента
        val scroll = ScrollView(ctx)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(box)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(250)).apply {
            topMargin = dp(8)
        })
        feedBox = box
        feedScroll = scroll

        // Быстрые кнопки
        val quick = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val gap = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(6) }
        quick.addView(chip("📬 Сводка") { send("Сделай короткую сводку новых сообщений: кто писал и что из этого важно.") }, gap)
        quick.addView(chip("👥 Группы") { send("Что интересного было в группах, за которыми ты следишь?") }, LinearLayout.LayoutParams(gap))
        quick.addView(chip("✅ Задачи") { send("Покажи мои задачи и напоминания.") }, LinearLayout.LayoutParams(gap))
        quick.addView(chip("👀 Прочитано") {
            Store.markAllSeen()
            applyMood()
            addFeed(2, "Все сообщения отмечены прочитанными")
        }, LinearLayout.LayoutParams(gap))
        quick.addView(chip("🤫 Помолчи") {
            Store.buddyMuteUntil = System.currentTimeMillis() + 30 * 60_000L
            addFeed(2, "Ок, полчаса молчу и не комментирую экран 🤐")
        }, LinearLayout.LayoutParams(gap))
        quick.addView(chip("⏻ Выключить") {
            Store.turnOff()
        }, LinearLayout.LayoutParams(gap))
        quick.addView(chip("🧹 Очистить") {
            feed.clear()
            Agent.reset()
            renderFeed()
        }, LinearLayout.LayoutParams(gap))
        root.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(quick)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        // Поле ввода
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val et = EditText(ctx).apply {
            hint = "Поручи что-нибудь…"
            setHintTextColor(0xFF6E7D89.toInt())
            setTextColor(C_TEXT)
            textSize = 15f
            background = rounded(C_CARD, dp(12))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false)
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        val submit: () -> Unit = {
            val t = et.text.toString().trim()
            if (t.isNotEmpty()) {
                et.setText("")
                send(t)
            }
        }
        et.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) {
                submit()
                true
            } else false
        }
        row.addView(et, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(ctx).apply {
            text = "➤"
            textSize = 18f
            setTextColor(0xFF0E1A17.toInt())
            gravity = Gravity.CENTER
            background = rounded(C_ACCENT, dp(22))
            setOnClickListener { submit() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) })
        root.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val w = min(screenW() - dp(20), dp(400))
        val lp = overlayParams(w, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = dp(40)
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN

        try {
            wm.addView(root, lp)
        } catch (_: Exception) {
            return
        }
        panelRoot = root
        updateBusyChip()
        renderFeed()
    }

    private fun closePanel() {
        panelRoot?.let { safeRemove(it) }
        panelRoot = null
        feedBox = null
        feedScroll = null
        busyChip = null
    }

    private fun updateBusyChip() {
        val c = busyChip ?: return
        if (Store.isBusy()) {
            c.text = "🟢 Отвечаю за тебя"
            c.setTextColor(0xFF0E1A17.toInt())
            c.background = rounded(C_ACCENT, dp(14))
        } else {
            c.text = "⚪ Я занят"
            c.setTextColor(C_TEXT)
            c.background = rounded(C_CARD, dp(14))
        }
    }

    private fun toggleBusy() {
        if (Store.isBusy()) Store.setBusy(false, 0) else Store.setBusy(true, 60)
        addFeed(2, Store.busyText())
    }

    private fun send(text: String) {
        addFeed(0, text)
        val pending = Msg(1, "печатаю…")
        feed.add(pending)
        renderFeed()
        Agent.ask(text) { result ->
            pending.text = result
            if (panelRoot != null) renderFeed() else say(result.take(160))
            applyMood()
        }
    }

    private fun addFeed(who: Int, text: String) {
        feed.add(Msg(who, text))
        while (feed.size > 60) feed.removeAt(0)
        renderFeed()
    }

    private fun renderFeed() {
        val box = feedBox ?: return
        box.removeAllViews()
        if (feed.isEmpty()) {
            box.addView(bubble(Msg(1,
                "Привет! Я Пикс. Поручай мне дела:\n• «напомни через 20 минут позвонить маме»\n" +
                    "• «я занят на 2 часа» — буду отвечать в мессенджерах за тебя\n" +
                    "• «что мне писали?»\n• «ответь Саше, что буду в 7»"
            )))
        }
        for (m in feed) box.addView(bubble(m))
        feedScroll?.let { sc -> sc.post { sc.scrollTo(0, box.bottom) } }
    }

    private fun bubble(m: Msg): View {
        val tv = TextView(this).apply {
            text = m.text
            textSize = if (m.who == 2) 12f else 14f
            setLineSpacing(0f, 1.1f)
            setTextIsSelectable(false)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(6)
        when (m.who) {
            0 -> {
                tv.setTextColor(0xFF0E1A17.toInt())
                tv.background = rounded(C_ACCENT, dp(14))
                tv.setPadding(dp(12), dp(8), dp(12), dp(8))
                lp.gravity = Gravity.END
                lp.leftMargin = dp(40)
            }
            1 -> {
                tv.setTextColor(C_TEXT)
                tv.background = rounded(C_CARD, dp(14))
                tv.setPadding(dp(12), dp(8), dp(12), dp(8))
                lp.gravity = Gravity.START
                lp.rightMargin = dp(40)
            }
            else -> {
                tv.setTextColor(C_SUB)
                tv.setPadding(dp(4), dp(2), dp(4), dp(2))
                lp.gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        tv.layoutParams = lp
        return tv
    }
}
