package com.pix.helper

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private val cBg = 0xFF0F1419.toInt()
    private val cCard = 0xFF182029.toInt()
    private val cField = 0xFF1E2A33.toInt()
    private val cAccent = 0xFF4FD1B5.toInt()
    private val cText = 0xFFE6EDF3.toInt()
    private val cSub = 0xFF8B98A5.toInt()
    private val cDark = 0xFF0E1A17.toInt()

    private lateinit var busyStatus: TextView
    private lateinit var powerBtn: Button
    private lateinit var overlayBtn: Button
    private lateinit var logText: TextView
    private lateinit var keyResult: TextView
    private lateinit var reqText: TextView
    private val permRows = ArrayList<Pair<TextView, () -> Boolean>>()

    private lateinit var fGKey: EditText
    private lateinit var fGChat: EditText
    private lateinit var fGReply: EditText
    private lateinit var btnGemini: Button
    private lateinit var btnClaude: Button
    private lateinit var btnMistral: Button
    private lateinit var btnRouter: Button
    private lateinit var fMistralKey: EditText
    private lateinit var fMistralModel: EditText
    private lateinit var fOrKey: EditText
    private lateinit var fOrModel: EditText
    private lateinit var geminiBox: LinearLayout
    private lateinit var claudeBox: LinearLayout
    private var provider = Store.provider
    private lateinit var fKey: EditText
    private lateinit var fChatModel: EditText
    private lateinit var fReplyModel: EditText
    private lateinit var fName: EditText
    private lateinit var fPersona: EditText
    private lateinit var fPrefix: EditText
    private lateinit var fCooldown: EditText
    private lateinit var fCustomApps: EditText
    private lateinit var cbGroups: CheckBox
    private lateinit var cbSilence: CheckBox
    private lateinit var fSilence: EditText
    private lateinit var cbUrgent: CheckBox
    private lateinit var cbLearn: CheckBox
    private lateinit var fStyle: EditText
    private lateinit var learnedText: TextView
    private lateinit var fPeople: EditText
    private var peopleSynced = Store.peopleNotes
    private lateinit var fWhite: EditText
    private lateinit var fBlack: EditText
    private lateinit var cbSummary: CheckBox
    private lateinit var fSummaryHour: EditText
    private lateinit var cbSleep: CheckBox
    private lateinit var fWatch: EditText
    private val buddyBtns = ArrayList<Button>()
    private val styleBtns = ArrayList<Button>()
    private lateinit var eyesStatus: TextView
    private lateinit var cbBuddyImages: CheckBox
    private lateinit var fBuddyExclude: EditText
    private lateinit var fAliases: EditText
    private lateinit var cbWatchClear: CheckBox
    private lateinit var fWatchEvery: EditText
    private lateinit var cbShake: CheckBox
    private val appBoxes = LinkedHashMap<String, CheckBox>()

    private val busListener: (Event) -> Unit = { e ->
        if (e.type == "log" || e.type == "busy") refreshDynamic()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg

        val scroll = ScrollView(this).apply { setBackgroundColor(cBg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)

        // Шапка
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(PixelSprite(this).apply { px = dp(5).toFloat() })
        val headText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        headText.addView(text("Пикс", 26f, cText, bold = true))
        headText.addView(text("ИИ-помощник поверх экрана: отвечает за тебя, пока ты занят, и выполняет поручения", 14f, cSub))
        head.addView(headText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(head)

        // Главный выключатель
        powerBtn = button("") { togglePower() }
        root.addView(powerBtn, fullWidth(top = 14))

        // Режим «Я занят»
        val busyCard = card(root, "Режим «Я занят»")
        busyStatus = text("", 15f, cText)
        busyCard.addView(busyStatus)
        busyCard.addView(row(
            button("30 мин") { setBusy(30) },
            button("1 час") { setBusy(60) },
            button("3 часа") { setBusy(180) }
        ))
        busyCard.addView(row(
            button("Пока не выключу", primary = true) { setBusy(0) },
            button("Я свободен") {
                Store.setBusy(false, 0)
                refreshDynamic()
            }
        ))

        // Пикс на экране
        val ovCard = card(root, "Пикс на экране")
        ovCard.addView(text("Тап по Пиксу — чат и поручения. Перетаскивай куда удобно. Долгое нажатие — открыть это окно.", 13f, cSub))
        overlayBtn = button("") { toggleOverlay() }
        ovCard.addView(overlayBtn, fullWidth(top = 10))
        ovCard.addView(button("Открыть чат с Пиксом") { openChat() }, fullWidth(top = 6))

        // Пикс-друг
        val buddyCard = card(root, "Пикс-друг 👀")
        buddyCard.addView(text("Пикс смотрит в телефон вместе с тобой и иногда бросает реплики: ржёт с мемов, комментирует видео и то, что ты пишешь. Работает, пока глаз на экране.", 13f, cSub))
        buddyCard.addView(text("⚠️ Текст и скриншоты экрана отправляются ИИ (Gemini, Mistral или OpenRouter). На экранах с паролями и в приложениях из исключений Пикс молчит.", 12f, 0xFFFFC66B.toInt()), fullWidth(top = 6))
        val levels = listOf("Выкл", "Редко", "Иногда", "Часто")
        buddyBtns.clear()
        levels.forEachIndexed { i, label -> buddyBtns.add(button(label) { setBuddyLevel(i) }) }
        buddyCard.addView(row(*buddyBtns.toTypedArray()))
        buddyCard.addView(text("Характер:", 13f, cSub), fullWidth(top = 8))
        styleBtns.clear()
        listOf("😂 Угарный", "😏 Дерзкий", "🥰 Добрый").forEachIndexed { i, label ->
            styleBtns.add(button(label) {
                Store.buddyStyle = i
                refreshDynamic()
            })
        }
        buddyCard.addView(row(*styleBtns.toTypedArray()))
        eyesStatus = text("", 13f, cText)
        buddyCard.addView(eyesStatus, fullWidth(top = 8))
        buddyCard.addView(button("Включить «Глаза Пикса» (спец. возможности)") {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                toast("Найди «Глаза Пикса» (может быть в «Скачанные приложения») и включи")
            } catch (e: Exception) {
                openAppDetails()
            }
        }, fullWidth(top = 6))
        cbBuddyImages = checkbox(buddyCard, "Смотреть картинки и видео (скриншоты, нужен ключ Gemini)", Store.buddyImages)
        fBuddyExclude = field(buddyCard, "Не комментировать в приложениях (названия через запятую)", Store.buddyExclude, "Kaspi, Банк")

        // Доступы
        val permCard = card(root, "Доступы")
        permCard.addView(text("Без них Пикс не сможет работать. На Android 13+ для доступа к уведомлениям сначала открой «О приложении» → ⋮ → «Разрешить ограниченные настройки».", 13f, cSub))
        permRow(permCard, "Показ поверх других окон", { Settings.canDrawOverlays(this) }) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        permRow(permCard, "Доступ к уведомлениям (читать и отвечать)", { listenerEnabled() }) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        permRow(permCard, "Показ уведомлений", { notificationsAllowed() }) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            else openAppDetails()
        }
        permRow(permCard, "Точные напоминания", { exactAlarmsAllowed() }) {
            if (Build.VERSION.SDK_INT >= 31) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        permRow(permCard, "Работа в фоне без ограничений", { batteryUnrestricted() }) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                openAppDetails()
            }
        }
        permCard.addView(button("О приложении (ограниченные настройки)") { openAppDetails() }, fullWidth(top = 8))
        if (Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }) {
            permCard.addView(text("Xiaomi: обязательно включи «Автозапуск» для Пикса, иначе система не пустит его к уведомлениям.", 13f, 0xFFFFC66B.toInt()), fullWidth(top = 10))
            permCard.addView(button("Автозапуск (Xiaomi)", primary = true) { openMiuiAutostart() }, fullWidth(top = 6))
        }

        // Claude API
        val apiCard = card(root, "Мозги Пикса")
        btnGemini = button("Gemini") { setProvider("gemini") }
        btnMistral = button("Mistral") { setProvider("mistral") }
        btnRouter = button("Router") { setProvider("openrouter") }
        btnClaude = button("Claude") { setProvider("claude") }
        apiCard.addView(text("Основной ИИ:", 13f, cSub), fullWidth(top = 4))
        apiCard.addView(row(btnGemini, btnMistral, btnRouter, btnClaude))
        apiCard.addView(text("Вставь ключи от всех бесплатных — Gemini, Mistral, OpenRouter. Отвечает основной, а когда у него кончится лимит или он сбоит, Пикс сам переходит к следующему.", 12f, cSub), fullWidth(top = 6))

        geminiBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        apiCard.addView(geminiBox, fullWidth(top = 6))
        geminiBox.addView(text("Бесплатный ключ без карты: открой aistudio.google.com → «Get API key» → «Create API key», скопируй и вставь сюда.", 13f, cSub), fullWidth(top = 6))
        geminiBox.addView(button("Открыть Google AI Studio") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
            } catch (e: Exception) {
                toast("Открой aistudio.google.com/apikey в браузере")
            }
        }, fullWidth(top = 8))
        fGKey = field(geminiBox, "Ключ Gemini", Store.geminiKey, "AIza…", password = true)
        fGChat = field(geminiBox, "Модель для поручений", Store.gChatModel, "gemini-3.8-flash")
        fGReply = field(geminiBox, "Модель для автоответов", Store.gReplyModel, "gemini-3.5-flash-lite")

        apiCard.addView(text("Mistral", 15f, cText, bold = true), fullWidth(top = 14))
        apiCard.addView(text("Бесплатно: console.mistral.ai → войди → API Keys → Create new key (может попросить подтвердить номер телефона и выбрать бесплатный план Experiment).", 13f, cSub), fullWidth(top = 4))
        apiCard.addView(button("Открыть Mistral Console") { openUrl("https://console.mistral.ai/api-keys") }, fullWidth(top = 8))
        fMistralKey = field(apiCard, "Ключ Mistral", Store.mistralKey, "…", password = true)
        fMistralModel = field(apiCard, "Модель Mistral", Store.mistralModel, "mistral-medium-latest")

        apiCard.addView(text("OpenRouter", 15f, cText, bold = true), fullWidth(top = 14))
        apiCard.addView(text("Один ключ ко многим бесплатным моделям: openrouter.ai → войди → Keys → Create Key (начинается с sk-or-).", 13f, cSub), fullWidth(top = 4))
        apiCard.addView(button("Открыть OpenRouter") { openUrl("https://openrouter.ai/keys") }, fullWidth(top = 8))
        fOrKey = field(apiCard, "Ключ OpenRouter", Store.orKey, "sk-or-…", password = true)
        fOrModel = field(apiCard, "Модель (пусто — Пикс сам выберет бесплатную)", Store.orModel, "")

        claudeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        apiCard.addView(claudeBox, fullWidth(top = 6))
        claudeBox.addView(text("Ключ: console.anthropic.com → API Keys (нужно пополнить баланс).", 13f, cSub), fullWidth(top = 6))
        fKey = field(claudeBox, "Ключ Claude", Store.apiKey, "sk-ant-…", password = true)
        fChatModel = field(claudeBox, "Модель для поручений", Store.chatModel, "claude-sonnet-5-5")
        fReplyModel = field(claudeBox, "Модель для автоответов", Store.replyModel, "claude-haiku-5-5")
        applyProviderUi()
        keyResult = text("", 13f, cSub)
        reqText = text("", 12f, cSub)
        apiCard.addView(reqText, fullWidth(top = 6))
        apiCard.addView(button("Проверить все ключи") { testKey() }, fullWidth(top = 10))
        apiCard.addView(keyResult)

        // Как отвечать
        val replyCard = card(root, "Как отвечать за меня")
        cbSilence = checkbox(replyCard, "Отвечать самому, если я не ответил вовремя", Store.silenceReply)
        fSilence = field(replyCard, "Через сколько секунд молчания отвечать (0 — сразу)", Store.silenceSec.toString(), "0", number = true)
        replyCard.addView(text("Если за это время ты открыл чат или ответил сам — Пикс не вмешивается.", 12f, cSub))
        fName = field(replyCard, "Как тебя зовут", Store.ownerName, "Например: Маша")
        fPersona = field(replyCard, "Инструкция для Пикса", Store.persona, "", multiline = true)
        fPrefix = field(replyCard, "Метка перед автоответом (пусто — без метки)", Store.prefix, "🤖")
        fCooldown = field(replyCard, "Пауза между ответами в один чат, секунд («Я занят» и мгновенный режим)", Store.cooldownSec.toString(), "30", number = true)
        cbGroups = checkbox(replyCard, "Отвечать и в групповых чатах", Store.replyGroups)
        cbUrgent = checkbox(replyCard, "Будить меня, если пишут «срочно»", Store.notifyUrgent)

        // Мессенджеры
        val appsCard = card(root, "Где отвечать")
        val chosen = Store.apps
        val installed = Store.KNOWN_APPS.filterKeys { isInstalled(it) }
        val show = if (installed.isEmpty()) Store.KNOWN_APPS else installed
        for ((pkg, name) in show) {
            appBoxes[pkg] = checkbox(appsCard, name, pkg in chosen)
        }
        val custom = chosen.filter { it !in Store.KNOWN_APPS.keys }.joinToString(", ")
        appsCard.addView(button("➕ Выбрать другие приложения…") { pickApps() }, fullWidth(top = 10))
        fCustomApps = field(appsCard, "Выбранные вручную (пакеты через запятую)", custom, "com.example.app")

        // Мой стиль
        val styleCard = card(root, "Пиши как я")
        styleCard.addView(text("Пикс копирует твою манеру: длину сообщений, смайлы, сленг. Вставь несколько своих настоящих сообщений — по одному в строке.", 13f, cSub))
        fStyle = field(styleCard, "Примеры моих сообщений", Store.styleSamples, "ща гляну\nну норм)\nдавай вечером созвонимся", multiline = true)
        cbLearn = checkbox(styleCard, "Учиться на моих ответах из уведомлений", Store.learnStyle)
        learnedText = text("", 12f, cSub)
        styleCard.addView(learnedText)
        styleCard.addView(button("Забыть выученное") {
            Store.clearOwnSamples()
            refreshDynamic()
            toast("Забыл")
        }, fullWidth(top = 8))

        // Люди
        val peopleCard = card(root, "Люди")
        peopleCard.addView(text("Кто есть кто — Пикс будет отвечать каждому по-своему. Одна строка — один человек. Можно и в чате с Пиксом: «запомни: Саша — мой брат».", 13f, cSub))
        fPeople = field(peopleCard, "Заметки о людях", Store.peopleNotes, "Мама — отвечать ласково, на «ты»\nСаша — брат, можно шутить\nОльга Петровна — начальница, вежливо", multiline = true)

        // Фильтр
        val filterCard = card(root, "Кому отвечать")
        filterCard.addView(text("Имена через запятую — как они видны в мессенджере.", 13f, cSub))
        fWhite = field(filterCard, "Отвечать только им (пусто — всем)", Store.whitelist, "Саша, Мама")
        fBlack = field(filterCard, "Никогда не отвечать", Store.blacklist, "Начальник, Банк")

        // Слежка за группами
        val watchCard = card(root, "Следить за группами")
        watchCard.addView(text("В этих группах Пикс молчит, тихо читает сообщения и рассказывает самое интересное. В Telegram включи для группы уведомления «Без звука» — не выключай их совсем, иначе Пикс ничего не увидит.", 13f, cSub))
        fWatch = field(watchCard, "Названия групп через запятую", Store.watchGroups, "Класс 11Б, Пацаны")
        fAliases = field(watchCard, "Как меня называют в группах (ники, прозвища)", Store.ownerAliases, "Макс, Максон, @max")
        cbWatchClear = checkbox(watchCard, "Убирать их уведомления из шторки после прочтения", Store.watchClear)
        fWatchEvery = field(watchCard, "Присылать выжимку каждые N новых сообщений (0 — только по запросу)", Store.watchEvery.toString(), "50", number = true)
        watchCard.addView(button("Что было в группах? (сейчас)") { showDigestNow() }, fullWidth(top = 8))

        // Сводка
        val sumCard = card(root, "Сводка дня")
        cbSummary = checkbox(sumCard, "Присылать вечером, кто писал и что важно", Store.summaryEnabled)
        fSummaryHour = field(sumCard, "Во сколько (час, 0–23)", Store.summaryHour.toString(), "21", number = true)
        sumCard.addView(button("Показать сводку сейчас") { showSummaryNow() }, fullWidth(top = 8))

        // Характер
        val moodCard = card(root, "Характер глаза")
        cbSleep = checkbox(moodCard, "Засыпать ночью (23:00–7:00), тап — разбудить", Store.sleepAtNight)
        cbShake = checkbox(moodCard, "Пугаться, если трясти телефон", Store.shakeReact)
        moodCard.addView(text("Глаз смотрит в сторону центра экрана, а когда тащишь — вслед за пальцем.", 12f, cSub))

        root.addView(button("Сохранить настройки", primary = true) {
            save()
            toast("Сохранено")
        }, fullWidth(top = 16))

        // Журнал
        val logCard = card(root, "Журнал Пикса")
        logText = text("", 13f, cSub)
        logText.setLineSpacing(0f, 1.15f)
        logCard.addView(logText)

        Summary.schedule(this)

        if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        showCrashIfAny()
        // после обновления Android иногда «забывает» подключить слушатель — напоминаем
        if (listenerEnabled() && !MessageListener.connected) MessageListener.rebind(this)
        Bus.on(busListener)
        refreshDynamic()
    }

    override fun onPause() {
        Bus.off(busListener)
        save()
        super.onPause()
    }

    // ---------------- Логика ----------------

    private fun refreshDynamic() {
        if (Store.enabled) {
            powerBtn.text = "⏻ Выключить Пикса полностью"
            styleButton(powerBtn, false)
        } else {
            powerBtn.text = "▶ Включить Пикса (сейчас выключен)"
            styleButton(powerBtn, true)
        }
        busyStatus.text = Store.busyText() + when {
            !listenerEnabled() -> "\n⚠️ Нет доступа к уведомлениям — Пикс не видит сообщения"
            !MessageListener.connected -> "\n⏳ Подключаюсь к уведомлениям… Если долго — выключи и включи доступ к уведомлениям"
            else -> "\n👁 Вижу уведомления"
        }
        overlayBtn.text = if (OverlayService.running) "Убрать Пикса с экрана" else "Показать Пикса на экране"
        styleButton(overlayBtn, !OverlayService.running)
        for ((tv, check) in permRows) {
            val ok = try {
                check()
            } catch (e: Exception) {
                false
            }
            tv.text = if (ok) "✅" else "Разрешить"
            tv.setTextColor(if (ok) cAccent else cDark)
            tv.background = if (ok) null else round(cAccent, dp(10))
        }
        reqText.text = "Запросов к ИИ сегодня: ${Store.requestsToday()}"
        buddyBtns.forEachIndexed { i, b -> styleButton(b, i == Store.buddyLevel) }
        styleBtns.forEachIndexed { i, b -> styleButton(b, i == Store.buddyStyle) }
        eyesStatus.text = when {
            !eyesEnabled() -> "Глаза Пикса: выключены — включи кнопкой ниже"
            Store.buddyLevel == 0 -> "Глаза Пикса: включены, но друг выключен — выбери частоту"
            else -> "Глаза Пикса: смотрят 👀 Реплик сегодня: ${Store.buddyToday()}"
        }
        learnedText.text = "Выучено моих сообщений: ${Store.ownSamples(100).size}"
        // если Пикс сам запомнил кого-то из чата — показать это в поле
        if (Store.peopleNotes != peopleSynced) {
            peopleSynced = Store.peopleNotes
            fPeople.setText(peopleSynced)
        }
        val lines = Store.logLines(40)
        logText.text = if (lines.isEmpty()) "Пока пусто" else lines.joinToString("\n")
    }

    private fun togglePower() {
        if (Store.enabled) {
            save()
            Store.turnOff()
            OverlayService.stop(this)
            toast("Пикс выключен — никому не отвечает")
        } else {
            Store.turnOn()
            toast("Пикс включён")
        }
        overlayBtn.postDelayed({ refreshDynamic() }, 300)
    }

    private fun setBusy(minutes: Int) {
        if (!Store.enabled) Store.turnOn()
        save()
        Store.setBusy(true, minutes)
        if (!listenerEnabled()) toast("Дай доступ к уведомлениям, иначе Пикс не увидит сообщения")
        if (!Brain.hasKey()) {
            toast("Вставь ключ ${Brain.name()} в блоке «Мозги Пикса»")
        }
        refreshDynamic()
    }

    private fun toggleOverlay() {
        if (OverlayService.running) {
            Store.overlayEnabled = false
            OverlayService.stop(this)
            overlayBtn.postDelayed({ refreshDynamic() }, 300)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Сначала разреши показ поверх окон")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        save()
        if (!Store.enabled) Store.turnOn()
        Store.overlayEnabled = true
        OverlayService.start(this)
        overlayBtn.postDelayed({ refreshDynamic() }, 400)
    }

    private fun openChat() {
        if (!Settings.canDrawOverlays(this)) {
            toggleOverlay()
            return
        }
        save()
        Store.overlayEnabled = true
        OverlayService.start(this, openPanel = true)
        moveTaskToBack(true)
    }

    private fun save() {
        Store.provider = provider
        Store.mistralKey = fMistralKey.text.toString().trim()
        Store.mistralModel = fMistralModel.text.toString().trim().ifEmpty { "mistral-medium-latest" }
        Store.orKey = fOrKey.text.toString().trim()
        Store.orModel = fOrModel.text.toString().trim()
        Store.geminiKey = fGKey.text.toString().trim()
        Store.gChatModel = fGChat.text.toString().trim().ifEmpty { "gemini-3.8-flash" }
        Store.gReplyModel = fGReply.text.toString().trim().ifEmpty { "gemini-3.5-flash-lite" }
        Store.apiKey = fKey.text.toString().trim()
        Store.chatModel = fChatModel.text.toString().trim().ifEmpty { "claude-sonnet-5-5" }
        Store.replyModel = fReplyModel.text.toString().trim().ifEmpty { "claude-haiku-5-5" }
        Store.ownerName = fName.text.toString().trim()
        Store.persona = fPersona.text.toString().trim().ifEmpty { Store.DEFAULT_PERSONA }
        Store.prefix = fPrefix.text.toString().trim()
        Store.cooldownSec = fCooldown.text.toString().trim().toIntOrNull()?.coerceIn(0, 86_400) ?: 30
        Store.replyGroups = cbGroups.isChecked
        Store.silenceReply = cbSilence.isChecked
        Store.silenceSec = fSilence.text.toString().trim().toIntOrNull()?.coerceIn(0, 7200) ?: 120
        Store.notifyUrgent = cbUrgent.isChecked
        Store.styleSamples = fStyle.text.toString().trim()
        Store.learnStyle = cbLearn.isChecked
        if (Store.peopleNotes == peopleSynced) {
            Store.peopleNotes = fPeople.text.toString().trim()
            peopleSynced = Store.peopleNotes
        }
        Store.whitelist = fWhite.text.toString().trim()
        Store.blacklist = fBlack.text.toString().trim()
        Store.sleepAtNight = cbSleep.isChecked
        Store.buddyImages = cbBuddyImages.isChecked
        Store.buddyExclude = fBuddyExclude.text.toString().trim()
        Store.watchGroups = fWatch.text.toString().trim()
        Store.ownerAliases = fAliases.text.toString().trim()
        Store.watchClear = cbWatchClear.isChecked
        Store.watchEvery = fWatchEvery.text.toString().trim().toIntOrNull()?.coerceIn(0, 10_000) ?: 50
        Store.shakeReact = cbShake.isChecked
        val newHour = fSummaryHour.text.toString().trim().toIntOrNull()?.coerceIn(0, 23) ?: 21
        if (Store.summaryEnabled != cbSummary.isChecked || Store.summaryHour != newHour) {
            Store.summaryEnabled = cbSummary.isChecked
            Store.summaryHour = newHour
            Summary.schedule(this)
        }
        val set = LinkedHashSet<String>()
        for ((pkg, cb) in appBoxes) if (cb.isChecked) set.add(pkg)
        // выбор по неустановленным известным приложениям сохраняем как был
        for (pkg in Store.apps) if (pkg in Store.KNOWN_APPS.keys && pkg !in appBoxes.keys) set.add(pkg)
        fCustomApps.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { set.add(it) }
        Store.apps = set
    }

    /** Диалог со всеми установленными приложениями — отметь, где Пиксу отвечать. */
    private fun pickApps() {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = pm.queryIntentActivities(main, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName && it.first !in appBoxes.keys }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        if (list.isEmpty()) {
            toast("Не удалось получить список приложений")
            return
        }
        val current = fCustomApps.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        val labels = list.map { "${it.second}\n${it.first}" }.toTypedArray()
        val checked = BooleanArray(list.size) { list[it].first in current }
        android.app.AlertDialog.Builder(this)
            .setTitle("Где Пиксу отвечать")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Готово") { _, _ ->
                for (i in list.indices) {
                    if (checked[i]) current.add(list[i].first) else current.remove(list[i].first)
                }
                fCustomApps.setText(current.joinToString(", "))
                save()
                toast("Сохранено")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showCrashIfAny() {
        val prefs = getSharedPreferences("pix", MODE_PRIVATE)
        val crash = prefs.getString("lastCrash", null) ?: return
        prefs.edit().remove("lastCrash").apply()
        android.app.AlertDialog.Builder(this)
            .setTitle("😵 Пикс упал в прошлый раз")
            .setMessage("Сделай скрин этого окна и отправь разработчику:\n\n$crash")
            .setPositiveButton("Скопировать") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("pix crash", crash))
                toast("Скопировано — вставь в чат")
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun eyesEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("$packageName/")
    }

    private fun setBuddyLevel(i: Int) {
        Store.buddyLevel = i
        if (i > 0 && !eyesEnabled()) toast("Теперь включи «Глаза Пикса» кнопкой ниже")
        if (i > 0 && !OverlayService.running) toast("И покажи Пикса на экране — он комментирует, только когда виден")
        refreshDynamic()
    }

    private fun showDigestNow() {
        save()
        toast("Читаю группы…")
        Thread {
            val t = try {
                Digest.all()
            } catch (e: Exception) {
                "⚠️ " + (e.message ?: e.toString())
            }
            runOnUiThread {
                android.app.AlertDialog.Builder(this)
                    .setTitle("👥 Что было в группах")
                    .setMessage(t)
                    .setPositiveButton("Ок", null)
                    .show()
            }
        }.start()
    }

    private fun showSummaryNow() {
        save()
        toast("Составляю сводку…")
        Thread {
            val t = try {
                Summary.build()
            } catch (e: Exception) {
                "⚠️ " + (e.message ?: e.toString())
            }
            runOnUiThread {
                android.app.AlertDialog.Builder(this)
                    .setTitle("📋 Сводка за сегодня")
                    .setMessage(t)
                    .setPositiveButton("Ок", null)
                    .show()
            }
        }.start()
    }

    private fun setProvider(p: String) {
        provider = p
        applyProviderUi()
        save()
    }

    private fun applyProviderUi() {
        styleButton(btnGemini, provider == "gemini")
        styleButton(btnMistral, provider == "mistral")
        styleButton(btnRouter, provider == "openrouter")
        styleButton(btnClaude, provider == "claude")
        // ключи бесплатных видны всегда — они нужны для чередования
        geminiBox.visibility = View.VISIBLE
        claudeBox.visibility = if (provider == "claude") View.VISIBLE else View.GONE
    }

    private fun openUrl(u: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        } catch (e: Exception) {
            toast("Открой $u в браузере")
        }
    }

    private fun testKey() {
        save()
        keyResult.text = "Проверяю все ключи…"
        keyResult.setTextColor(cSub)
        Thread {
            val res = try {
                Brain.testAll()
            } catch (e: Exception) {
                "⚠️ " + (e.message ?: e.toString())
            }
            runOnUiThread {
                keyResult.text = res
                keyResult.setTextColor(if (res.contains("✅")) cAccent else 0xFFFF8A8A.toInt())
            }
        }.start()
    }

    private fun listenerEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return s.contains(packageName)
    }

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun exactAlarmsAllowed(): Boolean =
        Build.VERSION.SDK_INT < 31 || getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun batteryUnrestricted(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun openMiuiAutostart() {
        val tries = listOf(
            Intent().setComponent(android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
        )
        for (i in tries) {
            try {
                startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
        toast("Открой: Безопасность → Разрешения → Автозапуск → Пикс")
        openAppDetails()
    }

    private fun openAppDetails() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun toast(s: String) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---------------- Интерфейс ----------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(cCard, dp(16))
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        c.addView(text(title, 17f, cText, bold = true), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) })
        parent.addView(c, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })
        return c
    }

    private fun styleButton(b: Button, primary: Boolean) {
        b.background = round(if (primary) cAccent else cField, dp(12))
        b.setTextColor(if (primary) cDark else cText)
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        stateListAnimator = null
        minHeight = dp(44)
        styleButton(this, primary)
        setOnClickListener { onClick() }
    }

    private fun fullWidth(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEachIndexed { i, v ->
            r.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = dp(8)
            })
        }
        r.layoutParams = fullWidth(top = 10)
        return r
    }

    private fun field(
        parent: LinearLayout, label: String, value: String, hint: String,
        password: Boolean = false, multiline: Boolean = false, number: Boolean = false
    ): EditText {
        parent.addView(text(label, 13f, cSub), fullWidth(top = 10))
        val e = EditText(this).apply {
            setText(value)
            this.hint = hint
            setHintTextColor(0xFF56636F.toInt())
            setTextColor(cText)
            textSize = 15f
            background = round(cField, dp(10))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                number -> InputType.TYPE_CLASS_NUMBER
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multiline) {
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
            }
        }
        parent.addView(e, fullWidth(top = 4))
        return e
    }

    private fun checkbox(parent: LinearLayout, label: String, checked: Boolean): CheckBox {
        val cb = CheckBox(this).apply {
            text = label
            isChecked = checked
            setTextColor(cText)
            textSize = 14f
            buttonTintList = ColorStateList.valueOf(cAccent)
        }
        parent.addView(cb, fullWidth(top = 4))
        return cb
    }

    private fun permRow(parent: LinearLayout, label: String, check: () -> Boolean, request: () -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        r.addView(text(label, 14f, cText), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val status = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                val ok = try {
                    check()
                } catch (e: Exception) {
                    false
                }
                if (!ok) {
                    try {
                        request()
                    } catch (e: Exception) {
                        openAppDetails()
                    }
                }
            }
        }
        r.addView(status)
        parent.addView(r, fullWidth(top = 10))
        permRows.add(status to check)
    }
}
