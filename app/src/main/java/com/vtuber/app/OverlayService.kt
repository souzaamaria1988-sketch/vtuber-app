package com.vtuber.app

import android.Manifest
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OverlayService : Service() {

    companion object {
        const val ACTION_TOGGLE_EDIT = "com.vtuber.app.TOGGLE_EDIT"
        const val ACTION_STOP = "com.vtuber.app.STOP"
        const val ACTION_TOGGLE_MIC = "com.vtuber.app.TOGGLE_MIC"
        private const val HANDLE_DP = 14
        private const val MIN_W_DP = 60
        private const val GLOW_PAD_DP = 8
    }

    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var spriteFrame: FrameLayout
    private lateinit var spriteView: ImageView
    private lateinit var glowView: View
    private lateinit var handleTL: View
    private lateinit var handleTR: View
    private lateinit var handleBL: View
    private lateinit var handleBR: View

    private lateinit var params: WindowManager.LayoutParams
    private lateinit var settings: SpriteSettings
    private lateinit var framesList: List<FrameItem>

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var voiceDetector: VoiceDetector? = null

    @Volatile private var detectorPaused = false

    private val idleFrames = mutableListOf<Bitmap>()
    private val talkingFrames = mutableListOf<Bitmap>()
    private var idleIdx = 0
    private var talkIdx = 0

    private var aspect = 1f
    private var spriteW = 0
    private var spriteH = 0
    private var handleR = 0
    private var minW = 0
    private var glowPad = 0
    private var longPressTimeout = 500L
    private var touchSlopSq = 1

    @Volatile private var talking = false
    private var editMode = false
    private var talkLevel = 0f
    private var talkAnimator: ValueAnimator? = null

    private enum class Drag { NONE, SPRITE, TL, TR, BL, BR }
    private var dragging = Drag.NONE
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragStartW = 0
    private var dragStartH = 0
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var downRawX = 0f
    private var downRawY = 0f

    private val longPressRunnable = Runnable { enterEditMode() }
    private val rotationRetry = Runnable { handleRotation() }

    private val frameRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!editMode) {
                if (talking && talkingFrames.isNotEmpty()) {
                    talkIdx = (talkIdx + 1) % talkingFrames.size
                    spriteView.setImageBitmap(talkingFrames[talkIdx])
                } else if (idleFrames.isNotEmpty()) {
                    idleIdx = (idleIdx + 1) % idleFrames.size
                    spriteView.setImageBitmap(idleFrames[idleIdx])
                }
            }
            handler.postDelayed(
                this,
                if (talking) settings.talkingIntervalMs else settings.idleIntervalMs
            )
        }
    }

    private val rotationCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = handleRotationSoon()
        override fun onLowMemory() {}
        override fun onTrimMemory(level: Int) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        if (!startForegroundCompat()) return

        settings = SpriteStore.loadSettings(this)
        framesList = SpriteStore.loadFrames(this)
        if (framesList.isEmpty()) framesList = SpriteStore.fallbackFrames()
        val config = ConfigParser.load(this)
        loadBitmaps(config)

        val base = idleFrames.firstOrNull() ?: talkingFrames.firstOrNull()
            ?: run { stopSelf(); return }

        aspect = base.width.toFloat() / base.height.toFloat()
        handleR = dp(HANDLE_DP)
        minW = dp(MIN_W_DP)
        glowPad = dp(GLOW_PAD_DP)
        longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        val slop = ViewConfiguration.get(this).scaledTouchSlop * 2
        touchSlopSq = slop * slop

        computeInitialGeometry()
        buildUI()
        showOverlay()
        applyTalkLevel(0f)
        handler.postDelayed(frameRunnable, settings.idleIntervalMs)
        startVoice()

        applicationContext.registerComponentCallbacks(rotationCallbacks)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startForegroundCompat()) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_TOGGLE_EDIT -> toggleEditMode()
            ACTION_TOGGLE_MIC -> toggleDetector()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    // ================== IMAGENS ==================
    private fun loadBitmaps(config: VtuberConfig) {
        idleFrames.clear()
        talkingFrames.clear()
        framesList.forEach { f ->
            val crop = if (f.fileName.isBlank()) config.cropTop else 0f
            val bmp = SpriteStore.loadBitmap(this, f, crop) ?: return@forEach
            if (f.category == "talking") talkingFrames.add(bmp) else idleFrames.add(bmp)
        }
    }

    // ================== GEOMETRIA ==================
    private fun computeInitialGeometry() {
        val dm = resources.displayMetrics
        spriteW = (dm.widthPixels * settings.wRatio).toInt().coerceAtLeast(minW)
        spriteH = (spriteW / aspect).toInt()
    }

    private fun computeInitialPosition() {
        val dm = resources.displayMetrics
        val fw = spriteW + 2 * handleR
        val fh = spriteH + 2 * handleR
        params.x = (dm.widthPixels * settings.xRatio).toInt() - fw / 2
        params.y = (dm.heightPixels * settings.yRatio).toInt() - fh / 2
    }

    private fun applyGeometryFromSettings() {
        val dm = resources.displayMetrics
        spriteW = (dm.widthPixels * settings.wRatio).toInt().coerceAtLeast(minW)
        spriteH = (spriteW / aspect).toInt()
        setSpriteSize(spriteW, spriteH)
        params.x = (dm.widthPixels * settings.xRatio).toInt() - (spriteW + 2 * handleR) / 2
        params.y = (dm.heightPixels * settings.yRatio).toInt() - (spriteH + 2 * handleR) / 2
        clampToScreen()
        runCatching { wm.updateViewLayout(root, params) }
    }

    private fun saveAsRatio() {
        val dm = resources.displayMetrics
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) return
        val fw = spriteW + 2 * handleR
        val fh = spriteH + 2 * handleR
        settings.xRatio = ((params.x + fw / 2) / dm.widthPixels.toFloat()).coerceIn(0f, 1f)
        settings.yRatio = ((params.y + fh / 2) / dm.heightPixels.toFloat()).coerceIn(0f, 1f)
        settings.wRatio = spriteW / dm.widthPixels.toFloat()
        SpriteStore.save(this, framesList, settings)
    }

    private fun clampToScreen() {
        val dm = resources.displayMetrics
        val w = spriteW + 2 * handleR
        val h = spriteH + 2 * handleR
        val minVisible = dp(40)
        params.x = params.x.coerceIn(-w + minVisible, dm.widthPixels - minVisible)
        params.y = params.y.coerceIn(-h + minVisible, dm.heightPixels - minVisible)
    }

    private fun setSpriteSize(w: Int, h: Int) {
        spriteW = w
        spriteH = h
        spriteView.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        glowView.layoutParams = FrameLayout.LayoutParams(
            w + 2 * glowPad, h + 2 * glowPad, Gravity.CENTER
        )
        spriteFrame.layoutParams = LinearLayout.LayoutParams(w + 2 * handleR, h + 2 * handleR)
    }

    // ================== UI ==================
    private fun buildUI() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        toolbar = buildToolbar()
        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        spriteFrame = FrameLayout(this).apply { setPadding(handleR, handleR, handleR, handleR) }

        glowView = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0x1AFFFFFF)
                setStroke(dp(3), Color.WHITE)
            }
        }
        spriteFrame.addView(
            glowView,
            FrameLayout.LayoutParams(
                spriteW + 2 * glowPad, spriteH + 2 * glowPad, Gravity.CENTER
            )
        )

        spriteView = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
        }
        spriteFrame.addView(
            spriteView,
            FrameLayout.LayoutParams(spriteW, spriteH, Gravity.CENTER)
        )
        spriteView.setImageBitmap(idleFrames.firstOrNull() ?: talkingFrames.firstOrNull())

        handleTL = makeHandle()
        handleTR = makeHandle()
        handleBL = makeHandle()
        handleBR = makeHandle()

        spriteFrame.addView(handleTL, FrameLayout.LayoutParams(2 * handleR, 2 * handleR, Gravity.TOP or Gravity.START))
        spriteFrame.addView(handleTR, FrameLayout.LayoutParams(2 * handleR, 2 * handleR, Gravity.TOP or Gravity.END))
        spriteFrame.addView(handleBL, FrameLayout.LayoutParams(2 * handleR, 2 * handleR, Gravity.BOTTOM or Gravity.START))
        spriteFrame.addView(handleBR, FrameLayout.LayoutParams(2 * handleR, 2 * handleR, Gravity.BOTTOM or Gravity.END))

        spriteFrame.layoutParams = LinearLayout.LayoutParams(spriteW + 2 * handleR, spriteH + 2 * handleR)
        root.addView(spriteFrame)

        setHandlesVisible(false)
        setupTouches()
    }

    private fun buildToolbar(): LinearLayout {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xEE1D1B20.toInt())
                setStroke(dp(1), 0xFFD0BCFF.toInt())
            }
            visibility = View.GONE
        }

        val studio = makeToolbarBtn("Studio") { openStudio() }
        val reset = makeToolbarBtn("Resetar") { resetPosition() }
        val done = makeToolbarBtn("Pronto") { exitEditMode() }

        t.addView(studio)
        t.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        t.addView(reset)
        t.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        t.addView(done)
        return t
    }

    private fun makeToolbarBtn(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(0xFFEADDFF.toInt())
            textSize = 13f
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFF4F378B.toInt())
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun makeHandle(): View = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(dp(3), 0xFFD0BCFF.toInt())
        }
        visibility = View.GONE
    }

    private fun showOverlay() {
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        computeInitialPosition()
        clampToScreen()

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.addView(root, params)
    }

    private fun openStudio() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        exitEditMode()
    }

    // ================== VOZ / EFEITO DISCORD ==================
    private fun startVoice() {
        scope.launch {
            val detector = VoiceDetector(
                appContext = applicationContext,
                threshold = settings.threshold,
                talkFrames = settings.talkFrames,
                silenceFrames = settings.silenceFrames,
            )
            voiceDetector = detector
            detector.run { talkingNow ->
                handler.post {
                    if (talkingNow != talking) {
                        talking = talkingNow
                        if (talkingNow) {
                            talkIdx = 0
                            talkingFrames.firstOrNull()?.let { spriteView.setImageBitmap(it) }
                        } else {
                            idleIdx = 0
                            idleFrames.firstOrNull()?.let { spriteView.setImageBitmap(it) }
                        }
                        setTalkingVisual(talkingNow)
                    }
                }
            }
        }
    }

    private fun setTalkingVisual(t: Boolean) {
        val target = if (t) 1f else 0f
        if (target == talkLevel) return
        talkAnimator?.cancel()
        val start = talkLevel
        talkAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = 240
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim -> applyTalkLevel(anim.animatedValue as Float) }
            start()
        }
    }

    private fun applyTalkLevel(level: Float) {
        talkLevel = level
        val dens = resources.displayMetrics.density
        spriteView.translationY = (1f - level) * settings.idleOffsetDp * dens
        spriteView.alpha = settings.idleAlpha + (1f - settings.idleAlpha) * level
        val bright = (1f - settings.idleDim) + settings.idleDim * level
        val v = (bright * 255).toInt().coerceIn(0, 255)
        spriteView.colorFilter = PorterDuffColorFilter(
            Color.rgb(v, v, v), PorterDuff.Mode.MULTIPLY
        )
        glowView.alpha = if (settings.glowWhenTalking) level * settings.glowAlpha else 0f
    }

    private fun toggleDetector() {
        if (!::settings.isInitialized) return
        detectorPaused = !detectorPaused
        voiceDetector?.let { if (detectorPaused) it.pause() else it.resume() }
        startForegroundCompat()
    }

    // ================== ROTACAO ==================
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handleRotationSoon()
    }

    private fun handleRotationSoon() {
        if (!::root.isInitialized || !::params.isInitialized || !::wm.isInitialized) return
        handleRotation()
        handler.removeCallbacks(rotationRetry)
        handler.postDelayed(rotationRetry, 350)
    }

    private fun handleRotation() {
        if (!::root.isInitialized || !::params.isInitialized || !::wm.isInitialized) return
        if (!::settings.isInitialized) return
        applyGeometryFromSettings()
    }

    // ================== TOUCH ==================
    private fun setupTouches() {
        spriteView.setOnTouchListener { _, e -> onSpriteTouch(e) }
        handleTL.setOnTouchListener { _, e -> onHandleTouch(Drag.TL, e) }
        handleTR.setOnTouchListener { _, e -> onHandleTouch(Drag.TR, e) }
        handleBL.setOnTouchListener { _, e -> onHandleTouch(Drag.BL, e) }
        handleBR.setOnTouchListener { _, e -> onHandleTouch(Drag.BR, e) }
    }

    private fun onSpriteTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX
                downRawY = e.rawY
                if (!editMode) {
                    handler.postDelayed(longPressRunnable, longPressTimeout)
                } else {
                    beginDrag(Drag.SPRITE, e)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (editMode) {
                    if (dragging == Drag.SPRITE) moveDrag(e)
                    return true
                }
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (dx * dx + dy * dy > touchSlopSq) {
                    handler.removeCallbacks(longPressRunnable)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (editMode && dragging == Drag.SPRITE) endDrag()
                return true
            }
        }
        return false
    }

    private fun onHandleTouch(target: Drag, e: MotionEvent): Boolean {
        if (!editMode) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { beginDrag(target, e); return true }
            MotionEvent.ACTION_MOVE -> { moveDrag(e); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { endDrag(); return true }
        }
        return false
    }

    private fun beginDrag(target: Drag, e: MotionEvent) {
        dragging = target
        dragStartX = params.x
        dragStartY = params.y
        dragStartW = spriteW
        dragStartH = spriteH
        dragStartRawX = e.rawX
        dragStartRawY = e.rawY
    }

    private fun moveDrag(e: MotionEvent) {
        val dx = (e.rawX - dragStartRawX).toInt()
        val dy = (e.rawY - dragStartRawY).toInt()

        when (dragging) {
            Drag.SPRITE -> {
                params.x = dragStartX + dx
                params.y = dragStartY + dy
                clampToScreen()
                wm.updateViewLayout(root, params)
            }
            Drag.TL, Drag.TR, Drag.BL, Drag.BR -> {
                val newW = when (dragging) {
                    Drag.TL, Drag.BL -> dragStartW - dx
                    else -> dragStartW + dx
                }.coerceAtLeast(minW)
                val newH = (newW / aspect).toInt()
                val startH = (dragStartW / aspect).toInt()

                setSpriteSize(newW, newH)

                params.x = when (dragging) {
                    Drag.TL, Drag.BL -> dragStartX + (dragStartW - newW)
                    else -> dragStartX
                }
                params.y = when (dragging) {
                    Drag.TL, Drag.TR -> dragStartY + (startH - newH)
                    else -> dragStartY
                }
                clampToScreen()
                wm.updateViewLayout(root, params)
            }
            Drag.NONE -> {}
        }
    }

    private fun endDrag() {
        dragging = Drag.NONE
        saveAsRatio()
    }

    // ================== EDIT MODE ==================
    private fun enterEditMode() {
        if (editMode) return
        editMode = true
        runCatching { spriteView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
        toolbar.visibility = View.VISIBLE
        setHandlesVisible(true)
        handler.postDelayed({
            if (!editMode || !::params.isInitialized) return@postDelayed
            val th = toolbar.height
            if (th > 0) {
                params.y -= th
                clampToScreen()
                runCatching { wm.updateViewLayout(root, params) }
            }
        }, 80)
    }

    private fun exitEditMode() {
        if (!editMode) return
        editMode = false
        val th = toolbar.height
        toolbar.visibility = View.GONE
        setHandlesVisible(false)
        if (th > 0) params.y += th
        clampToScreen()
        runCatching { wm.updateViewLayout(root, params) }
        saveAsRatio()
    }

    private fun toggleEditMode() {
        if (!::root.isInitialized) return
        if (editMode) exitEditMode() else enterEditMode()
    }

    private fun setHandlesVisible(v: Boolean) {
        val vis = if (v) View.VISIBLE else View.GONE
        handleTL.visibility = vis
        handleTR.visibility = vis
        handleBL.visibility = vis
        handleBR.visibility = vis
    }

    private fun resetPosition() {
        settings.xRatio = 0.5f
        settings.yRatio = 0.82f
        settings.wRatio = 0.55f
        applyGeometryFromSettings()
        SpriteStore.save(this, framesList, settings)
    }

    // ================== NOTIFICACAO / FGS ==================
    private fun startForegroundCompat(): Boolean {
        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !micGranted) {
            stopSelf()
            return false
        }

        val channelId = "vtuber_overlay"
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(channelId) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(channelId, "VTuber", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val editIntent = PendingIntent.getForegroundService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE_EDIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val micIntent = PendingIntent.getForegroundService(
            this, 3,
            Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE_MIC),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getForegroundService(
            this, 2,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle("VTuber ativo")
            .setContentText(
                if (detectorPaused) "Detector pausado — microfone 100% livre"
                else "Toque e segure o personagem para editar"
            )
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Editar", editIntent).build())
            .addAction(
                Notification.Action.Builder(
                    null,
                    if (detectorPaused) "Retomar detector" else "Pausar detector",
                    micIntent
                ).build()
            )
            .addAction(Notification.Action.Builder(null, "Parar", stopIntent).build())
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notif)
        }
        return true
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        runCatching { applicationContext.unregisterComponentCallbacks(rotationCallbacks) }
        handler.removeCallbacksAndMessages(null)
        voiceDetector?.stop()
        scope.cancel()
        if (::root.isInitialized && ::wm.isInitialized) {
            runCatching { wm.removeView(root) }
        }
    }
}
