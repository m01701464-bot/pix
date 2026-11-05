package com.pix.helper

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * «Глаза Пикса»: смотрит на экран и время от времени комментирует происходящее, как друг рядом.
 * Читает текст на экране, на Android 11+ может сделать скриншот (мемы, кадры из видео).
 */
class PixEyes : AccessibilityService() {

    companion object {
        @Volatile
        var running = false

        /** Системные экраны, где комментировать нечего */
        private val IGNORE_PARTS = listOf("systemui", "inputmethod", "keyboard", "launcher", ".home", "swiftkey", "packageinstaller", "permissioncontroller")
    }

    private class Scan {
        var password = false
        var last = ""
        var typed = ""
    }

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var currentPkg = ""
    private var lastEvent = 0L
    private var dirty = false
    private var lastComment = 0L
    private var lastSig = ""
    @Volatile
    private var working = false

    private val tick = object : Runnable {
        override fun run() {
            try {
                check()
            } catch (_: Exception) {
                working = false
            }
            main.postDelayed(this, 4_000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        Store.log("busy", "👀 Глаза Пикса включены")
        main.removeCallbacks(tick)
        main.postDelayed(tick, 4_000)
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || ignored(pkg)) return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != currentPkg) {
            currentPkg = pkg
        }
        dirty = true
        lastEvent = System.currentTimeMillis()
    }

    private fun ignored(pkg: String): Boolean {
        val p = pkg.lowercase()
        return IGNORE_PARTS.any { p.contains(it) } || p == "android"
    }

    private fun check() {
        val level = Store.buddyLevel
        if (level <= 0 || working || !dirty) return
        if (!Store.enabled || !OverlayService.running) return
        val now = System.currentTimeMillis()
        if (now < Store.buddyMuteUntil) return
        val interval = when (level) {
            1 -> 180_000L
            2 -> 90_000L
            else -> 40_000L
        }
        if (now - lastComment < interval) return
        if (now - lastEvent < 1_500) return // ждём, пока экран «успокоится»
        if (!(getSystemService(POWER_SERVICE) as PowerManager).isInteractive) return
        if (Store.buddyToday() >= 200) return

        val pkg = currentPkg
        if (pkg.isEmpty() || ignored(pkg)) return
        val app = MessageListener.appLabel(this, pkg)
        if (Store.buddyExcluded(app, pkg)) return

        val root = rootInActiveWindow ?: return
        val scan = Scan()
        val sb = StringBuilder()
        collect(root, sb, 0, scan)
        if (scan.password) {
            dirty = false
            return // экран с паролем — молчим
        }
        val text = sb.toString().trim()
        val sig = pkg + "|" + text.take(500).hashCode()
        if (sig == lastSig) {
            dirty = false
            return
        }
        dirty = false
        lastSig = sig
        lastComment = now
        working = true

        val wantImage = Store.buddyImages && Store.geminiKey.isNotBlank() && Build.VERSION.SDK_INT >= 30
        if (wantImage) {
            shoot { img -> exec.execute { react(app, text, scan.typed, img) } }
        } else {
            exec.execute { react(app, text, scan.typed, null) }
        }
    }

    private fun collect(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int, st: Scan) {
        if (node == null || depth > 40 || sb.length > 1500) return
        if (node.isPassword) {
            st.password = true
            return
        }
        val t = (node.text ?: node.contentDescription)?.toString()?.trim()
        if (!t.isNullOrEmpty() && t.length > 1 && t != st.last) {
            sb.append(t.take(300)).append('\n')
            st.last = t
        }
        if (node.isEditable && node.isFocused) {
            val typed = node.text?.toString()?.trim()
            if (!typed.isNullOrEmpty()) st.typed = typed.take(300)
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), sb, depth + 1, st)
    }

    /** Скриншот (Android 11+), уменьшенный и сжатый, в base64. */
    private fun shoot(done: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) {
            done(null)
            return
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY, mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val b64 = try {
                            val hb = result.hardwareBuffer
                            val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hb.close()
                            soft?.let { encode(it) }
                        } catch (e: Exception) {
                            null
                        }
                        done(b64)
                    }

                    override fun onFailure(errorCode: Int) {
                        done(null)
                    }
                }
            )
        } catch (e: Exception) {
            done(null)
        }
    }

    private fun encode(bmp: Bitmap): String {
        val maxSide = 640f
        val scale = maxSide / maxOf(bmp.width, bmp.height)
        val b = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        } else bmp
        val os = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 60, os)
        return Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP)
    }

    private fun react(app: String, screen: String, typed: String, image: String?) {
        try {
            if (screen.isBlank() && image == null) return
            val out = Brain.comment(app, screen, typed, image).trim().trim('"', '«', '»')
            if (out.isEmpty() || out.contains("SKIP", ignoreCase = true) && out.length < 12) return
            val i = out.indexOf('|')
            val emo = if (i in 1..10) out.substring(0, i).trim().lowercase() else "ок"
            val line = (if (i in 1..10) out.substring(i + 1) else out).trim()
            if (line.isEmpty() || line.equals("SKIP", true)) return
            Store.countBuddy()
            Bus.emit(Event("buddy", "$emo|$line"))
        } catch (_: Exception) {
            // друг просто промолчит
        } finally {
            working = false
        }
    }
}
