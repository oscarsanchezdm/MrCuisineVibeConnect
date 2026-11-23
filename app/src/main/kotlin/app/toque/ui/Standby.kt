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
import java.util.concurrent.Executors
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
    /** Numéro de la veille en cours, pour ignorer une réponse root arrivée après le réveil. */
    private var sleepId = 0
    /** Réveil en cours (overlay encore là le temps de rallumer le panneau). */
    private var waking = false

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
            // Android peut réécrire le backlight : on réécrit le niveau voulu chaque minute.
            if (panelOff) PanelBacklight.set(panelLevel(), force = true)
            main.postDelayed(this, 60_000)
        }
    }

    fun start() {
        if (running) return
        running = true
        lastInputMs = SystemClock.uptimeMillis()
        // Processus tué en veille : lcd-backlight peut rester à 0 sans overlay.
        PanelBacklight.ensureOn()
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
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(800).start()
        val id = ++sleepId
        if (showClock) {
            panelOff = false
            brightness(0.02f)
        } else {
            // Coupe le panneau via sysfs (root). Ne pas toucher WindowManager d'abord : sur le
            // SKMC 1200 son animation de luminosité réécrit lcd-backlight (~10) juste après un
            // echo 0 et laisse le panneau allumé. Repli WindowManager seulement si root échoue.
            panelOff = true
            PanelBacklight.off { ok ->
                if (!ok && id == sleepId && asleep && panelOff) {
                    panelOff = false
                    PanelBacklight.releaseHold()
                    brightness(0.01f)
                }
            }
        }
        refresh()
        when (a.prefs.standbyKnob) {
            "dim" -> KnobLight.start(KnobLight.NIGHT_LEVEL)
            "off" -> KnobLight.start(0)
        }
        main.postDelayed(tick, 60_000)
    }

    private fun wake() {
        val v = view ?: return
        if (waking) return
        main.removeCallbacks(tick)
        v.animate().cancel()
        box = null
        clock = null
        hot = null
        val wasPanelOff = panelOff
        panelOff = false
        if (wasPanelOff) {
            // Garder l'overlay noir jusqu'au restore sysfs : sinon Android rallume le panneau
            // (OVERRIDE_NONE) puis une écriture 0 encore en file l'éteint → flash allumé/éteint.
            waking = true
            var finished = false
            fun finish() {
                if (finished) return
                finished = true
                waking = false
                if (view !== v) return
                root.removeView(v)
                view = null
                brightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
                KnobLight.stop()
                if (running) onWake?.invoke()
            }
            PanelBacklight.restore { finish() }
            // Si Magisk/su ne répond pas : ne pas laisser l'écran noir pour toujours.
            main.postDelayed({ finish() }, 6_000)
        } else {
            root.removeView(v)
            view = null
            brightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            KnobLight.stop()
            if (running) onWake?.invoke()
        }
    }

    private fun refresh() {
        clock?.text = if (TimeSync.showClock(a)) MainActivity.now() else ""
        val temp = a.snapshot.actualTempC
        val hotNow = temp >= 50
        hot?.let {
            it.text = if (hotNow) tr("Bol chaud : %d °C", temp) else ""
            it.visibility = if (hotNow) View.VISIBLE else View.GONE
        }
        // Sans horloge : rallume le panneau seulement pour l'alerte bol chaud (écrit seulement si le niveau change).
        if (panelOff) PanelBacklight.set(panelLevel())
    }

    private fun panelLevel(): Int = if (hot?.visibility == View.VISIBLE) PanelBacklight.ALERT_LEVEL else 0

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
     * Écriture en root (Magisk) : le nœud est `system:system`. Chaque appel à `su` peut prendre
     * plusieurs secondes, donc tout passe par un fil dédié, dans l'ordre, comme [KnobLight] ;
     * le thread principal ne fait que noter le niveau voulu et n'écrit que s'il change.
     */
    private object PanelBacklight {
        private const val PATH = "/sys/class/leds/lcd-backlight/brightness"
        const val ALERT_LEVEL = 24
        private val main = Handler(Looper.getMainLooper())
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "toque-ecran").apply { isDaemon = true } }
        /** Dernier niveau demandé (thread principal) ; -1 hors veille. */
        @Volatile private var wanted = -1
        /** Niveau d'avant la veille (fil dédié) ; -1 si le panneau n'a pas été coupé. */
        private var saved = -1
        /**
         * False dès le réveil : les `echo 0` encore en file ne s'exécutent plus.
         * Évite aussi d'empiler des `su` Magisk (qui bloquent le pont série ttyMT0).
         */
        @Volatile private var holdingOff = false

        /** Note le niveau actuel puis coupe le panneau ; [done] reçoit le résultat sur le thread principal. */
        fun off(done: (Boolean) -> Unit) {
            wanted = 0
            holdingOff = true
            worker.execute {
                if (!holdingOff) {
                    main.post { done(false) }
                    return@execute
                }
                val r = RootShell.run("cat $PATH && echo 0 > $PATH", 5_000)
                val n = r.out.trim().lineSequence().firstOrNull()?.trim()?.toIntOrNull()
                val ok = r.ok && n != null
                if (ok && saved < 0) saved = n!!.coerceIn(1, 255)
                main.post { done(ok && holdingOff) }
            }
        }

        /** Rallume le panneau (un seul `su`) puis [done] sur le thread principal. */
        fun restore(done: (() -> Unit)? = null) {
            holdingOff = false
            wanted = -1
            worker.execute {
                val v = saved.let { if (it > 0) it else 255 }
                saved = -1
                RootShell.run("echo $v > $PATH", 5_000)
                main.post { done?.invoke() }
            }
        }

        /** Au démarrage : si le panneau est resté à 0 (kill en veille), un seul `su` le rallume. */
        fun ensureOn() {
            holdingOff = false
            wanted = -1
            saved = -1
            worker.execute {
                RootShell.run("c=\$(cat $PATH); [ \"\$c\" = 0 ] && echo 255 > $PATH", 5_000)
            }
        }

        fun releaseHold() {
            holdingOff = false
            wanted = -1
        }

        /** [force] réécrit même si le niveau n'a pas changé (Android a pu le remettre). */
        fun set(level: Int, force: Boolean = false) {
            val v = level.coerceIn(0, 255)
            if (!holdingOff) return
            if (!force && v == wanted) return
            wanted = v
            worker.execute {
                if (!holdingOff || saved <= 0) return@execute
                if (wanted != v) return@execute
                RootShell.run("echo $v > $PATH", 5_000)
            }
        }
    }
}
