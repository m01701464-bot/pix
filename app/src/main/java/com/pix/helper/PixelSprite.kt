package com.pix.helper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import java.util.Random
import kotlin.math.abs

/** Пикс — пиксельный глаз-ромб со зрачком посередине. Смотрит, моргает, щурится. */
class PixelSprite(ctx: Context) : View(ctx) {

    enum class Mood { IDLE, DUTY, THINKING, HAPPY, ALERT }

    var mood: Mood = Mood.IDLE
        set(v) {
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

    var squish = false
        set(v) {
            field = v
            invalidate()
        }

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
        setMeasuredDimension(((COLS + 2) * px).toInt(), ((ROWS + 3) * px).toInt())
    }

    private fun step() {
        tick++
        if (blinkLeft > 0) blinkLeft--
        else if (mood != Mood.THINKING && rnd.nextInt(100) < 3) blinkLeft = 2

        when (mood) {
            // думает — зрачок бегает из стороны в сторону
            Mood.THINKING -> look = if ((tick / 4) % 2 == 0) -2 else 2
            Mood.ALERT, Mood.HAPPY -> look = 0
            else -> {
                // иногда оглядывается
                if (lookHold > 0) lookHold--
                else {
                    look = when (rnd.nextInt(10)) {
                        0 -> -2
                        1 -> 2
                        2 -> -1
                        3 -> 1
                        else -> 0
                    }
                    lookHold = 8 + rnd.nextInt(20)
                }
            }
        }
    }

    override fun onDraw(c: Canvas) {
        val period = if (mood == Mood.THINKING) 2 else 5
        val bob = if ((tick / period) % 2 == 0) 0f else px
        val ox = px
        val oy = px * 2 - bob
        c.save()
        if (squish) c.scale(1.1f, 0.88f, width / 2f, height.toFloat())
        for (r in 0 until ROWS) {
            for (col in 0 until COLS) {
                val color = colorAt(r, col) ?: continue
                paint.color = color
                c.drawRect(ox + col * px, oy + r * px, ox + (col + 1) * px, oy + (r + 1) * px, paint)
            }
        }
        c.restore()

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

    private fun colorAt(r: Int, c: Int): Int? {
        val ch = GRID[r][c]
        if (ch != 'W') {
            return when (ch) {
                'O' -> C_OUTLINE
                'K' -> C_STAND
                else -> null
            }
        }
        // веко
        if (blinkLeft > 0) return if (r == MID) C_OUTLINE else C_LID
        if (mood == Mood.HAPPY && r < MID) return C_LID

        val cx = CENTER + look
        val dr = abs(r - MID)
        val dc = abs(c - cx)
        val pupilHalf = if (mood == Mood.ALERT) 1 else 2
        if (dc == 0 && dr <= pupilHalf) return C_PUPIL
        val inIris = (dr <= 1 && dc <= 2) || (dr == 2 && dc <= 1)
        if (inIris) {
            if (r == MID - 1 && c == cx - 1) return C_GLINT
            return irisColor()
        }
        return C_WHITE
    }

    /** Цвет радужки — индикатор состояния. */
    private fun irisColor(): Int {
        val on = (tick / 4) % 2 == 0
        return when (mood) {
            Mood.IDLE -> 0xFF4FD1B5.toInt()
            Mood.DUTY -> if (on) 0xFF5CFF9A.toInt() else 0xFF34C775.toInt()
            Mood.THINKING -> if ((tick / 2) % 2 == 0) 0xFF6AB8FF.toInt() else 0xFF4A8FD6.toInt()
            Mood.HAPPY -> 0xFFFF9BD2.toInt()
            Mood.ALERT -> if (on) 0xFFFF5A5A.toInt() else 0xFFC03A3A.toInt()
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

        private val C_OUTLINE = 0xFF1B1B1F.toInt()
        private val C_STAND = 0xFF26262C.toInt()
        private val C_LID = 0xFFCFC6B6.toInt()
        private val C_WHITE = 0xFFF3EEE4.toInt()
        private val C_PUPIL = 0xFF111114.toInt()
        private val C_GLINT = 0xFFFFFFFF.toInt()
    }
}
