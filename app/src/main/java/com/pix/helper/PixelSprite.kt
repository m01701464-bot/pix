package com.pix.helper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Пикс — пиксельный глаз-ромб со зрачком посередине.
 * Смотрит, моргает, щурится и живёт своей жизнью: надевает колпак перед сном, читает,
 * пьёт кофе, слушает музыку, ругается в телефоне, прячется за край экрана и т.д.
 */
class PixelSprite(ctx: Context) : View(ctx) {

    enum class Mood { IDLE, DUTY, THINKING, HAPPY, ALERT, SLEEP, DIZZY }

    /** Сценки-анимации */
    enum class Act {
        NONE, GO_SLEEP, WAKE, POKE, PHONE, WAVE, READ, COFFEE, MUSIC, PEEK, BALL,
        COOL, SNACK, SELFIE, JUMP, TIRED, CHARGE, YAWN, SNEEZE
    }

    var mood: Mood = Mood.IDLE
        set(v) {
            if (v != field) {
                if (v == Mood.SLEEP) play(Act.GO_SLEEP)
                else if (field == Mood.SLEEP || capOn) play(Act.WAKE)
            }
            field = v
            invalidate()
        }

    var badge: Int = 0
        set(v) {
            field = v
            invalidate()
        }

    var px: Float = 5f * ctx.resources.displayMetrics.density
        set(v) {
            field = v
            requestLayout()
        }

    /** Куда смотреть «по умолчанию» (-2..2): к центру экрана */
    var homeLook = 0

    /** Принудительный взгляд (например, вслед за пальцем); null — сам решает */
    var lookOverride: Int? = null

    /** Сценки можно отключить (например, для маленьких иконок в меню) */
    var actsEnabled = true

    var squish = false
        set(v) {
            field = v
            invalidate()
        }

    var act: Act = Act.NONE
        private set
    private var actTick = 0
    private var capOn = false

    private val paint = Paint()
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rnd = Random()
    private var tick = 0
    private var blinkLeft = 0
    private var look = 0
    private var lookY = 0
    private var lookHold = 0

    private val loop = object : Runnable {
        override fun run() {
            step()
            invalidate()
            postDelayed(this, 120)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(loop)
        post(loop)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(loop)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(((COLS + 2 * PAD_X) * px).toInt(), ((ROWS + PAD_TOP + PAD_BOTTOM) * px).toInt())
    }

    /** Длительность сценки в кадрах (кадр — 120 мс) */
    private fun lengthOf(a: Act): Int = when (a) {
        Act.NONE -> 0
        Act.GO_SLEEP -> 32
        Act.WAKE -> 18
        Act.POKE -> 26
        Act.PHONE -> 46
        Act.WAVE -> 18
        Act.READ -> 56
        Act.COFFEE -> 44
        Act.MUSIC -> 42
        Act.PEEK -> 44
        Act.BALL -> 46
        Act.COOL -> 46
        Act.SNACK -> 40
        Act.SELFIE -> 24
        Act.JUMP -> 10
        Act.TIRED -> 40
        Act.CHARGE -> 30
        Act.YAWN -> 26
        Act.SNEEZE -> 18
    }

    /** Запустить сценку */
    fun play(a: Act) {
        if (!actsEnabled && a != Act.NONE) {
            if (a == Act.GO_SLEEP) capOn = true
            if (a == Act.WAKE) capOn = false
            return
        }
        // сон важнее всего — не перебиваем засыпание
        if (act == Act.GO_SLEEP && a != Act.WAKE) return
        act = a
        actTick = 0
        if (a == Act.WAKE) capOn = false
        invalidate()
    }

    /** В какую сторону край экрана: -1 — слева, 1 — справа */
    private fun edgeSide(): Int = if (homeLook > 0) -1 else 1

    private fun step() {
        tick++
        if (act != Act.NONE) {
            actTick++
            if (actTick >= lengthOf(act)) {
                if (act == Act.GO_SLEEP) capOn = true
                act = Act.NONE
                actTick = 0
            }
        }

        if (blinkLeft > 0) blinkLeft--
        else if (act == Act.NONE && mood != Mood.THINKING && mood != Mood.DIZZY && rnd.nextInt(100) < 3) blinkLeft = 2

        lookY = 0
        val ov = lookOverride
        val side = edgeSide()
        val t = actTick
        when {
            act == Act.POKE -> {
                look = side * 2
                lookY = -1
            }
            act == Act.PHONE -> {
                look = -side * 2
                lookY = 1
            }
            act == Act.READ -> {
                // глаз бегает по строчкам
                look = -side + listOf(-1, 0, 1, 1)[(t / 3) % 4]
                lookY = 1
            }
            act == Act.COFFEE -> {
                look = -side
                lookY = 1
            }
            act == Act.BALL -> {
                look = (ballX() - CENTER).coerceIn(-2, 2)
                lookY = -1
            }
            act == Act.MUSIC -> look = if ((t / 3) % 2 == 0) -1 else 1
            act == Act.PEEK -> look = -side * 2
            act == Act.SELFIE -> {
                look = side
                lookY = -1
            }
            act == Act.SNACK -> {
                look = -side
                lookY = 1
            }
            act == Act.WAVE || act == Act.JUMP || act == Act.CHARGE -> look = 0
            act == Act.GO_SLEEP -> look = if (t < 16) side * 2 else 0
            mood == Mood.THINKING -> look = if ((tick / 4) % 2 == 0) -2 else 2
            mood == Mood.DIZZY -> look = DIZZY_PATH[tick % DIZZY_PATH.size]
            mood == Mood.ALERT || mood == Mood.HAPPY || mood == Mood.SLEEP -> look = ov ?: 0
            ov != null -> look = ov
            lookHold > 0 -> lookHold--
            else -> {
                look = when (rnd.nextInt(10)) {
                    0 -> -2
                    1 -> 2
                    2 -> -1
                    3 -> 1
                    else -> homeLook
                }.coerceIn(-2, 2)
                lookHold = 8 + rnd.nextInt(20)
            }
        }
    }

    // ---------------- Рисование ----------------

    private var ox = 0f
    private var oy = 0f

    /** Закрасить клетку в координатах сетки глаза (можно выходить за неё в поля) */
    private fun cell(c: Canvas, col: Int, row: Int, color: Int) {
        paint.color = color
        c.drawRect(ox + col * px, oy + row * px, ox + (col + 1) * px, oy + (row + 1) * px, paint)
    }

    private fun lerp(a: Int, b: Int, t: Float): Int = (a + (b - a) * t.coerceIn(0f, 1f)).roundToInt()

    /** На сколько клеток глаз уехал к краю (сценка «прячется») */
    private fun peekShift(): Int {
        if (act != Act.PEEK) return 0
        val far = PAD_X + 12
        val t = actTick
        val v = when {
            t < 8 -> lerp(0, far, t / 8f)
            t < 18 -> far
            t < 24 -> lerp(far, PAD_X + 5, (t - 18) / 6f) // выглядывает
            t < 34 -> PAD_X + 5 + if ((t / 3) % 2 == 0) 0 else 1
            else -> lerp(PAD_X + 5, 0, (t - 34) / 10f)
        }
        return v * edgeSide()
    }

    override fun onDraw(c: Canvas) {
        val period = when {
            act == Act.MUSIC || act == Act.PHONE -> 2
            act == Act.TIRED || mood == Mood.SLEEP -> 9
            mood == Mood.THINKING || mood == Mood.DIZZY -> 2
            else -> 5
        }
        var bob = if ((tick / period) % 2 == 0) 0f else px
        if (act == Act.JUMP) bob = px * listOf(0, 2, 4, 5, 5, 4, 2, 0, 1, 0)[actTick.coerceIn(0, 9)]
        if (act == Act.CHARGE && (actTick / 2) % 2 == 0) bob = px * 2
        ox = PAD_X * px
        oy = PAD_TOP * px - bob

        c.save()
        if (squish) c.scale(1.1f, 0.88f, width / 2f, height.toFloat())
        if (act == Act.SNEEZE && actTick in 8..11) c.translate((rnd.nextInt(3) - 1) * px * 0.6f, (rnd.nextInt(3) - 1) * px * 0.4f)
        if (act == Act.SNEEZE && actTick in 8..10) c.scale(1.12f, 0.85f, width / 2f, height.toFloat())
        val shift = peekShift()
        if (shift != 0) c.translate(shift * px, 0f)

        // глаз и подставка
        for (r in 0 until ROWS) {
            for (col in 0 until COLS) {
                val color = colorAt(r, col) ?: continue
                cell(c, col, r, color)
            }
        }

        drawActs(c)

        // колпак сидит на голове
        if (capOn && act != Act.WAKE) drawCap(c, 0, 0)

        // спит — над глазом всплывают «Z»
        if (mood == Mood.SLEEP && act == Act.NONE) {
            val phase = (tick / 6) % 3
            val zx = if (edgeSide() > 0) -5 else COLS + 1
            if (phase != 2) drawGlyph(c, GLYPH_Z, zx + 1, -1, 0.7f, C_Z)
            if (phase == 1) drawGlyph(c, GLYPH_Z, zx, -6, 1f, C_Z)
        }
        c.restore()

        // вспышка селфи
        if (act == Act.SELFIE && actTick in 14..15) {
            paint.color = 0xCCFFFFFF.toInt()
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }

        if (badge > 0) {
            val rad = px * 1.7f
            val cx = width - rad - 1f
            val cy = rad + 1f
            badgePaint.color = 0xFFFF4D5E.toInt()
            c.drawCircle(cx, cy, rad, badgePaint)
            textPaint.textSize = rad * 1.15f
            val label = if (badge > 99) "99" else badge.toString()
            c.drawText(label, cx, cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        }
    }

    /** Ручка от подставки до точки (tx, ty) с «ладошкой» на конце */
    private fun drawArm(c: Canvas, tx: Int, ty: Int) {
        val side = if (tx >= CENTER) 1 else -1
        val sx = CENTER + side * 2
        val sy = 12
        val dx = tx - sx
        val dy = ty - sy
        val steps = max(abs(dx), abs(dy)).coerceAtLeast(1)
        for (i in 0..steps) {
            val x = sx + (dx * i.toFloat() / steps).roundToInt()
            val y = sy + (dy * i.toFloat() / steps).roundToInt()
            cell(c, x, y, C_ARM)
        }
        cell(c, tx, ty, C_HAND)
        cell(c, tx + side, ty, C_HAND)
    }

    /** Нарисовать картинку-«спрайт» из строк по палитре */
    private fun drawSprite(c: Canvas, art: Array<String>, col: Int, row: Int, pal: Map<Char, Int>, mirror: Boolean = false) {
        for (r in art.indices) {
            val line = art[r]
            for (i in line.indices) {
                val color = pal[line[i]] ?: continue
                val x = if (mirror) col + (line.length - 1 - i) else col + i
                cell(c, x, row + r, color)
            }
        }
    }

    private fun drawCap(c: Canvas, dx: Int, dy: Int) {
        drawSprite(c, CAP, CAP_COL + dx, CAP_ROW + dy, CAP_PAL, mirror = edgeSide() < 0)
    }

    private fun drawGlyph(c: Canvas, g: Array<String>, col: Int, row: Int, scale: Float, color: Int) {
        paint.color = color
        val s = px * scale
        val x0 = ox + col * px
        val y0 = oy + row * px
        for (r in g.indices) for (i in g[r].indices) {
            if (g[r][i] == '#') c.drawRect(x0 + i * s, y0 + r * s, x0 + (i + 1) * s, y0 + (r + 1) * s, paint)
        }
    }

    /** Положение мячика в сценке «чеканит» */
    private fun ballX(): Int {
        val t = actTick
        val k = (t % 16) / 16f
        val dir = if ((t / 16) % 2 == 0) 1 else -1
        return CENTER + (dir * (k * 12 - 6)).roundToInt()
    }

    private fun ballY(): Int = -1 - (abs(sin(actTick / 16f * Math.PI)) * 6).roundToInt()

    /** Предмет у подставки: поднимается снизу, держится, уходит обратно */
    private fun holdRow(t: Int, len: Int, top: Int): Int = when {
        t < 5 -> lerp(16, top, t / 5f)
        t > len - 5 -> lerp(top, 16, (t - (len - 5)) / 5f)
        else -> top
    }

    private fun drawActs(c: Canvas) {
        val side = edgeSide()
        val t = actTick
        val inner = -side // сторона к центру экрана
        when (act) {
            Act.GO_SLEEP -> {
                val edgeX = CENTER + side * 10
                when {
                    t < 6 -> drawArm(c, lerp(CENTER + side * 3, edgeX, t / 6f), lerp(11, 9, t / 6f))
                    t < 16 -> {
                        // несёт колпак от края экрана на голову
                        val k = (t - 6) / 10f
                        val dx = lerp(side * 9, 0, k)
                        val dy = lerp(6, 0, k)
                        drawCap(c, dx, dy)
                        drawArm(c, CENTER + dx + side, CAP_ROW + 5 + dy)
                    }
                    else -> {
                        drawCap(c, 0, 0)
                        if (t < 21) {
                            val k = (t - 16) / 5f
                            drawArm(c, lerp(CENTER + side, CENTER + side * 3, k), lerp(CAP_ROW + 5, 11, k))
                        }
                    }
                }
            }
            Act.WAKE -> {
                // колпак слетает, потягивается двумя руками
                if (t < 10) drawCap(c, side * t, -t * 2)
                if (t in 4..15) {
                    val up = if ((t / 2) % 2 == 0) -3 else -2
                    drawArm(c, CENTER - 7, up)
                    drawArm(c, CENTER + 7, up)
                }
            }
            Act.POKE -> {
                val tx = CENTER + side * 7
                when {
                    t < 6 -> drawArm(c, tx, lerp(11, -4, t / 6f))
                    t < 20 -> drawArm(c, tx, if ((t / 2) % 2 == 0) -5 else -3)
                    else -> drawArm(c, tx, lerp(-4, 11, (t - 20) / 6f))
                }
            }
            Act.PHONE -> {
                val baseX = CENTER + inner * 6
                val top = holdRow(t, 46, 8)
                for (r in 0 until 5) for (i in -1..1) {
                    val color = when {
                        r == 0 || r == 4 || abs(i) == 1 -> C_DARK
                        t in 5..41 && rnd.nextInt(3) == 0 -> 0xFFFFFFFF.toInt()
                        else -> 0xFF6AB8FF.toInt()
                    }
                    cell(c, baseX + i, top + r, color)
                }
                drawArm(c, baseX, top + 5)
                if (t in 8..40) {
                    val g = when ((t / 5) % 3) {
                        0 -> GLYPH_HASH
                        1 -> GLYPH_BANG
                        else -> GLYPH_STAR
                    }
                    drawGlyph(c, g, CENTER + inner * 5 - 1, -6, 0.8f, 0xFFFF5A5A.toInt())
                }
            }
            Act.WAVE -> {
                val tx = CENTER + inner * 8 + if ((t / 2) % 2 == 0) 0 else inner
                drawArm(c, tx, if (t < 3 || t > 15) 6 else 2)
            }
            Act.READ -> {
                val baseX = CENTER + inner * 7 - 2
                val top = holdRow(t, 56, 8)
                val flip = t in 26..28
                drawSprite(c, if (flip) BOOK_FLIP else BOOK, baseX, top, BOOK_PAL)
                drawArm(c, baseX + 2, top + 4)
            }
            Act.COFFEE -> {
                val baseX = CENTER + inner * 7 - 1
                // глоток: кружка поднимается к глазу
                val top = when {
                    t in 18..26 -> 5
                    else -> holdRow(t, 44, 9)
                }
                drawSprite(c, CUP, baseX, top, CUP_PAL, mirror = inner < 0)
                drawArm(c, baseX + 1, top + 4)
                if (t in 5..40 && top > 5) {
                    // пар
                    val sx = baseX + 1 + if ((t / 3) % 2 == 0) 0 else 1
                    cell(c, sx, top - 1 - (t / 2) % 2, C_STEAM)
                    cell(c, sx + 1, top - 3 + (t / 3) % 2, C_STEAM)
                }
            }
            Act.MUSIC -> {
                drawSprite(c, PHONES, 0, -2, PHONES_PAL)
                val nx = if ((t / 8) % 2 == 0) -4 else COLS + 1
                drawGlyph(c, GLYPH_NOTE, nx, 2 - (t % 8), 0.9f, 0xFFFF9BD2.toInt())
                if (t > 4) drawGlyph(c, GLYPH_NOTE, if (nx < 0) COLS + 2 else -5, 4 - ((t + 4) % 8), 0.7f, 0xFFFFD25A.toInt())
            }
            Act.PEEK -> {}
            Act.BALL -> {
                val bx = ballX()
                val by = ballY()
                cell(c, bx, by, 0xFFFF6B4A.toInt())
                cell(c, bx + 1, by, 0xFFFF6B4A.toInt())
                cell(c, bx, by + 1, 0xFFE04830.toInt())
                cell(c, bx + 1, by + 1, 0xFFE04830.toInt())
                // ручка подбивает мячик, когда он внизу
                if (by >= -2) drawArm(c, bx, by + 2)
            }
            Act.COOL -> {
                when {
                    t < 8 -> {
                        // достаёт очки сбоку
                        val gx = lerp(side * 10, 0, t / 8f)
                        drawGlasses(c, gx)
                        drawArm(c, CENTER + gx + side * 6, 5)
                    }
                    t < 40 -> drawGlasses(c, 0)
                    else -> {
                        // снимает очки движением вверх
                        drawGlasses(c, 0, -(t - 40))
                    }
                }
            }
            Act.SNACK -> {
                val baseX = CENTER + inner * 7 - 1
                val top = holdRow(t, 40, 9)
                val bites = when {
                    t >= 30 -> 3
                    t >= 23 -> 2
                    t >= 16 -> 1
                    else -> 0
                }
                val cookie = COOKIE.map { it.toCharArray() }
                for ((r, i) in COOKIE_BITES.take(bites * 3)) cookie[r][i] = '.'
                drawSprite(c, cookie.map { String(it) }.toTypedArray(), baseX, top, COOKIE_PAL)
                drawArm(c, baseX + 1, top + 3)
                if (t in 16..36 && (t - 16) % 7 < 4) {
                    // крошки
                    val k = (t - 16) % 7
                    cell(c, baseX - 1, top + 3 + k, 0xFFC98B4A.toInt())
                    cell(c, baseX + 3, top + 4 + k, 0xFFC98B4A.toInt())
                }
            }
            Act.SELFIE -> {
                val tx = CENTER + side * 5
                val top = if (t < 4) lerp(10, -4, t / 4f) else -4
                for (r in 0 until 4) for (i in 0..2) {
                    cell(c, tx - 1 + i, top + r, if (r == 0 || r == 3 || i != 1) C_DARK else 0xFF9CFFE8.toInt())
                }
                drawArm(c, tx, top + 4)
            }
            Act.JUMP -> {
                if (t in 1..8) drawGlyph(c, GLYPH_BANG, CENTER - 1, -6, 1f, 0xFFFFD25A.toInt())
            }
            Act.TIRED -> {
                // капля пота стекает по краю
                val dy = (t / 2) % 8
                cell(c, CENTER + side * 6, 1 + dy, 0xFF6AB8FF.toInt())
                cell(c, CENTER + side * 6, 2 + dy, 0xFF9CD3FF.toInt())
            }
            Act.CHARGE -> {
                if ((t / 2) % 2 == 0) drawGlyph(c, GLYPH_BOLT, CENTER - 1, -6, 1f, 0xFFFFD25A.toInt())
            }
            Act.YAWN -> {
                if (t in 10..22) drawGlyph(c, GLYPH_Z, CENTER + side * 7, -3 - (t - 10) / 4, 0.6f, C_Z)
            }
            Act.SNEEZE -> {
                if (t in 8..14) {
                    val k = t - 8
                    drawGlyph(c, GLYPH_STAR, CENTER + inner * (5 + k), 3 - k / 2, 0.6f, 0xFF9CD3FF.toInt())
                    drawGlyph(c, GLYPH_STAR, CENTER + inner * (3 + k), 7 + k / 3, 0.5f, 0xFF9CD3FF.toInt())
                }
            }
            Act.NONE -> {}
        }
    }

    /** Тёмные очки поверх глаза */
    private fun drawGlasses(c: Canvas, dx: Int, dy: Int = 0) {
        for (r in 4..6) for (col in 1..15) {
            if (GRID[r][col] == '.') continue
            val glint = r == 4 && (col == 4 || col == 11)
            cell(c, col + dx, r + dy, if (glint) 0xFFFFFFFF.toInt() else 0xFF15151A.toInt())
        }
    }

    private fun colorAt(r: Int, c: Int): Int? {
        val ch = GRID[r][c]
        if (ch != 'W') {
            return when (ch) {
                'O' -> C_OUTLINE
                'K' -> C_STAND
                else -> null
            }
        }
        val t = actTick
        // веко
        val sleeping = mood == Mood.SLEEP && !(act == Act.GO_SLEEP && t < 22) && act != Act.WAKE
        if (blinkLeft > 0 || sleeping) return if (r == MID) C_OUTLINE else C_LID
        // засыпает — веко опускается
        if (act == Act.GO_SLEEP && t in 18..21 && r < MID - (21 - t)) return C_LID
        // довольный прищур
        val squint = mood == Mood.HAPPY || act == Act.WAVE || act == Act.CHARGE ||
            (act == Act.COFFEE && t in 18..26) || (act == Act.SELFIE && t in 10..18) ||
            (act == Act.SNACK && (t in 16..19 || t in 23..26 || t in 30..33))
        if (squint && r < MID) return C_LID
        // сонный / уставший — веко наполовину
        val half = act == Act.TIRED || (act == Act.YAWN && t in 4..20) || (act == Act.WAKE && t < 8)
        if (half && r < MID - 1) return C_LID
        // перед чихом зажмуривается
        if (act == Act.SNEEZE && t in 3..9) return if (r == MID) C_OUTLINE else C_LID

        val cx = CENTER + look
        val cy = MID + lookY
        val dr = abs(r - cy)
        val dc = abs(c - cx)
        val small = mood == Mood.ALERT || mood == Mood.DIZZY || act == Act.JUMP
        val pupilHalf = if (small) 1 else 2
        if (dc == 0 && dr <= pupilHalf) return C_PUPIL
        val inIris = (dr <= 1 && dc <= 2) || (dr == 2 && dc <= 1)
        if (inIris) {
            if (r == cy - 1 && c == cx - 1) return C_GLINT
            return irisColor()
        }
        return C_WHITE
    }

    /** Цвет радужки — индикатор состояния. */
    private fun irisColor(): Int {
        when (act) {
            Act.PHONE -> return if ((tick / 3) % 2 == 0) 0xFFFF5A5A.toInt() else 0xFFE04848.toInt()
            Act.MUSIC -> return if ((tick / 3) % 2 == 0) 0xFFFF9BD2.toInt() else 0xFFB98CFF.toInt()
            Act.CHARGE -> return 0xFFFFD25A.toInt()
            Act.TIRED -> return 0xFF8FA3A0.toInt()
            else -> {}
        }
        val on = (tick / 4) % 2 == 0
        return when (mood) {
            Mood.IDLE -> 0xFF4FD1B5.toInt()
            Mood.DUTY -> if (on) 0xFF5CFF9A.toInt() else 0xFF34C775.toInt()
            Mood.THINKING -> if ((tick / 2) % 2 == 0) 0xFF6AB8FF.toInt() else 0xFF4A8FD6.toInt()
            Mood.HAPPY -> 0xFFFF9BD2.toInt()
            Mood.ALERT -> if (on) 0xFFFF5A5A.toInt() else 0xFFC03A3A.toInt()
            Mood.SLEEP -> 0xFF4FD1B5.toInt()
            Mood.DIZZY -> if ((tick / 2) % 2 == 0) 0xFFFFD25A.toInt() else 0xFFFF9B4A.toInt()
        }
    }

    companion object {
        val GRID = arrayOf(
            "........O........",
            "......OOWOO......",
            "....OOWWWWWOO....",
            "..OOWWWWWWWWWOO..",
            ".OWWWWWWWWWWWWWO.",
            "OWWWWWWWWWWWWWWWO",
            ".OWWWWWWWWWWWWWO.",
            "..OOWWWWWWWWWOO..",
            "....OOWWWWWOO....",
            "......OOWOO......",
            "........O........",
            ".......KKK.......",
            "......KKKKK......",
            ".....KKKKKKK.....",
            "....KKKKKKKKK...."
        )
        val COLS = GRID[0].length
        val ROWS = GRID.size
        const val MID = 5
        const val CENTER = 8

        /** Поля вокруг глаза для ручек, предметов и «Z» */
        const val PAD_X = 6
        const val PAD_TOP = 7
        const val PAD_BOTTOM = 2

        /** Ночной колпак (рисуется от CAP_COL, CAP_ROW) */
        private val CAP = arrayOf(
            "........PP",
            "......bBPP",
            ".....BbB..",
            "....bBbB..",
            "...BbBbBb.",
            ".WWWWWWWWW"
        )
        private const val CAP_COL = 3
        private const val CAP_ROW = -4
        private val CAP_PAL = mapOf(
            'B' to 0xFF4A6FD9.toInt(), 'b' to 0xFF7FA0FF.toInt(),
            'W' to 0xFFF3EEE4.toInt(), 'P' to 0xFFFFF3C4.toInt()
        )

        private val BOOK = arrayOf("bbbbb", "bWbWb", "bWbWb", "bbbbb")
        private val BOOK_FLIP = arrayOf("bbbbb", "bWWWb", "bWWWb", "bbbbb")
        private val BOOK_PAL = mapOf('b' to 0xFF8B5A2B.toInt(), 'W' to 0xFFF3EEE4.toInt())

        private val CUP = arrayOf("CCC.", "MMMh", "MMMh", "MMM.")
        private val CUP_PAL = mapOf('C' to 0xFF5A3A22.toInt(), 'M' to 0xFFF3EEE4.toInt(), 'h' to 0xFFD9D1C2.toInt())

        private val COOKIE = arrayOf("cdc", "ccc", "cdc")
        private val COOKIE_BITES = listOf(0 to 2, 1 to 2, 2 to 2, 0 to 1, 1 to 1, 2 to 1, 0 to 0, 1 to 0, 2 to 0)
        private val COOKIE_PAL = mapOf('c' to 0xFFC98B4A.toInt(), 'd' to 0xFF4A2C17.toInt())

        /** Наушники: дуга над глазом и «чашки» по бокам */
        private val PHONES = arrayOf(
            ".....HHHHHHH.....",
            "...HH.......HH...",
            "..H...........H..",
            "..H...........H..",
            "..H...........H..",
            "RR.............RR",
            "RR.............RR",
            "RR.............RR",
            "RR.............RR"
        )
        private val PHONES_PAL = mapOf('H' to 0xFF2A2A33.toInt(), 'R' to 0xFFE04848.toInt())

        private val GLYPH_Z = arrayOf("####", "..#.", ".#..", "####")
        private val GLYPH_HASH = arrayOf("#.#", "###", "#.#", "###", "#.#")
        private val GLYPH_BANG = arrayOf(".#.", ".#.", ".#.", "...", ".#.")
        private val GLYPH_STAR = arrayOf("#.#", ".#.", "#.#")
        private val GLYPH_NOTE = arrayOf(".##", ".#.", ".#.", "##.", "##.")
        private val GLYPH_BOLT = arrayOf("..#", ".#.", "###", ".#.", "#..")

        private val DIZZY_PATH = intArrayOf(-2, -1, 0, 1, 2, 1, 0, -1)

        private val C_OUTLINE = 0xFF1B1B1F.toInt()
        private val C_STAND = 0xFF26262C.toInt()
        private val C_ARM = 0xFF3A3A44.toInt()
        private val C_HAND = 0xFF4D4D58.toInt()
        private val C_DARK = 0xFF111114.toInt()
        private val C_LID = 0xFFCFC6B6.toInt()
        private val C_WHITE = 0xFFF3EEE4.toInt()
        private val C_PUPIL = 0xFF111114.toInt()
        private val C_GLINT = 0xFFFFFFFF.toInt()
        private val C_Z = 0xFFB8C7D9.toInt()
        private val C_STEAM = 0xFFD9E2EA.toInt()
    }
}
