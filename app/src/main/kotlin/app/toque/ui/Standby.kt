package app.toque.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.TimeSync
import app.toque.core.i18n.tr
import app.toque.core.machine.RootShell
import app.toque.core.machine.Snapshot
import kotlin.random.Random

/**
 * Mise en veille : après quelques minutes sans geste et sans programme en cours, l'écran
 * s'éteint. Avec [Prefs.standbyShowClock], l'heure reste affichée (rétroéclairage minimal) ;
 * sinon le panneau est coupé via le backlight système (`lcd-backlight`, root). L'alerte bol
 * chaud rallume le panneau le temps qu'elle soit visible. Le premier geste réveille.
 */
class Standby(private val a: MainActivity, private val root: FrameLayout) {
    private val main = Handler(Looper.getMainLooper())
    private var lastInputMs = SystemClock.uptimeMillis()
    private var lastWeight = Int.MIN_VALUE
    private var lastLid: Boolean? = null
    private var view: FrameLayout? = null
    private var box: LinearLayout? = null
    private var clock: TextView? = null
    private var hot: TextView? = null
    private var running = false
    private var wokeMs = 0L
    /** Veille sans horloge : panneau coupé via sysfs. */
    private var panelOff = false

    val asleep: Boolean get() = view != null

    /** Sortie de veille (pas à l'arrêt de l'écran) : l'alerte de mise à jour regarde à ce moment-là. */
    var onWake: (() -> Unit)? = null

    private val check = object : Runnable {
        override fun run() {
            if (shouldSleep()) sleep()
            main.postDelayed(this, 10_000)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            move()
            // Android peut réécrire le backlight : on le maintient à 0 si le panneau doit rester off.
            if (panelOff && hot?.visibility != View.VISIBLE) PanelBacklight.set(0)
            main.postDelayed(this, 60_000)
        }
    }

    fun start() {
        if (running) return
        running = true
        lastInputMs = SystemClock.uptimeMillis()
        main.postDelayed(check, 10_000)
    }

    fun stop() {
        running = false
        main.removeCallbacks(check)
        wake()
    }

    /** Un geste de la personne : repousse la veille. Vrai si ce geste a servi à réveiller (ne pas le transmettre). */
    fun onInput(): Boolean {
        lastInputMs = SystemClock.uptimeMillis()
        if (!asleep) return false
        wake()
        wokeMs = SystemClock.uptimeMillis()
        return true
    }

    /** Bouton rotatif : un tour envoie plusieurs crans, on ignore aussi ceux qui suivent le réveil. */
    fun onKnob(): Boolean {
        if (onInput()) return true
        return SystemClock.uptimeMillis() - wokeMs < 800
    }

    /** Quelque chose se passe sur le robot : on réveille et on repousse la veille. */
    fun poke() {
        lastInputMs = SystemClock.uptimeMillis()
        wake()
    }

    fun onSnapshot(s: Snapshot) {
        val weightMoved = lastWeight != Int.MIN_VALUE && Math.abs(s.weightG - lastWeight) >= 20
        val lidMoved = lastLid != null && lastLid != s.lidOpen
        lastWeight = s.weightG
        lastLid = s.lidOpen
        if (s.busy || weightMoved || lidMoved) poke()
        else if (asleep) refresh()
    }

    private fun shouldSleep(): Boolean {
        val minutes = a.prefs.standbyMinutes
        if (minutes <= 0 || asleep || !running) return false
        if (a.snapshot.busy || a.current?.keepAwake == true) return false
        return SystemClock.uptimeMillis() - lastInputMs >= minutes * 60_000L
    }

    private fun sleep() {
        a.hideKeyboard()
        val v = FrameLayout(a)
        v.setBackgroundColor(0xFF000000.toInt())
        v.isClickable = true
        val showClock = a.prefs.standbyShowClock
        val b = Ui.vertical(a, Gravity.CENTER_HORIZONTAL)
        val t = Ui.text(a, "", 150f, 0xFF7C828B.toInt(), Ui.THIN)
        if (showClock) b.addView(t, Ui.lp(Ui.WRAP, Ui.WRAP))
        val h = Ui.text(a, "", 22f, Ui.WARN, Ui.MEDIUM)
        b.addView(h, Ui.lp(Ui.WRAP, Ui.WRAP, t = if (showClock) 6 else 0))
        if (showClock) {
            b.addView(Ui.text(a, tr("Touchez l'écran pour reprendre"), 17f, 0xFF3E434A.toInt()), Ui.lp(Ui.WRAP, Ui.WRAP, t = 18))
        }
        v.addView(b, Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.CENTER))
        root.addView(v, Ui.flp())
        view = v
        box = b
        clock = if (showClock) t else null
        hot = h
        refresh()
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(800).start()
        if (showClock) {
            panelOff = false
            brightness(0.02f)
        } else {
            // Coupe le panneau (pas seulement le rétroéclairage WindowManager).
            brightness(0f)
            panelOff = PanelBacklight.off().also { ok ->
                if (!ok) brightness(0.01f) // repli si pas de root / nœud absent
            }
        }
        when (a.prefs.standbyKnob) {
            "dim" -> KnobLight.start(KnobLight.NIGHT_LEVEL)
            "off" -> KnobLight.start(0)
        }
        main.postDelayed(tick, 60_000)
    }

    private fun wake() {
        val v = view ?: return
        main.removeCallbacks(tick)
        v.animate().cancel()
        root.removeView(v)
        view = null
        box = null
        clock = null
        hot = null
        if (panelOff) {
            PanelBacklight.restore()
            panelOff = false
        }
        brightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        KnobLight.stop()
        if (running) onWake?.invoke()
    }

    private fun refresh() {
        clock?.text = if (TimeSync.showClock(a)) MainActivity.now() else ""
        val temp = a.snapshot.actualTempC
        val hotNow = temp >= 50
        hot?.let {
            it.text = if (hotNow) tr("Bol chaud : %d °C", temp) else ""
            it.visibility = if (hotNow) View.VISIBLE else View.GONE
        }
        // Sans horloge : rallume le panneau seulement pour l'alerte bol chaud.
        if (panelOff) {
            if (hotNow) PanelBacklight.set(PanelBacklight.ALERT_LEVEL)
            else PanelBacklight.set(0)
        }
    }

    /** Déplace un peu l'heure chaque minute, pour ne pas marquer l'écran. */
    private fun move() {
        val v = view ?: return
        val b = box ?: return
        val dx = ((v.width - b.width) / 2 - Ui.u(40)).coerceAtLeast(0)
        val dy = ((v.height - b.height) / 2 - Ui.u(40)).coerceAtLeast(0)
        b.translationX = if (dx > 0) Random.nextInt(-dx, dx + 1).toFloat() else 0f
        b.translationY = if (dy > 0) Random.nextInt(-dy, dy + 1).toFloat() else 0f
    }

    private fun brightness(value: Float) {
        val lp = a.window.attributes
        lp.screenBrightness = value
        a.window.attributes = lp
    }

    /**
     * Rétroéclairage LCD du SKMC 1200 (`/sys/class/leds/lcd-backlight/brightness`, 0–255).
     * Écriture en root (Magisk) : le nœud est `system:system`.
     */
    private object PanelBacklight {
        private const val PATH = "/sys/class/leds/lcd-backlight/brightness"
        const val ALERT_LEVEL = 24
        private var saved = -1

        fun off(): Boolean {
            if (!RootShell.available()) return false
            val cur = RootShell.run("cat $PATH", 3_000)
            if (!cur.ok) return false
            val n = cur.out.trim().toIntOrNull() ?: return false
            if (saved < 0) saved = n.coerceIn(1, 255)
            return set(0)
        }

        fun restore() {
            val v = if (saved > 0) saved else 10
            saved = -1
            set(v)
        }

        fun set(level: Int): Boolean {
            if (!RootShell.available()) return false
            val v = level.coerceIn(0, 255)
            return RootShell.run("echo $v > $PATH", 3_000).ok
        }
    }
}
