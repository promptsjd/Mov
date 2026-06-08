package com.example.blocker

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Handler
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.example.blocker.sensor.FlatGuard
import com.example.blocker.sensor.MotionDetector
import com.example.blocker.sensor.PhoneGuard
import com.example.blocker.session.PhoneState
import com.example.blocker.session.SessionEngine
import com.example.blocker.session.Turn
import com.example.blocker.settings.SettingsStore

class BlockerAccessibilityService : AccessibilityService(), MotionDetector.Listener {

    private enum class Phase { PRE_SESSION, ACTIVE, COMPLETE }

    companion object {
        private const val COMPLETION_DELAY_MS         = 3000L
        private const val PRE_SESSION_UPRIGHT_MAX_DEG = 25f
    }

    private lateinit var detector: MotionDetector
    private lateinit var wm: WindowManager
    private lateinit var sm: SensorManager
    private lateinit var settings: SettingsStore

    private var overlayView: OverlayView? = null
    private var currentPkg  = ""
    private var prevRealPkg = ""

    private var phase                    = Phase.PRE_SESSION
    private var sessionEngine: SessionEngine? = null
    private var currentPhoneState        = PhoneState.UPRIGHT
    private var currentZone              = OverlayView.Zone.UPRIGHT
    private var activeInstruction        = ""
    private var activeInstructionRotation = 0f
    private var movesDone                = 0

    private val guards: List<PhoneGuard> = listOf(FlatGuard())
    private var anyGuardShowing          = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        detector = MotionDetector(this)
        wm       = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sm       = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        settings = SettingsStore(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        val isNewOpen = pkg != prevRealPkg
        if (!isSystemUiPkg(pkg)) prevRealPkg = pkg

        if (pkg in settings.blockedApps && overlayView == null && isNewOpen) {
            showOverlay(pkg)
        }
    }

    private fun isSystemUiPkg(pkg: String) =
        pkg == "android" || pkg == "com.android.systemui" || pkg == "com.android.phone"

    // ── Overlay ───────────────────────────────────────────────────────────────

    private fun showOverlay(pkg: String) {
        detector.reset()
        phase         = Phase.PRE_SESSION
        sessionEngine = null
        currentPkg    = pkg

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )

        performGlobalAction(GLOBAL_ACTION_HOME)

        val view = OverlayView(
            context       = this,
            onBackPressed = ::dismissOverlay,
            onCancel      = { dismissOverlay(); performGlobalAction(GLOBAL_ACTION_HOME) },
        )
        wm.addView(view, params)
        overlayView = view

        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(detector, it, SensorManager.SENSOR_DELAY_UI, Handler(mainLooper))
        }
    }

    private fun dismissOverlay() {
        currentPkg  = ""
        overlayView?.let { wm.removeView(it) }
        overlayView = null
        sm.unregisterListener(detector)
        detector.reset()
    }

    // ── MotionDetector.Listener ───────────────────────────────────────────────

    /** Called every sensor tick — drives alpha fading only, never state changes. */
    override fun onPosition(tiltDeg: Float, invertedDeg: Float) {
        val (effTilt, effInverted) = directionalAngles(tiltDeg, invertedDeg)
        overlayView?.updateAngles(effTilt, effInverted)
        checkGuards()
        checkPreSessionStart(tiltDeg, invertedDeg)
    }

    /** Called only when the phone genuinely moves into a new orientation zone. */
    override fun onStateChanged(newState: PhoneState, prevState: PhoneState?) {
        if (anyGuardShowing) return
        when (phase) {
            Phase.PRE_SESSION -> { }  // driven by checkPreSessionStart in onPosition
            Phase.ACTIVE      -> handleStateChange(newState, prevState)
            Phase.COMPLETE    -> { }
        }
    }

    // ── Session ───────────────────────────────────────────────────────────────

    private fun startSession(homeState: PhoneState) {
        phase             = Phase.ACTIVE
        currentPhoneState = homeState
        movesDone         = 0
        sessionEngine     = SessionEngine(homeState, settings.complexity.moveCount)
        overlayView?.setStripVisible(true)
        overlayView?.setProgress(0, settings.complexity.moveCount)
        val (zone, rotation) = zoneAndRotation(homeState)
        loadInstruction(zone, rotation)
    }

    private fun handleStateChange(newState: PhoneState, prevState: PhoneState?) {
        currentPhoneState = newState
        val step = sessionEngine?.current ?: return
        val (zone, rotation) = zoneAndRotation(newState)

        if (step.to == newState && prevState == step.from) {
            onCorrectMove(newState, zone, rotation)
        } else {
            // Wrong destination or wrong direction — keep instruction visible at full alpha.
            applyZone(zone, activeInstruction, activeInstructionRotation, lockAlpha = true)
        }
    }

    private fun onCorrectMove(newState: PhoneState, zone: OverlayView.Zone, rotation: Float) {
        currentPhoneState = newState
        sessionEngine?.advance()
        movesDone++
        overlayView?.setProgress(movesDone, settings.complexity.moveCount)
        if (sessionEngine?.isComplete == true) {
            completeSession(zone, rotation)
        } else {
            loadInstruction(zone, rotation)
        }
    }

    private fun loadInstruction(zone: OverlayView.Zone = OverlayView.Zone.UPRIGHT, rotation: Float = 0f) {
        val turn = sessionEngine?.current?.turn ?: return
        activeInstruction         = turn.label
        activeInstructionRotation = rotation
        applyZone(zone, activeInstruction, rotation)
    }

    private fun completeSession(zone: OverlayView.Zone, rotation: Float) {
        phase = Phase.COMPLETE
        overlayView?.setStripVisible(false)
        val appName     = getAppName(currentPkg)
        val pkgToLaunch = currentPkg
        applyZone(zone, "Opening $appName...", rotation)
        Handler(mainLooper).postDelayed({
            if (overlayView != null) {
                prevRealPkg = pkgToLaunch
                launchApp(pkgToLaunch)
                dismissOverlay()
            }
        }, COMPLETION_DELAY_MS)
    }

    // ── Guards ────────────────────────────────────────────────────────────────

    private fun checkGuards() {
        if (phase == Phase.COMPLETE) return

        val x = detector.lastXSmooth
        val y = detector.lastYSmooth
        val z = detector.lastZSmooth

        guards.forEach { it.update(x, y, z) }

        val topGuard  = guards.maxByOrNull { it.alpha(x, y, z) }
        val alpha     = topGuard?.alpha(x, y, z) ?: 0f
        val nowShowing = alpha > 0f

        if (nowShowing) {
            overlayView?.setGuardMessage(topGuard!!.message(phase == Phase.PRE_SESSION), alpha)
        }

        if (nowShowing && !anyGuardShowing) {
            if (phase == Phase.ACTIVE) overlayView?.setStripVisible(false)
        } else if (!nowShowing && anyGuardShowing) {
            overlayView?.setGuardMessage("", 0f)
            when (phase) {
                Phase.ACTIVE -> {
                    overlayView?.setStripVisible(true)
                    overlayView?.setProgress(movesDone, settings.complexity.moveCount)
                    applyZone(currentZone, activeInstruction, activeInstructionRotation, lockAlpha = true)
                }
                else -> { }  // PRE_SESSION handled by checkPreSessionStart on next tick
            }
        }

        anyGuardShowing = nowShowing
    }

    // Starts the session once guard is clear and phone is in a stable classified zone.
    // Using tiltDeg/invertedDeg directly prevents a false UPRIGHT start when the phone
    // is in the transition gap (35°–50°) between guard-clear and landscape entry.
    private fun checkPreSessionStart(tiltDeg: Float, invertedDeg: Float) {
        if (phase != Phase.PRE_SESSION || anyGuardShowing) return
        val state = detector.classifiedState ?: return
        val canStart = state != PhoneState.UPRIGHT ||
            (tiltDeg < PRE_SESSION_UPRIGHT_MAX_DEG && invertedDeg < PRE_SESSION_UPRIGHT_MAX_DEG)
        if (canStart) startSession(state)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * When in UPRIGHT zone, suppress alpha feedback for motion in the wrong
     * direction so the instruction text stays fully visible until the user
     * commits to the correct turn.
     */
    private fun directionalAngles(tiltDeg: Float, invertedDeg: Float): Pair<Float, Float> {
        if (phase != Phase.ACTIVE || currentZone != OverlayView.Zone.UPRIGHT) {
            return Pair(tiltDeg, invertedDeg)
        }
        return when (sessionEngine?.current?.turn) {
            Turn.RIGHT    -> Pair(if (detector.lastXSmooth < 0f) tiltDeg else 0f, 0f)
            Turn.LEFT     -> Pair(if (detector.lastXSmooth > 0f) tiltDeg else 0f, 0f)
            Turn.DOWN     -> Pair(tiltDeg, invertedDeg)
            Turn.UP, null -> Pair(tiltDeg, invertedDeg)
        }
    }

    private fun zoneAndRotation(state: PhoneState): Pair<OverlayView.Zone, Float> = when (state) {
        PhoneState.UPRIGHT         -> OverlayView.Zone.UPRIGHT   to 0f
        PhoneState.LANDSCAPE_LEFT  -> OverlayView.Zone.LANDSCAPE to 90f
        PhoneState.LANDSCAPE_RIGHT -> OverlayView.Zone.LANDSCAPE to -90f
        PhoneState.INVERTED        -> OverlayView.Zone.INVERTED  to 180f
    }

    private fun applyZone(zone: OverlayView.Zone, msg: String, rotation: Float = 0f, lockAlpha: Boolean = false) {
        currentZone = zone
        overlayView?.setZone(zone, msg, rotation, lockAlpha)
    }

    private fun launchApp(packageName: String) {
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        }
    }

    private fun getAppName(packageName: String): String = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        packageName
    }

    override fun onInterrupt() = dismissOverlay()
    override fun onDestroy()   { super.onDestroy(); dismissOverlay() }
}
